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
        "display", "layout", "menu", "tab", "composition", "fullscreen",
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
        sw(K.KEYBOARD_START, "Keyboard activation on app startup", false, "Opens the keyboard when Orbit starts on the start page."),
        sw(K.BACK_TWICE, "Back button twice to app exit", false),
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
        sw("adv_dom_storage", "Site storage (DOM storage)", true, "Needed by many sites to keep you signed in."),
        sw("adv_mixed", "Allow mixed content", true, "Loads http items on https pages.")
    )

    private fun siteRows(): List<Row> = listOf(
        act("Home page", "When you tap the home icon", { prefs.homepage.ifEmpty { "Orbit start page" } }) {
            Ui.prompt(this, "Home page", "https://… (empty = start page)", prefs.homepage) {
                prefs.setStr(K.HOMEPAGE, it); adapter.notifyDataSetChanged()
            }
        },
        act("Permission", "Camera, microphone, location and notifications are set in Android.") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { toast("Open Android settings > Apps > Orbit") }
        },
        Gap,
        ch(K.ENGINE, "Search engine", Engines.names, 1),
        Gap,
        ch(K.LP_LINK, "Link (Long press)", listOf("Menu", "Open in new tab", "Copy link"), 0),
        ch(K.LP_IMAGE, "Image (Long press)", listOf("Menu", "Save image", "Open in new tab"), 0)
    )

    private fun backupRows(): List<Row> = listOf(
        Head(R.drawable.ic_folder, "Storage"),
        act("Import", "Restore settings, start page and bookmarks from a backup file.") { backupIn.launch(arrayOf("application/json", "*/*")) },
        act("Export", "Save settings, start page and bookmarks to a file.") { backupOut.launch("orbit-backup.json") },
        Head(R.drawable.ic_bookmarks, "Bookmarks"),
        act("Import bookmarks (HTML)") { importHtml.launch(arrayOf("text/html", "*/*")) },
        act("Export bookmarks (HTML)") { exportHtml.launch("orbit-bookmarks.html") },
        act("Export bookmarks (CSV)") { exportCsv.launch("orbit-bookmarks.csv") }
    )

    private fun webCleanerRows(): List<Row> = listOf(
        sw(K.ADBLOCK, "Ad blocker", true, "At least one filter must be active to block ads."),
        act("Ad filter", "Add filter lists from a web address or paste rules.", { "${AdBlocker.customCount()} custom domains" }) { filterLists() },
        sites("Ad whitelist", K.SITE_AD_OFF, "You can register sites where ads are allowed."),
        Gap,
        sw(K.OVERLAY, "Overlay blocker", false, "Hides big fixed banners and dialogs."),
        sites("Overlay Whitelist", K.SITE_OVERLAY_OFF, "You can register sites where overlays are allowed."),
        Gap,
        ch(K.POPUP, "Popup blocker", listOf("Not used", "Normal", "Block all"), 1,
            "Normal blocks popups that open without you tapping."),
        sites("Popup whitelist", K.SITE_POPUP_OFF, "You can register sites where popups are allowed."),
        Gap,
        Act("Blocked Links", { siteSummary(K.BLOCKED_LINKS) }, "Domains that are never loaded.") {
            Ui.prompt(this, "Blocked Links", "one domain per line", prefs.str(K.BLOCKED_LINKS), multiline = true) { t ->
                prefs.setStr(K.BLOCKED_LINKS, t)
                AdBlocker.importList(this, t)
                adapter.notifyDataSetChanged()
            }
        },
        sw(K.IMAGES_OFF, "Block images", false, "Saves data; pages load faster."),
        sites("JavaScript blacklist", K.SITE_JS_OFF, "Sites where JavaScript is turned off."),
        sw(K.AMP_OFF, "Disable Google AMP", true, "Opens the original page instead.")
    )

    private fun downloadRows(): List<Row> = listOf(
        act("Downloads", "Open the list of downloads.") { startActivity(Intent(this, DownloadsActivity::class.java)) },
        act("Download location", "", { "Downloads/Orbit" }),
        Gap,
        sw("dl_ask", "Ask before downloading", false, "Shows the file name and asks first."),
        sw("dl_wifi", "Wi-Fi only", false, "Don't use mobile data for downloads."),
        sw("dl_notify", "Download completion notification", true),
        Gap,
        Note("Files are downloaded by Android's download manager, so they keep going in the background. " +
            "Due to YouTube policy, YouTube videos can not be downloaded.")
    )

    private fun mediaRows(): List<Row> = listOf(
        sw(K.MEDIA_DETECT, "Video manager button", true,
            "When a page plays video, a button appears that you can drag. Tap it to play or save the video, stream or subtitles. " +
                "Some sites need to start playing before the video is found."),
        Gap,
        Note("Due to YouTube policy, YouTube videos can not be downloaded.")
    )

    private fun memoryRows(): List<Row> = listOf(
        ch("mem_tab_limit", "Tabs kept in memory", listOf("Unlimited", "3", "5", "10"), 0,
            "When more tabs are open than the limit, the ones farthest from the current tab are released and reload when you open them. Incognito tabs are never released.",
            values = listOf(0, 3, 5, 10)),
        Gap,
        Note("When Android is low on memory, Orbit also releases background tabs on its own.")
    )

    private fun displayRows(): List<Row> = listOf(
        ch(K.THEME, "UI", listOf("System", "Light", "Dark"), 2) { OrbitApp.applyNightMode(prefs.int(K.THEME)) },
        sw(K.PAGE_DARK, "Web page dark mode", false, "Darkens page colours, keeps images normal."),
        Gap,
        ch(K.BRIGHTNESS, "Brightness", listOf("System", "Low", "Medium", "High"), 0, "Depends on system setting"),
        ch(K.SCREEN_TIMEOUT, "Screen timeout", listOf("System", "Always on"), 0, "Depends on system setting"),
        ch(K.ROTATION, "Screen rotation", listOf("Auto", "Portrait", "Landscape"), 0)
    )

    private fun layoutRows(): List<Row> = listOf(
        sw(K.ADDR_BOTTOM, "Address bar at bottom", false, "Moves the address bar and tab bar to the bottom."),
        sw(K.NAV_BAR, "Bottom bar", false, "Back, forward, home, bookmarks, tabs, menu"),
        sw(K.TAB_BAR, "Show tab bar", true)
    )

    private fun menuRows(): List<Row> = listOf(
        ch(K.MENU_STYLE, "Menu style", listOf("Popup", "Bottom sheet"), 0, "How the three-dot menu opens."),
        Gap,
        Note("The popup menu has quick buttons on top (reload, ad blocker, bookmark, bookmarks, settings) and Incognito, Desktop and DNS at the bottom.")
    )

    private fun tabRows(): List<Row> = listOf(
        ch("tab_add_pos", "Where to add tabs", listOf("Next to the current tab", "End of the list"), 0) {
            prefs.setBool(K.TAB_NEXT, prefs.int("tab_add_pos") == 0)
        },
        ch(K.TAB_VIEW, "Tab list style", listOf("Grid", "List", "Small list"), 0),
        Gap,
        sw(K.KEEP_TABS, "Restore tabs on launch", true)
    )

    private fun compositionRows(): List<Row> = listOf(
        Nav(R.drawable.ic_fullscreen, "Full screen and Toolbar", "fullscreen"),
        Gap,
        ch(K.ADDR_LABEL, "Address bar label", listOf("Domain", "Title", "Full address"), 0),
        Gap,
        sw(K.PULL_REFRESH, "Pull to refresh", true),
        sw(K.BACK_TOP, "Back to top button", true),
        ch(K.SHOW_SCROLLBAR, "Show scroll bar", listOf("Don't show", "Show"), 0),
        sw(K.VOL_SCROLL, "Scroll web pages with volume keys", false)
    )

    private fun fullscreenRows(): List<Row> = listOf(
        sw(K.FULLSCREEN, "Full screen", false, "Hides the status bar and navigation bar."),
        sw(K.SCROLL_HIDE, "Hide bars while scrolling", false)
    )

    private fun webContentRows(): List<Row> = listOf(
        ch(K.TEXT_ZOOM, "Text size", listOf("80%", "90%", "100%", "115%", "130%", "150%"), 100,
            values = listOf(80, 90, 100, 115, 130, 150)),
        sw("allow_zoom", "Allow pinch zoom", true),
        sw(K.IMAGES_OFF, "Block images", false, "Saves data; pages load faster.")
    )

    private fun gestureRows(): List<Row> = listOf(
        sw(K.PULL_REFRESH, "Pull to refresh", true),
        sw(K.VOL_SCROLL, "Scroll web pages with volume keys", false)
    )

    private fun translatorRows(): List<Row> = listOf(
        ch(K.TR_LANG, "Translate into", Langs.labels, 0, "System follows your phone language. Used by Translate in the menu.")
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
        sw(K.SAFE_BROWSING, "Safe Browsing", true, "Warns about dangerous sites.")
    )

    private fun incognitoRows(): List<Row> = listOf(
        sw(K.INCOG_SECURE, "Hide incognito in recent apps", true),
        sw(K.KEY_SECURE, "Incognito keyboard privacy", true, "Asks the keyboard not to learn what you type.")
    )

    private fun passwordRows(): List<Row> = listOf(
        sw(K.APP_LOCK, "App lock", false, "Asks for your device PIN, pattern, password or fingerprint.") {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (prefs.appLock && !km.isDeviceSecure) {
                prefs.setBool(K.APP_LOCK, false)
                toast("Set a screen lock in Android settings first")
                adapter.notifyDataSetChanged()
            }
        }
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
