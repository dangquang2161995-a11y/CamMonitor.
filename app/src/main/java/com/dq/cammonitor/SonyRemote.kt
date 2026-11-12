package com.dq.cammonitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** Thông số máy ảnh đọc được qua PC Remote. Giá trị âm / MIN_VALUE nghĩa là chưa biết. */
class SonyInfo {
    var fNumber = -1
    var shutter = -1L
    var iso = -1L
    var ev = Int.MIN_VALUE
    var whiteBalance = -1
    var battery = -1
    var recording = false

    /** Máy ảnh có báo trạng thái quay hay không (một số đời máy không gửi). */
    var recordingKnown = false
    var wbOptions = IntArray(0)

    fun shutterText(): String {
        if (shutter < 0) return "--"
        if (shutter == 0L) return "Bulb"
        val num = (shutter shr 16).toInt() and 0xFFFF
        val den = (shutter and 0xFFFF).toInt()
        if (den == 0) return "--"
        if (num == 1) return "1/$den"
        if (den == 10) return String.format(Locale.US, "%.1f\"", num / 10f)
        if (den == 1) return "$num\""
        return "$num/$den"
    }

    fun apertureText(): String {
        if (fNumber <= 0) return "f/--"
        return String.format(Locale.US, "f/%.1f", fNumber / 100f)
    }

    fun isoText(): String {
        if (iso < 0) return "ISO --"
        val v = iso and 0xFFFFFF
        return if (v == 0xFFFFFFL) "ISO AUTO" else "ISO $v"
    }

    fun evText(): String {
        if (ev == Int.MIN_VALUE) return ""
        return String.format(Locale.US, "%+.1f EV", ev / 1000f)
    }

    fun summary(): String {
        val sb = StringBuilder()
        sb.append(shutterText()).append("  ").append(apertureText()).append("  ").append(isoText())
        val e = evText()
        if (e.isNotEmpty()) sb.append("  ").append(e)
        if (whiteBalance >= 0) sb.append("  WB ").append(SonyRemote.whiteBalanceName(whiteBalance))
        if (battery >= 0) sb.append("  Pin ").append(battery).append('%')
        return sb.toString()
    }

    /** Chuỗi so sánh để chỉ báo giao diện khi có thay đổi. */
    fun signature(): String {
        return summary() + "|" + recording + "|" + wbOptions.size
    }
}

/**
 * Kết nối máy ảnh Sony ở chế độ PC Remote qua dây USB (giao thức PTP kèm phần mở rộng
 * của Sony): lấy hình xem trực tiếp, đọc thông số và gửi lệnh điều khiển, trong khi
 * máy ảnh vẫn quay/chụp bình thường.
 *
 * Trình tự bắt tay, mã thuộc tính và cách gửi lệnh dựa theo mã nguồn mở libgphoto2.
 * App KHÔNG giành quyền ưu tiên điều khiển, nên nút bấm trên thân máy vẫn hoạt động.
 */
class SonyRemote(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val listener: Listener
) {
    /** Các hàm này được gọi trên luồng riêng của SonyRemote, không phải luồng giao diện. */
    interface Listener {
        fun onRemoteLog(msg: String)
        fun onRemoteFrame(data: ByteArray, offset: Int, length: Int)
        fun onRemoteInfo(info: SonyInfo)
        fun onRemoteStopped(error: String?)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var tid = 0

    private val tx = ByteArray(64)
    // Android 9 trở lên cho đọc khối lớn trong một lần gọi: cả khung hình về trong 1-2 lượt
    // thay vì hàng chục lượt 16 KB, giảm đáng kể thời gian lấy mỗi khung hình.
    private val rx = ByteArray(if (Build.VERSION.SDK_INT >= 28) 1024 * 1024 else 16384)
    private val valueBytes = ByteArray(8)
    private var data = ByteArray(512 * 1024)
    private var dataLen = 0

    private var lastInfoSignature = ""
    private var movieHeld = false
    private var movieToggledAt = 0L
    private var halfReleaseAt = 0L
    private val rejected = HashSet<Int>()

    fun start() {
        if (thread != null) return
        running = true
        val t = Thread({ runSession() }, "SonyRemote")
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(800)
            } catch (e: InterruptedException) {
                // bỏ qua
            }
        }
    }

    // ------------------------------------------------------------ lệnh điều khiển (gọi từ luồng giao diện)

    /** Tăng/giảm một nấc: dùng cho ISO, tốc, khẩu, bù sáng. */
    fun stepValue(prop: Int, up: Boolean) {
        pending.add { control(OC_SONY_CONTROL_B, prop, 1, if (up) 0x01 else 0xFF) }
    }

    fun setWhiteBalance(value: Int) {
        pending.add { control(OC_SONY_SET_A, PROP_WHITE_BALANCE, 2, value) }
    }

    /**
     * Nút quay phim của Sony là kiểu "giữ": gửi 2 (nhấn) để bắt đầu quay,
     * gửi 1 (nhả) để dừng. App đổi trạng thái mỗi lần bấm.
     */
    fun toggleRecord() {
        pending.add {
            val start = !movieHeld
            if (start) {
                control(OC_SONY_CONTROL_B, PROP_MOVIE_BUTTON, 2, 2)
            } else {
                control(OC_SONY_CONTROL_B, PROP_MOVIE_BUTTON, 2, 1)
                // Một số máy coi nút quay là kiểu bấm-để-đổi: nếu nhả mà vẫn đang quay
                // thì bấm thêm một lần nữa để dừng.
                sleepQuietly(700)
                if (transact(OC_SONY_GET_ALL_PROPS) == RC_OK && dataLen > 8) {
                    val check = parseInfo()
                    if (check.recordingKnown && check.recording) {
                        control(OC_SONY_CONTROL_B, PROP_MOVIE_BUTTON, 2, 2)
                        sleepQuietly(200)
                        control(OC_SONY_CONTROL_B, PROP_MOVIE_BUTTON, 2, 1)
                    }
                }
            }
            movieHeld = start
            movieToggledAt = SystemClock.elapsedRealtime()
        }
    }

    /** Nhấn nửa cò khoảng 1,5 giây để máy lấy nét, không chụp. */
    fun focus() {
        pending.add {
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_HALF, 2, 2)
            halfReleaseAt = SystemClock.elapsedRealtime() + 1500
        }
    }

    /** Nhấn nửa cò để lấy nét, nhấn hết để chụp, rồi nhả. */
    fun capturePhoto() {
        pending.add {
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_HALF, 2, 2)
            sleepQuietly(500)
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_FULL, 2, 2)
            sleepQuietly(150)
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_FULL, 2, 1)
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_HALF, 2, 1)
        }
    }

    /** Đặt điểm lấy nét; x trong 0..639, y trong 0..479 (hệ toạ độ của Sony). */
    fun touchFocus(x: Int, y: Int) {
        val cx = x.coerceIn(0, 639)
        val cy = y.coerceIn(0, 479)
        pending.add {
            control(OC_SONY_CONTROL_B, PROP_AF_AREA_POSITION, 4, (cx shl 16) + cy)
            control(OC_SONY_CONTROL_B, PROP_SHUTTER_HALF, 2, 2)
            halfReleaseAt = SystemClock.elapsedRealtime() + 1500
        }
    }

    // ------------------------------------------------------------ phiên làm việc

    private fun runSession() {
        var error: String? = null
        var intf: UsbInterface? = null
        try {
            intf = findPtpInterface(device) ?: throw IOException("Máy ảnh không ở chế độ PC Remote")
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn == null || epOut == null) throw IOException("Không tìm thấy cổng dữ liệu USB")

            val c = usb.openDevice(device) ?: throw IOException("Không mở được thiết bị USB")
            conn = c
            if (!c.claimInterface(intf, true)) throw IOException("Không giành được giao diện USB")

            // 1. Mở phiên PTP
            tid = 0
            val open = transact(OC_OPEN_SESSION, 1)
            if (open != RC_OK && open != RC_SESSION_ALREADY_OPEN) {
                throw IOException("OpenSession bị từ chối, mã 0x" + Integer.toHexString(open))
            }
            listener.onRemoteLog("PTP: đã mở phiên")

            // 2. Bắt tay riêng của Sony
            expectOk("SDIO Connect 1", transact(OC_SONY_CONNECT, 1, 0, 0))
            expectOk("SDIO Connect 2", transact(OC_SONY_CONNECT, 2, 0, 0))
            expectOk("GetExtDeviceInfo", transact(OC_SONY_GET_EXT_INFO, 0x00C8))
            expectOk("SDIO Connect 3", transact(OC_SONY_CONNECT, 3, 0, 0))
            listener.onRemoteLog("Sony: bắt tay xong, đang lấy hình")

            // 3. Vòng lặp: lấy ảnh live view, chạy lệnh đang chờ, đọc thông số định kỳ
            var misses = 0
            var warned = false
            var nextInfoAt = 0L
            while (running) {
                while (true) {
                    val job = pending.poll() ?: break
                    job()
                }
                val now = SystemClock.elapsedRealtime()
                if (halfReleaseAt != 0L && now >= halfReleaseAt) {
                    halfReleaseAt = 0L
                    control(OC_SONY_CONTROL_B, PROP_SHUTTER_HALF, 2, 1)
                }
                if (now >= nextInfoAt) {
                    nextInfoAt = now + 1000
                    pollInfo()
                }

                val rc = transact(OC_GET_OBJECT, LIVEVIEW_HANDLE)
                var delivered = false
                if (rc == RC_OK && dataLen > 16) {
                    val start = findJpegStart()
                    if (start >= 0) {
                        var len = dataLen - start
                        val declared = le32(data, 4)
                        if (start == le32(data, 0) && declared > 0 && start + declared <= dataLen) {
                            len = declared
                        }
                        listener.onRemoteFrame(data, start, len)
                        delivered = true
                    }
                }
                if (delivered) {
                    misses = 0
                } else {
                    // Máy ảnh trả AccessDenied khi chưa có khung hình mới
                    misses++
                    if (misses > 150 && !warned) {
                        warned = true
                        listener.onRemoteLog("Máy ảnh chưa trả hình (mã 0x" + Integer.toHexString(rc) + ")")
                    }
                    sleepQuietly(6)
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            val c = conn
            if (c != null) {
                if (error == null) {
                    try {
                        transact(OC_CLOSE_SESSION)
                    } catch (e: Exception) {
                        // máy ảnh có thể đã bị rút dây
                    }
                }
                try {
                    if (intf != null) c.releaseInterface(intf)
                } catch (e: Exception) {
                    // bỏ qua
                }
                c.close()
            }
            conn = null
            running = false
            pending.clear()
            listener.onRemoteStopped(error)
        }
    }

    private fun expectOk(step: String, rc: Int) {
        if (rc != RC_OK) {
            throw IOException(step + " thất bại, mã 0x" + Integer.toHexString(rc))
        }
    }

    /** Gửi một giá trị điều khiển (1, 2 hoặc 4 byte) cho máy ảnh. */
    private fun control(opcode: Int, prop: Int, size: Int, value: Int) {
        for (i in 0 until size) {
            valueBytes[i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        val rc = exchange(opcode, intArrayOf(prop), valueBytes, size)
        if (rc != RC_OK && rejected.add(prop)) {
            listener.onRemoteLog(
                "Máy ảnh từ chối lệnh 0x" + Integer.toHexString(prop) +
                    " (mã 0x" + Integer.toHexString(rc) + ")"
            )
        }
    }

    // ------------------------------------------------------------ đọc thông số

    private fun pollInfo() {
        val rc = transact(OC_SONY_GET_ALL_PROPS)
        if (rc != RC_OK || dataLen <= 8) return
        val info = parseInfo()
        // Đồng bộ trạng thái nút quay với máy ảnh (chờ 2 giây sau lần bấm gần nhất)
        if (info.recordingKnown && SystemClock.elapsedRealtime() - movieToggledAt > 2000) {
            movieHeld = info.recording
        }
        val sig = info.signature()
        if (sig != lastInfoSignature) {
            lastInfoSignature = sig
            listener.onRemoteInfo(info)
        }
    }

    /** Kích thước (byte) của kiểu số PTP, 0 nếu không phải kiểu số đơn. */
    private fun numericSize(type: Int): Int {
        return when (type) {
            1, 2 -> 1
            3, 4 -> 2
            5, 6 -> 4
            7, 8 -> 8
            9, 10 -> 16
            else -> 0
        }
    }

    /** Vị trí ngay sau một giá trị kiểu [type] bắt đầu tại [off], hoặc -1 nếu dữ liệu hỏng. */
    private fun skipValue(off: Int, type: Int, end: Int): Int {
        val size = numericSize(type)
        if (size > 0) {
            return if (off + size <= end) off + size else -1
        }
        if (type == 0xFFFF) {
            // Chuỗi: 1 byte số ký tự, mỗi ký tự 2 byte
            if (off + 1 > end) return -1
            val n = data[off].toInt() and 0xFF
            val next = off + 1 + n * 2
            return if (next <= end) next else -1
        }
        if (type and 0x4000 != 0) {
            val elem = numericSize(type and 0x3FFF)
            if (elem == 0 || off + 4 > end) return -1
            val n = le32(data, off)
            if (n < 0 || n > 100000) return -1
            val next = off + 4 + n * elem
            return if (next <= end) next else -1
        }
        return -1
    }

    private fun readNumber(off: Int, type: Int): Long {
        return when (type) {
            1 -> data[off].toLong()
            2 -> (data[off].toInt() and 0xFF).toLong()
            3 -> le16(data, off).toShort().toLong()
            4 -> le16(data, off).toLong()
            5 -> le32(data, off).toLong()
            6 -> le32(data, off).toLong() and 0xFFFFFFFFL
            else -> 0L
        }
    }

    /** Phân tích khối "tất cả thuộc tính" của Sony, chỉ giữ lại những mục app cần. */
    private fun parseInfo(): SonyInfo {
        val info = SonyInfo()
        val end = dataLen
        var off = 8
        while (off + 6 <= end) {
            val code = le16(data, off)
            val type = le16(data, off + 2)
            off += 6

            // Giá trị mặc định rồi giá trị hiện tại
            val curOff = skipValue(off, type, end)
            if (curOff < 0) break
            val afterCur = skipValue(curOff, type, end)
            if (afterCur < 0) break
            val size = numericSize(type)
            val current = if (size in 1..4) readNumber(curOff, type) else 0L
            off = afterCur
            if (off + 1 > end) break
            val form = data[off].toInt() and 0xFF
            off += 1

            var options: IntArray? = null
            if (form == 1) {
                // Dạng khoảng: nhỏ nhất, lớn nhất, bước
                var o = off
                for (i in 0 until 3) {
                    o = skipValue(o, type, end)
                    if (o < 0) break
                }
                if (o < 0) break
                off = o
            } else if (form == 2) {
                // Dạng danh sách
                if (off + 2 > end) break
                var n = le16(data, off)
                off += 2
                var listOff = off
                var o = off
                var bad = false
                for (i in 0 until n) {
                    o = skipValue(o, type, end)
                    if (o < 0) {
                        bad = true
                        break
                    }
                }
                if (bad) break
                off = o
                // Máy đời mới gửi thêm danh sách thứ hai (các giá trị thật sự chọn được)
                if (off + 2 <= end) {
                    val peek = le16(data, off)
                    if (peek < 0x200) {
                        var o2 = off + 2
                        var bad2 = false
                        for (i in 0 until peek) {
                            o2 = skipValue(o2, type, end)
                            if (o2 < 0) {
                                bad2 = true
                                break
                            }
                        }
                        if (bad2) break
                        if (peek > 0) {
                            n = peek
                            listOff = off + 2
                        }
                        off = o2
                    }
                }
                if (code == PROP_WHITE_BALANCE && size in 1..4) {
                    val list = IntArray(n)
                    for (i in 0 until n) list[i] = readNumber(listOff + i * size, type).toInt()
                    options = list
                }
            }

            when (code) {
                PROP_F_NUMBER -> info.fNumber = current.toInt()
                PROP_SHUTTER -> info.shutter = current
                PROP_ISO -> info.iso = current
                PROP_EXPOSURE_COMP -> info.ev = current.toInt()
                PROP_WHITE_BALANCE -> {
                    info.whiteBalance = current.toInt()
                    if (options != null) info.wbOptions = options
                }
                PROP_BATTERY -> info.battery = current.toInt()
                PROP_RECORDING_STATE -> {
                    info.recording = current == 1L
                    info.recordingKnown = true
                }
            }
        }
        return info
    }

    // ------------------------------------------------------------ tầng PTP

    /** Vị trí bắt đầu ảnh JPEG trong khối dữ liệu live view, hoặc -1. */
    private fun findJpegStart(): Int {
        // 4 byte đầu thường là độ lệch tới ảnh JPEG
        val offset = le32(data, 0)
        if (offset > 0 && offset + 1 < dataLen &&
            data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte()
        ) {
            return offset
        }
        var i = 8
        while (i + 1 < dataLen) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) return i
            i++
        }
        return -1
    }

    private fun transact(code: Int, vararg params: Int): Int {
        return exchange(code, params, null, 0)
    }

    /**
     * Một giao dịch PTP: gửi lệnh, gửi khối dữ liệu [out] nếu có, nhận khối dữ liệu
     * trả về (nếu có) vào [data]/[dataLen], rồi trả về mã phản hồi.
     */
    private fun exchange(code: Int, params: IntArray, out: ByteArray?, outLen: Int): Int {
        val c = conn ?: throw IOException("Chưa kết nối")
        val epO = epOut ?: throw IOException("Chưa kết nối")
        val epI = epIn ?: throw IOException("Chưa kết nối")

        val id = tid
        tid++
        val len = 12 + 4 * params.size
        putLe32(tx, 0, len)
        putLe16(tx, 4, TYPE_COMMAND)
        putLe16(tx, 6, code)
        putLe32(tx, 8, id)
        for (i in params.indices) putLe32(tx, 12 + 4 * i, params[i])
        if (c.bulkTransfer(epO, tx, len, 2000) != len) throw IOException("Gửi lệnh USB thất bại")

        if (out != null) {
            val total = 12 + outLen
            putLe32(tx, 0, total)
            putLe16(tx, 4, TYPE_DATA)
            putLe16(tx, 6, code)
            putLe32(tx, 8, id)
            System.arraycopy(out, 0, tx, 12, outLen)
            if (c.bulkTransfer(epO, tx, total, 2000) != total) throw IOException("Gửi dữ liệu USB thất bại")
        }

        dataLen = 0
        var emptyReads = 0
        var response = -1
        while (response < 0) {
            val n = c.bulkTransfer(epI, rx, rx.size, 3000)
            if (n < 0) throw IOException("Máy ảnh không phản hồi")
            if (n == 0) {
                emptyReads++
                if (emptyReads > 5) throw IOException("Máy ảnh không phản hồi")
                continue
            }
            if (n < 12) throw IOException("Gói USB không hợp lệ")
            val total = le32(rx, 0)
            val type = le16(rx, 4)
            if (type == TYPE_RESPONSE) {
                response = le16(rx, 6)
                continue
            }
            if (type != TYPE_DATA) continue

            val payload = total - 12
            if (payload < 0 || payload > MAX_PAYLOAD) throw IOException("Khối dữ liệu quá lớn")
            if (data.size < payload) data = ByteArray(payload + 64 * 1024)
            var got = n - 12
            if (got > payload) got = payload
            System.arraycopy(rx, 12, data, 0, got)
            while (got < payload) {
                val want = minOf(payload - got, rx.size)
                val m = c.bulkTransfer(epI, data, got, want, 3000)
                if (m < 0) throw IOException("Đứt kết nối khi nhận hình")
                got += m
            }
            dataLen = payload
            // Tiếp tục vòng lặp để đọc gói phản hồi
        }
        return response
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // bỏ qua
        }
    }

    companion object {
        const val SONY_VENDOR_ID = 0x054C

        // Mã thuộc tính
        const val PROP_WHITE_BALANCE = 0x5005
        const val PROP_F_NUMBER = 0x5007
        const val PROP_EXPOSURE_COMP = 0x5010
        const val PROP_SHUTTER = 0xD20D
        const val PROP_BATTERY = 0xD218
        const val PROP_RECORDING_STATE = 0xD21D
        const val PROP_ISO = 0xD21E
        private const val PROP_SHUTTER_HALF = 0xD2C1
        private const val PROP_SHUTTER_FULL = 0xD2C2
        private const val PROP_MOVIE_BUTTON = 0xD2C8
        private const val PROP_AF_AREA_POSITION = 0xD2DC

        private const val TYPE_COMMAND = 1
        private const val TYPE_DATA = 2
        private const val TYPE_RESPONSE = 3

        private const val OC_OPEN_SESSION = 0x1002
        private const val OC_CLOSE_SESSION = 0x1003
        private const val OC_GET_OBJECT = 0x1009
        private const val OC_SONY_CONNECT = 0x9201
        private const val OC_SONY_GET_EXT_INFO = 0x9202
        private const val OC_SONY_SET_A = 0x9205
        private const val OC_SONY_CONTROL_B = 0x9207
        private const val OC_SONY_GET_ALL_PROPS = 0x9209

        private const val RC_OK = 0x2001
        private const val RC_SESSION_ALREADY_OPEN = 0x201E

        /** 0xFFFFC002: "đối tượng" ảo chứa khung hình live view */
        private const val LIVEVIEW_HANDLE = -16382

        private const val MAX_PAYLOAD = 16 * 1024 * 1024

        fun whiteBalanceName(value: Int): String {
            return when (value) {
                0x0002 -> "Tự động"
                0x0004 -> "Nắng"
                0x0006 -> "Đèn sợi đốt"
                0x0007 -> "Flash"
                0x8001 -> "Huỳnh quang ấm"
                0x8002 -> "Huỳnh quang lạnh"
                0x8003 -> "Huỳnh quang trắng"
                0x8004 -> "Huỳnh quang ngày"
                0x8010 -> "Mây"
                0x8011 -> "Bóng râm"
                0x8012 -> "Nhiệt độ màu"
                0x8020 -> "Tùy chỉnh 1"
                0x8021 -> "Tùy chỉnh 2"
                0x8022 -> "Tùy chỉnh 3"
                0x8023 -> "Tùy chỉnh"
                0x8030 -> "Dưới nước"
                else -> "0x" + Integer.toHexString(value)
            }
        }

        /** Giao diện PTP (lớp Still Image = 6) của thiết bị, hoặc null. */
        fun findPtpInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                if (intf.interfaceClass == 6) return intf
            }
            return null
        }

        fun isSonyRemote(d: UsbDevice): Boolean {
            return d.vendorId == SONY_VENDOR_ID && findPtpInterface(d) != null
        }

        private fun le16(b: ByteArray, o: Int): Int {
            return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        }

        private fun le32(b: ByteArray, o: Int): Int {
            return (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
        }

        private fun putLe16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putLe32(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte()
            b[o + 1] = ((v shr 8) and 0xFF).toByte()
            b[o + 2] = ((v shr 16) and 0xFF).toByte()
            b[o + 3] = ((v shr 24) and 0xFF).toByte()
        }
    }
}
