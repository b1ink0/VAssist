package com.vassist.app.ar

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.opengl.GLSurfaceView
import android.util.Base64
import org.json.JSONObject
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Native persistence and sharing for AR captures. Web owns hiding/restoring its controls. */
class ARCaptureController(
    private val activity: Activity,
    private val arSurface: GLSurfaceView,
) {
    companion object { private const val MAX_AVATAR_DATA_URL_CHARS = 32 * 1024 * 1024 }
    private var pendingStartId: String? = null
    private var pendingMediaAudio = true
    private var pendingMicrophone = false
    private var pendingStopId: String? = null
    private var activeVideoId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startTimeout: Runnable? = null
    private data class BarsState(val statusVisible: Boolean, val navigationVisible: Boolean, val behavior: Int)
    private var barsState: BarsState? = null
    private var barsUseCount = 0
    private data class AvatarRect(val left: Double, val top: Double, val width: Double, val height: Double)
    private data class PendingPhoto(
        val dataUrl: String,
        val rect: AvatarRect,
        val callback: (Uri?, String?) -> Unit,
    )
    private val pendingPhotoCallbacks = mutableListOf<PendingPhoto>()
    private var storagePermissionPending = false
    var requestStoragePermission: (() -> Unit)? = null
    var requestAudioPermission: (() -> Unit)? = null
    var requestProjectionConsent: ((Intent) -> Unit)? = null

    fun capturePhoto(dataUrl: String, rectJson: JSONObject, callback: (Uri?, String?) -> Unit) {
        val rect = parseAvatarRect(rectJson)
        if (rect == null) {
            callback(null, "The avatar canvas bounds are invalid. Refresh AR and try again.")
            return
        }
        if (!dataUrl.startsWith("data:image/png;base64,")) {
            callback(null, "The avatar canvas image is missing or is not a PNG.")
            return
        }
        if (dataUrl.length > MAX_AVATAR_DATA_URL_CHARS) {
            callback(null, "The avatar canvas image is too large to capture.")
            return
        }
        if (!hasStoragePermission()) {
            pendingPhotoCallbacks.add(PendingPhoto(dataUrl, rect, callback))
            requestStoragePermissionIfNeeded()
            return
        }
        hideSystemBars { error ->
            if (error != null) callback(null, error)
            else capturePhotoWithPermission(dataUrl, rect, callback)
        }
    }

    private fun parseAvatarRect(value: JSONObject): AvatarRect? {
        val left = value.optDouble("left", Double.NaN)
        val top = value.optDouble("top", Double.NaN)
        val width = value.optDouble("width", Double.NaN)
        val height = value.optDouble("height", Double.NaN)
        if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite()) return null
        // The fixed canvas can extend beyond the viewport when insets or the keyboard change.
        // Canvas.drawBitmap clips the visible part to the camera output.
        if (left < -2 || top < -2 || width <= 0 || height <= 0 || width > 3 || height > 3 || left + width > 3 || top + height > 3) return null
        return AvatarRect(left, top, width, height)
    }

    private fun capturePhotoWithPermission(
        dataUrl: String,
        rect: AvatarRect,
        callback: (Uri?, String?) -> Unit,
    ) {
        val width = arSurface.width
        val height = arSurface.height
        if (!arSurface.holder.surface.isValid || width <= 0 || height <= 0) {
            restoreSystemBars(); callback(null, "The AR camera surface is not ready. Start AR and try again."); return
        }
        val bitmap = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        catch (e: OutOfMemoryError) { restoreSystemBars(); callback(null, "The AR capture is too large to allocate."); return }
        catch (e: Exception) { restoreSystemBars(); callback(null, e.message ?: "Could not allocate the capture image."); return }
        try { PixelCopy.request(arSurface, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                bitmap.recycle(); restoreSystemBars(); callback(null, "Could not copy the AR display (PixelCopy $result).")
                return@request
            }
            restoreSystemBars()
            Thread({
            var savedUri: Uri? = null
            var captureError: String? = null
            var avatar: Bitmap? = null
            var composite: Bitmap? = null
            try {
                val avatarBytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IllegalArgumentException("The avatar canvas PNG could not be decoded.")
                }
                if (bounds.outWidth > 16_384 || bounds.outHeight > 16_384 ||
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() > 64_000_000L
                ) {
                    throw IllegalArgumentException("The avatar canvas PNG dimensions are too large to capture.")
                }
                val targetWidth = (rect.width * bitmap.width).toInt().coerceAtLeast(1)
                val targetHeight = (rect.height * bitmap.height).toInt().coerceAtLeast(1)
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= targetWidth &&
                    bounds.outHeight / (sampleSize * 2) >= targetHeight
                ) sampleSize *= 2
                avatar = BitmapFactory.decodeByteArray(
                    avatarBytes, 0, avatarBytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ) ?: throw IllegalArgumentException("The avatar canvas PNG could not be decoded.")
                composite = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(composite!!).apply {
                    drawBitmap(bitmap, 0f, 0f, null)
                    val target = android.graphics.RectF(
                        (rect.left * bitmap.width).toFloat(),
                        (rect.top * bitmap.height).toFloat(),
                        ((rect.left + rect.width) * bitmap.width).toFloat(),
                        ((rect.top + rect.height) * bitmap.height).toFloat(),
                    )
                    drawBitmap(avatar!!, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "VAssist_AR_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VAssist")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore could not create the photo.")
                savedUri = uri
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    if (!composite!!.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IllegalStateException("PNG encoding failed.")
                } ?: throw IllegalStateException("Could not open the photo destination.")
                if (Build.VERSION.SDK_INT >= 29) {
                    activity.contentResolver.update(uri, android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                }
            } catch (e: Exception) {
                savedUri?.let { runCatching { activity.contentResolver.delete(it, null, null) } }
                savedUri = null
                captureError = e.message ?: "Could not save the photo."
            } catch (e: OutOfMemoryError) {
                savedUri?.let { runCatching { activity.contentResolver.delete(it, null, null) } }
                savedUri = null
                captureError = "The AR capture is too large to compose in memory."
            }
            finally { bitmap.recycle(); avatar?.recycle(); composite?.recycle() }
            mainHandler.post {
                callback(savedUri, captureError)
            }
            }, "VAssistARPhotoSave").start()
        }, Handler(Looper.getMainLooper())) } catch (e: Exception) {
            bitmap.recycle(); restoreSystemBars(); callback(null, e.message ?: "Could not copy the AR display.")
        }
    }

    fun requestVideo(messageId: String, mediaAudio: Boolean, microphone: Boolean) {
        if (pendingStartId != null || activeVideoId != null) {
            emitStartResult(messageId, false, "An AR recording is already starting or active.")
            return
        }
        if (mediaAudio && Build.VERSION.SDK_INT < 29) {
            emitStartResult(messageId, false, "Media audio recording requires Android 10 or newer. Turn off media audio to record video on this device.")
            return
        }
        pendingStartId = messageId
        pendingMediaAudio = mediaAudio
        pendingMicrophone = microphone
        if (!hasStoragePermission()) {
            requestStoragePermissionIfNeeded()
            return
        }
        requestAudioThenProjection(messageId)
    }

    private fun hasStoragePermission(): Boolean = Build.VERSION.SDK_INT >= 29 ||
        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermissionIfNeeded() {
        if (storagePermissionPending) return
        storagePermissionPending = true
        try {
            requestStoragePermission?.invoke() ?: throw IllegalStateException("Storage permission request is unavailable.")
        } catch (e: Exception) {
            storagePermissionPending = false
            failPendingStorageRequests(e.message ?: "Could not request storage permission.")
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        storagePermissionPending = false
        if (!granted) {
            failPendingStorageRequests("Storage permission was denied. Allow storage access for VAssist in Android Settings, then retry the AR capture.")
            return
        }
        val photoCallbacks = pendingPhotoCallbacks.toList()
        pendingPhotoCallbacks.clear()
        photoCallbacks.forEach { photo -> capturePhoto(photo.dataUrl, JSONObject().apply {
            put("left", photo.rect.left); put("top", photo.rect.top)
            put("width", photo.rect.width); put("height", photo.rect.height)
        }, photo.callback) }
        pendingStartId?.let(::requestAudioThenProjection)
    }

    private fun requestAudioThenProjection(id: String) {
        if ((pendingMediaAudio || pendingMicrophone) &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestAudioPermission?.invoke() ?: throw IllegalStateException("Audio permission request is unavailable.")
            } catch (e: Exception) {
                pendingStartId = null
                emitStartResult(id, false, e.message ?: "Could not request audio permission.")
            }
            return
        }
        launchProjectionConsent(id)
    }

    fun onAudioPermissionResult(granted: Boolean) {
        val id = pendingStartId ?: return
        if (granted) launchProjectionConsent(id)
        else {
            pendingStartId = null
            emitStartResult(id, false, "Audio permission was denied. Allow it or turn off audio to record video.")
        }
    }

    private fun failPendingStorageRequests(message: String) {
        val photos = pendingPhotoCallbacks.toList()
        pendingPhotoCallbacks.clear()
        photos.forEach { it.callback(null, message) }
        pendingStartId?.let { id ->
            pendingStartId = null
            emitStartResult(id, false, message)
        }
    }

    private fun launchProjectionConsent(id: String) {
        try {
            val manager = activity.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val consentIntent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            } else manager.createScreenCaptureIntent()
            requestProjectionConsent?.invoke(consentIntent)
                ?: throw IllegalStateException("Screen recording consent request is unavailable.")
        } catch (e: Exception) {
            pendingStartId = null
            emitStartResult(id, false, e.message ?: "Could not request screen capture permission.")
        }
    }

    fun onProjectionConsent(resultCode: Int, data: Intent?) {
        val id = pendingStartId ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingStartId = null
            emitStartResult(id, false, "Screen recording permission was denied.")
            return
        }
        hideSystemBars { barsError ->
            if (barsError != null) {
                pendingStartId = null
                emitStartResult(id, false, barsError)
                return@hideSystemBars
            }
            startProjectionService(id, resultCode, data)
        }
    }

    private fun startProjectionService(id: String, resultCode: Int, data: Intent) {
        try {
            val intent = Intent(activity, ARCaptureService::class.java).apply {
                action = ARCaptureService.ACTION_START
                putExtra(ARCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ARCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ARCaptureService.EXTRA_MESSAGE_ID, id)
                putExtra(ARCaptureService.EXTRA_MEDIA_AUDIO, pendingMediaAudio)
                putExtra(ARCaptureService.EXTRA_MICROPHONE, pendingMicrophone)
            }
            androidx.core.content.ContextCompat.startForegroundService(activity, intent)
            val timeout = Runnable {
                if (pendingStartId == id) {
                    pendingStartId = null
                    restoreSystemBars()
                    runCatching { activity.startService(Intent(activity, ARCaptureService::class.java).setAction(ARCaptureService.ACTION_STOP)) }
                    emitStartResult(id, false, "Android did not confirm that screen recording started. Try again.")
                }
            }
            startTimeout = timeout
            mainHandler.postDelayed(timeout, 20_000L)
        } catch (e: Exception) {
            pendingStartId = null
            restoreSystemBars()
            emitStartResult(id, false, e.message ?: "Could not start screen recording.")
        }
    }

    fun stopVideo(messageId: String) {
        if (pendingStartId != null) {
            emitStopResult(messageId, false, "Recording has not started yet.")
            return
        }
        if (activeVideoId == null) {
            emitStopResult(messageId, false, "There is no active AR recording.")
            return
        }
        pendingStopId = messageId
        try {
            activity.startService(Intent(activity, ARCaptureService::class.java).setAction(ARCaptureService.ACTION_STOP))
        } catch (e: Exception) {
            pendingStopId = null
            emitStopResult(messageId, false, e.message ?: "Could not stop the AR recording.")
        }
    }

    fun stopForHostDestroy() {
        startTimeout?.let(mainHandler::removeCallbacks)
        startTimeout = null
        while (barsState != null) restoreSystemBars()
        if (activeVideoId == null && pendingStartId == null) return
        runCatching {
            activity.startService(Intent(activity, ARCaptureService::class.java).setAction(ARCaptureService.ACTION_STOP))
        }
    }

    private fun emitStartResult(id: String, success: Boolean, error: String?) {
        val out = org.json.JSONObject().put("type", "startVideoResult").put("messageId", id).put("success", success)
        if (error != null) out.put("error", error)
        listener?.invoke(out)
    }

    private fun emitStopResult(id: String, success: Boolean, error: String?) {
        val out = org.json.JSONObject().put("type", "stopVideoResult").put("messageId", id).put("success", success)
        if (error != null) out.put("error", error)
        listener?.invoke(out)
    }

    var listener: ((org.json.JSONObject) -> Unit)? = null

    fun onVideoStarted(id: String?, success: Boolean, error: String?) {
        val startId = pendingStartId
        if (startId == null || startId != id) return
        startTimeout?.let(mainHandler::removeCallbacks)
        startTimeout = null
        pendingStartId = null
        if (success) activeVideoId = startId
        else restoreSystemBars()
        emitStartResult(startId, success, error)
    }

    fun onVideoStopped(id: String?, uri: Uri?, error: String?) {
        val startId = pendingStartId
        if (startId != null && startId == id) {
            startTimeout?.let(mainHandler::removeCallbacks)
            startTimeout = null
            pendingStartId = null
            restoreSystemBars()
            emitStartResult(startId, false, error ?: "Recording stopped before it started.")
            return
        }
        if (activeVideoId != id) return
        val stopId = pendingStopId
        pendingStopId = null
        activeVideoId = null
        restoreSystemBars()
        val out = org.json.JSONObject().put("type", if (stopId == null) "videoStopped" else "stopVideoResult")
            .put("messageId", stopId ?: "").put("success", error == null && uri != null)
        if (uri != null) out.put("uri", uri.toString())
        if (error != null) out.put("error", error)
        else if (uri == null) out.put("error", "Recording ended without a saved video.")
        listener?.invoke(out)
    }

    fun shareCapture(uriText: String, callback: (String?) -> Unit) {
        try {
            val uri = Uri.parse(uriText)
            val mime = activity.contentResolver.getType(uri) ?: "video/mp4"
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share AR capture"))
            callback(null)
        } catch (e: Exception) { callback(e.message ?: "Could not share this capture.") }
    }

    private fun hideSystemBars(callback: (String?) -> Unit) {
        val decor = activity.window.decorView
        val controller = WindowInsetsControllerCompat(activity.window, decor)
        if (barsState != null) {
            barsUseCount += 1
            awaitSystemBarsHidden(decor, callback)
            return
        }
        val insets = ViewCompat.getRootWindowInsets(decor)
        if (insets == null) {
            callback("System bar visibility is not available yet. Wait for the app to finish opening, then retry.")
            return
        }
        val behavior = controller.systemBarsBehavior
        barsState = BarsState(
            insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
            behavior
        )
        barsUseCount = 1
        try {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            awaitSystemBarsHidden(decor, callback)
        } catch (e: Exception) {
            restoreSystemBars()
            callback(e.message ?: "Could not hide Android system bars for capture.")
        }
    }

    private fun awaitSystemBarsHidden(
        decor: android.view.View,
        callback: (String?) -> Unit
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + 1_500L
        fun check() {
            val insets = ViewCompat.getRootWindowInsets(decor)
            val hidden = insets != null &&
                !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            if (hidden) callback(null)
            else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                restoreSystemBars()
                callback("Android system bars did not hide in time. Retry the capture.")
            } else mainHandler.postDelayed({ check() }, 50L)
        }
        decor.postOnAnimation { check() }
    }

    private fun restoreSystemBars() {
        val state = barsState ?: return
        barsUseCount = (barsUseCount - 1).coerceAtLeast(0)
        if (barsUseCount > 0) return
        barsState = null
        runCatching {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = state.behavior
            if (state.statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (state.navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}
