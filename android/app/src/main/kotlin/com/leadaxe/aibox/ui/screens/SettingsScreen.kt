package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.ColorModeDark
import com.leadaxe.aibox.app.ColorModeLight
import com.leadaxe.aibox.app.ColorModeSystem
import com.leadaxe.aibox.app.SingBoxLogLevels
import com.leadaxe.aibox.app.SnifferProtocolOptions

/**
 * Settings tab. Every group is a collapsible card: the tab is long enough
 * that a flat list buries the tunnel options under appearance and logging
 * noise, so sections start collapsed and open on tap.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    val state by store.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(stringResource(R.string.settings_section_appearance), initiallyExpanded = true) {
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

        item {
            SettingsSection(stringResource(R.string.settings_section_tunnel)) {
                SwitchRow(
                    label = stringResource(R.string.settings_ipv6),
                    supporting = stringResource(R.string.settings_ipv6_desc),
                    checked = state.enableIpv6,
                    onCheckedChange = { v -> store.update { it.copy(enableIpv6 = v) } },
                )
                if (state.enableIpv6) {
                    SingleChoiceChips(
                        label = stringResource(R.string.settings_ip_family),
                        options = listOf("prefer_ipv6", "prefer_ipv4", "ipv6_only", "ipv4_only"),
                        selected = state.ipFamilyPolicy,
                        onSelect = { v -> store.update { it.copy(ipFamilyPolicy = v) } },
                        display = {
                            when (it) {
                                "prefer_ipv4" -> stringResource(R.string.settings_prefer_ipv4)
                                "ipv6_only" -> stringResource(R.string.settings_ipv6_only)
                                "ipv4_only" -> stringResource(R.string.settings_ipv4_only)
                                else -> stringResource(R.string.settings_prefer_ipv6)
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.settings_ip_family_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.ipv6FallbackActive) {
                        Text(
                            stringResource(R.string.settings_ipv6_fallback_active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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
                SwitchRow(
                    label = stringResource(R.string.settings_udp_over_tcp),
                    supporting = stringResource(R.string.settings_udp_over_tcp_desc),
                    checked = state.udpOverTcp,
                    onCheckedChange = { v -> store.update { it.copy(udpOverTcp = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_hev_tun),
                    supporting = stringResource(R.string.settings_hev_tun_desc),
                    checked = state.hevTunMode,
                    onCheckedChange = { v -> store.update { it.copy(hevTunMode = v) } },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.settings_tls_fragment),
                    options = listOf("none", "record", "packet"),
                    selected = state.tlsFragmentMode,
                    onSelect = { v -> store.update { it.copy(tlsFragmentMode = v) } },
                    display = {
                        when (it) {
                            "record" -> stringResource(R.string.settings_tls_fragment_record)
                            "packet" -> stringResource(R.string.settings_tls_fragment_packet)
                            else -> stringResource(R.string.settings_tls_fragment_none)
                        }
                    },
                )
                Text(
                    stringResource(R.string.settings_tls_fragment_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_ntp),
                    supporting = stringResource(R.string.settings_ntp_desc),
                    checked = state.enableNtp,
                    onCheckedChange = { v -> store.update { it.copy(enableNtp = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_local_socks5),
                    supporting = stringResource(R.string.settings_local_socks5_desc),
                    checked = state.enableLocalSocks5,
                    onCheckedChange = { v -> store.update { it.copy(enableLocalSocks5 = v) } },
                )
                if (state.enableLocalSocks5) {
                    StringField(
                        label = stringResource(R.string.settings_local_socks5_port),
                        value = state.localSocks5Port.toString(),
                        onValueChange = { v ->
                            store.update { st -> st.copy(localSocks5Port = v.filter { c -> c.isDigit() }.toIntOrNull() ?: st.localSocks5Port) }
                        },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.settings_local_http),
                    supporting = stringResource(R.string.settings_local_http_desc),
                    checked = state.enableLocalHttp,
                    onCheckedChange = { v -> store.update { it.copy(enableLocalHttp = v) } },
                )
                if (state.enableLocalHttp) {
                    StringField(
                        label = stringResource(R.string.settings_local_http_port),
                        value = state.localHttpPort.toString(),
                        onValueChange = { v ->
                            store.update { st -> st.copy(localHttpPort = v.filter { c -> c.isDigit() }.toIntOrNull() ?: st.localHttpPort) }
                        },
                    )
                }
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
                Text(
                    stringResource(R.string.settings_restart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_sniffer)) {
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

        item {
            SettingsSection(stringResource(R.string.settings_section_logging)) {
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

        item {
            SettingsSection(stringResource(R.string.settings_section_power)) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                Text(
                    text = stringResource(
                        if (ignoring) R.string.settings_battery_ok
                        else R.string.settings_battery_prompt,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!ignoring) {
                    FilledTonalButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:${ctx.packageName}"),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_battery_action))
                    }
                }
                Text(
                    stringResource(R.string.settings_battery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Collapsible settings group. The header row keeps the whole card tappable
 * so a section opens from anywhere on the title bar, not just the chevron.
 */
@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = title,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}