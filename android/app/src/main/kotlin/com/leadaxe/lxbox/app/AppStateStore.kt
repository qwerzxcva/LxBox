package com.leadaxe.lxbox.app

import android.content.Context
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
        _state.value = next
        schedulePersist(next)
    }

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
}
