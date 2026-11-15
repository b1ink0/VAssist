package com.vassist.app.ar

import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NativeARBridge(
    private val webView: WebView,
    private val sessionManager: ARCoreSessionManager?,
    private val capture: ARCaptureController? = null
) {
    companion object {
        private const val TAG = "VASSIST_AR"
        private const val APP_ORIGIN = "https://vassist.app"
    }

    @Volatile
    private var replyProxy: JavaScriptReplyProxy? = null
    private val pendingFrame = AtomicReference<String?>(null)
    private val frameScheduled = AtomicBoolean(false)

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "NativeARPlugin",
                setOf(APP_ORIGIN)
            ) { _, message, _, isMainFrame, proxy ->
                if (!isMainFrame) return@addWebMessageListener
                replyProxy = proxy
                try {
                    handleMessage(JSONObject(message.data ?: "{}"), proxy)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to handle native AR message", error)
                }
            }
        } else {
            Log.w(TAG, "This WebView does not support the native AR message bridge")
        }
    }

    private fun response(type: String, request: JSONObject): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("messageId", request.optString("messageId"))
        }

    private fun handleMessage(data: JSONObject, proxy: JavaScriptReplyProxy) {
        when (data.optString("action")) {
            "checkSupported" -> {
                proxy.postMessage(
                    response("checkSupportedResult", data).apply {
                        put("supported", sessionManager?.isSupported() == true)
                    }.toString()
                )
            }

            "startSession" -> {
                val result = sessionManager?.startSession()
                    ?: ARCoreSessionManager.StartResult(false, "AR session manager is unavailable")
                Log.i(TAG, "bridge:startSession success=${result.success} error=${result.error}")
                proxy.postMessage(
                    response("startSessionResult", data).apply {
                        put("success", result.success)
                        if (result.error != null) put("error", result.error)
                    }.toString()
                )
            }

            "stopSession" -> {
                Log.i(TAG, "bridge:stopSession")
                sessionManager?.stopSession()
                proxy.postMessage(
                    response("stopSessionResult", data).apply {
                        put("success", true)
                    }.toString()
                )
            }

            "hitTest" -> {
                val manager = sessionManager
                if (manager == null) {
                    proxy.postMessage(
                        response("hitTestResult", data).apply {
                            put("result", JSONObject.NULL)
                        }.toString()
                    )
                } else {
                    manager.hitTestAsync(
                        data.optDouble("x").toFloat(),
                        data.optDouble("y").toFloat()
                    ) { result ->
                        proxy.postMessage(
                            response("hitTestResult", data).apply {
                                put("result", result ?: JSONObject.NULL)
                            }.toString()
                        )
                    }
                }
            }

            "createAnchor" -> {
                val manager = sessionManager
                if (manager == null) {
                    proxy.postMessage(
                        response("createAnchorResult", data).apply {
                            put("anchor", JSONObject.NULL)
                        }.toString()
                    )
                } else {
                    manager.createAnchorAsync(
                        data.optDouble("worldX").toFloat(),
                        data.optDouble("worldY").toFloat(),
                        data.optDouble("worldZ").toFloat()
                    ) { anchor ->
                        proxy.postMessage(
                            response("createAnchorResult", data).apply {
                                put("anchor", anchor ?: JSONObject.NULL)
                            }.toString()
                        )
                    }
                }
            }

            "capturePhoto" -> capture?.capturePhoto(
                data.optString("avatarPngDataUrl"),
                data.optJSONObject("avatarRect") ?: JSONObject(),
            ) { uri, error ->
                postCaptureResult(response("capturePhotoResult", data).apply {
                    put("success", error == null)
                    if (uri != null) put("uri", uri.toString())
                    if (error != null) put("error", error)
                })
            } ?: postCaptureResult(response("capturePhotoResult", data).apply {
                put("success", false); put("error", "Native capture is unavailable")
            })

            "startVideo" -> capture?.requestVideo(data.optString("messageId"), data.optBoolean("mediaAudio", true), data.optBoolean("microphone", false))
                ?: postCaptureResult(response("startVideoResult", data).apply {
                    put("success", false); put("error", "Native capture is unavailable")
                })

            "stopVideo" -> capture?.stopVideo(data.optString("messageId"))
                ?: postCaptureResult(response("stopVideoResult", data).apply {
                    put("success", false); put("error", "Native capture is unavailable")
                })

            "shareCapture" -> capture?.shareCapture(data.optString("uri")) { error ->
                postCaptureResult(response("shareCaptureResult", data).apply {
                    put("success", error == null)
                    if (error != null) put("error", error)
                })
            } ?: postCaptureResult(response("shareCaptureResult", data).apply {
                put("success", false); put("error", "Native capture is unavailable")
            })
        }
    }

    fun postCaptureResult(message: JSONObject) {
        webView.post { replyProxy?.postMessage(message.toString()) }
    }

    /**
     * Keep at most one pending native frame. If the WebView is busy, stale
     * camera poses are replaced instead of building latency in its UI queue.
     */
    fun sendFrame(json: String) {
        pendingFrame.set(json)
        scheduleLatestFrame()
    }

    private fun scheduleLatestFrame() {
        if (!frameScheduled.compareAndSet(false, true)) return

        webView.postOnAnimation {
            pendingFrame.getAndSet(null)?.let { message ->
                replyProxy?.postMessage(message)
            }
            frameScheduled.set(false)
            if (pendingFrame.get() != null) scheduleLatestFrame()
        }
    }
}
