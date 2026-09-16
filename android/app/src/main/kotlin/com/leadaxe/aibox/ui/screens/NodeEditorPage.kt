package com.leadaxe.aibox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
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
import com.leadaxe.aibox.app.DomainStrategyOptions
import com.leadaxe.aibox.app.OutboundProfile
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Second-level node editor.
 *
 * The common fields (server / port / uuid / password / transport / TLS) are
 * editable visually; everything else lands in an override JSON that is
 * deep-merged over the node's config at compile time
 * (see ConfigCompiler.applyNodeOverride). Because the override survives
 * subscription refreshes, a hand-fixed node stays fixed when the panel
 * re-serves its config — the "overwrite, don't re-enter" model.
 */
@Composable
fun NodeEditorPage(
    initial: OutboundProfile,
    onDismiss: () -> Unit,
    onSave: (OutboundProfile) -> Unit,
) {
    val base = remember(initial) {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(initial.config)
                .let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull()
    }
    fun field(key: String): String = (base?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    var name by remember { mutableStateOf(if (initial.edited) initial.name else "") }
    var server by remember { mutableStateOf(field("server")) }
    var port by remember { mutableStateOf(field("server_port").takeIf { it.isNotBlank() && it != "0" } ?: "") }
    var secret by remember { mutableStateOf(field("uuid").ifBlank { field("password") }) }
    var sni by remember {
        mutableStateOf(
            (base?.get("tls") as? kotlinx.serialization.json.JsonObject)
                ?.get("server_name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
        )
    }
    var advancedJson by remember { mutableStateOf(initial.override) }
    var domainStrategy by remember { mutableStateOf(initial.domainStrategy) }
    var pqEnabled by remember {
        mutableStateOf(
            ((base?.get("tls") as? kotlinx.serialization.json.JsonObject)
                ?.get("utls") as? kotlinx.serialization.json.JsonObject)
                ?.get("pq_enabled")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } == true,
        )
    }
    var wsPingInterval by remember {
        mutableStateOf(
            (((base?.get("transport") as? kotlinx.serialization.json.JsonObject)?.get("ping_interval")
                as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val realityOn = ((base?.get("tls") as? kotlinx.serialization.json.JsonObject)
        ?.get("reality") as? kotlinx.serialization.json.JsonObject)
        ?.get("enabled")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } == true

    Column(modifier = Modifier.fillMaxSize()) {
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
                stringResource(R.string.node_edit_title, initial.name.ifBlank { initial.tag }),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val built = buildOverride(
                    initial = initial,
                    base = base,
                    name = name,
                    server = server,
                    port = port,
                    secret = secret,
                    sni = sni,
                    advancedJson = advancedJson,
                    pqEnabled = pqEnabled,
                    realityOn = realityOn,
                    wsPingInterval = wsPingInterval,
                    domainStrategy = domainStrategy,
                )
                when (built) {
                    is OverrideBuild.Invalid -> error = built.reason
                    is OverrideBuild.Ready -> {
                        onSave(
                            initial.copy(
                                name = name.ifBlank { initial.name },
                                override = if (built.json.isEmpty()) "" else built.json.toString(),
                                edited = true,
                            ),
                        )
                    }
                }
            }) { Text(stringResource(R.string.common_save)) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.node_edit_common),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_name),
                        value = name,
                        onValueChange = { name = it },
                        placeholder = initial.name.ifBlank { initial.tag },
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_server),
                        value = server,
                        onValueChange = { server = it },
                        placeholder = field("server").ifBlank { "example.com" },
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_port),
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        placeholder = field("server_port").ifBlank { "443" },
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_secret),
                        value = secret,
                        onValueChange = { secret = it },
                        placeholder = if (base?.containsKey("uuid") == true) "uuid" else "password",
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_sni),
                        value = sni,
                        onValueChange = { sni = it },
                        placeholder = "example.com",
                        supporting = stringResource(R.string.node_edit_sni_hint),
                    )
                    val isWs = ((base?.get("transport") as? kotlinx.serialization.json.JsonObject)
                        ?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content == "ws"
                    if (isWs) {
                        StringField(
                            label = stringResource(R.string.node_edit_ws_ping),
                            value = wsPingInterval,
                            onValueChange = { wsPingInterval = it },
                            placeholder = "off",
                            supporting = stringResource(R.string.node_edit_ws_ping_hint),
                        )
                    }
                    // Per-node domain resolution strategy (mikuRay's list):
                    // how the node's own server address is dialed.
                    SingleChoiceChips(
                        label = stringResource(R.string.node_edit_domain_strategy),
                        options = com.leadaxe.aibox.app.DomainStrategyOptions,
                        selected = domainStrategy,
                        onSelect = { domainStrategy = it },
                        display = {
                            when (it) {
                                "prefer_ipv4" -> stringResource(R.string.settings_prefer_ipv4)
                                "prefer_ipv6" -> stringResource(R.string.settings_prefer_ipv6)
                                "ipv4_only" -> stringResource(R.string.settings_ipv4_only)
                                "ipv6_only" -> stringResource(R.string.settings_ipv6_only)
                                else -> stringResource(R.string.node_edit_strategy_as_is)
                            }
                        },
                    )
                    // REALITY post-quantum switch: keeps X25519MLKEM768 in
                    // the ClientHello. Only shown for reality nodes; the
                    // kernel strips the group unless this is on.
                    if (realityOn) {
                        SwitchRow(
                            label = stringResource(R.string.node_edit_pq),
                            supporting = stringResource(R.string.node_edit_pq_hint),
                            checked = pqEnabled,
                            onCheckedChange = { pqEnabled = it },
                        )
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.node_edit_advanced),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StringField(
                        label = stringResource(R.string.node_edit_override),
                        value = advancedJson,
                        onValueChange = { advancedJson = it },
                        minLines = 4,
                        maxLines = 12,
                        supporting = stringResource(R.string.node_edit_override_hint),
                    )
                }
            }

            error?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                stringResource(R.string.node_edit_survival_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Result of turning the editor's fields into a node override. */
private sealed interface OverrideBuild {
    data class Ready(val json: kotlinx.serialization.json.JsonObject) : OverrideBuild
    data class Invalid(val reason: String) : OverrideBuild
}

/**
 * Collects the editor fields into an override JSON: every visual field that
 * differs from the base config becomes a key, the advanced JSON is merged
 * on top (winning for the keys it carries).
 */
private fun buildOverride(
    initial: OutboundProfile,
    base: kotlinx.serialization.json.JsonObject?,
    name: String,
    server: String,
    port: String,
    secret: String,
    sni: String,
    advancedJson: String,
    pqEnabled: Boolean,
    realityOn: Boolean,
    wsPingInterval: String,
    domainStrategy: String,
): OverrideBuild {
    fun field(key: String): String =
        (base?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    val override = buildJsonObject {}
    val mutable = override.toMutableMap()
    if (name.isNotBlank() && name != initial.name) mutable["tag"] = kotlinx.serialization.json.JsonPrimitive(name)
    if (server.isNotBlank() && server != field("server")) mutable["server"] = kotlinx.serialization.json.JsonPrimitive(server.trim())
    val portValue = port.filter { it.isDigit() }
    if (portValue.isNotBlank() && portValue.toIntOrNull() != field("server_port").toIntOrNull()) {
        val parsed = portValue.toIntOrNull() ?: return OverrideBuild.Invalid("invalid port")
        mutable["server_port"] = kotlinx.serialization.json.JsonPrimitive(parsed)
    }
    if (secret.isNotBlank() && secret != field("uuid") && secret != field("password")) {
        val key = if (base?.containsKey("uuid") == true) "uuid" else "password"
        mutable[key] = kotlinx.serialization.json.JsonPrimitive(secret.trim())
    }
    if (sni.isNotBlank()) {
        mutable["tls"] = buildJsonObject {
            put("enabled", true)
            put("server_name", sni.trim())
        }
    }
    if (wsPingInterval.isNotBlank()) {
        val existingTransport = (base?.get("transport") as? kotlinx.serialization.json.JsonObject)?.toMutableMap()
            ?: mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        existingTransport["ping_interval"] = kotlinx.serialization.json.JsonPrimitive(wsPingInterval.trim())
        mutable["transport"] = kotlinx.serialization.json.JsonObject(existingTransport)
    }
    if (realityOn) {
        // pq_enabled lives under tls.utls; keep any existing utls override
        // from the advanced JSON and layer our key on top.
        val tlsOverride = (mutable["tls"] as? kotlinx.serialization.json.JsonObject)?.toMutableMap()
            ?: mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        val existingTls = (base?.get("tls") as? kotlinx.serialization.json.JsonObject)?.toMutableMap()
            ?: mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        val existingUtls = (existingTls["utls"] as? kotlinx.serialization.json.JsonObject)?.toMutableMap()
            ?: mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        existingUtls["enabled"] = kotlinx.serialization.json.JsonPrimitive(true)
        if (pqEnabled) {
            existingUtls["pq_enabled"] = kotlinx.serialization.json.JsonPrimitive(true)
        } else {
            existingUtls.remove("pq_enabled")
        }
        tlsOverride["utls"] = kotlinx.serialization.json.JsonObject(existingUtls)
        mutable["tls"] = kotlinx.serialization.json.JsonObject(tlsOverride)
    }
    var merged = kotlinx.serialization.json.JsonObject(mutable)
    if (advancedJson.isNotBlank()) {
        val advanced = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(advancedJson)
                .let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return OverrideBuild.Invalid("invalid override JSON")
        merged = mergeJson(merged, advanced)
    }
    return OverrideBuild.Ready(merged)
}

/** Shallow deep-merge of two JSON objects (override wins). */
private fun mergeJson(
    base: kotlinx.serialization.json.JsonObject,
    override: kotlinx.serialization.json.JsonObject,
): kotlinx.serialization.json.JsonObject {
    val merged = base.toMutableMap()
    for ((key, value) in override) {
        val existing = merged[key]
        merged[key] = if (existing is kotlinx.serialization.json.JsonObject && value is kotlinx.serialization.json.JsonObject) {
            mergeJson(existing, value)
        } else {
            value
        }
    }
    return kotlinx.serialization.json.JsonObject(merged)
}

/** Generates a fresh id (used when duplicating nodes). */
fun newOutboundId(): String = UUID.randomUUID().toString()
