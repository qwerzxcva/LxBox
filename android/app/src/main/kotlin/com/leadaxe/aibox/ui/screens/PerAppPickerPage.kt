package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.AppState
import com.leadaxe.aibox.app.AppStateStore

/** One installed, launchable app as shown in the per-app picker. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Full-screen per-app proxy picker (v2rayNG/FlClash style): the launchable
 * apps with a checkbox each, a text filter, select-all/none, and the
 * whitelist/blacklist mode chip. Only reachable while the experimental
 * per-app flag is on; the picked set lands in [AppState.perAppProxyPackages].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppPickerPage(
    store: AppStateStore,
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, Boolean>() }

    // Load the launchable apps once per mode change; seed the checkbox map
    // from the persisted package set.
    LaunchedEffect(Unit, state.perAppProxyWhitelist) {
        val self = context.packageName
        apps = runCatching {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != self }
                .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        picked.clear()
        state.perAppProxyPackages.forEach { picked[it] = true }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.per_app_title)) },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                }
            },
            actions = {
                TextButton(onClick = {
                    store.update {
                        it.copy(
                            perAppProxyEnabled = picked.values.any { v -> v },
                            perAppProxyPackages = picked.filterValues { v -> v }.keys.toSet(),
                        )
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.common_save)) }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.perAppProxyWhitelist,
                onClick = { store.update { it.copy(perAppProxyWhitelist = true) } },
                label = { Text(stringResource(R.string.per_app_whitelist)) },
            )
            FilterChip(
                selected = !state.perAppProxyWhitelist,
                onClick = { store.update { it.copy(perAppProxyWhitelist = false) } },
                label = { Text(stringResource(R.string.per_app_blacklist)) },
            )
            Text(
                stringResource(R.string.per_app_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    TextButton(onClick = { apps.forEach { picked[it.packageName] = true } }) {
                        Text(stringResource(R.string.per_app_select_all))
                    }
                    TextButton(onClick = { picked.clear() }) {
                        Text(stringResource(R.string.per_app_clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val filtered = apps.filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName }) { app ->
                val checked = picked[app.packageName] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picked[app.packageName] = !checked }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = { v -> picked[app.packageName] = v })
                    if (checked) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
