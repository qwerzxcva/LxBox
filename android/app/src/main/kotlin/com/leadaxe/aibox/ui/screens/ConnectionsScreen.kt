package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.engine.vpn.BoxEngine
import com.leadaxe.aibox.engine.vpn.BoxState
import com.leadaxe.aibox.engine.vpn.VpnRelay

private const val HISTORY_KEEP_MILLIS = 30 * 60 * 1000L

/**
 * Live connection monitor. Connections are grouped by the first hop of
 * their outbound chain — the node (or direct) the user actually picked —
 * with active connections before closed ones inside each group.
 */
@Composable
fun ConnectionsScreen(relay: VpnRelay) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.leadaxe.aibox.AIBoxApp
    val store = app.appStateStore
    val state by store.state.collectAsStateWithLifecycle()
    val engineState by relay.state.collectAsState()
    val connections by relay.connections.collectAsStateWithLifecycle()
    val nodeNames = remember(state.outbounds) { state.outbounds.associate { it.tag to (it.name.ifBlank { it.tag }) } }
    val groupNames = remember(state.outboundGroups) { state.outboundGroups.associate { it.tag to (it.name.ifBlank { it.tag }) } }

    var paused by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var detailFor by remember { mutableStateOf<BoxEngine.ConnectionInfo?>(null) }

    val running = engineState is BoxState.Connected
    val visible = remember(connections, showHistory) {
        val now = System.currentTimeMillis()
        connections.filter { c ->
            showHistory || c.active || now - c.closedAt < HISTORY_KEEP_MILLIS
        }
    }
    val groups = remember(visible, nodeNames, groupNames) {
        visible
            .groupBy { it.groupKey }
            .map { (tag, conns) ->
                // Proxy groups first, then direct, then anything unknown;
                // alphabetical within the same class.
                val rank = when {
                    tag in groupNames -> 0
                    tag == DirectOutboundTag -> 1
                    else -> 2
                }
                Triple(rank, groupNames[tag] ?: nodeNames[tag] ?: tag, conns)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.connections_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (paused) R.string.connections_paused_subtitle
                        else R.string.connections_live_subtitle,
                        visible.count { it.active },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    paused = !paused
                    relay.setConnectionsPaused(paused)
                },
                enabled = running,
            ) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = stringResource(
                        if (paused) R.string.connections_resume else R.string.connections_pause,
                    ),
                )
            }
            IconButton(onClick = { showHistory = !showHistory }) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = stringResource(R.string.connections_history),
                    tint = if (showHistory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!running && visible.isEmpty()) {
            Text(
                stringResource(R.string.connections_empty_not_running),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (visible.isEmpty()) {
            Text(
                stringResource(R.string.connections_empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            groups.forEach { conns ->
                val key = conns.first().groupKey
                item(key = "group-$key") {
                    val expanded = key in expandedGroups
                    val label = groupNames[key] ?: nodeNames[key] ?: key
                    val activeCount = conns.count { it.active }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable {
                                expandedGroups = if (expanded) expandedGroups - key else expandedGroups + key
                            },
                        colors = CardDefaults.elevatedCardColors(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.connections_group_summary, activeCount, conns.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (expanded) "▾" else "▸",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    if (expanded) {
                        conns.sortedWith(
                            compareBy<BoxEngine.ConnectionInfo> { !it.active }
                                .thenByDescending { it.createdAt },
                        ).forEach { conn ->
                            ConnectionRow(
                                conn = conn,
                                nodeNames = nodeNames,
                                onClose = { relay.requestCloseConnection(conn.id) },
                                onOpen = { detailFor = conn },
                            )
                        }
                    }
                }
            }
        }
    }

    detailFor?.let { conn ->
        ConnectionDetailDialog(conn = conn, nodeNames = nodeNames, onDismiss = { detailFor = null })
    }
}

@Composable
private fun ConnectionRow(
    conn: BoxEngine.ConnectionInfo,
    nodeNames: Map<String, String>,
    onClose: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
            .clickable { onOpen() },
        colors = if (conn.active) CardDefaults.outlinedCardColors() else CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conn.domain.ifBlank { conn.destination },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        conn.network.uppercase(),
                        formatBytes(conn.uplinkTotal),
                        formatBytes(conn.downlinkTotal),
                        if (!conn.active) stringResource(R.string.connections_closed) else null,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                nodeNames[conn.groupKey] ?: conn.groupKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp, start = 8.dp),
            )
            IconButton(onClick = onClose, enabled = conn.active) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.connections_close),
                    tint = if (conn.active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionDetailDialog(
    conn: BoxEngine.ConnectionInfo,
    nodeNames: Map<String, String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                conn.domain.ifBlank { conn.destination },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.connections_detail_status), stringResource(if (conn.active) R.string.connections_status_active else R.string.connections_closed))
                DetailRow(stringResource(R.string.connections_detail_outbound), (nodeNames[conn.groupKey] ?: conn.groupKey) + "  (" + conn.outboundType + ")")
                if (conn.chain.size > 1) {
                    DetailRow(stringResource(R.string.connections_detail_chain), conn.chain.joinToString(" → ") { nodeNames[it] ?: it })
                }
                DetailRow(stringResource(R.string.connections_detail_rule), conn.rule.ifBlank { "—" })
                DetailRow(stringResource(R.string.connections_detail_network), conn.network.uppercase() + if (conn.protocol.isNotBlank()) "  ·  " + conn.protocol else "")
                DetailRow(stringResource(R.string.connections_detail_source), conn.source)
                DetailRow(stringResource(R.string.connections_detail_destination), conn.destination)
                if (conn.packageNames.isNotEmpty()) {
                    DetailRow(stringResource(R.string.connections_detail_app), conn.packageNames.joinToString(", "))
                }
                if (conn.userId >= 0) {
                    DetailRow(stringResource(R.string.connections_detail_uid), conn.userId.toString())
                }
                if (conn.processPath.isNotBlank()) {
                    DetailRow(stringResource(R.string.connections_detail_process), conn.processPath)
                }
                DetailRow(
                    stringResource(R.string.connections_detail_traffic),
                    "↑ " + formatBytes(conn.uplinkTotal) + "   ↓ " + formatBytes(conn.downlinkTotal),
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
