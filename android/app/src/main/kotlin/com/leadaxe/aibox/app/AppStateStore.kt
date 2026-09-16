package com.leadaxe.aibox.app

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Tagged wrapper around the exported [AppState] (see AppStateStore.exportJson). */
@Serializable
data class BackupEnvelope(
    val kind: String,
    val schema: Int,
    @SerialName("state")
    val state: AppState,
)

/**
 * JSON-file-backed [AppState] store. Reads are in-memory via [state]; mutations go
 * through [update], which applies the transform on the current value and persists
 * atomically (tmp file + rename) off the main thread.
 */
class AppStateStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "lxbox-state.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val persistMutex = Mutex()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Latest value, always safe to read on any thread. */
    val current: AppState get() = _state.value

    private val pendingPersist = AtomicReference<AppState?>(null)

    private fun load(): AppState {
        if (!file.isFile) return AppState()
        return runCatching { json.decodeFromString(AppState.serializer(), file.readText()) }
            .getOrElse { AppState() }
    }

    fun update(transform: (AppState) -> AppState) {
        val next = transform(_state.value)
        if (next === _state.value) return
        _state.value = sanitizeAppStateReferences(next)
        schedulePersist(next)
    }

    /**
     * Reference-consistency pass (upstream §443's data layer). Runs after
     * every update: when an outbound a DNS server detours through, an
     * outbound group references, or the final/resolver pin points at is
     * gone, the reference is reset to a safe default — the kernel would
     * otherwise refuse to start with "outbound detour not found".
     */


    private fun schedulePersist(value: AppState) {
        pendingPersist.set(value)
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                val target = pendingPersist.getAndSet(null) ?: return@withLock
                val tmp = File(file.parentFile, file.name + ".tmp")
                runCatching {
                    tmp.writeText(json.encodeToString(AppState.serializer(), target))
                    check(tmp.renameTo(file)) { "rename failed" }
                }
            }
        }
    }

    /** Flushes the latest state synchronously (used before building a VPN config). */
    suspend fun flush() {
        val target = _state.value
        persistMutex.withLock {
            pendingPersist.set(null)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(AppState.serializer(), target))
            check(tmp.renameTo(file)) { "rename failed" }
        }
    }

    /** Marker so an import file is recognisable and version-checked. */
    private fun backupEnvelope(state: AppState): String =
        json.encodeToString(
            BackupEnvelope.serializer(),
            BackupEnvelope(kind = "aibox-backup", schema = 1, state = state),
        )

    /** Full state as a tagged JSON document (see [BackupEnvelope]). */
    fun exportJson(): String = backupEnvelope(_state.value)

    /**
     * Imports a backup produced by [exportJson]. Accepts both the tagged
     * envelope and a bare AppState JSON (hand-edited files). Unknown keys
     * are ignored, so newer backups degrade gracefully. Persisted through
     * the same reference-sanitising [update] path as any edit.
     */
    fun importJson(text: String): String {
        val trimmed = text.trim()
        val imported: AppState = when {
            trimmed.startsWith("{") && trimmed.contains("\"aibox-backup\"") -> {
                val envelope = json.decodeFromString(BackupEnvelope.serializer(), trimmed)
                envelope.state
            }
            trimmed.startsWith("{") ->
                json.decodeFromString(AppState.serializer(), trimmed)
            else -> throw IllegalStateException("not a JSON backup")
        }
        update { imported }
        return "ok"
    }
}

internal fun sanitizeAppStateReferences(state: AppState): AppState {
    val liveTags = buildSet {
        add(DirectOutboundTag)
        add(ProxySelectorTag)
        state.outbounds.forEach { add(it.tag) }
        state.outboundGroups.filter { it.enabled }.forEach { add(it.tag) }
    }
    // DNS-group members reference DNS server tags, not outbound tags —
    // they live in a different namespace. Disabled servers stay live
    // here: disabled ≠ deleted, and stripping them from the group would
    // silently uncheck the user's selection in the editor.
    val liveDnsTags = state.dnsServers.map { it.tag }.toSet()
    var changed = false

    val dnsServers = state.dnsServers.map { server ->
        when {
            server.detour.isNotBlank() && server.detour !in liveTags -> {
                changed = true
                // Fall back to the main selector, not direct: the user
                // pinned an exit for a reason (a censored resolver is
                // useless over a direct path).
                server.copy(detour = ProxySelectorTag)
            }
            server.type == "group" && server.groupServers.any { it !in liveDnsTags } -> {
                changed = true
                server.copy(groupServers = server.groupServers.filter { it in liveDnsTags })
            }
            else -> server
        }
    }.filter { server ->
        // A group that lost every member is not a server any more.
        if (server.type == "group" && server.groupServers.isEmpty()) {
            changed = true
            false
        } else {
            true
        }
    }

    // "final:proxy/direct/reject" are built-in shortcuts, not server tags —
    // they survive sanitisation untouched.
    val finalDnsShortcut = state.finalDnsServer in listOf(DnsFinalProxy, DnsFinalDirect, DnsFinalReject)
    val finalDnsServer = if (finalDnsShortcut) {
        state.finalDnsServer
    } else if (state.finalDnsServer.isNotBlank() &&
        state.dnsServers.none { it.tag == state.finalDnsServer } ||
        (state.finalDnsServer.isNotBlank() &&
            dnsServers.none { it.enabled && it.tag == state.finalDnsServer })
    ) {
        changed = true
        ""
    } else {
        state.finalDnsServer
    }

    val finalDnsServerByExit = state.finalDnsServerByExit.filterValues { v ->
        v.isBlank() || v in listOf(DnsFinalProxy, DnsFinalDirect, DnsFinalReject) ||
            dnsServers.any { it.enabled && it.tag == v }
    }.let {
        if (it.size != state.finalDnsServerByExit.size) changed = true
        it
    }

    val outboundGroups = state.outboundGroups.mapNotNull { group ->
        val live = group.members.filter { it in liveTags }
        val updated = if (live.size != group.members.size) {
            changed = true
            group.copy(members = live)
        } else {
            group
        }
        // An empty urltest/selector group is dead weight the kernel
        // would compile to an empty outbounds array — drop it.
        if (updated.members.isEmpty() && state.outbounds.isNotEmpty()) {
            changed = true
            null
        } else {
            updated
        }
    }
    val selectedOutbound = if (state.selectedOutbound.isNotBlank() &&
        state.selectedOutbound !in liveTags
    ) {
        changed = true
        ""
    } else {
        state.selectedOutbound
    }

    return if (changed) {
        state.copy(
            dnsServers = dnsServers,
            finalDnsServer = finalDnsServer,
            finalDnsServerByExit = finalDnsServerByExit,
            outboundGroups = outboundGroups,
            selectedOutbound = selectedOutbound,
        )
    } else {
        state
    }
}
