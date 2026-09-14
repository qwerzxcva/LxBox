package com.leadaxe.lxbox.engine.share

import android.content.Context
import com.leadaxe.lxbox.app.DnsServerState
import com.leadaxe.lxbox.app.FetchViaAuto
import com.leadaxe.lxbox.app.FetchViaDirect
import com.leadaxe.lxbox.app.FetchViaProxy
import com.leadaxe.lxbox.app.OutboundProfile
import com.leadaxe.lxbox.app.RuleSetResource
import com.leadaxe.lxbox.app.Subscription
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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
    )

    suspend fun fetch(
        subscription: Subscription,
        existing: List<OutboundProfile> = emptyList(),
        dnsServers: List<DnsServerState> = emptyList(),
    ): FetchResult = withContext(Dispatchers.IO) {
        val body = download(subscription, dnsServers)
        val parsed = ShareLinkParser.parseMany(body)
        val decoded = parsed.mapNotNull { res ->
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

        if (!subscription.deduplicate) {
            return@withContext FetchResult(decoded, errors, 0)
        }
        // Fingerprint = the outbound JSON minus volatile fields (tag/id) plus
        // the type. Same server + port + credentials collapses to one node,
        // which is what shows up when a provider lists one node in several
        // subscriptions, or repeats it under different display names.
        val seen = existing.map { fingerprint(it.type, it.config) }.toMutableSet()
        val kept = ArrayList<OutboundProfile>(decoded.size)
        var dropped = 0
        for (node in decoded) {
            val fp = fingerprint(node.type, node.config)
            if (!seen.add(fp)) {
                dropped++
                continue
            }
            kept += node
        }
        FetchResult(kept, errors, dropped)
    }

    /** Refresh every subscription, collapsing duplicates across the batch. */
    suspend fun refreshAll(
        state: List<Subscription>,
        dnsServers: List<DnsServerState> = emptyList(),
    ): RefreshAllResult = withContext(Dispatchers.IO) {
        val collected = mutableListOf<OutboundProfile>()
        val failures = mutableMapOf<String, String>()
        var duplicates = 0
        for (sub in state) {
            runCatching { fetch(sub, existing = collected, dnsServers = dnsServers) }
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
        val body = openStream(URL(ruleSet.url), proxy = null, pinnedAddress = null)
        target.writeBytes(body.toByteArray(StandardCharsets.UTF_8))
        target
    }

    // ------------------------------------------------------------- download

    private fun download(subscription: Subscription, dnsServers: List<DnsServerState>): String {
        val url = URL(subscription.url)
        val mode = subscription.fetchVia
        return when (mode) {
            FetchViaProxy -> {
                val proxy = boxProxy() ?: error("VPN is not running")
                openStream(url, proxy, pinnedAddress = null)
            }
            FetchViaDirect -> {
                val pinned = pinnedAddress(subscription, dnsServers, url)
                openStream(url, proxy = null, pinnedAddress = pinned)
            }
            else -> {
                // auto: try the plain path first, then route through the box.
                runCatching {
                    val pinned = pinnedAddress(subscription, dnsServers, url)
                    openStream(url, proxy = null, pinnedAddress = pinned)
                }.getOrElse { first ->
                    val proxy = boxProxy()
                        ?: throw IllegalStateException(first.message ?: "fetch failed")
                    openStream(url, proxy, pinnedAddress = null)
                }
            }
        }
    }

    /** Local HTTP proxy exposed by the running box, or null when it is not up. */
    private fun boxProxy(): Proxy? {
        val port = com.leadaxe.lxbox.engine.singbox.ConfigCompiler.currentLocalProxyPort() ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
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

    private fun openStream(
        url: URL,
        proxy: Proxy?,
        pinnedAddress: InetAddress?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
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
        conn.setRequestProperty("User-Agent", "LxBox/3.0 (Android)")
        if (pinnedAddress != null) {
            conn.setRequestProperty("Host", realHost)
        }
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn is HttpsURLConnection && pinnedAddress != null) {
            conn.sslSocketFactory = SniSocketFactory(realHost)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
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
}