package com.example.burplite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction

/**
 * Shown when InterceptStore pauses a request (intercept mode ON).
 * User can edit method/url/headers/body, then Forward or Drop.
 * Forwarding resumes the blocked Netty worker thread via tx.forward().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptEditorScreen(tx: HttpTransaction, viewModel: ProxyViewModel, onDone: () -> Unit) {
    var method by remember { mutableStateOf(tx.request.method) }
    var url by remember { mutableStateOf(tx.request.url) }
    var headersText by remember {
        mutableStateOf(tx.request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var bodyText by remember { mutableStateOf(String(tx.request.body)) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Intercepted request") }) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.drop(tx); onDone() },
                    modifier = Modifier.weight(1f)
                ) { Text("Drop") }
                Button(
                    onClick = {
                        val headersMap = parseHeaders(headersText)
                        viewModel.updateRequest(tx, method, url, headersMap, bodyText)
                        viewModel.forward(tx)
                        onDone()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Forward") }
            }
        }
    ) { padding ->
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
            Text("Headers", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = headersText, onValueChange = { headersText = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
            )
            Text("Body", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = bodyText, onValueChange = { bodyText = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
            )
        }
    }
}

/** Read-only view for a completed transaction's request + response (tap from history list). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(tx: HttpTransaction, onSendToRepeater: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${tx.request.method} detail") },
                actions = { TextButton(onClick = onSendToRepeater) { Text("To Repeater") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("REQUEST", style = MaterialTheme.typography.titleSmall)
            Text("${tx.request.method} ${tx.request.url}", fontFamily = FontFamily.Monospace)
            tx.request.headers.forEach { (k, v) -> Text("$k: $v", fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.height(8.dp))
            Text(String(tx.request.body), fontFamily = FontFamily.Monospace)

            Spacer(Modifier.height(20.dp))
            Text("RESPONSE", style = MaterialTheme.typography.titleSmall)
            val resp = tx.response
            if (resp == null) {
                Text("No response yet")
            } else {
                Text("Status: ${resp.statusCode}", fontFamily = FontFamily.Monospace)
                resp.headers.forEach { (k, v) -> Text("$k: $v", fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Text(String(resp.body), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun parseHeaders(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()
