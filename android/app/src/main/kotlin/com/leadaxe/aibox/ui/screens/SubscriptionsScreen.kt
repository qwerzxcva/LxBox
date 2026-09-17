package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.OutboundProfile
import com.leadaxe.aibox.app.Subscription
import com.leadaxe.aibox.app.withSubscriptionRule
import com.leadaxe.aibox.engine.share.ShareLinkParser
import com.leadaxe.aibox.engine.share.SubscriptionFetcher
import com.leadaxe.aibox.engine.vpn.VpnRelay
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun SubscriptionsScreen(onEditorLock: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as AIBoxApp
    val store = app.appStateStore
    val state by store.state.collectAsState()
    val fetcher = remember { SubscriptionFetcher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddForm by remember { mutableStateOf(false) }
    var addDialogFolderId by remember { mutableStateOf<String?>(null) }
    var pasteDialog by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    // Section collapse state: groups and mux open (they are the page's
    // primary content), the raw lists stay closed until asked for.
    var groupsExpanded by remember { mutableStateOf(true) }
    var muxExpanded by remember { mutableStateOf(false) }
    var probeExpanded by remember { mutableStateOf(false) }
    var nodesExpanded by remember { mutableStateOf(false) }
    // null = config order; true = delay ascending (failures last)
    var sourcesExpanded by remember { mutableStateOf(false) }
    var foldersExpanded by remember { mutableStateOf(false) }
    val relay = remember { app.vpnRelay }
    // Latency results come back through the relay from the :vpn process.
    val remotePings by relay.pings.collectAsState()
    var pingResults by remember { mutableStateOf<Map<String, PingState>>(emptyMap()) }
    var editingGroup: com.leadaxe.aibox.app.OutboundGroup? by remember { mutableStateOf(null) }
    var editingSubscription: Subscription? by remember { mutableStateOf(null) }
    var editingNode: OutboundProfile? by remember { mutableStateOf(null) }
    androidx.compose.runtime.DisposableEffect(editingNode, editingSubscription, editingGroup) {
        onEditorLock(editingNode != null || editingSubscription != null || editingGroup != null)
        onDispose { onEditorLock(false) }
    }
    var creatingGroup by remember { mutableStateOf(false) }

    // Remote ping answers (from the :vpn process) fold into the local map.
    androidx.compose.runtime.LaunchedEffect(remotePings) {
        val mapped = remotePings.mapValues { (_, r) ->
            when {
                r.delayMillis != null -> PingState.Ok(r.delayMillis)
                r.error != null -> PingState.Failed(r.error)
                else -> PingState.Triggered
            }
        }
        pingResults = pingResults + mapped
    }
    // Prune results of nodes that are gone (deleted subscription / removed
    // node): the map must not grow with every refresh cycle.
    androidx.compose.runtime.LaunchedEffect(state.outbounds) {
        val live = state.outbounds.map { it.id }.toSet()
        pingResults = pingResults.filterKeys { it in live }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Node/group editor replaces the whole screen (early return) — the
    // previous overlay rendered under the list and was untouchable.
    if (editingNode != null) {
        NodeEditorPage(
            initial = editingNode!!,
            onDismiss = { editingNode = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(outbounds = st.outbounds.map { if (it.id == updated.id) updated else it })
                }
                editingNode = null
            },
        )
        return
    }

    fun addSubscription(sub: com.leadaxe.aibox.app.Subscription) {
        store.update { st ->
            st.copy(
                subscriptions = st.subscriptions + sub,
                routeRules = st.routeRules.withSubscriptionRule(sub),
            )
        }
        scope.launch {
            runCatching { fetcher.fetch(sub, dnsServers = state.dnsServers, existingFor = emptyList()) }
        }
    }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Actions row: compact icon buttons; each section below carries
            // its own add affordance, so this row stays short.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showAddForm = !showAddForm }) {
                        Icon(
                            if (showAddForm) Icons.Outlined.ExpandLess else Icons.Outlined.Add,
                            contentDescription = null,
                        )
                        Text(stringResource(R.string.subs_add))
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val r = fetcher.refreshAll(state.subscriptions, state.dnsServers, existingFor = state.outbounds)
                            store.update { current ->
                                // A subscription that failed to refresh keeps
                                // its nodes: wiping them would turn one bad
                                // fetch into a lost node list. Nodes that do
                                // not come from a subscription (pasted
                                // share links) are kept as well.
                                val failed = r.failures.keys
                                val kept = current.outbounds.filter {
                                    it.subscriptionId == null || it.subscriptionId in failed
                                }
                                current.copy(
                                    outbounds = kept + r.outbounds,
                                    subscriptions = current.subscriptions.map { sub ->
                                        if (sub.id in r.failures) sub else sub.copy(lastUpdatedEpochMillis = System.currentTimeMillis())
                                    },
                                )
                            }
                            val base = if (r.failures.isEmpty())
                                context.getString(R.string.subs_refreshed, r.outbounds.size)
                            else
                                context.getString(R.string.subs_refreshed_with_failures, r.failures.size)
                            val msg = if (r.duplicates > 0)
                                "$base · " + context.getString(R.string.subs_duplicates_dropped, r.duplicates)
                            else base
                            snackbar.showSnackbar(msg)
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text(stringResource(R.string.subs_refresh_all))
                    }
                    FilledTonalButton(onClick = { pasteDialog = true }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Text(stringResource(R.string.subs_paste_link))
                    }
                }
            }

            if (showAddForm) {
                item {
                    AddSubscriptionFormCard(
                        state = state,
                        presetGroupId = addDialogFolderId,
                        onDismiss = {
                            showAddForm = false
                            addDialogFolderId = null
                        },
                        onAdd = { sub -> addSubscription(sub) },
                    )
                }
            }


            // ----- outbound groups (the tab's namesake, expanded by default)
            // The single built-in exit group replaced user-created outbound
            // groups (routing rules now filter nodes directly), so the group
            // editor UI is gone — its load-balance settings moved to
            // Settings -> Load balancing.
            // Paste link lives with the groups: a pasted source becomes a
            // subscription entry grouped with the panel imports.
            item {
                FilledTonalButton(onClick = { pasteDialog = true }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Text(stringResource(R.string.subs_paste_link))
                }
            }


            // ----- multiplex, next to the nodes it affects
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_mux),
                    expanded = muxExpanded,
                    onToggle = { muxExpanded = !muxExpanded },
                    subtitle = if (state.muxEnabled) state.muxProtocol else stringResource(R.string.settings_mux_disabled_short),
                ) {
                    MuxSettingsContent(state = state, onChange = { reducer -> store.update(reducer) })
                }
            }

            // ----- node health probing (leastPing-style scheduled url-test)
            item {
                CollapsibleSection(
                    title = stringResource(R.string.probe_section),
                    expanded = probeExpanded,
                    onToggle = { probeExpanded = !probeExpanded },
                    subtitle = if (state.healthCheckIntervalMinutes > 0) {
                        stringResource(R.string.probe_every_minutes, state.healthCheckIntervalMinutes)
                    } else {
                        stringResource(R.string.probe_off_short)
                    },
                ) {
                    HealthProbeSettingsContent(state = state, onChange = { reducer -> store.update(reducer) })
                }
            }

            // ----- nodes, collapsed by default (they can be hundreds)
            item {
                CollapsibleSection(
                    title = stringResource(R.string.subs_section_nodes_title),
                    expanded = nodesExpanded,
                    onToggle = { nodesExpanded = !nodesExpanded },
                    count = state.outbounds.size,
                    subtitle = stringResource(R.string.subs_section_nodes_short),
                ) {
                    if (state.outbounds.isNotEmpty()) {
                        var showSpeedUrl by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showSpeedUrl = !showSpeedUrl }) {
                                Icon(Icons.Outlined.Link, contentDescription = null)
                                Text(stringResource(R.string.subs_speed_url_title))
                            }
                            FilledTonalButton(onClick = {
                                // Parallel probes (karing-style test panel):
                                // all requests fire at once; the :vpn process
                                // multiplexes them and snapshots stream back
                                // per group.
                                scope.launch {
                                    state.outbounds.forEach { node ->
                                        pingResults = pingResults + (node.id to PingState.Triggered)
                                        relay.requestPing(node.id, node.tag, state.speedTestUrl)
                                    }
                                }
                            }) { Text(stringResource(R.string.subs_ping_all)) }
                        }
                        if (showSpeedUrl) {
                            StringField(
                                label = stringResource(R.string.subs_speed_url_title),
                                value = state.speedTestUrl,
                                onValueChange = { v -> store.update { it.copy(speedTestUrl = v) } },
                                placeholder = "https://cp.cloudflare.com/generate_204",
                            )
                        }
                    }
                    val displayNodes = state.outbounds.sortedWith(
                        compareBy { node -> (pingResults[node.id] as? PingState.Ok)?.delayMillis ?: Int.MAX_VALUE },
                    )
                    // Region grouping (karing-style): nodes sharing a base
                    // name (flag+region prefix before trailing counters) fold
                    // into one header row "region ×N"; tapping unfolds them.
                    val groups = displayNodes
                        .groupBy { it.name.substringBeforeLast(' ').ifBlank { it.name } }
                    groups.forEach { (region, nodes) ->
                        var regionOpen by remember(region) { mutableStateOf(false) }
                        if (nodes.size == 1) {
                            nodes.forEach { node ->
                                NodeRow(
                                    node = node,
                                    selected = node.tag == state.selectedOutbound,
                                    pingState = pingResults[node.id],
                                    onSelect = { selected -> store.update { it.copy(selectedOutbound = selected) } },
                                    onPing = {
                                        scope.launch {
                                            pingResults = pingResults + (node.id to PingState.Triggered)
                                            relay.requestPing(node.id, node.tag, state.speedTestUrl)
                                        }
                                    },
                                    onEdit = { editingNode = node },
                                )
                            }
                        } else {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { regionOpen = !regionOpen }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "$region ✖️${nodes.size}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        if (regionOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (regionOpen) {
                                nodes.forEach { node ->
                                    NodeRow(
                                        node = node,
                                        selected = node.tag == state.selectedOutbound,
                                        pingState = pingResults[node.id],
                                        onSelect = { selected -> store.update { it.copy(selectedOutbound = selected) } },
                                        onPing = {
                                            scope.launch {
                                                pingResults = pingResults + (node.id to PingState.Triggered)
                                                relay.requestPing(node.id, node.tag, state.speedTestUrl)
                                            }
                                        },
                                        onEdit = { editingNode = node },
                                    )
                                }
                            }
                        }
                    }
                    if (state.outbounds.isEmpty()) {
                        Text(
                            stringResource(R.string.subs_nodes_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ----- sources (subscriptions), collapsed by default
            item {
                CollapsibleSection(
                    title = stringResource(R.string.subs_section_subscriptions_title),
                    expanded = sourcesExpanded,
                    onToggle = { sourcesExpanded = !sourcesExpanded },
                    count = state.subscriptions.size,
                    subtitle = stringResource(R.string.subs_section_sources_short),
                ) {
                    state.subscriptions.forEach { sub ->
                        SubscriptionRow(
                            sub = sub,
                            state = state,
                            onEdit = { editingSubscription = sub },
                            onDelete = {
                                store.update { st ->
                                    st.copy(
                                        subscriptions = st.subscriptions.filterNot { it.id == sub.id },
                                        outbounds = st.outbounds.filterNot { it.subscriptionId == sub.id },
                                        routeRules = st.routeRules.withSubscriptionRule(
                                            sub.copy(routeBySuffix = false),
                                        ),
                                    )
                                }
                            },
                            onRefresh = {
                                scope.launch {
                                    runCatching { fetcher.fetch(sub, dnsServers = state.dnsServers, existingFor = state.outbounds) }
                                        .onSuccess { r ->
                                            store.update { st ->
                                                st.copy(
                                                    outbounds = (st.outbounds.filterNot { it.subscriptionId == sub.id } + r.outbounds),
                                                    subscriptions = st.subscriptions.map { it.takeIf { s -> s.id != sub.id } ?: sub.copy(lastUpdatedEpochMillis = System.currentTimeMillis()) },
                                                )
                                            }
                                            snackbar.showSnackbar(context.getString(R.string.subs_nodes_added, sub.name, r.outbounds.size))
                                        }
                                        .onFailure { snackbar.showSnackbar(context.getString(R.string.subs_fetch_failed, sub.name, it.message ?: "fetch failed")) }
                                }
                            },
                        )
                    }
                    FilledTonalButton(onClick = { creatingFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                        Text(stringResource(R.string.subs_group_add))
                    }
                }
            }

            // ----- folders (presentational grouping of subscriptions)
            if (state.subscriptionGroups.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = stringResource(R.string.subs_groups_section_title),
                        expanded = foldersExpanded,
                        onToggle = { foldersExpanded = !foldersExpanded },
                        count = state.subscriptionGroups.size,
                    ) {
                        state.subscriptionGroups.forEach { folder ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        folder.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        stringResource(R.string.subs_folder_count, state.subscriptions.count { it.groupId == folder.id }),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                IconButton(onClick = {
                                    // Open the add dialog pre-filed into this
                                    // folder: a folder card is where a user
                                    // naturally wants to put a new source.
                                    addDialogFolderId = folder.id
                                    showAddForm = true
                                }) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = stringResource(R.string.subs_folder_add_url),
                                    )
                                }
                                IconButton(onClick = {
                                    store.update { st ->
                                        st.copy(
                                            subscriptionGroups = st.subscriptionGroups.filterNot { it.id == folder.id },
                                            // Orphaned subscriptions become ungrouped rather than disappearing.
                                            subscriptions = st.subscriptions.map {
                                                if (it.groupId == folder.id) it.copy(groupId = null) else it
                                            },
                                        )
                                    }
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { Snackbar(snackbarData = it) }
    }



    if (pasteDialog) {
        PasteLinkDialog(
            onDismiss = { pasteDialog = false },
            onAdd = { raw ->
                val parsed = ShareLinkParser.parseMany(raw)
                val ok = parsed.mapNotNull { res ->
                    when (res) {
                        is ShareLinkParser.Result.Ok -> OutboundProfile(
                            id = UUID.randomUUID().toString(),
                            name = res.name,
                            type = res.type,
                            config = res.config,
                        )
                        is ShareLinkParser.Result.Err -> null
                    }
                }
                val errs = parsed.size - ok.size
                store.update { st -> st.copy(outbounds = st.outbounds + ok) }
                scope.launch { snackbar.showSnackbar(context.getString(R.string.subs_paste_result, ok.size, errs)) }
                pasteDialog = false
            },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = stringResource(R.string.subs_group_add),
            label = stringResource(R.string.subs_group_name),
            onDismiss = { creatingFolder = false },
            onConfirm = { folderName ->
                store.update { st ->
                    st.copy(
                        subscriptionGroups = st.subscriptionGroups + com.leadaxe.aibox.app.SubscriptionGroup(
                            id = UUID.randomUUID().toString(),
                            name = folderName,
                        ),
                    )
                }
                creatingFolder = false
            },
        )
    }

    if (creatingGroup) {
        GroupEditor(
            initial = null,
            state = state,
            onDismiss = { creatingGroup = false },
            onSave = { group ->
                store.update { st -> st.copy(outboundGroups = st.outboundGroups + group) }
                creatingGroup = false
            },
        )
    }
    editingGroup?.let { group ->
        GroupEditor(
            initial = group,
            state = state,
            onDismiss = { editingGroup = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(outboundGroups = st.outboundGroups.map { if (it.id == updated.id) updated else it })
                }
                editingGroup = null
            },
        )
    }
    editingSubscription?.let { sub ->
        EditSubscriptionDialog(
            state = state,
            initial = sub,
            onDismiss = { editingSubscription = null },
            onSave = { updated ->
                store.update { st ->
                    st.copy(
                        subscriptions = st.subscriptions.map { if (it.id == updated.id) updated else it },
                        // Re-materialise the managed suffix rule: the URL,
                        // fetch mode or the toggle may all have changed.
                        routeRules = st.routeRules.withSubscriptionRule(updated),
                    )
                }
                editingSubscription = null
                // The link or the update mode changed — refetch so the node
                // list matches what the user just configured, instead of
                // silently keeping nodes from the old URL. Replaces this
                // subscription's nodes only.
                scope.launch {
                    runCatching { fetcher.fetch(updated, dnsServers = state.dnsServers, existingFor = state.outbounds) }
                        .onSuccess { r ->
                            store.update { st ->
                                st.copy(
                                    outbounds = st.outbounds.filterNot { it.subscriptionId == updated.id } + r.outbounds,
                                    subscriptions = st.subscriptions.map {
                                        if (it.id == updated.id) it.copy(lastUpdatedEpochMillis = System.currentTimeMillis()) else it
                                    },
                                )
                            }
                            snackbar.showSnackbar(
                                context.getString(
                                    R.string.subs_nodes_added,
                                    updated.name.ifBlank { updated.url },
                                    r.outbounds.size,
                                ),
                            )
                        }
                        .onFailure {
                            snackbar.showSnackbar(
                                context.getString(
                                    R.string.subs_fetch_failed,
                                    updated.name.ifBlank { updated.url },
                                    it.message ?: "fetch failed",
                                ),
                            )
                        }
                }
            },
        )
    }
}

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    state: com.leadaxe.aibox.app.AppState,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name.ifBlank { sub.url }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub.url, style = MaterialTheme.typography.bodySmall)
                // Node count + last update: the first thing to look at when
                // a refresh "does nothing" — it tells apart "the fetch
                // failed" from "the panel returned nothing new".
                val nodes = state.outbounds.count { it.subscriptionId == sub.id }
                val updated = if (sub.lastUpdatedEpochMillis > 0) {
                    stringResource(
                        R.string.subs_last_updated,
                        android.text.format.DateFormat.format(
                            "yyyy-MM-dd HH:mm",
                            sub.lastUpdatedEpochMillis,
                        ).toString(),
                    )
                } else {
                    stringResource(R.string.subs_never_updated)
                }
                Text(
                    stringResource(R.string.subs_nodes_count, nodes) + " · " + updated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.subs_refresh))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.subs_delete))
            }
        }
    }
}

/**
 * Edits a subscription's fetch behaviour. Nodes are re-materialised on the
 * next refresh, so toggling ECH here takes effect after 刷新.
 */
@Composable
private fun EditSubscriptionDialog(
    state: com.leadaxe.aibox.app.AppState,
    initial: Subscription,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var fetchVia by remember { mutableStateOf(initial.fetchVia) }
    var resolver by remember { mutableStateOf(initial.dnsServer) }
    var customResolver by remember { mutableStateOf(initial.customDnsServer) }
    var routeBySuffix by remember { mutableStateOf(initial.routeBySuffix) }
    var enableEch by remember { mutableStateOf(initial.enableEch) }
    var echQueryServerName by remember { mutableStateOf(initial.echQueryServerName) }
    var echConfig by remember { mutableStateOf(initial.echConfig) }

    val resolverOptions = remember(state.dnsServers) {
        state.dnsServers.filter { it.type == "https" || it.type == "h3" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_edit)) },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.subs_name),
                    value = name,
                    onValueChange = { name = it },
                )
                // The URL is editable: panels rotate the subscription token
                // without changing anything else, and re-adding the whole
                // subscription just to paste a new link loses the nodes the
                // user already has selected.
                StringField(
                    label = stringResource(R.string.subs_url),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://panel.example/sub",
                    supporting = stringResource(R.string.subs_url_edit_hint),
                )
                Text(
                    stringResource(R.string.subs_update_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SingleChoiceChips(
                    label = stringResource(R.string.subs_fetch_via),
                    options = com.leadaxe.aibox.app.FetchViaOptions,
                    selected = fetchVia,
                    onSelect = { fetchVia = it },
                    display = {
                        when (it) {
                            com.leadaxe.aibox.app.FetchViaDirect -> stringResource(R.string.subs_fetch_via_direct)
                            com.leadaxe.aibox.app.FetchViaProxy -> stringResource(R.string.subs_fetch_via_proxy)
                            else -> stringResource(R.string.subs_fetch_via_auto)
                        }
                    },
                )
                Text(
                    stringResource(R.string.subs_fetch_via_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResolverPicker(
                    allServers = state.dnsServers,
                    selectedTag = resolver,
                    custom = customResolver,
                    onSelectTag = { resolver = it; customResolver = "" },
                    onCustom = { customResolver = it; resolver = "" },
                )
                SwitchRow(
                    label = stringResource(R.string.subs_route_by_suffix),
                    supporting = stringResource(R.string.subs_route_by_suffix_desc),
                    checked = routeBySuffix,
                    onCheckedChange = { routeBySuffix = it },
                )
                SwitchRow(
                    label = stringResource(R.string.subs_ech),
                    supporting = stringResource(R.string.subs_ech_desc),
                    checked = enableEch,
                    onCheckedChange = { enableEch = it },
                )
                if (enableEch) {
                    StringField(
                        label = stringResource(R.string.subs_ech_query_name),
                        value = echQueryServerName,
                        onValueChange = { echQueryServerName = it },
                        placeholder = stringResource(R.string.subs_ech_query_name_hint),
                    )
                    StringField(
                        label = stringResource(R.string.subs_ech_config),
                        value = echConfig,
                        onValueChange = { echConfig = it },
                        minLines = 3,
                        maxLines = 6,
                        supporting = stringResource(R.string.subs_ech_config_hint),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = url.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { url },
                            url = url.trim(),
                            fetchVia = fetchVia,
                            dnsServer = resolver,
                            customDnsServer = customResolver,
                            routeBySuffix = routeBySuffix,
                            enableEch = enableEch,
                            echQueryServerName = echQueryServerName,
                            echConfig = echConfig,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Subscription DNS-resolver picker. Collapsed by default: one label chip
 * showing the current choice (default = the tunnel's DNS, i.e. the
 * subscription's host is resolved by the rules' DNS chain — including the
 * fallback). Tapping expands to all saved DNS servers plus a custom-entry
 * tail. DoH-only servers are marked, but any server is selectable —
 * non-DoH ones simply resolve via the system resolver.
 */
@Composable
private fun ResolverPicker(
    allServers: List<com.leadaxe.aibox.app.DnsServerState>,
    selectedTag: String,
    custom: String,
    onSelectTag: (String) -> Unit,
    onCustom: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editingCustom by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.subs_resolver),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    custom.isNotBlank() -> custom
                    selectedTag.isNotBlank() ->
                        allServers.firstOrNull { it.tag == selectedTag }?.name?.ifBlank { selectedTag } ?: selectedTag
                    else -> stringResource(R.string.subs_resolver_follow)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            FilterChip(
                selected = selectedTag.isBlank() && custom.isBlank(),
                onClick = { onSelectTag("") },
                label = { Text(stringResource(R.string.subs_resolver_follow)) },
            )
            allServers.forEach { server ->
                FilterChip(
                    selected = selectedTag == server.tag,
                    onClick = { onSelectTag(server.tag) },
                    label = {
                        Text(
                            (server.name.ifBlank { server.tag }) +
                                if (server.type == "https" || server.type == "h3") " (DoH)" else "",
                        )
                    },
                )
            }
            FilterChip(
                selected = custom.isNotBlank() || editingCustom,
                onClick = { editingCustom = !editingCustom },
                label = { Text(stringResource(R.string.subs_resolver_custom)) },
            )
            if (editingCustom || custom.isNotBlank()) {
                StringField(
                    label = stringResource(R.string.subs_resolver_custom),
                    value = custom,
                    onValueChange = onCustom,
                    placeholder = "https://dns.google/dns-query",
                    supporting = stringResource(R.string.subs_resolver_custom_hint),
                )
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) stringResource(R.string.common_collapse)
                else stringResource(R.string.common_expand),
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: OutboundProfile,
    selected: Boolean,
    pingState: PingState?,
    onSelect: (String) -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.elevatedCardColors() else CardDefaults.outlinedCardColors(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onSelect(node.tag) })
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name.ifBlank { node.tag }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Full protocol line: type · server:port (parsed once per
                    // composition; config JSON is small).
                    val proto = remember(node.config) {
                        val obj = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(node.config)
                                .let { it as? kotlinx.serialization.json.JsonObject }
                        }.getOrNull()
                        val host = (obj?.get("server") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        val port = (obj?.get("server_port") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                        if (host.isNotBlank()) "${node.type} · $host:$port" else node.type
                    }
                    Text(proto, style = MaterialTheme.typography.bodySmall)
                    PingBadge(pingState)
                }
            }
            IconButton(
                onClick = onPing,
                enabled = pingState !is PingState.Running,
            ) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = stringResource(R.string.subs_ping),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
            }
        }
    }
}

/** Outcome of a latency probe against one outbound. */
private sealed interface PingState {
    data object Running : PingState
    /** Probe fired asynchronously; fresh delay arrives via group snapshots. */
    data object Triggered : PingState
    data class Ok(val delayMillis: Int) : PingState
    data class Failed(val reason: String) : PingState
}

/**
 * Latency badge (karing/FlClash style): a colored pill graded by RTT —
 * green under 200ms, orange under 500ms, red beyond, grey while running.
 */
@Composable
private fun PingBadge(state: PingState?) {
    if (state == null) return
    val text = when (state) {
        PingState.Running -> stringResource(R.string.subs_ping_running)
        PingState.Triggered -> stringResource(R.string.subs_ping_running)
        is PingState.Ok -> stringResource(R.string.subs_ping_result, state.delayMillis)
        is PingState.Failed -> stringResource(R.string.subs_ping_failed)
    }
    val (bg, fg) = when (val s = state) {
        is PingState.Ok -> when {
            s.delayMillis < 200 -> Color(0xFF2E7D32) to Color.White
            s.delayMillis < 500 -> Color(0xFFEF6C00) to Color.White
            else -> Color(0xFFC62828) to Color.White
        }
        is PingState.Failed -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        color = bg,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddSubscriptionFormCard(
    state: com.leadaxe.aibox.app.AppState,
    presetGroupId: String? = null,
    onDismiss: () -> Unit,
    onAdd: (com.leadaxe.aibox.app.Subscription) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var fetchVia by remember { mutableStateOf(com.leadaxe.aibox.app.FetchViaAuto) }
    var resolver by remember { mutableStateOf("") }
    var customResolver by remember { mutableStateOf("") }
    var routeBySuffix by remember { mutableStateOf(false) }
    var deduplicate by remember { mutableStateOf(true) }
    var userAgent by remember { mutableStateOf(com.leadaxe.aibox.app.UserAgentSingBox) }
    var tlsFingerprint by remember { mutableStateOf(com.leadaxe.aibox.app.FingerprintChrome) }
    var maskHwid by remember { mutableStateOf(true) }
    var enableEch by remember { mutableStateOf(false) }
    var echQueryServerName by remember { mutableStateOf("") }
    var echConfig by remember { mutableStateOf("") }
    var updateInterval by remember { mutableStateOf(0) }
    // Pre-filed when the user opened the dialog from a folder card.
    var groupId by remember(presetGroupId) { mutableStateOf(presetGroupId) }

    // Only DoH-capable servers can be used for the pinned-resolver path.
    val resolverOptions = remember(state.dnsServers) {
        state.dnsServers.filter { it.type == "https" || it.type == "h3" }
    }

        // Inline expanding form card: no dialog. The outer LazyColumn owns
    // scrolling; this is a plain card at the top of the list.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.subs_add),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            FormBody {
                StringField(
                    label = stringResource(R.string.subs_name_optional),
                    value = name,
                    onValueChange = { name = it },
                )
                StringField(
                    label = stringResource(R.string.subs_url),
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://panel.example/sub",
                )
                SingleChoiceChips(
                    label = stringResource(R.string.subs_fetch_via),
                    options = com.leadaxe.aibox.app.FetchViaOptions,
                    selected = fetchVia,
                    onSelect = { fetchVia = it },
                    display = {
                        when (it) {
                            com.leadaxe.aibox.app.FetchViaDirect -> stringResource(R.string.subs_fetch_via_direct)
                            com.leadaxe.aibox.app.FetchViaProxy -> stringResource(R.string.subs_fetch_via_proxy)
                            else -> stringResource(R.string.subs_fetch_via_auto)
                        }
                    },
                )
                ResolverPicker(
                    allServers = state.dnsServers,
                    selectedTag = resolver,
                    custom = customResolver,
                    onSelectTag = { resolver = it; customResolver = "" },
                    onCustom = { customResolver = it; resolver = "" },
                )
                if (resolverOptions.isEmpty()) {
                    Text(
                        stringResource(R.string.subs_resolver_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.subs_route_by_suffix),
                    supporting = stringResource(R.string.subs_route_by_suffix_desc),
                    checked = routeBySuffix,
                    onCheckedChange = { routeBySuffix = it },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.subs_update_interval),
                    options = com.leadaxe.aibox.app.UpdateIntervalOptions.map { it.first.toString() },
                    selected = updateInterval.toString(),
                    onSelect = { updateInterval = it.toIntOrNull() ?: 0 },
                    display = { value ->
                        com.leadaxe.aibox.app.UpdateIntervalOptions
                            .firstOrNull { it.first.toString() == value }
                            ?.second
                            ?.let { stringResource(it) }
                            ?: value
                    },
                )
                SwitchRow(
                    label = stringResource(R.string.subs_deduplicate),
                    supporting = stringResource(R.string.subs_deduplicate_desc),
                    checked = deduplicate,
                    onCheckedChange = { deduplicate = it },
                )
                // ----- header masking -----
                // Some panels serve a different (or empty) node list per
                // client, so the fetch can impersonate a mainstream client.
                Text(
                    stringResource(R.string.subs_http_masking),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SingleChoiceChips(
                    label = stringResource(R.string.subs_user_agent),
                    options = com.leadaxe.aibox.app.UserAgentOptions,
                    selected = userAgent,
                    onSelect = { userAgent = it },
                    display = { choice ->
                        when (choice) {
                            com.leadaxe.aibox.app.UserAgentMihomo -> stringResource(R.string.ua_mihomo)
                            com.leadaxe.aibox.app.UserAgentFlClash -> stringResource(R.string.ua_flclash)
                            com.leadaxe.aibox.app.UserAgentV2rayNG -> stringResource(R.string.ua_v2rayng)
                            com.leadaxe.aibox.app.UserAgentClashMeta -> stringResource(R.string.ua_clashmeta)
                            else -> stringResource(R.string.ua_singbox)
                        }
                    },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.subs_tls_fingerprint),
                    options = com.leadaxe.aibox.app.FingerprintOptions,
                    selected = tlsFingerprint,
                    onSelect = { tlsFingerprint = it },
                    display = { fp ->
                        when (fp) {
                            com.leadaxe.aibox.app.FingerprintChrome -> stringResource(R.string.fp_chrome)
                            com.leadaxe.aibox.app.FingerprintIOS -> stringResource(R.string.fp_ios)
                            com.leadaxe.aibox.app.FingerprintFirefox -> stringResource(R.string.fp_firefox)
                            com.leadaxe.aibox.app.FingerprintEdge -> stringResource(R.string.fp_edge)
                            else -> stringResource(R.string.fp_auto)
                        }
                    },
                )
                SwitchRow(
                    label = stringResource(R.string.subs_mask_hwid),
                    supporting = stringResource(R.string.subs_mask_hwid_desc),
                    checked = maskHwid,
                    onCheckedChange = { maskHwid = it },
                )
                SwitchRow(
                    label = stringResource(R.string.subs_ech),
                    supporting = stringResource(R.string.subs_ech_desc),
                    checked = enableEch,
                    onCheckedChange = { enableEch = it },
                )
                if (enableEch) {
                    StringField(
                        label = stringResource(R.string.subs_ech_query_name),
                        value = echQueryServerName,
                        onValueChange = { echQueryServerName = it },
                        placeholder = stringResource(R.string.subs_ech_query_name_hint),
                    )
                    StringField(
                        label = stringResource(R.string.subs_ech_config),
                        value = echConfig,
                        onValueChange = { echConfig = it },
                        minLines = 3,
                        maxLines = 6,
                        supporting = stringResource(R.string.subs_ech_config_hint),
                    )
                }
                if (state.subscriptionGroups.isNotEmpty()) {
                    SingleChoiceChips(
                        label = stringResource(R.string.subs_group),
                        options = listOf("") + state.subscriptionGroups.map { g -> g.id },
                        selected = groupId ?: "",
                        onSelect = { groupId = it.ifBlank { null } },
                        display = { id ->
                            if (id.isEmpty()) stringResource(R.string.subs_group_none)
                            else state.subscriptionGroups.firstOrNull { g -> g.id == id }?.name ?: id
                        },
                    )
                }
                    }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.subs_cancel))
                }
                Button(
                    onClick = {
                        onAdd(
                            com.leadaxe.aibox.app.Subscription(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                url = url,
                                groupId = groupId,
                                fetchVia = fetchVia,
                                dnsServer = resolver,
                                customDnsServer = customResolver,
                                routeBySuffix = routeBySuffix,
                                deduplicate = deduplicate,
                                userAgent = userAgent,
                                tlsFingerprint = tlsFingerprint,
                                maskHwid = maskHwid,
                                enableEch = enableEch,
                                echQueryServerName = echQueryServerName,
                                echConfig = echConfig,
                                updateIntervalHours = updateInterval,
                            ),
                        )
                    },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.subs_add_action))
                }
            }
        }
    }
}


@Composable
private fun PasteLinkDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.subs_paste_hint))
                OutlinedTextField(
                    value = raw, onValueChange = { raw = it },
                    label = { Text(stringResource(R.string.subs_paste_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(raw) }, enabled = raw.isNotBlank()) { Text(stringResource(R.string.subs_add_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subs_cancel)) } },
    )
}

// ----------------------------------------------------------- outbound groups

/** Minimal single-field dialog reused by the folder creator. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun GroupRow(
    group: com.leadaxe.aibox.app.OutboundGroup,
    state: com.leadaxe.aibox.app.AppState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.name.ifBlank { group.tag },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val kindLabel = if (group.kind == com.leadaxe.aibox.app.OutboundGroup.KindSelector)
                    stringResource(R.string.groups_kind_selector)
                else
                    stringResource(R.string.groups_kind_urltest)
                val modeLabel = when {
                    group.kind != com.leadaxe.aibox.app.OutboundGroup.KindUrlTest -> ""
                    group.mode == com.leadaxe.aibox.app.OutboundGroup.ModeRoundRobin ->
                        " · " + stringResource(R.string.groups_mode_rr)
                    group.mode == com.leadaxe.aibox.app.OutboundGroup.ModeFallback ->
                        " · " + stringResource(R.string.groups_mode_fallback)
                    else -> " · " + stringResource(R.string.groups_mode_least)
                }
                Text(
                    "$kindLabel$modeLabel · ${group.members.size} member(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun GroupEditor(
    initial: com.leadaxe.aibox.app.OutboundGroup?,
    state: com.leadaxe.aibox.app.AppState,
    onDismiss: () -> Unit,
    onSave: (com.leadaxe.aibox.app.OutboundGroup) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: com.leadaxe.aibox.app.OutboundGroup.KindUrlTest) }
    var members by remember { mutableStateOf(initial?.members ?: emptyList()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var interval by remember { mutableStateOf(initial?.interval.orEmpty()) }
    var idleTimeout by remember { mutableStateOf(initial?.idleTimeout.orEmpty()) }
    var includeRegex by remember { mutableStateOf(initial?.includeRegex.orEmpty()) }
    var excludeRegex by remember { mutableStateOf(initial?.excludeRegex.orEmpty()) }
    var lbStrategy by remember { mutableStateOf(initial?.lbStrategy.orEmpty()) }
    var lbTtl by remember { mutableStateOf(initial?.lbTtl.orEmpty()) }
    var tolerance by remember { mutableStateOf((initial?.tolerance ?: 0).toString()) }
    var unifiedDelay by remember { mutableStateOf(initial?.unifiedDelay ?: false) }
    var mode by remember { mutableStateOf(initial?.mode ?: com.leadaxe.aibox.app.OutboundGroup.ModeLeastTest) }
    var pool by remember { mutableStateOf((initial?.pool ?: 0).toString()) }
    var poolTolerance by remember { mutableStateOf((initial?.poolTolerance ?: 0).toString()) }
    var stickyHash by remember { mutableStateOf(initial?.stickyHash ?: com.leadaxe.aibox.app.defaultStickyHash()) }
    var selected by remember { mutableStateOf(initial?.selected.orEmpty()) }

    val nodeTags = remember(state.outbounds) { state.outbounds.map { it.tag } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.groups_new else R.string.groups_edit,
                ),
            )
        },
        text = {
            FormBody {
                StringField(
                    label = stringResource(R.string.groups_name),
                    value = name,
                    onValueChange = { name = it },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.groups_kind),
                    options = com.leadaxe.aibox.app.OutboundGroupKinds,
                    selected = kind,
                    onSelect = { kind = it },
                    display = {
                        when (it) {
                            com.leadaxe.aibox.app.OutboundGroup.KindSelector ->
                                stringResource(R.string.groups_kind_selector)
                            else -> stringResource(R.string.groups_kind_urltest)
                        }
                    },
                )
                // Load-balance sub-mode: only meaningful for urltest groups —
                // the compiler emits a loadbalance outbound when a strategy
                // is picked.
                if (kind == com.leadaxe.aibox.app.OutboundGroup.KindUrlTest) {
                    SingleChoiceChips(
                        label = stringResource(R.string.groups_kind_loadbalance),
                        options = listOf("") + com.leadaxe.aibox.app.LbStrategies,
                        selected = lbStrategy,
                        onSelect = { lbStrategy = it },
                        display = {
                            when (it) {
                                com.leadaxe.aibox.app.LbStrategyRoundRobin ->
                                    stringResource(R.string.groups_lb_round_robin)
                                com.leadaxe.aibox.app.LbStrategyConsistentHashing ->
                                    stringResource(R.string.groups_lb_hashing)
                                com.leadaxe.aibox.app.LbStrategyStickySessions ->
                                    stringResource(R.string.groups_lb_sticky)
                                else -> stringResource(R.string.groups_lb_none)
                            }
                        },
                    )
                }
                MultiChoiceChips(
                    label = stringResource(R.string.groups_members),
                    options = nodeTags,
                    selected = members,
                    onToggle = { tag -> members = if (tag in members) members - tag else members + tag },
                    display = { tag ->
                        state.outbounds.firstOrNull { it.tag == tag }?.name?.ifBlank { tag } ?: tag
                    },
                    selectAllAction = {
                        members = if (members.containsAll(nodeTags)) emptyList() else nodeTags.toList()
                    },
                )
                StringField(
                    label = stringResource(R.string.groups_alias_include),
                    value = includeRegex,
                    onValueChange = { includeRegex = it },
                    supporting = stringResource(R.string.groups_alias_hint),
                )
                StringField(
                    label = stringResource(R.string.groups_alias_exclude),
                    value = excludeRegex,
                    onValueChange = { excludeRegex = it },
                )
                if (state.outbounds.isEmpty()) {
                    Text(
                        stringResource(R.string.groups_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind == com.leadaxe.aibox.app.OutboundGroup.KindSelector) {
                    SingleChoiceChips(
                        label = stringResource(R.string.groups_selected),
                        options = listOf("") + members,
                        selected = selected,
                        onSelect = { selected = it },
                        display = { tag ->
                            if (tag.isEmpty()) stringResource(R.string.groups_first_member)
                            else state.outbounds.firstOrNull { it.tag == tag }?.name?.ifBlank { tag } ?: tag
                        },
                    )
                } else {
                    StringField(
                        label = stringResource(R.string.groups_probe_url),
                        value = url,
                        onValueChange = { url = it },
                        placeholder = state.speedTestUrl,
                    )
                    StringField(
                        label = stringResource(R.string.groups_interval),
                        value = interval,
                        onValueChange = { interval = it },
                        placeholder = com.leadaxe.aibox.app.defaultUrlTestInterval(),
                        supporting = stringResource(R.string.groups_interval_hint),
                    )
                    if (lbStrategy.isNotBlank()) {
                        StringField(
                            label = stringResource(R.string.groups_lb_ttl),
                            value = lbTtl,
                            onValueChange = { lbTtl = it },
                            placeholder = "1h",
                            supporting = stringResource(R.string.groups_lb_ttl_hint),
                        )
                    }
                    StringField(
                        label = stringResource(R.string.groups_idle_timeout),
                        value = idleTimeout,
                        onValueChange = { idleTimeout = it },
                        placeholder = "30m",
                        supporting = stringResource(R.string.groups_idle_timeout_hint),
                    )
                    StringField(
                        label = stringResource(R.string.groups_tolerance),
                        value = tolerance,
                        onValueChange = { tolerance = it.filter { c -> c.isDigit() } },
                        placeholder = "50",
                    )
                    SwitchRow(
                        label = stringResource(R.string.groups_unified_delay),
                        supporting = stringResource(R.string.groups_unified_delay_desc),
                        checked = unifiedDelay,
                        onCheckedChange = { unifiedDelay = it },
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.groups_mode),
                        options = com.leadaxe.aibox.app.OutboundGroupModes,
                        selected = mode,
                        onSelect = { mode = it },
                        display = {
                            if (it == com.leadaxe.aibox.app.OutboundGroup.ModeRoundRobin)
                                stringResource(R.string.groups_mode_rr)
                            else if (it == com.leadaxe.aibox.app.OutboundGroup.ModeFallback)
                                stringResource(R.string.groups_mode_fallback)
                            else stringResource(R.string.groups_mode_least)
                        },
                    )
                    if (mode == com.leadaxe.aibox.app.OutboundGroup.ModeRoundRobin) {
                        StringField(
                            label = stringResource(R.string.groups_pool),
                            value = pool,
                            onValueChange = { pool = it.filter { c -> c.isDigit() } },
                            placeholder = "3",
                        )
                        StringField(
                            label = stringResource(R.string.groups_pool_tolerance),
                            value = poolTolerance,
                            onValueChange = { poolTolerance = it.filter { c -> c.isDigit() } },
                            placeholder = "0",
                        )
                        Column {
                            Text(
                                stringResource(R.string.groups_sticky),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                stringResource(R.string.groups_sticky_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MultiChoiceChips(
                            label = "",
                            options = com.leadaxe.aibox.app.StickyHashComponents,
                            selected = stickyHash,
                            onToggle = { c ->
                                stickyHash = if (c in stickyHash) stickyHash - c else stickyHash + c
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        (initial ?: com.leadaxe.aibox.app.OutboundGroup(id = UUID.randomUUID().toString())).copy(
                            name = name,
                            kind = kind,
                            members = members,
                            url = url,
                            interval = interval,
                            idleTimeout = idleTimeout,
                            includeRegex = includeRegex,
                            excludeRegex = excludeRegex,
                            lbStrategy = lbStrategy,
                            lbTtl = lbTtl,
                            tolerance = tolerance.toIntOrNull() ?: 0,
                            unifiedDelay = unifiedDelay,
                            mode = mode,
                            pool = pool.toIntOrNull() ?: 0,
                            poolTolerance = poolTolerance.toIntOrNull() ?: 0,
                            stickyHash = stickyHash,
                            selected = selected,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}