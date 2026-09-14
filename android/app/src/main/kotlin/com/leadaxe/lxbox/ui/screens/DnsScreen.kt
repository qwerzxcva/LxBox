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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.R
import com.leadaxe.lxbox.app.AppState
import com.leadaxe.lxbox.app.DirectOutboundTag
import com.leadaxe.lxbox.app.DnsDetourDirect
import com.leadaxe.lxbox.app.DnsDetourProxy
import com.leadaxe.lxbox.app.DnsRule
import com.leadaxe.lxbox.app.DnsServerState
import com.leadaxe.lxbox.app.DnsServerTypes
import com.leadaxe.lxbox.app.DnsStrategies
import com.leadaxe.lxbox.app.ProxySelectorTag
import java.util.UUID

@Composable
fun DnsScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as LxBoxApp).appStateStore }
    val state by store.state.collectAsState()

    var editingServer: DnsServerState? by remember { mutableStateOf(null) }
    var creatingServer by remember { mutableStateOf(false) }
    var editingRule: DnsRule? by remember { mutableStateOf(null) }
    var creatingRule by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FakeIpSection(state = state, onChange = { store.update(it) })
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { creatingServer = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.dns_add_server))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.dns_section_servers, state.dnsServers.size)) }
        items(state.dnsServers, key = { it.id }) { srv ->
            DnsServerCard(
                server = srv,
                state = state,
                onEdit = { editingServer = srv },
                onDelete = {
                    store.update { st -> st.copy(dnsServers = st.dnsServers.filterNot { it.id == srv.id }) }
                },
                onToggleEnabled = { enabled ->
                    store.update { st ->
                        st.copy(dnsServers = st.dnsServers.map {
                            if (it.id == srv.id) it.copy(enabled = enabled) else it
                        })
                    }
                },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { creatingRule = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.dns_add_rule))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.dns_section_rules, state.dnsRules.size)) }
        items(state.dnsRules, key = { it.id }) { rule ->
            DnsRuleCard(
                rule = rule,
                state = state,
                onEdit = { editingRule = rule },
                onDelete = {
                    store.update { st -> st.copy(dnsRules = st.dnsRules.filterNot { it.id == rule.id }) }
                },
                onToggleEnabled = { enabled ->
                    store.update { st ->
                        st.copy(dnsRules = st.dnsRules.map {
                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                        })
                    }
                },
            )
        }

        item { SectionHeader(stringResource(R.string.dns_options)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow(
                        label = stringResource(R.string.dns_hijack),
                        supporting = stringResource(R.string.dns_hijack_desc),
                        checked = state.hijackDns,
                        onCheckedChange = { v -> store.update { it.copy(hijackDns = v) } },
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.dns_global_strategy),
                        options = DnsStrategies,
                        selected = state.dnsStrategy,
                        onSelect = { v -> store.update { it.copy(dnsStrategy = v) } },
                        display = { if (it.isBlank()) stringResource(R.string.dns_strategy_inherit) else it },
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.dns_final_server),
                        options = listOf("") + state.dnsServers.map { it.tag },
                        selected = state.finalDnsServer,
                        onSelect = { v -> store.update { it.copy(finalDnsServer = v) } },
                        display = {
                            if (it.isEmpty()) stringResource(R.string.dns_final_auto)
                            else state.dnsServers.firstOrNull { s -> s.tag == it }?.name?.ifBlank { it } ?: it
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.dns_independent_cache),
                        checked = state.dnsIndependentCache,
                        onCheckedChange = { v -> store.update { it.copy(dnsIndependentCache = v) } },
                    )
                }
            }
        }
    }

    if (creatingServer) {
        DnsServerEditor(
            initial = null,
            state = state,
            onDismiss = { creatingServer = false },
            onSave = { server ->
                store.update { st -> st.copy(dnsServers = st.dnsServers + server) }
                creatingServer = false
            },
        )
    }
    editingServer?.let { srv ->
        DnsServerEditor(
            initial = srv,
            state = state,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(dnsServers = st.dnsServers.map { if (it.id == updated.id) updated else it })
                }
                editingServer = null
            },
        )
    }
    if (creatingRule) {
        DnsRuleEditor(
            initial = null,
            state = state,
            onDismiss = { creatingRule = false },
            onSave = { rule ->
                store.update { st -> st.copy(dnsRules = st.dnsRules + rule) }
                creatingRule = false
            },
        )
    }
    editingRule?.let { rule ->
        DnsRuleEditor(
            initial = rule,
            state = state,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(dnsRules = st.dnsRules.map { if (it.id == updated.id) updated else it })
                }
                editingRule = null
            },
        )
    }
}

// ---------------------------------------------------------------- fake-ip

@Composable
private fun FakeIpSection(state: AppState, onChange: (AppState.() -> AppState) -> Unit) {
    SwitchRow(
        label = stringResource(R.string.fakeip_title),
        supporting = stringResource(R.string.fakeip_desc),
        checked = state.enableFakeIp,
        onCheckedChange = { v -> onChange { copy(enableFakeIp = v) } },
    )
    if (state.enableFakeIp) {
        StringField(
            label = stringResource(R.string.fakeip_inet4_range),
            value = state.fakeIpInet4Range,
            onValueChange = { v -> onChange { copy(fakeIpInet4Range = v) } },
            placeholder = stringResource(R.string.fakeip_default_range4),
        )
        if (state.enableIpv6) {
            StringField(
                label = stringResource(R.string.fakeip_inet6_range),
                value = state.fakeIpInet6Range,
                onValueChange = { v -> onChange { copy(fakeIpInet6Range = v) } },
                placeholder = stringResource(R.string.fakeip_default_range6),
            )
        }
        ListField(
            label = stringResource(R.string.fakeip_filter),
            values = state.fakeIpFilter,
            onValuesChange = { v -> onChange { copy(fakeIpFilter = v) } },
            placeholder = stringResource(R.string.fakeip_filter_hint),
        )
        if (state.fakeIpFilter.isNotEmpty()) {
            SwitchRow(
                label = stringResource(R.string.fakeip_filter_exclude),
                supporting = stringResource(R.string.fakeip_filter_exclude_desc),
                checked = state.fakeIpFilterExclude,
                onCheckedChange = { v -> onChange { copy(fakeIpFilterExclude = v) } },
            )
        }
    }
}

// ------------------------------------------------------------ dns server

@Composable
private fun DnsServerCard(
    server: DnsServerState,
    state: AppState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.name.ifBlank { server.tag },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${server.type} · ${server.address.ifBlank { "(system)" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.dns_detour) + ": " + detourLabel(server.detour, state),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilterChip(
                    selected = server.enabled,
                    onClick = { onToggleEnabled(!server.enabled) },
                    label = { Text(if (server.enabled) "on" else "off") },
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun detourLabel(detour: String, state: AppState): String = when (detour) {
    "", DirectOutboundTag -> stringResource(R.string.dns_detour_direct)
    ProxySelectorTag -> stringResource(R.string.dns_detour_proxy)
    else -> {
        val node = state.outbounds.firstOrNull { it.tag == detour }
        if (node != null) stringResource(R.string.dns_detour_node, node.name.ifBlank { node.tag })
        else detour
    }
}

@Composable
private fun DnsServerEditor(
    initial: DnsServerState?,
    state: AppState,
    onDismiss: () -> Unit,
    onSave: (DnsServerState) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: "https") }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var detour by remember { mutableStateOf(initial?.detour ?: DnsDetourProxy) }
    var strategy by remember { mutableStateOf(initial?.strategy.orEmpty()) }
    var domainResolver by remember { mutableStateOf(initial?.domainResolver.orEmpty()) }
    var clientSubnet by remember { mutableStateOf(initial?.clientSubnet.orEmpty()) }
    var tlsServerName by remember { mutableStateOf(initial?.tlsServerName.orEmpty()) }
    var insecure by remember { mutableStateOf(initial?.insecure ?: false) }
    var groupServers by remember { mutableStateOf(initial?.groupServers ?: emptyList()) }
    var groupMode by remember { mutableStateOf(initial?.groupMode ?: com.leadaxe.lxbox.app.DnsGroupStable) }
    var groupErrorTtl by remember { mutableStateOf(initial?.groupErrorTtl.orEmpty()) }
    var groupWinTtl by remember { mutableStateOf(initial?.groupWinTtl.orEmpty()) }

    val detourOptions = remember(state.outbounds) {
        listOf(DnsDetourDirect, DnsDetourProxy) + state.outbounds.map { it.tag }
    }
    // Candidate members for a group: every other server, by tag.
    val groupCandidates = remember(state.dnsServers, initial) {
        state.dnsServers.filter { it.id != initial?.id }.map { it.tag }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.dns_new_server else R.string.dns_edit_server,
                ),
            )
        },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.dns_name),
                    value = name,
                    onValueChange = { name = it },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.dns_type),
                    options = DnsServerTypes,
                    selected = type,
                    onSelect = { type = it },
                    display = { t -> if (t == "group") stringResource(R.string.dns_type_group) else t },
                )
                if (type != "local" && type != "direct" && type != "group") {
                    StringField(
                        label = stringResource(R.string.dns_address),
                        value = address,
                        onValueChange = { address = it },
                        placeholder = "1.1.1.1",
                    )
                }
                if (type == "group") {
                    Text(
                        stringResource(R.string.dns_group_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MultiChoiceChips(
                        label = stringResource(R.string.dns_group_members),
                        options = groupCandidates,
                        selected = groupServers,
                        onToggle = { tag ->
                            groupServers = if (tag in groupServers) groupServers - tag else groupServers + tag
                        },
                        display = { tag ->
                            state.dnsServers.firstOrNull { it.tag == tag }?.name?.ifBlank { tag } ?: tag
                        },
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.dns_group_mode),
                        options = com.leadaxe.lxbox.app.DnsGroupModes,
                        selected = groupMode,
                        onSelect = { groupMode = it },
                        display = {
                            when (it) {
                                com.leadaxe.lxbox.app.DnsGroupFastest -> stringResource(R.string.dns_group_fastest)
                                com.leadaxe.lxbox.app.DnsGroupParallel -> stringResource(R.string.dns_group_parallel)
                                else -> stringResource(R.string.dns_group_stable)
                            }
                        },
                    )
                    StringField(
                        label = stringResource(R.string.dns_group_error_ttl),
                        value = groupErrorTtl,
                        onValueChange = { groupErrorTtl = it },
                        placeholder = stringResource(R.string.dns_group_error_ttl_hint),
                    )
                    if (groupMode == com.leadaxe.lxbox.app.DnsGroupFastest) {
                        StringField(
                            label = stringResource(R.string.dns_group_win_ttl),
                            value = groupWinTtl,
                            onValueChange = { groupWinTtl = it },
                            placeholder = stringResource(R.string.dns_group_win_ttl_hint),
                        )
                    }
                } else {
                    DetourPicker(
                        label = stringResource(R.string.dns_detour),
                        options = detourOptions,
                        selected = detour,
                        state = state,
                        onSelect = { detour = it },
                    )
                }
                SingleChoiceChips(
                    label = stringResource(R.string.dns_strategy),
                    options = DnsStrategies,
                    selected = strategy,
                    onSelect = { strategy = it },
                    display = { if (it.isBlank()) stringResource(R.string.dns_strategy_inherit) else it },
                )
                ResolverPicker(
                    label = stringResource(R.string.dns_domain_resolver),
                    servers = state.dnsServers,
                    selected = domainResolver,
                    onSelect = { domainResolver = it },
                )
                if (type == "tls" || type == "https" || type == "quic" || type == "h3") {
                    StringField(
                        label = stringResource(R.string.dns_tls_server_name),
                        value = tlsServerName,
                        onValueChange = { tlsServerName = it },
                        placeholder = "dns.example.com",
                    )
                    SwitchRow(
                        label = stringResource(R.string.dns_insecure),
                        checked = insecure,
                        onCheckedChange = { insecure = it },
                    )
                }
                StringField(
                    label = stringResource(R.string.dns_client_subnet),
                    value = clientSubnet,
                    onValueChange = { clientSubnet = it },
                    placeholder = "1.2.3.0/24",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: DnsServerState(id = UUID.randomUUID().toString(), name = "")).copy(
                            name = name.ifBlank { address.ifBlank { type } },
                            type = type,
                            address = address,
                            detour = detour,
                            strategy = strategy,
                            domainResolver = domainResolver,
                            clientSubnet = clientSubnet,
                            tlsServerName = tlsServerName,
                            insecure = insecure,
                            groupServers = groupServers,
                            groupMode = groupMode,
                            groupErrorTtl = groupErrorTtl,
                            groupWinTtl = groupWinTtl,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DetourPicker(
    label: String,
    options: List<String>,
    selected: String,
    state: AppState,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(detourLabel(option, state), maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun ResolverPicker(
    label: String,
    servers: List<DnsServerState>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf("") + servers.map { it.tag }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { tag ->
                FilterChip(
                    selected = tag == selected,
                    onClick = { onSelect(tag) },
                    label = {
                        Text(
                            if (tag.isEmpty()) stringResource(R.string.dns_domain_resolver_none)
                            else servers.firstOrNull { it.tag == tag }?.name?.ifBlank { tag } ?: tag,
                        )
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------- dns rule

@Composable
private fun DnsRuleCard(
    rule: DnsRule,
    state: AppState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name.ifBlank { rule.id.take(8) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = rule.enabled,
                    onClick = { onToggleEnabled(!rule.enabled) },
                    label = { Text(if (rule.enabled) "on" else "off") },
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                }
            }
            val summary = buildList {
                if (rule.domain.isNotEmpty()) add("domain ${rule.domain.size}")
                if (rule.domainSuffix.isNotEmpty()) add("suffix ${rule.domainSuffix.size}")
                if (rule.domainKeyword.isNotEmpty()) add("keyword ${rule.domainKeyword.size}")
                if (rule.ruleSet.isNotEmpty()) add("rule_set ${rule.ruleSet.size}")
                if (rule.queryType.isNotEmpty()) add("qtype ${rule.queryType.size}")
                if (rule.packageName.isNotEmpty()) add("package ${rule.packageName.size}")
                if (rule.clashMode.isNotEmpty()) add("mode ${rule.clashMode.size}")
            }.joinToString(" · ").ifBlank { stringResource(R.string.routes_empty_matcher) }
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Text(
                "${rule.action} → ${rule.server.ifBlank { stringResource(R.string.dns_target_system) }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DnsRuleEditor(
    initial: DnsRule?,
    state: AppState,
    onDismiss: () -> Unit,
    onSave: (DnsRule) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain ?: emptyList()) }
    var domainSuffix by remember { mutableStateOf(initial?.domainSuffix ?: emptyList()) }
    var domainKeyword by remember { mutableStateOf(initial?.domainKeyword ?: emptyList()) }
    var ruleSet by remember { mutableStateOf(initial?.ruleSet ?: emptyList()) }
    var queryType by remember { mutableStateOf(initial?.queryType ?: emptyList()) }
    var packageName by remember { mutableStateOf(initial?.packageName ?: emptyList()) }
    var clashMode by remember { mutableStateOf(initial?.clashMode ?: emptyList()) }
    var server by remember { mutableStateOf(initial?.server.orEmpty()) }
    var invert by remember { mutableStateOf(initial?.invert ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    val serverOptions = remember(state.dnsServers) { state.dnsServers.map { it.tag } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.dns_new_rule else R.string.dns_edit_rule)) },
        text = {
            FormBody {
                    StringField(
                        label = stringResource(R.string.dns_name),
                        value = name,
                        onValueChange = { name = it },
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain_suffix),
                        values = domainSuffix,
                        onValuesChange = { domainSuffix = it },
                        placeholder = "google.com, openai.com",
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain),
                        values = domain,
                        onValuesChange = { domain = it },
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain_keyword),
                        values = domainKeyword,
                        onValuesChange = { domainKeyword = it },
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_rule_set),
                        values = ruleSet,
                        onValuesChange = { ruleSet = it },
                    )
                    ListField(
                        label = stringResource(R.string.dns_query_type),
                        values = queryType,
                        onValuesChange = { queryType = it },
                        placeholder = "A, AAAA",
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_package),
                        values = packageName,
                        onValuesChange = { packageName = it },
                    )
                    MultiChoiceChips(
                        label = stringResource(R.string.routes_logical_mode),
                        options = com.leadaxe.lxbox.app.ClashModes,
                        selected = clashMode,
                        onToggle = { m ->
                            clashMode = if (m in clashMode) clashMode - m else clashMode + m
                        },
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.dns_target_server),
                        options = listOf("") + serverOptions,
                        selected = server,
                        onSelect = { server = it },
                        display = {
                            if (it.isEmpty()) stringResource(R.string.dns_target_system)
                            else state.dnsServers.firstOrNull { s -> s.tag == it }?.name?.ifBlank { it } ?: it
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.routes_invert),
                        checked = invert,
                        onCheckedChange = { invert = it },
                    )
                    SwitchRow(
                        label = stringResource(R.string.routes_enabled),
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: DnsRule(id = UUID.randomUUID().toString())).copy(
                            name = name,
                            domain = domain,
                            domainSuffix = domainSuffix,
                            domainKeyword = domainKeyword,
                            ruleSet = ruleSet,
                            queryType = queryType,
                            packageName = packageName,
                            clashMode = clashMode,
                            server = server,
                            invert = invert,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}