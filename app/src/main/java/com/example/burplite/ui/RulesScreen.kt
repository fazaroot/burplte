package com.example.burplite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.burplite.proxy.ProxySettings
import com.example.burplite.ui.theme.glassCard

/**
 * Rules & filters tab: granular interception filter, response interception,
 * and block/redirect rule engine.
 */
@Composable
fun RulesScreen(viewModel: ProxyViewModel) {
    val rules by viewModel.rules.collectAsState()
    val filterEnabled by viewModel.filterEnabled.collectAsState()
    val filterText by viewModel.filterText.collectAsState()
    val respIntercept by viewModel.responseInterceptOn.collectAsState()

    var newPattern by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    var newAction by remember { mutableStateOf(ProxySettings.Action.BLOCK) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Rules & Filters",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 14.dp)
        )
        Spacer(Modifier.height(10.dp))

        // ---- intercept filter ----
        Column(
            Modifier.fillMaxWidth().glassCard().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intercept Filter", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = filterEnabled, onCheckedChange = {
                    viewModel.setInterceptFilter(it, filterText)
                })
            }
            OutlinedTextField(
                value = filterText,
                onValueChange = { viewModel.setInterceptFilter(filterEnabled, it) },
                label = { Text("host:x.com · method:POST · substring") },
                singleLine = true,
                enabled = filterEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intercept responses too", modifier = Modifier.weight(1f))
                Switch(checked = respIntercept, onCheckedChange = viewModel::setResponseIntercept)
            }
            Text(
                "Paused responses appear in the Intercept tab for editing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- rules list ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Block & Redirect Rules", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (rules.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearRules() }) { Text("Clear all") }
            }
        }

        if (rules.isEmpty()) {
            Text(
                "No rules. Add one below to block or redirect matching URLs.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().glassCard().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${r.action.name} · ${r.pattern}",
                                fontWeight = FontWeight.Medium
                            )
                            if (r.action == ProxySettings.Action.REDIRECT && r.target.isNotBlank()) {
                                Text(
                                    "→ ${r.target}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Switch(checked = r.enabled, onCheckedChange = {
                            viewModel.toggleRule(r.id, it)
                        })
                        IconButton(onClick = { viewModel.removeRule(r.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete rule")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- add-rule form ----
        Column(
            Modifier.fillMaxWidth().glassCard().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add Rule", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = newAction == ProxySettings.Action.BLOCK,
                    onClick = { newAction = ProxySettings.Action.BLOCK },
                    label = { Text("BLOCK") }
                )
                FilterChip(
                    selected = newAction == ProxySettings.Action.REDIRECT,
                    onClick = { newAction = ProxySettings.Action.REDIRECT },
                    label = { Text("REDIRECT") }
                )
            }
            OutlinedTextField(
                value = newPattern, onValueChange = { newPattern = it },
                label = { Text("URL contains…") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (newAction == ProxySettings.Action.REDIRECT) {
                OutlinedTextField(
                    value = newTarget, onValueChange = { newTarget = it },
                    label = { Text("Redirect to (https://new.host)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(
                onClick = {
                    viewModel.addRule(newPattern, newAction, newTarget)
                    newPattern = ""
                    newTarget = ""
                },
                enabled = newPattern.isNotBlank() &&
                    (newAction == ProxySettings.Action.BLOCK || newTarget.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add Rule") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
