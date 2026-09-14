package com.leadaxe.lxbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.app.ClashModeDirect
import com.leadaxe.lxbox.app.ClashModeGlobal
import com.leadaxe.lxbox.app.ClashModeRule
import com.leadaxe.lxbox.app.ClashModes
import com.leadaxe.lxbox.app.MainActivity
import com.leadaxe.lxbox.engine.vpn.BoxController
import com.leadaxe.lxbox.engine.vpn.BoxEngine
import com.leadaxe.lxbox.engine.vpn.BoxRuntimeSnapshot
import com.leadaxe.lxbox.engine.vpn.BoxState
import androidx.compose.ui.platform.LocalContext

@Composable
fun HomeScreen(
    controller: BoxController,
    onRequestVpnConsent: (android.content.Intent) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as LxBoxApp).appStateStore }
    val appState by store.state.collectAsState()
    val engine = BoxEngine.shared()
    val boxState = engine?.state?.collectAsState()?.value ?: BoxState.Idle
    val runtime = engine?.runtime?.collectAsState()?.value ?: BoxRuntimeSnapshot()
    val activity = context as? MainActivity

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "L×Box",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )

        StatusCard(state = boxState, runtime = runtime, selectedNode = appState.selectedOutbound)

        ClashModeRow(
            current = appState.clashMode,
            onChange = { mode -> store.update { it.copy(clashMode = mode) } },
        )

        ConnectButton(
            state = boxState,
            onConnect = {
                val activityRef = activity
                val intent = controller.prepareVpn(activityRef ?: return@ConnectButton)
                if (intent != null) {
                    onRequestVpnConsent(intent)
                } else {
                    controller.startFromBackground()
                }
            },
            onDisconnect = { controller.stop() },
        )

        NodePickerPreview(
            outbounds = appState.outbounds,
            selected = appState.selectedOutbound,
            onSelect = { tag -> store.update { it.copy(selectedOutbound = tag) } },
        )
    }
}

@Composable
private fun StatusCard(
    state: BoxState,
    runtime: BoxRuntimeSnapshot,
    selectedNode: String,
) {
    val (statusLabel, statusColor) = when (state) {
        BoxState.Idle -> "Idle" to Color(0xFF6E7F8F)
        BoxState.Starting -> "Starting…" to Color(0xFF9D5CFF)
        is BoxState.Connected -> "Connected" to Color(0xFF1F6FEB)
        BoxState.Stopping -> "Stopping…" to Color(0xFFB0B8C1)
        is BoxState.Error -> "Error: ${state.message}" to Color(0xFFCC3344)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(statusLabel) })
                Spacer(Modifier.size(12.dp))
                Text(
                    text = if (state is BoxState.Connected)
                        "via ${selectedNode.ifBlank { "proxy" }}"
                    else "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TrafficColumn(label = "Upload", bytes = runtime.uplinkBytes, total = runtime.uplinkTotalBytes)
                TrafficColumn(label = "Download", bytes = runtime.downlinkBytes, total = runtime.downlinkTotalBytes)
                MetricColumn(label = "Goroutines", value = runtime.goroutines.toString())
                MetricColumn(label = "Memory", value = formatBytes(runtime.memoryBytes))
            }
            if (state is BoxState.Connected) {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun TrafficColumn(label: String, bytes: Long, total: Long) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatBytes(bytes), style = MaterialTheme.typography.titleMedium)
        if (total > 0) {
            Text("Σ ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ClashModeRow(
    current: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClashModes.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { onChange(mode) },
                label = {
                    Text(
                        when (mode) {
                            ClashModeRule -> "Rule"
                            ClashModeGlobal -> "Global"
                            ClashModeDirect -> "Direct"
                            else -> mode
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun ConnectButton(
    state: BoxState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is BoxState.Connected
    val busy = state is BoxState.Starting || state is BoxState.Stopping
    ElevatedButton(
        onClick = { if (connected) onDisconnect() else onConnect() },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(
            text = when {
                connected -> "Disconnect"
                busy -> "Working…"
                else -> "Connect"
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NodePickerPreview(
    outbounds: List<com.leadaxe.lxbox.app.OutboundProfile>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (outbounds.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select outbound", style = MaterialTheme.typography.titleMedium)
            outbounds.take(5).forEach { node ->
                FilterChip(
                    selected = node.tag == selected,
                    onClick = { onSelect(node.tag) },
                    label = { Text(node.name.ifBlank { node.tag }) },
                )
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "${value} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = value.toDouble() / 1024.0
    var u = 0
    while (v >= 1024.0 && u < units.lastIndex) { v /= 1024.0; u++ }
    return "%.1f %s".format(v, units[u])
}