package com.example.burplite.proxy

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.burplite.R

private const val TAG = "ProxyForegroundService"

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
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        
        try {
            // Start foreground first (required on Android 12+)
            startForeground(NOTIF_ID, buildNotification(port))
            Log.d(TAG, "Foreground service started with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground", e)
        }
        
        // Start proxy server in background thread to avoid ANR
        Thread {
            try {
                startProxy(port)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting proxy in thread", e)
            }
        }.start()
        
        return START_STICKY
    }

    private fun startProxy(port: Int) {
        if (isRunning) {
            Log.d(TAG, "Proxy already running")
            return
        }
        
        try {
            Log.d(TAG, "Starting proxy server on port $port")
            server = ProxyServer(port, caStorageDir = filesDir)
            server?.start()
            isRunning = true
            Log.d(TAG, "Proxy server started successfully")
            updateNotification(port, "Running")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server", e)
            isRunning = false
            updateNotification(port, "Failed to start")
            
            // Try to cleanup resources
            try {
                server?.stop()
            } catch (e2: Exception) {
                Log.e(TAG, "Error during cleanup", e2)
            }
            server = null
        }
    }

    fun rootCaPath(): String? {
        return try {
            server?.rootCaPemPath
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root CA path", e)
            null
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        try {
            server?.stop()
            isRunning = false
            Log.d(TAG, "Proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy during destroy", e)
        }
        super.onDestroy()
    }

    private fun buildNotification(port: Int, status: String = "Starting"): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BurpLite Proxy - $status")
            .setContentText("Port: 127.0.0.1:$port")
            .setSmallIcon(R.drawable.ic_proxy)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(port: Int, status: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIF_ID, buildNotification(port, status))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Proxy Service", NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "BurpLite Proxy Service"
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channel", e)
            }
        }
    }

    companion object {
        const val EXTRA_PORT = "extra_port"
        private const val CHANNEL_ID = "burplite_proxy_channel"
        private const val NOTIF_ID = 1
    }
}
