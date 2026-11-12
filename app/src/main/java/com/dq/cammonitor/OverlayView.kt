package com.dq.cammonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Lớp phủ vẽ bằng Canvas phía trên hình: khung tỉ lệ, lưới, tâm,
 * vùng an toàn, histogram và chú thích false color.
 */
class OverlayView(context: Context, private val state: MonitorState) : View(context) {

    private val density = resources.displayMetrics.density

    @Volatile var videoW = 0
    @Volatile var videoH = 0
    private var histogram: IntArray? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(200, 255, 255, 255)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(255, 255, 220, 0)
    }
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val histBgPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
    }
    private val boxPaint = Paint()
    private val path = Path()
    private val rect = RectF()

    fun setHistogram(bins: IntArray?) {
        histogram = bins
        invalidate()
    }

    /** Vùng ảnh đang hiển thị trong view (đã tính letterbox và de-squeeze). */
    private fun imageRect(out: RectF) {
        val vw = if (videoW > 0) videoW else 16
        val vh = if (videoH > 0) videoH else 9
        val aspect = vw.toFloat() * state.squeezeFactor / vh.toFloat()
        val fit = MonitorState.fit(width, height, aspect)
        val w = width * fit[0]
        val h = height * fit[1]
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        out.set(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        imageRect(rect)
        val img = RectF(rect)

        // Khung và lưới chỉ đúng khi không zoom
        if (state.zoom == 0) {
            drawFrameGuide(canvas, img)
            drawGrid(canvas, img)
        }
        if (state.histogram != 0) drawHistogram(canvas)
        if (state.mode == 1) drawFalseColorLegend(canvas)
    }

    private fun drawFrameGuide(canvas: Canvas, img: RectF) {
        val idx = state.frame
        if (idx <= 0 || idx >= MonitorState.FRAMES.size) return
        val target = MonitorState.FRAMES[idx]
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
        // Làm tối phần nằm ngoài khung
        canvas.drawRect(img.left, img.top, img.right, t, dimPaint)
        canvas.drawRect(img.left, b, img.right, img.bottom, dimPaint)
        canvas.drawRect(img.left, t, l, b, dimPaint)
        canvas.drawRect(r, t, img.right, b, dimPaint)
        canvas.drawRect(l, t, r, b, framePaint)
    }

    private fun drawGrid(canvas: Canvas, img: RectF) {
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

    private fun drawHistogram(canvas: Canvas) {
        val bins = histogram ?: return
        val w = 170f * density
        val h = 70f * density
        val margin = 10f * density
        val left = width - w - margin
        val top = margin
        canvas.drawRect(left, top, left + w, top + h, histBgPaint)

        val n = MonitorRenderer.HIST_BINS
        if (bins.size < n * 4) return
        val rgb = state.histogram == 2
        val first = if (rgb) 1 else 0
        val last = if (rgb) 3 else 0

        var max = 1
        // Bỏ hai đầu khi tìm đỉnh để vùng cháy/đen kịt không làm dẹt biểu đồ
        for (ch in first..last) {
            for (i in 1 until n - 1) {
                val v = bins[ch * n + i]
                if (v > max) max = v
            }
        }
        val step = w / n
        for (ch in first..last) {
            histPaint.color = when (ch) {
                1 -> Color.argb(170, 255, 60, 60)
                2 -> Color.argb(170, 60, 230, 90)
                3 -> Color.argb(170, 80, 130, 255)
                else -> Color.argb(230, 255, 255, 255)
            }
            path.reset()
            path.moveTo(left, top + h)
            for (i in 0 until n) {
                val v = (bins[ch * n + i].toFloat() / max).coerceAtMost(1f)
                val y = top + h - v * h
                path.lineTo(left + i * step, y)
                path.lineTo(left + (i + 1) * step, y)
            }
            path.lineTo(left + w, top + h)
            path.close()
            canvas.drawPath(path, histPaint)
        }
        canvas.drawRect(left, top, left + w, top + h, linePaint)
    }

    private fun drawFalseColorLegend(canvas: Canvas) {
        val items = arrayOf(
            Pair(Color.rgb(115, 0, 153), "<2"),
            Pair(Color.rgb(0, 51, 230), "2-10"),
            Pair(Color.rgb(26, 191, 51), "38-42"),
            Pair(Color.rgb(255, 140, 166), "52-56"),
            Pair(Color.rgb(255, 230, 0), "90-97"),
            Pair(Color.rgb(255, 0, 0), ">97")
        )
        val box = 12f * density
        val gap = 6f * density
        var x = 10f * density
        val y = 10f * density
        for (item in items) {
            boxPaint.color = item.first
            canvas.drawRect(x, y, x + box, y + box, boxPaint)
            x += box + gap * 0.6f
            canvas.drawText(item.second, x, y + box - 1.5f * density, textPaint)
            x += textPaint.measureText(item.second) + gap * 1.6f
        }
    }
}
