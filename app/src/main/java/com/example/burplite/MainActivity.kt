package com.example.burplite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.example.burplite.proxy.ProxyForegroundService
import com.example.burplite.ui.BurpLiteApp
import com.example.burplite.ui.ProxyViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()
    private val proxyPort = 8080 // change or make user-configurable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the proxy as a foreground service so it survives backgrounding
        val intent = Intent(this, ProxyForegroundService::class.java)
            .putExtra(ProxyForegroundService.EXTRA_PORT, proxyPort)
        startForegroundService(intent)

        // CertificateAuthority always writes to filesDir with this fixed name (see
        // CertificateAuthority.kt), so we can resolve it directly without binding the service.
        val caPath = File(filesDir, "burplite_root_ca.pem").absolutePath

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BurpLiteApp(viewModel = viewModel, caCertPath = caPath)
                }
            }
        }
    }
}
