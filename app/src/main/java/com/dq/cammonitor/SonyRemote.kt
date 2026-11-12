package com.dq.cammonitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Lấy hình xem trực tiếp từ máy ảnh Sony ở chế độ PC Remote qua dây USB (giao thức PTP
 * kèm phần mở rộng của Sony). Ở chế độ này máy ảnh vẫn quay/chụp bình thường.
 *
 * Trình tự bắt tay và địa chỉ ảnh live view (0xFFFFC002) dựa theo mã nguồn mở libgphoto2.
 * App KHÔNG giành quyền ưu tiên điều khiển, nên nút bấm trên thân máy vẫn hoạt động.
 */
class SonyRemote(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val listener: Listener
) {
    interface Listener {
        /** Các hàm này được gọi trên luồng riêng của SonyRemote, không phải luồng giao diện. */
        fun onRemoteLog(msg: String)
        fun onRemoteFrame(data: ByteArray, offset: Int, length: Int)
        fun onRemoteStopped(error: String?)
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var tid = 0

    private val tx = ByteArray(64)
    private val rx = ByteArray(16384)
    private var data = ByteArray(512 * 1024)
    private var dataLen = 0

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

            // 3. Vòng lặp lấy ảnh live view
            var misses = 0
            var warned = false
            while (running) {
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
                    sleepQuietly(20)
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
            listener.onRemoteStopped(error)
        }
    }

    private fun expectOk(step: String, rc: Int) {
        if (rc != RC_OK) {
            throw IOException(step + " thất bại, mã 0x" + Integer.toHexString(rc))
        }
    }

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

    /**
     * Gửi một lệnh PTP, nhận khối dữ liệu (nếu có) vào [data]/[dataLen],
     * trả về mã phản hồi.
     */
    private fun transact(code: Int, vararg params: Int): Int {
        val c = conn ?: throw IOException("Chưa kết nối")
        val out = epOut ?: throw IOException("Chưa kết nối")
        val inp = epIn ?: throw IOException("Chưa kết nối")

        val len = 12 + 4 * params.size
        putLe32(tx, 0, len)
        putLe16(tx, 4, TYPE_COMMAND)
        putLe16(tx, 6, code)
        putLe32(tx, 8, tid)
        for (i in params.indices) putLe32(tx, 12 + 4 * i, params[i])
        tid++
        val sent = c.bulkTransfer(out, tx, len, 2000)
        if (sent != len) throw IOException("Gửi lệnh USB thất bại")

        dataLen = 0
        var emptyReads = 0
        var response = -1
        while (response < 0) {
            val n = c.bulkTransfer(inp, rx, rx.size, 3000)
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
                val want = minOf(payload - got, 16384)
                val m = c.bulkTransfer(inp, data, got, want, 3000)
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

        private const val TYPE_COMMAND = 1
        private const val TYPE_DATA = 2
        private const val TYPE_RESPONSE = 3

        private const val OC_OPEN_SESSION = 0x1002
        private const val OC_CLOSE_SESSION = 0x1003
        private const val OC_GET_OBJECT = 0x1009
        private const val OC_SONY_CONNECT = 0x9201
        private const val OC_SONY_GET_EXT_INFO = 0x9202

        private const val RC_OK = 0x2001
        private const val RC_SESSION_ALREADY_OPEN = 0x201E

        /** 0xFFFFC002: "đối tượng" ảo chứa khung hình live view */
        private const val LIVEVIEW_HANDLE = -16382

        private const val MAX_PAYLOAD = 16 * 1024 * 1024

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
