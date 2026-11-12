package com.dq.cammonitor

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Vẽ hình từ máy ảnh lên màn hình bằng OpenGL ES 2.0.
 * Toàn bộ peaking / zebra / false color chạy trong fragment shader (GPU).
 */
class MonitorRenderer(
    private val state: MonitorState,
    private val listener: Listener
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    interface Listener {
        /** Gọi trên luồng GL khi SurfaceTexture mới đã sẵn sàng nhận hình. */
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)

        /** Gọi trên luồng GL, bins gồm 4 đoạn HIST_BINS: sáng, đỏ, lục, lam. */
        fun onHistogram(bins: IntArray)

        fun onFrameAvailable()
    }

    private var program = 0
    private var aPos = 0
    private var uScale = 0
    private var uUvScale = 0
    private var uUvOffset = 0
    private var uTexMatrix = 0
    private var uTexel = 0
    private var uMode = 0
    private var uPeak = 0
    private var uPeakColor = 0
    private var uPeakThresh = 0
    private var uZebra = 0
    private var uZebraLevel = 0
    private var uTime = 0
    private var uTex = 0
    private var uLut = 0
    private var uLutSize = 0
    private var uLutOn = 0

    private var lutTex = 0
    private var lutSize = 0
    private var lutData: ByteBuffer? = null
    private var lutDirty = false

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    private var fbo = 0
    private var fboTex = 0
    private val histPixels: ByteBuffer =
        ByteBuffer.allocateDirect(HIST_W * HIST_H * 4).order(ByteOrder.nativeOrder())

    private var viewW = 1
    private var viewH = 1
    @Volatile private var videoW = 1280
    @Volatile private var videoH = 720
    private var frameCount = 0
    private val startTime = SystemClock.elapsedRealtime()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    /** Gọi trên luồng GL (qua queueEvent) trước khi gắn surface vào camera. */
    fun applyVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    /**
     * Gọi trên luồng GL. Tạo SurfaceTexture mới khi đổi nguồn hình (UVC <-> PC Remote),
     * vì một surface chỉ nhận được một kiểu nguồn ghi.
     */
    fun recreateSurface() {
        if (oesTex == 0) return
        surfaceTexture?.release()
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(videoW, videoH)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        listener.onSurfaceTextureReady(st)
    }

    /** Gọi trên luồng GL (qua queueEvent). Truyền null để gỡ LUT. */
    fun setLut(size: Int, rgba: ByteBuffer?) {
        lutSize = if (rgba != null) size else 0
        lutData = rgba
        lutDirty = true
    }

    private fun uploadLut() {
        lutDirty = false
        val data = lutData ?: return
        if (lutTex == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            lutTex = t[0]
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        data.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lutSize * lutSize, lutSize, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Ngữ cảnh GL mới: texture LUT cũ không còn, phải nạp lại
        lutTex = 0
        lutDirty = lutData != null
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uUvScale = GLES20.glGetUniformLocation(program, "uUvScale")
        uUvOffset = GLES20.glGetUniformLocation(program, "uUvOffset")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uPeak = GLES20.glGetUniformLocation(program, "uPeak")
        uPeakColor = GLES20.glGetUniformLocation(program, "uPeakColor")
        uPeakThresh = GLES20.glGetUniformLocation(program, "uPeakThresh")
        uZebra = GLES20.glGetUniformLocation(program, "uZebra")
        uZebraLevel = GLES20.glGetUniformLocation(program, "uZebraLevel")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutSize = GLES20.glGetUniformLocation(program, "uLutSize")
        uLutOn = GLES20.glGetUniformLocation(program, "uLutOn")

        // Texture nhận hình từ camera
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture?.release()
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(videoW, videoH)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st

        // Framebuffer nhỏ để lấy mẫu tính histogram
        GLES20.glGenTextures(1, t, 0)
        fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, HIST_W, HIST_H, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        listener.onSurfaceTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        listener.onFrameAvailable()
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage", e)
            return
        }
        st.getTransformMatrix(texMatrix)
        frameCount++
        if (lutDirty) uploadLut()

        if (state.histogram != 0 && frameCount % HIST_EVERY == 0) {
            computeHistogram()
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val aspect = videoW.toFloat() * state.squeezeFactor / videoH.toFloat()
        val fit = MonitorState.fit(viewW, viewH, aspect)
        val zoom = state.zoomFactor
        val flip = state.flip
        val sx = (if (flip and 1 != 0) -1f else 1f) / zoom
        val sy = (if (flip and 2 != 0) -1f else 1f) / zoom
        draw(fit[0], fit[1], sx, sy, state.panX, state.panY, true)
    }

    private fun draw(
        scaleX: Float, scaleY: Float,
        uvScaleX: Float, uvScaleY: Float,
        offX: Float, offY: Float,
        effects: Boolean
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uTex, 0)

        val lutActive = state.lutOn && lutTex != 0 && lutSize > 1
        if (lutActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        GLES20.glUniform1i(uLut, 1)
        GLES20.glUniform1f(uLutSize, if (lutActive) lutSize.toFloat() else 2f)
        GLES20.glUniform1f(uLutOn, if (lutActive) 1f else 0f)

        GLES20.glUniform2f(uScale, scaleX, scaleY)
        GLES20.glUniform2f(uUvScale, uvScaleX, uvScaleY)
        GLES20.glUniform2f(uUvOffset, offX, offY)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uTexel, 1f / videoW, 1f / videoH)

        val peaking = if (effects) state.peaking else 0
        val zebra = if (effects) state.zebra else 0
        GLES20.glUniform1f(uMode, if (effects) state.mode.toFloat() else 0f)
        GLES20.glUniform1f(uPeak, if (peaking > 0) 1f else 0f)
        when (peaking) {
            2 -> GLES20.glUniform3f(uPeakColor, 0.1f, 1f, 0.2f)
            3 -> GLES20.glUniform3f(uPeakColor, 1f, 0.95f, 0f)
            else -> GLES20.glUniform3f(uPeakColor, 1f, 0.1f, 0.1f)
        }
        GLES20.glUniform1f(uPeakThresh, PEAK_THRESH[state.peakSens.coerceIn(0, 2)])
        GLES20.glUniform1f(uZebra, if (zebra > 0) 1f else 0f)
        GLES20.glUniform1f(uZebraLevel, ZEBRA_LEVELS[zebra.coerceIn(0, 3)])
        val seconds = ((SystemClock.elapsedRealtime() - startTime) % 100000L) / 1000f
        GLES20.glUniform1f(uTime, seconds)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /** Vẽ ảnh gốc (không hiệu ứng) vào khung nhỏ rồi đọc về để đếm độ sáng. */
    private fun computeHistogram() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, HIST_W, HIST_H)
        draw(1f, 1f, 1f, 1f, 0f, 0f, false)
        histPixels.position(0)
        GLES20.glReadPixels(
            0, 0, HIST_W, HIST_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, histPixels
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val bins = IntArray(HIST_BINS * 4)
        val total = HIST_W * HIST_H
        var i = 0
        for (p in 0 until total) {
            val r = histPixels.get(i).toInt() and 0xFF
            val g = histPixels.get(i + 1).toInt() and 0xFF
            val b = histPixels.get(i + 2).toInt() and 0xFF
            i += 4
            // Rec.709 luma, hệ số nguyên (tổng 256)
            val y = (54 * r + 183 * g + 19 * b) shr 8
            bins[(y * HIST_BINS) shr 8]++
            bins[HIST_BINS + ((r * HIST_BINS) shr 8)]++
            bins[HIST_BINS * 2 + ((g * HIST_BINS) shr 8)]++
            bins[HIST_BINS * 3 + ((b * HIST_BINS) shr 8)]++
        }
        listener.onHistogram(bins)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link lỗi: " + GLES20.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader lỗi: " + GLES20.glGetShaderInfoLog(s))
        }
        return s
    }

    companion object {
        private const val TAG = "MonitorRenderer"

        const val HIST_BINS = 64
        private const val HIST_W = 128
        private const val HIST_H = 72
        private const val HIST_EVERY = 5

        /** Ngưỡng cạnh cho peaking: thấp / vừa / cao (số nhỏ = nhạy hơn) */
        private val PEAK_THRESH = floatArrayOf(0.22f, 0.13f, 0.07f)

        /** Mức zebra: tắt / 70% / 95% / 100% */
        private val ZEBRA_LEVELS = floatArrayOf(2f, 0.70f, 0.95f, 0.995f)

        private const val VERTEX_SHADER = """
attribute vec2 aPos;
uniform vec2 uScale;
uniform vec2 uUvScale;
uniform vec2 uUvOffset;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos * uScale, 0.0, 1.0);
    vUv = (aPos * 0.5) * uUvScale + 0.5 + uUvOffset;
}
"""

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uTexel;
uniform float uMode;
uniform float uPeak;
uniform vec3 uPeakColor;
uniform float uPeakThresh;
uniform float uZebra;
uniform float uZebraLevel;
uniform float uTime;
uniform sampler2D uLut;
uniform float uLutSize;
uniform float uLutOn;
varying vec2 vUv;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 samp(vec2 uv) {
    return texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

// LUT 3D lưu dạng các lát xanh lam xếp ngang; nội suy giữa hai lát kề nhau
vec3 applyLut(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float n = uLutSize;
    float b = c.b * (n - 1.0);
    float b0 = floor(b);
    float b1 = min(b0 + 1.0, n - 1.0);
    float x = (c.r * (n - 1.0) + 0.5) / (n * n);
    float yy = (c.g * (n - 1.0) + 0.5) / n;
    vec3 s0 = texture2D(uLut, vec2(x + b0 / n, yy)).rgb;
    vec3 s1 = texture2D(uLut, vec2(x + b1 / n, yy)).rgb;
    return mix(s0, s1, b - b0);
}

vec3 falseColor(float y) {
    if (y < 0.02) return vec3(0.45, 0.0, 0.60);   // mất chi tiết vùng tối
    if (y < 0.10) return vec3(0.0, 0.2, 0.9);     // rất tối
    if (y < 0.38) return vec3(y * 0.9);
    if (y < 0.42) return vec3(0.1, 0.75, 0.2);    // xám trung tính
    if (y < 0.52) return vec3(y * 0.9);
    if (y < 0.56) return vec3(1.0, 0.55, 0.65);   // da sáng
    if (y < 0.90) return vec3(y * 0.9);
    if (y < 0.97) return vec3(1.0, 0.9, 0.0);     // sắp cháy
    return vec3(1.0, 0.0, 0.0);                   // cháy sáng
}

void main() {
    if (vUv.x < 0.0 || vUv.x > 1.0 || vUv.y < 0.0 || vUv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 c = samp(vUv);
    if (uLutOn > 0.5) {
        c = applyLut(c);
    }
    float y = luma(c);
    vec3 o = c;

    if (uMode > 1.5) {
        o = vec3(y);
    } else if (uMode > 0.5) {
        o = falseColor(y);
    }

    if (uZebra > 0.5) {
        bool hit;
        if (uZebraLevel < 0.9) {
            hit = abs(y - uZebraLevel) < 0.05;
        } else {
            hit = y >= uZebraLevel;
        }
        if (hit) {
            float s = mod(gl_FragCoord.x + gl_FragCoord.y + uTime * 60.0, 14.0);
            if (s < 6.0) {
                o = vec3(0.05);
            }
        }
    }

    if (uPeak > 0.5) {
        float l = luma(samp(vUv - vec2(uTexel.x, 0.0)));
        float r = luma(samp(vUv + vec2(uTexel.x, 0.0)));
        float d = luma(samp(vUv - vec2(0.0, uTexel.y)));
        float u = luma(samp(vUv + vec2(0.0, uTexel.y)));
        float edge = abs(4.0 * y - l - r - d - u);
        if (edge > uPeakThresh) {
            o = uPeakColor;
        }
    }

    gl_FragColor = vec4(o, 1.0);
}
"""
    }
}
