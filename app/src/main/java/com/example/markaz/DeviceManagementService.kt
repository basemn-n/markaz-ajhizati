package com.example.markaz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Visible, user-started device presence service.
 * Command polling/execution remains in MainActivity to avoid duplicate command claims.
 * Stops when authentication is revoked or the service is explicitly stopped.
 */
class DeviceManagementService : Service() {
    private val worker = ThreadLocal<Boolean>()
    @Volatile private var running = false
    private var authToken: String? = null
    private var deviceName: String = "جهاز أندرويد"
    private var thread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPresence()
            stopSelf()
            return START_NOT_STICKY
        }
        authToken = intent?.getStringExtra(EXTRA_TOKEN)
        deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: deviceName
        if (authToken.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())
        if (!running) {
            running = true
            thread = Thread({ presenceLoop() }, "markaz-presence").apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun presenceLoop() {
        while (running) {
            val token = authToken ?: break
            try {
                val connection = (URL("$API_BASE/api/android/presence").openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    connection.outputStream.use {
                        it.write(JSONObject().put("device_name", deviceName).toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    if (code == 401 || code == 403) {
                        Log.w(TAG, "Presence authorization rejected; stopping service")
                        running = false
                    } else if (code !in 200..299) {
                        Log.w(TAG, "Presence request returned HTTP $code")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Presence request failed", e)
            }

            // Wake periodically while the service is active; interruption stops promptly.
            try {
                Thread.sleep(PRESENCE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        stopSelf()
    }

    private fun stopPresence() {
        running = false
        thread?.interrupt()
        thread = null
        authToken = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "حالة اتصال الجهاز", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("مركز أجهزتي")
            .setContentText("اتصال الجهاز بالخادم نشط")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    override fun onDestroy() {
        stopPresence()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.markaz.action.START_DEVICE_MANAGEMENT"
        const val ACTION_STOP = "com.example.markaz.action.STOP_DEVICE_MANAGEMENT"
        const val EXTRA_TOKEN = "auth_token"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val API_BASE = "https://markaz-ajhizati-api.onrender.com"
        private const val TAG = "MarkazDeviceService"
        private const val CHANNEL_ID = "device_management"
        private const val NOTIFICATION_ID = 1001
        private const val PRESENCE_INTERVAL_MS = 30_000L
    }
}
