package com.example.burplite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction
import com.example.burplite.ui.theme.glassCard

/**
 * The Repeater + Intruder-lite: fire a single tweaked request, or replace a
 * "{FUZZ}" marker with each payload and compare all responses.
 */
@Composable
fun RepeaterScreen(seed: HttpTransaction?, viewModel: ProxyViewModel) {
    var method by remember { mutableStateOf(seed?.request?.method ?: "GET") }
    var url by remember { mutableStateOf(seed?.request?.url ?: "http://") }
    var headersText by remember {
        mutableStateOf(
            seed?.request?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
        )
    }
    var bodyText by remember { mutableStateOf(seed?.request?.body?.let { String(it) } ?: "") }
    var payloadText by remember { mutableStateOf("") }

    val result by viewModel.repeaterResult.collectAsState()
    val sending by viewModel.repeaterSending.collectAsState()
    val fuzzResults by viewModel.fuzzResults.collectAsState()
    val fuzzRunning by viewModel.fuzzRunning.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Repeater", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = method, onValueChange = { method = it },
                label = { Text("Method") }, modifier = Modifier.width(110.dp), singleLine = true
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("URL — use {FUZZ} marker for fuzzing") },
                modifier = Modifier.weight(1f), singleLine = true
            )
        }
        OutlinedTextField(
            value = headersText, onValueChange = { headersText = it },
            label = { Text("Headers (one per line)") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
        )
        OutlinedTextField(
            value = bodyText, onValueChange = { bodyText = it },
            label = { Text("Body — use {FUZZ} marker for fuzzing") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
        )

        // ---- single send ----
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                viewModel.repeaterSend(method, url, parseHeaders(headersText), bodyText)
            }) { Text(if (sending) "Sending…" else "Send") }
            if (sending) CircularProgressIndicator(Modifier.width(24.dp))
        }

        // ---- fuzzer ----
        Column(
            Modifier.fillMaxWidth().glassCard().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Fuzzer (Intruder-lite)", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = payloadText, onValueChange = { payloadText = it },
                label = { Text("Payloads — one per line, replaces {FUZZ}") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.runFuzz(method, url, parseHeaders(headersText), bodyText,
                            payloadText.lines())
                    },
                    enabled = !fuzzRunning && url.isNotBlank()
                ) { Text(if (fuzzRunning) "Fuzzing…" else "Run Fuzzer") }
                if (fuzzRunning) CircularProgressIndicator(Modifier.width(24.dp))
                if (fuzzResults.isNotEmpty() && !fuzzRunning) {
                    OutlinedButton(onClick = { viewModel.clearFuzzResults() }) { Text("Clear") }
                }
            }
        }

        // ---- fuzz results table ----
        if (fuzzResults.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().glassCard().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("PAYLOAD", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium)
                Text("STATUS", Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
                Text("SIZE", Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
            }
            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                items(fuzzResults, key = { it.payload + it.status + it.size }) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.payload.ifEmpty { "(empty)" }, Modifier.weight(1.4f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sc = when {
                            r.status in 200..299 -> Color(0xFF69F0AE)
                            r.status in 300..399 -> Color(0xFFFFD54F)
                            else -> Color(0xFFFF6E6E)
                        }
                        Text("${r.status}", Modifier.weight(0.6f), color = sc)
                        Text(formatSize(r.size), Modifier.weight(0.6f))
                    }
                }
            }
        }

        result?.let { resp ->
            Text(
                "RESPONSE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                buildString {
                    appendLine("Status: ${resp.statusCode}")
                    resp.headers.forEach { (k, v) -> appendLine("$k: $v") }
                    appendLine()
                    append(String(resp.body).preview())
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(10.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun String.preview(): String =
    if (length > 20_000) "${take(20_000)}\n… (truncated)" else this

private fun formatSize(bytes: Int): String =
    if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"
