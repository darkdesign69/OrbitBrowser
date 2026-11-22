package com.redowan.orbit

import android.content.Context

/** Keys for every setting. Defaults live in [Prefs.DEFAULTS]. */
object K {
    // bars and tabs
    const val ADDR_BOTTOM = "addr_bottom"
    const val SCROLL_HIDE = "scroll_hide"
    const val TAB_BAR = "tab_bar"
    const val TAB_NEXT = "tab_next"
    const val TAB_VIEW = "tab_view"
    const val KEEP_TABS = "keep_tabs"
    const val PULL_REFRESH = "pull_refresh"
    const val BACK_TOP = "back_top"
    const val VOL_SCROLL = "vol_scroll"

    // display
    const val THEME = "theme"
    const val PAGE_DARK = "page_dark"
    const val TEXT_ZOOM = "text_zoom"
    const val DESKTOP_ALL = "desktop_all"

    // general
    const val ENGINE = "engine"
    const val HOMEPAGE = "homepage"

    // privacy and security
    const val ADBLOCK = "adblock"
    const val POPUP = "popup"
    const val HTTPS_FIRST = "https_first"
    const val SSL_BLOCK = "ssl_block"
    const val OVERLAY = "overlay_block"
    const val AMP_OFF = "amp_off"
    const val NAV_BAR = "nav_bar"
    const val MEDIA_DETECT = "media_detect"
    const val MEDIA_X = "media_x"
    const val MEDIA_Y = "media_y"
    const val SAVE_HISTORY = "save_history"
    const val KEY_SECURE = "key_secure"
    const val INCOG_SECURE = "incog_secure"
    const val APP_LOCK = "app_lock"
    const val EXIT_HISTORY = "exit_history"
    const val EXIT_COOKIES = "exit_cookies"
    const val EXIT_CACHE = "exit_cache"

    // site
    const val JS = "js"
    const val IMAGES_OFF = "images_off"
    const val COOKIES = "cookies"
    const val THIRD_COOKIES = "third_cookies"
    const val SITE_AD_OFF = "site_ad_off"
    const val SITE_JS_OFF = "site_js_off"
}

object Engines {
    val names = listOf(
        "DuckDuckGo", "Google", "Bing", "Brave", "Startpage", "Ecosia", "Qwant", "Yahoo", "Yandex"
    )
    val urls = listOf(
        "https://duckduckgo.com/?q=%s",
        "https://www.google.com/search?q=%s",
        "https://www.bing.com/search?q=%s",
        "https://search.brave.com/search?q=%s",
        "https://www.startpage.com/do/search?q=%s",
        "https://www.ecosia.org/search?q=%s",
        "https://www.qwant.com/?q=%s",
        "https://search.yahoo.com/search?p=%s",
        "https://yandex.com/search/?text=%s"
    )
}

/** Typed access to SharedPreferences. Defaults are defined once, here. */
class Prefs(context: Context) {

    val sp = context.applicationContext.getSharedPreferences("orbit_settings", Context.MODE_PRIVATE)

    fun bool(k: String): Boolean = sp.getBoolean(k, DEFAULTS[k] as? Boolean ?: false)
    fun int(k: String): Int = sp.getInt(k, DEFAULTS[k] as? Int ?: 0)
    fun str(k: String): String = sp.getString(k, DEFAULTS[k] as? String ?: "") ?: ""

    fun setBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    fun setInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    fun setStr(k: String, v: String) { sp.edit().putString(k, v).apply() }

    // ---- per-site lists (one host per line) ----
    fun siteList(key: String): List<String> =
        str(key).lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun siteHas(key: String, host: String): Boolean {
        if (host.isEmpty()) return false
        val h = host.lowercase().removePrefix("www.")
        return siteList(key).any { it == h || h.endsWith(".$it") }
    }

    fun siteSet(key: String, host: String, on: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        val rest = siteList(key).filter { it != h }
        setStr(key, (if (on) rest + h else rest).joinToString("\n"))
    }

    // ---- typed shortcuts used on hot paths ----
    val addrBottom get() = bool(K.ADDR_BOTTOM)
    val scrollHide get() = bool(K.SCROLL_HIDE)
    val showTabBar get() = bool(K.TAB_BAR)
    val tabNext get() = bool(K.TAB_NEXT)
    val keepTabs get() = bool(K.KEEP_TABS)
    val pullRefresh get() = bool(K.PULL_REFRESH)
    val backTop get() = bool(K.BACK_TOP)
    val volScroll get() = bool(K.VOL_SCROLL)
    val pageDark get() = bool(K.PAGE_DARK)
    val textZoom get() = int(K.TEXT_ZOOM)
    val desktopAll get() = bool(K.DESKTOP_ALL)
    val adBlock get() = bool(K.ADBLOCK)
    val popup get() = int(K.POPUP)
    val httpsFirst get() = bool(K.HTTPS_FIRST)
    val sslBlock get() = bool(K.SSL_BLOCK)
    val saveHistory get() = bool(K.SAVE_HISTORY)
    val js get() = bool(K.JS)
    val imagesOff get() = bool(K.IMAGES_OFF)
    val cookies get() = bool(K.COOKIES)
    val thirdCookies get() = bool(K.THIRD_COOKIES)
    val appLock get() = bool(K.APP_LOCK)
    val keySecure get() = bool(K.KEY_SECURE)
    val incogSecure get() = bool(K.INCOG_SECURE)
    val homepage get() = str(K.HOMEPAGE)

    fun searchUrl(query: String): String {
        val i = int(K.ENGINE).coerceIn(0, Engines.urls.size - 1)
        return Engines.urls[i].replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))
    }

    companion object {
        val DEFAULTS: Map<String, Any> = mapOf(
            K.ADDR_BOTTOM to false,
            K.SCROLL_HIDE to false,
            K.TAB_BAR to true,
            K.TAB_NEXT to true,
            K.TAB_VIEW to 0,
            K.KEEP_TABS to true,
            K.PULL_REFRESH to true,
            K.BACK_TOP to true,
            K.VOL_SCROLL to false,
            K.THEME to 0,
            K.PAGE_DARK to false,
            K.TEXT_ZOOM to 100,
            K.DESKTOP_ALL to false,
            K.ENGINE to 0,
            K.HOMEPAGE to "",
            K.ADBLOCK to true,
            K.POPUP to 1,
            K.HTTPS_FIRST to true,
            K.SSL_BLOCK to true,
            K.OVERLAY to false,
            K.AMP_OFF to true,
            K.NAV_BAR to false,
            K.MEDIA_DETECT to true,
            K.MEDIA_X to 1000,
            K.MEDIA_Y to 620,
            K.SAVE_HISTORY to true,
            K.KEY_SECURE to true,
            K.INCOG_SECURE to true,
            K.APP_LOCK to false,
            K.EXIT_HISTORY to false,
            K.EXIT_COOKIES to false,
            K.EXIT_CACHE to false,
            K.JS to true,
            K.IMAGES_OFF to false,
            K.COOKIES to true,
            K.THIRD_COOKIES to true,
            K.SITE_AD_OFF to "",
            K.SITE_JS_OFF to ""
        )
    }
}
