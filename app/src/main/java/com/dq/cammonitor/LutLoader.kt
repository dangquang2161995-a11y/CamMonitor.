package com.dq.cammonitor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Đọc file LUT 3D định dạng .cube và chuyển thành ảnh 2D (các lát xếp ngang)
 * để shader OpenGL ES 2.0 tra cứu được.
 */
object LutLoader {

    /** rgba có kích thước (size*size) x size điểm ảnh, 4 byte mỗi điểm. */
    class Lut(val size: Int, val rgba: ByteBuffer)

    private const val MAX_DIRECT = 64
    private const val RESAMPLED = 33

    fun parse(text: String): Lut {
        val space = Regex("\\s+")
        var n = 0
        var data: FloatArray? = null
        var count = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            if (line[0].isLetter()) {
                val parts = line.split(space)
                val key = parts[0].uppercase()
                if (key == "LUT_3D_SIZE" && parts.size >= 2) {
                    n = parts[1].toInt()
                    if (n < 2 || n > 129) throw IllegalArgumentException("Kích thước LUT không hợp lệ: $n")
                    data = FloatArray(n * n * n * 3)
                } else if (key == "LUT_1D_SIZE") {
                    throw IllegalArgumentException("Đây là LUT 1D, app chỉ hỗ trợ LUT 3D")
                }
                continue
            }
            val d = data ?: throw IllegalArgumentException("File thiếu dòng LUT_3D_SIZE")
            val parts = line.split(space)
            if (parts.size < 3) continue
            if (count + 3 > d.size) break
            d[count] = parts[0].toFloat()
            d[count + 1] = parts[1].toFloat()
            d[count + 2] = parts[2].toFloat()
            count += 3
        }

        val d = data ?: throw IllegalArgumentException("Không phải file .cube 3D")
        if (count != d.size) throw IllegalArgumentException("File .cube thiếu dữ liệu")

        // LUT quá lớn thì lấy mẫu lại cho vừa texture
        val m = if (n > MAX_DIRECT) RESAMPLED else n
        val buf = ByteBuffer.allocateDirect(m * m * m * 4).order(ByteOrder.nativeOrder())
        val width = m * m
        for (b in 0 until m) {
            val sb = srcIndex(b, m, n)
            for (g in 0 until m) {
                val sg = srcIndex(g, m, n)
                for (r in 0 until m) {
                    val sr = srcIndex(r, m, n)
                    // Trong .cube, kênh đỏ thay đổi nhanh nhất
                    val src = (sr + sg * n + sb * n * n) * 3
                    val dst = (g * width + (r + b * m)) * 4
                    buf.put(dst, toByte(d[src]))
                    buf.put(dst + 1, toByte(d[src + 1]))
                    buf.put(dst + 2, toByte(d[src + 2]))
                    buf.put(dst + 3, 255.toByte())
                }
            }
        }
        buf.position(0)
        return Lut(m, buf)
    }

    private fun srcIndex(i: Int, m: Int, n: Int): Int {
        if (m == n) return i
        return Math.round(i.toFloat() * (n - 1) / (m - 1))
    }

    private fun toByte(v: Float): Byte {
        val c = if (v < 0f) 0f else if (v > 1f) 1f else v
        return (c * 255f + 0.5f).toInt().toByte()
    }
}
