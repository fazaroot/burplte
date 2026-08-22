package com.example.burplite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction

/**
 * The Repeater: take any past transaction (or a blank template),
 * tweak payloads (e.g. inject ' OR '1'='1 into a param), and fire
 * repeatedly against the target — exactly the SQLi/XSS testing loop.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(topBar = { TopAppBar(title = { Text("Repeater") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = method, onValueChange = { method = it },
                    label = { Text("Method") }, modifier = Modifier.width(110.dp)
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL") }, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = headersText, onValueChange = { headersText = it },
                label = { Text("Headers (one per line)") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
            )
            OutlinedTextField(
                value = bodyText, onValueChange = { bodyText = it },
                label = { Text("Body — edit your SQLi/XSS payload here") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
            Button(onClick = {
                val headersMap = headersText.lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }.toMap()
                viewModel.repeaterSend(method, url, headersMap, bodyText)
            }) { Text("Send") }

            Divider(Modifier.padding(vertical = 8.dp))
            Text("RESPONSE", style = MaterialTheme.typography.titleSmall)
            result?.let { resp ->
                Text("Status: ${resp.statusCode}", fontFamily = FontFamily.Monospace)
                resp.headers.forEach { (k, v) -> Text("$k: $v", fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Text(String(resp.body), fontFamily = FontFamily.Monospace)
            }
        }
    }
}
