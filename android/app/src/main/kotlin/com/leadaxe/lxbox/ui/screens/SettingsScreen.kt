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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
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
        item { SectionHeader("Appearance") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Color mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        ColorChip("System", state.colorMode == ColorModeSystem) { store.update { it.copy(colorMode = ColorModeSystem) } }
                        ColorChip("Light", state.colorMode == ColorModeLight) { store.update { it.copy(colorMode = ColorModeLight) } }
                        ColorChip("Dark", state.colorMode == ColorModeDark) { store.update { it.copy(colorMode = ColorModeDark) } }
                    }
                }
            }
        }

        item { SectionHeader("Tunnel") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("IPv6", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Enable IPv6 routing through the tun interface.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.enableIpv6, onCheckedChange = { v -> store.update { it.copy(enableIpv6 = v) } })
                    }
                    Text("MTU: ${state.tunMtu}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = state.tunMtu.toFloat(),
                        onValueChange = { v -> store.update { it.copy(tunMtu = v.toInt()) } },
                        valueRange = 1280f..9000f,
                        steps = 30,
                    )
                }
            }
        }

        item { SectionHeader("Sniffer") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable sniffer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Detect protocol from traffic for finer rule matching.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.enableSniffer, onCheckedChange = { v -> store.update { it.copy(enableSniffer = v) } })
                    }
                    Text("Protocols", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SnifferProtocolOptions.forEach { proto ->
                            FilterChip(
                                selected = proto in state.snifferProtocols,
                                onClick = {
                                    store.update { st ->
                                        val next = if (proto in st.snifferProtocols)
                                            st.snifferProtocols - proto
                                        else
                                            st.snifferProtocols + proto
                                        st.copy(snifferProtocols = next)
                                    }
                                },
                                label = { Text(proto) },
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("Logging") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Log level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SingBoxLogLevels.forEach { level ->
                            FilterChip(
                                selected = level == state.logLevel,
                                onClick = { store.update { it.copy(logLevel = level) } },
                                label = { Text(level) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Append logs to file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Used for crash reports and post-mortem diagnostics.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.appendLogToFile, onCheckedChange = { v -> store.update { it.copy(appendLogToFile = v) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}