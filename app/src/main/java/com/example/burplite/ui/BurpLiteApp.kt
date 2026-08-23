package com.example.burplite.ui

import android.content.Context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.burplite.model.HttpTransaction
import com.example.burplite.ui.theme.GlassBackground

/**
 * BurpLite shell — glassmorphism bottom-tab layout:
 * Traffic (history + filters), Intercept (pause/edit queue), Repeater.
 */
private data class TabSpec(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec("Traffic", Icons.Filled.ListAlt),
    TabSpec("Intercept", Icons.Filled.Bolt),
    TabSpec("Rules", Icons.Filled.Security),
    TabSpec("Repeater", Icons.Filled.Send)
)

@Composable
fun BurpLiteApp(viewModel: ProxyViewModel, caCertPath: String?) {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<HttpTransaction?>(null) }
    val pendingList by viewModel.pending.collectAsState()

    // Bug 3: one-time warning that a system-wide WiFi proxy breaks apps that
    // don't trust the BurpLite CA.
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("burplite_prefs", Context.MODE_PRIVATE)
    var showWarn by remember { mutableStateOf(!prefs.getBoolean("warn_system_proxy_v1", false)) }
    if (showWarn) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Important — proxy scope") },
            text = { Text("Setting this proxy system-wide (WiFi settings) will break HTTPS " +
                "in apps that don't trust the BurpLite CA (most apps). Recommended: use a " +
                "browser-level proxy setting, and revert the system proxy when done testing.") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("warn_system_proxy_v1", true).apply()
                    showWarn = false
                }) { Text("OK") }
            }
        )
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color.Black.copy(alpha = 0.35f)) {
                    TABS.forEachIndexed { index, t ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index; selected = null },
                            icon = {
                                if (index == 1 && pendingList.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${pendingList.size}") } }) {
                                        Icon(t.icon, contentDescription = t.label)
                                    }
                                } else {
                                    Icon(t.icon, contentDescription = t.label)
                                }
                            },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> TrafficListScreen(
                        viewModel = viewModel,
                        caCertPath = caCertPath,
                        onOpenTransaction = { selected = it }
                    )
                    1 -> InterceptQueueScreen(viewModel = viewModel)
                    2 -> RulesScreen(viewModel = viewModel)
                    3 -> RepeaterScreen(seed = selected, viewModel = viewModel)
                }
            }
        }

        // Full-screen detail overlay on top of the tabs.
        selected?.let { tx ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                TransactionDetailScreen(
                    tx = tx,
                    onClose = { selected = null },
                    onSendToRepeater = { tab = 2 }
                )
            }
        }
    }
}
