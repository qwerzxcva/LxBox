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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.app.RouteRule

@Composable
fun RoutesScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as LxBoxApp).appStateStore }
    val state by store.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader("Rules (${state.routeRules.size})")
        }
        items(state.routeRules, key = { it.id }) { rule ->
            RuleRow(rule = rule)
        }
        item {
            SectionHeader("Rule sets (${state.ruleSets.size})")
        }
        items(state.ruleSets, key = { it.id }) { rs ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(rs.tag, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${rs.format} · ${rs.url}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: RouteRule) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = rule.name.ifBlank { rule.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(selected = rule.enabled, onClick = {}, label = { Text(if (rule.enabled) "on" else "off") })
            }
            val summary = when (rule.kind) {
                RouteRule.KindJson -> "JSON · ${rule.json.length} chars"
                RouteRule.RuleTypeLogical, RouteRule.RuleTypeDefault ->
                    listOfNotNull(
                        rule.domain.takeIf { it.isNotEmpty() }?.let { "domain: ${it.size}" },
                        rule.domainSuffix.takeIf { it.isNotEmpty() }?.let { "suffix: ${it.size}" },
                        rule.ipCidr.takeIf { it.isNotEmpty() }?.let { "cidr: ${it.size}" },
                        rule.ruleSet.takeIf { it.isNotEmpty() }?.let { "rule_set: ${it.size}" },
                    ).joinToString(" · ").ifBlank { "empty matcher" }
                else -> rule.kind
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Text(
                "${rule.action} → ${rule.outbound.ifBlank { "proxy" }}" +
                    if (rule.invert) " (invert)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}