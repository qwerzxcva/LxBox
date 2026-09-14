package com.leadaxe.lxbox.engine.vpn

import com.leadaxe.lxbox.app.AppState
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StringIterator

/**
 * Adapt a Kotlin list into libbox's `StringIterator` interface (gomobile uses
 * pull-based iteration, no `Iterable`).
 */
internal fun List<String>.toStringIterator(): StringIterator = object : StringIterator {
    private val it = iterator()
    override fun hasNext(): Boolean = it.hasNext()
    override fun len(): Int = size
    override fun next(): String = it.next()
}

/**
 * Build the [OverrideOptions] carried by [CommandServer.startOrReloadService]
 * from the user-visible per-app-proxy fields on [state].
 *
 * libbox treats `includePackage`/`excludePackage` as overriding the route's
 * `package_name` logic: an empty list means "no override", and the empty
 * package list is encoded as the selector mode itself (whitelist/blacklist).
 */
internal fun AppState.toOverrideOptions(): OverrideOptions = OverrideOptions().apply {
    if (!perAppProxyEnabled) return@apply
    val packages = perAppProxyPackages.toList()
    if (packages.isEmpty()) return@apply
    if (perAppProxyWhitelist) {
        setIncludePackage(packages.toStringIterator())
        setAutoRedirect(false)
    } else {
        setExcludePackage(packages.toStringIterator())
        setAutoRedirect(false)
    }
}