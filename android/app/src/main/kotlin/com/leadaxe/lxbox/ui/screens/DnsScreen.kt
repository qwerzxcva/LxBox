package com.leadaxe.lxbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.leadaxe.lxbox.app.DnsServerState
import com.leadaxe.lxbox.app.DnsServerTypes

@Composable
fun DnsScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as LxBoxApp).appStateStore }
    val state by store.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader("DNS servers (${state.dnsServers.size})")
        }
        items(state.dnsServers, key = { it.id }) { srv ->
            DnsRow(server = srv)
        }
        item {
            SectionHeader("Options")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hijack DNS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Route port 53 through sing-box; required for fake-IP / tun DNS.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.hijackDns,
                        onCheckedChange = { v -> store.update { it.copy(hijackDns = v) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsRow(server: DnsServerState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(server.name.ifBlank { server.tag }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${server.type} · ${server.address.ifBlank { "(local resolver)" }}", style = MaterialTheme.typography.bodySmall)
            Text(
                "detour: ${server.detour.ifBlank { "direct" }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}