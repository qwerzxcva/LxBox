package com.leadaxe.aibox.engine.share

import android.content.Context
import com.leadaxe.aibox.app.DnsServerState
import com.leadaxe.aibox.app.FetchViaAuto
import com.leadaxe.aibox.app.FetchViaDirect
import com.leadaxe.aibox.app.FetchViaProxy
import com.leadaxe.aibox.app.OutboundProfile
import com.leadaxe.aibox.app.RuleSetResource
import com.leadaxe.aibox.app.Subscription
import com.leadaxe.aibox.app.userAgentHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Downloads subscription content and converts it into [OutboundProfile]s
 * (plus managed [RuleSetResource] caches). All network and disk IO is
 * dispatched on [Dispatchers.IO] so callers can stay on the main thread.
 *
 * ### Fetch paths
 *
 * The app runs its own box; subscribers to censored networks often cannot
 * reach the subscription URL at all until the tunnel is up. [Subscription.fetchVia]
 * picks the path:
 *
 *  - `direct` — plain socket, system resolver.
 *  - `proxy`  — through the box's local mixed inbound, so the fetch uses the
 *    user's own routing and DNS rules (this is the "resolve with your DNS"
 *    path; the box decides how to look the hostname up).
 *  - `auto`   — direct first, proxy on failure. Default.
 *
 * Additionally [Subscription.dnsServer] can pin a resolver for the `direct`
 * path: an `https`/`h3` DNS server from the user's DNS tab is queried over
 * DoH for an A record, and the request then connects to that address with
 * SNI/Host preserved. Handy when the system resolver is polluted but the
 * tunnel is not up yet.
 */
class SubscriptionFetcher(private val context: Context) {

    data class FetchResult(
        val outbounds: List<OutboundProfile>,
        val errors: List<ShareLinkParser.Result.Err>,
        /** Nodes dropped as duplicates of existing ones (same fingerprint). */
        val duplicates: Int = 0,
        /** Panel-provided display name (profile-title header), if any. */
        val suggestedName: String? = null,
        /** Panel-provided homepage URL (profile-web-page-url header), if any. */
        val suggestedHomePage: String? = null,
    )

    /**
     * Rewrites a parsed vless node to speak ECH when the subscription asks
     * for it. `tls.enabled` nodes get an `ech` block; an empty config lets
     * the core fetch the ECH config list from the node's DNS HTTPS record
     * (cached by TTL), so "automatic" is the default path.
     */
    private fun applyEch(subscription: Subscription, res: ShareLinkParser.Result.Ok): String {
        if (!subscription.enableEch || res.type != "vless") return res.config
        return runCatching {
            val json = Json.parseToJsonElement(res.config).jsonObject
            val tls = json["tls"]?.jsonObject ?: return@runCatching res.config
            if (tls["enabled"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching res.config
            val ech = buildJsonObject {
                put("enabled", true)
                if (subscription.echConfig.isNotBlank()) {
                    put("config", subscription.echConfig.trim())
                }
                if (subscription.echQueryServerName.isNotBlank()) {
                    put("query_server_name", subscription.echQueryServerName.trim())
                }
            }
            JsonObject(json.toMutableMap().apply { put("tls", JsonObject(tls.toMutableMap().apply { put("ech", ech) })) })
                .toString()
        }.getOrDefault(res.config)
    }

    suspend fun fetch(
        subscription: Subscription,
        existing: List<OutboundProfile> = emptyList(),
        dnsServers: List<DnsServerState> = emptyList(),
        /** Same subscription's current nodes: edited nodes pass their
         *  override/name down to the fresh copy by fingerprint match. */
        existingFor: List<OutboundProfile> = emptyList(),
    ): FetchResult = withContext(Dispatchers.IO) {
        val (body, headers) = downloadWithMeta(subscription, dnsServers)
        // Panels advertise their display name and homepage on every
        // subscription response; picking them up here lets the user leave
        // the name blank and still get a labelled subscription.
        val suggestedName = headers["profile-title"]
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "Subscription" && it != "subscription" }
        val suggestedHomePage = headers["profile-web-page-url"]?.trim()?.takeIf { it.isNotBlank() }
        val parsed = ShareLinkParser.parseMany(body)
        val decoded = parsed.mapNotNull { res ->
            when (res) {
                is ShareLinkParser.Result.Ok -> OutboundProfile(
                    id = UUID.randomUUID().toString(),
                    name = res.name,
                    type = res.type,
                    config = applyEch(subscription, res),
                    subscriptionId = subscription.id,
                )
                is ShareLinkParser.Result.Err -> null
            }
        }
        val errors = parsed.filterIsInstance<ShareLinkParser.Result.Err>()

        // Hand edits survive refreshes: a freshly served node inherits the
        // override/name of the edited node it replaces (matched by
        // fingerprint — same server, port, credentials).
        val editedByFingerprint = existingFor
            .filter { it.edited || it.override.isNotBlank() }
            .associateBy { fingerprint(it.type, it.config) }
        val inherited = decoded.map { node ->
            val previous = editedByFingerprint[fingerprint(node.type, node.config)]
            if (previous != null) {
                node.copy(
                    override = previous.override,
                    edited = previous.edited,
                    name = if (previous.edited) previous.name else node.name,
                )
            } else {
                node
            }
 }

        if (!subscription.deduplicate) {
            return@withContext FetchResult(inherited, errors, 0, suggestedName, suggestedHomePage)
        }
        // Fingerprint = the outbound JSON minus volatile fields (tag/id) plus
        // the type. Same server + port + credentials collapses to one node,
        // which is what shows up when a provider lists one node in several
        // subscriptions, or repeats it under different display names.
        val seen = existing.map { fingerprint(it.type, it.config) }.toMutableSet()
        val kept = ArrayList<OutboundProfile>(decoded.size)
        var dropped = 0
        for (node in inherited) {
            val fp = fingerprint(node.type, node.config)
            if (!seen.add(fp)) {
                dropped++
                continue
            }
            kept += node
        }
        FetchResult(kept, errors, dropped, suggestedName, suggestedHomePage)
    }

    /** Refresh every subscription, collapsing duplicates across the batch. */
    suspend fun refreshAll(
        state: List<Subscription>,
        dnsServers: List<DnsServerState> = emptyList(),
        /** All current nodes; edited nodes pass their overrides down. */
        existingFor: List<OutboundProfile> = emptyList(),
    ): RefreshAllResult = withContext(Dispatchers.IO) {
        val collected = mutableListOf<OutboundProfile>()
        val failures = mutableMapOf<String, String>()
        var duplicates = 0
        for (sub in state) {
            val currentSubNodes = existingFor.filter { it.subscriptionId == sub.id }
            runCatching {
                fetch(sub, existing = collected, dnsServers = dnsServers, existingFor = currentSubNodes)
            }
                .onSuccess {
                    collected += it.outbounds
                    duplicates += it.duplicates
                }
                .onFailure { failures[sub.id] = it.message ?: "fetch failed" }
        }
        RefreshAllResult(collected, failures, duplicates)
    }

    data class RefreshAllResult(
        val outbounds: List<OutboundProfile>,
        val failures: Map<String, String>,
        val duplicates: Int = 0,
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
        // Rule-set payloads are not user-proxied content; always direct.
        // GitHub mirror aid still applies — most managed rule sets are
        // raw.githubusercontent.com URLs that censored networks can't reach.
        val body = openStream(URL(applyDownloadAids(ruleSet.url)), proxy = null, pinnedAddress = null)
        target.writeBytes(body.toByteArray(StandardCharsets.UTF_8))
        target
    }

    // ------------------------------------------------------------- download

    private fun download(subscription: Subscription, dnsServers: List<DnsServerState>): String =
        downloadWithMeta(subscription, dnsServers).first

    /**
     * Applies the download aids from state (reference client's box.tool):
     * a mirror prefix for GitHub URLs (ghfast.top-style), bypassing the
     * blocks that make raw.githubusercontent.com unreachable from censored
     * networks. Non-GitHub URLs pass through untouched.
     */
    private fun applyDownloadAids(rawUrl: String): String {
        val mirror = currentGithubMirror
        if (mirror.isBlank()) return rawUrl
        val isGithub = rawUrl.startsWith("https://github.com/") ||
            rawUrl.startsWith("https://raw.githubusercontent.com/") ||
            rawUrl.startsWith("https://gist.github.com/") ||
            rawUrl.startsWith("https://gist.githubusercontent.com/")
        return if (isGithub && !rawUrl.startsWith(mirror)) {
            mirror.trimEnd('/') + "/" + rawUrl
        } else {
            rawUrl
        }
    }

    /** Latest mirror/token, read from the store on every call (cheap). */
    private var cachedDownloadAids: Pair<String, String> = "" to ""

    private var downloadAidsAt: Long = 0

    private val currentGithubMirror: String
        get() {
            // Cache for 5s to avoid a disk read per URL in a batch refresh.
            if (System.currentTimeMillis() - downloadAidsAt > 5_000) {
                cachedDownloadAids = downloadAidsProvider?.invoke() ?: ("" to "")
                downloadAidsAt = System.currentTimeMillis()
            }
            return cachedDownloadAids.first
        }

    private val currentGithubToken: String
        get() {
            if (System.currentTimeMillis() - downloadAidsAt > 5_000) {
                cachedDownloadAids = downloadAidsProvider?.invoke() ?: ("" to "")
                downloadAidsAt = System.currentTimeMillis()
            }
            return cachedDownloadAids.second
        }

    /** [download] plus the response headers the panel advertised. */
    private fun downloadWithMeta(
        subscription: Subscription,
        dnsServers: List<DnsServerState>,
    ): Pair<String, Map<String, String>> {
        // GitHub mirror aid: censored networks often can't reach
        // raw.githubusercontent.com directly; a mirror prefix fixes that.
        val url = URL(applyDownloadAids(subscription.url))
        val mode = subscription.fetchVia
        val headers = subscriptionHeaders(subscription)
        return when (mode) {
            FetchViaProxy -> {
                val proxy = boxProxy() ?: error("tunnel is not running — enable AIBox first, or switch the fetch mode to direct")
                openStreamWithMeta(url, proxy, pinnedAddress = null, extraHeaders = headers)
            }
            FetchViaDirect -> {
                val pinned = pinnedAddress(subscription, dnsServers, url)
                openStreamWithMeta(url, proxy = null, pinnedAddress = pinned, extraHeaders = headers)
            }
            else -> {
                // auto: try the plain path first, then route through the box.
                runCatching {
                    val pinned = pinnedAddress(subscription, dnsServers, url)
                    openStreamWithMeta(url, proxy = null, pinnedAddress = pinned, extraHeaders = headers)
                }.getOrElse { first ->
                    val proxy = boxProxy() ?: throw IllegalStateException(
                        "direct fetch failed (${first.message ?: "no route"}) and the tunnel is not running — enable AIBox and retry",
                    )
                    openStreamWithMeta(url, proxy, pinnedAddress = null, extraHeaders = headers)
                }
            }
        }
    }

    /**
     * Headers that make the request look like a mainstream client to the
     * panel: a recognised User-Agent (some panels gate the node list on it)
     * and a stable fake device id for panels that bind a subscription to the
     * first device that fetched it.
     */
    private fun subscriptionHeaders(subscription: Subscription): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to userAgentHeader(subscription.userAgent))
        if (subscription.maskHwid) {
            headers["x-hwid"] = stableDeviceId()
        }
        return headers
    }

    /**
     * Per-install device id derived from the package name and the app data
     * directory. Stable across subscription fetches so the panel keeps
     * recognising the device, but not a real hardware identifier.
     */
    private fun stableDeviceId(): String {
        val seed = "${context.packageName}-${context.filesDir.absolutePath}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Local HTTP proxy exposed by the running box, or null when it is not up. */
    private fun boxProxy(): Proxy? {
        // The fetch port is fixed (ConfigCompiler.SubscriptionFetchPort), but
        // ConfigCompiler itself is an in-process singleton: the UI process
        // never compiles a configuration, so asking it for the port always
        // answers 0 there and every proxy-path fetch failed with "VPN is not
        // running" even while the tunnel was up. Probe the loopback port
        // instead — if the box is running, its mixed inbound is listening.
        val port = com.leadaxe.aibox.engine.singbox.ConfigCompiler.SubscriptionFetchPort
        val reachable = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
        if (!reachable) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, port))
    }

    /**
     * Resolves the subscription host through the pinned DNS server when one
     * is configured and reachable over DoH. Returns null when the request
     * should use the system resolver as usual.
     */
    private fun pinnedAddress(
        subscription: Subscription,
        dnsServers: List<DnsServerState>,
        url: URL,
    ): InetAddress? {
        if (subscription.dnsServer.isBlank()) return null
        val server = dnsServers.firstOrNull { it.tag == subscription.dnsServer } ?: return null
        val host = url.host ?: return null
        // Only DoH endpoints can be queried without a full DNS stack. Plain
        // udp/tcp/local servers are left to the system resolver.
        if (server.type != "https" && server.type != "h3") return null
        if (server.address.isBlank()) return null
        return queryDoh(server.address, host)
    }

    private fun queryDoh(serverAddress: String, host: String): InetAddress? {
        val endpoint = if (serverAddress.startsWith("http")) serverAddress else "https://$serverAddress/dns-query"
        val query = "$endpoint?name=$host&type=A"
        return runCatching {
            val text = openStream(
                URL(query),
                proxy = null,
                pinnedAddress = null,
                extraHeaders = mapOf("accept" to "application/dns-json"),
            )
            val obj = Json.parseToJsonElement(text).jsonObject
            val answers = obj["Answer"]?.jsonArray ?: return@runCatching null
            answers.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { (it["type"] as? JsonPrimitive)?.content == "1" }
                ?.get("data")
                ?.let { (it as? JsonPrimitive)?.content }
                ?.let { InetAddress.getByName(it) }
        }.getOrNull()
    }

    // --------------------------------------------------------------- http

    private fun openStream(url: URL, proxy: Proxy?, pinnedAddress: InetAddress?, extraHeaders: Map<String, String> = emptyMap()): String =
        openStreamWithMeta(url, proxy, pinnedAddress, extraHeaders).first

    /** [openStream] plus the response headers (profile-title and friends). */
    private fun openStreamWithMeta(
        url: URL,
        proxy: Proxy?,
        pinnedAddress: InetAddress?,
        extraHeaders: Map<String, String> = emptyMap(),
        depth: Int = 0,
    ): Pair<String, Map<String, String>> {
        // GitHub token aid: raises the api/raw rate limit on hosts that
        // receive anonymous 60/hr limits. Never sent to non-GitHub hosts.
        val headers = if (currentGithubToken.isNotBlank() &&
            url.host.orEmpty().let { it == "api.github.com" || it == "raw.githubusercontent.com" }
        ) {
            extraHeaders + ("Authorization" to "Bearer ${currentGithubToken}")
        } else {
            extraHeaders
        }
        val realHost = url.host
        val connectUrl = if (pinnedAddress != null && url.protocol == "https") {
            // Keep the real host in the Host header / SNI while dialing the
            // pinned address: the URL host is swapped for the literal IP and
            // the socket factory restores SNI below.
            URL(url.protocol, "$pinnedAddress", url.port, url.file)
        } else {
            url
        }
        val conn = (if (proxy != null) connectUrl.openConnection(proxy) else connectUrl.openConnection())
            as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        // Panels redirect (http→https, vanity host→edge node) and answer
        // with gzip; following manually keeps SNI and Host correct across
        // the hop, which the built-in follower does not guarantee once a
        // pinned address is in play.
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "sing-box/1.14.0")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        if (pinnedAddress != null) {
            conn.setRequestProperty("Host", realHost)
        }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn is HttpsURLConnection && pinnedAddress != null) {
            conn.sslSocketFactory = SniSocketFactory(realHost)
        }
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: error("HTTP $code without Location")
                if (depth >= MAX_REDIRECTS) error("too many redirects")
                val next = runCatching { URL(url, location) }.getOrElse {
                    error("bad redirect target: $location")
                }
                // A redirect to another host must not keep the pinned
                // address or the old Host header.
                val sameHost = next.host == realHost
                return openStreamWithMeta(
                    next,
                    proxy = proxy,
                    pinnedAddress = if (sameHost) pinnedAddress else null,
                    extraHeaders = extraHeaders,
                    depth = depth + 1,
                )
            }
            if (code !in 200..299) error("HTTP $code")
            val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                java.util.zip.GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            // A panel behind a captive portal or a parked domain answers
            // 200 with an HTML page; saying so beats "0 nodes".
            val head = text.trimStart().take(64).lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                error("the server returned an HTML page, not a subscription (check the URL)")
            }
            if (text.isBlank()) error("empty response")
            val meta = conn.headerFields
                ?.filterKeys { it != null && it.startsWith("profile-", ignoreCase = true) }
                ?.mapKeys { it.key.lowercase() }
                ?.mapValues { it.value.firstOrNull().orEmpty() }
                ?.filterValues { it.isNotBlank() }
                .orEmpty()
            return text to meta
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Wraps the platform TLS stack so a request dialed by IP still presents
     * the original hostname in SNI (and therefore validates the certificate
     * against it). Only the first overload matters — HttpsURLConnection uses
     * it whenever it layered the socket itself.
     */
    private class SniSocketFactory(private val realHost: String) : SSLSocketFactory() {
        private val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean): java.net.Socket {
            val ssl = delegate.createSocket(s, realHost, port, autoClose) as SSLSocket
            runCatching {
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf(SNIHostName(realHost))
                }
            }
            return ssl
        }

        override fun createSocket(host: String, port: Int): java.net.Socket =
            delegate.createSocket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): java.net.Socket = delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): java.net.Socket =
            delegate.createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): java.net.Socket = delegate.createSocket(address, port, localAddress, localPort)
    }

    private fun fingerprint(type: String, config: String): String =
        "$type|" + config.replace(Regex("\"(tag|id)\"\\s*:\\s*\"[^\"]*\""), "")

    // Unused imports guard (SSLContext/TrustManager kept for a future pinned-CA mode).
    @Suppress("unused")
    private fun unusedTlsHooks(): Pair<SSLContext?, TrustManager?> = null to null

    @Suppress("unused")
    private val unusedTrust: X509TrustManager? = null

    @Suppress("unused")
    private val unusedCert: X509Certificate? = null

    companion object {
        const val LOOPBACK = "127.0.0.1"

        /** How long to wait for the loopback probe before giving up. */
        const val PROBE_TIMEOUT_MS = 400

        /** Redirect hops accepted before giving up (panel → edge → sign). */
        const val MAX_REDIRECTS = 5

        /**
         * Set once by the service so the fetcher can read the user's
         * download aids (mirror/token) without a direct store dependency.
         */
        @Volatile
        var downloadAidsProvider: (() -> Pair<String, String>)? = null
    }
}