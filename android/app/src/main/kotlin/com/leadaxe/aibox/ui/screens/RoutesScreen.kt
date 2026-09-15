package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.outlined.DragHandle
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
        item {
            Column {
                SectionHeader(stringResource(R.string.routes_section_rules, state.routeRules.size))
                Text(
                    stringResource(R.string.routes_exec_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
        }

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
            SectionHeader(stringResource(R.string.routes_section_builtin))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwitchRow(
                        label = stringResource(R.string.routes_fakeip_bypass),
                        supporting = stringResource(R.string.routes_fakeip_bypass_desc),
                        checked = state.fakeIpBypass,
                        onCheckedChange = { v -> store.update { it.copy(fakeIpBypass = v) } },
                    )
                    val outboundOptions = remember(state.outbounds, state.outboundGroups) {
                        listOf("", DirectOutboundTag) +
                            state.outboundGroups.filter { it.enabled }.map { it.tag } +
                            state.outbounds.map { it.tag }
                    }
                    SingleChoiceChips(
                        label = stringResource(R.string.routes_unknown_traffic),
                        options = outboundOptions,
                        selected = state.unknownTrafficOutbound,
                        onSelect = { v -> store.update { it.copy(unknownTrafficOutbound = v) } },
                        display = { tag ->
                            when (tag) {
                                "" -> stringResource(R.string.routes_unknown_traffic_proxy)
                                DirectOutboundTag -> stringResource(R.string.dns_detour_direct)
                                else -> state.outboundGroups.firstOrNull { it.tag == tag }?.name?.ifBlank { tag }
                                    ?: state.outbounds.firstOrNull { it.tag == tag }?.name?.ifBlank { tag }
                                    ?: tag
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.routes_unknown_traffic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                val (adds, normalized) = materializeRuleSetReferences(state.ruleSets, rule)
                store.update { st ->
                    st.copy(routeRules = st.routeRules + normalized, ruleSets = st.ruleSets + adds)
                }
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
                val (adds, normalized) = materializeRuleSetReferences(state.ruleSets, updated)
                store.update { st ->
                    st.copy(
                        routeRules = st.routeRules.map { if (it.id == normalized.id) normalized else it },
                        ruleSets = st.ruleSets + adds,
                    )
                }
                editing = null
            },
        )
    }
}

/**
 * Turns https URLs in a rule_set list into managed rule-set resources (tag
 * derived from the last URL path segment). Existing tags pass through
 * untouched; unknown plain tags are kept as-is so a resource can be created
 * later from the list. Returns the resources to add and the rewritten tags.
 */
internal fun materializeRuleSetTags(
    existing: List<com.leadaxe.aibox.app.RuleSetResource>,
    entries: List<String>,
): Pair<List<com.leadaxe.aibox.app.RuleSetResource>, List<String>> {
    if (entries.isEmpty()) return Pair(emptyList(), entries)
    val knownTags = existing.map { it.tag }.toSet()
    val adds = mutableListOf<com.leadaxe.aibox.app.RuleSetResource>()
    fun addResource(tag: String, url: String, format: String): String {
        val resource = com.leadaxe.aibox.app.RuleSetResource(
            id = java.util.UUID.randomUUID().toString(),
            tag = tag,
            format = format,
            url = url,
        )
        adds += resource
        return tag
    }
    val tags = entries.map { entry ->
        val trimmed = entry.trim()
        if (trimmed.startsWith("https://")) {
            val last = trimmed.trimEnd('/').substringAfterLast('/')
            val derivedTag = last.substringAfterLast("-").removeSuffix(".srs").removeSuffix(".json")
                .ifBlank { last.substringBefore('.') }
            val format = if (trimmed.endsWith(".json")) "source" else "binary"
            val taken = { t: String -> t in knownTags || adds.any { it.tag == t } }
            when {
                derivedTag.isBlank() -> {
                    var unique = "ruleset-${existing.size + adds.size}"
                    while (taken(unique)) unique += "-x"
                    addResource(unique, trimmed, format)
                }
                taken(derivedTag) -> {
                    var unique = "${derivedTag}-${existing.size + adds.size}"
                    while (taken(unique)) unique += "-x"
                    addResource(unique, trimmed, format)
                }
                else -> addResource(derivedTag, trimmed, format)
            }
        } else {
            trimmed
        }
    }
    return Pair(adds, tags)
}

/** Route-rule flavor of [materializeRuleSetTags]. */
internal fun materializeRuleSetReferences(
    existing: List<com.leadaxe.aibox.app.RuleSetResource>,
    rule: RouteRule,
): Pair<List<com.leadaxe.aibox.app.RuleSetResource>, RouteRule> =
    materializeRuleSetTags(existing, rule.ruleSet).let { (adds, tags) ->
        Pair(adds, rule.copy(ruleSet = tags))
    }

/**
 * Renders a list with per-row move-up/move-down affordances. Order matters in
 * sing-box routing — rules are tried top to bottom — so reordering needs to
 * be one tap away, not buried in the editor.
 *
 * Reordering: long-press the handle icon and drag. The dragged row follows
 * the finger; on drop, the permutation is applied via [onMove] calls.
 */
internal fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedWithActions(
    items: List<T>,
    onMove: (Int, Int) -> Unit,
    itemContent: @Composable (Int, T) -> Unit,
) {
    items.forEachIndexed { index, value ->
        item(key = "${index}-${value.hashCode()}") {
            DragDropRow(
                index = index,
                itemsCount = items.size,
                onMove = onMove,
                content = { itemContent(index, value) },
            )
        }
    }
}

/**
 * Row wrapper with a drag handle. Long-press the handle to lift the row,
 * drag to the target position, release to drop. Uses a simple offset shift
 * and calls [onMove] on release; the store rewrite re-renders the list in
 * the new order.
 */
@Composable
internal fun DragDropRow(
    index: Int,
    itemsCount: Int,
    onMove: (Int, Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = offsetX
                    translationY = offsetY
                    if (dragging) {
                        shadowElevation = 16f
                        scaleX = 1.02f
                        scaleY = 1.02f
                    }
                }
                .zIndex(if (dragging) 1f else 0f),
        ) { content() }
        IconButton(
            onClick = {},
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            offsetX = 0f
                            offsetY = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            offsetX = 0f
                            offsetY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offsetX += amount.x
                            offsetY += amount.y
                            // Once the row is dragged past a neighbour's
                            // height, apply the move and reset the offset
                            // so the row keeps tracking the finger.
                            while (offsetY > rowHeightPx && index < itemsCount - 1) {
                                onMove(index, index + 1)
                                offsetY -= rowHeightPx
                            }
                            while (offsetY < -rowHeightPx && index > 0) {
                                onMove(index, index - 1)
                                offsetY += rowHeightPx
                            }
                        },
                    )
                },
        ) {
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = stringResource(R.string.common_drag_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var clientSubnet by remember { mutableStateOf(initial?.clientSubnet.orEmpty()) }
    var syncDnsServer by remember { mutableStateOf(initial?.syncDnsServer.orEmpty()) }
    var logicalMode by remember { mutableStateOf(initial?.logicalMode ?: RouteRule.LogicalAnd) }
    var subRules by remember { mutableStateOf(initial?.rules ?: emptyList()) }
    // IP-only rules match resolved addresses, not names — a synced DNS rule
    // would never fire for them.
    val ipOnly = domain.isEmpty() && domainSuffix.isEmpty() && domainKeyword.isEmpty() &&
        domainRegex.isEmpty() && ruleSet.isEmpty() && subRules.all { sub ->
            sub.domain.isEmpty() && sub.domainSuffix.isEmpty() && sub.domainKeyword.isEmpty() &&
                sub.domainRegex.isEmpty() && sub.ruleSet.isEmpty()
        }
    val dnsSyncApplicable = kind == RouteRule.KindInline && !ipOnly
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
                    // ----- structure: implicit ----- 
                    // A rule is logical iff it carries sub-rules; no separate
                    // mode chip is needed. AND / OR only matters then.
                    if (subRules.isNotEmpty()) {
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
                    }
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
                if (kind == RouteRule.KindInline && subRules.isEmpty()) {
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
                        minLines = 6,
                        maxLines = 14,
                        supporting = stringResource(R.string.routes_json_multiline_hint),
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
                if (action == RouteRule.RuleActionResolve) {
                    StringField(
                        label = stringResource(R.string.routes_client_subnet),
                        value = clientSubnet,
                        onValueChange = { clientSubnet = it },
                        placeholder = "1.2.3.0/24",
                        supporting = stringResource(R.string.hint_client_subnet),
                    )
                }
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
                if (dnsSyncApplicable) {
                    // lxbox-style DNS linkage: pick a server here and the
                    // compiler derives the DNS rule from this rule's domain
                    // matchers — no entries copied into the DNS list.
                    SingleChoiceChips(
                        label = stringResource(R.string.routes_sync_dns_server),
                        options = listOf("") + state.dnsServers.filter { it.enabled }.map { it.tag },
                        selected = syncDnsServer,
                        onSelect = { syncDnsServer = it },
                        display = { tag ->
                            if (tag.isEmpty()) stringResource(R.string.routes_sync_dns_none)
                            else state.dnsServers.firstOrNull { s -> s.tag == tag }?.name?.ifBlank { tag } ?: tag
                        },
                    )
                } else if (kind == RouteRule.KindInline) {
                    Text(
                        stringResource(R.string.routes_sync_dns_na),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: RouteRule(id = UUID.randomUUID().toString())).copy(
                            name = name,
                            kind = kind,
                            type = if (subRules.isEmpty()) RouteRule.RuleTypeDefault else RouteRule.RuleTypeLogical,
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
                            clientSubnet = clientSubnet,
                            syncDnsServer = syncDnsServer,
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
