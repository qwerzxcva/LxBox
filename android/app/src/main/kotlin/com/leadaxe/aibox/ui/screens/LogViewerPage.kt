package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.engine.vpn.AIVpnService

/**
 * Settings -> Logs: the kernel log viewer. The "view log" action asks the
 * :vpn service to export a sanitised log file (node servers, UUIDs,
 * passwords and SNI stripped); this page receives the file path over a
 * broadcast and renders it, filterable by level.
 */
@kotlinx.serialization.Serializable
private data class LogLine(val level: String, val message: String)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LogViewerPage(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var minLevel by rememberSaveable { mutableStateOf("warn") }
    var logText by rememberSaveable { mutableStateOf("") }
    val levels = listOf("debug", "info", "warn", "error")
    val minIndex = levels.indexOf(minLevel).coerceAtLeast(0)

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                val path = intent.getStringExtra("path") ?: return
                logText = runCatching { java.io.File(path).readText() }.getOrDefault("")
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(AIVpnService.BROADCAST_LOGS_EXPORTED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val lines = remember(logText) {
        logText.lines().drop(2).filter { it.isNotBlank() }
    }
    val filtered = lines.filter { line ->
        val close = line.indexOf(']')
        val lvl = if (close > 1) line.substring(1, close).lowercase() else ""
        val idx = levels.indexOf(lvl)
        idx < 0 || idx >= minIndex
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_cancel),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            context.startService(
                                android.content.Intent(
                                    context,
                                    AIVpnService::class.java,
                                ).setAction(AIVpnService.ACTION_EXPORT_LOGS),
                            )
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.logs_export),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { lvl ->
                    FilterChip(
                        selected = lvl == minLevel,
                        onClick = { minLevel = lvl },
                        label = { Text(lvl) },
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.logs_sanitised_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.hashCode() }) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
