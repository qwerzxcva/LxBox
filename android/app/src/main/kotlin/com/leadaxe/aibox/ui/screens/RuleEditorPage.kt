package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.DirectOutboundTag
import com.leadaxe.aibox.app.ProxySelectorTag
import com.leadaxe.aibox.app.RouteRule
import com.leadaxe.aibox.engine.singbox.RouteJson
import java.util.UUID

/**
 * Full-screen rule editor (second-level page). The shape the user asked for:
 *
 *  - **Exit and DNS live at the top level, once.** Sub-rules carry match
 *    conditions only — the whole rule routes to one target and resolves
 *    through one server, so the fields are hoisted out of the branches.
 *  - **Sub-rules are inline cards, named automatically** ("Sub-rule 1",
 *    "Sub-rule 2", …) — no naming required, no nested dialogs. New cards are
 *    appended below the last one and start expanded.
 *  - **Every card collapses**, including the first: the header row always
 *    shows the title and a summary, the body hides behind a chevron.
 *  - The whole rule sits inside one large rounded card (the "oval").
 *
 * Saving normalises the shape: a rule with sub-rules becomes the logical
 * container (AND/OR over the branches); a rule without them keeps its own
 * conditions. Either way the exit/DNS picked at the top are applied to the
 * container, which is what the compiler derives DNS rules from.
 */
@Composable
fun RuleEditorPage(
    initial: RouteRule?,
    state: AppState,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
) {
    // ----- top-level identity + action (hoisted out of the branches)
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: RouteRule.KindInline) }
    var action by remember { mutableStateOf(initial?.action ?: RouteRule.RuleActionRoute) }
    var outbound by remember { mutableStateOf(initial?.outbound ?: ProxySelectorTag) }
    var syncDnsServer by remember { mutableStateOf(initial?.syncDnsServer.orEmpty()) }
    var clientSubnet by remember { mutableStateOf(initial?.clientSubnet.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var jsonBody by remember { mutableStateOf(initial?.json.orEmpty()) }
    var ipFamily by remember { mutableStateOf(initial?.ipFamily.orEmpty()) }
    var ipPreference by remember { mutableStateOf(initial?.ipPreference ?: "prefer_ipv6") }

    // Live JSON validation for the json kind: the editor refuses to save a
    // payload the kernel would silently drop, and shows where the problem is.
    val jsonProblem = remember(jsonBody, kind) {
        if (kind == RouteRule.KindJson && jsonBody.isNotBlank()) RouteJson.validate(jsonBody) else null
    }
    val jsonSummary = remember(jsonBody, kind) {
        if (kind == RouteRule.KindJson && jsonBody.isNotBlank()) RouteJson.describe(jsonBody) else null
    }

    // ----- branches: every rule is a stack of (at least one) sub-rule card.
    // A plain rule's own conditions become sub-rule 1, so the model is
    // uniform: adding another branch is just appending a card.
    var branches by remember {
        mutableStateOf(
            initial?.rules?.takeIf { it.isNotEmpty() }
                ?: listOf(matchOnlyBranch(initial)),
        )
    }
    var collapsed by remember { mutableStateOf(initial?.rules?.indices?.toSet() ?: emptySet()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ----- header bar
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
                    stringResource(if (initial == null) R.string.routes_new_rule else R.string.routes_edit_rule),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !(kind == RouteRule.KindJson && jsonProblem != null),
                    onClick = {
                        onSave(
                            compose(
                                initial = initial,
                                name = name, kind = kind, action = action, outbound = outbound,
                                syncDnsServer = syncDnsServer, clientSubnet = clientSubnet,
                                enabled = enabled, jsonBody = jsonBody,
                                ipFamily = ipFamily, ipPreference = ipPreference,
                                branches = branches,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.common_save)) }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ----- the "oval": one big rounded card around the whole rule
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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

                        if (kind == RouteRule.KindJson) {
                            StringField(
                                label = stringResource(R.string.routes_json_body),
                                value = jsonBody,
                                onValueChange = { jsonBody = it },
                                minLines = 6, maxLines = 14,
                                supporting = stringResource(R.string.routes_json_multiline_hint),
                            )
                            // Live validation feedback: green when the payload
                            // parses, red with the position when it does not.
                            JsonValidationBanner(problem = jsonProblem, summary = jsonSummary, raw = jsonBody, state = state)
                        } else {
                            // ----- branches
                            branches.forEachIndexed { index, branch ->
                                BranchCard(
                                    index = index,
                                    branch = branch,
                                    state = state,
                                    collapsed = index in collapsed,
                                    onToggleCollapse = {
                                        collapsed = if (index in collapsed) collapsed - index else collapsed + index
                                    },
                                    onChange = { updated ->
                                        branches = branches.toMutableList().also { it[index] = updated }
                                    },
                                    onDelete = {
                                        branches = branches.toMutableList().also { it.removeAt(index) }
                                        collapsed = emptySet()
                                    },
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    branches = branches + RouteRule(
                                        id = UUID.randomUUID().toString(),
                                        name = "",
                                        type = RouteRule.RuleTypeDefault,
                                        combine = RouteRule.CombineOr,
                                    )
                                },
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Text(
                                    stringResource(
                                        R.string.routes_add_sub_rule_named,
                                        branches.size + 1,
                                    ),
                                )
                            }
                        }
                    }
                }

                // ----- action block: exit + DNS, once for the whole rule
                if (kind == RouteRule.KindInline) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Action + combine + invert on one row, English
                            // tokens as the user asked ("or" "and" "invert").
                            SingleChoiceChips(
                                label = stringResource(R.string.routes_action),
                                options = listOf(
                                    RouteRule.RuleActionRoute, RouteRule.RuleActionReject,
                                    RouteRule.RuleActionResolve,
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
                            }
                            // ECS / client subnet is a rule-level decision,
                            // not tied to the resolve action: any rule can
                            // carry a client_subnet for its DNS linkage.
                            StringField(
                                label = stringResource(R.string.routes_client_subnet),
                                value = clientSubnet,
                                onValueChange = { clientSubnet = it },
                                placeholder = "1.2.3.0/24",
                                supporting = stringResource(R.string.hint_client_subnet),
                            )
                            // DNS linkage: creating a DNS rule alongside this
                            // route rule. The created rule shows in the DNS
                            // tab as JSON (it is derived, not hand-edited).
                            val dnsOptions = remember(state.dnsServers) {
                                listOf("") + state.dnsServers.filter { it.enabled }.map { it.tag }
                            }
                            SingleChoiceChips(
                                label = stringResource(R.string.routes_sync_dns_server),
                                options = dnsOptions,
                                selected = syncDnsServer,
                                onSelect = { syncDnsServer = it },
                                display = { tag ->
                                    if (tag.isEmpty()) stringResource(R.string.routes_sync_dns_none)
                                    else state.dnsServers.firstOrNull { s -> s.tag == tag }
                                        ?.name?.ifBlank { tag } ?: tag
                                },
                            )
                            if (syncDnsServer.isNotBlank()) {
                                Text(
                                    stringResource(R.string.routes_sync_dns_json_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SwitchRow(
                                label = stringResource(R.string.routes_enabled),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                            )
                            // ----- per-rule address family
                            SingleChoiceChips(
                                label = stringResource(R.string.routes_ip_family),
                                options = listOf("", "both", "ipv4_only", "ipv6_only"),
                                selected = ipFamily,
                                onSelect = { ipFamily = it },
                                display = {
                                    when (it) {
                                        "both" -> stringResource(R.string.routes_ip_family_both)
                                        "ipv4_only" -> stringResource(R.string.routes_ip_family_v4)
                                        "ipv6_only" -> stringResource(R.string.routes_ip_family_v6)
                                        else -> stringResource(R.string.routes_ip_family_inherit)
                                    }
                                },
                            )
                            if (ipFamily == "both") {
                                SingleChoiceChips(
                                    label = stringResource(R.string.routes_ip_preference),
                                    options = listOf("prefer_ipv6", "prefer_ipv4"),
                                    selected = ipPreference,
                                    onSelect = { ipPreference = it },
                                    display = {
                                        if (it == "prefer_ipv4") stringResource(R.string.settings_prefer_ipv4)
                                        else stringResource(R.string.settings_prefer_ipv6)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live feedback for a JSON-kind rule. Green with a one-line summary when
 * the payload parses; red with the problem and its position when it does
 * not — including line/column when the parser reported an offset.
 */
@Composable
private fun JsonValidationBanner(
    problem: RouteJson.Problem?,
    summary: RouteJson.Summary?,
    raw: String,
    state: AppState,
) {
    if (summary == null && problem == null) return
    if (problem != null) {
        val location = problem.line(raw).let { line ->
            if (line > 0) " (line ${line}, column ${problem.column(raw)})" else ""
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Column {
                Text(
                    stringResource(R.string.routes_json_invalid),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    problem.message + location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    if (summary != null && summary.valid) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    stringResource(R.string.routes_json_recognized),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One collapsible sub-rule card: title + summary header, conditions body. */
@Composable
private fun BranchCard(
    index: Int,
    branch: RouteRule,
    state: AppState,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onChange: (RouteRule) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.routes_sub_rule_n, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val summary = subRuleSummary(branch)
                    Text(
                        summary.ifBlank { stringResource(R.string.routes_sub_rule_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = stringResource(R.string.routes_expand_collapse),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                }
            }
            AnimatedVisibility(visible = !collapsed) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Combine + invert on one row, plain English tokens:
                    // matching any condition (or) vs all of them (and).
                    SingleChoiceChips(
                        label = stringResource(R.string.routes_combine_mode),
                        options = listOf(RouteRule.CombineOr, RouteRule.CombineAnd),
                        selected = branch.combine.ifBlank { RouteRule.CombineOr },
                        onSelect = { onChange(branch.copy(combine = it)) },
                        display = { it },
                    )
                    SwitchRow(
                        label = "invert",
                        checked = branch.invert,
                        onCheckedChange = { onChange(branch.copy(invert = it)) },
                    )
                    MatchFields(rule = branch, state = state, onChange = onChange)
                }
            }
        }
    }
}

/**
 * Match-condition editor shared by a branch card and the implicit
 * single-branch form. Contains no action fields: exit and DNS belong to the
 * container rule.
 */
@Composable
private fun MatchFields(
    rule: RouteRule,
    state: AppState,
    onChange: (RouteRule) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListField(
        label = stringResource(R.string.routes_field_domain),
        values = rule.domain,
        onValuesChange = { onChange(rule.copy(domain = it)) },
        supporting = stringResource(R.string.hint_domain),
    )
    ListField(
        label = stringResource(R.string.routes_field_domain_suffix),
        values = rule.domainSuffix,
        onValuesChange = { onChange(rule.copy(domainSuffix = it)) },
        placeholder = "google.com, openai.com",
        supporting = stringResource(R.string.hint_domain_suffix),
    )
    ListField(
        label = stringResource(R.string.routes_field_domain_keyword),
        values = rule.domainKeyword,
        onValuesChange = { onChange(rule.copy(domainKeyword = it)) },
        supporting = stringResource(R.string.hint_domain_keyword),
    )
    ListField(
        label = stringResource(R.string.routes_field_ip_cidr),
        values = rule.ipCidr,
        onValuesChange = { onChange(rule.copy(ipCidr = it)) },
        supporting = stringResource(R.string.hint_ip_cidr),
    )
    ListField(
        label = stringResource(R.string.routes_field_package),
        values = rule.packageName,
        onValuesChange = { onChange(rule.copy(packageName = it)) },
        supporting = stringResource(R.string.hint_package_name),
    )
    ListField(
        label = stringResource(R.string.routes_field_rule_set),
        values = rule.ruleSet,
        onValuesChange = { onChange(rule.copy(ruleSet = it)) },
        supporting = stringResource(R.string.hint_rule_set),
    )
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.routes_hide_advanced else R.string.routes_show_advanced,
            ),
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListField(
                label = stringResource(R.string.routes_field_domain_regex),
                values = rule.domainRegex,
                onValuesChange = { onChange(rule.copy(domainRegex = it)) },
                supporting = stringResource(R.string.hint_domain_regex),
            )
            ListField(
                label = stringResource(R.string.routes_field_port),
                values = rule.port,
                onValuesChange = { onChange(rule.copy(port = it)) },
                supporting = stringResource(R.string.hint_port),
            )
            ListField(
                label = stringResource(R.string.routes_field_protocol),
                values = rule.protocol,
                onValuesChange = { onChange(rule.copy(protocol = it)) },
                supporting = stringResource(R.string.hint_protocol),
            )
            MultiChoiceChips(
                label = stringResource(R.string.routes_field_network),
                options = listOf("tcp", "udp"),
                selected = rule.network,
                onToggle = { n ->
                    onChange(rule.copy(network = if (n in rule.network) rule.network - n else rule.network + n))
                },
            )
            SwitchRow(
                label = stringResource(R.string.routes_ip_private),
                checked = rule.ipIsPrivate,
                onCheckedChange = { onChange(rule.copy(ipIsPrivate = it)) },
            )
        }
    }
}

/**
 * A match-only copy of [source]: the branch form of a plain rule carries
 * exactly its conditions, never its action or exit.
 */
private fun matchOnlyBranch(source: RouteRule?): RouteRule = RouteRule(
    id = UUID.randomUUID().toString(),
    name = source?.name.orEmpty(),
    type = RouteRule.RuleTypeDefault,
    combine = source?.combine ?: RouteRule.CombineOr,
    domain = source?.domain ?: emptyList(),
    domainSuffix = source?.domainSuffix ?: emptyList(),
    domainKeyword = source?.domainKeyword ?: emptyList(),
    domainRegex = source?.domainRegex ?: emptyList(),
    ipCidr = source?.ipCidr ?: emptyList(),
    port = source?.port ?: emptyList(),
    protocol = source?.protocol ?: emptyList(),
    network = source?.network ?: emptyList(),
    packageName = source?.packageName ?: emptyList(),
    ruleSet = source?.ruleSet ?: emptyList(),
    ipIsPrivate = source?.ipIsPrivate ?: false,
    invert = source?.invert ?: false,
)

/** Folds the editor state into the rule the compiler will see. */
private fun compose(
    initial: RouteRule?,
    name: String,
    kind: String,
    action: String,
    outbound: String,
    syncDnsServer: String,
    clientSubnet: String,
    enabled: Boolean,
    jsonBody: String,
    ipFamily: String,
    ipPreference: String,
    branches: List<RouteRule>,
): RouteRule {
    val base = initial ?: RouteRule(id = UUID.randomUUID().toString())
    // Uniform model: the branches are the match; the container owns the
    // action + exit. A single branch flattens back into a plain rule — the
    // kernel sees the same shape either way, and plain rules keep their
    // compile-time dedupe (logical rules must be passed through verbatim).
    if (branches.size == 1) {
        val m = branches.first()
        return base.copy(
            name = name, kind = kind, action = action, outbound = outbound,
            syncDnsServer = syncDnsServer, clientSubnet = clientSubnet,
            enabled = enabled, type = RouteRule.RuleTypeDefault,
            combine = m.combine,
            rules = emptyList(),
            ipFamily = ipFamily, ipPreference = ipPreference,
            domain = m.domain, domainSuffix = m.domainSuffix,
            domainKeyword = m.domainKeyword, domainRegex = m.domainRegex,
            ipCidr = m.ipCidr, port = m.port, protocol = m.protocol,
            network = m.network, packageName = m.packageName,
            ruleSet = m.ruleSet, ipIsPrivate = m.ipIsPrivate,
            invert = m.invert,
            json = jsonBody,
        )
    }
    // Multiple branches combine with OR: each branch is a self-contained
    // match, and first-hit-wins routing means "any branch matches" is what
    // a multi-branch rule reads as. Each branch keeps its own combine mode
    // (the kernel accepts nested logical rules as long as only the outer
    // rule carries an action — see ValidateNoNestedRuleActions).
    return base.copy(
        name = name, kind = kind, action = action, outbound = outbound,
        syncDnsServer = syncDnsServer, clientSubnet = clientSubnet,
        enabled = enabled, logicalMode = RouteRule.LogicalOr,
        type = RouteRule.RuleTypeLogical,
        ipFamily = ipFamily, ipPreference = ipPreference,
        rules = branches,
        // Container-level conditions stay empty: branches own the match.
        domain = emptyList(), domainSuffix = emptyList(), domainKeyword = emptyList(),
        domainRegex = emptyList(), ipCidr = emptyList(), port = emptyList(),
        protocol = emptyList(), network = emptyList(), packageName = emptyList(),
        ruleSet = emptyList(), ipIsPrivate = false, invert = false,
        json = jsonBody,
    )
}

/** One-line human summary of a sub-rule, shown in the card header. */
internal fun subRuleSummary(rule: RouteRule): String {
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
