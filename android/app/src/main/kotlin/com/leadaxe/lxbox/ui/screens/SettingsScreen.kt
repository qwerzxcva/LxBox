package com.leadaxe.lxbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.R
import com.leadaxe.lxbox.app.ColorModeDark
import com.leadaxe.lxbox.app.ColorModeLight
import com.leadaxe.lxbox.app.ColorModeSystem
import com.leadaxe.lxbox.app.SingBoxLogLevels
import com.leadaxe.lxbox.app.SnifferProtocolOptions

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as LxBoxApp).appStateStore }
    val state by store.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.settings_color_mode),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = stringResource(R.string.settings_color_system),
                            selected = state.colorMode == ColorModeSystem,
                        ) { store.update { it.copy(colorMode = ColorModeSystem) } }
                        Chip(
                            label = stringResource(R.string.settings_color_light),
                            selected = state.colorMode == ColorModeLight,
                        ) { store.update { it.copy(colorMode = ColorModeLight) } }
                        Chip(
                            label = stringResource(R.string.settings_color_dark),
                            selected = state.colorMode == ColorModeDark,
                        ) { store.update { it.copy(colorMode = ColorModeDark) } }
                    }
                    Text(
                        stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = stringResource(R.string.settings_language_system),
                            selected = state.language.isEmpty(),
                        ) { store.update { it.copy(language = "") } }
                        Chip(
                            label = stringResource(R.string.settings_language_en),
                            selected = state.language == "en",
                        ) { store.update { it.copy(language = "en") } }
                        Chip(
                            label = stringResource(R.string.settings_language_zh),
                            selected = state.language == "zh",
                        ) { store.update { it.copy(language = "zh") } }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_tunnel)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow(
                        label = stringResource(R.string.settings_ipv6),
                        supporting = stringResource(R.string.settings_ipv6_desc),
                        checked = state.enableIpv6,
                        onCheckedChange = { v -> store.update { it.copy(enableIpv6 = v) } },
                    )
                    Text(
                        stringResource(R.string.settings_mtu, state.tunMtu),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = state.tunMtu.toFloat(),
                        onValueChange = { v -> store.update { it.copy(tunMtu = v.toInt()) } },
                        valueRange = 1280f..9000f,
                        steps = 30,
                    )
                    Text(
                        stringResource(R.string.settings_tun_addresses),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StringField(
                        label = stringResource(R.string.settings_tun_ipv4),
                        value = state.tunInet4Address,
                        onValueChange = { v -> store.update { it.copy(tunInet4Address = v) } },
                        placeholder = "172.19.0.1/30",
                    )
                    if (state.enableIpv6) {
                        StringField(
                            label = stringResource(R.string.settings_tun_ipv6),
                            value = state.tunInet6Address,
                            onValueChange = { v -> store.update { it.copy(tunInet6Address = v) } },
                            placeholder = "fdfe:dcba:9876::1/126",
                        )
                    }
                    ListField(
                        label = stringResource(R.string.settings_tun_dns),
                        values = state.tunDnsAddresses,
                        onValuesChange = { v -> store.update { it.copy(tunDnsAddresses = v) } },
                        placeholder = stringResource(R.string.settings_tun_dns_hint),
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_sniffer)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow(
                        label = stringResource(R.string.settings_enable_sniffer),
                        supporting = stringResource(R.string.settings_enable_sniffer_desc),
                        checked = state.enableSniffer,
                        onCheckedChange = { v -> store.update { it.copy(enableSniffer = v) } },
                    )
                    MultiChoiceChips(
                        label = stringResource(R.string.settings_sniffer_protocols),
                        options = SnifferProtocolOptions,
                        selected = state.snifferProtocols,
                        onToggle = { proto ->
                            store.update { st ->
                                val next = if (proto in st.snifferProtocols)
                                    st.snifferProtocols - proto
                                else
                                    st.snifferProtocols + proto
                                st.copy(snifferProtocols = next)
                            }
                        },
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_logging)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceChips(
                        label = stringResource(R.string.settings_log_level),
                        options = SingBoxLogLevels,
                        selected = state.logLevel,
                        onSelect = { v -> store.update { it.copy(logLevel = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_append_log),
                        supporting = stringResource(R.string.settings_append_log_desc),
                        checked = state.appendLogToFile,
                        onCheckedChange = { v -> store.update { it.copy(appendLogToFile = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_cache_file),
                        supporting = stringResource(R.string.settings_cache_file_desc),
                        checked = state.enableCacheFile,
                        onCheckedChange = { v -> store.update { it.copy(enableCacheFile = v) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}