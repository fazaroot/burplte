package com.example.burplite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.burplite.model.HttpTransaction
import com.example.burplite.ui.theme.glassCard

/**
 * Intercept tab: shows the QUEUE of paused requests (audit B2 fix — nothing
 * gets lost), lets the user pick one, edit it, then Forward/Drop, with
 * bulk Forward-all/Drop-all like Burp's Intercept tab.
 */
@Composable
fun InterceptQueueScreen(viewModel: ProxyViewModel) {
    val pending by viewModel.pending.collectAsState()
    val interceptOn by viewModel.interceptEnabled.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }

    val current = pending.firstOrNull { it.id == selectedId } ?: pending.firstOrNull()

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Intercept",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text("ON", style = MaterialTheme.typography.labelMedium)
            Switch(checked = interceptOn, onCheckedChange = viewModel::toggleIntercept)
        }
        Text(
            if (interceptOn) "Requests pause here before reaching the server"
            else "OFF — traffic flows automatically",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        val respPending by viewModel.pendingResponses.collectAsState()
        var selectedRespId by remember { mutableStateOf<String?>(null) }
        val currentResp = respPending.firstOrNull { it.id == selectedRespId } ?: respPending.firstOrNull()

        if (pending.isEmpty() && respPending.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing paused.\nTurn Intercept ON (set a filter in Rules tab), browse, then review here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return
        }

        if (pending.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paused requests (${pending.size})",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.forwardAll() }) { Text("Forward all") }
                TextButton(onClick = { viewModel.dropAll() }) { Text("Drop all") }
            }
            // Queue selector — every paused request stays visible and selectable.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pending, key = { it.id }) { p ->
                    FilterChip(
                        selected = current?.id == p.id,
                        onClick = { selectedId = p.id },
                        label = { Text("${p.request.method.uppercase()} ${hostOf(p.request.url)}") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            current?.let { tx ->
                InterceptEditorCard(tx = tx, viewModel = viewModel, modifier = Modifier.weight(1f))
            }
        }

        if (respPending.isNotEmpty()) {
            if (pending.isNotEmpty()) Spacer(Modifier.height(12.dp))
            Text(
                "Paused responses (${respPending.size})",
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(respPending, key = { it.id }) { r ->
                    FilterChip(
                        selected = currentResp?.id == r.id,
                        onClick = { selectedRespId = r.id },
                        label = {
                            Text("${r.response?.statusCode ?: "?"} ${hostOf(r.request.url)}")
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            currentResp?.let { tx ->
                ResponseEditorCard(tx = tx, viewModel = viewModel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InterceptEditorCard(tx: HttpTransaction, viewModel: ProxyViewModel, modifier: Modifier = Modifier) {
    var method by remember(tx.id) { mutableStateOf(tx.request.method) }
    var url by remember(tx.id) { mutableStateOf(tx.request.url) }
    var headersText by remember(tx.id) {
        mutableStateOf(tx.request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var bodyText by remember(tx.id) { mutableStateOf(String(tx.request.body)) }

    Column(modifier.fillMaxWidth()) {

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .glassCard()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            Text("Headers", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = headersText, onValueChange = { headersText = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
            )
            Text("Body", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = bodyText, onValueChange = { bodyText = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.drop(tx) },
                modifier = Modifier.weight(1f)
            ) { Text("Drop") }
            Button(
                onClick = {
                    val headersMap = parseHeaders(headersText)
                    viewModel.updateRequest(tx, method, url, headersMap, bodyText)
                    viewModel.forward(tx)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Forward") }
        }
    }
}

/** Edit a paused RESPONSE (status + body) before it is delivered to the client. */
@Composable
private fun ResponseEditorCard(tx: HttpTransaction, viewModel: ProxyViewModel, modifier: Modifier = Modifier) {
    var statusText by remember(tx.id) {
        mutableStateOf((tx.editedStatus ?: tx.response?.statusCode ?: 200).toString())
    }
    var bodyText by remember(tx.id) { mutableStateOf(String(tx.response?.body ?: ByteArray(0))) }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .glassCard()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = statusText, onValueChange = { statusText = it },
                    label = { Text("Status") },
                    modifier = Modifier.width(110.dp), singleLine = true
                )
                OutlinedTextField(
                    value = hostOf(tx.request.url), onValueChange = {},
                    label = { Text("URL") }, readOnly = true,
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }
            Text(
                "Body — edit before it reaches the client",
                style = MaterialTheme.typography.labelLarge
            )
            OutlinedTextField(
                value = bodyText, onValueChange = { bodyText = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.rejectResponse(tx) },
                modifier = Modifier.weight(1f)
            ) { Text("Drop") }
            Button(
                onClick = {
                    val st = statusText.trim().toIntOrNull()
                        ?: tx.response?.statusCode ?: 200
                    viewModel.approveResponseWithEdit(tx, st, bodyText)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Approve & Send") }
        }
    }
}

/** Read-only tabbed view for a completed transaction (tap from history list). */
@Composable
fun TransactionDetailScreen(tx: HttpTransaction, onClose: () -> Unit, onSendToRepeater: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "${tx.request.method.uppercase()} ${hostOf(tx.request.url)}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSendToRepeater) { Text("To Repeater") }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(buildCurl(tx)))
            }) { Text("Copy cURL") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Request") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Response") })
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (tab == 0) {
                MonoBlock("REQUEST LINE + HEADERS") {
                    buildString {
                        appendLine("${tx.request.method} ${tx.request.url}")
                        tx.request.headers.forEach { (k, v) -> appendLine("$k: $v") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                MonoBlock("BODY (${tx.request.body.size} B)") { String(tx.request.body).preview() }
            } else {
                val resp = tx.response
                if (resp == null) {
                    Text("No response yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MonoBlock("STATUS + HEADERS") {
                        buildString {
                            appendLine("Status: ${resp.statusCode}")
                            resp.headers.forEach { (k, v) -> appendLine("$k: $v") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    MonoBlock("BODY (${resp.body.size} B)") { String(resp.body).preview() }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MonoBlock(label: String, text: () -> String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text(),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .glassCard()
            .padding(10.dp)
    )
}

internal fun String.preview(): String =
    if (length > 20_000) "${take(20_000)}\n… (truncated)" else this

internal fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')

internal fun parseHeaders(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx == -1) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()

/** Export a transaction as a ready-to-run cURL command. */
internal fun buildCurl(tx: HttpTransaction): String = buildString {
    append("curl -k -X ").append(tx.request.method.uppercase())
    append(" '").append(tx.request.url).append("'")
    tx.request.headers.forEach { (k, v) ->
        if (!k.equals("host", true) && !k.equals("content-length", true)) {
            append(" -H '").append(k).append(": ").append(v.replace("'", "'\\''")).append("'")
        }
    }
    if (tx.request.body.isNotEmpty()) {
        append(" --data-raw '").append(String(tx.request.body).replace("'", "'\\''")).append("'")
    }
}
