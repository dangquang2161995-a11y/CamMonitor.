package com.dq.cammonitor

/**
 * Trạng thái các công cụ monitor. Luồng giao diện ghi, luồng GL đọc,
 * nên mọi trường đều là @Volatile.
 */
class MonitorState {
    /** 0 = thường, 1 = false color, 2 = đen trắng */
    @Volatile var mode = 0

    /** 0 = tắt, 1 = đỏ, 2 = xanh lá, 3 = vàng */
    @Volatile var peaking = 0

    /** 0 = thấp, 1 = vừa, 2 = cao */
    @Volatile var peakSens = 1

    /** 0 = tắt, 1 = 70%, 2 = 95%, 3 = 100% */
    @Volatile var zebra = 0

    /** 0 = tắt, 1 = độ sáng, 2 = RGB */
    @Volatile var histogram = 0

    /** Bật/tắt LUT 3D đã nạp */
    @Volatile var lutOn = false

    /** chỉ số trong ZOOMS */
    @Volatile var zoom = 0

    /** bit 0 = lật ngang, bit 1 = lật dọc */
    @Volatile var flip = 0

    /** chỉ số trong SQUEEZES (anamorphic de-squeeze) */
    @Volatile var squeeze = 0

    /** 0 = tắt, còn lại là chỉ số trong FRAMES */
    @Volatile var frame = 0

    /** 0 = tắt, 1 = lưới 1/3, 2 = tâm + vùng an toàn, 3 = tất cả */
    @Volatile var grid = 0

    /** Độ lệch khi zoom, tính theo toạ độ ảnh 0..1 */
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    val zoomFactor: Float get() = ZOOMS[zoom]
    val squeezeFactor: Float get() = SQUEEZES[squeeze]

    fun clampPan() {
        val limit = 0.5f - 0.5f / zoomFactor
        panX = panX.coerceIn(-limit, limit)
        panY = panY.coerceIn(-limit, limit)
    }

    companion object {
        val ZOOMS = floatArrayOf(1f, 2f, 4f)
        val SQUEEZES = floatArrayOf(1f, 1.33f, 1.5f, 2f)

        /** Tỉ lệ khung (rộng / cao); phần tử 0 là "tắt" */
        val FRAMES = floatArrayOf(0f, 9f / 16f, 4f / 5f, 1f, 2.35f)
        val FRAME_NAMES = arrayOf("Tắt", "9:16", "4:5", "1:1", "2.35:1")

        /**
         * Tính kích thước vùng ảnh (đơn vị: tỉ lệ so với view, 0..1) sao cho
         * ảnh nằm trọn trong view và giữ đúng tỉ lệ.
         */
        fun fit(viewW: Int, viewH: Int, imageAspect: Float): FloatArray {
            if (viewW <= 0 || viewH <= 0 || imageAspect <= 0f) return floatArrayOf(1f, 1f)
            val viewAspect = viewW.toFloat() / viewH.toFloat()
            return if (imageAspect > viewAspect) {
                floatArrayOf(1f, viewAspect / imageAspect)
            } else {
                floatArrayOf(imageAspect / viewAspect, 1f)
            }
        }
    }
}
