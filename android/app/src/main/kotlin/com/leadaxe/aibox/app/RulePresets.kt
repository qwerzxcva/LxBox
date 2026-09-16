package com.leadaxe.aibox.app

import com.leadaxe.aibox.R
import java.util.UUID

/**
 * Built-in route-rule presets, ported from the reference client's wizard
 * template (`selectable_rules`). Each preset materialises into ordinary
 * [RouteRule]s on the routes page, so a user can enable, disable, edit or
 * delete them like any hand-made rule — nothing is hidden behind a flag.
 *
 * Russian-specific presets from the source template (ru-direct, ru-inside)
 * are intentionally not ported: they targeted a different audience.
 *
 * [RulePreset.ruleSetUrl] names a remote `.srs` resource a preset needs;
 * it is downloaded lazily by the engine and cached on disk.
 */
data class RulePreset(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val ruleSetTag: String? = null,
    val ruleSetUrl: String? = null,
    val ruleSetFormat: String = "binary",
    /** Builds the rules this preset contributes. */
    val build: (preset: RulePreset, existing: List<RuleSetResource>) -> List<RouteRule>,
)

/**
 * The preset catalogue. Order matters: it is the order the section shows
 * them in, and the order they land in the rule table when all are added.
 */
val RulePresets: List<RulePreset> = listOf(
    RulePreset(
        id = "block-ads",
        titleRes = R.string.preset_block_ads,
        descriptionRes = R.string.preset_block_ads_desc,
        ruleSetTag = "ads-all",
        ruleSetUrl = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/category-ads-all.srs",
        build = { preset, _ ->
            listOf(
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "Block ads",
                    action = RouteRule.RuleActionReject,
                    ruleSet = listOf(preset.ruleSetTag!!),
                ),
            )
        },
    ),
    RulePreset(
        id = "private-ip",
        titleRes = R.string.preset_private_ip,
        descriptionRes = R.string.preset_private_ip_desc,
        build = { _, _ ->
            listOf(
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "Private IPs",
                    ipIsPrivate = true,
                    outbound = DirectOutboundTag,
                ),
            )
        },
    ),
    RulePreset(
        id = "bittorrent",
        titleRes = R.string.preset_bittorrent,
        descriptionRes = R.string.preset_bittorrent_desc,
        build = { _, _ ->
            listOf(
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "BitTorrent",
                    protocol = listOf("bittorrent"),
                    outbound = DirectOutboundTag,
                ),
            )
        },
    ),
    RulePreset(
        id = "vowifi",
        titleRes = R.string.preset_vowifi,
        descriptionRes = R.string.preset_vowifi_desc,
        build = { _, _ ->
            listOf(
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "VoWiFi (carrier calling)",
                    domainSuffix = listOf("pub.3gppnetwork.org"),
                    outbound = DirectOutboundTag,
                ),
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "VoWiFi IKEv2",
                    network = listOf("udp"),
                    port = listOf("500", "4500"),
                    outbound = DirectOutboundTag,
                ),
            )
        },
    ),
    RulePreset(
        id = "fcm-push",
        titleRes = R.string.preset_fcm,
        descriptionRes = R.string.preset_fcm_desc,
        build = { _, _ ->
            listOf(
                RouteRule(
                    id = UUID.randomUUID().toString(),
                    name = "Google push (FCM)",
                    combine = RouteRule.CombineOr,
                    domain = listOf(
                        "mtalk.google.com",
                        "mtalk4.google.com",
                        "android.apis.google.com",
                    ),
                    domainSuffix = listOf(
                        "firebaseinstallations.googleapis.com",
                        "device-provisioning.googleapis.com",
                        "iid.googleapis.com",
                    ),
                    domainRegex = listOf("^alt\\d+-mtalk\\.google\\.com$"),
                    portRange = listOf("5228:5230"),
                    outbound = DirectOutboundTag,
                ),
            )
        },
    ),
)

/** Preset ids currently materialised in [AppState.routeRules]. */
fun AppState.isPresetInstalled(presetId: String): Boolean =
    routeRules.any { it.presetId == presetId }

/**
 * Materialises [preset] into rules + the rule-set resource it needs.
 * Returns the rules to append and the resource to register (if any).
 */
fun materializePreset(
    preset: RulePreset,
    state: AppState,
): Pair<List<RouteRule>, List<RuleSetResource>> {
    val rules = preset.build(preset, state.ruleSets).map { it.copy(presetId = preset.id) }
    val url = preset.ruleSetUrl ?: return rules to emptyList()
    val tag = preset.ruleSetTag ?: return rules to emptyList()
    if (state.ruleSets.any { it.tag == tag }) return rules to emptyList()
    val resource = RuleSetResource(
        id = UUID.randomUUID().toString(),
        tag = tag,
        format = preset.ruleSetFormat,
        url = url,
    )
    return rules to listOf(resource)
}

/**
 * Removes every rule [materializePreset] produced; the managed rule set is
 * left in place (other rules may reference it after a hand edit).
 */
fun List<RouteRule>.withoutPreset(presetId: String): List<RouteRule> =
    filterNot { it.presetId == presetId }

/**
 * Managed id for the per-subscription suffix rule (see
 * [Subscription.routeBySuffix]). One rule per subscription; a later
 * materialisation replaces the previous one in place.
 */
fun subscriptionRuleId(subscriptionId: String) = "sub-route-$subscriptionId"

/**
 * Materialises the subscription host as a domain-suffix rule so the panel
 * itself is routed the way the subscription is fetched: proxy mode → the
 * main selector; direct/auto mode → direct (auto tries direct first, so
 * pinning direct matches what actually happens). Managed like presets:
 * visible in the rules list, removable, replaced (not duplicated) on
 * re-save. Returns null when the URL has no hostname.
 */
fun subscriptionSuffixRule(subscription: Subscription): RouteRule? {
    val host = runCatching {
        java.net.URI(subscription.url.trim()).host
    }.getOrNull()?.lowercase() ?: return null
    if (host.isBlank()) return null
    val outbound = if (subscription.fetchVia == FetchViaProxy) ProxySelectorTag else DirectOutboundTag
    return RouteRule(
        id = subscriptionRuleId(subscription.id),
        name = subscription.name.ifBlank { host },
        domainSuffix = listOf(host.removePrefix("www.")),
        outbound = outbound,
        presetId = subscriptionRuleId(subscription.id),
    )
}

/**
 * Applies the routeBySuffix toggle of [subscription] to [rules]: removes
 * any previous managed rule for it, then appends the fresh one when
 * enabled. Returns the updated list (same instance when nothing changed).
 */
fun List<RouteRule>.withSubscriptionRule(subscription: Subscription): List<RouteRule> {
    val managedId = subscriptionRuleId(subscription.id)
    val without = filterNot { it.id == managedId || it.presetId == managedId }
    if (!subscription.routeBySuffix) {
        return if (without.size == size) this else without
    }
    val rule = subscriptionSuffixRule(subscription) ?: return without
    return without + rule
}
