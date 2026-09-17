package com.leadaxe.aibox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import com.leadaxe.aibox.app.ColorModeDark
import com.leadaxe.aibox.app.ColorModeLight
import com.leadaxe.aibox.app.ColorModeSystem
import com.leadaxe.aibox.app.MuxProtocolH2mux
import com.leadaxe.aibox.app.MuxProtocolSmux
import com.leadaxe.aibox.app.MuxProtocolYamux
import com.leadaxe.aibox.app.MuxProtocols
import com.leadaxe.aibox.app.SingBoxLogLevels
import com.leadaxe.aibox.app.SnifferProtocolOptions

/**
 * Settings tab. Every group is a collapsible card: the tab is long enough
 * that a flat list buries the tunnel options under appearance and logging
 * noise, so sections start collapsed and open on tap.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AIBoxApp).appStateStore }
    val state by store.state.collectAsState()
    var showLogViewer by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    if (showLogViewer) {
        LogViewerPage(onDismiss = { showLogViewer = false })
        return
    }

    // Backup / restore via SAF: no storage permission needed, the user
    // picks the file. Export writes the full AppState JSON; import merges
    // a backup file back (schema-validated, unknown keys ignored).
    var backupMessage by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf(false) }
    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (uri == null) {
            backupMessage = ""
            return@rememberLauncherForActivityResult
        }
        backupError = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.exportJson().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("cannot open file")
        }.isFailure
        backupMessage = context.getString(
            if (backupError) R.string.settings_backup_failed else R.string.settings_backup_exported,
        )
    }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (uri == null) {
            backupMessage = ""
            return@rememberLauncherForActivityResult
        }
        val outcome = runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("cannot open file")
            store.importJson(text)
        }.getOrElse { it.message ?: "import failed" }
        backupError = outcome != "ok"
        backupMessage = context.getString(
            if (backupError) R.string.settings_backup_failed else R.string.settings_backup_restored,
        ) + if (backupError) " ($outcome)" else ""
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(stringResource(R.string.settings_section_appearance), initiallyExpanded = true) {
                Text(
                    stringResource(R.string.settings_color_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        label = stringResource(R.string.settings_color_system),
                        selected = state.colorMode == ColorModeSystem,
                    ) { store.update { it.copy(colorMode = ColorModeSystem) } }
                    Chip(
                        label = stringResource(R.string.settings_color_light),
                        selected = state.colorMode == ColorModeLight,
                    ) { store.update { it.copy(colorMode = ColorModeLight) } }
                    Chip(
                        label = stringResource(R.string.settings_color_dark),
                        selected = state.colorMode == ColorModeDark,
                    ) { store.update { it.copy(colorMode = ColorModeDark) } }
                }
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        label = stringResource(R.string.settings_language_system),
                        selected = state.language.isEmpty(),
                    ) { store.update { it.copy(language = "") } }
                    Chip(
                        label = stringResource(R.string.settings_language_en),
                        selected = state.language == "en",
                    ) { store.update { it.copy(language = "en") } }
                    Chip(
                        label = stringResource(R.string.settings_language_zh),
                        selected = state.language == "zh",
                    ) { store.update { it.copy(language = "zh") } }
                }
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_tunnel)) {
                SwitchRow(
                    label = stringResource(R.string.settings_ipv6),
                    supporting = stringResource(R.string.settings_ipv6_desc),
                    checked = state.enableIpv6,
                    onCheckedChange = { v -> store.update { it.copy(enableIpv6 = v) } },
                )
                if (state.enableIpv6) {
                    SingleChoiceChips(
                        label = stringResource(R.string.settings_ip_family),
                        options = listOf("prefer_ipv6", "prefer_ipv4", "ipv6_only", "ipv4_only"),
                        selected = state.ipFamilyPolicy,
                        onSelect = { v -> store.update { it.copy(ipFamilyPolicy = v) } },
                        display = {
                            when (it) {
                                "prefer_ipv4" -> stringResource(R.string.settings_prefer_ipv4)
                                "ipv6_only" -> stringResource(R.string.settings_ipv6_only)
                                "ipv4_only" -> stringResource(R.string.settings_ipv4_only)
                                else -> stringResource(R.string.settings_prefer_ipv6)
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.settings_ip_family_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.ipv6FallbackActive) {
                        Text(
                            stringResource(R.string.settings_ipv6_fallback_active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_mtu, state.tunMtu),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = state.tunMtu.toFloat(),
                    onValueChange = { v -> store.update { it.copy(tunMtu = v.toInt()) } },
                    valueRange = 1280f..9000f,
                    steps = 30,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_udp_over_tcp),
                    supporting = stringResource(R.string.settings_udp_over_tcp_desc),
                    checked = state.udpOverTcp,
                    onCheckedChange = { v -> store.update { it.copy(udpOverTcp = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_hev_tun),
                    supporting = stringResource(R.string.settings_hev_tun_desc),
                    checked = state.hevTunMode,
                    onCheckedChange = { v -> store.update { it.copy(hevTunMode = v) } },
                )
                SingleChoiceChips(
                    label = stringResource(R.string.settings_tls_fragment),
                    options = listOf("none", "record", "packet"),
                    selected = state.tlsFragmentMode,
                    onSelect = { v -> store.update { it.copy(tlsFragmentMode = v) } },
                    display = {
                        when (it) {
                            "record" -> stringResource(R.string.settings_tls_fragment_record)
                            "packet" -> stringResource(R.string.settings_tls_fragment_packet)
                            else -> stringResource(R.string.settings_tls_fragment_none)
                        }
                    },
                )
                Text(
                    stringResource(R.string.settings_tls_fragment_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // QUIC compatibility (experimental): for devices where the
                // kernel's QUIC stack misbehaves. Off by default — quic-go
                // defaults are the right choice for most devices.
                Text(
                    stringResource(R.string.settings_quic_compat),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_quic_disable_gso),
                    supporting = stringResource(R.string.settings_quic_disable_gso_desc),
                    checked = state.quicDisableGso,
                    onCheckedChange = { v -> store.update { it.copy(quicDisableGso = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_quic_disable_ecn),
                    supporting = stringResource(R.string.settings_quic_disable_ecn_desc),
                    checked = state.quicDisableEcn,
                    onCheckedChange = { v -> store.update { it.copy(quicDisableEcn = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_ntp),
                    supporting = stringResource(R.string.settings_ntp_desc),
                    checked = state.enableNtp,
                    onCheckedChange = { v -> store.update { it.copy(enableNtp = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_local_socks5),
                    supporting = stringResource(R.string.settings_local_socks5_desc),
                    checked = state.enableLocalSocks5,
                    onCheckedChange = { v -> store.update { it.copy(enableLocalSocks5 = v) } },
                )
                if (state.enableLocalSocks5) {
                    StringField(
                        label = stringResource(R.string.settings_local_socks5_port),
                        value = state.localSocks5Port.toString(),
                        onValueChange = { v ->
                            store.update { st -> st.copy(localSocks5Port = v.filter { c -> c.isDigit() }.toIntOrNull() ?: st.localSocks5Port) }
                        },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.settings_local_http),
                    supporting = stringResource(R.string.settings_local_http_desc),
                    checked = state.enableLocalHttp,
                    onCheckedChange = { v -> store.update { it.copy(enableLocalHttp = v) } },
                )
                if (state.enableLocalHttp) {
                    StringField(
                        label = stringResource(R.string.settings_local_http_port),
                        value = state.localHttpPort.toString(),
                        onValueChange = { v ->
                            store.update { st -> st.copy(localHttpPort = v.filter { c -> c.isDigit() }.toIntOrNull() ?: st.localHttpPort) }
                        },
                    )
                }
                // Per-app auto-start of the local inbounds: while a trigger
                // app has live connections, the inbounds stay on; after the
                // hold window they switch off. Saves battery vs. always-on.
                SwitchRow(
                    label = stringResource(R.string.settings_local_trigger),
                    supporting = stringResource(R.string.settings_local_trigger_desc),
                    checked = state.localProxyAutoTrigger,
                    onCheckedChange = { v -> store.update { it.copy(localProxyAutoTrigger = v) } },
                )
                if (state.localProxyAutoTrigger) {
                    ListField(
                        label = stringResource(R.string.settings_local_trigger_packages),
                        values = state.localProxyTriggerPackages.toList(),
                        onValuesChange = { v -> store.update { it.copy(localProxyTriggerPackages = v) } },
                        supporting = stringResource(R.string.settings_local_trigger_packages_hint),
                    )
                    StringField(
                        label = stringResource(R.string.settings_local_trigger_hold),
                        value = state.localProxyHoldMs.toString(),
                        onValueChange = { v ->
                            store.update { st ->
                                st.copy(localProxyHoldMs = v.filter { c -> c.isDigit() }.toLongOrNull() ?: st.localProxyHoldMs)
                            }
                        },
                        placeholder = "60000",
                        supporting = stringResource(R.string.settings_local_trigger_hold_hint),
                    )
                }
                Text(
                    stringResource(R.string.settings_tun_addresses),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StringField(
                    label = stringResource(R.string.settings_tun_ipv4),
                    value = state.tunInet4Address,
                    onValueChange = { v -> store.update { it.copy(tunInet4Address = v) } },
                    placeholder = "172.19.0.1/30",
                )
                if (state.enableIpv6) {
                    StringField(
                        label = stringResource(R.string.settings_tun_ipv6),
                        value = state.tunInet6Address,
                        onValueChange = { v -> store.update { it.copy(tunInet6Address = v) } },
                        placeholder = "fdfe:dcba:9876::1/126",
                    )
                }
                ListField(
                    label = stringResource(R.string.settings_tun_dns),
                    values = state.tunDnsAddresses,
                    onValuesChange = { v -> store.update { it.copy(tunDnsAddresses = v) } },
                    placeholder = stringResource(R.string.settings_tun_dns_hint),
                )
                Text(
                    stringResource(R.string.settings_restart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_privacy)) {
                SwitchRow(
                    label = stringResource(R.string.settings_privacy_screenshots),
                    supporting = stringResource(R.string.settings_privacy_screenshots_desc),
                    checked = state.blockScreenshots,
                    onCheckedChange = { v -> store.update { it.copy(blockScreenshots = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_privacy_insecure_dns),
                    supporting = stringResource(R.string.settings_privacy_insecure_dns_desc),
                    checked = state.allowInsecureDns,
                    onCheckedChange = { v -> store.update { it.copy(allowInsecureDns = v) } },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_backup)) {
                Text(
                    stringResource(R.string.settings_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        backupLauncher.launch(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                    type = "application/json"
                                    putExtra(
                                        android.content.Intent.EXTRA_TITLE,
                                        "aibox-backup-${android.text.format.DateFormat.format("yyyyMMdd-HHmm", System.currentTimeMillis())}.json",
                                    )
                                },
                                null,
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.settings_backup_export))
                    }
                    FilledTonalButton(onClick = {
                        restoreLauncher.launch(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                    type = "application/json"
                                },
                                null,
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.settings_backup_import))
                    }
                }
                if (backupMessage.isNotBlank()) {
                    Text(
                        backupMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (backupError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_network)) {
                SingleChoiceChips(
                    label = stringResource(R.string.settings_ssid_policy),
                    options = listOf("", "blacklist", "whitelist"),
                    selected = state.ssidPolicyMode,
                    onSelect = { v -> store.update { it.copy(ssidPolicyMode = v) } },
                    display = {
                        when (it) {
                            "blacklist" -> stringResource(R.string.settings_ssid_blacklist)
                            "whitelist" -> stringResource(R.string.settings_ssid_whitelist)
                            else -> stringResource(R.string.settings_ssid_off)
                        }
                    },
                )
                if (state.ssidPolicyMode.isNotBlank()) {
                    ListField(
                        label = stringResource(R.string.settings_ssid_list),
                        values = state.ssidPolicyList,
                        onValuesChange = { v -> store.update { it.copy(ssidPolicyList = v) } },
                        supporting = stringResource(R.string.settings_ssid_list_hint),
                    )
                }
                StringField(
                    label = stringResource(R.string.settings_github_mirror),
                    value = state.githubMirror,
                    onValueChange = { v -> store.update { it.copy(githubMirror = v.trim()) } },
                    placeholder = "https://ghfast.top",
                    supporting = stringResource(R.string.settings_github_mirror_hint),
                )
                StringField(
                    label = stringResource(R.string.settings_github_token),
                    value = state.githubToken,
                    onValueChange = { v -> store.update { it.copy(githubToken = v.trim()) } },
                    placeholder = "ghp_… (optional)",
                    supporting = stringResource(R.string.settings_github_token_hint),
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_logging)) {
                FilledTonalButton(onClick = {
                    context.startService(
                        android.content.Intent(context, com.leadaxe.aibox.engine.vpn.AIVpnService::class.java)
                            .setAction(com.leadaxe.aibox.engine.vpn.AIVpnService.ACTION_EXPORT_LOGS),
                    )
                    showLogViewer = true
                }) {
                    Text(stringResource(R.string.logs_open))
                }
                SingleChoiceChips(
                    label = stringResource(R.string.settings_log_level),
                    options = SingBoxLogLevels,
                    selected = state.logLevel,
                    onSelect = { v -> store.update { it.copy(logLevel = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_append_log),
                    supporting = stringResource(R.string.settings_append_log_desc),
                    checked = state.appendLogToFile,
                    onCheckedChange = { v -> store.update { it.copy(appendLogToFile = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_cache_file),
                    supporting = stringResource(R.string.settings_cache_file_desc),
                    checked = state.enableCacheFile,
                    onCheckedChange = { v -> store.update { it.copy(enableCacheFile = v) } },
                )
            }
        }

        // Load balancing (moved here from the removed outbound-group
        // editor): the single built-in exit group uses these settings.
        item {
            LoadBalanceSection(store = store)
        }
        item {
            BatterySection(store = store)
        }
    }
}

/**
 * Battery & background section.
 *
 * The old version only rendered a button while the exemption was missing,
 * and that button called `startActivity` on an intent the app had not
 * declared permission for — Android throws there, the exception was
 * swallowed by `runCatching`, and the tap did nothing. The section now:
 *
 *  - declares the request permission (manifest),
 *  - reports the live exemption state with the reason (idle / data / both),
 *  - offers both the direct in-app request and a fallback to the system
 *    battery-optimization screen for OEM ROMs that ignore the intent,
 *  - explains what happens while the box is not exempt.
 */
@Composable
private fun BatterySection(store: com.leadaxe.aibox.app.AppStateStore) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager }

    // The exemption can change while the app is in the background (user taps
    // Allow in the system dialog), so re-read whenever the screen resumes.
    var ignoring by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text(
        text = if (ignoring) stringResource(R.string.settings_battery_ok)
        else stringResource(R.string.settings_battery_prompt),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (ignoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )

    if (!ignoring) {
        FilledTonalButton(
            onClick = {
                val request = runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                // Some ROMs ship an activity manager that rejects the
                // package-scoped intent (or the user's OEM security layer
                // blocks it). Fall back to the battery-optimization list so
                // the tap always leads somewhere useful.
                if (request.isFailure) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
        ) {
            Text(stringResource(R.string.settings_battery_action))
        }
        Text(
            stringResource(R.string.settings_battery_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    FilledTonalButton(
        onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    ) {
        Text(stringResource(R.string.settings_battery_app_settings))
    }

    Text(
        stringResource(R.string.settings_battery_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The engine's own power behaviour, made visible instead of hidden
    // behind the box: what the tunnel does when the screen goes off.
    Text(
        stringResource(R.string.settings_battery_tunnel_policy),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        stringResource(R.string.settings_battery_tunnel_policy_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Boot autostart: opt-in. The VPN consent grant persists, so the tunnel
    // can come back without any user interaction after a reboot.
    SwitchRow(
        label = stringResource(R.string.settings_boot_autostart),
        supporting = stringResource(R.string.settings_boot_autostart_desc),
        checked = store.current.bootAutoStart,
        onCheckedChange = { v -> store.update { it.copy(bootAutoStart = v) } },
    )
}

/**
 * Collapsible settings group. The header row keeps the whole card tappable
 * so a section opens from anywhere on the title bar, not just the chevron.
 */
@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = title,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * Load balancing for the single built-in proxy exit group: strategy, top-N
 * (use the N fastest nodes simultaneously), and sticky-session TTL.
 */
@Composable
private fun LoadBalanceSection(store: com.leadaxe.aibox.app.AppStateStore) {
    val state by store.state.collectAsState()
    SettingsSection(stringResource(R.string.settings_lb_section)) {
        SingleChoiceChips(
            label = stringResource(R.string.groups_lb_strategy),
            options = com.leadaxe.aibox.app.LbStrategies,
            selected = state.lbStrategy,
            onSelect = { v -> store.update { it.copy(lbStrategy = v) } },
            display = {
                when (it) {
                    com.leadaxe.aibox.app.LbStrategyConsistentHashing -> stringResource(R.string.groups_lb_hashing)
                    com.leadaxe.aibox.app.LbStrategyStickySessions -> stringResource(R.string.groups_lb_sticky)
                    else -> stringResource(R.string.groups_lb_round_robin)
                }
            },
        )
        // Top-N: how many of the fastest nodes serve traffic at once.
        Text(
            stringResource(R.string.settings_lb_top_n, state.lbTopN.takeIf { it > 0 } ?: 0),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 3, 4, 8).forEach { n ->
                FilterChip(
                    selected = state.lbTopN == n,
                    onClick = { store.update { it.copy(lbTopN = n) } },
                    label = { Text(n.toString()) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_lb_top_n_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StringField(
            label = stringResource(R.string.settings_lb_ttl),
            value = state.lbTtl,
            onValueChange = { v -> store.update { it.copy(lbTtl = v) } },
            placeholder = "1h",
            supporting = stringResource(R.string.settings_lb_ttl_desc),
        )
    }
}
