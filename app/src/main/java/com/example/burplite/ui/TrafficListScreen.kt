package com.example.burplite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction
import com.example.burplite.util.CertShare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficListScreen(
    viewModel: ProxyViewModel,
    caCertPath: String?,
    onOpenTransaction: (HttpTransaction) -> Unit,
    onOpenRepeater: (HttpTransaction) -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val interceptOn by viewModel.interceptEnabled.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BurpLite — HTTP History") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Intercept", modifier = Modifier.padding(end = 4.dp))
                        Switch(checked = interceptOn, onCheckedChange = viewModel::toggleIntercept)
                    }
                    IconButton(onClick = {
                        caCertPath?.let { CertShare.shareRootCa(context, it) }
                    }) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = "Install CA cert")
                    }
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        if (transactions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No traffic yet. Set device proxy to 127.0.0.1:<port> and browse.")
            }
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(transactions) { tx ->
                TransactionRow(tx, onClick = { onOpenTransaction(tx) }, onLongClick = { onOpenRepeater(tx) })
                Divider()
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: HttpTransaction, onClick: () -> Unit, onLongClick: () -> Unit) {
    val statusColor = when (tx.response?.statusCode) {
        null -> Color.Gray
        in 200..299 -> Color(0xFF2E7D32)
        in 300..399 -> Color(0xFFF9A825)
        in 400..599 -> Color(0xFFC62828)
        else -> Color.Gray
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(56.dp)) {
            Text(tx.request.method, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(tx.request.url, maxLines = 1)
            Text(
                (if (tx.isHttps) "HTTPS" else "HTTP") + " • " +
                    (tx.response?.statusCode?.toString() ?: "pending"),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
    }
}
