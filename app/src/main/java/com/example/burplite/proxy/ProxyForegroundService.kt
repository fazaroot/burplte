package com.example.burplite.proxy

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.burplite.R

class ProxyForegroundService : Service() {

    private val binder = LocalBinder()
    private var server: ProxyServer? = null
    var isRunning = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): ProxyForegroundService = this@ProxyForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        startForeground(NOTIF_ID, buildNotification(port))
        startProxy(port)
        return START_STICKY
    }

    private fun startProxy(port: Int) {
        if (isRunning) return
        try {
            server = ProxyServer(port, caStorageDir = filesDir).also { it.start() }
            isRunning = true
            ProxyState.running = true
            ProxyState.lastError = null
            notifyPort(server!!.actualPort)
        } catch (e: Exception) {
            // Surface, never crash-loop: a stale listener or port conflict must
            // not kill the service silently (root cause of "no request at all").
            Log.e(TAG, "Failed to start proxy", e)
            ProxyState.running = false
            ProxyState.port = -1
            ProxyState.lastError = e.message
            isRunning = false
        }
    }

    private fun notifyPort(port: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification(port))
        } catch (e: Exception) {
            // Notification permission may be denied on Android 13+; the in-app
            // indicator still shows the true state, so this is non-fatal.
            Log.w(TAG, "notification failed (non-fatal): " + e.message)
        }
    }

    fun rootCaPath(): String? = server?.rootCaPemPath

    override fun onDestroy() {
        server?.stop()
        isRunning = false
        ProxyState.running = false
        ProxyState.port = -1
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BurpLite proxy active")
            .setContentText("Listening on 127.0.0.1:$port")
            .setSmallIcon(R.drawable.ic_proxy) // add a simple vector icon
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Proxy service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_PORT = "extra_port"
        private const val CHANNEL_ID = "burplite_proxy"
        private const val NOTIF_ID = 1
    }
}
