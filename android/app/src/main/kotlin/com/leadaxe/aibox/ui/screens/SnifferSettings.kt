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
import com.leadaxe.aibox.app.SnifferProtocolOptions

/**
 * Sniffer configuration, shared by the Routes tab (where the sniff rule
 * actually runs, first of the built-ins) and the Settings tab.
 */
@Composable
fun SnifferSettingsContent(
    state: AppState,
    onChange: (AppState.() -> AppState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow(
            label = stringResource(R.string.settings_enable_sniffer),
            supporting = stringResource(R.string.settings_enable_sniffer_desc),
            checked = state.enableSniffer,
            onCheckedChange = { v -> onChange { copy(enableSniffer = v) } },
        )
        MultiChoiceChips(
            label = stringResource(R.string.settings_sniffer_protocols),
            options = SnifferProtocolOptions,
            selected = state.snifferProtocols,
            onToggle = { proto ->
                val reducer: (AppState) -> AppState = { st ->
                    val next = if (proto in st.snifferProtocols)
                        st.snifferProtocols - proto
                    else
                        st.snifferProtocols + proto
                    st.copy(snifferProtocols = next)
                }
                onChange(reducer)
            },
        )
        Text(
            stringResource(R.string.routes_builtin_sniff_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
