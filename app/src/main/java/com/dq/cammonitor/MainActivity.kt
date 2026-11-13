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
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

class MainActivity : Activity(), MonitorRenderer.Listener, SonyRemote.Listener, WebServer.Listener, SensorEventListener {

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
    private lateinit var toolBar: HorizontalScrollView
    private lateinit var sideButtons: LinearLayout
    private lateinit var cameraToggle: Button
    private var showTools = true
    private var showCamera = true

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

    // Giải mã JPEG ở luồng riêng để luồng USB lấy khung kế tiếp song song
    private val frameLock = Object()
    private var slot = ByteArray(0)
    private var slotLen = 0
    private var slotFull = false
    private var work = ByteArray(0)
    @Volatile private var decoding = false
    private var decodeThread: Thread? = null
    @Volatile private var fpsReceived = 0
    @Volatile private var fpsShown = 0
    @Volatile private var decodeMs = 0L
    private var liveViewLow = false
    private var recStartAt = 0L
    private var batteryWarned = false

    /** Phiên PC Remote hiện tại có lấy hình hay chỉ điều khiển (khi hình đi qua HDMI). */
    private var remoteLiveView = true
    @Volatile private var ptpControl = false

    // ---- lưu ảnh vừa chụp về điện thoại
    private var saveShots = true
    private var shotsSaved = 0

    // ---- chụp theo khoảng thời gian
    private var intervalOn = false
    private var intervalSec = 5
    private var intervalTotal = 0
    private var intervalDone = 0
    private var intervalNextAt = 0L
    private val intervalTick = object : Runnable {
        override fun run() {
            if (!intervalOn) return
            val remote = sonyRemote
            if (remote == null || !remoteReady()) {
                stopInterval("Dừng chụp hẹn giờ: mất kết nối máy ảnh")
                return
            }
            remote.capturePhoto()
            intervalDone++
            if (intervalTotal > 0 && intervalDone >= intervalTotal) {
                stopInterval("Đã chụp xong " + intervalDone + " ảnh")
                return
            }
            // Tính theo mốc thời gian để không bị trôi dần
            intervalNextAt += intervalSec * 1000L
            val wait = (intervalNextAt - SystemClock.elapsedRealtime()).coerceAtLeast(200L)
            handler.postDelayed(this, wait)
            refreshCamInfo()
            for (u in labelUpdaters) u()
        }
    }

    // ---- máy phụ xem qua Wi-Fi: 0 = tắt, 1 = chỉ xem, 2 = được điều khiển
    @Volatile private var webMode = 0
    @Volatile private var webServer: WebServer? = null
    @Volatile private var webInfo = "Chưa nối máy ảnh"
    private var webPin = ""

    // ---- máy nhắc chữ
    private lateinit var prompter: TeleprompterView
    private var tpOn = false
    private var tpText = ""
    private var tpSpeed = 2
    private var tpSize = 1
    private var tpMirror = false
    private var tpBackdrop = 1

    private var uvcSizePref = ""

    // ---- đếm ngược trước khi quay/chụp
    private lateinit var countdownText: TextView
    private var countdownIndex = 0
    private var countdownLeft = 0
    private var countdownAction: (() -> Unit)? = null
    private val countdownTick = object : Runnable {
        override fun run() {
            if (countdownAction == null) return
            countdownLeft--
            if (countdownLeft <= 0) {
                val action = countdownAction
                cancelCountdown()
                action?.invoke()
            } else {
                countdownText.text = countdownLeft.toString()
                handler.postDelayed(this, 1000)
            }
        }
    }

    // ---- danh sách cảnh quay
    private lateinit var shotText: TextView
    private val shotNames = ArrayList<String>()
    private val shotDone = ArrayList<Boolean>()
    private var recordAudio = false

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
            updateFpsInfo()
            refreshCamInfo()
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
        // Chọn cấu hình EGL cho phép ghi hình (cần để ghi preview bằng bộ mã hoá video);
        // nếu máy không có thì dùng cấu hình thường.
        glView.setEGLConfigChooser(object : GLSurfaceView.EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                val base = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_RENDERABLE_TYPE, 4
                )
                val recordable = base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE)
                val plain = base + intArrayOf(EGL10.EGL_NONE)
                for (attrs in arrayOf(recordable, plain)) {
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val count = IntArray(1)
                    if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) {
                        val c = configs[0]
                        if (c != null) return c
                    }
                }
                throw IllegalStateException("Không tìm được cấu hình hiển thị phù hợp")
            }
        })
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(glView, matchParent())

        overlay = OverlayView(this, state)
        root.addView(overlay, matchParent())

        prompter = TeleprompterView(this)
        prompter.visibility = View.GONE
        root.addView(prompter, matchParent())
        applyPrompter(false)

        statusText = TextView(this)
        statusText.setTextColor(Color.WHITE)
        statusText.textSize = 15f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(dp(24), dp(16), dp(24), dp(16))
        statusText.setBackgroundColor(Color.argb(170, 0, 0, 0))
        statusText.text = TEXT_WAITING
        statusText.setOnClickListener {
            if (sonyRemote != null) resetPtpConnection() else connectFirstDevice()
        }
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

        // Cảnh quay kế tiếp ở góc trên phải; chạm vào để đánh dấu đã quay xong
        shotText = TextView(this)
        shotText.setTextColor(Color.WHITE)
        shotText.textSize = 13f
        shotText.maxLines = 2
        shotText.maxWidth = dp(260)
        shotText.setPadding(dp(8), dp(4), dp(8), dp(4))
        shotText.setBackgroundColor(Color.argb(140, 0, 60, 110))
        shotText.visibility = View.GONE
        shotText.setOnClickListener { if (!locked) markCurrentShotDone() }
        val shotLp = wrap(Gravity.TOP or Gravity.END)
        shotLp.topMargin = dp(28)
        shotLp.rightMargin = dp(8)
        root.addView(shotText, shotLp)

        countdownText = TextView(this)
        countdownText.setTextColor(Color.WHITE)
        countdownText.textSize = 140f
        countdownText.setShadowLayer(12f, 0f, 0f, Color.BLACK)
        countdownText.gravity = Gravity.CENTER
        countdownText.visibility = View.GONE
        // Chạm vào số đang đếm để hủy
        countdownText.setOnClickListener { cancelCountdown() }
        root.addView(countdownText, wrap(Gravity.CENTER))

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

        toolBar = HorizontalScrollView(this)
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

        // Hai nút nhỏ luôn hiện ở mép trái để ẩn/hiện từng thanh, không che hình
        sideButtons = LinearLayout(this)
        sideButtons.orientation = LinearLayout.VERTICAL
        val toolsToggle = sideButton("Công cụ") {
            showTools = !showTools
            updateBars()
        }
        cameraToggle = sideButton("Máy ảnh") {
            showCamera = !showCamera
            updateBars()
        }
        sideButtons.addView(toolsToggle)
        sideButtons.addView(cameraToggle)
        val sideLp = wrap(Gravity.START or Gravity.CENTER_VERTICAL)
        sideLp.leftMargin = dp(4)
        root.addView(sideButtons, sideLp)

        overlay.setOnTouchListener { _, event -> handleTouch(event) }
        setContentView(root)
        refreshShotText()
        updateBars()
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
        addButton(row, { "Ba khung 9:16·4:5·1:1: " + onOff(state.multiFrame) }) {
            state.multiFrame = !state.multiFrame
        }
        addButton(row, { "Vùng an toàn: " + MonitorState.SAFE_NAMES[state.safeZone] }) {
            state.safeZone = (state.safeZone + 1) % MonitorState.SAFE_NAMES.size
        }
        // Chạm: xem và tick cảnh. Giữ lâu: sửa danh sách.
        addButton(row, { shotLabel() }, { showShotEditor() }) { showShotList() }
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
        addButton(row, { "Thu tiếng khi ghi: " + onOff(recordAudio) }) {
            if (!recordAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            } else {
                recordAudio = !recordAudio
                prefs.edit().putBoolean("recordAudio", recordAudio).apply()
                if (recordAudio) {
                    toast("Bản ghi preview sẽ có tiếng: lấy từ capture card HDMI nếu điện thoại nhận được, không thì từ micro điện thoại.")
                }
            }
        }
        addButton(row, { "Sáng tối đa: " + onOff(state.maxBright) }) {
            state.maxBright = !state.maxBright
            applyBrightness()
        }
        // Chạm: bật/tắt máy nhắc chữ. Giữ lâu: nhập chữ, tốc độ, cỡ chữ, lật gương.
        addButton(row, { "Nhắc chữ: " + onOff(tpOn) }, { showPrompterMenu() }) {
            if (tpText.isBlank()) {
                showPrompterEditor()
            } else {
                tpOn = !tpOn
                applyPrompter(true)
            }
        }
        // Chạm: đổi chế độ máy phụ. Giữ lâu: xem lại địa chỉ.
        addButton(row, { "Máy phụ Wi-Fi: " + WEB_NAMES[webMode] }, { showWebAddress() }) {
            val wasOff = webMode == 0
            webMode = (webMode + 1) % WEB_NAMES.size
            applyWebMode()
            if (wasOff && webMode != 0 && webServer != null) showWebAddress()
        }
        addButton(row, { "Cỡ hình HDMI" }) { showUvcSizeMenu() }
        addButton(row, { "Preset" }) { showPresetMenu() }
        addButton(row, { "Khóa cảm ứng" }) { setLocked(true) }
        return row
    }

    private fun buildCameraRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(6), dp(4), dp(6), dp(4))

        addButton(row, { if (lastInfo?.recording == true) "■ Dừng quay" else "● Quay" }) {
            if (lastInfo?.recording == true) {
                // Dừng quay thì làm ngay, không đếm
                cancelCountdown()
                sonyRemote?.toggleRecord()
            } else {
                withCountdown { sonyRemote?.toggleRecord() }
            }
        }
        addButton(row, { "Chụp ảnh" }) { withCountdown { sonyRemote?.capturePhoto() } }
        addButton(row, { "Đếm ngược: " + COUNTDOWN_NAMES[countdownIndex] }) {
            countdownIndex = (countdownIndex + 1) % COUNTDOWN_NAMES.size
            prefs.edit().putInt("countdown", countdownIndex).apply()
        }
        // Chạm: bắt đầu hoặc dừng. Giữ lâu: đặt lại khoảng cách và số ảnh.
        addButton(row, { intervalLabel() }, { showIntervalDialog() }) {
            if (intervalOn) stopInterval("Đã dừng chụp hẹn giờ sau " + intervalDone + " ảnh") else showIntervalDialog()
        }
        addButton(row, { "Lưu ảnh về máy: " + onOff(saveShots) }) {
            saveShots = !saveShots
            sonyRemote?.saveImages = saveShots
            prefs.edit().putBoolean("saveShots", saveShots).apply()
            if (saveShots) {
                toast("Trên máy ảnh đặt MENU > Network > PC Remote Function > Still Img. Save Dest. = PC+Camera. Ảnh chụp xong sẽ tự lưu vào album CamMonitor.")
            }
        }
        addButton(row, { "Lấy nét" }) { sonyRemote?.focus() }
        // Chỉnh nét tay từng bước, máy ảnh phải ở MF hoặc DMF
        addButton(row, { "Nét ◀◀" }) { sonyRemote?.manualFocus(-MF_BIG) }
        addButton(row, { "Nét ◀" }) { sonyRemote?.manualFocus(-MF_SMALL) }
        addButton(row, { "Nét ▶" }) { sonyRemote?.manualFocus(MF_SMALL) }
        addButton(row, { "Nét ▶▶" }) { sonyRemote?.manualFocus(MF_BIG) }
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
        // Thử nghiệm: hạ chất lượng hình xem để máy ảnh gửi nhiều khung hình hơn
        addButton(row, { "Hình xem: " + (if (liveViewLow) "Nhẹ" else "Đẹp") }) {
            liveViewLow = !liveViewLow
            sonyRemote?.setLiveViewQuality(liveViewLow)
        }
        addButton(row, { "Đặt lại kết nối" }) { resetPtpConnection() }
        addButton(row, { "Chẩn đoán máy ảnh" }) { showCameraDiag() }
        return row
    }

    /** Ngắt phiên PC Remote hiện tại và kết nối lại ngay, không cần cắm lại cáp. */
    private fun resetPtpConnection() {
        logEvent("Đặt lại kết nối PC Remote")
        toast("Đang kết nối lại máy ảnh…")
        ptpAskedFor = null
        ptpRetryAt = 0L
        if (sonyRemote != null) {
            stopSonyRemote()
        } else {
            checkSonyRemote()
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
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

    private fun sideButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.setAllCaps(false)
        b.textSize = 10f
        b.text = label
        b.alpha = 0.75f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), 0, dp(8), 0)
        b.setOnClickListener { action() }
        b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        return b
    }

    /** Chạm vào hình: ẩn hoặc hiện cả hai thanh cùng lúc. */
    private fun setBarsVisible(visible: Boolean) {
        showTools = visible
        showCamera = visible
        updateBars()
    }

    /** Áp trạng thái ẩn/hiện của từng thanh; thanh máy ảnh chỉ có khi nối PC Remote. */
    private fun updateBars() {
        toolBar.visibility = if (showTools && !locked) View.VISIBLE else View.GONE
        val ready = remoteReady()
        cameraBar.visibility = if (showCamera && ready && !locked) View.VISIBLE else View.GONE
        cameraToggle.visibility = if (ready) View.VISIBLE else View.GONE
        sideButtons.visibility = if (locked) View.GONE else View.VISIBLE
        barsVisible = showTools || (showCamera && ready)
        prefs.edit().putBoolean("showTools", showTools).putBoolean("showCamera", showCamera).apply()
        hideSystemBars()
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        overlay.locked = lock
        updateBars()
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
        val audio = recordAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val device = if (audio) usbAudioInput() else null
        glView.queueEvent { renderer.startRecording(file, hint, audio, device) }
    }

    /** Đầu thu tiếng qua USB (tiếng HDMI của capture card), hoặc null nếu điện thoại không thấy. */
    private fun usbAudioInput(): AudioDeviceInfo? {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) return d
        }
        return null
    }

    override fun onRecordingStarted(audioNote: String?) {
        runOnUiThread {
            previewRecording = true
            overlay.previewRecording = true
            afterChange()
            if (audioNote != null) {
                toast("Đang ghi preview. $audioNote")
                logEvent("Ghi preview: $audioNote")
            }
        }
    }

    override fun onRecordingStopped(file: File?, error: String?) {
        runOnUiThread {
            previewRecording = false
            overlay.previewRecording = false
            afterChange()
            if (error != null) {
                toast("Ghi preview dừng: $error")
                logEvent("Ghi preview lỗi: $error")
            }
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
        applyWebMode()
        handler.removeCallbacks(diagTick)
        handler.post(diagTick)
    }

    override fun onStop() {
        super.onStop()
        saveState()
        handler.removeCallbacks(diagTick)
        handler.removeCallbacks(unlockRunnable)
        if (intervalOn) stopInterval(null)
        cancelCountdown()
        prompter.pause()
        stopWebServer()
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
        if (requestCode == REQ_AUDIO) {
            recordAudio = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            prefs.edit().putBoolean("recordAudio", recordAudio).apply()
            if (!recordAudio) toast("Chưa có quyền Micro nên bản ghi preview sẽ không có tiếng")
            for (u in labelUpdaters) u()
            return
        }
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
            infoText.text = videoW.toString() + "×" + videoH.toString() + " · HDMI/UVC"
            logEvent("Chọn " + videoW + "x" + videoH)
            // Có hình từ capture card: phiên PC Remote đang lấy hình phải nhường surface,
            // sau vài giây nó tự nối lại ở chế độ chỉ điều khiển.
            if (sonyRemote != null && remoteLiveView) {
                ptpRetryAt = 0L
                stopSonyRemote()
            }
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
            // Mất hình HDMI: cho PC Remote nối lại ở chế độ có hình
            if (sonyRemote != null && !remoteLiveView) stopSonyRemote()
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
        // Cỡ hình người dùng đã chọn lần trước
        if (uvcSizePref.isNotEmpty()) {
            val saved = pool.firstOrNull { (it.width.toString() + "x" + it.height) == uvcSizePref }
            if (saved != null) return saved
        }
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
        if (sonyRemote != null) return
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
        if (cameraOpened) {
            // Hình đang lấy từ capture card HDMI: PC Remote chỉ dùng để điều khiển
            logEvent("Sony PC Remote: nối để điều khiển, hình lấy từ HDMI")
            remoteLiveView = false
            val control = SonyRemote(usb, dev, this, false, cacheDir)
            control.saveImages = saveShots
            sonyRemote = control
            control.start()
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
        remoteLiveView = true
        val remote = SonyRemote(usb, dev, this, true, cacheDir)
        remote.saveImages = saveShots
        sonyRemote = remote
        startDecoder()
        remote.start()
    }

    /** Đã gửi được lệnh cho máy ảnh, dù hình đi qua PC Remote hay HDMI. */
    private fun remoteReady(): Boolean = ptpStreaming || ptpControl

    private fun stopSonyRemote() {
        val remote = sonyRemote ?: return
        ptpSurface = null
        stopDecoder()
        remote.stop()
    }

    private fun startDecoder() {
        if (decodeThread != null) return
        decoding = true
        val t = Thread({ decodeLoop() }, "JpegDecoder")
        decodeThread = t
        t.start()
    }

    private fun stopDecoder() {
        decoding = false
        synchronized(frameLock) {
            slotFull = false
            frameLock.notifyAll()
        }
        decodeThread = null
    }

    private fun decodeLoop() {
        while (decoding) {
            var len = 0
            synchronized(frameLock) {
                while (!slotFull && decoding) {
                    try {
                        frameLock.wait(500)
                    } catch (e: InterruptedException) {
                        // bỏ qua
                    }
                }
                if (slotFull) {
                    // Đổi chỗ hai bộ đệm: luồng USB ghi tiếp vào cái còn lại
                    val tmp = work
                    work = slot
                    slot = tmp
                    len = slotLen
                    slotFull = false
                }
            }
            if (len > 0 && decoding) decodeAndDraw(work, len)
        }
    }

    /** Mỗi giây cập nhật dòng thông tin: số khung máy ảnh gửi và số khung hiện được. */
    private fun updateFpsInfo() {
        if (!ptpStreaming) return
        val received = fpsReceived
        val shown = fpsShown
        fpsReceived = 0
        fpsShown = 0
        val sb = StringBuilder()
        sb.append(videoW).append('×').append(videoH)
            .append(" · nhận ").append(received).append(" / hiện ").append(shown).append(" fps")
        val remote = sonyRemote
        if (remote != null) {
            // Số đo chi tiết: thời gian lấy và giải mã mỗi khung, cỡ file, số lần máy ảnh chưa có khung mới
            val frames = remote.statFrames
            val fetch = remote.statFetchMs
            val bytes = remote.statBytes
            val misses = remote.statMisses
            remote.statFrames = 0
            remote.statFetchMs = 0L
            remote.statBytes = 0L
            remote.statMisses = 0
            val dec = decodeMs
            decodeMs = 0L
            if (frames > 0) {
                sb.append(" · lấy ").append(fetch / frames).append("ms")
                    .append(" · ").append(bytes / frames / 1024).append("KB")
            }
            if (shown > 0) sb.append(" · giải mã ").append(dec / shown).append("ms")
            sb.append(" · chờ ").append(misses)
        }
        infoText.text = sb.toString()
    }

    override fun onRemoteLog(msg: String) {
        runOnUiThread { logEvent(msg) }
    }

    /** Chạy trên luồng của SonyRemote: chép khung hình cho luồng giải mã rồi quay lại lấy khung kế. */
    override fun onRemoteFrame(data: ByteArray, offset: Int, length: Int) {
        fpsReceived++
        webServer?.publishFrame(data, offset, length)
        synchronized(frameLock) {
            if (slot.size < length) slot = ByteArray(length + 64 * 1024)
            System.arraycopy(data, offset, slot, 0, length)
            slotLen = length
            // Nếu khung trước chưa kịp giải mã thì bị ghi đè: luôn hiện khung mới nhất
            slotFull = true
            frameLock.notifyAll()
        }
    }

    /** Chạy trên luồng giải mã: giải mã JPEG rồi vẽ vào surface của OpenGL. */
    private fun decodeAndDraw(data: ByteArray, length: Int) {
        val offset = 0
        val t0 = SystemClock.elapsedRealtime()
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
            fpsShown++
            decodeMs += SystemClock.elapsedRealtime() - t0
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
        updateBars()
        infoText.text = w.toString() + "×" + h.toString() + " · PC Remote"
        fpsReceived = 0
        fpsShown = 0
        logEvent("PC Remote: có hình " + w + "x" + h)
    }

    override fun onRemoteInfo(info: SonyInfo) {
        runOnUiThread {
            lastInfo = info
            if (info.recording) {
                if (recStartAt == 0L) recStartAt = SystemClock.elapsedRealtime()
            } else {
                recStartAt = 0L
            }
            if (info.battery in 0..BATTERY_LOW) {
                if (!batteryWarned) {
                    batteryWarned = true
                    logEvent("Pin máy ảnh yếu: " + info.battery + "%")
                    toast("Pin máy ảnh còn " + info.battery + "%")
                }
            } else if (info.battery > BATTERY_LOW) {
                batteryWarned = false
            }
            refreshCamInfo()
            overlay.cameraRecording = info.recording
            for (u in labelUpdaters) u()
            overlay.invalidate()
        }
    }

    /** Dòng thông số máy ảnh ở góc trên trái; gọi mỗi giây để đồng hồ REC chạy đều. */
    private fun refreshCamInfo() {
        val info = lastInfo
        if (info == null || !remoteReady()) {
            camInfoText.visibility = View.GONE
            webInfo = "Máy chính chưa nối điều khiển máy ảnh"
            return
        }
        val sb = StringBuilder(info.summary())
        if (info.recording && recStartAt > 0L) {
            sb.append("\n● REC ").append(formatDuration(SystemClock.elapsedRealtime() - recStartAt))
        }
        if (info.battery in 0..BATTERY_LOW) {
            sb.append("\n⚠ Pin máy ảnh yếu: ").append(info.battery).append('%')
        }
        if (intervalOn) {
            sb.append("\nChụp hẹn giờ: ").append(intervalDone)
            if (intervalTotal > 0) sb.append('/').append(intervalTotal)
            val left = ((intervalNextAt - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
            sb.append(" ảnh · ảnh kế sau ").append(left).append(" giây")
        }
        if (shotsSaved > 0) sb.append("\nĐã lưu về máy: ").append(shotsSaved).append(" ảnh")
        val text = sb.toString()
        camInfoText.text = text
        camInfoText.visibility = View.VISIBLE
        webInfo = text + (if (webMode == 2) "" else "\n(Máy chính đang để chế độ chỉ xem)")
    }

    override fun onRemoteReady(liveView: Boolean) {
        runOnUiThread {
            if (!liveView) {
                ptpControl = true
                updateBars()
            }
        }
    }

    /** Chạy trên luồng của SonyRemote: chuyển ảnh vừa tải về vào thư viện ở luồng phụ. */
    override fun onRemoteImage(file: File, name: String) {
        Thread {
            val where = MediaSaver.publishPhoto(this, file, safeName(name))
            runOnUiThread {
                if (where != null) {
                    shotsSaved++
                    refreshCamInfo()
                    if (!intervalOn) toast("Đã lưu " + name + " vào: " + where)
                } else {
                    logEvent("Không lưu được ảnh " + name)
                    toast("Không lưu được ảnh " + name)
                }
            }
        }.start()
    }

    override fun onRemoteStopped(error: String?) {
        runOnUiThread {
            sonyRemote = null
            ptpSurface = null
            ptpStreaming = false
            ptpControl = false
            stopDecoder()
            lastInfo = null
            recStartAt = 0L
            if (intervalOn) stopInterval("Dừng chụp hẹn giờ: mất kết nối máy ảnh")
            cancelCountdown()
            overlay.cameraRecording = false
            camInfoText.visibility = View.GONE
            updateBars()
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

    /** Bảng giá trị thô vài thuộc tính của máy ảnh, để đối chiếu khi cần thêm tính năng. */
    private fun showCameraDiag() {
        val raw = sonyRemote?.lastRaw
        if (raw == null || raw.isEmpty()) {
            toast("Chưa đọc được thông số từ máy ảnh")
            return
        }
        val sb = StringBuilder()
        sb.append("Chụp màn hình bảng này gửi cho người viết app, kèm số ảnh còn lại và thời gian quay còn lại đang hiện trên màn máy ảnh.\n\n")
        for (code in raw.keys.sorted()) {
            if (code < 0xD200 || code > 0xD2FF) continue
            if (code in 0xD240..0xD25F || code == 0xD215 || code == 0xD218 || code == 0xD21D) {
                val v = raw[code] ?: continue
                sb.append(String.format("%04X = %d (0x%X)\n", code, v, v))
            }
        }
        sb.append("\nTổng số thuộc tính: ").append(raw.size)
        dialog().setTitle("Chẩn đoán máy ảnh").setMessage(sb.toString()).setPositiveButton("Đóng", null).show()
    }

    // ================================================================ đếm ngược trước khi quay/chụp

    /** Chạy [action] ngay, hoặc sau khi đếm ngược nếu đang bật. Bấm lần nữa khi đang đếm thì hủy. */
    private fun withCountdown(action: () -> Unit) {
        if (countdownAction != null) {
            cancelCountdown()
            toast("Đã hủy đếm ngược")
            return
        }
        val seconds = COUNTDOWN_SECONDS[countdownIndex]
        if (seconds <= 0 || sonyRemote == null) {
            action()
            return
        }
        countdownAction = action
        countdownLeft = seconds
        countdownText.text = seconds.toString()
        countdownText.visibility = View.VISIBLE
        handler.removeCallbacks(countdownTick)
        handler.postDelayed(countdownTick, 1000)
    }

    private fun cancelCountdown() {
        countdownAction = null
        handler.removeCallbacks(countdownTick)
        countdownText.visibility = View.GONE
    }

    // ================================================================ danh sách cảnh quay

    private fun loadShots() {
        shotNames.clear()
        shotDone.clear()
        val saved = prefs.getString("shotList", "") ?: ""
        for (line in saved.split('\n')) {
            if (line.length < 3 || line[1] != '|') continue
            shotNames.add(line.substring(2))
            shotDone.add(line[0] == '1')
        }
    }

    private fun saveShots() {
        val sb = StringBuilder()
        for (i in shotNames.indices) {
            sb.append(if (shotDone[i]) '1' else '0').append('|').append(shotNames[i]).append('\n')
        }
        prefs.edit().putString("shotList", sb.toString()).apply()
    }

    private fun shotsDoneCount(): Int = shotDone.count { it }

    private fun shotLabel(): String {
        if (shotNames.isEmpty()) return "Cảnh quay"
        return "Cảnh quay " + shotsDoneCount() + "/" + shotNames.size
    }

    /** Cập nhật ô "cảnh kế tiếp" ở góc trên phải. */
    private fun refreshShotText() {
        val next = shotDone.indexOf(false)
        if (shotNames.isEmpty()) {
            shotText.visibility = View.GONE
        } else if (next < 0) {
            shotText.text = "✓ Đã quay đủ " + shotNames.size + " cảnh"
            shotText.visibility = View.VISIBLE
        } else {
            shotText.text = "Cảnh " + (next + 1) + "/" + shotNames.size + ": " + shotNames[next] + "\n(chạm khi quay xong)"
            shotText.visibility = View.VISIBLE
        }
        for (u in labelUpdaters) u()
    }

    private fun markCurrentShotDone() {
        val next = shotDone.indexOf(false)
        if (next < 0) return
        shotDone[next] = true
        saveShots()
        refreshShotText()
    }

    private fun showShotList() {
        if (shotNames.isEmpty()) {
            showShotEditor()
            return
        }
        val names = Array(shotNames.size) { (it + 1).toString() + ". " + shotNames[it] }
        val checked = BooleanArray(shotNames.size) { shotDone[it] }
        dialog().setTitle("Danh sách cảnh quay")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (which in shotDone.indices) {
                    shotDone[which] = isChecked
                    saveShots()
                    refreshShotText()
                }
            }
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Sửa danh sách") { _, _ -> showShotEditor() }
            .setNegativeButton("Bỏ tick hết") { _, _ ->
                for (i in shotDone.indices) shotDone[i] = false
                saveShots()
                refreshShotText()
            }
            .show()
    }

    private fun showShotEditor() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.minLines = 5
        input.maxLines = 10
        input.gravity = Gravity.TOP or Gravity.START
        input.hint = "Mỗi dòng một cảnh, ví dụ:\nToàn cảnh quán\nCận tay pha cà phê\nKhách cầm ly cười"
        input.setText(shotNames.joinToString("\n"))
        dialog().setTitle("Sửa danh sách cảnh quay").setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                // Cảnh nào giữ nguyên tên thì giữ nguyên trạng thái đã quay
                val old = HashMap<String, Boolean>()
                for (i in shotNames.indices) old[shotNames[i]] = shotDone[i]
                shotNames.clear()
                shotDone.clear()
                for (line in input.text.toString().split('\n')) {
                    val name = line.trim()
                    if (name.isEmpty() || shotNames.size >= 200) continue
                    shotNames.add(name)
                    shotDone.add(old[name] ?: false)
                }
                saveShots()
                refreshShotText()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ================================================================ chụp theo khoảng thời gian

    private fun intervalLabel(): String {
        if (!intervalOn) return "Chụp hẹn giờ"
        val total = if (intervalTotal > 0) "/$intervalTotal" else ""
        return "■ Dừng hẹn giờ ($intervalDone$total)"
    }

    private fun showIntervalDialog() {
        if (intervalOn) {
            toast("Dừng chụp hẹn giờ trước khi đổi cài đặt")
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val secLabel = TextView(this)
        secLabel.text = "Mỗi ảnh cách nhau (giây, từ 2 đến 3600)"
        val secInput = EditText(this)
        secInput.inputType = InputType.TYPE_CLASS_NUMBER
        secInput.setText(intervalSec.toString())
        val countLabel = TextView(this)
        countLabel.text = "Số ảnh (0 = chụp đến khi bấm dừng)"
        val countInput = EditText(this)
        countInput.inputType = InputType.TYPE_CLASS_NUMBER
        countInput.setText(intervalTotal.toString())
        box.addView(secLabel)
        box.addView(secInput)
        box.addView(countLabel)
        box.addView(countInput)
        dialog().setTitle("Chụp theo khoảng thời gian").setView(box)
            .setPositiveButton("Bắt đầu") { _, _ ->
                val sec = secInput.text.toString().trim().toIntOrNull()
                val count = countInput.text.toString().trim().toIntOrNull()
                if (sec == null || sec < 2 || sec > 3600 || count == null || count < 0 || count > 100000) {
                    toast("Số không hợp lệ. Khoảng cách từ 2 đến 3600 giây, số ảnh từ 0 trở lên.")
                } else {
                    intervalSec = sec
                    intervalTotal = count
                    prefs.edit().putInt("intervalSec", sec).putInt("intervalTotal", count).apply()
                    startInterval()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun startInterval() {
        if (sonyRemote == null || !remoteReady()) {
            toast("Chưa nối điều khiển máy ảnh")
            return
        }
        intervalOn = true
        intervalDone = 0
        intervalNextAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(intervalTick)
        handler.post(intervalTick)
        toast("Bắt đầu chụp hẹn giờ. Nên để máy ảnh ở MF để mỗi ảnh không phải lấy nét lại.")
        for (u in labelUpdaters) u()
    }

    private fun stopInterval(message: String?) {
        intervalOn = false
        handler.removeCallbacks(intervalTick)
        if (message != null) {
            toast(message)
            logEvent(message)
        }
        for (u in labelUpdaters) u()
        refreshCamInfo()
    }

    // ================================================================ lệnh điều khiển dùng chung (máy phụ)

    private fun runCameraAction(action: String): Boolean {
        val remote = sonyRemote ?: return false
        when (action) {
            "rec" -> remote.toggleRecord()
            "photo" -> remote.capturePhoto()
            "focus" -> remote.focus()
            "iso_up" -> remote.stepValue(SonyRemote.PROP_ISO, true)
            "iso_down" -> remote.stepValue(SonyRemote.PROP_ISO, false)
            "ss_up" -> remote.stepValue(SonyRemote.PROP_SHUTTER, true)
            "ss_down" -> remote.stepValue(SonyRemote.PROP_SHUTTER, false)
            "av_up" -> remote.stepValue(SonyRemote.PROP_F_NUMBER, true)
            "av_down" -> remote.stepValue(SonyRemote.PROP_F_NUMBER, false)
            "ev_up" -> remote.stepValue(SonyRemote.PROP_EXPOSURE_COMP, true)
            "ev_down" -> remote.stepValue(SonyRemote.PROP_EXPOSURE_COMP, false)
            "near3" -> remote.manualFocus(-MF_BIG)
            "near1" -> remote.manualFocus(-MF_SMALL)
            "far1" -> remote.manualFocus(MF_SMALL)
            "far3" -> remote.manualFocus(MF_BIG)
            else -> return false
        }
        return true
    }

    // ================================================================ máy phụ xem qua Wi-Fi

    /** Chạy trên luồng mạng. */
    override fun onWebCommand(action: String): String {
        if (webMode != 2) return "Máy chính đang để chế độ chỉ xem"
        if (!remoteReady()) return "Máy chính chưa nối điều khiển máy ảnh"
        runOnUiThread {
            if (webMode == 2) runCameraAction(action)
        }
        return "Đã gửi lệnh: $action"
    }

    /** Chạy trên luồng mạng. */
    override fun onWebInfo(): String = webInfo

    private fun applyWebMode() {
        if (webMode == 0) {
            stopWebServer()
            return
        }
        if (webServer != null) return
        val server = WebServer(WEB_PORT, webPin, this)
        webServer = server
        // Mở và đóng cổng mạng ở luồng phụ, Android không cho làm việc mạng trên luồng giao diện
        Thread {
            val ok = server.start()
            runOnUiThread {
                if (ok) {
                    logEvent("Máy phụ Wi-Fi: đã bật ở cổng $WEB_PORT")
                } else if (webServer === server) {
                    webServer = null
                    webMode = 0
                    toast("Không mở được cổng $WEB_PORT cho máy phụ")
                    for (u in labelUpdaters) u()
                }
            }
        }.start()
    }

    private fun stopWebServer() {
        val server = webServer ?: return
        webServer = null
        Thread { server.stop() }.start()
    }

    private fun localAddresses(): List<String> {
        val list = ArrayList<String>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return list
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null) list.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            // không đọc được danh sách mạng
        }
        return list
    }

    private fun showWebAddress() {
        if (webMode == 0 || webServer == null) {
            toast("Chạm nút này để bật máy phụ trước")
            return
        }
        val sb = StringBuilder()
        val addrs = localAddresses()
        if (addrs.isEmpty()) {
            sb.append("Điện thoại chưa vào mạng nào. Hãy nối Wi-Fi, hoặc bật phát Wi-Fi (hotspot) trên máy này rồi cho máy phụ nối vào.")
        } else {
            sb.append("Cho máy phụ vào cùng mạng Wi-Fi (hoặc nối vào hotspot của máy này), rồi mở trình duyệt gõ địa chỉ:\n\n")
            for (a in addrs) sb.append("http://").append(a).append(':').append(WEB_PORT).append("/?k=").append(webPin).append('\n')
            sb.append("\nNếu có nhiều dòng, thử lần lượt từng dòng.")
        }
        sb.append("\n\nChế độ hiện tại: ").append(WEB_NAMES[webMode])
            .append(".\nMáy phụ nhận hình gốc từ máy ảnh (không có LUT, peaking). Hiện chỉ phát được hình khi nối PC Remote; ở chế độ HDMI máy phụ chỉ điều khiển.")
            .append("\nAi có địa chỉ này và ở cùng mạng đều xem được, nên chỉ dùng trên mạng của bạn.")
        dialog().setTitle("Máy phụ qua Wi-Fi").setMessage(sb.toString()).setPositiveButton("Đóng", null).show()
    }

    // ================================================================ cỡ hình HDMI (capture card)

    private fun showUvcSizeMenu() {
        val helper = cameraHelper
        if (helper == null || !cameraOpened) {
            toast("Chỉ dùng khi đang nối capture card HDMI")
            return
        }
        val all = helper.supportedSizeList
        if (all == null || all.isEmpty()) {
            toast("Capture card không báo danh sách cỡ hình")
            return
        }
        val seen = HashSet<String>()
        val sizes = ArrayList<Size>()
        for (sz in all.sortedByDescending { it.width * it.height }) {
            val key = sz.type.toString() + ":" + sz.width + "x" + sz.height
            if (seen.add(key)) sizes.add(sz)
        }
        val names = Array(sizes.size) {
            val sz = sizes[it]
            val kind = if (sz.type == UVCCamera.UVC_VS_FRAME_MJPEG) "MJPEG" else "không nén (fps thấp)"
            sz.width.toString() + "×" + sz.height + " · " + kind
        }
        dialog().setTitle("Cỡ hình từ capture card").setItems(names) { _, which ->
            applyUvcSize(sizes[which])
        }.show()
    }

    private fun applyUvcSize(size: Size) {
        val helper = cameraHelper ?: return
        if (!cameraOpened) return
        try {
            val old = attachedSurface
            if (old != null) helper.removeSurface(old)
            attachedSurface = null
            helper.stopPreview()
            helper.setPreviewSize(size)
            helper.startPreview()
        } catch (e: Exception) {
            toast("Capture card không nhận cỡ hình này")
            logEvent("Đổi cỡ hình lỗi: " + (e.message ?: ""))
        }
        val now = helper.previewSize
        if (now != null) {
            videoW = now.width
            videoH = now.height
        }
        uvcSizePref = videoW.toString() + "x" + videoH
        prefs.edit().putString("uvcSize", uvcSizePref).apply()
        overlay.videoW = videoW
        overlay.videoH = videoH
        overlay.invalidate()
        infoText.text = videoW.toString() + "×" + videoH.toString() + " · HDMI/UVC"
        logEvent("Đổi cỡ hình: " + videoW + "x" + videoH)
        attachSurfaceIfReady()
    }

    // ================================================================ máy nhắc chữ

    /** Áp cài đặt nhắc chữ lên giao diện; [save] = có lưu lại hay không. */
    private fun applyPrompter(save: Boolean) {
        prompter.speedDp = TP_SPEEDS[tpSpeed]
        prompter.setTextSizeSp(TP_SIZES[tpSize])
        prompter.setMirror(tpMirror)
        prompter.setBackdrop(tpBackdrop)
        if (tpOn && tpText.isNotBlank()) {
            if (prompter.visibility != View.VISIBLE) {
                prompter.setScript(tpText)
                prompter.visibility = View.VISIBLE
            }
        } else {
            tpOn = false
            prompter.pause()
            prompter.visibility = View.GONE
        }
        if (save) {
            prefs.edit()
                .putString("tpText", tpText)
                .putInt("tpSpeed", tpSpeed)
                .putInt("tpSize", tpSize)
                .putBoolean("tpMirror", tpMirror)
                .putInt("tpBackdrop", tpBackdrop)
                .apply()
        }
    }

    private fun showPrompterEditor() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.minLines = 5
        input.maxLines = 10
        input.gravity = Gravity.TOP or Gravity.START
        input.hint = "Dán hoặc gõ kịch bản vào đây"
        input.setText(tpText)
        dialog().setTitle("Kịch bản nhắc chữ").setView(input)
            .setPositiveButton("Dùng") { _, _ ->
                tpText = input.text.toString().trim()
                if (tpText.isEmpty()) {
                    tpOn = false
                } else {
                    tpOn = true
                    prompter.setScript(tpText)
                }
                applyPrompter(true)
                for (u in labelUpdaters) u()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showPrompterMenu() {
        val items = arrayOf(
            "Nhập hoặc sửa kịch bản…",
            "Tốc độ: " + TP_SPEED_NAMES[tpSpeed],
            "Cỡ chữ: " + TP_SIZE_NAMES[tpSize],
            "Lật gương: " + onOff(tpMirror),
            "Nền: " + TP_BACKDROP_NAMES[tpBackdrop],
            "Về đầu bài"
        )
        dialog().setTitle("Nhắc chữ").setItems(items) { _, which ->
            when (which) {
                0 -> showPrompterEditor()
                1 -> dialog().setTitle("Tốc độ cuộn").setItems(TP_SPEED_NAMES) { _, i ->
                    tpSpeed = i
                    applyPrompter(true)
                }.show()
                2 -> dialog().setTitle("Cỡ chữ").setItems(TP_SIZE_NAMES) { _, i ->
                    tpSize = i
                    applyPrompter(true)
                }.show()
                3 -> {
                    tpMirror = !tpMirror
                    applyPrompter(true)
                }
                4 -> {
                    tpBackdrop = (tpBackdrop + 1) % TP_BACKDROP_NAMES.size
                    applyPrompter(true)
                }
                else -> prompter.rewind()
            }
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
        showTools = prefs.getBoolean("showTools", true)
        showCamera = prefs.getBoolean("showCamera", true)
        saveShots = prefs.getBoolean("saveShots", true)
        recordAudio = prefs.getBoolean("recordAudio", false)
        countdownIndex = prefs.getInt("countdown", 0).coerceIn(0, COUNTDOWN_NAMES.size - 1)
        loadShots()
        intervalSec = prefs.getInt("intervalSec", 5)
        intervalTotal = prefs.getInt("intervalTotal", 0)
        uvcSizePref = prefs.getString("uvcSize", "") ?: ""
        tpText = prefs.getString("tpText", "") ?: ""
        tpSpeed = prefs.getInt("tpSpeed", 2).coerceIn(0, TP_SPEEDS.size - 1)
        tpSize = prefs.getInt("tpSize", 1).coerceIn(0, TP_SIZES.size - 1)
        tpMirror = prefs.getBoolean("tpMirror", false)
        tpBackdrop = prefs.getInt("tpBackdrop", 1).coerceIn(0, 2)
        var pin = prefs.getString("webPin", "") ?: ""
        if (pin.length != 4) {
            pin = (1000..9999).random().toString()
            prefs.edit().putString("webPin", pin).apply()
        }
        webPin = pin
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
        private const val REQ_AUDIO = 4
        private const val BATTERY_LOW = 20
        private const val WEB_PORT = 8080
        private val COUNTDOWN_NAMES = arrayOf("Tắt", "3 giây", "5 giây", "10 giây")
        private val COUNTDOWN_SECONDS = intArrayOf(0, 3, 5, 10)
        private const val MF_SMALL = 1
        private const val MF_BIG = 5
        private val WEB_NAMES = arrayOf("Tắt", "Chỉ xem", "Điều khiển")
        private val TP_SPEED_NAMES = arrayOf("Rất chậm", "Chậm", "Vừa", "Nhanh", "Rất nhanh")
        private val TP_SPEEDS = floatArrayOf(15f, 25f, 40f, 60f, 90f)
        private val TP_SIZE_NAMES = arrayOf("Nhỏ", "Vừa", "Lớn", "Rất lớn")
        private val TP_SIZES = floatArrayOf(22f, 30f, 40f, 52f)
        private val TP_BACKDROP_NAMES = arrayOf("Trong suốt", "Mờ", "Đen")
        private const val EGL_RECORDABLE_ANDROID = 0x3142
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
