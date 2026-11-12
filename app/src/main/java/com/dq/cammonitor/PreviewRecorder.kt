package com.dq.cammonitor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import java.io.File

/**
 * Ghi hình preview thành file MP4 (H.264). Phải tạo và dùng trên luồng GL:
 * renderer vẽ thêm một lần mỗi khung hình vào bề mặt đầu vào của bộ mã hoá.
 */
class PreviewRecorder(val width: Int, val height: Int, val file: File, orientationHint: Int) {

    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private var savedDraw: EGLSurface? = null
    private var savedRead: EGLSurface? = null

    private val encoder: MediaCodec
    private val inputSurface: Surface
    private val muxer: MediaMuxer
    private val eglSurface: EGLSurface
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var released = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHint)

        // Dùng lại đúng cấu hình EGL của ngữ cảnh đang vẽ
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0,
            configs, 0, 1, count, 0
        )
        val config = configs[0]
        var surface: EGLSurface? = null
        if (count[0] > 0 && config != null) {
            surface = EGL14.eglCreateWindowSurface(
                display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
        }
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            encoder.stop()
            encoder.release()
            inputSurface.release()
            muxer.release()
            throw IllegalStateException("Máy không tạo được bề mặt ghi hình")
        }
        eglSurface = surface
    }

    /** Chuyển đích vẽ sang bộ mã hoá. */
    fun begin() {
        // Lấy lại đích vẽ hiện tại mỗi khung hình, vì màn hình có thể vừa được tạo lại
        savedDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        savedRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    /** Đẩy khung hình vừa vẽ cho bộ mã hoá rồi trả đích vẽ về màn hình. */
    fun end(timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(display, eglSurface)
        EGL14.eglMakeCurrent(display, savedDraw, savedRead, context)
        drain(false)
    }

    /** Kết thúc file. Trả về true nếu file có dữ liệu. */
    fun finish(): Boolean {
        if (released) return false
        var ok = false
        try {
            encoder.signalEndOfInputStream()
            drain(true)
            ok = muxerStarted
        } catch (e: Exception) {
            ok = false
        }
        release()
        return ok
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                waits++
                if (waits > 300) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (index >= 0) {
                val buffer = encoder.getOutputBuffer(index)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (buffer != null && info.size > 0 && muxerStarted) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buffer, info)
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun release() {
        if (released) return
        released = true
        try {
            encoder.stop()
        } catch (e: Exception) {
            // bỏ qua
        }
        encoder.release()
        try {
            if (muxerStarted) muxer.stop()
        } catch (e: Exception) {
            // file chưa có khung hình nào
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            // bỏ qua
        }
        EGL14.eglDestroySurface(display, eglSurface)
        inputSurface.release()
    }
}
