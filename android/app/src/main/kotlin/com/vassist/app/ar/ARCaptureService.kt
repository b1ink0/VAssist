package com.vassist.app.ar

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/** Screen recording service. Notification remains available while the web UI is hidden. */
class ARCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.vassist.app.ar.START_CAPTURE"
        const val ACTION_STOP = "com.vassist.app.ar.STOP_CAPTURE"
        const val ACTION_FINISHED = "com.vassist.app.ar.CAPTURE_FINISHED"
        const val ACTION_STARTED = "com.vassist.app.ar.CAPTURE_STARTED"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_MEDIA_AUDIO = "mediaAudio"
        const val EXTRA_MICROPHONE = "microphone"
        private const val CHANNEL = "ar_capture"
        private const val NOTIFICATION = 7815
    }
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var file: File? = null
    private var stopping = false
    private var recordingStarted = false
    private var messageId = ""
    private var audioCapture: ARCaptureAudio? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!recordingStarted) finishRecording("No active AR recording is available to stop.")
            else finishRecording(null)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val requestedMessageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        if (stopping) {
            sendStatus(ACTION_STARTED, false, "The previous AR recording is still being finalized.", requestedMessageId)
            return START_NOT_STICKY
        }
        if (recordingStarted || projection != null) {
            sendStatus(ACTION_STARTED, recordingStarted, if (recordingStarted) null else "An AR recording is still initializing.", requestedMessageId)
            return START_NOT_STICKY
        }
        messageId = requestedMessageId
        stopping = false
        try {
            createChannel()
            val mediaAudio = intent.getBooleanExtra(EXTRA_MEDIA_AUDIO, true)
            val microphone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
            require(!mediaAudio || Build.VERSION.SDK_INT >= 29) { "Media audio capture requires Android 10 or later." }
            if (Build.VERSION.SDK_INT >= 29) {
                var serviceTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (microphone && Build.VERSION.SDK_INT >= 30) {
                    serviceTypes = serviceTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION, notification(), serviceTypes)
            } else startForeground(NOTIFICATION, notification())
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            require(data != null && code == Activity.RESULT_OK) { "Screen recording permission data is missing." }
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    finishRecording(if (recordingStarted) null else "Screen capture permission ended before recording started.")
                }
            }, null)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(2) and -2
            val height = metrics.heightPixels.coerceAtLeast(2) and -2
            file = File(cacheDir, "ar_${System.currentTimeMillis()}.mp4")
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = mr
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(width, height)
            mr.setVideoFrameRate(30)
            mr.setVideoEncodingBitRate(8_000_000)
            mr.setOutputFile(file!!.absolutePath)
            mr.prepare()
            audioCapture = ARCaptureAudio.start(this, projection!!, mediaAudio, microphone)
            display = projection!!.createVirtualDisplay("VAssist AR capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.surface, null, null)
            mr.start()
            audioCapture?.markVideoStarted()
            recordingStarted = true
            sendStatus(ACTION_STARTED, true, null)
            return START_NOT_STICKY
        } catch (e: Exception) {
            val message = e.message ?: "Could not start screen recording."
            sendStatus(ACTION_STARTED, false, message)
            finishRecording(message)
            return START_NOT_STICKY
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AR recording", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): android.app.Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ARCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording AR view").setContentText("Tap Stop to finish and save the video.")
            .setOngoing(true).setWhen(System.currentTimeMillis()).setUsesChronometer(true)
            .addAction(0, "Stop", stop).build()
    }

    private fun finishRecording(error: String?) {
        if (stopping) return
        stopping = true
        val wasRecording = recordingStarted
        recordingStarted = false
        val activeRecorder = recorder.also { recorder = null }
        val activeDisplay = display.also { display = null }
        val activeProjection = projection.also { projection = null }
        val activeFile = file.also { file = null }
        val activeAudio = audioCapture.also { audioCapture = null }
        val captureMessageId = messageId.also { messageId = "" }
        Thread({
            var resultError = error
            try {
                if (wasRecording) activeRecorder?.stop()
                else if (resultError == null) resultError = "Recording did not start; no video was created."
            } catch (e: Exception) {
                if (resultError == null) resultError = "Recording ended before a valid video was produced."
            } finally {
                runCatching { activeRecorder?.reset(); activeRecorder?.release() }
                runCatching { activeDisplay?.release() }
            }

            if (resultError == null && wasRecording && activeAudio != null) {
                try {
                    if (activeFile == null) throw IllegalStateException("The recording file is missing.")
                    activeAudio.muxInto(activeFile)
                } catch (e: Exception) {
                    resultError = e.message ?: "Could not encode and mux the requested audio."
                }
            } else runCatching { activeAudio?.close() }
            runCatching { activeProjection?.stop() }

            var uri: android.net.Uri? = null
            if (resultError == null) try {
                if (activeFile == null || !activeFile.isFile || activeFile.length() == 0L) {
                    throw IllegalStateException("The recording file is empty; no video was saved.")
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "VAssist_AR_${System.currentTimeMillis()}.mp4")
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/VAssist")
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create MediaStore video.")
                contentResolver.openOutputStream(uri!!)?.use { output -> activeFile.inputStream().use { it.copyTo(output) } }
                    ?: throw IllegalStateException("Could not write MediaStore video.")
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(uri!!, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (e: Exception) {
                uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                uri = null
                resultError = e.message ?: "Could not save the video."
            }
            runCatching { activeFile?.delete() }
            val finalUri = uri
            val finalError = resultError
            Handler(Looper.getMainLooper()).post {
                if (wasRecording || finalError == null) {
                    sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra("uri", finalUri?.toString())
                        .putExtra("error", finalError).putExtra(EXTRA_MESSAGE_ID, captureMessageId))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "VAssistARCaptureFinalize").start()
    }

    private fun sendStatus(action: String, success: Boolean, error: String?, id: String = messageId) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_MESSAGE_ID, id)
            .putExtra("success", success).putExtra("error", error))
    }
}
