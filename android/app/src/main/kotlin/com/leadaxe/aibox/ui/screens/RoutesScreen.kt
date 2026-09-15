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

    // Second-level page: the editor replaces the list while open, and the
    // back arrow returns. No dialog — the form is long and needed the room.
    if (creating || editing != null) {
        RuleEditorPage(
            initial = editing,
            state = state,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                val (adds, normalized) = materializeRuleSetReferences(state.ruleSets, rule)
                store.update { st ->
                    val list = if (creating) {
                        st.routeRules + normalized
                    } else {
                        st.routeRules.map { if (it.id == normalized.id) normalized else it }
                    }
                    st.copy(routeRules = list, ruleSets = st.ruleSets + adds)
                }
                creating = false
                editing = null
            },
        )
        return
    }

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
        else -> outboundDisplayLabel(rule.outbound, state)
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
internal fun outboundDisplayLabel(tag: String, state: AppState): String {
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
