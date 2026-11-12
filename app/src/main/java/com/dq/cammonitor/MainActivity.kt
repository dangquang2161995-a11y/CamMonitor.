package com.dq.cammonitor

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
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
import android.widget.Toast
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.io.File
import kotlin.math.abs

class MainActivity : Activity(), MonitorRenderer.Listener, SonyRemote.Listener {

    private val state = MonitorState()
    private lateinit var prefs: SharedPreferences

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MonitorRenderer
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var diagText: TextView
    private lateinit var controlBar: HorizontalScrollView

    private var cameraHelper: ICameraHelper? = null
    private var surface: Surface? = null
    private var attachedSurface: Surface? = null
    private var cameraOpened = false
    private var videoW = 1280
    private var videoH = 720

    // Sony PC Remote (PTP): xem hình trong lúc máy ảnh vẫn quay/chụp
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

    /** Surface đang được dùng bởi: 0 = chưa ai, 1 = UVC, 2 = PC Remote */
    private var surfaceUse = 0
    private var surfaceRecreating = false

    private var lutLoaded = false
    private var lutName = ""
    private var lutButton: Button? = null

    // Bảng chẩn đoán: hiện khi chưa có hình, để biết điện thoại có thấy máy ảnh không
    private val handler = Handler(Looper.getMainLooper())
    private val events = ArrayList<String>()
    private val diagTick = object : Runnable {
        override fun run() {
            checkSonyRemote()
            refreshDiag()
            handler.postDelayed(this, 1000)
        }
    }

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
        restoreSavedLut()

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

        diagText = TextView(this)
        diagText.setTextColor(Color.argb(255, 140, 255, 160))
        diagText.textSize = 10f
        diagText.setPadding(dp(8), dp(6), dp(8), dp(6))
        diagText.setBackgroundColor(Color.argb(150, 0, 0, 0))
        val diagLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        diagLp.topMargin = dp(8)
        diagLp.leftMargin = dp(8)
        root.addView(diagText, diagLp)

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
        addButton(row, { "Histogram: " + HIST_NAMES[state.histogram] }) {
            state.histogram = (state.histogram + 1) % HIST_NAMES.size
            if (state.histogram == 0) overlay.setHistogram(null)
        }
        // Chạm: bật/tắt LUT (chưa có thì mở chọn file). Giữ lâu: chọn file .cube khác.
        lutButton = addButton(row, { lutLabel() }, { pickLut() }) {
            if (lutLoaded) {
                state.lutOn = !state.lutOn
            } else {
                pickLut()
            }
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
        b.setOnClickListener {
            action()
            b.text = label()
            saveState()
            overlay.invalidate()
            glView.requestRender()
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

    // ---------------------------------------------------------------- LUT 3D (.cube)

    private fun lutLabel(): String {
        if (!lutLoaded) return "LUT: Chọn file"
        val short = if (lutName.length > 14) lutName.substring(0, 14) + "…" else lutName
        return "LUT " + (if (state.lutOn) "Bật" else "Tắt") + ": " + short
    }

    private fun pickLut() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        try {
            startActivityForResult(intent, REQ_LUT)
        } catch (e: Exception) {
            toast("Không mở được trình chọn file")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LUT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        var name = "LUT"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: "LUT"
            }
        } catch (e: Exception) {
            // không lấy được tên thì dùng tên mặc định
        }
        val displayName = name.removeSuffix(".cube").removeSuffix(".CUBE")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Không đọc được file")
                if (bytes.size > 40 * 1024 * 1024) throw IllegalArgumentException("File quá lớn")
                val lut = LutLoader.parse(String(bytes, Charsets.UTF_8))
                File(filesDir, LUT_FILE).writeBytes(bytes)
                runOnUiThread { applyLut(lut, displayName, true) }
            } catch (e: Exception) {
                runOnUiThread { toast("Không nạp được LUT: " + (e.message ?: "lỗi định dạng")) }
            }
        }.start()
    }

    /** Nạp lại LUT đã dùng lần trước (lưu trong bộ nhớ riêng của app). */
    private fun restoreSavedLut() {
        val file = File(filesDir, LUT_FILE)
        if (!file.exists()) return
        val savedName = prefs.getString("lutName", "LUT") ?: "LUT"
        val wasOn = prefs.getBoolean("lutOn", false)
        Thread {
            try {
                val lut = LutLoader.parse(String(file.readBytes(), Charsets.UTF_8))
                runOnUiThread { applyLut(lut, savedName, wasOn) }
            } catch (e: Exception) {
                file.delete()
            }
        }.start()
    }

    private fun applyLut(lut: LutLoader.Lut, name: String, turnOn: Boolean) {
        lutLoaded = true
        lutName = name
        state.lutOn = turnOn
        glView.queueEvent { renderer.setLut(lut.size, lut.rgba) }
        glView.requestRender()
        lutButton?.text = lutLabel()
        saveState()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
        handler.removeCallbacks(diagTick)
        handler.post(diagTick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(diagTick)
        stopSonyRemote()
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
                .append(" | lớp ").append(d.deviceClass)
                .append(" | giao diện ").append(classes.joinToString(","))
                .append(if (isUvc) " | UVC: CÓ" else " | UVC: KHÔNG")
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
            infoText.text = ""
            statusText.text = TEXT_WAITING
            statusText.visibility = View.VISIBLE
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

    /** Gắn surface của OpenGL vào camera khi cả hai đã sẵn sàng. */
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

    // ---------------------------------------------------------------- Sony PC Remote

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
        infoText.text = w.toString() + "×" + h.toString() + " · PC Remote"
        logEvent("PC Remote: có hình " + w + "x" + h)
    }

    override fun onRemoteStopped(error: String?) {
        runOnUiThread {
            sonyRemote = null
            ptpSurface = null
            ptpStreaming = false
            ptpRetryAt = SystemClock.elapsedRealtime() + 3000
            if (error != null) logEvent("PC Remote dừng: " + error)
            if (!cameraOpened) {
                infoText.text = ""
                statusText.text = TEXT_WAITING
                statusText.visibility = View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- callback từ renderer (luồng GL)

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        runOnUiThread {
            surface = Surface(surfaceTexture)
            surfaceUse = 0
            surfaceRecreating = false
            attachSurfaceIfReady()
        }
    }

    override fun onHistogram(bins: IntArray) {
        runOnUiThread {
            if (state.histogram != 0) overlay.setHistogram(bins)
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
        state.histogram = prefs.getInt("hist", 0).coerceIn(0, HIST_NAMES.size - 1)
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
            .putInt("hist", state.histogram)
            .putBoolean("lutOn", state.lutOn)
            .putString("lutName", lutName)
            .putInt("frame", state.frame)
            .putInt("grid", state.grid)
            .putInt("flip", state.flip)
            .putInt("squeeze", state.squeeze)
            .apply()
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_LUT = 2
        private const val ACTION_USB_PERMISSION = "com.dq.cammonitor.USB_PERMISSION"
        private const val LUT_FILE = "lut.cube"
        private const val TEXT_WAITING =
            "Chưa thấy máy ảnh.\nSony: đặt MENU > Network > PC Remote Function > PC Remote = On (kết nối USB), rồi cắm dây USB-C.\nCapture card HDMI: cắm vào là tự nhận. Chạm vào đây để thử kết nối lại."

        private val MODE_NAMES = arrayOf("Thường", "False color", "Đen trắng")
        private val PEAK_NAMES = arrayOf("Tắt", "Đỏ", "Xanh", "Vàng")
        private val SENS_NAMES = arrayOf("Thấp", "Vừa", "Cao")
        private val HIST_NAMES = arrayOf("Tắt", "Sáng", "RGB")
        private val ZEBRA_NAMES = arrayOf("Tắt", "70%", "95%", "100%")
        private val GRID_NAMES = arrayOf("Tắt", "1/3", "Tâm", "Tất cả")
        private val ZOOM_NAMES = arrayOf("1×", "2×", "4×")
        private val FLIP_NAMES = arrayOf("Không", "Ngang", "Dọc", "Cả hai")
        private val SQUEEZE_NAMES = arrayOf("1×", "1.33×", "1.5×", "2×")
    }
}
