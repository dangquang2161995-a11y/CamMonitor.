package com.dq.cammonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs

/**
 * Lớp phủ vẽ bằng Canvas phía trên hình: khung tỉ lệ, lưới, ảnh mẫu, thước cân bằng,
 * histogram, waveform, vectorscope, viền REC và chú thích false color.
 */
class OverlayView(context: Context, private val state: MonitorState) : View(context) {

    private val density = resources.displayMetrics.density

    @Volatile var videoW = 0
    @Volatile var videoH = 0

    /** Ảnh thu nhỏ RGBA từ renderer (hàng 0 là đáy hình), dùng cho các biểu đồ. */
    private var analysis: ByteArray? = null
    private var analysisW = 0
    private var analysisH = 0

    /** Ảnh mẫu chồng lên hình. fromSource = ảnh lấy từ máy ảnh (cần xoay theo hình). */
    var onionBitmap: Bitmap? = null
    var onionFromSource = false

    /** Góc nghiêng (độ); levelValid = false khi điện thoại nằm ngửa. */
    var levelAngle = 0f
    var levelValid = false

    var cameraRecording = false
    var previewRecording = false
    var locked = false

    private var focusX = 0f
    private var focusY = 0f
    private var focusUntil = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(200, 255, 255, 255)
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = Color.argb(120, 255, 255, 255)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(255, 255, 220, 0)
    }
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.argb(255, 235, 30, 30)
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val dimPaint = Paint()
    private val scopeBgPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val onionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint()
    private val path = Path()
    private val img = RectF()
    private val dst = RectF()

    private val bins = IntArray(HIST_BINS * 4)
    private val waveCounts = IntArray(WAVE_W * WAVE_H)
    private val wavePixels = IntArray(WAVE_W * WAVE_H)
    private val waveBitmap = Bitmap.createBitmap(WAVE_W, WAVE_H, Bitmap.Config.ARGB_8888)
    private val vecCounts = IntArray(VEC_SIZE * VEC_SIZE)
    private val vecPixels = IntArray(VEC_SIZE * VEC_SIZE)
    private val vecBitmap = Bitmap.createBitmap(VEC_SIZE, VEC_SIZE, Bitmap.Config.ARGB_8888)

    /** Gọi trên luồng giao diện. */
    fun setAnalysis(pixels: ByteArray?, w: Int, h: Int) {
        analysis = pixels
        analysisW = w
        analysisH = h
        invalidate()
    }

    /** Đánh dấu điểm vừa chạm lấy nét (toạ độ màn hình). */
    fun showFocusPoint(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusUntil = SystemClock.elapsedRealtime() + 1500
        invalidate()
        postInvalidateDelayed(1600)
    }

    /** Vùng ảnh đang hiển thị trong view (đã tính letterbox, de-squeeze và xoay). */
    fun imageRect(out: RectF) {
        val fit = MonitorState.fit(width, height, state.displayAspect(videoW, videoH))
        val w = width * fit[0]
        val h = height * fit[1]
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        out.set(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        imageRect(img)

        // Ảnh mẫu, khung và lưới chỉ khớp khi không zoom
        if (state.zoom == 0) {
            drawOnion(canvas)
            drawFrameGuide(canvas)
            drawGrid(canvas)
        }
        if (state.level) drawLevel(canvas)
        drawScopes(canvas)
        if (state.mode == 1) drawFalseColorLegend(canvas)
        drawFocusPoint(canvas)
        drawRecording(canvas)
        if (locked) {
            val msg = "Đã khóa cảm ứng. Giữ 1 giây để mở khóa."
            canvas.drawText(msg, (width - textPaint.measureText(msg)) / 2f, height - 14f * density, textPaint)
        }
    }

    // ---------------------------------------------------------------- bố cục

    private fun drawOnion(canvas: Canvas) {
        val bmp = onionBitmap ?: return
        val level = state.onion
        if (level <= 0 || bmp.isRecycled) return
        onionPaint.alpha = ONION_ALPHA[level.coerceIn(0, 3)]
        if (onionFromSource) {
            // Ảnh lấy từ máy ảnh: xoay cùng góc với hình đang hiển thị
            val rot = state.rotation and 3
            val w = if (rot % 2 == 1) img.height() else img.width()
            val h = if (rot % 2 == 1) img.width() else img.height()
            canvas.save()
            canvas.rotate(90f * rot, img.centerX(), img.centerY())
            dst.set(img.centerX() - w / 2f, img.centerY() - h / 2f, img.centerX() + w / 2f, img.centerY() + h / 2f)
            canvas.drawBitmap(bmp, null, dst, onionPaint)
            canvas.restore()
        } else {
            // Ảnh người dùng chọn: đặt vừa trong vùng ảnh, giữ tỉ lệ
            val a = bmp.width.toFloat() / bmp.height.toFloat()
            var w = img.width()
            var h = w / a
            if (h > img.height()) {
                h = img.height()
                w = h * a
            }
            dst.set(img.centerX() - w / 2f, img.centerY() - h / 2f, img.centerX() + w / 2f, img.centerY() + h / 2f)
            canvas.drawBitmap(bmp, null, dst, onionPaint)
        }
    }

    private fun drawFrameGuide(canvas: Canvas) {
        val target = state.frameRatio()
        if (target <= 0f) return
        val imgAspect = img.width() / img.height()
        val fw: Float
        val fh: Float
        if (target < imgAspect) {
            fh = img.height()
            fw = fh * target
        } else {
            fw = img.width()
            fh = fw / target
        }
        val l = img.centerX() - fw / 2f
        val t = img.centerY() - fh / 2f
        val r = l + fw
        val b = t + fh
        val alpha = MonitorState.DIM_ALPHA[state.dim.coerceIn(0, MonitorState.DIM_ALPHA.size - 1)]
        if (alpha > 0) {
            dimPaint.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawRect(img.left, img.top, img.right, t, dimPaint)
            canvas.drawRect(img.left, b, img.right, img.bottom, dimPaint)
            canvas.drawRect(img.left, t, l, b, dimPaint)
            canvas.drawRect(r, t, img.right, b, dimPaint)
        }
        canvas.drawRect(l, t, r, b, framePaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val g = state.grid
        if (g == 1 || g == 3) {
            for (i in 1..2) {
                val x = img.left + img.width() * i / 3f
                val y = img.top + img.height() * i / 3f
                canvas.drawLine(x, img.top, x, img.bottom, linePaint)
                canvas.drawLine(img.left, y, img.right, y, linePaint)
            }
        }
        if (g == 2 || g == 3) {
            val c = 14f * density
            canvas.drawLine(img.centerX() - c, img.centerY(), img.centerX() + c, img.centerY(), linePaint)
            canvas.drawLine(img.centerX(), img.centerY() - c, img.centerX(), img.centerY() + c, linePaint)
            // Vùng an toàn 90%
            val dx = img.width() * 0.05f
            val dy = img.height() * 0.05f
            canvas.drawRect(img.left + dx, img.top + dy, img.right - dx, img.bottom - dy, linePaint)
        }
    }

    private fun drawLevel(canvas: Canvas) {
        if (!levelValid) return
        val cx = img.centerX()
        val cy = img.centerY()
        val half = minOf(img.width(), img.height()) * 0.28f
        val flat = abs(levelAngle) < 0.8f
        levelPaint.color = if (flat) Color.argb(255, 60, 230, 90) else Color.argb(255, 255, 200, 0)
        // Hai vạch mốc cố định ở hai bên
        val tick = 10f * density
        canvas.drawLine(cx - half - tick, cy, cx - half, cy, linePaint)
        canvas.drawLine(cx + half, cy, cx + half + tick, cy, linePaint)
        // Đường chân trời xoay theo độ nghiêng
        canvas.save()
        canvas.rotate(-levelAngle, cx, cy)
        canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        canvas.restore()
        val label = String.format(java.util.Locale.US, "%.1f°", levelAngle)
        canvas.drawText(label, cx - textPaint.measureText(label) / 2f, cy - 8f * density, textPaint)
    }

    private fun drawFocusPoint(canvas: Canvas) {
        if (SystemClock.elapsedRealtime() > focusUntil) return
        val s = 22f * density
        levelPaint.color = Color.argb(255, 60, 230, 90)
        canvas.drawRect(focusX - s, focusY - s, focusX + s, focusY + s, levelPaint)
    }

    private fun drawRecording(canvas: Canvas) {
        if (cameraRecording) {
            val inset = 2f * density
            canvas.drawRect(img.left + inset, img.top + inset, img.right - inset, img.bottom - inset, recPaint)
            boxPaint.color = Color.argb(255, 235, 30, 30)
            val r = 6f * density
            val x = img.left + 16f * density
            val y = img.bottom - 18f * density
            canvas.drawCircle(x, y, r, boxPaint)
            canvas.drawText("REC", x + r + 5f * density, y + 4f * density, textPaint)
        }
        if (previewRecording) {
            val msg = "● Đang ghi preview"
            canvas.drawText(msg, img.right - textPaint.measureText(msg) - 12f * density, img.bottom - 14f * density, textPaint)
        }
    }

    // ---------------------------------------------------------------- biểu đồ

    private fun drawScopes(canvas: Canvas) {
        val px = analysis ?: return
        val w = analysisW
        val h = analysisH
        if (w <= 0 || h <= 0 || px.size < w * h * 4) return
        if (state.histogram == 0 && !state.waveform && !state.vectorscope) return

        val margin = 10f * density
        val right = width - margin
        var top = margin

        if (state.histogram != 0) {
            val sw = 170f * density
            val sh = 70f * density
            drawHistogram(canvas, px, w, h, right - sw, top, sw, sh)
            top += sh + margin * 0.6f
        }
        if (state.waveform) {
            val sw = 170f * density
            val sh = 90f * density
            drawWaveform(canvas, px, w, h, right - sw, top, sw, sh)
            top += sh + margin * 0.6f
        }
        if (state.vectorscope) {
            val size = 120f * density
            drawVectorscope(canvas, px, w, h, right - size, top, size)
        }
    }

    private fun drawHistogram(
        canvas: Canvas, px: ByteArray, w: Int, h: Int,
        left: Float, top: Float, sw: Float, sh: Float
    ) {
        java.util.Arrays.fill(bins, 0)
        val total = w * h
        var i = 0
        for (p in 0 until total) {
            val r = px[i].toInt() and 0xFF
            val g = px[i + 1].toInt() and 0xFF
            val b = px[i + 2].toInt() and 0xFF
            i += 4
            val y = (54 * r + 183 * g + 19 * b) shr 8
            bins[(y * HIST_BINS) shr 8]++
            bins[HIST_BINS + ((r * HIST_BINS) shr 8)]++
            bins[HIST_BINS * 2 + ((g * HIST_BINS) shr 8)]++
            bins[HIST_BINS * 3 + ((b * HIST_BINS) shr 8)]++
        }

        canvas.drawRect(left, top, left + sw, top + sh, scopeBgPaint)
        val rgb = state.histogram == 2
        val first = if (rgb) 1 else 0
        val last = if (rgb) 3 else 0
        var max = 1
        // Bỏ hai đầu khi tìm đỉnh để vùng cháy/đen kịt không làm dẹt biểu đồ
        for (ch in first..last) {
            for (k in 1 until HIST_BINS - 1) {
                val v = bins[ch * HIST_BINS + k]
                if (v > max) max = v
            }
        }
        val step = sw / HIST_BINS.toFloat()
        for (ch in first..last) {
            histPaint.color = when (ch) {
                1 -> Color.argb(170, 255, 60, 60)
                2 -> Color.argb(170, 60, 230, 90)
                3 -> Color.argb(170, 80, 130, 255)
                else -> Color.argb(230, 255, 255, 255)
            }
            path.reset()
            path.moveTo(left, top + sh)
            for (k in 0 until HIST_BINS) {
                val v = (bins[ch * HIST_BINS + k].toFloat() / max.toFloat()).coerceAtMost(1f)
                val y = top + sh - v * sh
                path.lineTo(left + k.toFloat() * step, y)
                path.lineTo(left + (k + 1).toFloat() * step, y)
            }
            path.lineTo(left + sw, top + sh)
            path.close()
            canvas.drawPath(path, histPaint)
        }
        canvas.drawRect(left, top, left + sw, top + sh, linePaint)
    }

    /** Waveform độ sáng: trục ngang là vị trí trong hình, trục dọc là mức sáng 0..100%. */
    private fun drawWaveform(
        canvas: Canvas, px: ByteArray, w: Int, h: Int,
        left: Float, top: Float, sw: Float, sh: Float
    ) {
        java.util.Arrays.fill(waveCounts, 0)
        for (row in 0 until h) {
            var i = row * w * 4
            for (x in 0 until w) {
                val r = px[i].toInt() and 0xFF
                val g = px[i + 1].toInt() and 0xFF
                val b = px[i + 2].toInt() and 0xFF
                i += 4
                val y = (54 * r + 183 * g + 19 * b) shr 8
                val col = x * WAVE_W / w
                val level = (y * WAVE_H) shr 8
                waveCounts[(WAVE_H - 1 - level) * WAVE_W + col]++
            }
        }
        for (k in waveCounts.indices) {
            val c = waveCounts[k]
            if (c == 0) {
                wavePixels[k] = 0x96000000.toInt()
            } else {
                val v = minOf(255, 70 + c * 45)
                wavePixels[k] = Color.argb(255, v * 3 / 5, v, v * 3 / 5)
            }
        }
        waveBitmap.setPixels(wavePixels, 0, WAVE_W, 0, 0, WAVE_W, WAVE_H)
        dst.set(left, top, left + sw, top + sh)
        canvas.drawBitmap(waveBitmap, null, dst, bitmapPaint)
        // Vạch 0 / 50 / 100%
        canvas.drawLine(left, top + sh / 2f, left + sw, top + sh / 2f, thinPaint)
        canvas.drawRect(left, top, left + sw, top + sh, linePaint)
    }

    /** Vectorscope: vị trí điểm là sắc độ (Cb ngang, Cr dọc), tâm là không màu. */
    private fun drawVectorscope(
        canvas: Canvas, px: ByteArray, w: Int, h: Int,
        left: Float, top: Float, size: Float
    ) {
        java.util.Arrays.fill(vecCounts, 0)
        val total = w * h
        val half = VEC_SIZE / 2
        var i = 0
        for (p in 0 until total) {
            val r = px[i].toInt() and 0xFF
            val g = px[i + 1].toInt() and 0xFF
            val b = px[i + 2].toInt() and 0xFF
            i += 4
            // Rec.709, khoảng giá trị xấp xỉ -128..127
            val cb = (-29 * r - 99 * g + 128 * b) shr 8
            val cr = (128 * r - 116 * g - 12 * b) shr 8
            val x = (half + cb * half / 128).coerceIn(0, VEC_SIZE - 1)
            val y = (half - cr * half / 128).coerceIn(0, VEC_SIZE - 1)
            vecCounts[y * VEC_SIZE + x]++
        }
        for (k in vecCounts.indices) {
            val c = vecCounts[k]
            if (c == 0) {
                vecPixels[k] = 0
            } else {
                val v = minOf(255, 90 + c * 30)
                vecPixels[k] = Color.argb(255, v, v, v)
            }
        }
        vecBitmap.setPixels(vecPixels, 0, VEC_SIZE, 0, 0, VEC_SIZE, VEC_SIZE)

        val cx = left + size / 2f
        val cy = top + size / 2f
        val radius = size / 2f
        canvas.drawCircle(cx, cy, radius, scopeBgPaint)
        dst.set(left, top, left + size, top + size)
        canvas.drawBitmap(vecBitmap, null, dst, bitmapPaint)
        canvas.drawCircle(cx, cy, radius, linePaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, thinPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, thinPaint)
        // Đường màu da (khoảng 123 độ tính từ trục Cb)
        levelPaint.color = Color.argb(220, 255, 170, 120)
        canvas.drawLine(cx, cy, cx - radius * 0.545f, cy - radius * 0.839f, levelPaint)
    }

    private fun drawFalseColorLegend(canvas: Canvas) {
        val box = 12f * density
        val gap = 6f * density
        var x = img.left + 10f * density
        val y = img.top + 30f * density
        for (k in LEGEND_COLORS.indices) {
            boxPaint.color = LEGEND_COLORS[k]
            canvas.drawRect(x, y, x + box, y + box, boxPaint)
            x += box + gap * 0.6f
            canvas.drawText(LEGEND_LABELS[k], x, y + box - 1.5f * density, textPaint)
            x += textPaint.measureText(LEGEND_LABELS[k]) + gap * 1.6f
        }
    }

    companion object {
        private const val HIST_BINS = 64
        private const val WAVE_W = 128
        private const val WAVE_H = 64
        private const val VEC_SIZE = 96

        private val ONION_ALPHA = intArrayOf(0, 64, 128, 191)

        private val LEGEND_COLORS = intArrayOf(
            Color.rgb(115, 0, 153), Color.rgb(0, 51, 230), Color.rgb(26, 191, 51),
            Color.rgb(255, 140, 166), Color.rgb(255, 230, 0), Color.rgb(255, 0, 0)
        )
        private val LEGEND_LABELS = arrayOf("<2", "2-10", "38-42", "52-56", "90-97", ">97")
    }
}
