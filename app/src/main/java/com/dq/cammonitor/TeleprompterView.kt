package com.dq.cammonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Máy nhắc chữ phủ lên hình xem: chữ tự cuộn từ dưới lên với tốc độ đều.
 * Chạm để chạy hoặc tạm dừng, kéo để tua. Có lật gương cho kính phản chiếu.
 */
@SuppressLint("ViewConstructor")
class TeleprompterView(context: Context) : FrameLayout(context) {

    private val scroll = ScrollView(context)
    private val label = TextView(context)
    private val hint = TextView(context)
    private val density = resources.displayMetrics.density

    private var playing = false
    private var touching = false
    private var pos = 0f
    private var lastTime = 0L

    /** Tốc độ cuộn, tính bằng dp mỗi giây. */
    var speedDp = 40f

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTime).coerceIn(0L, 100L) / 1000f
            lastTime = now
            if (!touching) {
                pos += speedDp * density * dt
                val max = (label.height - scroll.height).coerceAtLeast(0)
                if (pos >= max) {
                    pos = max.toFloat()
                    scroll.scrollTo(0, max)
                    pause()
                    return
                }
                scroll.scrollTo(0, pos.toInt())
            }
            postOnAnimation(this)
        }
    }

    init {
        label.setTextColor(Color.WHITE)
        label.textSize = 30f
        label.setLineSpacing(0f, 1.25f)
        label.gravity = Gravity.CENTER_HORIZONTAL
        label.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hint.setTextColor(Color.argb(230, 255, 220, 120))
        hint.textSize = 12f
        hint.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        hint.text = "Chạm để chạy · kéo để tua"
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        lp.topMargin = (26 * density).toInt()
        lp.rightMargin = (10 * density).toInt()
        addView(hint, lp)

        val slop = ViewConfiguration.get(context).scaledTouchSlop
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    moved = false
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    pos = scroll.scrollY.toFloat()
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (playing) pause() else play()
                    }
                }
            }
            // Trả false để ScrollView vẫn tự xử lý thao tác kéo
            false
        }
        setBackdrop(1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Chừa khoảng trống để chữ bắt đầu từ giữa màn hình và cuộn hết ra khỏi màn hình
        val side = (w * 0.08f).toInt()
        post { label.setPadding(side, (h * 0.5f).toInt(), side, (h * 0.7f).toInt()) }
    }

    fun setScript(text: String) {
        label.text = text
        rewind()
    }

    fun setTextSizeSp(sp: Float) {
        label.textSize = sp
    }

    /** Lật gương theo chiều ngang, dùng khi đọc chữ qua kính phản chiếu. */
    fun setMirror(on: Boolean) {
        scroll.scaleX = if (on) -1f else 1f
    }

    /** 0 = trong suốt, 1 = mờ, 2 = đen. */
    fun setBackdrop(level: Int) {
        val alpha = when (level) {
            0 -> 0
            2 -> 255
            else -> 150
        }
        setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    fun isPlaying(): Boolean = playing

    fun play() {
        if (playing) return
        playing = true
        pos = scroll.scrollY.toFloat()
        lastTime = SystemClock.uptimeMillis()
        hint.visibility = GONE
        removeCallbacks(tick)
        postOnAnimation(tick)
    }

    fun pause() {
        playing = false
        removeCallbacks(tick)
        hint.visibility = VISIBLE
    }

    /** Về đầu bài và dừng lại. */
    fun rewind() {
        pause()
        pos = 0f
        scroll.scrollTo(0, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pause()
    }
}
