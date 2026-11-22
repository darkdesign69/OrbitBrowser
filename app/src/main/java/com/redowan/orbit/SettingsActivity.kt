package com.redowan.orbit

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** One long, sectioned settings list: section headers and toggle / choice / action rows. */
class SettingsActivity : AppCompatActivity() {

    private sealed class Row
    private class Header(val text: String) : Row()
    private class Sw(val key: String, val title: String, val info: String = "", val after: (() -> Unit)? = null) : Row()
    private class Choice(
        val key: String, val title: String, val labels: List<String>,
        val values: List<Int> = labels.indices.toList(), val after: (() -> Unit)? = null
    ) : Row()
    private class Act(val title: String, val info: () -> String = { "" }, val run: () -> Unit) : Row()

    private lateinit var prefs: Prefs
    private lateinit var store: Store
    private lateinit var adapter: RowAdapter
    private lateinit var rows: List<Row>

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
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.setTitle).text = "Settings"
        findViewById<View>(R.id.setBack).setOnClickListener { finish() }
        rows = buildRows()
        adapter = RowAdapter()
        val list = findViewById<RecyclerView>(R.id.setList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    private fun buildRows(): List<Row> = listOf(
        Header("General"),
        Choice(K.ENGINE, "Search engine", Engines.names),
        Act("Home page", { prefs.homepage.ifEmpty { "Start page (speed dial)" } }) {
            Ui.prompt(this, "Home page", "https://… (empty = start page)", prefs.homepage) {
                prefs.setStr(K.HOMEPAGE, it); adapter.notifyDataSetChanged()
            }
        },
        Sw(K.KEEP_TABS, "Restore tabs on launch"),
        Act("Set Orbit as default browser") { defaultBrowser() },

        Header("Appearance"),
        Choice(K.THEME, "Theme", listOf("System", "Light", "Dark")) {
            OrbitApp.applyNightMode(prefs.int(K.THEME))
        },
        Sw(K.PAGE_DARK, "Dark pages", "Inverts page colours, keeps images normal"),
        Choice(K.TEXT_ZOOM, "Text size", listOf("80%", "90%", "100%", "115%", "130%", "150%"),
            listOf(80, 90, 100, 115, 130, 150)),
        Sw(K.DESKTOP_ALL, "Request desktop site on all tabs"),

        Header("Toolbars and tabs"),
        Sw(K.ADDR_BOTTOM, "Address bar at bottom"),
        Sw(K.SCROLL_HIDE, "Hide bars while scrolling"),
        Sw(K.TAB_BAR, "Show tab bar"),
        Sw(K.TAB_NEXT, "Open new tab next to current tab"),
        Choice(K.TAB_VIEW, "Tab list style", listOf("Grid", "List", "Small list")),
        Sw(K.PULL_REFRESH, "Pull down to refresh"),
        Sw(K.BACK_TOP, "Back-to-top button"),
        Sw(K.VOL_SCROLL, "Volume keys scroll the page"),

        Header("Ads and blocking"),
        Sw(K.ADBLOCK, "Ad blocker", "Blocks ad and tracker hosts"),
        Act("Filter lists", { "${AdBlocker.customCount()} custom domains" }) { filterLists() },
        Choice(K.POPUP, "Popup blocker", listOf("Allow popups", "Block suspected ads", "Block all popups")),
        Sw(K.OVERLAY, "Remove page overlays", "Hides big fixed banners and dialogs"),
        Sw(K.AMP_OFF, "Disable Google AMP", "Opens the original page instead"),
        Sw(K.IMAGES_OFF, "Block images", "Saves data; pages load faster"),
        Sw(K.JS, "JavaScript"),
        Act("Sites with ad blocker off", { siteSummary(K.SITE_AD_OFF) }) {
            editSites("Ad blocker allow list", K.SITE_AD_OFF)
        },
        Act("Sites with JavaScript blocked", { siteSummary(K.SITE_JS_OFF) }) {
            editSites("JavaScript blacklist", K.SITE_JS_OFF)
        },

        Header("Privacy and security"),
        Sw(K.HTTPS_FIRST, "HTTPS first", "Tries https:// before http://"),
        Sw(K.SSL_BLOCK, "Block pages with certificate errors"),
        Sw(K.COOKIES, "Cookies"),
        Sw(K.THIRD_COOKIES, "Third-party cookies"),
        Sw(K.SAVE_HISTORY, "Save history"),
        Act("Secure DNS", { "Set through Android Private DNS" }) { secureDns() },
        Sw(K.APP_LOCK, "App lock", "Asks for your device PIN, pattern, password or fingerprint") {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (prefs.appLock && !km.isDeviceSecure) {
                prefs.setBool(K.APP_LOCK, false)
                toast("Set a screen lock in Android settings first")
                adapter.notifyDataSetChanged()
            }
        },
        Sw(K.INCOG_SECURE, "Hide incognito in recent apps"),
        Sw(K.KEY_SECURE, "Incognito keyboard privacy", "Asks the keyboard not to learn what you type"),
        Sw(K.EXIT_HISTORY, "Delete history on exit"),
        Sw(K.EXIT_COOKIES, "Delete cookies and site data on exit"),
        Sw(K.EXIT_CACHE, "Delete cache on exit"),
        Act("Clear browsing data now") { clearNow() },

        Header("Bookmarks and backup"),
        Act("Export bookmarks (HTML)") { exportHtml.launch("orbit-bookmarks.html") },
        Act("Export bookmarks (CSV)") { exportCsv.launch("orbit-bookmarks.csv") },
        Act("Import bookmarks (HTML)") { importHtml.launch(arrayOf("text/html", "*/*")) },
        Act("Back up settings, bookmarks and start page") { backupOut.launch("orbit-backup.json") },
        Act("Restore from backup") { backupIn.launch(arrayOf("application/json", "*/*")) },

        Header("About"),
        Act("Orbit Browser", { "Open-source browser on the system WebView" }) {}
    )

    // ------------------------------------------------------------------ rows

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (rows[position] is Header) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) object : RecyclerView.ViewHolder(inf.inflate(R.layout.row_header, parent, false)) {}
            else object : RecyclerView.ViewHolder(inf.inflate(R.layout.row_setting, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val v = h.itemView
            if (r is Header) { (v as TextView).text = r.text; return }
            val title = v.findViewById<TextView>(R.id.rowTitle)
            val info = v.findViewById<TextView>(R.id.rowInfo)
            val sw = v.findViewById<SwitchCompat>(R.id.rowSwitch)
            var infoText = ""
            when (r) {
                is Sw -> {
                    title.text = r.title
                    infoText = r.info
                    sw.visibility = View.VISIBLE
                    sw.isChecked = prefs.bool(r.key)
                    v.setOnClickListener {
                        prefs.setBool(r.key, !prefs.bool(r.key))
                        sw.isChecked = prefs.bool(r.key)
                        r.after?.invoke()
                    }
                }
                is Choice -> {
                    title.text = r.title
                    val cur = if (r.key == K.TEXT_ZOOM || r.values != r.labels.indices.toList()) prefs.int(r.key) else prefs.int(r.key)
                    val idx = r.values.indexOf(cur).let { if (it < 0) 0 else it }
                    infoText = r.labels[idx]
                    sw.visibility = View.GONE
                    v.setOnClickListener {
                        Ui.listSheet(this@SettingsActivity, r.title, r.labels.mapIndexed { i, l ->
                            l to {
                                prefs.setInt(r.key, r.values[i])
                                r.after?.invoke()
                                notifyItemChanged(position)
                            }
                        })
                    }
                }
                is Act -> {
                    title.text = r.title
                    infoText = r.info()
                    sw.visibility = View.GONE
                    v.setOnClickListener { r.run() }
                }
                else -> {}
            }
            info.text = infoText
            info.visibility = if (infoText.isEmpty()) View.GONE else View.VISIBLE
        }
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
            this, "Filter lists", listOf(
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
                    "Open Network settings, find Private DNS and choose \"Private DNS provider hostname\", then enter one of:\n\n" +
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
}
