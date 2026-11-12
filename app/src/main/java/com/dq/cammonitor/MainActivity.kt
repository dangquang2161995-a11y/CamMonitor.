package com.dq.cammonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import kotlin.math.abs

class MainActivity : Activity(), MonitorRenderer.Listener {

    private val state = MonitorState()
    private lateinit var prefs: SharedPreferences

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var controlBar: HorizontalScrollView

    private var cameraHelper: ICameraHelper? = null
    private var surface: Surface? = null
    private var attachedSurface: Surface? = null
    private var cameraOpened = false
    private var videoW = 1280
    private var videoH = 720

    // Chạm: chạm nhanh để ẩn/hiện thanh công cụ, kéo để dời vùng zoom
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE)
        loadState()
        buildUi()
        hideSystemBars()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    // ---------------------------------------------------------------- giao diện

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        renderer = MonitorRenderer(state, this)
        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(glView, matchParent())

        overlay = OverlayView(this, state)
        root.addView(overlay, matchParent())

        statusText = TextView(this)
        statusText.setTextColor(Color.WHITE)
        statusText.textSize = 16f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(dp(24), dp(16), dp(24), dp(16))
        statusText.setBackgroundColor(Color.argb(170, 0, 0, 0))
        statusText.text = TEXT_WAITING
        statusText.setOnClickListener { connectFirstDevice() }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        infoText = TextView(this)
        infoText.setTextColor(Color.argb(220, 255, 255, 255))
        infoText.textSize = 11f
        infoText.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        val infoLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        infoLp.topMargin = dp(8)
        root.addView(infoText, infoLp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(6), dp(4), dp(6), dp(4))

        addButton(row, { "Chế độ: " + MODE_NAMES[state.mode] }) {
            state.mode = (state.mode + 1) % MODE_NAMES.size
        }
        addButton(row, { "Peaking: " + PEAK_NAMES[state.peaking] }) {
            state.peaking = (state.peaking + 1) % PEAK_NAMES.size
        }
        addButton(row, { "Độ nhạy: " + SENS_NAMES[state.peakSens] }) {
            state.peakSens = (state.peakSens + 1) % SENS_NAMES.size
        }
        addButton(row, { "Zebra: " + ZEBRA_NAMES[state.zebra] }) {
            state.zebra = (state.zebra + 1) % ZEBRA_NAMES.size
        }
        addButton(row, { "Histogram: " + (if (state.histogram) "Bật" else "Tắt") }) {
            state.histogram = !state.histogram
            if (!state.histogram) overlay.setHistogram(null)
        }
        addButton(row, { "Khung: " + MonitorState.FRAME_NAMES[state.frame] }) {
            state.frame = (state.frame + 1) % MonitorState.FRAME_NAMES.size
        }
        addButton(row, { "Lưới: " + GRID_NAMES[state.grid] }) {
            state.grid = (state.grid + 1) % GRID_NAMES.size
        }
        addButton(row, { "Zoom: " + ZOOM_NAMES[state.zoom] }) {
            state.zoom = (state.zoom + 1) % ZOOM_NAMES.size
            if (state.zoom == 0) {
                state.panX = 0f
                state.panY = 0f
            }
            state.clampPan()
        }
        addButton(row, { "Lật: " + FLIP_NAMES[state.flip] }) {
            state.flip = (state.flip + 1) % FLIP_NAMES.size
        }
        addButton(row, { "De-squeeze: " + SQUEEZE_NAMES[state.squeeze] }) {
            state.squeeze = (state.squeeze + 1) % SQUEEZE_NAMES.size
        }

        controlBar = HorizontalScrollView(this)
        controlBar.isHorizontalScrollBarEnabled = false
        controlBar.setBackgroundColor(Color.argb(150, 0, 0, 0))
        controlBar.addView(row)
        root.addView(
            controlBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        overlay.setOnTouchListener { _, event -> handleTouch(event) }
        setContentView(root)
    }

    private fun addButton(row: LinearLayout, label: () -> String, action: () -> Unit) {
        val b = Button(this)
        b.setAllCaps(false)
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(12), 0, dp(12), 0)
        b.text = label()
        b.setOnClickListener {
            action()
            b.text = label()
            saveState()
            overlay.invalidate()
            glView.requestRender()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        lp.marginEnd = dp(4)
        row.addView(b, lp)
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    dragging = true
                }
                if (dragging && state.zoom > 0) {
                    val w = overlay.width.coerceAtLeast(1).toFloat()
                    val h = overlay.height.coerceAtLeast(1).toFloat()
                    val zoom = state.zoomFactor
                    val signX = if (state.flip and 1 != 0) -1f else 1f
                    val signY = if (state.flip and 2 != 0) -1f else 1f
                    // Kéo ngón tay sang phải thì hình đi theo sang phải
                    state.panX -= signX * (e.x - lastX) / w / zoom
                    state.panY += signY * (e.y - lastY) / h / zoom
                    state.clampPan()
                    glView.requestRender()
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    controlBar.visibility =
                        if (controlBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    hideSystemBars()
                }
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- vòng đời

    override fun onStart() {
        super.onStart()
        glView.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCamera()
        }
    }

    override fun onStop() {
        super.onStop()
        releaseCamera()
        glView.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initCamera()
        } else {
            statusText.text = "Cần cấp quyền Camera để đọc hình qua USB.\nVào Cài đặt > Ứng dụng > Cam Monitor > Quyền."
            statusText.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- camera UVC

    private fun initCamera() {
        if (cameraHelper != null) return
        val helper = CameraHelper()
        helper.setStateCallback(stateCallback)
        cameraHelper = helper
    }

    private fun releaseCamera() {
        cameraOpened = false
        attachedSurface = null
        cameraHelper?.release()
        cameraHelper = null
    }

    private fun connectFirstDevice() {
        val helper = cameraHelper ?: return
        if (cameraOpened) return
        val list = helper.deviceList
        if (list != null && list.isNotEmpty()) {
            statusText.text = "Đang kết nối…"
            helper.selectDevice(list[0])
        } else {
            statusText.text = TEXT_WAITING
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice?) {
            if (device == null || cameraOpened) return
            statusText.text = "Đang kết nối…"
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            val helper = cameraHelper ?: return
            val best = pickSize(helper.supportedSizeList)
            if (best != null) {
                helper.stopPreview()
                helper.setPreviewSize(best)
                videoW = best.width
                videoH = best.height
            } else {
                val current = helper.previewSize
                if (current != null) {
                    videoW = current.width
                    videoH = current.height
                }
            }
            helper.startPreview()

            cameraOpened = true
            overlay.videoW = videoW
            overlay.videoH = videoH
            overlay.invalidate()
            statusText.visibility = View.GONE
            infoText.text = videoW.toString() + "×" + videoH.toString()
            attachSurfaceIfReady()
        }

        override fun onCameraClose(device: UsbDevice?) {
            val s = attachedSurface
            if (s != null) cameraHelper?.removeSurface(s)
            attachedSurface = null
            cameraOpened = false
        }

        override fun onDeviceClose(device: UsbDevice?) {
            cameraOpened = false
        }

        override fun onDetach(device: UsbDevice?) {
            cameraOpened = false
            attachedSurface = null
            infoText.text = ""
            statusText.text = TEXT_WAITING
            statusText.visibility = View.VISIBLE
        }

        override fun onCancel(device: UsbDevice?) {
            statusText.text = "Bạn chưa cho phép truy cập thiết bị USB.\nChạm vào đây để thử lại."
            statusText.visibility = View.VISIBLE
        }
    }

    /**
     * Chọn độ phân giải: ưu tiên MJPEG (đủ fps qua USB 2.0), ưu tiên đúng 1280×720
     * vì khớp màn hình điện thoại; nếu không có thì lấy cỡ lớn nhất không quá 1080p.
     */
    private fun pickSize(list: List<Size>?): Size? {
        if (list == null || list.isEmpty()) return null
        val mjpeg = list.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
        val pool = if (mjpeg.isNotEmpty()) mjpeg else list
        val exact = pool.firstOrNull { it.width == 1280 && it.height == 720 }
        if (exact != null) return exact
        val fits = pool.filter { it.width <= 1920 && it.height <= 1080 }
        val candidates = if (fits.isNotEmpty()) fits else pool
        return candidates.maxByOrNull { it.width * it.height }
    }

    /** Gắn surface của OpenGL vào camera khi cả hai đã sẵn sàng. */
    private fun attachSurfaceIfReady() {
        val helper = cameraHelper ?: return
        val s = surface ?: return
        if (!cameraOpened || attachedSurface === s) return
        val w = videoW
        val h = videoH
        glView.queueEvent {
            renderer.applyVideoSize(w, h)
            runOnUiThread {
                val old = attachedSurface
                if (old != null && old !== s) helper.removeSurface(old)
                if (cameraOpened && attachedSurface !== s) {
                    helper.addSurface(s, false)
                    attachedSurface = s
                }
            }
        }
    }

    // ---------------------------------------------------------------- callback từ renderer (luồng GL)

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        runOnUiThread {
            surface = Surface(surfaceTexture)
            attachSurfaceIfReady()
        }
    }

    override fun onHistogram(bins: IntArray) {
        runOnUiThread {
            if (state.histogram) overlay.setHistogram(bins)
        }
    }

    override fun onFrameAvailable() {
        glView.requestRender()
    }

    // ---------------------------------------------------------------- lưu cài đặt

    private fun loadState() {
        state.mode = prefs.getInt("mode", 0).coerceIn(0, MODE_NAMES.size - 1)
        state.peaking = prefs.getInt("peaking", 0).coerceIn(0, PEAK_NAMES.size - 1)
        state.peakSens = prefs.getInt("peakSens", 1).coerceIn(0, SENS_NAMES.size - 1)
        state.zebra = prefs.getInt("zebra", 0).coerceIn(0, ZEBRA_NAMES.size - 1)
        state.histogram = prefs.getBoolean("histogram", false)
        state.frame = prefs.getInt("frame", 0).coerceIn(0, MonitorState.FRAME_NAMES.size - 1)
        state.grid = prefs.getInt("grid", 0).coerceIn(0, GRID_NAMES.size - 1)
        state.flip = prefs.getInt("flip", 0).coerceIn(0, FLIP_NAMES.size - 1)
        state.squeeze = prefs.getInt("squeeze", 0).coerceIn(0, SQUEEZE_NAMES.size - 1)
    }

    private fun saveState() {
        prefs.edit()
            .putInt("mode", state.mode)
            .putInt("peaking", state.peaking)
            .putInt("peakSens", state.peakSens)
            .putInt("zebra", state.zebra)
            .putBoolean("histogram", state.histogram)
            .putInt("frame", state.frame)
            .putInt("grid", state.grid)
            .putInt("flip", state.flip)
            .putInt("squeeze", state.squeeze)
            .apply()
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val TEXT_WAITING =
            "Cắm máy ảnh hoặc capture card vào cổng USB-C.\nNếu đã cắm, chạm vào đây để kết nối."

        private val MODE_NAMES = arrayOf("Thường", "False color", "Đen trắng")
        private val PEAK_NAMES = arrayOf("Tắt", "Đỏ", "Xanh", "Vàng")
        private val SENS_NAMES = arrayOf("Thấp", "Vừa", "Cao")
        private val ZEBRA_NAMES = arrayOf("Tắt", "70%", "95%", "100%")
        private val GRID_NAMES = arrayOf("Tắt", "1/3", "Tâm", "Tất cả")
        private val ZOOM_NAMES = arrayOf("1×", "2×", "4×")
        private val FLIP_NAMES = arrayOf("Không", "Ngang", "Dọc", "Cả hai")
        private val SQUEEZE_NAMES = arrayOf("1×", "1.33×", "1.5×", "2×")
    }
}
