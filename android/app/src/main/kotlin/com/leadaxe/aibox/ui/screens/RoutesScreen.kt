package com.leadaxe.aibox.ui.screens

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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.ProxySelectorTag
import com.leadaxe.aibox.app.RouteRule
import java.util.UUID

@Composable
fun RoutesScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    val state by store.state.collectAsState()

    var editing: RouteRule? by remember { mutableStateOf(null) }
    var creating by remember { mutableStateOf(false) }
    var editingRuleSet: com.leadaxe.aibox.app.RuleSetResource? by remember { mutableStateOf(null) }
    var creatingRuleSet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.routes_add_rule))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.routes_section_rules, state.routeRules.size)) }

        itemsIndexedWithActions(
            items = state.routeRules,
            onMove = { from, to ->
                store.update { st ->
                    val list = st.routeRules.toMutableList()
                    if (to in list.indices) {
                        val item = list.removeAt(from)
                        list.add(to, item)
                    }
                    st.copy(routeRules = list)
                }
            },
        ) { index, rule ->
            RuleCard(
                rule = rule,
                state = state,
                onEdit = { editing = rule },
                onDelete = {
                    store.update { st -> st.copy(routeRules = st.routeRules.filterNot { it.id == rule.id }) }
                },
                onToggleEnabled = { enabled ->
                    store.update { st ->
                        st.copy(routeRules = st.routeRules.map {
                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                        })
                    }
                },
            )
        }

        item {
            FilledTonalButton(onClick = { creatingRuleSet = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.rulesets_add))
            }
        }
        item { SectionHeader(stringResource(R.string.routes_section_rulesets, state.ruleSets.size)) }
        items(state.ruleSets, key = { it.id }) { rs ->
            val cached = remember(rs.id, rs.lastUpdatedEpochMillis) {
                java.io.File(context.filesDir, "box/ruleset/${rs.id}.${rs.extension}").isFile
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rs.tag, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${rs.format} · ${rs.url}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(if (cached) R.string.rulesets_cached else R.string.rulesets_not_cached),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editingRuleSet = rs }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                    }
                    IconButton(onClick = {
                        store.update { st -> st.copy(ruleSets = st.ruleSets.filterNot { it.id == rs.id }) }
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                    }
                }
            }
        }
    }

    if (creating) {
        RuleEditor(
            initial = null,
            state = state,
            onDismiss = { creating = false },
            onSave = { rule ->
                store.update { st -> st.copy(routeRules = st.routeRules + rule) }
                creating = false
            },
        )
    }
    editing?.let { rule ->
        RuleEditor(
            initial = rule,
            state = state,
            onDismiss = { editing = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(routeRules = st.routeRules.map { if (it.id == updated.id) updated else it })
                }
                editing = null
            },
        )
    }

    if (creatingRuleSet) {
        RuleSetEditor(
            initial = null,
            onDismiss = { creatingRuleSet = false },
            onSave = { rs ->
                store.update { st -> st.copy(ruleSets = st.ruleSets + rs) }
                creatingRuleSet = false
            },
        )
    }
    editingRuleSet?.let { rs ->
        RuleSetEditor(
            initial = rs,
            onDismiss = { editingRuleSet = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(ruleSets = st.ruleSets.map { if (it.id == updated.id) updated else it })
                }
                editingRuleSet = null
            },
        )
    }
}

/**
 * Renders a list with per-row move-up/move-down affordances. Order matters in
 * sing-box routing — rules are tried top to bottom — so reordering needs to
 * be one tap away, not buried in the editor.
 */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedWithActions(
    items: List<T>,
    onMove: (Int, Int) -> Unit,
    itemContent: @Composable (Int, T) -> Unit,
) {
    items.forEachIndexed { index, value ->
        item(key = "${index}-${value.hashCode()}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { itemContent(index, value) }
                Column {
                    IconButton(
                        onClick = { onMove(index, index - 1) },
                        enabled = index > 0,
                    ) { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) }
                    IconButton(
                        onClick = { onMove(index, index + 1) },
                        enabled = index < items.lastIndex,
                    ) { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) }
                }
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: RouteRule,
    state: AppState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // One line per rule, Karing-style: a compact matcher summary on
            // top, the action underneath. Long matcher lists are counted
            // rather than printed so the row height stays predictable.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ruleLine(rule, state),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
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
        }
    }
}

/**
 * Renders one rule as a single readable line: the matcher first (with the
 * first few literal values shown), then an arrow and the action target.
 */
@Composable
private fun ruleLine(rule: RouteRule, state: AppState): String {
    val matcher = when (rule.kind) {
        RouteRule.KindJson -> "JSON"
        else -> {
            val parts = buildList {
                appendMatcher(this, "domain", rule.domain)
                appendMatcher(this, "suffix", rule.domainSuffix)
                appendMatcher(this, "keyword", rule.domainKeyword)
                appendMatcher(this, "regex", rule.domainRegex)
                appendMatcher(this, "cidr", rule.ipCidr)
                appendMatcher(this, "port", rule.port)
                appendMatcher(this, "rule_set", rule.ruleSet)
                appendMatcher(this, "protocol", rule.protocol)
                appendMatcher(this, "package", rule.packageName)
                if (rule.isLogical) {
                    add(rule.logicalMode + "(" + rule.rules.size + ")")
                }
            }
            parts.joinToString(" ").ifBlank { "—" }
        }
    }
    val target = when (rule.action) {
        RouteRule.RuleActionReject -> stringResource(R.string.routes_action_reject)
        RouteRule.RuleActionResolve -> stringResource(R.string.routes_action_resolve)
        else -> outboundLabel(rule.outbound, state)
    }
    val prefix = if (rule.invert) "!" else ""
    return "$prefix$matcher → $target"
}

/**
 * Appends `label:first-values` for a matcher list, collapsing long lists to
 * `label:n items` so a single line stays a single line.
 */
private fun appendMatcher(out: MutableList<String>, label: String, values: List<String>) {
    if (values.isEmpty()) return
    out += when {
        values.size <= 2 -> "$label:${values.joinToString(",")}"
        else -> "$label:${values.size}"
    }
}

@Composable
private fun outboundDisplayLabel(tag: String, state: AppState): String {
    return when (tag) {
        "", ProxySelectorTag -> stringResource(R.string.dns_detour_proxy)
        DirectOutboundTag -> stringResource(R.string.dns_detour_direct)
        else -> {
            // Groups first: their tags are checked before node tags so a rule
            // pointing at a group shows the group's display name.
            val group = state.outboundGroups.firstOrNull { it.tag == tag }
            if (group != null) {
                group.name.ifBlank { tag }
            } else {
                val node = state.outbounds.firstOrNull { it.tag == tag }
                node?.name?.ifBlank { tag } ?: tag
            }
        }
    }
}

/** Route-rule card summary; same labels as the editor picker. */
@Composable
private fun outboundLabel(tag: String, state: AppState): String = outboundDisplayLabel(tag, state)

@Composable
private fun RuleEditor(
    initial: RouteRule?,
    state: AppState,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: RouteRule.KindInline) }
    var action by remember { mutableStateOf(initial?.action ?: RouteRule.RuleActionRoute) }
    var outbound by remember { mutableStateOf(initial?.outbound ?: ProxySelectorTag) }
    var invert by remember { mutableStateOf(initial?.invert ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    var domain by remember { mutableStateOf(initial?.domain ?: emptyList()) }
    var domainSuffix by remember { mutableStateOf(initial?.domainSuffix ?: emptyList()) }
    var domainKeyword by remember { mutableStateOf(initial?.domainKeyword ?: emptyList()) }
    var domainRegex by remember { mutableStateOf(initial?.domainRegex ?: emptyList()) }
    var ipCidr by remember { mutableStateOf(initial?.ipCidr ?: emptyList()) }
    var port by remember { mutableStateOf(initial?.port ?: emptyList()) }
    var sourcePort by remember { mutableStateOf(initial?.sourcePort ?: emptyList()) }
    var portRange by remember { mutableStateOf(initial?.portRange ?: emptyList()) }
    var network by remember { mutableStateOf(initial?.network ?: emptyList()) }
    var protocol by remember { mutableStateOf(initial?.protocol ?: emptyList()) }
    var packageName by remember { mutableStateOf(initial?.packageName ?: emptyList()) }
    var ruleSet by remember { mutableStateOf(initial?.ruleSet ?: emptyList()) }
    var sourceIpIsPrivate by remember { mutableStateOf(initial?.sourceIpIsPrivate ?: false) }
    var ipIsPrivate by remember { mutableStateOf(initial?.ipIsPrivate ?: false) }
    var jsonBody by remember { mutableStateOf(initial?.json.orEmpty()) }
    var structureType by remember { mutableStateOf(initial?.type ?: RouteRule.RuleTypeDefault) }
    var logicalMode by remember { mutableStateOf(initial?.logicalMode ?: RouteRule.LogicalAnd) }
    var subRules by remember { mutableStateOf(initial?.rules ?: emptyList()) }
    var editingSubRule by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.routes_new_rule else R.string.routes_edit_rule)) },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.routes_name),
                    value = name,
                    onValueChange = { name = it },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.routes_kind),
                    options = listOf(RouteRule.KindInline, RouteRule.KindJson),
                    selected = kind,
                    onSelect = { kind = it },
                    display = {
                        if (it == RouteRule.KindInline) stringResource(R.string.routes_kind_inline)
                        else stringResource(R.string.routes_kind_json)
                    },
                )
                if (kind == RouteRule.KindInline) {
                    // ----- structure: default vs logical -----
                    // This is where AND / OR lives: a logical rule carries
                    // sub-rules and earns the combination mode; a default
                    // rule matches its own fields directly. The sub-rule
                    // list only appears in logical mode, which keeps the
                    // common case (one matcher) to one screen full of
                    // fields instead of a tree editor.
                    SingleChoiceChips(
                        label = stringResource(R.string.routes_rule_structure),
                        options = listOf(RouteRule.RuleTypeDefault, RouteRule.RuleTypeLogical),
                        selected = structureType,
                        onSelect = { structureType = it },
                        display = {
                            if (it == RouteRule.RuleTypeLogical)
                                stringResource(R.string.routes_type_logical)
                            else stringResource(R.string.routes_type_default)
                        },
                    )
                    Text(
                        stringResource(R.string.routes_structure_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (structureType == RouteRule.RuleTypeLogical) {
                        SingleChoiceChips(
                            label = stringResource(R.string.routes_logical_mode),
                            options = listOf(RouteRule.LogicalAnd, RouteRule.LogicalOr),
                            selected = logicalMode,
                            onSelect = { logicalMode = it },
                            display = {
                                if (it == RouteRule.LogicalOr)
                                    stringResource(R.string.routes_logical_or)
                                else stringResource(R.string.routes_logical_and)
                            },
                        )
                        Text(
                            stringResource(R.string.routes_rule_sub_rules, subRules.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        subRules.forEachIndexed { index, sub ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            sub.name.ifBlank { sub.id.take(8) },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            subRuleSummary(sub),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            subRules = subRules.toMutableList().also { it.removeAt(index) }
                                        },
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                                    }
                                }
                            }
                        }
                        FilledTonalButton(onClick = { editingSubRule = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.routes_add_sub_rule))
                        }
                    }
                }
                if (kind == RouteRule.KindInline && structureType == RouteRule.RuleTypeDefault) {
                    ListField(
                        label = stringResource(R.string.routes_field_domain),
                        values = domain,
                        onValuesChange = { domain = it },
                        placeholder = "example.com",
                        supporting = stringResource(R.string.hint_domain),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain_suffix),
                        values = domainSuffix,
                        onValuesChange = { domainSuffix = it },
                        placeholder = "google.com, openai.com",
                        supporting = stringResource(R.string.hint_domain_suffix),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain_keyword),
                        values = domainKeyword,
                        onValuesChange = { domainKeyword = it },
                        supporting = stringResource(R.string.hint_domain_keyword),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_domain_regex),
                        values = domainRegex,
                        onValuesChange = { domainRegex = it },
                        supporting = stringResource(R.string.hint_domain_regex),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_ip_cidr),
                        values = ipCidr,
                        onValuesChange = { ipCidr = it },
                        placeholder = "8.8.8.8/32",
                        supporting = stringResource(R.string.hint_ip_cidr),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_port),
                        values = port,
                        onValuesChange = { port = it },
                        placeholder = "443, 8443",
                        supporting = stringResource(R.string.hint_port),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_source_port),
                        values = sourcePort,
                        onValuesChange = { sourcePort = it },
                        supporting = stringResource(R.string.hint_source_port),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_port_range),
                        values = portRange,
                        onValuesChange = { portRange = it },
                        placeholder = "1000:2000",
                        supporting = stringResource(R.string.hint_port_range),
                    )
                    MultiChoiceChips(
                        label = stringResource(R.string.routes_field_network),
                        options = listOf("tcp", "udp"),
                        selected = network,
                        onToggle = { n -> network = if (n in network) network - n else network + n },
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_protocol),
                        values = protocol,
                        onValuesChange = { protocol = it },
                        placeholder = "http, tls, quic",
                        supporting = stringResource(R.string.hint_protocol),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_package),
                        values = packageName,
                        onValuesChange = { packageName = it },
                        supporting = stringResource(R.string.hint_package_name),
                    )
                    ListField(
                        label = stringResource(R.string.routes_field_rule_set),
                        values = ruleSet,
                        onValuesChange = { ruleSet = it },
                        supporting = stringResource(R.string.hint_rule_set),
                    )
                    SwitchRow(
                        label = stringResource(R.string.routes_source_private),
                        checked = sourceIpIsPrivate,
                        onCheckedChange = { sourceIpIsPrivate = it },
                    )
                    SwitchRow(
                        label = stringResource(R.string.routes_ip_private),
                        checked = ipIsPrivate,
                        onCheckedChange = { ipIsPrivate = it },
                    )
                } else {
                    StringField(
                        label = stringResource(R.string.routes_json_body),
                        value = jsonBody,
                        onValueChange = { jsonBody = it },
                    )
                }
                SingleChoiceChips(
                    label = stringResource(R.string.routes_action),
                    options = listOf(
                        RouteRule.RuleActionRoute, RouteRule.RuleActionReject, RouteRule.RuleActionResolve,
                    ),
                    selected = action,
                    onSelect = { action = it },
                    display = {
                        when (it) {
                            RouteRule.RuleActionReject -> stringResource(R.string.routes_action_reject)
                            RouteRule.RuleActionResolve -> stringResource(R.string.routes_action_resolve)
                            else -> stringResource(R.string.routes_action_route)
                        }
                    },
                )
                Text(
                    stringResource(R.string.hint_action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action == RouteRule.RuleActionRoute) {
                    val outboundOptions = remember(state.outbounds, state.outboundGroups) {
                        listOf(ProxySelectorTag, DirectOutboundTag) +
                            state.outboundGroups.filter { it.enabled }.map { it.tag } +
                            state.outbounds.map { it.tag }
                    }
                    SingleChoiceChips(
                        label = stringResource(R.string.routes_outbound),
                        options = outboundOptions,
                        selected = outbound,
                        onSelect = { outbound = it },
                        display = { outboundDisplayLabel(it, state) },
                    )
                    Text(
                        stringResource(R.string.hint_outbound),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.routes_invert),
                    checked = invert,
                    onCheckedChange = { invert = it },
                )
                Text(
                    stringResource(R.string.hint_invert),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        (initial ?: RouteRule(id = UUID.randomUUID().toString())).copy(
                            name = name,
                            kind = kind,
                            type = structureType,
                            logicalMode = logicalMode,
                            rules = subRules,
                            action = action,
                            outbound = outbound,
                            invert = invert,
                            enabled = enabled,
                            domain = domain,
                            domainSuffix = domainSuffix,
                            domainKeyword = domainKeyword,
                            domainRegex = domainRegex,
                            ipCidr = ipCidr,
                            port = port,
                            sourcePort = sourcePort,
                            portRange = portRange,
                            network = network,
                            protocol = protocol,
                            packageName = packageName,
                            ruleSet = ruleSet,
                            sourceIpIsPrivate = sourceIpIsPrivate,
                            ipIsPrivate = ipIsPrivate,
                            json = jsonBody,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    if (editingSubRule) {
        // Sub-rules are pure matchers: no action, no outbound, no name is
        // required. A compact copy of this editor would do, but reusing the
        // full one keeps the field set (and its hints) identical between
        // top-level and nested rules.
        SubRuleEditor(
            onDismiss = { editingSubRule = false },
            onSave = { sub ->
                subRules = subRules + sub
                editingSubRule = false
            },
        )
    }
}

/** One-line human summary of a sub-rule, shown in the sub-rule list. */
private fun subRuleSummary(rule: RouteRule): String {
    val parts = buildList {
        if (rule.domain.isNotEmpty()) add("domain ${rule.domain.size}")
        if (rule.domainSuffix.isNotEmpty()) add("suffix ${rule.domainSuffix.size}")
        if (rule.domainKeyword.isNotEmpty()) add("keyword ${rule.domainKeyword.size}")
        if (rule.domainRegex.isNotEmpty()) add("regex ${rule.domainRegex.size}")
        if (rule.ipCidr.isNotEmpty()) add("cidr ${rule.ipCidr.size}")
        if (rule.port.isNotEmpty()) add("port ${rule.port.size}")
        if (rule.network.isNotEmpty()) add("net ${rule.network.joinToString("/")}")
        if (rule.protocol.isNotEmpty()) add("proto ${rule.protocol.joinToString("/")}")
        if (rule.packageName.isNotEmpty()) add("pkg ${rule.packageName.size}")
        if (rule.ruleSet.isNotEmpty()) add("rule_set ${rule.ruleSet.size}")
        if (rule.sourceIpIsPrivate) add("src-private")
        if (rule.ipIsPrivate) add("dst-private")
    }
    return parts.joinToString(" · ")
}

/**
 * Matcher-only editor for a logical rule's sub-rule. Deliberately smaller
 * than [RuleEditor]: sub-rules carry no action and no outbound, so the two
 * chips are the only structural controls.
 */
@Composable
private fun SubRuleEditor(
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(emptyList<String>()) }
    var domainSuffix by remember { mutableStateOf(emptyList<String>()) }
    var domainKeyword by remember { mutableStateOf(emptyList<String>()) }
    var ipCidr by remember { mutableStateOf(emptyList<String>()) }
    var port by remember { mutableStateOf(emptyList<String>()) }
    var network by remember { mutableStateOf(emptyList<String>()) }
    var protocol by remember { mutableStateOf(emptyList<String>()) }
    var packageName by remember { mutableStateOf(emptyList<String>()) }
    var ruleSet by remember { mutableStateOf(emptyList<String>()) }
    var ipIsPrivate by remember { mutableStateOf(false) }
    var invert by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routes_add_sub_rule)) },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.routes_name),
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
                    label = stringResource(R.string.routes_field_ip_cidr),
                    values = ipCidr,
                    onValuesChange = { ipCidr = it },
                )
                ListField(
                    label = stringResource(R.string.routes_field_port),
                    values = port,
                    onValuesChange = { port = it },
                )
                MultiChoiceChips(
                    label = stringResource(R.string.routes_field_network),
                    options = listOf("tcp", "udp"),
                    selected = network,
                    onToggle = { n -> network = if (n in network) network - n else network + n },
                )
                ListField(
                    label = stringResource(R.string.routes_field_protocol),
                    values = protocol,
                    onValuesChange = { protocol = it },
                )
                ListField(
                    label = stringResource(R.string.routes_field_package),
                    values = packageName,
                    onValuesChange = { packageName = it },
                )
                ListField(
                    label = stringResource(R.string.routes_field_rule_set),
                    values = ruleSet,
                    onValuesChange = { ruleSet = it },
                )
                SwitchRow(
                    label = stringResource(R.string.routes_ip_private),
                    checked = ipIsPrivate,
                    onCheckedChange = { ipIsPrivate = it },
                )
                SwitchRow(
                    label = stringResource(R.string.routes_invert),
                    checked = invert,
                    onCheckedChange = { invert = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RouteRule(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = RouteRule.RuleTypeDefault,
                            domain = domain,
                            domainSuffix = domainSuffix,
                            domainKeyword = domainKeyword,
                            ipCidr = ipCidr,
                            port = port,
                            network = network,
                            protocol = protocol,
                            packageName = packageName,
                            ruleSet = ruleSet,
                            ipIsPrivate = ipIsPrivate,
                            invert = invert,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
/**
 * Editor for a managed rule-set (.srs / source .json) resource. The tag is
 * what inline route and DNS rules reference via `rule_set`; the URL is
 * downloaded to `filesDir/box/ruleset/<id>.<ext>` and reused as a local
 * rule set when the cache exists.
 */
@Composable
private fun RuleSetEditor(
    initial: com.leadaxe.aibox.app.RuleSetResource?,
    onDismiss: () -> Unit,
    onSave: (com.leadaxe.aibox.app.RuleSetResource) -> Unit,
) {
    var tag by remember { mutableStateOf(initial?.tag.orEmpty()) }
    var format by remember { mutableStateOf(initial?.format ?: "binary") }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var interval by remember { mutableStateOf((initial?.updateIntervalHours ?: 168).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.rulesets_new else R.string.rulesets_edit))
        },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.rulesets_tag),
                    value = tag,
                    onValueChange = { tag = it },
                    placeholder = stringResource(R.string.rulesets_tag_hint),
                )
                SingleChoiceChips(
                    label = stringResource(R.string.rulesets_format),
                    options = listOf("binary", "source"),
                    selected = format,
                    onSelect = { format = it },
                    display = {
                        if (it == "source") stringResource(R.string.rulesets_format_source)
                        else stringResource(R.string.rulesets_format_binary)
                    },
                )
                StringField(
                    label = stringResource(R.string.rulesets_url),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = stringResource(R.string.rulesets_url_hint),
                )
                StringField(
                    label = stringResource(R.string.rulesets_interval),
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() } },
                    placeholder = stringResource(R.string.rulesets_interval_hint),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: com.leadaxe.aibox.app.RuleSetResource(
                            id = java.util.UUID.randomUUID().toString(),
                            tag = "",
                        )).copy(
                            tag = tag.trim(),
                            format = format,
                            url = url.trim(),
                            updateIntervalHours = interval.toIntOrNull() ?: 0,
                        ),
                    )
                },
                enabled = tag.isNotBlank() && url.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
