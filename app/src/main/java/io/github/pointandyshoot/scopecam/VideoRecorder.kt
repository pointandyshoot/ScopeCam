package io.github.pointandyshoot.scopecam

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Encoder output is a camera Surface, never a window capture or GL/UI composite. */
class VideoRecorder(private val context: Context, private val onError: (String) -> Unit = {}) {
    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    var started = false
        private set
    val surface: Surface get() = requireNotNull(recorder).surface
    val prepared: Boolean get() = recorder != null || uri != null

    @Suppress("DEPRECATION")
    fun prepare(mode: VideoMode, orientation: Int, audio: Boolean) {
        check(recorder == null)
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ScopeCam_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScopeCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create gallery video")
            descriptor = context.contentResolver.openFileDescriptor(requireNotNull(uri), "w")
                ?: error("Could not open video output")
            val encoder = MediaRecorder()
            recorder = encoder
            encoder.apply {
                setOnErrorListener { source, what, extra -> if (recorder === source) onError("Recorder error $what/$extra") }
                if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(requireNotNull(descriptor).fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(mode.width, mode.height)
                setVideoFrameRate(mode.fps)
                setVideoEncodingBitRate(if (mode.width >= 3840) 45_000_000 else if (mode.fps == 60) 24_000_000 else 16_000_000)
                if (audio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(48_000)
                }
                setOrientationHint(orientation)
                prepare()
            }
        } catch (e: Exception) { finish(false); throw e }
    }

    fun start() { requireNotNull(recorder).start(); started = true }

    /** A stop failure deletes the pending row instead of exposing a corrupt/empty MP4. */
    fun finish(save: Boolean): Uri? {
        val output = uri
        var valid = save && started
        try { if (started) recorder?.stop() } catch (_: RuntimeException) { valid = false }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { descriptor?.close() }
        recorder = null; descriptor = null; uri = null; started = false
        if (output != null) {
            if (valid) {
                try {
                    val count = context.contentResolver.update(output, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    if (count == 0) valid = false
                } catch (_: Exception) { valid = false }
            }
            if (!valid) runCatching { context.contentResolver.delete(output, null, null) }
        }
        return output.takeIf { valid }
    }

    /** Remove only this app's abandoned pending rows after a killed recording process. */
    fun clearAbandonedPending() {
        runCatching {
            context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.IS_PENDING}=1 AND ${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME}=?",
                arrayOf("Movies/ScopeCam/", context.packageName))
        }
    }
}
