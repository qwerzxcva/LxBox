package com.leadaxe.lxbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.app.OutboundProfile
import com.leadaxe.lxbox.app.Subscription
import com.leadaxe.lxbox.engine.share.ShareLinkParser
import com.leadaxe.lxbox.engine.share.SubscriptionFetcher
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun SubscriptionsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LxBoxApp
    val store = app.appStateStore
    val state by store.state.collectAsState()
    val fetcher = remember { SubscriptionFetcher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { addDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Add subscription")
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val r = fetcher.refreshAll(state.subscriptions)
                            store.update { current ->
                                current.copy(
                                    outbounds = r.outbounds,
                                    subscriptions = current.subscriptions.map { sub ->
                                        if (sub.id in r.failures) sub else sub.copy(lastUpdatedEpochMillis = System.currentTimeMillis())
                                    },
                                )
                            }
                            snackbar.showSnackbar(
                                if (r.failures.isEmpty())
                                    "Refreshed ${r.outbounds.size} node(s)"
                                else
                                    "Refreshed with ${r.failures.size} failure(s)",
                            )
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text("Refresh all")
                    }
                    FilledTonalButton(onClick = { pasteDialog = true }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Text("Paste link")
                    }
                }
            }

            item { SectionHeader("Subscriptions (${state.subscriptions.size})") }
            items(state.subscriptions, key = { it.id }) { sub ->
                SubscriptionRow(
                    sub = sub,
                    onDelete = {
                        store.update { st ->
                            st.copy(
                                subscriptions = st.subscriptions.filterNot { it.id == sub.id },
                                outbounds = st.outbounds.filterNot { it.subscriptionId == sub.id },
                            )
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { fetcher.fetch(sub) }
                                .onSuccess { r ->
                                    store.update { st ->
                                        st.copy(
                                            outbounds = (st.outbounds.filterNot { it.subscriptionId == sub.id } + r.outbounds),
                                            subscriptions = st.subscriptions.map { it.takeIf { s -> s.id != sub.id } ?: sub.copy(lastUpdatedEpochMillis = System.currentTimeMillis()) },
                                        )
                                    }
                                    snackbar.showSnackbar("${sub.name}: ${r.outbounds.size} node(s)")
                                }
                                .onFailure { snackbar.showSnackbar("${sub.name}: ${it.message ?: "fetch failed"}") }
                        }
                    },
                )
            }

            item { SectionHeader("Nodes (${state.outbounds.size})") }
            items(state.outbounds, key = { it.id }) { node ->
                NodeRow(node = node, selected = node.tag == state.selectedOutbound, onSelect = { selected ->
                    store.update { it.copy(selectedOutbound = selected) }
                })
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { Snackbar(snackbarData = it) }
    }

    if (addDialog) {
        AddSubscriptionDialog(
            onDismiss = { addDialog = false },
            onAdd = { name, url ->
                val id = UUID.randomUUID().toString()
                store.update { st ->
                    st.copy(
                        subscriptions = st.subscriptions + Subscription(
                            id = id, name = name.ifBlank { url }, url = url,
                        ),
                    )
                }
                scope.launch {
                    runCatching { fetcher.fetch(Subscription(id = id, name = name, url = url)) }
                        .onSuccess { r ->
                            store.update { st ->
                                st.copy(outbounds = st.outbounds + r.outbounds)
                            }
                        }
                        .onFailure { snackbar.showSnackbar("Subscription fetch failed: ${it.message}") }
                }
                addDialog = false
            },
        )
    }

    if (pasteDialog) {
        PasteLinkDialog(
            onDismiss = { pasteDialog = false },
            onAdd = { raw ->
                val parsed = ShareLinkParser.parseMany(raw)
                val ok = parsed.mapNotNull { res ->
                    when (res) {
                        is ShareLinkParser.Result.Ok -> OutboundProfile(
                            id = UUID.randomUUID().toString(),
                            name = res.name,
                            type = res.type,
                            config = res.config,
                        )
                        is ShareLinkParser.Result.Err -> null
                    }
                }
                val errs = parsed.size - ok.size
                store.update { st -> st.copy(outbounds = st.outbounds + ok) }
                scope.launch { snackbar.showSnackbar("Added ${ok.size} node(s), ${errs} skipped") }
                pasteDialog = false
            },
        )
    }
}

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name.ifBlank { sub.url }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub.url, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onDelete) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: OutboundProfile,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.elevatedCardColors() else CardDefaults.outlinedCardColors(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onSelect(node.tag) })
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name.ifBlank { node.tag }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(node.type, style = MaterialTheme.typography.bodySmall)
            }
            FilterChip(
                selected = selected,
                onClick = { onSelect(node.tag) },
                label = { Text(if (selected) "selected" else "tap to use") },
            )
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subscription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Subscription URL") })
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, url) }, enabled = url.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasteLinkDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste share link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("vless/vmess/trojan/ss/hy2/tuic, one per line or base64.")
                OutlinedTextField(
                    value = raw, onValueChange = { raw = it },
                    label = { Text("Links") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(raw) }, enabled = raw.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}