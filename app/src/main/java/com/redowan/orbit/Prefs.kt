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
    const val SITE_OVERLAY_OFF = "site_overlay_off"
    const val SITE_POPUP_OFF = "site_popup_off"
    const val BLOCKED_LINKS = "blocked_links"
    const val MEM_KEEP = "mem_keep"
    const val TR_BLACK = "tr_black"

    // keys read by the browser screen
    const val BACK_TWICE = "back_twice"
    const val KEYBOARD_START = "keyboard_start"
    const val UA = "user_agent"
    const val ROTATION = "screen_rotation"
    const val BRIGHTNESS = "brightness"
    const val SCREEN_TIMEOUT = "screen_timeout"
    const val FULLSCREEN = "fullscreen"
    const val SAFE_BROWSING = "safe_browsing"
    const val TR_LANG = "tr_lang"
    const val TTS_RATE = "tts_rate"
    const val MENU_STYLE = "menu_style"
    const val ADDR_LABEL = "addr_label"
    const val NEW_TAB = "new_tab_page"
    const val START_PAGE = "start_page"
    const val LP_LINK = "lp_link"
    const val LP_IMAGE = "lp_image"
    const val SCREEN_FILTER = "screen_filter"
    const val FILTER_COLOR = "filter_color"
    const val BACK_LAST = "back_last"
    const val SHOW_SCROLLBAR = "show_scrollbar"
    const val HOME_CLOSE_ALL = "home_close_all"
    const val TAB_BAR_MODE = "tab_bar_mode"      // 0 not used, 1 below top bar, 2 above top bar
    const val TAB_OUTLINE = "tab_outline"
    const val TAB_COLOR = "tab_color"            // index into Tabs.COLORS
    const val TAB_ADD_RIGHT = "tab_add_right"    // 0 left, 1 right
    const val TAB_CLOSE_BTN = "tab_close_btn"
    const val TAB_W_PCT = "tab_w_pct"            // 100% = 192dp
    const val TAB_H_PCT = "tab_h_pct"            // 100% = 52dp
    const val TAB_SWIPE_UP = "tab_swipe_up"      // 0 none, 1 tab list, 2 close tab, 3 new tab
    const val TAB_SWIPE_DN = "tab_swipe_dn"
    const val TAB_INDI = "tab_indi"
    const val TAB_UNDO = "tab_undo"
    const val LAST_NOTI = "last_noti"
    const val KEYBOARD_ADD = "keyboard_add"
    const val TAB_OPEN_LIMIT = "tab_open_limit"
    const val DEBUG_WEB = "adv_debug"
    const val LAY_TOP = "lay_top"
    const val LAY_BOTTOM = "lay_bottom"
    const val LAY_TOP_H = "lay_top_h"
    const val LAY_ICON = "lay_icon"
    const val LAY_TAB_H = "lay_tab_h"
    const val LAY_TAB_W = "lay_tab_w"
    const val LAY_BOT_H = "lay_bot_h"
    const val LAY_COLS = "lay_cols"
    const val MENU_QUICK = "menu_quick"
    const val MENU_LIST = "menu_list"
    const val MENU_BOTTOM = "menu_bottom"
}

/** Languages offered for translation: label and language code ("" = follow the system). */
object Langs {
    val labels = listOf("System", "English", "Bengali", "Hindi", "Arabic", "Spanish", "French", "German",
        "Portuguese", "Russian", "Japanese", "Korean", "Chinese", "Turkish", "Italian", "Indonesian", "Vietnamese", "Thai")
    val codes = listOf("", "en", "bn", "hi", "ar", "es", "fr", "de", "pt", "ru", "ja", "ko", "zh-CN", "tr", "it", "id", "vi", "th")
    fun target(p: Prefs): String = codes.getOrElse(p.int(K.TR_LANG)) { "" }.ifEmpty { java.util.Locale.getDefault().language }
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
    val showTabBar get() = int(K.TAB_BAR_MODE) != 0
    val tabNext get() = bool(K.TAB_NEXT)
    val keepTabs get() = bool(K.KEEP_TABS)
    val pullRefresh get() = bool(K.PULL_REFRESH)
    val backTop get() = bool(K.BACK_TOP)
    val volScroll get() = bool(K.VOL_SCROLL)
    val pageDark get() = bool(K.PAGE_DARK)
    val safeBrowsing get() = bool(K.SAFE_BROWSING)
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
        val DEFAULTS: MutableMap<String, Any> = hashMapOf(
            K.ADDR_BOTTOM to false,
            K.SCROLL_HIDE to false,
            K.TAB_BAR to true,
            K.TAB_NEXT to true,
            K.TAB_VIEW to 0,
            K.KEEP_TABS to true,
            K.PULL_REFRESH to true,
            K.BACK_TOP to true,
            K.VOL_SCROLL to false,
            K.THEME to 2,
            K.PAGE_DARK to false,
            K.TEXT_ZOOM to 100,
            K.DESKTOP_ALL to false,
            K.ENGINE to 1,
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
            K.SITE_JS_OFF to "",
            K.SITE_OVERLAY_OFF to "",
            K.SITE_POPUP_OFF to "",
            K.BLOCKED_LINKS to "",
            K.MEM_KEEP to "",
            K.TR_BLACK to "",
            K.BACK_TWICE to false,
            K.KEYBOARD_START to false,
            K.UA to 0,
            K.ROTATION to 0,
            K.BRIGHTNESS to 0,
            K.SCREEN_TIMEOUT to 0,
            K.FULLSCREEN to false,
            K.SAFE_BROWSING to true,
            K.TR_LANG to 0,
            K.TTS_RATE to 2,
            K.MENU_STYLE to 0,
            K.ADDR_LABEL to 0,
            K.NEW_TAB to 0,
            K.START_PAGE to 0,
            K.LP_LINK to 0,
            K.LP_IMAGE to 0,
            K.SCREEN_FILTER to 0,
            K.FILTER_COLOR to 0xFF000000.toInt(),
            K.BACK_LAST to 0,
            K.SHOW_SCROLLBAR to 0,
            K.HOME_CLOSE_ALL to false,
            "allow_zoom" to true,
            "adv_mixed" to true,
            "adv_dom_storage" to true,
            "dl_ask" to false,
            "tab_swipe_switch" to true,
            "dark_img" to true,
            "img_bright" to 70,
            "back_nofresh" to true,
            "dl_mgr" to 0,
            "dl_fast" to 10,
            "dl_retry" to 10,
            "dl_conc" to 0,
            "dl_wifi" to false,
            "dl_notify" to true,
            "mem_tab_limit" to 0,
            K.KEYBOARD_ADD to false,
            K.TAB_OPEN_LIMIT to 0,
            K.DEBUG_WEB to false,
            K.LAY_TOP to Bars.DEFAULT_TOP,
            K.LAY_BOTTOM to Bars.DEFAULT_BOTTOM,
            K.LAY_TOP_H to 44,
            K.LAY_ICON to 40,
            K.TAB_BAR_MODE to 1,
            K.TAB_OUTLINE to true,
            K.TAB_COLOR to 4,
            K.TAB_ADD_RIGHT to 1,
            K.TAB_CLOSE_BTN to true,
            K.TAB_W_PCT to 81,
            K.TAB_H_PCT to 73,
            K.TAB_SWIPE_UP to 1,
            K.TAB_SWIPE_DN to 2,
            K.TAB_INDI to false,
            K.TAB_UNDO to true,
            K.LAST_NOTI to true,
            K.LAY_TAB_H to 34,
            K.LAY_TAB_W to 156,
            K.LAY_BOT_H to 52,
            K.LAY_COLS to 4,
            ThemeBg.MODE to "wall:1",
            ThemeBg.VER to 0,
            ThemeBg.TRANS to 70,
            ThemeBg.BLUR to 0,
            ThemeBg.DIM to 40,
            ThemeBg.ACCENT to 0,
            ThemeBg.WEB_FONT to true,
            K.MENU_QUICK to MenuCat.DEFAULT_QUICK,
            K.MENU_LIST to MenuCat.DEFAULT_LIST,
            K.MENU_BOTTOM to MenuCat.DEFAULT_BOTTOM
        )
    }
}
