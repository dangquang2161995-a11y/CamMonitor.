package com.dq.cammonitor

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Máy chủ web nhỏ chạy ngay trong app để máy thứ hai (điện thoại, máy tính bảng, laptop)
 * cùng mạng Wi-Fi mở trình duyệt xem hình và bấm điều khiển.
 *
 * Hình phát đi là chính các ảnh JPEG nhận từ máy ảnh (dạng MJPEG), không mã hoá lại nên
 * gần như không tốn thêm sức máy. Mọi địa chỉ đều cần đúng mã [pin] mới được phục vụ.
 */
class WebServer(
    private val port: Int,
    private val pin: String,
    private val listener: Listener
) {
    /** Các hàm này được gọi trên luồng mạng, không phải luồng giao diện. */
    interface Listener {
        /** Thực hiện một lệnh điều khiển; trả về câu báo kết quả để hiện trên máy phụ. */
        fun onWebCommand(action: String): String

        /** Dòng thông số hiện trên máy phụ, làm mới mỗi giây. */
        fun onWebInfo(): String
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    private val viewers = AtomicInteger(0)

    private val frameLock = Object()
    private var frame = ByteArray(0)
    private var frameLen = 0
    private var frameSeq = 0L

    /** Số máy đang xem hình. */
    fun viewerCount(): Int = viewers.get()

    fun start(): Boolean {
        if (running) return true
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            server = s
        } catch (e: IOException) {
            return false
        }
        running = true
        Thread({ acceptLoop() }, "WebServer").start()
        return true
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
            // bỏ qua
        }
        server = null
        val copy: Array<Socket>
        synchronized(sockets) { copy = sockets.toTypedArray() }
        for (s in copy) closeQuietly(s)
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    /** Đưa khung hình mới nhất cho các máy đang xem. Không có ai xem thì không làm gì. */
    fun publishFrame(data: ByteArray, offset: Int, length: Int) {
        if (viewers.get() == 0) return
        synchronized(frameLock) {
            if (frame.size < length) frame = ByteArray(length + 64 * 1024)
            System.arraycopy(data, offset, frame, 0, length)
            frameLen = length
            frameSeq++
            frameLock.notifyAll()
        }
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (running) {
            val client: Socket
            try {
                client = s.accept()
            } catch (e: IOException) {
                break
            }
            if (sockets.size >= MAX_CLIENTS) {
                closeQuietly(client)
                continue
            }
            sockets.add(client)
            Thread({
                try {
                    handle(client)
                } catch (e: Exception) {
                    // máy phụ ngắt kết nối
                } finally {
                    sockets.remove(client)
                    closeQuietly(client)
                }
            }, "WebClient").start()
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 8000
        sock.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val out = sock.getOutputStream()
        val line = reader.readLine() ?: return
        // Đọc bỏ phần đầu yêu cầu
        var headers = 0
        while (headers < 100) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            headers++
        }
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            sendText(out, "405 Method Not Allowed", "Chỉ hỗ trợ GET")
            return
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (pair in target.substring(q + 1).split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) query[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
        }
        if (path == "/favicon.ico") {
            sendText(out, "404 Not Found", "")
            return
        }
        if (query["k"] != pin) {
            sendText(out, "403 Forbidden", "Sai mã truy cập. Hãy mở đúng địa chỉ đang hiện trên máy chính.")
            return
        }
        when (path) {
            "/" -> send(out, "200 OK", "text/html; charset=utf-8", PAGE.replace("@K@", pin).toByteArray(Charsets.UTF_8))
            "/stream" -> stream(out)
            "/cmd" -> {
                val action = query["a"] ?: ""
                val safe = action.length in 1..24 && action.all { it.isLetterOrDigit() || it == '_' }
                sendText(out, "200 OK", if (safe) listener.onWebCommand(action) else "Lệnh không hợp lệ")
            }
            "/info" -> sendText(out, "200 OK", listener.onWebInfo())
            else -> sendText(out, "404 Not Found", "Không có trang này")
        }
    }

    private fun stream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.flush()
        viewers.incrementAndGet()
        try {
            var last = -1L
            var buf = ByteArray(0)
            while (running) {
                var len = 0
                synchronized(frameLock) {
                    if (frameSeq == last) {
                        try {
                            frameLock.wait(1000)
                        } catch (e: InterruptedException) {
                            // bỏ qua
                        }
                    }
                    if (frameSeq != last && frameLen > 0) {
                        if (buf.size < frameLen) buf = ByteArray(frame.size)
                        System.arraycopy(frame, 0, buf, 0, frameLen)
                        len = frameLen
                        last = frameSeq
                    }
                }
                if (len > 0) {
                    out.write(
                        ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + len + "\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    out.write(buf, 0, len)
                    out.write(CRLF)
                    out.flush()
                }
            }
        } finally {
            viewers.decrementAndGet()
        }
    }

    private fun sendText(out: OutputStream, status: String, text: String) {
        send(out, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }

    private fun send(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.size + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(body)
        out.flush()
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (e: IOException) {
            // bỏ qua
        }
    }

    companion object {
        private const val MAX_CLIENTS = 8
        private val CRLF = byteArrayOf(13, 10)

        private const val PAGE = """<!doctype html>
<html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cam Monitor</title>
<style>
body{margin:0;background:#000;color:#eee;font-family:sans-serif}
img{width:100%;max-height:72vh;object-fit:contain;display:block;background:#111;min-height:120px}
#i{padding:6px 10px;font-size:14px;white-space:pre-line}
#m{padding:0 10px;color:#fc6;min-height:1.3em;font-size:13px}
.b{display:flex;flex-wrap:wrap;gap:6px;padding:8px}
button{flex:1 0 22%;padding:13px 4px;font-size:15px;background:#333;color:#fff;border:0;border-radius:6px}
button:active{background:#666}
button.r{background:#a22}
</style></head><body>
<img src="/stream?k=@K@" alt="Chưa có hình từ máy chính">
<div id="i">Đang kết nối…</div>
<div id="m"></div>
<div class="b">
<button class="r" onclick="c('rec')">Quay / Dừng</button>
<button onclick="c('photo')">Chụp ảnh</button>
<button onclick="c('focus')">Lấy nét</button>
<button onclick="c('iso_down')">ISO −</button>
<button onclick="c('iso_up')">ISO +</button>
<button onclick="c('ss_down')">Tốc −</button>
<button onclick="c('ss_up')">Tốc +</button>
<button onclick="c('av_down')">Khẩu −</button>
<button onclick="c('av_up')">Khẩu +</button>
<button onclick="c('ev_down')">EV −</button>
<button onclick="c('ev_up')">EV +</button>
<button onclick="c('near3')">Nét ◀◀</button>
<button onclick="c('near1')">Nét ◀</button>
<button onclick="c('far1')">Nét ▶</button>
<button onclick="c('far3')">Nét ▶▶</button>
</div>
<script>
var k='@K@';
function show(id,t){document.getElementById(id).textContent=t}
function c(a){fetch('/cmd?k='+k+'&a='+a).then(function(r){return r.text()}).then(function(t){show('m',t)}).catch(function(){show('m','Không gửi được lệnh')})}
setInterval(function(){fetch('/info?k='+k).then(function(r){return r.text()}).then(function(t){show('i',t)}).catch(function(){show('i','Mất kết nối với máy chính')})},1000);
</script></body></html>"""
    }
}
