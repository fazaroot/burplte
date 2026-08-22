package com.example.burplite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction
import com.example.burplite.ui.theme.glassCard
import com.example.burplite.util.CertShare

/**
 * HTTP History — glassmorphism traffic list with URL search and status filters.
 */
@Composable
fun TrafficListScreen(
    viewModel: ProxyViewModel,
    caCertPath: String?,
    onOpenTransaction: (HttpTransaction) -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    val filtered = transactions.filter { tx ->
        val okQuery = query.isBlank() || tx.request.url.contains(query, ignoreCase = true)
        val code = tx.response?.statusCode
        okQuery && when (filter) {
            "2XX" -> code != null && code in 200..299
            "3XX" -> code != null && code in 300..399
            "ERR" -> code != null && code >= 400
            "HTTPS" -> tx.isHttps
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "HTTP History",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { caCertPath?.let { CertShare.shareRootCa(context, it) } }) {
                Icon(
                    Icons.Filled.Key, contentDescription = "Share CA certificate",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { viewModel.clearHistory() }) {
                Icon(
                    Icons.Filled.Delete, contentDescription = "Clear history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Filter by URL…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "2XX", "3XX", "ERR", "HTTPS").forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No traffic yet.\nSet the device proxy to 127.0.0.1:8080 and browse.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filtered, key = { it.id }) { tx ->
                    TransactionRow(tx, onClick = { onOpenTransaction(tx) })
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: HttpTransaction, onClick: () -> Unit) {
    val code = tx.response?.statusCode
    val statusColor = when {
        code == null -> Color(0xFF9AA3B5)
        code < 300 -> Color(0xFF69F0AE)
        code < 400 -> Color(0xFFFFD54F)
        else -> Color(0xFFFF6E6E)
    }
    val methodColor = when (tx.request.method.uppercase()) {
        "GET" -> Color(0xFF80DEEA)
        "POST" -> Color(0xFFFFCC80)
        "PUT" -> Color(0xFFB39DDB)
        "DELETE" -> Color(0xFFEF9A9A)
        else -> Color(0xFFE6EE9C)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .glassCard()
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tx.request.method.uppercase(),
                color = methodColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(64.dp)
            )
            if (tx.isHttps) {
                Icon(
                    Icons.Filled.Lock, contentDescription = "HTTPS",
                    Modifier.size(14.dp), tint = Color(0xFF9FA8DA)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                tx.request.url.removePrefix("http://").removePrefix("https://"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                code?.toString() ?: "…",
                color = statusColor,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            tx.response?.let { r ->
                Text(
                    formatSize(r.body.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Int): String =
    if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"
