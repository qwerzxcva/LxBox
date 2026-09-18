package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leadaxe.aibox.AIBoxApp
import com.leadaxe.aibox.R
import com.leadaxe.aibox.app.ClashModeDirect
import com.leadaxe.aibox.app.ClashModeGlobal
import com.leadaxe.aibox.app.ClashModeRule
import com.leadaxe.aibox.app.ClashModes
import com.leadaxe.aibox.app.MainActivity
import com.leadaxe.aibox.engine.vpn.BoxController
import com.leadaxe.aibox.engine.vpn.BoxRuntimeSnapshot
import com.leadaxe.aibox.engine.vpn.BoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Composable
fun HomeScreen(
    controller: BoxController,
    onRequestVpnConsent: (android.content.Intent) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    val relay = remember { (context.applicationContext as AIBoxApp).vpnRelay }
    val appState by store.state.collectAsState()
    // The engine lives in the :vpn process; the UI sees it through the relay.
    val boxState = relay.state.collectAsState().value ?: BoxState.Idle
    val runtime = relay.runtime.collectAsState().value
    val activity = context as? MainActivity

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )

        PowerDome(
            state = boxState,
            onConnect = {
                // React to the tap before :vpn cold-starts (seconds); the
                // real Starting/Connected/Error broadcast overwrites this.
                relay.optimisticStarting()
                val intent = controller.prepareVpn(activity ?: return@PowerDome)
                if (intent != null) {
                    onRequestVpnConsent(intent)
                } else {
                    controller.startFromBackground()
                }
            },
            onDisconnect = {
                relay.optimisticStopping()
                controller.stop()
            },
        )

        ClashModeRow(
            current = appState.clashMode,
            onChange = { mode -> store.update { it.copy(clashMode = mode) } },
        )

        // The home tab is for *looking* (bettbox-style): status, traffic and
        // mode. Node selection lives in Groups → Nodes; repeating it here
        // made the page a second control surface.
        // Explicit connection state: the dial alone left the user guessing
        // whether the tunnel was actually up. A status chip states it.
        val connectedAt = (boxState as? BoxState.Connected)?.sinceEpochMillis ?: 0L
        val stateLabel = when (boxState) {
            is BoxState.Connected -> stringResource(R.string.home_status_connected)
            is BoxState.Starting -> stringResource(R.string.home_status_starting)
            is BoxState.Stopping -> stringResource(R.string.home_status_stopping)
            is BoxState.Error -> stringResource(R.string.home_status_error, boxState.message)
            else -> stringResource(R.string.home_status_idle)
        }
        val stateColor = when (boxState) {
            is BoxState.Connected -> MaterialTheme.colorScheme.primary
            is BoxState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            color = stateColor.copy(alpha = 0.14f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = stateColor,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Text(
                    text = "  $stateLabel" + if (connectedAt > 0L) {
                        " · ${formatDuration(System.currentTimeMillis() - connectedAt)}"
                    } else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = stateColor,
                )
            }
        }

        if (boxState is BoxState.Connected) {
            val via = appState.outbounds.firstOrNull { it.tag == appState.selectedOutbound }
                ?.name?.ifBlank { appState.selectedOutbound }
                ?: appState.outboundGroups.firstOrNull { it.tag == appState.selectedOutbound }
                    ?.name?.ifBlank { appState.selectedOutbound }
            if (!via.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.home_via_node, via),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Exit-IP check (v2rayNG/FlClash style): while the tunnel is up,
            // ping a plain-JSON echo endpoint THROUGH the tun and show
            // country + IP. Auto-refreshes every 5 min while connected.
            ExitIpLine()
        }

        StatusCard(state = boxState, runtime = runtime ?: BoxRuntimeSnapshot())
    }
}

/**
 * Hero control: a volumetric dome. The connected fill is a radial gradient
 * (bright core, darkened rim) inside an accent ring; while connected a soft
 * breathing halo pulses behind the dial so the tunnel reads as "alive".
 * Idle keeps the same shape but flat and quiet. The dial tracks the engine
 * state in colour the way the old PowerDial did.
 */
@Composable
private fun PowerDome(
    state: BoxState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is BoxState.Connected
    val busy = state is BoxState.Starting || state is BoxState.Stopping
    val accent = when (state) {
        is BoxState.Connected -> MaterialTheme.colorScheme.primary
        is BoxState.Error -> MaterialTheme.colorScheme.error
        BoxState.Starting, BoxState.Stopping -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        connected -> stringResource(R.string.home_disconnect)
        busy -> stringResource(R.string.home_working)
        else -> stringResource(R.string.home_connect)
    }
    val status = when (state) {
        is BoxState.Connected -> stringResource(R.string.home_status_connected)
        is BoxState.Error -> stringResource(R.string.home_status_error, state.message)
        BoxState.Starting -> stringResource(R.string.home_status_starting)
        BoxState.Stopping -> stringResource(R.string.home_status_stopping)
        else -> stringResource(R.string.home_status_idle)
    }
    // Dome fill colours are resolved outside the Canvas draw scope (composable
    // reads are not allowed inside a draw lambda).
    val idleFillTop = MaterialTheme.colorScheme.surfaceContainerHigh
    val idleFillMid = MaterialTheme.colorScheme.surfaceVariant
    val idleFillEdge = MaterialTheme.colorScheme.surface
    val onAccent = if (connected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    val glow = rememberInfiniteTransition(label = "glow")
    val glowPhase by glow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseOutCubic), RepeatMode.Reverse),
        label = "phase",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "dialScale",
    )

    Box(
        modifier = Modifier
            .size(230.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { if (!busy) (if (connected) onDisconnect else onConnect)() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val dialRadius = size.minDimension * 0.31f
            val ringRadius = dialRadius + size.minDimension * 0.035f
            // Breathing halo behind the dial — only while connected.
            if (connected) {
                val haloRadius = dialRadius * (1.35f + 0.15f * glowPhase)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.28f * (1f - glowPhase * 0.4f)), Color.Transparent),
                        center = center,
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                    center = center,
                )
            }
            // Accent ring around the dome.
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.35f), accent, accent.copy(alpha = 0.35f)),
                    center,
                ),
                radius = ringRadius,
                center = center,
                style = Stroke(width = size.minDimension * 0.010f, cap = StrokeCap.Round),
            )
            // The dome itself: the highlight sits toward the upper-left and
            // fades into a deep tint at the rim — volumetric, not a flat disc.
            val fillColors = when {
                connected -> listOf(accent, accent.copy(alpha = 0.85f), accent.copy(alpha = 0.35f))
                busy -> listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.25f), accent.copy(alpha = 0.08f))
                else -> listOf(idleFillTop, idleFillMid, idleFillEdge)
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = fillColors,
                    center = center - Offset(dialRadius * 0.25f, dialRadius * 0.30f),
                    radius = dialRadius * 1.7f,
                ),
                radius = dialRadius,
                center = center,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = label,
                modifier = Modifier.size(44.dp),
                tint = onAccent,
            )
            Spacer(Modifier.height(10.dp))
            Text(status, style = MaterialTheme.typography.labelMedium, color = onAccent.copy(alpha = 0.75f))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onAccent,
            )
        }
    }
}

/**
 * Rolling rate samples for the traffic chart. Kept outside the composable
 * so it survives recomposition; capacity 60 = one minute at 1s pushes.
 */
private val rateHistory = ArrayDeque<Pair<Long, Long>>(60)

/** FlClash-style live traffic-rate sparkline over the last minute. */
@Composable
private fun TrafficRateChart(uplink: Long, downlink: Long) {
    // Record the delta since the previous push as the current rate.
    androidx.compose.runtime.LaunchedEffect(uplink, downlink) {
        rateHistory.addLast(uplink to downlink)
        while (rateHistory.size > 60) rateHistory.removeFirst()
    }
    val upColor = MaterialTheme.colorScheme.primary
    val downColor = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (rateHistory.size < 2) return@Canvas
        val maxRate = rateHistory.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1L)
        fun x(i: Int) = size.width * i / (rateHistory.size - 1).coerceAtLeast(1).toFloat()
        fun y(v: Long) = size.height * (1f - v.toFloat() / maxRate)
        // Downlink filled area.
        val downPath = androidx.compose.ui.graphics.Path()
        val upPath = androidx.compose.ui.graphics.Path()
        rateHistory.forEachIndexed { i, (up, down) ->
            val px = x(i)
            if (i == 0) {
                downPath.moveTo(px, y(down)); upPath.moveTo(px, y(up))
            } else {
                downPath.lineTo(px, y(down)); upPath.lineTo(px, y(up))
            }
        }
        drawPath(downPath, color = downColor.copy(alpha = 0.55f), style = Stroke(2.dp.toPx()))
        drawPath(upPath, color = upColor.copy(alpha = 0.85f), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun StatusCard(
    state: BoxState,
    runtime: BoxRuntimeSnapshot,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TrafficColumn(
                    label = stringResource(R.string.home_upload),
                    bytes = runtime.uplinkBytes,
                    total = runtime.uplinkTotalBytes,
                )
                TrafficColumn(
                    label = stringResource(R.string.home_download),
                    bytes = runtime.downlinkBytes,
                    total = runtime.downlinkTotalBytes,
                )
                MetricColumn(label = stringResource(R.string.home_goroutines), value = runtime.goroutines.toString())
                MetricColumn(label = stringResource(R.string.home_memory), value = formatBytes(runtime.memoryBytes))
            }
            if (state is BoxState.Connected) {
                Spacer(Modifier.height(10.dp))
                TrafficRateChart(runtime.uplinkBytes, runtime.downlinkBytes)
            }
        }
    }
}

@Composable
private fun TrafficColumn(label: String, bytes: Long, total: Long) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatBytes(bytes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (total > 0) {
            Text(
                "Σ ${formatBytes(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ClashModeRow(
    current: String,
    onChange: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ClashModes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == current,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ClashModes.size),
                label = {
                    Text(
                        when (mode) {
                            ClashModeRule -> stringResource(R.string.home_mode_rule)
                            ClashModeGlobal -> stringResource(R.string.home_mode_global)
                            ClashModeDirect -> stringResource(R.string.home_mode_direct)
                            else -> mode
                        },
                    )
                },
            )
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = value.toDouble() / 1024.0
    var u = 0
    while (v >= 1024.0 && u < units.lastIndex) { v /= 1024.0; u++ }
    return "%.1f %s".format(v, units[u])
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/**
 * The exit IP + country of the current tunnel, v2rayNG/FlClash style. Two
 * free JSON echo endpoints are tried in order; the request rides the tun so
 * the answer reflects the proxy exit, not the local network. Refreshes every
 * five minutes while the tunnel stays connected; silently hides on failure.
 */
@Composable
private fun ExitIpLine() {
    var label by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            label = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val endpoints = listOf(
                        "https://api.ip.sb/geoip" to { obj: kotlinx.serialization.json.JsonObject ->
                            val country = (obj["country"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            val ip = (obj["ip"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            listOf(country, ip).filter { it.isNotBlank() }.joinToString(" · ")
                        },
                        "https://ipapi.co/json/" to { obj: kotlinx.serialization.json.JsonObject ->
                            val country = (obj["country_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            val ip = (obj["ip"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            listOf(country, ip).filter { it.isNotBlank() }.joinToString(" · ")
                        },
                    )
                    for ((url, extract) in endpoints) {
                        runCatching {
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 5_000
                            conn.readTimeout = 5_000
                            conn.setRequestProperty("User-Agent", "aibox/1.0")
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            conn.disconnect()
                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body)
                                .let { it as? kotlinx.serialization.json.JsonObject }
                            obj?.let(extract)?.takeIf { it.isNotBlank() }
                        }.getOrNull()?.let { return@withContext it }
                    }
                    null
                }
            }.getOrNull()
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    label?.let {
        Text(
            text = stringResource(R.string.home_exit_ip, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
