package com.example.burplite

import android.os.Build
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.example.burplite.proxy.ProxyForegroundService
import com.example.burplite.ui.BurpLiteApp
import com.example.burplite.ui.ProxyViewModel
import com.example.burplite.ui.theme.GlassColorScheme
import java.io.File

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()
    private val proxyPort = 8080 // change or make user-configurable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "MainActivity created")

        // Android 13+: request notification permission so the proxy-service
        // notification is allowed to show. Denial is non-fatal (in-app status
        // pill still reflects the real proxy state).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        try {
            // Start the proxy as a foreground service so it survives backgrounding
            val intent = Intent(this, ProxyForegroundService::class.java)
                .putExtra(ProxyForegroundService.EXTRA_PORT, proxyPort)
            
            Log.d(TAG, "Starting foreground service on port $proxyPort")
            startForegroundService(intent)
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }

        // CA certificate is saved by CertificateAuthority in filesDir
        val caPath = File(filesDir, "burplite_root_ca.pem").absolutePath

        try {
            setContent {
                MaterialTheme(colorScheme = GlassColorScheme) {
                    BurpLiteApp(viewModel = viewModel, caCertPath = caPath)
                }
            }
            Log.d(TAG, "UI content set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting content", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity destroyed")
        super.onDestroy()
    }
}
