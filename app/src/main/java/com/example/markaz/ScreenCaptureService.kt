package com.example.markaz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Consent-based Android WebRTC screen publisher. Stops when the user or Android revokes capture. */
class ScreenCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var token = ""
    private var sessionId = 0L
    private var projectionData: Intent? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var egl: EglBase? = null
    private var lastSignalId = 0L
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var pollThread: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopStreaming(); return START_NOT_STICKY }
        token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        projectionData = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (token.isBlank() || sessionId <= 0 || resultCode == Int.MIN_VALUE || projectionData == null) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        val stop = PendingIntent.getService(this, 4102, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("مشاركة الشاشة مفعّلة").setContentText("جارٍ تجهيز التقاط الشاشة واتصال الفيديو...").setOngoing(true).addAction(android.R.drawable.ic_media_pause, "إيقاف", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID, notification)
        if (running.compareAndSet(false, true)) { sendSignal("status", JSONObject().put("state", "starting")); main.post { initializeRtc() } }
        return START_NOT_STICKY
    }
    private fun initializeRtc() {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
            egl = EglBase.create()
            factory = PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext)).createPeerConnectionFactory()
            val ice = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            val config = PeerConnection.RTCConfiguration(listOf(ice)); config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> sendSignal("status", JSONObject().put("state", "connected"))
                        PeerConnection.IceConnectionState.DISCONNECTED -> sendSignal("status", JSONObject().put("state", "disconnected"))
                        PeerConnection.IceConnectionState.FAILED -> { sendSignal("status", JSONObject().put("state", "failed").put("message", "ICE connection failed; a TURN relay may be required.")); main.post { stopStreaming(false) } }
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) { if (candidate != null && running.get()) sendSignal("ice", JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)) }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
            }) ?: throw IllegalStateException("تعذر إنشاء اتصال WebRTC")
            val source = factory!!.createVideoSource(true); videoSource = source
            textureHelper = SurfaceTextureHelper.create("MarkazScreenCapture", egl!!.eglBaseContext)
            capturer = ScreenCapturerAndroid(projectionData!!, object : MediaProjection.Callback() { override fun onStop() { main.post { stopStreaming() } } })
            capturer!!.initialize(textureHelper, this, source.capturerObserver); capturer!!.startCapture(1280, 720, 15)
            val track = factory!!.createVideoTrack("markaz-screen", source); peer!!.addTrack(track, listOf("markaz-screen-stream"))
            sendSignal("status", JSONObject().put("state", "capturing"))
            startSignalPolling()
        } catch (e: Exception) {
            Log.e("MarkazScreen", "Screen capture initialization failed", e)
            sendSignal("status", JSONObject().put("state", "failed").put("message", (e.message ?: e.javaClass.simpleName).take(250)))
            stopStreaming(false)
        }
    }
    private fun startSignalPolling() {
        pollThread = Thread({ while (running.get()) {
            try {
                val response = request("GET", "/api/screen-sessions/$sessionId/signals?after_id=$lastSignalId")
                if (response.first in 200..299) {
                    val json = JSONObject(response.second); val signals = json.optJSONArray("signals") ?: JSONArray()
                    for (i in 0 until signals.length()) { val item = signals.getJSONObject(i); lastSignalId = maxOf(lastSignalId, item.optLong("id")); val kind = item.optString("kind"); val payload = item.optJSONObject("payload") ?: JSONObject(); main.post { handleSignal(kind, payload) } }
                    if (json.optString("status") == "stopped") { main.post { stopStreaming() }; break }
                }
            } catch (_: Exception) {}
            try { Thread.sleep(900) } catch (_: InterruptedException) { break }
        } }, "MarkazWebRtcSignals").apply { start() }
    }
    private fun handleSignal(kind: String, payload: JSONObject) {
        val pc = peer ?: return
        when (kind) {
            "stop" -> stopStreaming()
            "offer" -> {
                sendSignal("status", JSONObject().put("state", "offer_received"))
                val offer = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        synchronized(pendingRemoteIce) { pendingRemoteIce.forEach { pc.addIceCandidate(it) }; pendingRemoteIce.clear() }
                        pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription?) { if (answer == null) return; pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                sendSignal("answer", JSONObject().put("type", "answer").put("sdp", answer.description))
                                sendSignal("status", JSONObject().put("state", "answer_sent"))
                            }
                            override fun onSetFailure(error: String?) { reportRtcFailure("تعذر تثبيت رد الاتصال: " + (error ?: "unknown")) }
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) { reportRtcFailure("تعذر إنشاء رد الاتصال: " + (error ?: "unknown")) }
                        }, answer) }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String?) { reportRtcFailure("تعذر تثبيت الوصف المحلي: " + (error ?: "unknown")) }
                        override fun onCreateFailure(error: String?) { reportRtcFailure("تعذر إنشاء رد الاتصال: " + (error ?: "unknown")) }
                    }, MediaConstraints()) }
                    override fun onSetFailure(error: String?) { reportRtcFailure("رفض الهاتف عرض الاتصال: " + (error ?: "unknown")) }
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) { reportRtcFailure("تعذر قراءة عرض الاتصال: " + (error ?: "unknown")) }
                }, offer)
            }
            "ice" -> try {
                val candidate = IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.optString("candidate"))
                if (pc.remoteDescription == null) synchronized(pendingRemoteIce) { pendingRemoteIce.add(candidate) } else pc.addIceCandidate(candidate)
            } catch (_: Exception) {}
        }
    }
    private fun reportRtcFailure(message: String) {
        Log.e("MarkazScreen", message)
        sendSignal("status", JSONObject().put("state", "failed").put("message", message.take(250)))
        main.post { stopStreaming(false) }
    }
    private fun sendSignal(kind: String, payload: JSONObject) { Thread { try { request("POST", "/api/screen-sessions/$sessionId/signals", JSONObject().put("kind", kind).put("payload", payload)) } catch (e: Exception) { Log.w("MarkazScreen", "Signal send failed: $kind", e) } }.start() }
    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL(API_BASE + path).openConnection() as HttpURLConnection
        try { c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 12000; c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Accept", "application/json")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) } }
            val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream; return Pair(code, stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "مشاركة الشاشة", NotificationManager.IMPORTANCE_LOW)) }
    private fun stopStreaming(notifyPeer: Boolean = true) {
        if (!running.getAndSet(false)) { stopSelf(); return }
        if (notifyPeer) Thread { try { request("POST", "/api/screen-sessions/$sessionId/stop") } catch (_: Exception) {} }.start()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { factory?.dispose(); textureHelper?.dispose(); egl?.release() } catch (_: Exception) {}
        pollThread?.interrupt(); pollThread = null; capturer = null; peer = null; factory = null; videoSource = null; textureHelper = null; egl = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stopStreaming(); super.onDestroy() }
    companion object {
        const val ACTION_STOP = "com.example.markaz.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_TOKEN = "screen_auth_token"
        const val EXTRA_SESSION_ID = "screen_session_id"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val API_BASE = "https://markaz-ajhizati-api.onrender.com"
        private const val CHANNEL = "markaz_screen_capture"
        private const val NOTIFICATION_ID = 4102
    }
}