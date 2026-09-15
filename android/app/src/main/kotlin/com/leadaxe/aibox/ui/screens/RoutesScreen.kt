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
import androidx.compose.material3.OutlinedTextField
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
import com.leadaxe.aibox.app.isPresetInstalled
import com.leadaxe.aibox.app.materializePreset
import com.leadaxe.aibox.app.withoutPreset
import com.leadaxe.aibox.engine.rust.AiboxCore
import com.leadaxe.aibox.engine.singbox.ConfigCompiler
import com.leadaxe.aibox.engine.singbox.RouteJson
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
                // JSON rules mirror their payload's own action so the list and
                // the planner see what the kernel will actually run.
                val synced = if (rule.kind == RouteRule.KindJson) RouteJson.syncMirror(rule) else rule
                val (adds, normalized) = materializeRuleSetReferences(state.ruleSets, synced)
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

        // Route check: type a domain / IP and see which rule fires. The
        // compiled table is replayed by the Rust micro-kernel (rsxm), so
        // the answer matches what the kernel will actually do.
        item {
            RouteCheckSection(state = state)
        }

        // Built-in presets: each is a one-tap rule bundle that materialises
        // into ordinary rules above, so nothing is hidden — they can be
        // enabled, edited or deleted like hand-made rules.
        item {
            SectionHeader(stringResource(R.string.preset_section))
        }
        items(com.leadaxe.aibox.app.RulePresets, key = { it.id }) { preset ->
            PresetCard(
                preset = preset,
                installed = state.isPresetInstalled(preset.id),
                onAdd = {
                    val (rules, resources) = materializePreset(preset, state)
                    store.update { st ->
                        st.copy(
                            routeRules = st.routeRules + rules,
                            ruleSets = st.ruleSets + resources,
                        )
                    }
                },
                onRemove = {
                    store.update { st ->
                        st.copy(routeRules = st.routeRules.withoutPreset(preset.id))
                    }
                },
            )
        }
    }
}

/**
 * One built-in preset row: title, explanation, and an add / remove action.
 * An installed preset toggles its rules instead of re-adding them.
 */
@Composable
private fun PresetCard(
    preset: com.leadaxe.aibox.app.RulePreset,
    installed: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(preset.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(preset.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installed) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.common_delete))
                    }
                } else {
                    FilterChip(
                        selected = false,
                        onClick = onAdd,
                        label = { Text(stringResource(R.string.preset_add)) },
                    )
                }
            }
            if (installed) {
                Text(
                    stringResource(R.string.preset_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
    if (rule.kind == RouteRule.KindJson) {
        // JSON rules describe themselves: parse the payload instead of
        // assuming it routes to the proxy.
        val summary = remember(rule.json) { RouteJson.describe(rule.json) }
        val proxyLabel = stringResource(R.string.dns_detour_proxy)
        val directLabel = stringResource(R.string.dns_detour_direct)
        val label = RouteJson.summaryLabel(summary) { tag ->
            when (tag) {
                "", ProxySelectorTag -> proxyLabel
                DirectOutboundTag -> directLabel
                else -> {
                    state.outboundGroups.firstOrNull { it.tag == tag }?.name?.ifBlank { tag }
                        ?: state.outbounds.firstOrNull { it.tag == tag }?.name?.ifBlank { tag }
                        ?: tag
                }
            }
        }
        return "JSON · $label"
    }
    val matcher = run {
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
            } else if (rule.combine == RouteRule.CombineOr) {
                add("OR")
            }
        }
        parts.joinToString(" ").ifBlank { "—" }
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

/**
 * The 防分流检测 section: type a domain (or IP) with optional port and
 * package, and the Rust micro-kernel (rsxm route-check engine) replays the
 * *compiled* rule table — the exact JSON the kernel would load — and names
 * the rule that fires, its action and outbound.
 *
 * The engine lives in `libaibox_core.so` next to the snapshot fingerprint;
 * when the native library is missing the section says so instead of
 * pretending to check.
 */
@Composable
private fun RouteCheckSection(state: AppState) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    var expanded by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var simulateRuleSet by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CheckResult?>(null) }

    CollapsibleSection(
        title = stringResource(R.string.routecheck_title),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        subtitle = stringResource(R.string.routecheck_subtitle),
    ) {
        StringField(
            label = stringResource(R.string.routecheck_domain),
            value = domain,
            onValueChange = { domain = it },
            placeholder = "www.example.com",
        )
        StringField(
            label = stringResource(R.string.routecheck_port),
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = "443",
        )
        StringField(
            label = stringResource(R.string.routecheck_package),
            value = packageName,
            onValueChange = { packageName = it },
            placeholder = "com.android.chrome",
            supporting = stringResource(R.string.routecheck_package_hint),
        )
        if (state.ruleSets.isNotEmpty()) {
            SingleChoiceChips(
                label = stringResource(R.string.routecheck_ruleset_sim),
                options = listOf("") + state.ruleSets.map { it.tag },
                selected = simulateRuleSet,
                onSelect = { simulateRuleSet = it },
                display = { tag ->
                    if (tag.isEmpty()) stringResource(R.string.routecheck_ruleset_none)
                    else tag
                },
            )
        }
        Button(
            onClick = {
                val compiled = compileForCheck(state, context)
                val query = buildString {
                    append("{")
                    if (domain.isNotBlank()) append("\"domain\":${kotlinx.serialization.json.JsonPrimitive(domain.trim())}")
                    if (port.isNotBlank()) {
                        if (length > 1) append(",")
                        append("\"port\":${port.toIntOrNull() ?: 0}")
                    }
                    if (packageName.isNotBlank()) {
                        if (length > 1) append(",")
                        append("\"package\":${kotlinx.serialization.json.JsonPrimitive(packageName.trim())}")
                    }
                    if (simulateRuleSet.isNotBlank()) {
                        if (length > 1) append(",")
                        append("\"matched_rule_sets\":[${kotlinx.serialization.json.JsonPrimitive(simulateRuleSet)}]")
                    }
                    append("}")
                }
                result = when {
                    compiled == null -> CheckResult(error = context.getString(R.string.routecheck_err_compile))
                    else -> {
                        val raw = AiboxCore.routeCheck(compiled, query)
                        when {
                            raw == null -> CheckResult(error = context.getString(R.string.routecheck_err_native))
                            else -> parseCheckResult(raw)
                        }
                    }
                }
            },
            enabled = domain.isNotBlank() || packageName.isNotBlank(),
        ) {
            Text(stringResource(R.string.routecheck_run))
        }
        result?.let { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        r.error != null -> Text(
                            r.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> {
                            Text(
                                formatAction(r.action, r.outbound, state),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (r.action == "reject") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                r.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.routecheck_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One route-check outcome as rendered by the section. */
private data class CheckResult(
    val action: String = "",
    val outbound: String? = null,
    val reason: String = "",
    val error: String? = null,
)

private fun parseCheckResult(raw: String): CheckResult {
    val element = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw).let { it as? kotlinx.serialization.json.JsonObject }
    }.getOrNull() ?: return CheckResult(error = "engine returned garbage")
    (element["error"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
        return CheckResult(error = "engine: ${it.content}")
    }
    fun str(key: String): String = (element[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    return CheckResult(
        action = str("action"),
        outbound = (element["outbound"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        reason = str("reason"),
    )
}

@Composable
private fun formatAction(action: String, outbound: String?, state: AppState): String = when (action) {
    "reject" -> stringResource(R.string.routecheck_action_reject)
    "hijack-dns" -> stringResource(R.string.routecheck_action_hijack)
    else -> {
        val target = outbound?.let { tag ->
            outboundDisplayLabel(tag, state)
        } ?: stringResource(R.string.routes_unknown_traffic_proxy)
        stringResource(R.string.routecheck_action_route, target)
    }
}

/**
 * Compiles the current state into the rule-table JSON the checker replays.
 * Pure computation — no box, no tun, no service needed; the same function
 * the VPN process feeds the kernel at connect time.
 */
private fun compileForCheck(state: AppState, context: android.content.Context): String? = runCatching {
    val workDir = java.io.File(context.filesDir, "box").apply { mkdirs() }
    val config = ConfigCompiler.compile(state, ruleSetDir = workDir)
    (config["route"] as? kotlinx.serialization.json.JsonObject)?.get("rules").toString()
}.getOrNull()
