package com.redowan.orbit

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Settings as a tree of pages, like the reference browser: a main list of categories, each opening
 * a page of grouped cards. Every page is plain data (see [build]); rows save straight into [Prefs].
 */
class SettingsActivity : AppCompatActivity() {

    private sealed class Row
    private object Gap : Row()
    private class Head(val icon: Int, val text: String) : Row()
    private class Nav(val icon: Int, val title: String, val page: String) : Row()
    private class Sw(val key: String, val title: String, val info: String, val after: (() -> Unit)?) : Row()
    private class Ch(
        val key: String, val title: String, val labels: List<String>, val values: List<Int>,
        val info: String, val after: (() -> Unit)?
    ) : Row()
    private class Multi(val key: String, val title: String, val labels: List<String>) : Row()
    private class Col(val key: String, val title: String) : Row()
    private class Act(val title: String, val value: () -> String, val info: String, val run: () -> Unit) : Row()
    private class Note(val text: String) : Row()

    private lateinit var prefs: Prefs
    private lateinit var store: Store
    private lateinit var adapter: RowAdapter
    private lateinit var rows: List<Row>
    private lateinit var page: String
    private var ripple = 0

    // file import / export (system file picker, no storage permission needed)
    private val exportHtml = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { u ->
        u?.let { write(it, bookmarksHtml()) }
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        u?.let { write(it, bookmarksCsv()) }
    }
    private val importHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { importBookmarks(read(it)) }
    }
    private val backupOut = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u?.let { write(it, backupJson()) }
    }
    private val backupIn = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { restoreJson(read(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = Store(this)
        page = intent.getStringExtra("page") ?: "main"
        setContentView(R.layout.activity_settings)

        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        ripple = tv.resourceId

        findViewById<TextView>(R.id.setTitle).text = pageTitle(page)
        findViewById<View>(R.id.setBack).setOnClickListener { finish() }
        val reset = findViewById<View>(R.id.setReset)
        val search = findViewById<View>(R.id.setSearch)
        if (page == "main") {
            reset.visibility = View.GONE
            search.visibility = View.VISIBLE
            search.setOnClickListener { searchSettings() }
        } else {
            reset.setOnClickListener { resetPage() }
        }

        rows = build(page)
        adapter = RowAdapter()
        val list = findViewById<RecyclerView>(R.id.setList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ row builders

    private fun sw(key: String, title: String, def: Boolean, info: String = "", after: (() -> Unit)? = null): Row {
        Prefs.DEFAULTS.putIfAbsent(key, def)
        return Sw(key, title, info, after)
    }

    private fun ch(
        key: String, title: String, labels: List<String>, def: Int = 0, info: String = "",
        values: List<Int> = labels.indices.toList(), after: (() -> Unit)? = null
    ): Row {
        Prefs.DEFAULTS.putIfAbsent(key, def)
        return Ch(key, title, labels, values, info, after)
    }

    private fun multi(key: String, title: String, labels: List<String>, def: Int): Row {
        Prefs.DEFAULTS.putIfAbsent(key, def)
        return Multi(key, title, labels)
    }

    private fun col(key: String, title: String, def: Int = LAVENDER): Row {
        Prefs.DEFAULTS.putIfAbsent(key, def)
        return Col(key, title)
    }

    private fun act(title: String, info: String = "", value: () -> String = { "" }, run: () -> Unit = {}): Row =
        Act(title, value, info, run)

    private fun sites(title: String, key: String, info: String): Row {
        Prefs.DEFAULTS.putIfAbsent(key, "")
        return Act(title, { siteSummary(key) }, info) { editSites(title, key) }
    }

    private fun optNum(key: String, title: String, unlimited: String, nums: List<Int>, def: Int, info: String = ""): Row =
        ch(key, title, listOf(unlimited) + nums.map { it.toString() }, def, info)

    // ------------------------------------------------------------------ page tree

    private val allPages = listOf(
        "general", "advanced", "site", "backup", "webcleaner", "download", "media", "memory",
        "display", "layout", "menu", "tab", "composition", "fullscreen", "landscape", "floatbtn",
        "webcontent", "gesture", "translator", "tts", "tvcast", "security", "incognito", "password",
        "cleardata", "dns"
    )

    private fun pageTitle(p: String) = when (p) {
        "main" -> "Settings"
        "general" -> "General"
        "advanced" -> "Advanced"
        "site" -> "Site"
        "backup" -> "Backup"
        "webcleaner" -> "Web cleaner"
        "download" -> "Download"
        "media" -> "Media"
        "memory" -> "Memory saving"
        "display" -> "Display"
        "layout" -> "Layout"
        "menu" -> "Menu"
        "tab" -> "Tab"
        "composition" -> "Composition"
        "fullscreen" -> "Full screen and Toolbar"
        "landscape" -> "Landscape mode"
        "floatbtn" -> "Floating button"
        "webcontent" -> "Web content"
        "gesture" -> "Gesture"
        "translator" -> "Translator"
        "tts" -> "Text to Speech"
        "tvcast" -> "TV Cast"
        "security" -> "Security"
        "incognito" -> "Incognito mode"
        "password" -> "Password"
        "cleardata" -> "Clear data"
        "dns" -> "DNS"
        else -> "Settings"
    }

    private fun build(p: String): List<Row> = when (p) {
        "main" -> mainRows()
        "general" -> generalRows()
        "advanced" -> advancedRows()
        "site" -> siteRows()
        "backup" -> backupRows()
        "webcleaner" -> webCleanerRows()
        "download" -> downloadRows()
        "media" -> mediaRows()
        "memory" -> memoryRows()
        "display" -> displayRows()
        "layout" -> layoutRows()
        "menu" -> menuRows()
        "tab" -> tabRows()
        "composition" -> compositionRows()
        "fullscreen" -> fullscreenRows()
        "landscape" -> landscapeRows()
        "floatbtn" -> floatRows()
        "webcontent" -> webContentRows()
        "gesture" -> gestureRows()
        "translator" -> translatorRows()
        "tts" -> ttsRows()
        "tvcast" -> tvCastRows()
        "security" -> securityRows()
        "incognito" -> incognitoRows()
        "password" -> passwordRows()
        "cleardata" -> clearDataRows()
        "dns" -> dnsRows()
        else -> emptyList()
    }

    private fun mainRows(): List<Row> = listOf(
        Nav(R.drawable.ic_settings, "General", "general"),
        Nav(R.drawable.ic_tune, "Advanced", "advanced"),
        Nav(R.drawable.ic_globe, "Site", "site"),
        Nav(R.drawable.ic_sync, "Backup", "backup"),
        Gap,
        Nav(R.drawable.ic_shield, "Web cleaner", "webcleaner"),
        Nav(R.drawable.ic_download, "Download", "download"),
        Nav(R.drawable.ic_play_arrow, "Media", "media"),
        Nav(R.drawable.ic_bolt, "Memory saving", "memory"),
        Gap,
        Nav(R.drawable.ic_paint, "Display", "display"),
        Nav(R.drawable.ic_dashboard, "Layout", "layout"),
        Nav(R.drawable.ic_grid, "Menu", "menu"),
        Nav(R.drawable.ic_copy, "Tab", "tab"),
        Nav(R.drawable.ic_build, "Composition", "composition"),
        Gap,
        Nav(R.drawable.ic_web, "Web content", "webcontent"),
        Nav(R.drawable.ic_swipe, "Gesture", "gesture"),
        Nav(R.drawable.ic_translate, "Translator", "translator"),
        Nav(R.drawable.ic_volume, "Text to Speech", "tts"),
        Nav(R.drawable.ic_cast, "TV Cast", "tvcast"),
        Gap,
        Nav(R.drawable.ic_shield, "Security", "security"),
        Nav(R.drawable.ic_incognito, "Incognito mode", "incognito"),
        Nav(R.drawable.ic_lock, "Password", "password"),
        Nav(R.drawable.ic_delete, "Clear data", "cleardata"),
        Nav(R.drawable.ic_key, "DNS", "dns"),
        Gap,
        act("Orbit Browser", "Open-source browser on the system WebView. No ads, no tracking.")
    )

    private fun generalRows(): List<Row> = listOf(
        act("Set as default browser") { defaultBrowser() },
        act("Notification") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) { toast("Open Android settings > Notifications") }
        },
        Gap,
        act("Language", "Depends on system setting", { "System" }) {
            try {
                startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { toast("Open Android settings > Languages") }
        },
        act("Language resource (GitHub)") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/darkdesign69/OrbitBrowser")))
            } catch (_: Exception) { toast("No app can open this") }
        },
        Gap,
        sw(K.KEYBOARD_START, "Keyboard activation on app startup", false),
        sw(K.BACK_TWICE, "Back button twice to app exit", false),
        Gap,
        sw(K.KEEP_TABS, "Restore tabs on launch", true)
    )

    private fun advancedRows(): List<Row> = listOf(
        sw(K.JS, "JavaScript", true),
        sw(K.COOKIES, "Cookies", true),
        sw(K.THIRD_COOKIES, "Third-party cookies", true),
        sw(K.SAVE_HISTORY, "Save history", true),
        Gap,
        sw(K.DESKTOP_ALL, "Request desktop site on all tabs", false),
        ch(K.UA, "User agent", listOf("Android", "Desktop", "iPhone"), 0),
        Gap,
        sw("adv_dom_storage", "Allow site storage (DOM storage)", true, "Needed by many sites to keep you signed in."),
        sw("adv_mixed", "Allow mixed content", true, "Loads http items on https pages.")
    )

    private fun siteRows(): List<Row> = listOf(
        act("Home page", "When you tap the home icon", { prefs.homepage.ifEmpty { "Orbit start page" } }) {
            Ui.prompt(this, "Home page", "https://… (empty = start page)", prefs.homepage) {
                prefs.setStr(K.HOMEPAGE, it); adapter.notifyDataSetChanged()
            }
        },
        ch(K.START_PAGE, "Start page", listOf("Last visited page", "Home page"), 0, "When starting the app"),
        ch(K.NEW_TAB, "New tab", listOf("Home page", "Blank page"), 0, "When creating a new tab"),
        Gap,
        act("Permission") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { toast("Open Android settings > Apps > Orbit") }
        },
        Gap,
        ch(K.ENGINE, "Search engine", Engines.names, 1),
        ch(K.UA, "User agent", listOf("Android", "Desktop", "iPhone"), 0),
        Gap,
        sw("open_link_app", "Open link with app", false),
        ch(K.LP_LINK, "Link (Long press)", listOf("Menu", "Open in new tab", "Copy link"), 0),
        ch(K.LP_IMAGE, "Image (Long press)", listOf("Menu", "Save image", "Open in new tab"), 0)
    )

    private fun backupRows(): List<Row> = listOf(
        Head(R.drawable.ic_folder, "Storage"),
        act("Import") { backupIn.launch(arrayOf("application/json", "*/*")) },
        act("Export") { backupOut.launch("orbit-backup.json") },
        Head(R.drawable.ic_bookmarks, "Bookmarks"),
        act("Import bookmarks (HTML)") { importHtml.launch(arrayOf("text/html", "*/*")) },
        act("Export bookmarks (HTML)") { exportHtml.launch("orbit-bookmarks.html") },
        act("Export bookmarks (CSV)") { exportCsv.launch("orbit-bookmarks.csv") }
    )

    private fun webCleanerRows(): List<Row> = listOf(
        sw(K.ADBLOCK, "Ad blocker", true, "At least one filter must be active to block ads."),
        sw("element_inspect", "Element inspection (more fine-grained blocking)", true, "This feature uses more memory."),
        act("Ad filter", "", { "${AdBlocker.customCount()} custom domains" }) { filterLists() },
        sites("Ad whitelist", K.SITE_AD_OFF, "You can register sites where ads are allowed."),
        Gap,
        sw("block_area", "Block area", true, "Long press on the object you want to block and a menu will appear."),
        Gap,
        sw(K.OVERLAY, "Overlay blocker", false, "Hides big fixed banners and dialogs."),
        sites("Overlay Whitelist", K.SITE_OVERLAY_OFF, "You can register sites where overlays are allowed."),
        Gap,
        ch(K.POPUP, "Popup blocker", listOf("Not used", "Normal", "Block all"), 1,
            "Block when other sites are opened (including advertising sites)"),
        sites("Popup whitelist", K.SITE_POPUP_OFF, "You can register sites where popups are allowed."),
        Gap,
        ch("block_app_exec", "Block app execution", listOf("Not blocked", "Always blocked"), 1,
            "Block the site from running other apps."),
        Gap,
        Act("Blocked Links", { siteSummary(K.BLOCKED_LINKS) }, "") {
            Ui.prompt(this, "Blocked Links", "one domain per line", prefs.str(K.BLOCKED_LINKS), multiline = true) { t ->
                prefs.setStr(K.BLOCKED_LINKS, t)
                AdBlocker.importList(this, t)
                adapter.notifyDataSetChanged()
            }
        },
        ch("lock_screen", "Lock screen", listOf("Not used", "Used"), 0),
        Gap,
        sw(K.IMAGES_OFF, "Blocked images", false, "Blocks images to save data; pages load faster."),
        sites("JavaScript blacklist", K.SITE_JS_OFF, "Sites where JavaScript is turned off."),
        sw(K.AMP_OFF, "Disable Google AMP", true, "Opens the original page instead.")
    )

    private fun downloadRows(): List<Row> = listOf(
        ch("dl_manager", "Download manager", listOf("Default", "Other apps"), 0),
        act("Download location", "", { "Device/Download/Orbit" }),
        Gap,
        ch("dl_fast", "Fast download", listOf("Off", "x2", "x4", "x8", "x10"), 4, "Some sites may not be supported."),
        optNum("dl_retries", "Number of retries", "None", listOf(1, 3, 5, 10), 4, "Number of retries in case of connection failure"),
        optNum("dl_concurrent", "Concurrent download", "Unlimited", listOf(1, 2, 3, 5), 0,
            "If the number of downloads exceeds the limit, it will be queued.\nDownloading too many files at the same time can slow device and cause problems."),
        ch("dl_bg_timeout", "Background state timeout", listOf("Unlimited", "30 minutes", "1 hour", "2 hours", "6 hours"), 3,
            "If the time limit is exceeded while the app is in the background, downloads will be paused to save battery.\nThe timer is released when the app goes to the foreground."),
        sw("dl_notify", "Download completion notification", true),
        Gap,
        Note("Due to YouTube policy, YouTube videos can not be downloaded.")
    )

    private fun mediaRows(): List<Row> = listOf(
        sw(K.MEDIA_DETECT, "Video manager button", true,
            "Floating button. You can move the button by dragging it. When video is detected, a button will appear on the screen. Some sites need to play the video before the video is detected. YouTube is not supported."),
        col("media_btn_color", "Button color"),
        Gap,
        sw("bg_play", "Background playback", false, "Some sites may not be supported."),
        Note("Due to YouTube policy, YouTube background playback is not supported."),
        Gap,
        Head(R.drawable.ic_play_arrow, "YouTube"),
        ch("yt_button", "YouTube manager button", listOf("Not used", "Floating button"), 1, "You can move the button by dragging it."),
        col("yt_btn_color", "Button color"),
        Gap,
        sw("yt_autoplay", "YouTube autoplay", true),
        sw("yt_unmute", "Unmute YouTube", true)
    )

    private fun memoryRows(): List<Row> = listOf(
        Head(R.drawable.ic_memo, "Page"),
        sw("mem_back_norefresh", "Go back without refresh", true, "Some sites may not be supported.\nThis feature uses more memory."),
        optNum("mem_page_limit", "Memory limit", "Unlimited", listOf(1, 3, 5, 10), 3,
            "If you exceed the limit, memory is automatically released starting with the oldest pages.\nIf you have too many pages open, your browser may be slow."),
        Head(R.drawable.ic_copy, "Tab"),
        optNum("tab_open_limit", "Open limit", "Unlimited", listOf(5, 10, 20, 50), 0,
            "If too many tabs are open, the browser may slow down."),
        optNum("mem_tab_limit", "Memory limit", "Unlimited", listOf(1, 3, 5, 10), 3,
            "If you exceed the limit, memory is automatically released starting with the oldest tabs.\nIf too many tabs are open, the browser may slow down."),
        sites("Memory release prevention list", K.MEM_KEEP, ""),
        Gap,
        ch("mem_retention", "Memory retention period", listOf("1 day", "2 day", "7 day", "Unlimited"), 1,
            "If you keep memory too long, your browser may become slower.")
    )

    private fun displayRows(): List<Row> = listOf(
        ch(K.THEME, "UI", listOf("System", "Light", "Dark"), 2) { OrbitApp.applyNightMode(prefs.int(K.THEME)) },
        sw(K.PAGE_DARK, "Web page dark mode", false, "Darkens page colours, keeps images normal."),
        Gap,
        sw("dark_image", "Dark image", true, "Applies only when the webpage is in dark mode."),
        ch("img_bright", "Image brightness", listOf("50%", "60%", "70%", "80%", "100%"), 2),
        Gap,
        ch("header_mode", "Header", listOf("System", "Custom"), 1),
        col("header_color", "Header color", 0xFF000000.toInt()),
        sw("dark_home", "Dark mode Home", true, "When you set a wallpaper, your homepage will always be in dark mode."),
        Gap,
        ch(K.SCREEN_FILTER, "Screen filter", listOf("Not used", "Warm", "Dim"), 0),
        col(K.FILTER_COLOR, "Filter color", 0xFF222222.toInt()),
        Gap,
        ch(K.BRIGHTNESS, "Brightness", listOf("System", "Low", "Medium", "High"), 0, "Depends on system setting"),
        ch(K.SCREEN_TIMEOUT, "Screen timeout", listOf("System", "Always on"), 0, "Depends on system setting"),
        ch(K.ROTATION, "Screen rotation", listOf("Auto", "Portrait", "Landscape"), 0)
    )

    private fun layoutRows(): List<Row> = listOf(
        sw(K.ADDR_BOTTOM, "Address bar at bottom", false, "Moves the address bar and tab bar to the bottom."),
        sw(K.NAV_BAR, "Bottom bar", false, "Back, forward, home, bookmarks, tabs, menu"),
        sw(K.SCROLL_HIDE, "Hide bars while scrolling", false),
        sw(K.TAB_BAR, "Show tab bar", true)
    )

    private fun menuRows(): List<Row> = listOf(
        ch(K.MENU_STYLE, "Menu style", listOf("Popup", "Bottom sheet"), 0, "How the three-dot menu opens."),
        Gap,
        Note("The popup menu has quick buttons on top (reload, ad blocker, tabs, settings) and a bottom row with Incognito tab, Desktop site and DNS.")
    )

    private fun tabRows(): List<Row> = listOf(
        ch("tab_add_pos", "Where to add tabs", listOf("Next to the current tab", "End of the list"), 0) {
            prefs.setBool(K.TAB_NEXT, prefs.int("tab_add_pos") == 0)
        },
        sw("tab_autogroup", "Automatic grouping", true, "They are automatically grouped when a new window opens on the site."),
        Gap,
        ch("tab_bar_show", "Show tab bar", listOf("Hidden", "Below top bar"), 1) {
            prefs.setBool(K.TAB_BAR, prefs.int("tab_bar_show") == 1)
        },
        ch(K.TAB_VIEW, "Tab list style", listOf("Grid", "List", "Small list"), 0),
        ch("swipe_up_tabbar", "Swipe up tab bar", listOf("Nothing", "Tab list"), 1),
        ch("swipe_down_tabbar", "Swipe down tab bar", listOf("Nothing", "Close tab"), 1),
        Gap,
        sw("tab_indicator", "Tab indicator", true, "Show tab position and number of tabs on tab button"),
        sw("tab_loop", "Tab loop", true, "Switch between first and last tab"),
        sw("tap_anim", "Use tap animation", true),
        Gap,
        ch("undelete", "Undelete", listOf("Nothing", "Web page", "Tab list", "Web page, Tab list"), 3),
        sw("tab_keyboard", "Activate keyboard when adding tab", false),
        sw(K.HOME_CLOSE_ALL, "Click on the homepage icon to close all tabs", false),
        Gap,
        sw("last_page_notify", "Last page notification", false,
            "If you click the back button on the last page of the tab, it is notified that it is the last page of the tab."),
        sw("swipe_close_last", "Swipe the page from the last page to close the tab", true),
        ch(K.BACK_LAST, "Previous button behavior on last page", listOf("Close tab", "Do nothing", "Exit app"), 0)
    )

    private fun compositionRows(): List<Row> = listOf(
        Nav(R.drawable.ic_fullscreen, "Full screen and Toolbar", "fullscreen"),
        Nav(R.drawable.ic_desktop, "Landscape mode", "landscape"),
        Nav(R.drawable.ic_add, "Floating button", "floatbtn"),
        Gap,
        ch(K.ADDR_LABEL, "Address bar label", listOf("Domain", "Title", "Full address"), 0),
        multi("suggest_sources", "Search suggestions", listOf("Google", "Recent searches", "History", "Bookmark"), 15),
        sw("autocomplete", "Auto complete", true),
        Gap,
        optNum("recent_max", "Recent searches", "Unlimited", listOf(10, 20, 50), 0),
        sw("recent_incognito", "Save recent searches in incognito mode", false),
        Gap,
        sw("onehand", "One-handed mode button", false, "You can move the button by dragging it."),
        col("onehand_color", "Button color", 0xFF2E3A8C.toInt()),
        ch("onehand_size", "Area size", listOf("Small", "Medium", "Large"), 1),
        Gap,
        sw(K.PULL_REFRESH, "Pull to refresh", true),
        sw(K.BACK_TOP, "Back to top button", true),
        ch(K.SHOW_SCROLLBAR, "Show scroll bar", listOf("Don't show", "Show"), 0),
        sw(K.VOL_SCROLL, "Scroll web pages with volume keys", false),
        sw("scroll_anim", "Use scroll animation", false)
    )

    private fun fullscreenRows(): List<Row> = listOf(
        sw(K.FULLSCREEN, "Full screen", false, "Hides the status bar and navigation bar."),
        sw(K.SCROLL_HIDE, "Hide bars while scrolling", false)
    )

    private fun landscapeRows(): List<Row> = listOf(
        ch(K.ROTATION, "Screen rotation", listOf("Auto", "Portrait", "Landscape"), 0),
        sw("land_hide_tabbar", "Hide tab bar in landscape", false)
    )

    private fun floatRows(): List<Row> = listOf(
        sw("float_btn", "Floating button", false, "You can move the button by dragging it."),
        ch("float_action", "Button action", listOf("Back to top", "Tab list", "New tab"), 0),
        col("float_color", "Button color")
    )

    private fun webContentRows(): List<Row> = listOf(
        ch(K.TEXT_ZOOM, "Text size", listOf("80%", "90%", "100%", "115%", "130%", "150%"), 100,
            values = listOf(80, 90, 100, 115, 130, 150)),
        sw("allow_zoom", "Allow pinch zoom", true),
        sw(K.DESKTOP_ALL, "Request desktop site on all tabs", false),
        sw(K.IMAGES_OFF, "Block images", false, "Saves data; pages load faster."),
        sw(K.PAGE_DARK, "Web page dark mode", false)
    )

    private fun gestureRows(): List<Row> = listOf(
        sw("swipe_nav", "Swipe navigation", true, "Swipe from the screen edge to go back or forward."),
        sw(K.PULL_REFRESH, "Pull to refresh", true),
        sw(K.VOL_SCROLL, "Scroll web pages with volume keys", false),
        ch("double_tap", "Double tap on page", listOf("Nothing", "Back to top"), 0)
    )

    private fun translatorRows(): List<Row> = listOf(
        ch("tr_engine", "Translator", listOf("Google"), 0),
        ch(K.TR_LANG, "Translate into", Langs.labels, 0, "System follows your phone language."),
        sw("tr_auto", "Automatic translation", false),
        sites("Translation blacklist", K.TR_BLACK, "Sites that are never translated automatically.")
    )

    private fun ttsRows(): List<Row> = listOf(
        ch(K.TTS_RATE, "Reading speed", listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"), 2,
            "Used by Read aloud in the menu."),
        act("System text-to-speech settings", "Voice and language are set in Android.") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: Exception) { toast("Open Android settings > Text-to-speech") }
        }
    )

    private fun tvCastRows(): List<Row> = listOf(
        act("Cast screen", "Opens Android's cast / screen-mirroring panel.") {
            try { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
            catch (_: Exception) { toast("No cast settings found on this phone") }
        },
        Gap,
        Note("Play the video full screen first, then start casting from Android.")
    )

    private fun securityRows(): List<Row> = listOf(
        sw(K.HTTPS_FIRST, "HTTPS first", true, "Tries https:// before http://"),
        sw(K.SSL_BLOCK, "Block pages with certificate errors", true),
        sw(K.SAFE_BROWSING, "Safe Browsing", true, "Warns about dangerous sites."),
        Gap,
        sw(K.COOKIES, "Cookies", true),
        sw(K.THIRD_COOKIES, "Third-party cookies", true),
        sw(K.SAVE_HISTORY, "Save history", true)
    )

    private fun incognitoRows(): List<Row> = listOf(
        sw(K.INCOG_SECURE, "Hide incognito in recent apps", true),
        sw(K.KEY_SECURE, "Incognito keyboard privacy", true, "Asks the keyboard not to learn what you type."),
        sw("incog_external", "Open links from other apps in incognito", false)
    )

    private fun passwordRows(): List<Row> = listOf(
        sw(K.APP_LOCK, "App lock", false, "Asks for your device PIN, pattern, password or fingerprint.") {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (prefs.appLock && !km.isDeviceSecure) {
                prefs.setBool(K.APP_LOCK, false)
                toast("Set a screen lock in Android settings first")
                adapter.notifyDataSetChanged()
            }
        },
        sw("incog_lock", "Lock incognito tabs", false)
    )

    private fun clearDataRows(): List<Row> = listOf(
        sw(K.EXIT_HISTORY, "Delete history on exit", false),
        sw(K.EXIT_COOKIES, "Delete cookies and site data on exit", false),
        sw(K.EXIT_CACHE, "Delete cache on exit", false),
        Gap,
        act("Clear browsing data now") { clearNow() }
    )

    private val dnsNames = listOf("System default", "Cloudflare", "Google", "Quad9", "CleanBrowsing", "AdGuard")
    private val dnsHosts = listOf(
        "", "one.one.one.one", "dns.google", "dns.quad9.net",
        "family-filter-dns.cleanbrowsing.org", "dns.adguard-dns.com"
    )

    private fun dnsRows(): List<Row> = listOf(
        ch("dns_provider", "Secure DNS", dnsNames, 0,
            "Android applies secure DNS to the whole phone. Pick a provider, then paste the copied hostname into Private DNS.") {
            val host = dnsHosts.getOrElse(prefs.int("dns_provider")) { "" }
            if (host.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dns", host))
                toast("Copied $host")
                secureDns()
            }
        },
        act("Open Private DNS settings") {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
        }
    )

    // ------------------------------------------------------------------ rows on screen

    private fun isCard(i: Int): Boolean = rows.getOrNull(i)?.let { it !is Gap && it !is Head } ?: false

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Head -> 0
            is Gap -> 1
            else -> 2
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> object : RecyclerView.ViewHolder(inf.inflate(R.layout.row_header, parent, false)) {}
                1 -> object : RecyclerView.ViewHolder(View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.context.dp(22))
                }) {}
                else -> object : RecyclerView.ViewHolder(inf.inflate(R.layout.row_setting, parent, false)) {}
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val v = h.itemView
            if (r is Gap) return
            if (r is Head) {
                v.findViewById<ImageView>(R.id.headerIcon).setImageResource(r.icon)
                v.findViewById<TextView>(R.id.headerText).text = r.text
                return
            }

            val body = v.findViewById<View>(R.id.rowBody)
            val icon = v.findViewById<ImageView>(R.id.rowIcon)
            val title = v.findViewById<TextView>(R.id.rowTitle)
            val value = v.findViewById<TextView>(R.id.rowValue)
            val info = v.findViewById<TextView>(R.id.rowInfo)
            val sw = v.findViewById<SwitchCompat>(R.id.rowSwitch)
            val swatch = v.findViewById<View>(R.id.rowSwatch)
            val divider = v.findViewById<View>(R.id.rowDivider)

            val top = !isCard(position - 1)
            val bottom = !isCard(position + 1)
            v.setBackgroundResource(
                when {
                    top && bottom -> R.drawable.bg_c_single
                    top -> R.drawable.bg_c_top
                    bottom -> R.drawable.bg_c_bottom
                    else -> R.drawable.bg_c_mid
                }
            )
            divider.visibility = if (bottom) View.GONE else View.VISIBLE
            body.setBackgroundResource(ripple)

            icon.visibility = View.GONE
            sw.visibility = View.GONE
            swatch.visibility = View.GONE
            title.setTextColor(color(R.color.text))
            title.textSize = 16f
            body.setOnClickListener(null)

            var valueText = ""
            var infoText = ""
            when (r) {
                is Nav -> {
                    icon.visibility = View.VISIBLE
                    icon.setImageResource(r.icon)
                    title.text = r.title
                    body.setOnClickListener { openPage(r.page) }
                }
                is Sw -> {
                    title.text = r.title
                    infoText = r.info
                    sw.visibility = View.VISIBLE
                    sw.isChecked = prefs.bool(r.key)
                    body.setOnClickListener {
                        prefs.setBool(r.key, !prefs.bool(r.key))
                        sw.isChecked = prefs.bool(r.key)
                        r.after?.invoke()
                    }
                }
                is Ch -> {
                    title.text = r.title
                    val idx = r.values.indexOf(prefs.int(r.key)).let { if (it < 0) 0 else it }
                    valueText = r.labels[idx]
                    infoText = r.info
                    body.setOnClickListener {
                        Ui.listSheet(this@SettingsActivity, r.title, r.labels.mapIndexed { i, l ->
                            l to {
                                prefs.setInt(r.key, r.values[i])
                                r.after?.invoke()
                                notifyItemChanged(position)
                            }
                        })
                    }
                }
                is Multi -> {
                    title.text = r.title
                    val mask = prefs.int(r.key)
                    valueText = r.labels.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ").ifEmpty { "None" }
                    body.setOnClickListener {
                        val checked = BooleanArray(r.labels.size) { i -> mask and (1 shl i) != 0 }
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(r.title)
                            .setMultiChoiceItems(r.labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                            .setPositiveButton("OK") { _, _ ->
                                var m = 0
                                checked.forEachIndexed { i, on -> if (on) m = m or (1 shl i) }
                                prefs.setInt(r.key, m)
                                notifyItemChanged(position)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                is Col -> {
                    title.text = r.title
                    swatch.visibility = View.VISIBLE
                    (swatch.background.mutate() as? GradientDrawable)?.setColor(prefs.int(r.key))
                    body.setOnClickListener {
                        Ui.listSheet(this@SettingsActivity, r.title, PALETTE.map { (name, c) ->
                            name to {
                                prefs.setInt(r.key, c)
                                notifyItemChanged(position)
                            }
                        })
                    }
                }
                is Act -> {
                    title.text = r.title
                    valueText = r.value()
                    infoText = r.info
                    body.setOnClickListener { r.run() }
                }
                is Note -> {
                    title.text = r.text
                    title.setTextColor(color(R.color.text_sub))
                    title.textSize = 14f
                }
                else -> {}
            }
            value.text = valueText
            value.visibility = if (valueText.isEmpty()) View.GONE else View.VISIBLE
            info.text = infoText
            info.visibility = if (infoText.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openPage(p: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", p))
    }

    private fun rowTitle(r: Row): String? = when (r) {
        is Nav -> r.title
        is Sw -> r.title
        is Ch -> r.title
        is Multi -> r.title
        is Col -> r.title
        is Act -> r.title
        else -> null
    }

    private fun searchSettings() {
        Ui.prompt(this, "Search settings", "e.g. popup") { q ->
            if (q.isBlank()) return@prompt
            val hits = ArrayList<Pair<String, () -> Unit>>()
            for (pg in allPages) {
                for (r in build(pg)) {
                    val t = rowTitle(r) ?: continue
                    if (t.contains(q, ignoreCase = true)) hits.add("$t  ·  ${pageTitle(pg)}" to { openPage(pg) })
                }
            }
            if (hits.isEmpty()) toast("No match") else Ui.listSheet(this, "Results", hits)
        }
    }

    private fun resetPage() {
        AlertDialog.Builder(this)
            .setTitle("Reset")
            .setMessage("Restore this page's settings to their defaults?")
            .setPositiveButton("Reset") { _, _ ->
                val e = prefs.sp.edit()
                rows.forEach { r ->
                    when (r) {
                        is Sw -> e.remove(r.key)
                        is Ch -> e.remove(r.key)
                        is Multi -> e.remove(r.key)
                        is Col -> e.remove(r.key)
                        else -> {}
                    }
                }
                e.apply()
                rows.forEach { r ->
                    when (r) {
                        is Sw -> r.after?.invoke()
                        is Ch -> r.after?.invoke()
                        else -> {}
                    }
                }
                OrbitApp.applyNightMode(prefs.int(K.THEME))
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun siteSummary(key: String): String {
        val n = prefs.siteList(key).size
        return if (n == 0) "None" else "$n site(s)"
    }

    private fun editSites(title: String, key: String) {
        Ui.prompt(this, title, "one site per line, e.g. example.com", prefs.str(key), multiline = true) {
            prefs.setStr(key, it.lines().map { l -> l.trim().lowercase().removePrefix("www.") }.filter { l -> l.isNotEmpty() }.joinToString("\n"))
            adapter.notifyDataSetChanged()
        }
    }

    private fun filterLists() {
        Ui.listSheet(
            this, "Ad filter", listOf(
                "Import from web address" to {
                    Ui.prompt(this, "Filter list address", "https://…/hosts or Adblock list") { url ->
                        if (url.startsWith("http")) fetchList(url) else toast("Enter a full web address")
                    }
                },
                "Paste rules" to {
                    Ui.prompt(this, "Paste filter rules", "hosts lines or ||domain^ rules", multiline = true) { text ->
                        val n = AdBlocker.importList(this, text)
                        toast("Added $n domains"); adapter.notifyDataSetChanged()
                    }
                },
                "Remove all custom lists" to {
                    AdBlocker.clearCustom(this); adapter.notifyDataSetChanged()
                }
            )
        )
    }

    private fun fetchList(address: String) {
        toast("Downloading…")
        thread {
            val msg = try {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val n = AdBlocker.importList(this, text)
                "Added $n domains"
            } catch (e: Exception) {
                "Couldn't download the list"
            }
            runOnUiThread { toast(msg); adapter.notifyDataSetChanged() }
        }
    }

    private fun secureDns() {
        AlertDialog.Builder(this)
            .setTitle("Secure DNS")
            .setMessage(
                "Orbit uses the system WebView, which follows Android's Private DNS setting. " +
                    "Open Network settings, find Private DNS, choose \"Private DNS provider hostname\" and paste the hostname.\n\n" +
                    "Cloudflare: one.one.one.one\nGoogle: dns.google\nQuad9: dns.quad9.net\n" +
                    "CleanBrowsing: family-filter-dns.cleanbrowsing.org\nAdGuard: dns.adguard-dns.com"
            )
            .setPositiveButton("Open settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun defaultBrowser() {
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 1)
                return
            }
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            toast("Open Android settings > Default apps")
        }
    }

    private fun clearNow() {
        Ui.listSheet(
            this, "Clear browsing data", listOf(
                "History" to { store.clearHistory(); toast("History cleared") },
                "Cookies and site data" to {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    toast("Cookies cleared")
                },
                "Cache" to { WebView(this).apply { clearCache(true); destroy() }; toast("Cache cleared") },
                "Everything" to {
                    store.clearHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    WebView(this).apply { clearCache(true); destroy() }
                    toast("Cleared")
                }
            )
        )
    }

    // ------------------------------------------------------------------ import / export

    private fun write(u: Uri, text: String) {
        try {
            contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) }
            toast("Saved")
        } catch (_: Exception) {
            toast("Couldn't save the file")
        }
    }

    private fun read(u: Uri): String = try {
        contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: Exception) { "" }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun bookmarksHtml(): String {
        val sb = StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        store.bookmarks().forEach {
            sb.append("    <DT><A HREF=\"${esc(it.url)}\" ADD_DATE=\"${it.time / 1000}\">${esc(it.title.ifBlank { it.url })}</A>\n")
        }
        return sb.append("</DL><p>\n").toString()
    }

    private fun bookmarksCsv(): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("title,url\n")
        store.bookmarks().forEach { sb.append(q(it.title)).append(',').append(q(it.url)).append('\n') }
        return sb.toString()
    }

    private fun importBookmarks(html: String) {
        val re = Regex("<A[^>]*?HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var n = 0
        re.findAll(html).forEach { m ->
            val url = m.groupValues[1].replace("&amp;", "&")
            if (url.startsWith("http")) {
                store.addBookmark(Entry(m.groupValues[2].replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim(), url))
                n++
            }
        }
        toast(if (n == 0) "No bookmarks found in that file" else "Imported $n bookmarks")
    }

    private fun backupJson(): String {
        val settings = JSONObject()
        for ((k, v) in prefs.sp.all) {
            when (v) {
                is Boolean -> settings.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> settings.put(k, JSONObject().put("t", "i").put("v", v))
                is String -> settings.put(k, JSONObject().put("t", "s").put("v", v))
            }
        }
        val orbit = getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val data = JSONObject()
        listOf("dial", "bookmarks").forEach { k -> orbit.getString(k, null)?.let { data.put(k, it) } }
        return JSONObject().put("orbit_backup", 1).put("settings", settings).put("data", data).toString(2)
    }

    private fun restoreJson(text: String) {
        try {
            val o = JSONObject(text)
            if (!o.has("orbit_backup")) { toast("That isn't an Orbit backup"); return }
            val s = o.getJSONObject("settings")
            val e = prefs.sp.edit()
            s.keys().forEach { k ->
                val item = s.getJSONObject(k)
                when (item.getString("t")) {
                    "b" -> e.putBoolean(k, item.getBoolean("v"))
                    "i" -> e.putInt(k, item.getInt("v"))
                    else -> e.putString(k, item.getString("v"))
                }
            }
            e.apply()
            val d = o.optJSONObject("data")
            if (d != null) {
                val oe = getSharedPreferences("orbit", Context.MODE_PRIVATE).edit()
                d.keys().forEach { k -> if (k == "dial" || k == "bookmarks") oe.putString(k, d.getString(k)) }
                oe.apply()
            }
            OrbitApp.applyNightMode(prefs.int(K.THEME))
            adapter.notifyDataSetChanged()
            toast("Backup restored")
        } catch (_: Exception) {
            toast("Couldn't read that backup")
        }
    }

    companion object {
        private const val LAVENDER = 0xFFA4A4EA.toInt()
        private val PALETTE: List<Pair<String, Int>> = listOf(
            "Lavender" to 0xFFA4A4EA.toInt(),
            "Blue" to 0xFF2E3A8C.toInt(),
            "Green" to 0xFF2E9E4A.toInt(),
            "Red" to 0xFFE53935.toInt(),
            "Orange" to 0xFFFB8C00.toInt(),
            "Pink" to 0xFFD81B60.toInt(),
            "Dark grey" to 0xFF222222.toInt(),
            "Black" to 0xFF000000.toInt(),
            "White" to 0xFFFFFFFF.toInt()
        )
    }
}
