package com.dq.cammonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : Activity(), MonitorRenderer.Listener, SonyRemote.Listener, SensorEventListener {

    private val state = MonitorState()
    private lateinit var prefs: SharedPreferences

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var diagText: TextView
    private lateinit var camInfoText: TextView
    private lateinit var bars: LinearLayout
    private lateinit var cameraBar: HorizontalScrollView

    /** Mỗi nút đăng ký một hàm cập nhật nhãn, để làm mới đồng loạt khi nạp preset. */
    private val labelUpdaters = ArrayList<() -> Unit>()

    // ---- camera UVC (capture card / webcam)
    private var cameraHelper: ICameraHelper? = null
    private var surface: Surface? = null
    private var attachedSurface: Surface? = null
    private var cameraOpened = false
    private var videoW = 1280
    private var videoH = 720

    // ---- Sony PC Remote (PTP): xem hình và điều khiển trong lúc máy ảnh vẫn quay/chụp
    private var sonyRemote: SonyRemote? = null
    @Volatile private var ptpSurface: Surface? = null
    @Volatile private var ptpStreaming = false
    private var ptpAskedFor: String? = null
    private var ptpRetryAt = 0L
    private var ptpBitmap: Bitmap? = null
    private val ptpOptions = BitmapFactory.Options()
    private val ptpDst = Rect()
    private var ptpW = 0
    private var ptpH = 0
    private var lastInfo: SonyInfo? = null

    /** Surface đang được dùng bởi: 0 = chưa ai, 1 = UVC, 2 = PC Remote */
    private var surfaceUse = 0
    private var surfaceRecreating = false

    // ---- LUT, ảnh mẫu, ghi hình
    private var currentLut = ""
    private var previewRecording = false
    private var locked = false
    private var barsVisible = true

    // ---- thước cân bằng
    private var sensorManager: SensorManager? = null
    private var sensorOn = false
    private var smoothAngle = 0f

    // ---- bảng chẩn đoán
    private val handler = Handler(Looper.getMainLooper())
    private val events = ArrayList<String>()
    private val diagTick = object : Runnable {
        override fun run() {
            checkSonyRemote()
            refreshDiag()
            handler.postDelayed(this, 1000)
        }
    }
    private val unlockRunnable = Runnable { setLocked(false) }

    // ---- chạm
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var multiTouch = false
    private val touchRect = RectF()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        loadState()
        buildUi()
        hideSystemBars()
        applyOrientation()
        applyBrightness()
        restoreLut()
        restoreOnion()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    // ================================================================ giao diện

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
        statusText.textSize = 15f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(dp(24), dp(16), dp(24), dp(16))
        statusText.setBackgroundColor(Color.argb(170, 0, 0, 0))
        statusText.text = TEXT_WAITING
        statusText.setOnClickListener { connectFirstDevice() }
        root.addView(statusText, wrap(Gravity.CENTER))

        infoText = TextView(this)
        infoText.setTextColor(Color.argb(220, 255, 255, 255))
        infoText.textSize = 11f
        infoText.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        val infoLp = wrap(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        infoLp.topMargin = dp(6)
        root.addView(infoText, infoLp)

        camInfoText = TextView(this)
        camInfoText.setTextColor(Color.WHITE)
        camInfoText.textSize = 13f
        camInfoText.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        camInfoText.setPadding(dp(8), dp(3), dp(8), dp(3))
        camInfoText.setBackgroundColor(Color.argb(110, 0, 0, 0))
        camInfoText.visibility = View.GONE
        val camLp = wrap(Gravity.TOP or Gravity.START)
        camLp.topMargin = dp(6)
        camLp.leftMargin = dp(8)
        root.addView(camInfoText, camLp)

        diagText = TextView(this)
        diagText.setTextColor(Color.argb(255, 140, 255, 160))
        diagText.textSize = 10f
        diagText.setPadding(dp(8), dp(6), dp(8), dp(6))
        diagText.setBackgroundColor(Color.argb(150, 0, 0, 0))
        val diagLp = wrap(Gravity.TOP or Gravity.START)
        diagLp.topMargin = dp(8)
        diagLp.leftMargin = dp(8)
        root.addView(diagText, diagLp)

        // Hai thanh công cụ ở đáy: điều khiển máy ảnh (chỉ hiện khi có PC Remote) và công cụ monitor
        bars = LinearLayout(this)
        bars.orientation = LinearLayout.VERTICAL

        cameraBar = HorizontalScrollView(this)
        cameraBar.isHorizontalScrollBarEnabled = false
        cameraBar.setBackgroundColor(Color.argb(150, 60, 0, 0))
        cameraBar.addView(buildCameraRow())
        cameraBar.visibility = View.GONE
        bars.addView(cameraBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val toolBar = HorizontalScrollView(this)
        toolBar.isHorizontalScrollBarEnabled = false
        toolBar.setBackgroundColor(Color.argb(150, 0, 0, 0))
        toolBar.addView(buildToolRow())
        bars.addView(toolBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val barsLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        root.addView(bars, barsLp)

        overlay.setOnTouchListener { _, event -> handleTouch(event) }
        setContentView(root)
    }

    private fun buildToolRow(): LinearLayout {
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
        addButton(row, { "Nét peaking: " + WIDTH_NAMES[state.peakWidth] }) {
            state.peakWidth = (state.peakWidth + 1) % WIDTH_NAMES.size
        }
        addButton(row, { "Zebra: " + ZEBRA_NAMES[state.zebra] }) {
            state.zebra = (state.zebra + 1) % ZEBRA_NAMES.size
        }
        addButton(row, { "Histogram: " + HIST_NAMES[state.histogram] }) {
            state.histogram = (state.histogram + 1) % HIST_NAMES.size
        }
        addButton(row, { "Waveform: " + onOff(state.waveform) }) {
            state.waveform = !state.waveform
        }
        addButton(row, { "Vectorscope: " + onOff(state.vectorscope) }) {
            state.vectorscope = !state.vectorscope
        }
        // Chạm: đổi LUT (Tắt > LUT 1 > LUT 2...). Giữ lâu: thêm hoặc xoá LUT.
        addButton(row, { lutLabel() }, { showLutMenu() }) { cycleLut() }
        // Chạm: đổi khung. Giữ lâu: nhập tỉ lệ riêng.
        addButton(row, { frameLabel() }, { showCustomRatioDialog() }) {
            state.frame = (state.frame + 1) % MonitorState.FRAME_NAMES.size
        }
        addButton(row, { "Mờ ngoài khung: " + MonitorState.DIM_NAMES[state.dim] }) {
            state.dim = (state.dim + 1) % MonitorState.DIM_NAMES.size
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
        addButton(row, { "Xoay hình: " + ROTATION_NAMES[state.rotation] }) {
            state.rotation = (state.rotation + 1) % ROTATION_NAMES.size
        }
        addButton(row, { "Giao diện: " + ORIENT_NAMES[state.orient] }) {
            state.orient = (state.orient + 1) % ORIENT_NAMES.size
            applyOrientation()
        }
        addButton(row, { "Lật: " + FLIP_NAMES[state.flip] }) {
            state.flip = (state.flip + 1) % FLIP_NAMES.size
        }
        addButton(row, { "De-squeeze: " + SQUEEZE_NAMES[state.squeeze] }) {
            state.squeeze = (state.squeeze + 1) % SQUEEZE_NAMES.size
        }
        // Chạm: đổi độ đậm ảnh mẫu. Giữ lâu: chọn ảnh mẫu.
        addButton(row, { "Ảnh mẫu: " + ONION_NAMES[state.onion] }, { showOnionMenu() }) {
            if (overlay.onionBitmap == null) {
                showOnionMenu()
            } else {
                state.onion = (state.onion + 1) % ONION_NAMES.size
            }
        }
        addButton(row, { "Thước cân bằng: " + onOff(state.level) }) {
            state.level = !state.level
            applyLevelSensor()
        }
        addButton(row, { "Chụp màn hình" }) {
            renderer.requestCapture(MonitorRenderer.CAPTURE_SAVE)
        }
        addButton(row, { if (previewRecording) "■ Dừng ghi preview" else "● Ghi preview" }) {
            togglePreviewRecording()
        }
        addButton(row, { "Sáng tối đa: " + onOff(state.maxBright) }) {
            state.maxBright = !state.maxBright
            applyBrightness()
        }
        addButton(row, { "Preset" }) { showPresetMenu() }
        addButton(row, { "Khóa cảm ứng" }) { setLocked(true) }
        return row
    }

    private fun buildCameraRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(6), dp(4), dp(6), dp(4))

        addButton(row, { if (lastInfo?.recording == true) "■ Dừng quay" else "● Quay" }) {
            sonyRemote?.toggleRecord()
        }
        addButton(row, { "Chụp ảnh" }) { sonyRemote?.capturePhoto() }
        addButton(row, { "Lấy nét" }) { sonyRemote?.focus() }
        addButton(row, { "Chạm lấy nét: " + onOff(state.touchAf) }) {
            state.touchAf = !state.touchAf
            if (state.touchAf) {
                toast("Chạm vào hình để lấy nét. Chạm hai ngón để ẩn/hiện thanh công cụ. Máy ảnh cần ở AF và vùng lấy nét dạng điểm (Spot).")
            }
        }
        addButton(row, { "ISO −" }) { sonyRemote?.stepValue(SonyRemote.PROP_ISO, false) }
        addButton(row, { "ISO +" }) { sonyRemote?.stepValue(SonyRemote.PROP_ISO, true) }
        addButton(row, { "Tốc −" }) { sonyRemote?.stepValue(SonyRemote.PROP_SHUTTER, false) }
        addButton(row, { "Tốc +" }) { sonyRemote?.stepValue(SonyRemote.PROP_SHUTTER, true) }
        addButton(row, { "Khẩu −" }) { sonyRemote?.stepValue(SonyRemote.PROP_F_NUMBER, false) }
        addButton(row, { "Khẩu +" }) { sonyRemote?.stepValue(SonyRemote.PROP_F_NUMBER, true) }
        addButton(row, { "EV −" }) { sonyRemote?.stepValue(SonyRemote.PROP_EXPOSURE_COMP, false) }
        addButton(row, { "EV +" }) { sonyRemote?.stepValue(SonyRemote.PROP_EXPOSURE_COMP, true) }
        addButton(row, { "Cân bằng trắng" }) { showWhiteBalanceMenu() }
        return row
    }

    private fun addButton(
        row: LinearLayout,
        label: () -> String,
        longAction: (() -> Unit)? = null,
        action: () -> Unit
    ): Button {
        val b = Button(this)
        b.setAllCaps(false)
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(12), 0, dp(12), 0)
        b.text = label()
        labelUpdaters.add { b.text = label() }
        b.setOnClickListener {
            action()
            afterChange()
        }
        if (longAction != null) {
            b.setOnLongClickListener {
                longAction()
                true
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        lp.marginEnd = dp(4)
        row.addView(b, lp)
        return b
    }

    /** Gọi sau mỗi thay đổi cài đặt: làm mới nhãn, lưu lại và vẽ lại. */
    private fun afterChange() {
        for (u in labelUpdaters) u()
        saveState()
        overlay.invalidate()
        glView.requestRender()
    }

    private fun onOff(v: Boolean): String = if (v) "Bật" else "Tắt"

    private fun frameLabel(): String {
        val i = state.frame
        if (i == MonitorState.FRAME_NAMES.size - 1) {
            return "Khung: " + String.format(Locale.US, "%.2f:1", state.customRatio)
        }
        return "Khung: " + MonitorState.FRAME_NAMES[i]
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun wrap(gravity: Int) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun dialog(): AlertDialog.Builder {
        return AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setOnDismissListener { hideSystemBars() }
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

    private fun applyOrientation() {
        requestedOrientation = if (state.orient == 1) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Sáng tối đa chỉ áp cho cửa sổ app, thoát app là độ sáng tự về như cũ. */
    private fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness = if (state.maxBright) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun setBarsVisible(visible: Boolean) {
        barsVisible = visible
        bars.visibility = if (visible) View.VISIBLE else View.GONE
        hideSystemBars()
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        overlay.locked = lock
        setBarsVisible(!lock)
        overlay.invalidate()
    }

    // ================================================================ chạm

    private fun handleTouch(e: MotionEvent): Boolean {
        if (locked) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(unlockRunnable, 1000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(unlockRunnable)
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                dragging = false
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    dragging = true
                }
                if (dragging && state.zoom > 0 && !multiTouch) {
                    overlay.imageRect(touchRect)
                    val w = touchRect.width().coerceAtLeast(1f)
                    val h = touchRect.height().coerceAtLeast(1f)
                    val zoom = state.zoomFactor
                    val signX = if (state.flip and 1 != 0) -1f else 1f
                    val signY = if (state.flip and 2 != 0) -1f else 1f
                    // Kéo ngón tay thì hình đi theo ngón tay
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
                    if (!multiTouch && state.touchAf && ptpStreaming && touchFocusAt(e.x, e.y)) {
                        // đã gửi lệnh lấy nét
                    } else {
                        setBarsVisible(!barsVisible)
                    }
                }
            }
        }
        return true
    }

    /** Đổi điểm chạm trên màn hình thành toạ độ trong khung hình máy ảnh rồi gửi lệnh lấy nét. */
    private fun touchFocusAt(x: Float, y: Float): Boolean {
        val remote = sonyRemote ?: return false
        overlay.imageRect(touchRect)
        if (!touchRect.contains(x, y)) return false
        val zoom = state.zoomFactor
        val sx = (if (state.flip and 1 != 0) -1f else 1f) / zoom
        val sy = (if (state.flip and 2 != 0) -1f else 1f) / zoom
        val px = (x - touchRect.left) / touchRect.width() - 0.5f
        val py = 0.5f - (y - touchRect.top) / touchRect.height()
        val qx = px * sx + state.panX
        val qy = py * sy + state.panY
        // Cùng phép xoay với shader
        val c = ROT_COS[state.rotation and 3]
        val s = ROT_SIN[state.rotation and 3]
        val u = c * qx - s * qy + 0.5f
        val v = s * qx + c * qy + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false
        remote.touchFocus((u * 639f).toInt(), ((1f - v) * 479f).toInt())
        overlay.showFocusPoint(x, y)
        return true
    }

    // ================================================================ LUT 3D (nhiều file)

    private fun lutDir(): File {
        val dir = File(filesDir, "luts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun lutFiles(): List<File> {
        val list = lutDir().listFiles() ?: return emptyList()
        return list.filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".cube") }.sortedBy { it.name }
    }

    private fun lutLabel(): String {
        if (!state.lutOn || currentLut.isEmpty()) return "LUT: Tắt"
        var name = currentLut.removeSuffix(".cube")
        if (name.length > 16) name = name.substring(0, 16) + "…"
        return "LUT: " + name
    }

    private fun cycleLut() {
        val files = lutFiles()
        if (files.isEmpty()) {
            pickFile(REQ_LUT, "*/*")
            return
        }
        var index = -1
        if (state.lutOn) {
            for (i in files.indices) {
                if (files[i].name == currentLut) index = i
            }
        }
        val next = index + 1
        if (next >= files.size) {
            state.lutOn = false
        } else {
            loadLut(files[next], true)
        }
    }

    private fun loadLut(file: File, turnOn: Boolean) {
        Thread {
            try {
                val lut = LutLoader.parse(String(file.readBytes(), Charsets.UTF_8))
                runOnUiThread {
                    currentLut = file.name
                    state.lutOn = turnOn
                    glView.queueEvent { renderer.setLut(lut.size, lut.rgba) }
                    afterChange()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Không nạp được LUT: " + (e.message ?: "lỗi định dạng")) }
            }
        }.start()
    }

    private fun restoreLut() {
        // Bản cũ chỉ lưu một file lut.cube, chuyển nó vào thư viện LUT
        val old = File(filesDir, "lut.cube")
        if (old.exists()) {
            val name = (prefs.getString("lutName", "LUT") ?: "LUT") + ".cube"
            old.renameTo(File(lutDir(), safeName(name)))
        }
        if (currentLut.isEmpty()) return
        val file = File(lutDir(), currentLut)
        if (file.exists()) {
            loadLut(file, state.lutOn)
        } else {
            currentLut = ""
            state.lutOn = false
        }
    }

    private fun showLutMenu() {
        val items = arrayOf("Thêm file .cube…", "Xóa LUT đang chọn")
        dialog().setTitle("LUT 3D").setItems(items) { _, which ->
            if (which == 0) {
                pickFile(REQ_LUT, "*/*")
            } else {
                if (currentLut.isEmpty()) {
                    toast("Chưa chọn LUT nào")
                } else {
                    File(lutDir(), currentLut).delete()
                    currentLut = ""
                    state.lutOn = false
                    afterChange()
                }
            }
        }.show()
    }

    private fun importLut(uri: Uri) {
        val name = safeName(displayName(uri, "LUT.cube"))
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Không đọc được file")
                if (bytes.size > 40 * 1024 * 1024) throw IllegalArgumentException("File quá lớn")
                // Phân tích thử để chắc chắn là file .cube hợp lệ rồi mới lưu
                LutLoader.parse(String(bytes, Charsets.UTF_8))
                val fileName = if (name.lowercase(Locale.US).endsWith(".cube")) name else "$name.cube"
                val file = File(lutDir(), fileName)
                file.writeBytes(bytes)
                runOnUiThread { loadLut(file, true) }
            } catch (e: Exception) {
                runOnUiThread { toast("Không nạp được LUT: " + (e.message ?: "lỗi định dạng")) }
            }
        }.start()
    }

    // ================================================================ ảnh mẫu (onion skin)

    private fun onionFile(): File = File(filesDir, "onion.jpg")

    private fun showOnionMenu() {
        val items = arrayOf("Lấy khung hình hiện tại làm mẫu", "Chọn ảnh từ máy…", "Xóa ảnh mẫu")
        dialog().setTitle("Ảnh mẫu").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    renderer.requestCapture(MonitorRenderer.CAPTURE_ONION)
                    glView.requestRender()
                }
                1 -> pickFile(REQ_ONION, "image/*")
                else -> {
                    onionFile().delete()
                    overlay.onionBitmap = null
                    state.onion = 0
                    afterChange()
                }
            }
        }.show()
    }

    private fun setOnion(bitmap: Bitmap, fromSource: Boolean, save: Boolean) {
        overlay.onionBitmap = bitmap
        overlay.onionFromSource = fromSource
        if (state.onion == 0) state.onion = 2
        prefs.edit().putBoolean("onionFromSource", fromSource).apply()
        afterChange()
        if (save) {
            Thread {
                try {
                    FileOutputStream(onionFile()).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                } catch (e: Exception) {
                    // không lưu được thì ảnh mẫu chỉ dùng cho lần này
                }
            }.start()
        }
    }

    private fun restoreOnion() {
        val file = onionFile()
        if (!file.exists()) return
        val fromSource = prefs.getBoolean("onionFromSource", false)
        Thread {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                runOnUiThread {
                    overlay.onionBitmap = bmp
                    overlay.onionFromSource = fromSource
                    overlay.invalidate()
                }
            }
        }.start()
    }

    private fun importOnion(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Không đọc được ảnh")
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: throw IllegalArgumentException("Không phải file ảnh")
                runOnUiThread { setOnion(bmp, false, true) }
            } catch (e: Exception) {
                runOnUiThread { toast("Không nạp được ảnh mẫu: " + (e.message ?: "")) }
            }
        }.start()
    }

    // ================================================================ khung tự nhập, preset

    private fun showCustomRatioDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Ví dụ 3:4 hoặc 1.91"
        input.setText(String.format(Locale.US, "%.2f", state.customRatio))
        dialog().setTitle("Tỉ lệ khung (rộng:cao)").setView(input)
            .setPositiveButton("Dùng") { _, _ ->
                val ratio = parseRatio(input.text.toString())
                if (ratio == null) {
                    toast("Tỉ lệ không hợp lệ. Nhập dạng 3:4 hoặc 1.91")
                } else {
                    state.customRatio = ratio
                    state.frame = MonitorState.FRAME_NAMES.size - 1
                    afterChange()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun parseRatio(text: String): Float? {
        val t = text.trim().replace(',', '.')
        var value: Float? = null
        val sep = t.indexOfAny(charArrayOf(':', '/', 'x', 'X'))
        if (sep > 0) {
            val a = t.substring(0, sep).trim().toFloatOrNull()
            val b = t.substring(sep + 1).trim().toFloatOrNull()
            if (a != null && b != null && b > 0f) value = a / b
        } else {
            value = t.toFloatOrNull()
        }
        val v = value ?: return null
        if (v < 0.2f || v > 5f) return null
        return v
    }

    private fun showPresetMenu() {
        val items = arrayOf(
            "Nạp preset 1", "Nạp preset 2", "Nạp preset 3",
            "Lưu vào preset 1", "Lưu vào preset 2", "Lưu vào preset 3"
        )
        dialog().setTitle("Preset cài đặt").setItems(items) { _, which ->
            val slot = which % 3 + 1
            if (which < 3) {
                val saved = prefs.getString("preset$slot", null)
                if (saved == null) {
                    toast("Preset $slot chưa có gì")
                } else {
                    state.deserialize(saved)
                    applyOrientation()
                    applyBrightness()
                    applyLevelSensor()
                    afterChange()
                    toast("Đã nạp preset $slot")
                }
            } else {
                prefs.edit().putString("preset$slot", state.serialize()).apply()
                toast("Đã lưu preset $slot")
            }
        }.show()
    }

    // ================================================================ chọn file

    private fun pickFile(request: Int, mime: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = mime
        try {
            startActivityForResult(intent, request)
        } catch (e: Exception) {
            toast("Không mở được trình chọn file")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_LUT -> importLut(uri)
            REQ_ONION -> importOnion(uri)
        }
    }

    private fun displayName(uri: Uri, fallback: String): String {
        var name = fallback
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: fallback
            }
        } catch (e: Exception) {
            // không lấy được tên thì dùng tên mặc định
        }
        return name
    }

    private fun safeName(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            sb.append(if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' || ch == ' ') ch else '_')
        }
        return if (sb.isEmpty()) "LUT.cube" else sb.toString()
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ================================================================ chụp màn hình, ghi preview

    /** Gọi trên luồng GL: nhận khung hình sạch rồi xử lý ở luồng phụ. */
    override fun onCapture(pixels: ByteBuffer, width: Int, height: Int, purpose: Int) {
        val rotation = state.rotation
        Thread {
            try {
                val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixels.position(0)
                raw.copyPixelsFromBuffer(pixels)
                // Dữ liệu OpenGL có hàng đầu là đáy hình nên phải lật dọc
                val m = Matrix()
                m.postScale(1f, -1f)
                if (purpose == MonitorRenderer.CAPTURE_SAVE) m.postRotate(90f * rotation)
                val bmp = Bitmap.createBitmap(raw, 0, 0, width, height, m, true)
                if (purpose == MonitorRenderer.CAPTURE_ONION) {
                    runOnUiThread { setOnion(bmp, true, true) }
                } else {
                    val where = MediaSaver.saveImage(this, bmp, "CamMonitor_" + timestamp() + ".jpg")
                    runOnUiThread {
                        toast(if (where != null) "Đã lưu ảnh vào: $where" else "Không lưu được ảnh")
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Không chụp được khung hình") }
            }
        }.start()
    }

    private fun togglePreviewRecording() {
        if (previewRecording) {
            glView.queueEvent { renderer.stopRecording() }
            return
        }
        if (!cameraOpened && !ptpStreaming) {
            toast("Chưa có hình để ghi")
            return
        }
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "CamMonitor_" + timestamp() + ".mp4")
        val hint = state.rotation * 90
        glView.queueEvent { renderer.startRecording(file, hint) }
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            previewRecording = true
            overlay.previewRecording = true
            afterChange()
        }
    }

    override fun onRecordingStopped(file: File?, error: String?) {
        runOnUiThread {
            previewRecording = false
            overlay.previewRecording = false
            afterChange()
            if (error != null) toast("Ghi preview dừng: $error")
        }
        if (file == null) return
        // Chép file vào thư viện ở luồng phụ để không làm khựng hình
        Thread {
            val where = MediaSaver.publishVideo(this, file)
            runOnUiThread {
                toast(if (where != null) "Đã lưu video vào: $where" else "Không lưu được video")
            }
        }.start()
    }

    // ================================================================ thước cân bằng

    private fun applyLevelSensor() {
        val sm = sensorManager ?: return
        val want = state.level
        if (want && !sensorOn) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor != null) {
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                sensorOn = true
            }
        } else if (!want && sensorOn) {
            sm.unregisterListener(this)
            sensorOn = false
        }
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        if (sqrt(gx * gx + gy * gy) < 3f) {
            // Điện thoại gần như nằm ngửa, không đo được độ nghiêng
            overlay.levelValid = false
        } else {
            var deg = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            deg -= when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }
            while (deg > 180f) deg -= 360f
            while (deg < -180f) deg += 360f
            smoothAngle += (deg - smoothAngle) * 0.25f
            overlay.levelAngle = smoothAngle
            overlay.levelValid = true
        }
        overlay.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // không dùng
    }

    // ================================================================ vòng đời

    override fun onStart() {
        super.onStart()
        glView.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCamera()
        }
        applyLevelSensor()
        handler.removeCallbacks(diagTick)
        handler.post(diagTick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(diagTick)
        handler.removeCallbacks(unlockRunnable)
        if (previewRecording) glView.queueEvent { renderer.stopRecording() }
        stopSonyRemote()
        releaseCamera()
        if (sensorOn) {
            sensorManager?.unregisterListener(this)
            sensorOn = false
        }
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

    // ================================================================ camera UVC

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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        initCamera()
        val helper = cameraHelper ?: return
        if (cameraOpened) return
        val list = helper.deviceList
        logEvent("Chạm kết nối: thư viện thấy " + (list?.size ?: 0) + " thiết bị UVC")
        if (list != null && list.isNotEmpty()) {
            statusText.text = "Đang kết nối…"
            helper.selectDevice(list[0])
        } else {
            statusText.text = TEXT_WAITING
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice?) {
            logEvent("onAttach: " + (device?.productName ?: "null"))
            if (device == null || cameraOpened) return
            statusText.text = "Đang kết nối…"
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            logEvent("onDeviceOpen (đã có quyền USB)")
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            val helper = cameraHelper ?: return
            val sizes = helper.supportedSizeList
            logEvent("onCameraOpen: " + (sizes?.size ?: 0) + " cỡ hình")
            val best = pickSize(sizes)
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
            logEvent("Chọn " + videoW + "x" + videoH)
            attachSurfaceIfReady()
        }

        override fun onCameraClose(device: UsbDevice?) {
            logEvent("onCameraClose")
            val s = attachedSurface
            if (s != null) cameraHelper?.removeSurface(s)
            attachedSurface = null
            cameraOpened = false
        }

        override fun onDeviceClose(device: UsbDevice?) {
            cameraOpened = false
        }

        override fun onDetach(device: UsbDevice?) {
            logEvent("onDetach")
            cameraOpened = false
            attachedSurface = null
            if (!ptpStreaming) {
                infoText.text = ""
                statusText.text = TEXT_WAITING
                statusText.visibility = View.VISIBLE
            }
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            logEvent("LỖI mở camera: mã " + (e?.code ?: -1) + " " + (e?.message ?: ""))
            statusText.text = "Không mở được camera.\nRút dây, cắm lại rồi chạm vào đây."
            statusText.visibility = View.VISIBLE
        }

        override fun onCancel(device: UsbDevice?) {
            logEvent("onCancel: bị từ chối quyền USB")
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

    /** Gắn surface của OpenGL vào camera UVC khi cả hai đã sẵn sàng. */
    private fun attachSurfaceIfReady() {
        val helper = cameraHelper ?: return
        val s = surface ?: return
        if (!cameraOpened || attachedSurface === s) return
        if (surfaceUse == 2) {
            // Surface này đã dùng cho PC Remote, cần cái mới cho UVC
            requestFreshSurface()
            return
        }
        surfaceUse = 1
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
                    logEvent("Đã gắn surface, chờ hình")
                }
            }
        }
    }

    private fun requestFreshSurface() {
        if (surfaceRecreating) return
        surfaceRecreating = true
        surface = null
        glView.queueEvent { renderer.recreateSurface() }
    }

    // ================================================================ Sony PC Remote

    /** Gọi mỗi giây: thấy máy Sony ở chế độ PC Remote thì tự xin quyền và kết nối. */
    private fun checkSonyRemote() {
        if (sonyRemote != null || cameraOpened) return
        if (SystemClock.elapsedRealtime() < ptpRetryAt) return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        var found: UsbDevice? = null
        for (d in usb.deviceList.values) {
            if (SonyRemote.isSonyRemote(d)) {
                found = d
                break
            }
        }
        val dev = found
        if (dev == null) {
            ptpAskedFor = null
            return
        }
        if (!usb.hasPermission(dev)) {
            if (ptpAskedFor != dev.deviceName) {
                ptpAskedFor = dev.deviceName
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                try {
                    usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, intent, flags))
                    logEvent("Sony PC Remote: đang xin quyền USB")
                } catch (e: Exception) {
                    logEvent("Không xin được quyền USB: " + (e.message ?: ""))
                }
            }
            return
        }
        val s = surface ?: return
        if (surfaceUse == 1) {
            requestFreshSurface()
            return
        }
        surfaceUse = 2
        ptpSurface = s
        ptpW = 0
        ptpH = 0
        statusText.text = "Đang kết nối Sony PC Remote…"
        logEvent("Sony PC Remote: bắt đầu kết nối")
        val remote = SonyRemote(usb, dev, this)
        sonyRemote = remote
        remote.start()
    }

    private fun stopSonyRemote() {
        val remote = sonyRemote ?: return
        ptpSurface = null
        remote.stop()
    }

    override fun onRemoteLog(msg: String) {
        runOnUiThread { logEvent(msg) }
    }

    /** Chạy trên luồng của SonyRemote: giải mã JPEG rồi vẽ vào surface của OpenGL. */
    override fun onRemoteFrame(data: ByteArray, offset: Int, length: Int) {
        val s = ptpSurface ?: return
        ptpOptions.inMutable = true
        ptpOptions.inBitmap = ptpBitmap
        var bmp: Bitmap? = null
        try {
            bmp = BitmapFactory.decodeByteArray(data, offset, length, ptpOptions)
        } catch (e: IllegalArgumentException) {
            // Kích thước hình đổi nên không dùng lại được bitmap cũ
            ptpOptions.inBitmap = null
            try {
                bmp = BitmapFactory.decodeByteArray(data, offset, length, ptpOptions)
            } catch (e2: Exception) {
                bmp = null
            }
        }
        val frame = bmp ?: return
        ptpBitmap = frame
        if (frame.width != ptpW || frame.height != ptpH) {
            ptpW = frame.width
            ptpH = frame.height
            val w = ptpW
            val h = ptpH
            runOnUiThread { onRemoteVideoSize(w, h) }
        }
        try {
            val canvas = s.lockCanvas(null)
            ptpDst.set(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(frame, null, ptpDst, null)
            s.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // surface vừa bị thay, bỏ khung hình này
        }
    }

    private fun onRemoteVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        overlay.videoW = w
        overlay.videoH = h
        overlay.invalidate()
        glView.queueEvent { renderer.applyVideoSize(w, h) }
        ptpStreaming = true
        statusText.visibility = View.GONE
        cameraBar.visibility = View.VISIBLE
        infoText.text = w.toString() + "×" + h.toString() + " · PC Remote"
        logEvent("PC Remote: có hình " + w + "x" + h)
    }

    override fun onRemoteInfo(info: SonyInfo) {
        runOnUiThread {
            lastInfo = info
            camInfoText.text = info.summary()
            camInfoText.visibility = if (ptpStreaming) View.VISIBLE else View.GONE
            overlay.cameraRecording = info.recording
            for (u in labelUpdaters) u()
            overlay.invalidate()
        }
    }

    override fun onRemoteStopped(error: String?) {
        runOnUiThread {
            sonyRemote = null
            ptpSurface = null
            ptpStreaming = false
            lastInfo = null
            overlay.cameraRecording = false
            camInfoText.visibility = View.GONE
            cameraBar.visibility = View.GONE
            ptpRetryAt = SystemClock.elapsedRealtime() + 3000
            if (error != null) logEvent("PC Remote dừng: $error")
            if (!cameraOpened) {
                infoText.text = ""
                statusText.text = TEXT_WAITING
                statusText.visibility = View.VISIBLE
            }
            overlay.invalidate()
        }
    }

    private fun showWhiteBalanceMenu() {
        val remote = sonyRemote ?: return
        val options = lastInfo?.wbOptions
        if (options == null || options.isEmpty()) {
            toast("Máy ảnh chưa gửi danh sách cân bằng trắng")
            return
        }
        val names = Array(options.size) { SonyRemote.whiteBalanceName(options[it]) }
        dialog().setTitle("Cân bằng trắng").setItems(names) { _, which ->
            remote.setWhiteBalance(options[which])
        }.show()
    }

    // ================================================================ callback từ renderer (luồng GL)

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        runOnUiThread {
            surface = Surface(surfaceTexture)
            surfaceUse = 0
            surfaceRecreating = false
            attachSurfaceIfReady()
        }
    }

    override fun onAnalysis(pixels: ByteArray, width: Int, height: Int) {
        runOnUiThread { overlay.setAnalysis(pixels, width, height) }
    }

    override fun onFrameAvailable() {
        glView.requestRender()
    }

    // ================================================================ bảng chẩn đoán

    private fun logEvent(msg: String) {
        events.add(msg)
        while (events.size > 8) events.removeAt(0)
        refreshDiag()
    }

    /** Liệt kê mọi thiết bị USB mà Android đang thấy, kèm nhật ký kết nối. */
    private fun refreshDiag() {
        if ((cameraOpened && attachedSurface != null) || ptpStreaming) {
            diagText.visibility = View.GONE
            return
        }
        diagText.visibility = View.VISIBLE
        val sb = StringBuilder()
        val camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        sb.append("Quyền Camera: ").append(if (camOk) "có" else "CHƯA CÓ").append('\n')
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values
        sb.append("Thiết bị USB Android thấy: ").append(devices.size).append('\n')
        for (d in devices) {
            val classes = ArrayList<Int>()
            for (i in 0 until d.interfaceCount) {
                val c = d.getInterface(i).interfaceClass
                if (!classes.contains(c)) classes.add(c)
            }
            val isUvc = classes.contains(14) || d.deviceClass == 14
            sb.append("• ")
                .append(String.format("%04X:%04X", d.vendorId, d.productId))
                .append(" ").append(d.productName ?: "?")
                .append(" | giao diện ").append(classes.joinToString(","))
                .append(if (isUvc) " | UVC: CÓ" else "")
                .append(if (SonyRemote.isSonyRemote(d)) " | PC Remote: CÓ" else "")
                .append(if (classes.contains(8)) " | đang là Ổ ĐĨA (Mass Storage), cần đổi sang PC Remote" else "")
                .append(if (usb.hasPermission(d)) " | đã cấp quyền" else " | chưa cấp quyền")
                .append('\n')
        }
        if (events.isNotEmpty()) {
            sb.append("Nhật ký:\n")
            for (e in events) sb.append("  ").append(e).append('\n')
        }
        diagText.text = sb.toString().trimEnd()
    }

    // ================================================================ lưu cài đặt

    private fun loadState() {
        val saved = prefs.getString("state", null)
        if (saved != null) {
            state.deserialize(saved)
        } else {
            // Lần chạy đầu: bật sáng tối đa vì monitor thường dùng ngoài trời
            state.maxBright = true
        }
        currentLut = prefs.getString("lutFile", "") ?: ""
    }

    private fun saveState() {
        prefs.edit()
            .putString("state", state.serialize())
            .putString("lutFile", currentLut)
            .apply()
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_LUT = 2
        private const val REQ_ONION = 3
        private const val ACTION_USB_PERMISSION = "com.dq.cammonitor.USB_PERMISSION"
        private const val TEXT_WAITING =
            "Chưa thấy máy ảnh.\nSony: đặt MENU > Network > PC Remote Function > PC Remote = On (kết nối USB), rồi cắm dây USB-C.\nCapture card HDMI: cắm vào là tự nhận. Chạm vào đây để thử kết nối lại."

        private val ROT_COS = floatArrayOf(1f, 0f, -1f, 0f)
        private val ROT_SIN = floatArrayOf(0f, 1f, 0f, -1f)

        private val MODE_NAMES = arrayOf("Thường", "False color", "Đen trắng", "Kênh đỏ", "Kênh lục", "Kênh lam")
        private val PEAK_NAMES = arrayOf("Tắt", "Đỏ", "Xanh lá", "Vàng", "Trắng", "Xanh dương")
        private val SENS_NAMES = arrayOf("Thấp", "Vừa", "Cao")
        private val WIDTH_NAMES = arrayOf("Mảnh", "Vừa", "Dày")
        private val ZEBRA_NAMES = arrayOf("Tắt", "70%", "95%", "100%")
        private val HIST_NAMES = arrayOf("Tắt", "Sáng", "RGB")
        private val GRID_NAMES = arrayOf("Tắt", "1/3", "Tâm", "Tất cả")
        private val ZOOM_NAMES = arrayOf("1×", "2×", "4×")
        private val ROTATION_NAMES = arrayOf("0°", "90°", "180°", "270°")
        private val ORIENT_NAMES = arrayOf("Ngang", "Dọc")
        private val FLIP_NAMES = arrayOf("Không", "Ngang", "Dọc", "Cả hai")
        private val SQUEEZE_NAMES = arrayOf("1×", "1.33×", "1.5×", "2×")
        private val ONION_NAMES = arrayOf("Tắt", "25%", "50%", "75%")
    }
}
