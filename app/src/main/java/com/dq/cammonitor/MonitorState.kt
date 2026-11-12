package com.dq.cammonitor

/**
 * Trạng thái các công cụ monitor. Luồng giao diện ghi, luồng GL đọc,
 * nên mọi trường đều là @Volatile.
 */
class MonitorState {
    /** 0 = thường, 1 = false color, 2 = đen trắng, 3 = kênh đỏ, 4 = kênh lục, 5 = kênh lam */
    @Volatile var mode = 0

    /** 0 = tắt, 1 = đỏ, 2 = xanh lá, 3 = vàng, 4 = trắng, 5 = xanh dương */
    @Volatile var peaking = 0

    /** 0 = thấp, 1 = vừa, 2 = cao */
    @Volatile var peakSens = 1

    /** 0 = mảnh, 1 = vừa, 2 = dày */
    @Volatile var peakWidth = 0

    /** 0 = tắt, 1 = 70%, 2 = 95%, 3 = 100% */
    @Volatile var zebra = 0

    /** 0 = tắt, 1 = độ sáng, 2 = RGB */
    @Volatile var histogram = 0
    @Volatile var waveform = false
    @Volatile var vectorscope = false

    /** Bật/tắt LUT 3D đang chọn */
    @Volatile var lutOn = false

    /** chỉ số trong ZOOMS */
    @Volatile var zoom = 0

    /** bit 0 = lật ngang, bit 1 = lật dọc */
    @Volatile var flip = 0

    /** chỉ số trong SQUEEZES (anamorphic de-squeeze) */
    @Volatile var squeeze = 0

    /** Xoay hình: 0, 1, 2, 3 tương ứng 0°, 90°, 180°, 270° */
    @Volatile var rotation = 0

    /** Hướng giao diện: 0 = ngang, 1 = dọc */
    @Volatile var orient = 0

    /** chỉ số trong FRAME_NAMES; phần tử cuối là tỉ lệ tự nhập */
    @Volatile var frame = 0
    @Volatile var customRatio = 0.75f

    /** Độ tối phần ngoài khung: chỉ số trong DIM_ALPHA */
    @Volatile var dim = 2

    /** 0 = tắt, 1 = lưới 1/3, 2 = tâm + vùng an toàn, 3 = tất cả */
    @Volatile var grid = 0

    @Volatile var level = false

    /** Ảnh mẫu chồng lên hình: 0 = tắt, 1..3 = độ đậm */
    @Volatile var onion = 0

    @Volatile var touchAf = false
    @Volatile var maxBright = false

    /** Độ lệch khi zoom, tính theo tỉ lệ vùng ảnh trên màn hình */
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    val zoomFactor: Float get() = ZOOMS[zoom]
    val squeezeFactor: Float get() = SQUEEZES[squeeze]

    fun clampPan() {
        val limit = 0.5f - 0.5f / zoomFactor
        panX = panX.coerceIn(-limit, limit)
        panY = panY.coerceIn(-limit, limit)
    }

    /** Tỉ lệ khung đang chọn (rộng / cao), 0 nếu tắt. */
    fun frameRatio(): Float {
        val i = frame
        if (i <= 0) return 0f
        if (i >= FRAMES.size) return customRatio
        return FRAMES[i]
    }

    /** Tỉ lệ rộng/cao của hình khi hiển thị, đã tính de-squeeze và xoay. */
    fun displayAspect(videoW: Int, videoH: Int): Float {
        val w = if (videoW > 0) videoW else 16
        val h = if (videoH > 0) videoH else 9
        val a = w.toFloat() * squeezeFactor / h.toFloat()
        return if (rotation % 2 == 1) 1f / a else a
    }

    /** Ghi toàn bộ cài đặt thành một chuỗi để lưu (cài đặt chung và preset). */
    fun serialize(): String {
        val sb = StringBuilder()
        fun put(k: String, v: Int) {
            sb.append(k).append('=').append(v).append(';')
        }
        put("mode", mode)
        put("peaking", peaking)
        put("peakSens", peakSens)
        put("peakWidth", peakWidth)
        put("zebra", zebra)
        put("hist", histogram)
        put("wave", if (waveform) 1 else 0)
        put("vector", if (vectorscope) 1 else 0)
        put("lutOn", if (lutOn) 1 else 0)
        put("flip", flip)
        put("squeeze", squeeze)
        put("rotation", rotation)
        put("orient", orient)
        put("frame", frame)
        put("dim", dim)
        put("grid", grid)
        put("level", if (level) 1 else 0)
        put("onion", onion)
        put("touchAf", if (touchAf) 1 else 0)
        put("maxBright", if (maxBright) 1 else 0)
        put("ratio1000", (customRatio * 1000f).toInt())
        return sb.toString()
    }

    fun deserialize(text: String) {
        val map = HashMap<String, Int>()
        for (part in text.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val v = part.substring(eq + 1).toIntOrNull() ?: continue
            map[part.substring(0, eq)] = v
        }
        fun get(k: String, def: Int, max: Int): Int = (map[k] ?: def).coerceIn(0, max)
        mode = get("mode", 0, 5)
        peaking = get("peaking", 0, 5)
        peakSens = get("peakSens", 1, 2)
        peakWidth = get("peakWidth", 0, 2)
        zebra = get("zebra", 0, 3)
        histogram = get("hist", 0, 2)
        waveform = get("wave", 0, 1) == 1
        vectorscope = get("vector", 0, 1) == 1
        lutOn = get("lutOn", 0, 1) == 1
        flip = get("flip", 0, 3)
        squeeze = get("squeeze", 0, SQUEEZES.size - 1)
        rotation = get("rotation", 0, 3)
        orient = get("orient", 0, 1)
        frame = get("frame", 0, FRAME_NAMES.size - 1)
        dim = get("dim", 2, DIM_ALPHA.size - 1)
        grid = get("grid", 0, 3)
        level = get("level", 0, 1) == 1
        onion = get("onion", 0, 3)
        touchAf = get("touchAf", 0, 1) == 1
        maxBright = get("maxBright", 0, 1) == 1
        customRatio = (get("ratio1000", 750, 10000) / 1000f).coerceIn(0.2f, 5f)
        zoom = 0
        panX = 0f
        panY = 0f
    }

    companion object {
        val ZOOMS = floatArrayOf(1f, 2f, 4f)
        val SQUEEZES = floatArrayOf(1f, 1.33f, 1.5f, 2f)

        /** Tỉ lệ khung (rộng / cao); phần tử 0 là "tắt" */
        val FRAMES = floatArrayOf(0f, 9f / 16f, 4f / 5f, 1f, 3f / 4f, 16f / 9f, 1.91f, 2.35f)
        val FRAME_NAMES = arrayOf("Tắt", "9:16", "4:5", "1:1", "3:4", "16:9", "1.91:1", "2.35:1", "Tự nhập")

        /** Độ tối phần ngoài khung (alpha 0..255) */
        val DIM_ALPHA = intArrayOf(0, 64, 128, 191, 255)
        val DIM_NAMES = arrayOf("0%", "25%", "50%", "75%", "100%")

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
