package com.leadaxe.lxbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.lxbox.LxBoxApp
import com.leadaxe.lxbox.R
import com.leadaxe.lxbox.app.OutboundProfile
import com.leadaxe.lxbox.app.Subscription
import com.leadaxe.lxbox.engine.share.ShareLinkParser
import com.leadaxe.lxbox.engine.share.SubscriptionFetcher
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun SubscriptionsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LxBoxApp
    val store = app.appStateStore
    val state by store.state.collectAsState()
    val fetcher = remember { SubscriptionFetcher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var editingGroup: com.leadaxe.lxbox.app.OutboundGroup? by remember { mutableStateOf(null) }
    var creatingGroup by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { addDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.subs_add))
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val r = fetcher.refreshAll(state.subscriptions)
                            store.update { current ->
                                current.copy(
                                    outbounds = r.outbounds,
                                    subscriptions = current.subscriptions.map { sub ->
                                        if (sub.id in r.failures) sub else sub.copy(lastUpdatedEpochMillis = System.currentTimeMillis())
                                    },
                                )
                            }
                            val msg = if (r.failures.isEmpty())
                                context.getString(R.string.subs_refreshed, r.outbounds.size)
                            else
                                context.getString(R.string.subs_refreshed_with_failures, r.failures.size)
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

            item { SectionHeader(stringResource(R.string.subs_section_subscriptions, state.subscriptions.size)) }
            items(state.subscriptions, key = { it.id }) { sub ->
                SubscriptionRow(
                    sub = sub,
                    onDelete = {
                        store.update { st ->
                            st.copy(
                                subscriptions = st.subscriptions.filterNot { it.id == sub.id },
                                outbounds = st.outbounds.filterNot { it.subscriptionId == sub.id },
                            )
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching { fetcher.fetch(sub) }
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

            item { SectionHeader(stringResource(R.string.subs_section_nodes, state.outbounds.size)) }
            items(state.outbounds, key = { it.id }) { node ->
                NodeRow(node = node, selected = node.tag == state.selectedOutbound, onSelect = { selected ->
                    store.update { it.copy(selectedOutbound = selected) }
                })
            }

            item {
                FilledTonalButton(onClick = { creatingGroup = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.groups_add))
                }
            }
            item { SectionHeader(stringResource(R.string.groups_section, state.outboundGroups.size)) }
            items(state.outboundGroups, key = { it.id }) { group ->
                GroupRow(
                    group = group,
                    state = state,
                    onEdit = { editingGroup = group },
                    onDelete = {
                        store.update { st ->
                            st.copy(
                                outboundGroups = st.outboundGroups.filterNot { it.id == group.id },
                                // Drop the selection if it pointed at the deleted group.
                                selectedOutbound = st.selectedOutbound.takeIf { it != group.tag } ?: "",
                            )
                        }
                    },
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { Snackbar(snackbarData = it) }
    }

    if (addDialog) {
        AddSubscriptionDialog(
            onDismiss = { addDialog = false },
            onAdd = { name, url ->
                val id = UUID.randomUUID().toString()
                store.update { st ->
                    st.copy(
                        subscriptions = st.subscriptions + Subscription(
                            id = id, name = name.ifBlank { url }, url = url,
                        ),
                    )
                }
                scope.launch {
                    runCatching { fetcher.fetch(Subscription(id = id, name = name, url = url)) }
                        .onSuccess { r ->
                            store.update { st ->
                                st.copy(outbounds = st.outbounds + r.outbounds)
                            }
                        }
                        .onFailure { snackbar.showSnackbar(context.getString(R.string.subs_fetch_failed, name, it.message ?: "fetch failed")) }
                }
                addDialog = false
            },
        )
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
}

@Composable
private fun SubscriptionRow(
    sub: Subscription,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name.ifBlank { sub.url }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub.url, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun NodeRow(
    node: OutboundProfile,
    selected: Boolean,
    onSelect: (String) -> Unit,
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
                Text(node.type, style = MaterialTheme.typography.bodySmall)
            }
            FilterChip(
                selected = selected,
                onClick = { onSelect(node.tag) },
                label = {
                    Text(
                        stringResource(if (selected) R.string.subs_selected else R.string.subs_tap_to_use),
                    )
                },
            )
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.subs_name)) })
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.subs_url)) })
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, url) }, enabled = url.isNotBlank()) { Text(stringResource(R.string.subs_add_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subs_cancel)) } },
    )
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

@Composable
private fun GroupRow(
    group: com.leadaxe.lxbox.app.OutboundGroup,
    state: com.leadaxe.lxbox.app.AppState,
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
                val kindLabel = if (group.kind == com.leadaxe.lxbox.app.OutboundGroup.KindSelector)
                    stringResource(R.string.groups_kind_selector)
                else
                    stringResource(R.string.groups_kind_urltest)
                val modeLabel = when {
                    group.kind != com.leadaxe.lxbox.app.OutboundGroup.KindUrlTest -> ""
                    group.mode == com.leadaxe.lxbox.app.OutboundGroup.ModeRoundRobin ->
                        " · " + stringResource(R.string.groups_mode_rr)
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
    initial: com.leadaxe.lxbox.app.OutboundGroup?,
    state: com.leadaxe.lxbox.app.AppState,
    onDismiss: () -> Unit,
    onSave: (com.leadaxe.lxbox.app.OutboundGroup) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: com.leadaxe.lxbox.app.OutboundGroup.KindUrlTest) }
    var members by remember { mutableStateOf(initial?.members ?: emptyList()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var interval by remember { mutableStateOf(initial?.interval.orEmpty()) }
    var tolerance by remember { mutableStateOf((initial?.tolerance ?: 0).toString()) }
    var mode by remember { mutableStateOf(initial?.mode ?: com.leadaxe.lxbox.app.OutboundGroup.ModeLeastTest) }
    var pool by remember { mutableStateOf((initial?.pool ?: 0).toString()) }
    var poolTolerance by remember { mutableStateOf((initial?.poolTolerance ?: 0).toString()) }
    var stickyHash by remember { mutableStateOf(initial?.stickyHash ?: com.leadaxe.lxbox.app.defaultStickyHash()) }
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
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StringField(
                    label = stringResource(R.string.groups_name),
                    value = name,
                    onValueChange = { name = it },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.groups_kind),
                    options = com.leadaxe.lxbox.app.OutboundGroupKinds,
                    selected = kind,
                    onSelect = { kind = it },
                    display = {
                        if (it == com.leadaxe.lxbox.app.OutboundGroup.KindSelector)
                            stringResource(R.string.groups_kind_selector)
                        else stringResource(R.string.groups_kind_urltest)
                    },
                )
                MultiChoiceChips(
                    label = stringResource(R.string.groups_members),
                    options = nodeTags,
                    selected = members,
                    onToggle = { tag -> members = if (tag in members) members - tag else members + tag },
                    display = { tag ->
                        state.outbounds.firstOrNull { it.tag == tag }?.name?.ifBlank { tag } ?: tag
                    },
                )
                if (state.outbounds.isEmpty()) {
                    Text(
                        stringResource(R.string.groups_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind == com.leadaxe.lxbox.app.OutboundGroup.KindSelector) {
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
                        placeholder = com.leadaxe.lxbox.app.defaultUrlTestInterval(),
                    )
                    StringField(
                        label = stringResource(R.string.groups_tolerance),
                        value = tolerance,
                        onValueChange = { tolerance = it.filter { c -> c.isDigit() } },
                        placeholder = "50",
                    )
                    SingleChoiceChips(
                        label = stringResource(R.string.groups_mode),
                        options = com.leadaxe.lxbox.app.OutboundGroupModes,
                        selected = mode,
                        onSelect = { mode = it },
                        display = {
                            if (it == com.leadaxe.lxbox.app.OutboundGroup.ModeRoundRobin)
                                stringResource(R.string.groups_mode_rr)
                            else stringResource(R.string.groups_mode_least)
                        },
                    )
                    if (mode == com.leadaxe.lxbox.app.OutboundGroup.ModeRoundRobin) {
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
                            options = com.leadaxe.lxbox.app.StickyHashComponents,
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
                        (initial ?: com.leadaxe.lxbox.app.OutboundGroup(id = UUID.randomUUID().toString())).copy(
                            name = name,
                            kind = kind,
                            members = members,
                            url = url,
                            interval = interval,
                            tolerance = tolerance.toIntOrNull() ?: 0,
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