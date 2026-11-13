package com.dq.cammonitor

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import java.io.File

/**
 * Ghi hình preview thành file MP4 (H.264, kèm tiếng AAC nếu được yêu cầu). Phải tạo và
 * dùng trên luồng GL: renderer vẽ thêm một lần mỗi khung hình vào bề mặt đầu vào của bộ
 * mã hoá. Tiếng được thu và mã hoá ở một luồng riêng.
 *
 * [withAudio] = có thu tiếng; [audioDevice] = thiết bị thu ưu tiên (tiếng HDMI từ capture
 * card), null thì dùng micro của điện thoại. Không thu được tiếng thì vẫn ghi hình bình thường.
 */
class PreviewRecorder(
    val width: Int,
    val height: Int,
    val file: File,
    orientationHint: Int,
    withAudio: Boolean,
    audioDevice: AudioDeviceInfo?
) {

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
    @Volatile private var muxerStarted = false
    private var released = false

    // ---- tiếng
    private val muxerLock = Any()
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioThread: Thread? = null
    private var audioTrack = -1
    @Volatile private var audioRunning = false
    private val audioInfo = MediaCodec.BufferInfo()
    private var audioRate = 48000
    private var audioChannels = 1
    private var audioFrames = 0L
    private var audioBasePts = 0L
    private val pcm = ByteArray(4096)

    /** Mô tả nguồn tiếng đang thu, hoặc lý do không thu được; null nếu không yêu cầu tiếng. */
    var audioNote: String? = null
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 8).coerceIn(6_000_000, 16_000_000))
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

        if (withAudio) {
            try {
                setupAudio(audioDevice)
            } catch (e: Exception) {
                releaseAudio()
                audioTrack = -1
                audioNote = "Không thu được tiếng (" + (e.message ?: e.javaClass.simpleName) + "), chỉ ghi hình"
            }
        }
    }

    /**
     * Mở micro và bộ mã hoá AAC, rồi chạy thử cho tới khi biết định dạng tiếng để thêm
     * rãnh tiếng vào file TRƯỚC khi bắt đầu ghi hình (file MP4 phải biết đủ rãnh từ đầu).
     */
    private fun setupAudio(device: AudioDeviceInfo?) {
        val channels = if (device != null) 2 else 1
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        var record: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(48000, 44100)) {
            val min = AudioRecord.getMinBufferSize(candidate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, candidate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16384)
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                record = r
                rate = candidate
                break
            }
            r.release()
        }
        val rec = record ?: throw IllegalStateException("không mở được micro")
        audioRecord = rec
        audioRate = rate
        audioChannels = channels
        if (device != null) rec.setPreferredDevice(device)
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("micro đang bị app khác dùng")
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 160_000 else 96_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder = enc
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        audioBasePts = System.nanoTime() / 1000L
        val deadline = System.nanoTime() + 1_500_000_000L
        while (audioTrack < 0 && System.nanoTime() < deadline) {
            pumpAudio(rec, enc)
        }
        if (audioTrack < 0) throw IllegalStateException("bộ mã hoá tiếng không phản hồi")

        audioNote = if (device != null) {
            "Có tiếng từ " + (device.productName?.toString() ?: "thiết bị USB")
        } else {
            "Có tiếng từ micro điện thoại"
        }
        audioRunning = true
        val t = Thread({
            try {
                while (audioRunning) pumpAudio(rec, enc)
                // Báo hết dữ liệu rồi lấy nốt phần còn lại
                val index = enc.dequeueInputBuffer(100000L)
                if (index >= 0) {
                    enc.queueInputBuffer(index, 0, 0, audioPts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drainAudio(enc, true)
                }
            } catch (e: Exception) {
                // tiếng hỏng giữa chừng thì phần hình vẫn được giữ
            }
        }, "PreviewAudio")
        audioThread = t
        t.start()
    }

    private fun audioPts(): Long {
        return audioBasePts + audioFrames * 1_000_000L / audioRate
    }

    /** Đọc một khối tiếng từ micro, đưa vào bộ mã hoá và ghi phần đã mã hoá ra file. */
    private fun pumpAudio(rec: AudioRecord, enc: MediaCodec) {
        val n = rec.read(pcm, 0, pcm.size)
        if (n <= 0) {
            Thread.sleep(5)
            return
        }
        val index = enc.dequeueInputBuffer(10000L)
        if (index >= 0) {
            val buffer = enc.getInputBuffer(index)
            if (buffer != null) {
                buffer.clear()
                buffer.put(pcm, 0, n)
            }
            enc.queueInputBuffer(index, 0, n, audioPts(), 0)
        }
        audioFrames += n / (2 * audioChannels)
        drainAudio(enc, false)
    }

    private fun drainAudio(enc: MediaCodec, endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = enc.dequeueOutputBuffer(audioInfo, if (endOfStream) 10000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                waits++
                if (waits > 100) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (audioTrack < 0 && !muxerStarted) audioTrack = muxer.addTrack(enc.outputFormat)
                }
            } else if (index >= 0) {
                val buffer = enc.getOutputBuffer(index)
                if (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    audioInfo.size = 0
                }
                if (buffer != null && audioInfo.size > 0 && audioTrack >= 0) {
                    synchronized(muxerLock) {
                        // Tiếng đến trước khi file bắt đầu (chưa có khung hình đầu) thì bỏ
                        if (muxerStarted && !released) {
                            buffer.position(audioInfo.offset)
                            buffer.limit(audioInfo.offset + audioInfo.size)
                            muxer.writeSampleData(audioTrack, buffer, audioInfo)
                        }
                    }
                }
                enc.releaseOutputBuffer(index, false)
                if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun stopAudioThread() {
        audioRunning = false
        val t = audioThread ?: return
        audioThread = null
        try {
            t.join(3000)
        } catch (e: InterruptedException) {
            // bỏ qua
        }
    }

    private fun releaseAudio() {
        audioRunning = false
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (e: Exception) {
                // bỏ qua
            }
            rec.release()
        }
        val enc = audioEncoder
        audioEncoder = null
        if (enc != null) {
            try {
                enc.stop()
            } catch (e: Exception) {
                // bỏ qua
            }
            enc.release()
        }
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
        stopAudioThread()
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
                synchronized(muxerLock) {
                    if (!muxerStarted) {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
            } else if (index >= 0) {
                val buffer = encoder.getOutputBuffer(index)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (buffer != null && info.size > 0 && muxerStarted) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    synchronized(muxerLock) { muxer.writeSampleData(track, buffer, info) }
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun release() {
        if (released) return
        stopAudioThread()
        synchronized(muxerLock) { released = true }
        releaseAudio()
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
