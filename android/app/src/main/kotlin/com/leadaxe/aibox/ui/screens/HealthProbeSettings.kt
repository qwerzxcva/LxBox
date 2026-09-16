package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.ProbeIntervalOptions

/**
 * Node health probing (leastPing-style scheduled url-test): a timed pass
 * over every group while the tunnel is up, so the delay column stays fresh
 * and auto groups re-select on degradation. The interval is user-chosen —
 * probing every node costs battery and traffic, so "off" is a first-class
 * option and the description says so.
 *
 * Also hosts the TCP keep-alive idle knob: same battery/latency trade-off,
 * applied per-outbound by the compiler (tcp_keep_alive dialer option).
 */
@Composable
fun HealthProbeSettingsContent(
    state: AppState,
    onChange: (AppState.() -> AppState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceChips(
            label = stringResource(R.string.probe_interval),
            options = ProbeIntervalOptions.map { it.first.toString() },
            selected = state.healthCheckIntervalMinutes.toString(),
            onSelect = { v ->
                onChange { copy(healthCheckIntervalMinutes = v.toIntOrNull() ?: 0) }
            },
            display = { value ->
                ProbeIntervalOptions
                    .firstOrNull { it.first.toString() == value }
                    ?.second
                    ?.let { stringResource(it) }
                    ?: value
            },
        )
        Text(
            stringResource(R.string.probe_interval_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringField(
            label = stringResource(R.string.probe_timeout),
            value = state.speedTestTimeoutMs.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { v ->
                onChange { copy(speedTestTimeoutMs = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            placeholder = "5000",
            supporting = stringResource(R.string.probe_timeout_desc),
        )
        StringField(
            label = stringResource(R.string.probe_keepalive),
            value = state.tcpKeepAliveIdleSeconds.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { v ->
                onChange { copy(tcpKeepAliveIdleSeconds = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            placeholder = stringResource(R.string.probe_keepalive_default),
            supporting = stringResource(R.string.probe_keepalive_desc),
        )
    }
}
