package com.redowan.orbit

/**
 * Minimal host-based blocker. Any request whose host equals (or is a subdomain of)
 * an entry below is answered with an empty response.
 * Extend BLOCKED, or load a bigger hosts list from assets later.
 */
object AdBlocker {

    private val BLOCKED = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "google-analytics.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "advertising.com",
        "amazon-adsystem.com",
        "criteo.com",
        "exoclick.com",
        "mgid.com",
        "moatads.com",
        "openx.net",
        "outbrain.com",
        "popads.net",
        "propellerads.com",
        "pubmatic.com",
        "rubiconproject.com",
        "scorecardresearch.com",
        "taboola.com",
        "teads.tv",
        "yieldmo.com"
    )

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return BLOCKED.any { h == it || h.endsWith(".$it") }
    }
}
