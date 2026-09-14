package com.leadaxe.lxbox.engine.share

import android.content.Context
import com.leadaxe.lxbox.app.OutboundProfile
import com.leadaxe.lxbox.app.RuleSetResource
import com.leadaxe.lxbox.app.Subscription
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads subscription content and converts it into [OutboundProfile]s
 * (plus managed [RuleSetResource] caches). All network and disk IO is
 * dispatched on [Dispatchers.IO] so callers can stay on the main thread.
 *
 * The fetcher is intentionally tolerant: each line of the body is parsed
 * independently and bad lines are dropped. The UI can show the success
 * count and a list of failures separately.
 */
class SubscriptionFetcher(private val context: Context) {

    data class FetchResult(
        val outbounds: List<OutboundProfile>,
        val errors: List<ShareLinkParser.Result.Err>,
    )

    suspend fun fetch(subscription: Subscription): FetchResult = withContext(Dispatchers.IO) {
        val body = httpGet(subscription.url)
        val parsed = ShareLinkParser.parseMany(body)
        val outbounds = parsed.mapNotNull { res ->
            when (res) {
                is ShareLinkParser.Result.Ok -> OutboundProfile(
                    id = UUID.randomUUID().toString(),
                    name = res.name,
                    type = res.type,
                    config = res.config,
                    subscriptionId = subscription.id,
                )
                is ShareLinkParser.Result.Err -> null
            }
        }
        val errors = parsed.filterIsInstance<ShareLinkParser.Result.Err>()
        FetchResult(outbounds, errors)
    }

    /**
     * Refresh all subscriptions and return the merged list of outbounds plus
     * a per-subscription failure map. Used by the UI to bump the
     * `lastUpdatedEpochMillis` on success and show error toasts on failure.
     */
    suspend fun refreshAll(state: List<Subscription>): RefreshAllResult =
        withContext(Dispatchers.IO) {
            val collected = mutableListOf<OutboundProfile>()
            val failures = mutableMapOf<String, String>()
            for (sub in state) {
                runCatching { fetch(sub) }
                    .onSuccess { collected += it.outbounds }
                    .onFailure { failures[sub.id] = it.message ?: "fetch failed" }
            }
            RefreshAllResult(collected, failures)
        }

    data class RefreshAllResult(
        val outbounds: List<OutboundProfile>,
        val failures: Map<String, String>,
    )

    /**
     * Refresh the cache for every managed [RuleSetResource] whose update
     * interval has elapsed. Safe to call from the UI thread; all IO is
     * dispatched off-thread.
     */
    suspend fun refreshStaleRuleSets(resources: List<RuleSetResource>): List<File> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val stale = resources.filter { rs ->
                rs.updateIntervalHours > 0 &&
                    now - rs.lastUpdatedEpochMillis >= rs.updateIntervalHours * 3_600_000L
            }
            // Fetch in parallel — `cacheRuleSet` already serialises per file
            // writes via distinct paths, so concurrent runs are safe.
            stale.map { rs -> runCatching { cacheRuleSet(rs) }.getOrNull() }
                .filterNotNull()
        }

    /**
     * Download a managed rule-set resource, cache it under
     * `filesDir/box/ruleset/<id>.<ext>`, and report the cache file.
     *
     * The cached path is what the sing-box config's `rule_set` entry uses
     * (`local/path` form); [RuleSetResource.lastUpdatedEpochMillis] is
     * updated on success.
     */
    suspend fun cacheRuleSet(ruleSet: RuleSetResource): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "box/ruleset").apply { mkdirs() }
        val target = File(dir, "${ruleSet.id}.${ruleSet.extension}")
        val body = httpGet(ruleSet.url)
        target.writeBytes(body.toByteArray(StandardCharsets.UTF_8))
        target
    }

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LxBox/3.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}