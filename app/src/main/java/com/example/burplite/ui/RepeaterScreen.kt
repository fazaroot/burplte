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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction
import com.example.burplite.ui.theme.glassCard

/**
 * The Repeater: take any past transaction (or a blank template), tweak
 * payloads, and fire repeatedly against the target.
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

    val result by viewModel.repeaterResult.collectAsState()
    val sending by viewModel.repeaterSending.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Repeater",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 14.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = method, onValueChange = { method = it },
                label = { Text("Method") }, modifier = Modifier.width(110.dp), singleLine = true
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("URL") }, modifier = Modifier.weight(1f), singleLine = true
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
            label = { Text("Body — edit your payload here") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
        )
        Button(onClick = {
            viewModel.repeaterSend(method, url, parseHeaders(headersText), bodyText)
        }) {
            Text(if (sending) "Sending…" else "Send")
        }
        if (sending) CircularProgressIndicator(Modifier.width(24.dp))

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
