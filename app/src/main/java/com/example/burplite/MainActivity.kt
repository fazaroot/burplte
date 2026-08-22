package com.example.burplite

import android.content.Intent
import android.os.Bundle
import android.util.Log
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

        // CA certificate is saved in filesDir/ca.pem
        val caPath = File(filesDir, "ca.pem").absolutePath

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
