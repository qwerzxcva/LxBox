package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.MuxProtocolSmux
import com.leadaxe.aibox.app.MuxProtocolYamux
import com.leadaxe.aibox.app.MuxProtocols

/**
 * Multiplex settings, shared by the Settings tab and the Groups tab so the
 * same knobs are reachable from where the user manages nodes (the reference
 * clients put mux next to the node list for exactly that reason).
 *
 * [onChange] is a receiver-style reducer — `onChange { copy(muxEnabled = true) }` —
 * matching the other editors in this package.
 */
@Composable
fun MuxSettingsContent(
    state: AppState,
    onChange: (AppState.() -> AppState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow(
            label = stringResource(R.string.settings_mux_enable),
            supporting = stringResource(R.string.settings_mux_enable_desc),
            checked = state.muxEnabled,
            onCheckedChange = { v -> onChange { copy(muxEnabled = v) } },
        )
        if (state.muxEnabled) {
            Text(
                stringResource(R.string.settings_mux_risk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!state.muxEnabled) return@Column
        SingleChoiceChips(
            label = stringResource(R.string.settings_mux_protocol),
            options = MuxProtocols,
            selected = state.muxProtocol,
            onSelect = { v -> onChange { copy(muxProtocol = v) } },
            display = {
                when (it) {
                    MuxProtocolSmux -> stringResource(R.string.settings_mux_protocol_smux)
                    MuxProtocolYamux -> stringResource(R.string.settings_mux_protocol_yamux)
                    else -> stringResource(R.string.settings_mux_protocol_h2mux)
                }
            },
        )
        Text(
            stringResource(R.string.settings_mux_protocol_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringField(
            label = stringResource(R.string.settings_mux_max_connections),
            value = state.muxMaxConnections.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { v ->
                onChange { copy(muxMaxConnections = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            placeholder = stringResource(R.string.settings_mux_default),
            supporting = stringResource(R.string.settings_mux_max_connections_desc),
        )
        StringField(
            label = stringResource(R.string.settings_mux_min_streams),
            value = state.muxMinStreams.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { v ->
                onChange { copy(muxMinStreams = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            placeholder = stringResource(R.string.settings_mux_default),
            supporting = stringResource(R.string.settings_mux_min_streams_desc),
        )
        StringField(
            label = stringResource(R.string.settings_mux_max_streams),
            value = state.muxMaxStreams.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { v ->
                onChange { copy(muxMaxStreams = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            placeholder = stringResource(R.string.settings_mux_default),
            supporting = stringResource(R.string.settings_mux_max_streams_desc),
        )
        SwitchRow(
            label = stringResource(R.string.settings_mux_padding),
            supporting = stringResource(R.string.settings_mux_padding_desc),
            checked = state.muxPadding,
            onCheckedChange = { v -> onChange { copy(muxPadding = v) } },
        )
        SwitchRow(
            label = stringResource(R.string.settings_mux_brutal),
            supporting = stringResource(R.string.settings_mux_brutal_desc),
            checked = state.muxBrutalEnabled,
            onCheckedChange = { v -> onChange { copy(muxBrutalEnabled = v) } },
        )
        if (state.muxBrutalEnabled) {
            StringField(
                label = stringResource(R.string.settings_mux_brutal_up),
                value = state.muxBrutalUpMbps.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { v ->
                    onChange { copy(muxBrutalUpMbps = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
                },
                placeholder = "10",
            )
            StringField(
                label = stringResource(R.string.settings_mux_brutal_down),
                value = state.muxBrutalDownMbps.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { v ->
                    onChange { copy(muxBrutalDownMbps = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
                },
                placeholder = "50",
            )
            Text(
                stringResource(R.string.settings_mux_brutal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.settings_mux_flow_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
