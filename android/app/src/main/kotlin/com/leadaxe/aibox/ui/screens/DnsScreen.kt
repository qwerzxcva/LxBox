package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.ClashModeDirect
import com.leadaxe.aibox.app.ClashModeGlobal
import com.leadaxe.aibox.app.ClashModeRule
import com.leadaxe.aibox.app.ClashModes
import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.DnsDetourDirect
import com.leadaxe.aibox.app.DnsDetourProxy
import com.leadaxe.aibox.app.DnsFinalDirect
import com.leadaxe.aibox.app.DnsFinalProxy
import com.leadaxe.aibox.app.DnsRule
import com.leadaxe.aibox.app.DnsRuleActionReject
import com.leadaxe.aibox.app.DnsRuleActionRoute
import com.leadaxe.aibox.app.DnsRuleActionRouteOptions
import com.leadaxe.aibox.app.DnsRuleActions
import com.leadaxe.aibox.app.DnsServerState
import com.leadaxe.aibox.app.DnsServerTypes
import com.leadaxe.aibox.app.DnsStrategies
import com.leadaxe.aibox.app.ProxySelectorTag
import java.util.UUID

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun DnsScreen(onEditorLock: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AIBoxApp
    val store = remember { app.appStateStore }
    val state by store.state.collectAsState()
    // Live tunnel state from the :vpn process (the persistent proxyRunning
    // flag is only for boot autostart decisions).
    val relay = remember { app.vpnRelay }
    val boxState by relay.state.collectAsState()

    // Server create/edit are inline expanding forms (no popup dialog):
    // showAddForm renders a fresh form card under the section header;
    // editingServerId swaps that server's card for the form in place.
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddForm by rememberSaveable { mutableStateOf(false) }
    // Collapsed-by-default advanced DNS sections.
    var strategyExpanded by rememberSaveable { mutableStateOf(false) }
    var fallbackExpanded by rememberSaveable { mutableStateOf(false) }
    var editingRule: DnsRule? by rememberSaveable { mutableStateOf(null) }
    var creatingRule by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(creatingRule, editingRule) {
        onEditorLock(creatingRule || editingRule != null)
        onDispose { onEditorLock(false) }
    }

    // Rule editor as a second-level page (replaces the list while open).
    // The dialog version clipped long forms and could not scroll properly.
    if (creatingRule || editingRule != null) {
        DnsRuleEditorPage(
            initial = editingRule,
            state = state,
            onDismiss = {
                creatingRule = false
                editingRule = null
            },
            onSave = { updated ->
                val (adds, tags) = com.leadaxe.aibox.ui.screens.materializeRuleSetTags(state.ruleSets, updated.ruleSet)
                val finalRule = updated.copy(ruleSet = tags)
                store.update { st ->
                    if (creatingRule) {
                        st.copy(dnsRules = st.dnsRules + finalRule, ruleSets = st.ruleSets + adds)
                    } else {
                        st.copy(
                            dnsRules = st.dnsRules.map { if (it.id == finalRule.id) finalRule else it },
                            ruleSets = st.ruleSets + adds,
                        )
                    }
                }
                creatingRule = false
                editingRule = null
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title first, then the add action: the page reads as "DNS servers
        // — add one — the list", not "an action floating above a title".
        item { SectionHeader(stringResource(R.string.dns_section_servers, state.dnsServers.size)) }
        // Add server: an inline expanding form, not a popup. The trigger is
        // a quiet dashed-style row; tapping it unfolds the full editor card
        // in the list where the new entry will live.
        item {
            if (showAddForm) {
                DnsServerFormCard(
                    title = stringResource(R.string.dns_new_server),
                    initial = null,
                    state = state,
                    onCancel = { showAddForm = false },
                    onSave = { server ->
                        store.update { st -> st.copy(dnsServers = st.dnsServers + server) }
                        showAddForm = false
                    },
                )
            } else {
                AddServerRow(onClick = {
                    editingServerId = null
                    showAddForm = true
                })
            }
        }
        items(state.dnsServers, key = { it.id }) { srv ->
            if (editingServerId == srv.id) {
                DnsServerFormCard(
                    title = stringResource(R.string.dns_edit_server),
                    initial = srv,
                    state = state,
                    onCancel = { editingServerId = null },
                    onSave = { updated ->
                        store.update { st ->
                            st.copy(dnsServers = st.dnsServers.map { if (it.id == updated.id) updated else it })
                        }
                        editingServerId = null
                    },
                )
            } else {
                DnsServerCard(
                    server = srv,
                    state = state,
                    onEdit = {
                        showAddForm = false
                        editingServerId = srv.id
                    },
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
        }

        // Rules live in a second-level page: the editor is a full-screen
        // form (the dialog version clipped long forms), and the "add rule"
        // action belongs here with the list it populates.
        item { SectionHeader(stringResource(R.string.dns_section_rules, state.dnsRules.size + 1)) }
        // Fake-IP lives in the rule list: it is a routing decision like any
        // other, and hiding its settings in a separate card above the list
        // made the page read as two unrelated things. One compact row with a
        // pencil that expands in place keeps the page scannable.
        item {
            FakeIpRow(state = state, onChange = { store.update(it) })
        }
        itemsIndexedWithActions(
            items = state.dnsRules,
            onMove = { from, to ->
                store.update { st ->
                    val list = st.dnsRules.toMutableList()
                    if (to in list.indices) {
                        val item = list.removeAt(from)
                        list.add(to, item)
                    }
                    st.copy(dnsRules = list)
                }
            },
        ) { _, rule ->
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
        item {
            FilledTonalButton(onClick = { creatingRule = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.dns_add_rule))
            }
        }

        item { SectionHeader(stringResource(R.string.dns_options)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var dnsCacheMessage by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dns_clear_cache),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (dnsCacheMessage.isNotBlank()) {
                                Text(
                                    dnsCacheMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // The engine lives in the :vpn process — the request
                        // goes over as a service intent, same as reload.
                        FilledTonalButton(
                            onClick = {
                                val offline = boxState !is com.leadaxe.aibox.engine.vpn.BoxState.Connected
                                if (offline) {
                                    dnsCacheMessage = context.getString(R.string.dns_clear_cache_offline)
                                } else {
                                    dnsCacheMessage = context.getString(R.string.dns_clear_cache_done)
                                    context.startService(
                                        android.content.Intent(
                                            context,
                                            com.leadaxe.aibox.engine.vpn.AIVpnService::class.java,
                                        ).setAction(com.leadaxe.aibox.engine.vpn.AIVpnService.ACTION_CLEAR_DNS_CACHE),
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.dns_clear_cache_action))
                        }
                    }
                    SwitchRow(
                        label = stringResource(R.string.dns_hijack),
                        supporting = stringResource(R.string.dns_hijack_desc),
                        checked = state.hijackDns,
                        onCheckedChange = { v -> store.update { it.copy(hijackDns = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.dns_independent_cache),
                        checked = state.dnsIndependentCache,
                        onCheckedChange = { v -> store.update { it.copy(dnsIndependentCache = v) } },
                    )
                }
            }
        }

        // Global strategy and the fallback resolver are advanced knobs:
        // both are collapsed by default with the current choice shown in
        // the subtitle, so the everyday list stays short (ClashFest-style
        // quiet page, expand on demand).
        // Plain computation (a handful of strings) — remember() is not
        // available in the LazyListScope builder block.
        val DnsFinalReject = "final:reject"
        val finalOptions = listOf("", DnsFinalProxy, DnsFinalDirect, DnsFinalReject) +
            state.dnsServers.map { it.tag }
        item {
            CollapsibleSection(
                title = stringResource(R.string.dns_global_strategy),
                expanded = strategyExpanded,
                onToggle = { strategyExpanded = !strategyExpanded },
                subtitle = if (state.dnsStrategy.isBlank()) {
                    stringResource(R.string.dns_strategy_inherit)
                } else {
                    state.dnsStrategy
                },
            ) {
                SingleChoiceChips(
                    label = stringResource(R.string.dns_global_strategy),
                    options = DnsStrategies,
                    selected = state.dnsStrategy,
                    onSelect = { v -> store.update { it.copy(dnsStrategy = v) } },
                    display = { if (it.isBlank()) stringResource(R.string.dns_strategy_inherit) else it },
                )
            }
        }
        item {
            val overrideCount = state.finalDnsServerByExit.values.count { it.isNotBlank() }
            CollapsibleSection(
                title = stringResource(R.string.dns_final_server),
                expanded = fallbackExpanded,
                onToggle = { fallbackExpanded = !fallbackExpanded },
                subtitle = if (overrideCount == 0) {
                    FinalOptionLabel(state.finalDnsServer, state)
                } else {
                    FinalOptionLabel(state.finalDnsServer, state) + " · " +
                        stringResource(R.string.dns_final_overrides, overrideCount)
                },
            ) {
                // Per-mode picks: Rule / Global / Direct can each pin their
                // own fallback (e.g. Global resolves through the proxy exit
                // while Rule follows the rules' servers). Empty means the
                // global fallback below applies.
                listOf("proxy", "direct").forEach { exit ->
                    val exitValue = state.finalDnsServerByExit[exit].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (exit == "proxy") stringResource(R.string.dns_final_exit_proxy)
                            else stringResource(R.string.dns_final_exit_direct),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.28f),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(0.72f),
                        ) {
                            finalOptions.forEach { option ->
                                FilterChip(
                                    selected = option == exitValue,
                                    onClick = {
                                        store.update { st ->
                                            val map = st.finalDnsServerByExit.toMutableMap()
                                            if (option.isBlank()) map.remove(exit) else map[exit] = option
                                            st.copy(finalDnsServerByExit = map)
                                        }
                                    },
                                    label = {
                                        Text(
                                            when (option) {
                                                "" -> stringResource(R.string.dns_final_mode_default)
                                                else -> FinalOptionLabel(option, state)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                // Global fallback: used by modes without their own pick.
                SingleChoiceChips(
                    label = stringResource(R.string.dns_final_global_fallback),
                    options = finalOptions,
                    selected = state.finalDnsServer,
                    onSelect = { v -> store.update { it.copy(finalDnsServer = v) } },
                    display = { FinalOptionLabel(it, state) },
                )
                if (state.finalDnsServer == DnsFinalReject) {
                    Text(
                        stringResource(R.string.dns_final_reject_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- fake-ip

/**
 * Fake-IP as a rule-list row: title + state on one line, a pencil that
 * expands the pool settings inline. The row is deliberately the same shape
 * as a DNS rule card so the list reads as one table.
 */
@Composable
private fun FakeIpRow(state: AppState, onChange: (AppState.() -> AppState) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.fakeip_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.enableFakeIp) {
                            val v4 = state.fakeIpInet4Range.ifBlank { "198.18.0.0/15" }
                            val v6 = state.fakeIpInet6Range.ifBlank { "fc00::/18" }
                            "$v4 · $v6" + if (state.fakeIpFilter.isNotEmpty()) {
                                " · ${state.fakeIpFilter.size} filter"
                            } else ""
                        } else {
                            stringResource(R.string.fakeip_disabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = state.enableFakeIp,
                    onClick = { onChange { copy(enableFakeIp = !state.enableFakeIp) } },
                    label = { Text(if (state.enableFakeIp) "on" else "off") },
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.fakeip_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Scope: which traffic gets fake addresses. The auto
                    // exclusion list is built by the compiler from the
                    // route rules targeting the opposite exit.
                    SingleChoiceChips(
                        label = stringResource(R.string.fakeip_scope),
                        options = listOf(
                            com.leadaxe.aibox.app.FakeIpScopeAll,
                            com.leadaxe.aibox.app.FakeIpScopeDirectOnly,
                            com.leadaxe.aibox.app.FakeIpScopeProxyOnly,
                        ),
                        selected = state.fakeIpScope,
                        onSelect = { v -> onChange { copy(fakeIpScope = v) } },
                        display = {
                            when (it) {
                                com.leadaxe.aibox.app.FakeIpScopeDirectOnly ->
                                    stringResource(R.string.fakeip_scope_direct_only)
                                com.leadaxe.aibox.app.FakeIpScopeProxyOnly ->
                                    stringResource(R.string.fakeip_scope_proxy_only)
                                else -> stringResource(R.string.fakeip_scope_all)
                            }
                        },
                    )
                    StringField(
                        label = stringResource(R.string.fakeip_inet4_range),
                        value = state.fakeIpInet4Range,
                        onValueChange = { v -> onChange { copy(fakeIpInet4Range = v) } },
                        placeholder = stringResource(R.string.fakeip_default_range4),
                    )
                    // Always visible: the pool works best when the user sees
                    // the v6 range next to the v4 one, even if tun IPv6 is
                    // currently off — the field is simply not compiled into
                    // the config until IPv6 is enabled, and the supporting
                    // text says so.
                    StringField(
                        label = stringResource(R.string.fakeip_inet6_range),
                        value = state.fakeIpInet6Range,
                        onValueChange = { v -> onChange { copy(fakeIpInet6Range = v) } },
                        placeholder = stringResource(R.string.fakeip_default_range6),
                    )
                    if (!state.enableIpv6) {
                        Text(
                            stringResource(R.string.fakeip_inet6_needs_ipv6),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ListField(
                        label = stringResource(R.string.fakeip_filter),
                        values = state.fakeIpFilter,
                        onValuesChange = { v -> onChange { copy(fakeIpFilter = v) } },
                        placeholder = stringResource(R.string.fakeip_filter_hint),
                        supporting = stringResource(
                            if (state.fakeIpScope == com.leadaxe.aibox.app.FakeIpScopeAll)
                                R.string.fakeip_filter_hint
                            else
                                R.string.fakeip_filter_scope_hint,
                        ),
                    )
                    if (state.fakeIpFilter.isNotEmpty()) {
                        SwitchRow(
                            label = stringResource(R.string.fakeip_filter_exclude),
                            supporting = stringResource(R.string.fakeip_filter_exclude_desc),
                            checked = state.fakeIpFilterExclude,
                            onCheckedChange = { v -> onChange { copy(fakeIpFilterExclude = v) } },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.routes_fakeip_bypass),
                        supporting = stringResource(R.string.routes_fakeip_bypass_desc),
                        checked = state.fakeIpBypass,
                        onCheckedChange = { v -> onChange { copy(fakeIpBypass = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.fakeip_block_https),
                        supporting = stringResource(R.string.fakeip_block_https_desc),
                        checked = state.fakeIpBlockHttps,
                        onCheckedChange = { v -> onChange { copy(fakeIpBlockHttps = v) } },
                    )
                }
            }
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

/** Human label for one final-resolver option (shortcut, server tag, auto). */
@Composable
private fun FinalOptionLabel(option: String, state: AppState): String = when (option) {
    "" -> stringResource(R.string.dns_final_auto)
    DnsFinalProxy -> stringResource(R.string.dns_final_proxy)
    DnsFinalDirect -> stringResource(R.string.dns_final_direct)
    "final:reject" -> stringResource(R.string.dns_final_reject)
    else -> state.dnsServers.firstOrNull { it.tag == option }?.name?.ifBlank { option } ?: option
}

/**
 * Quiet full-width "add" affordance: a tinted card row that unfolds the
 * inline server form in place instead of opening a popup.
 */
@Composable
private fun AddServerRow(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.dns_add_server),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Inline expanding server editor (used both for create and edit). The outer
 * LazyColumn owns scrolling, so the form is a plain Column inside a card —
 * no dialog, no clipped viewport. [formKey] resets the fields when the
 * target switches between "new" and a different server id.
 */
@Composable
private fun DnsServerFormCard(
    title: String,
    initial: DnsServerState?,
    state: AppState,
    onCancel: () -> Unit,
    onSave: (DnsServerState) -> Unit,
) {
    val formKey = initial?.id ?: "__new_dns_server__"
    var name by remember(formKey) { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember(formKey) { mutableStateOf(initial?.type ?: "https") }
    var address by remember(formKey) { mutableStateOf(initial?.address.orEmpty()) }
    var detour by remember(formKey) { mutableStateOf(initial?.detour ?: DnsDetourProxy) }
    var strategy by remember(formKey) { mutableStateOf(initial?.strategy.orEmpty()) }
    var domainResolver by remember(formKey) { mutableStateOf(initial?.domainResolver.orEmpty()) }
    var clientSubnet by remember(formKey) { mutableStateOf(initial?.clientSubnet.orEmpty()) }
    var tlsServerName by remember(formKey) { mutableStateOf(initial?.tlsServerName.orEmpty()) }
    var insecure by remember(formKey) { mutableStateOf(initial?.insecure ?: false) }
    var groupServers by remember(formKey) { mutableStateOf(initial?.groupServers ?: emptyList()) }
    var hostsEntries by remember(formKey) { mutableStateOf(initial?.hostsEntries ?: emptyList()) }
    var groupMode by remember(formKey) {
        mutableStateOf(initial?.groupMode ?: com.leadaxe.aibox.app.DnsGroupStable)
    }
    var groupErrorTtl by remember(formKey) { mutableStateOf(initial?.groupErrorTtl.orEmpty()) }
    var groupWinTtl by remember(formKey) { mutableStateOf(initial?.groupWinTtl.orEmpty()) }

    val detourOptions = remember(state.outbounds) {
        listOf(DnsDetourDirect, DnsDetourProxy) + state.outbounds.map { it.tag }
    }
    // Candidate members for a group: every concrete (non-group) server
    // except this one — groups cannot nest groups.
    val groupCandidates = remember(state.dnsServers, formKey) {
        state.dnsServers
            .filter { it.id != initial?.id && it.type != "group" }
            .map { it.tag }
    }

    val save: () -> Unit = {
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
                hostsEntries = hostsEntries,
                groupMode = groupMode,
                groupErrorTtl = groupErrorTtl,
                groupWinTtl = groupWinTtl,
            ),
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
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
            if (type == "hosts") {
                ListField(
                    label = stringResource(R.string.dns_hosts_entries),
                    values = hostsEntries,
                    onValuesChange = { hostsEntries = it },
                    placeholder = "example.com=1.2.3.4",
                    supporting = stringResource(R.string.dns_hosts_hint),
                )
            }
            if (type != "local" && type != "direct" && type != "group" && type != "hosts") {
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
                    options = com.leadaxe.aibox.app.DnsGroupModes,
                    selected = groupMode,
                    onSelect = { groupMode = it },
                    display = {
                        when (it) {
                            com.leadaxe.aibox.app.DnsGroupFastest -> stringResource(R.string.dns_group_fastest)
                            com.leadaxe.aibox.app.DnsGroupParallel -> stringResource(R.string.dns_group_parallel)
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
                if (groupMode == com.leadaxe.aibox.app.DnsGroupFastest) {
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(onClick = save) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
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
private fun DnsRuleEditorPage(
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
    var responseRcode by remember { mutableStateOf(initial?.responseRcode ?: emptyList()) }
    var server by remember { mutableStateOf(initial?.server.orEmpty()) }
    var action by remember { mutableStateOf(initial?.action ?: DnsRuleActionRoute) }
    var clientSubnet by remember { mutableStateOf(initial?.clientSubnet.orEmpty()) }
    var invert by remember { mutableStateOf(initial?.invert ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header bar: back arrow + title + save, the same shape as the
        // route-rule editor's second-level page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
            }
            Text(
                stringResource(if (initial == null) R.string.dns_new_rule else R.string.dns_edit_rule),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
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
                        responseRcode = responseRcode,
                        action = action,
                        server = server,
                        clientSubnet = clientSubnet,
                        invert = invert,
                        enabled = enabled,
                    ),
                )
            }) { Text(stringResource(R.string.common_save)) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // JSON-kind rules (the route-sync "sync-…" rules) carry their
            // matchers in the JSON body: showing the inline matchers here
            // would be a lie — saving them would strip the body. Edit them
            // as JSON or from the route rule that created them.
            if (initial?.kind == DnsRule.KindJson) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.dns_json_rule_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        StringField(
                            label = stringResource(R.string.routes_json_body),
                            value = initial.json,
                            onValueChange = { },
                            minLines = 4,
                            maxLines = 12,
                        )
                    }
                }
            }
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
                placeholder = "A, AAAA, HTTPS, SVCB…",
            )
            if (action == DnsRuleActionReject) {
                // Response-code matching is a reject-rule concern: which
                // upstream answers should be swallowed.
                ListField(
                    label = stringResource(R.string.dns_response_rcode),
                    values = responseRcode,
                    onValuesChange = { responseRcode = it },
                    placeholder = "NOERROR, NXDOMAIN, SERVFAIL",
                    supporting = stringResource(R.string.dns_response_rcode_hint),
                )
            }
            ListField(
                label = stringResource(R.string.routes_field_package),
                values = packageName,
                onValuesChange = { packageName = it },
            )
            MultiChoiceChips(
                label = stringResource(R.string.dns_clash_mode),
                options = com.leadaxe.aibox.app.ClashModes,
                selected = clashMode,
                onToggle = { m ->
                    clashMode = if (m in clashMode) clashMode - m else clashMode + m
                },
            )
            // Action: route (to a server) / reject (block the query) /
            // route-options (attach strategy options without terminating).
            // The fields below follow the action: reject needs no target
            // server, and the response-code matcher only applies to reject.
            SingleChoiceChips(
                label = stringResource(R.string.dns_rule_action),
                options = DnsRuleActions,
                selected = action,
                onSelect = { action = it },
                display = {
                    when (it) {
                        DnsRuleActionReject -> stringResource(R.string.dns_rule_action_reject)
                        DnsRuleActionRouteOptions -> stringResource(R.string.dns_rule_action_route_options)
                        else -> stringResource(R.string.dns_rule_action_route)
                    }
                },
            )
            if (action != DnsRuleActionReject) {
                SingleChoiceChips(
                    label = stringResource(R.string.dns_target_server),
                    options = listOf("") + state.dnsServers.map { it.tag },
                    selected = server,
                    onSelect = { server = it },
                    display = {
                        if (it.isEmpty()) stringResource(R.string.dns_target_system)
                        else state.dnsServers.firstOrNull { s -> s.tag == it }?.name?.ifBlank { it } ?: it
                    },
                )
            }
            SwitchRow(
                label = stringResource(R.string.routes_invert),
                checked = invert,
                onCheckedChange = { invert = it },
            )
            if (action != DnsRuleActionReject) {
                StringField(
                    label = stringResource(R.string.routes_client_subnet),
                    value = clientSubnet,
                    onValueChange = { clientSubnet = it },
                    placeholder = "1.2.3.0/24",
                    supporting = stringResource(R.string.hint_client_subnet),
                )
            }
        }
    }
}

