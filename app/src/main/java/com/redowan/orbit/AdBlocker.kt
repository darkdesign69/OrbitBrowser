package com.redowan.orbit

import android.content.Context
import java.io.File

/**
 * Host-based blocker. A request is blocked when its host (or any parent domain) is in the
 * built-in list or in the user's imported lists. Imported lists may be hosts files
 * ("0.0.0.0 ads.example.com") or simple Adblock-style domain rules ("||ads.example.com^").
 * Path rules, wildcards, exceptions and cosmetic rules are ignored on purpose.
 */
object AdBlocker {

    private val BUILTIN = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "2mdn.net",
        "adnxs.com", "adsrvr.org", "advertising.com", "amazon-adsystem.com", "criteo.com",
        "criteo.net", "exoclick.com", "mgid.com", "moatads.com", "openx.net", "outbrain.com",
        "popads.net", "popcash.net", "propellerads.com", "pubmatic.com", "rubiconproject.com",
        "taboola.com", "teads.tv", "yieldmo.com", "adform.net",
        "smartadserver.com", "casalemedia.com", "contextweb.com", "lijit.com", "sharethrough.com",
        "33across.com", "bidswitch.net", "revcontent.com", "zedo.com",
        "ads.twitter.com", "analytics.twitter.com", "ads.linkedin.com", "ads.yahoo.com",
        "adcolony.com", "unityads.unity3d.com", "applovin.com", "chartboost.com", "vungle.com",
        "inmobi.com", "mopub.com", "juicyads.com", "trafficjunky.net", "adsterra.com",
        "hilltopads.net", "clickadu.com", "onclickads.net", "adcash.com", "ero-advertising.com"
    )

    @Volatile
    private var custom: Set<String> = emptySet()

    private fun file(ctx: Context) = File(ctx.filesDir, "filters.txt")

    fun load(ctx: Context) {
        val f = file(ctx)
        custom = if (f.exists()) try { parse(f.readText()) } catch (_: Exception) { emptySet() } else emptySet()
    }

    fun customCount(): Int = custom.size

    /** Appends a filter list and returns how many domains it added. */
    fun importList(ctx: Context, text: String): Int {
        val before = custom.size
        val f = file(ctx)
        val merged = (if (f.exists()) f.readText() + "\n" else "") + text
        f.writeText(merged)
        load(ctx)
        return custom.size - before
    }

    fun clearCustom(ctx: Context) {
        file(ctx).delete()
        custom = emptySet()
    }

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        val c = custom
        while (true) {
            if (h in BUILTIN || h in c) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    internal fun parse(text: String): Set<String> {
        val out = HashSet<String>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') continue
            if (line.startsWith("@@") || line.contains("##") || line.contains("#@#") || line.contains("#?#")) continue
            // hosts file: "0.0.0.0 host  # comment"
            val hashAt = line.indexOf('#')
            if (hashAt > 0) line = line.substring(0, hashAt).trim()
            if (line.any { it == ' ' || it == '\t' }) line = line.split(Regex("\\s+")).last()
            if (line.startsWith("||")) line = line.substring(2)
            if (line.contains('*')) continue
            val end = line.indexOfFirst { it == '^' || it == '/' || it == '$' || it == ':' || it == '?' }
            if (end == 0) continue
            if (end > 0) {
                if (line[end] != '^' && line[end] != '$') continue   // path-specific rule
                line = line.substring(0, end)
            }
            line = line.lowercase().trimStart('.')
            if (line == "localhost" || line.indexOf('.') < 0) continue
            if (!line.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) continue
            if (line.all { it.isDigit() || it == '.' }) continue          // bare IP address
            out.add(line)
        }
        return out
    }
}
