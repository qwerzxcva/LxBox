package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.ClashModeDirect
import com.leadaxe.aibox.app.ClashModeGlobal
import com.leadaxe.aibox.app.ClashModeRule
import com.leadaxe.aibox.app.ClashModes
import com.leadaxe.aibox.app.MainActivity
import com.leadaxe.aibox.engine.vpn.BoxController
import com.leadaxe.aibox.engine.vpn.BoxEngine
import com.leadaxe.aibox.engine.vpn.BoxRuntimeSnapshot
import com.leadaxe.aibox.engine.vpn.BoxState

@Composable
fun HomeScreen(
    controller: BoxController,
    onRequestVpnConsent: (android.content.Intent) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    val appState by store.state.collectAsState()
    val engine = BoxEngine.shared()
    val boxState = engine?.state?.collectAsState()?.value ?: BoxState.Idle
    val runtime = engine?.runtime?.collectAsState()?.value ?: BoxRuntimeSnapshot()
    val activity = context as? MainActivity

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )

        // Betttbox-style hero control: one large target dominates the screen,
        // with the status and action label inside it. Everything else on the
        // tab is supporting information.
        PowerDial(
            state = boxState,
            onConnect = {
                val activityRef = activity
                val intent = controller.prepareVpn(activityRef ?: return@PowerDial)
                if (intent != null) {
                    onRequestVpnConsent(intent)
                } else {
                    controller.startFromBackground()
                }
            },
            onDisconnect = { controller.stop() },
        )

        Text(
            text = if (boxState is BoxState.Connected)
                stringResource(
                    R.string.home_via_node,
                    appState.outbounds.firstOrNull { it.tag == appState.selectedOutbound }
                        ?.name?.ifBlank { appState.selectedOutbound }
                        ?: appState.selectedOutbound.ifBlank { "proxy" },
                )
            else statusHeadline(boxState),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state = boxState, runtime = runtime)

        ClashModeRow(
            current = appState.clashMode,
            onChange = { mode -> store.update { it.copy(clashMode = mode) } },
        )

        NodePickerPreview(
            outbounds = appState.outbounds,
            selected = appState.selectedOutbound,
            onSelect = { tag -> store.update { it.copy(selectedOutbound = tag) } },
        )
    }
}

@Composable
private fun statusHeadline(state: BoxState): String = when (state) {
    BoxState.Idle -> stringResource(R.string.home_status_idle)
    BoxState.Starting -> stringResource(R.string.home_status_starting)
    is BoxState.Connected -> stringResource(R.string.home_status_connected)
    BoxState.Stopping -> stringResource(R.string.home_status_stopping)
    is BoxState.Error -> stringResource(R.string.home_status_error, state.message)
}

/**
 * Large circular connect/disconnect control. The ring colour tracks the
 * engine state so the current state reads at a glance without text.
 */
@Composable
private fun PowerDial(
    state: BoxState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is BoxState.Connected
    val busy = state is BoxState.Starting || state is BoxState.Stopping
    val ringColor = when (state) {
        is BoxState.Connected -> MaterialTheme.colorScheme.primary
        is BoxState.Error -> MaterialTheme.colorScheme.error
        BoxState.Starting, BoxState.Stopping -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val label = when {
        connected -> stringResource(R.string.home_disconnect)
        busy -> stringResource(R.string.home_working)
        else -> stringResource(R.string.home_connect)
    }
    Card(
        onClick = { if (connected) onDisconnect() else onConnect() },
        enabled = !busy,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.size(180.dp),
        colors = CardDefaults.cardColors(containerColor = ringColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = label,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: BoxState,
    runtime: BoxRuntimeSnapshot,
) {
    val statusLabel = when (state) {
        BoxState.Idle -> stringResource(R.string.home_status_idle)
        BoxState.Starting -> stringResource(R.string.home_status_starting)
        is BoxState.Connected -> stringResource(R.string.home_status_connected)
        BoxState.Stopping -> stringResource(R.string.home_status_stopping)
        is BoxState.Error -> stringResource(R.string.home_status_error, state.message)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(statusLabel) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TrafficColumn(
                    label = stringResource(R.string.home_upload),
                    bytes = runtime.uplinkBytes,
                    total = runtime.uplinkTotalBytes,
                )
                TrafficColumn(
                    label = stringResource(R.string.home_download),
                    bytes = runtime.downlinkBytes,
                    total = runtime.downlinkTotalBytes,
                )
                MetricColumn(label = stringResource(R.string.home_goroutines), value = runtime.goroutines.toString())
                MetricColumn(label = stringResource(R.string.home_memory), value = formatBytes(runtime.memoryBytes))
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
                            ClashModeRule -> stringResource(R.string.home_mode_rule)
                            ClashModeGlobal -> stringResource(R.string.home_mode_global)
                            ClashModeDirect -> stringResource(R.string.home_mode_direct)
                            else -> mode
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun NodePickerPreview(
    outbounds: List<com.leadaxe.aibox.app.OutboundProfile>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_select_outbound), style = MaterialTheme.typography.titleMedium)
            if (outbounds.isEmpty()) {
                Text(
                    stringResource(R.string.home_no_nodes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                outbounds.take(8).forEach { node ->
                    FilterChip(
                        selected = node.tag == selected,
                        onClick = { onSelect(node.tag) },
                        label = { Text(node.name.ifBlank { node.tag }) },
                    )
                }
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