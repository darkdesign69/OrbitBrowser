package com.redowan.orbit

import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatDialog
import android.content.res.Configuration
import android.os.Build
import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.util.Base64
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.hypot

/**
 * The browser screen: tab bar, address bar, page area (WebView or start page) and bottom bar.
 * Everything here is Orbit's own code, written from scratch.
 */
class MainActivity : AppCompatActivity() {

    lateinit var prefs: Prefs
    lateinit var store: Store

    val tabs = ArrayList<Tab>()
    var current = 0
    val cur: Tab get() = tabs[current]

    // views
    private lateinit var root: LinearLayout
    private lateinit var tabBarRow: View
    private lateinit var addrRow: View
    private lateinit var navRow: View
    private lateinit var tabStrip: RecyclerView
    private lateinit var urlField: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var topHome: ImageButton
    private lateinit var topTabs: View
    private lateinit var topTabCount: TextView
    private lateinit var topMenu: ImageButton
    private lateinit var fabMedia: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var tabCount: TextView
    private lateinit var webHolder: SwipeRefreshLayout
    private lateinit var startList: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var fabTop: ImageButton
    private lateinit var findBar: View
    private lateinit var findField: EditText
    private lateinit var findCount: TextView

    private val stripAdapter by lazy { TabStripAdapter(this) }
    private val dialAdapter by lazy { DialAdapter(this) }

    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private val upgraded = HashMap<Tab, String>()
    private var barsShown = true
    private var barAcc = 0
    private var lastToggle = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val mediaTick = Runnable { updateMediaFab() }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // full-screen video
    private var customView: View? = null
    private var lastBackAt = 0L
    private var tts: TextToSpeech? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    // file upload
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    // bookmarks / history screen result
    private val libLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data ?: return@registerForActivityResult
        val u = d.getStringExtra("url") ?: return@registerForActivityResult
        when (d.getStringExtra("mode")) {
            "new" -> newTab(u)
            "incognito" -> newTab(u, true)
            else -> openUrl(u)
        }
    }

    // app lock (device PIN / pattern / password / fingerprint)
    private var unlocked = false
    private var lockShown = false
    private var lastPause = 0L
    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        lockShown = false
        if (r.resultCode == RESULT_OK) {
            unlocked = true
            root.visibility = View.VISIBLE
        } else finish()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = Store(this)
        AdBlocker.load(this)
        setContentView(R.layout.activity_main)
        bindViews()

        tabStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tabStrip.adapter = stripAdapter
        startList.layoutManager = GridLayoutManager(this, 4)
        startList.adapter = dialAdapter
        webHolder.setColorSchemeResources(R.color.accent_dark)
        webHolder.setOnRefreshListener { cur.web?.reload() ?: run { webHolder.isRefreshing = false } }
        webHolder.setOnChildScrollUpCallback { _, _ -> (cur.web?.scrollY ?: 0) > 0 }

        wireClicks()
        WebView.setWebContentsDebuggingEnabled(prefs.bool(K.DEBUG_WEB))
        restoreTabs()
        if (prefs.bool(K.KEYBOARD_START) && cur.home) urlField.post { urlField.requestFocus(); urlField.showKeyboard() }
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    cur.web?.canGoBack() == true && !cur.home -> cur.web?.goBack()
                    !cur.home && tabs.size == 1 -> goHome()
                    tabs.size > 1 -> closeTab(current)
                    else -> {
                        if (prefs.bool(K.BACK_TWICE)) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastBackAt > 2000) { lastBackAt = now; toast("Press back again to exit"); return }
                        }
                        isEnabled = false; onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkLock()
        applyPrefs()
        cur.web?.onResume()
    }

    override fun onPause() {
        super.onPause()
        lastPause = SystemClock.elapsedRealtime()
        cur.web?.onPause()
        saveSession()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // memory saving: release background tabs, they reload when selected
            tabs.forEach { t ->
                if (t !== cur && t.web != null && !t.incognito) {
                    capture(t)
                    destroyTab(t)
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (isFinishing) {
            if (prefs.bool(K.EXIT_HISTORY)) store.clearHistory()
            if (prefs.bool(K.EXIT_COOKIES)) {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
            }
            if (prefs.bool(K.EXIT_CACHE)) cur.web?.clearCache(true)
        }
        tabs.forEach { destroyTab(it) }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (prefs.volScroll && !cur.home && cur.web != null &&
            (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) cur.web?.pageDown(false) else cur.web?.pageUp(false)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        tabBarRow = findViewById(R.id.tabBarRow)
        addrRow = findViewById(R.id.addrRow)
        navRow = findViewById(R.id.navRow)
        tabStrip = findViewById(R.id.tabStrip)
        urlField = findViewById(R.id.urlField)
        lockIcon = findViewById(R.id.lockIcon)
        topHome = findViewById(R.id.topHome)
        topTabs = findViewById(R.id.topTabs)
        topTabCount = findViewById(R.id.topTabCount)
        topMenu = findViewById(R.id.topMenu)
        fabMedia = findViewById(R.id.fabMedia)
        btnReload = findViewById(R.id.btnReload)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        tabCount = findViewById(R.id.tabCount)
        webHolder = findViewById(R.id.webHolder)
        startList = findViewById(R.id.startList)
        progress = findViewById(R.id.progress)
        fabTop = findViewById(R.id.fabTop)
        findBar = findViewById(R.id.findBar)
        findField = findViewById(R.id.findField)
        findCount = findViewById(R.id.findCount)
    }

    private fun wireClicks() {
        findViewById<View>(R.id.btnNewTab).setOnClickListener { newTab(null) }

        urlField.setOnEditorActionListener { v, actionId, ev ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            if (go) {
                val text = urlField.text.toString().trim()
                if (text.isNotEmpty()) navigate(cur, text)
                v.hideKeyboard()
                urlField.clearFocus()
            }
            go
        }
        urlField.setOnFocusChangeListener { _, focus ->
            if (focus) {
                urlField.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                urlField.setText(if (cur.home) "" else (cur.url ?: ""))
                urlField.selectAll()
            } else updateAddress()
        }

        topHome.setOnClickListener { goHome() }
        topMenu.setOnClickListener { showMenu() }
        topTabs.setOnClickListener { TabSwitcher(this).show() }
        topTabs.setOnLongClickListener { tabsQuickMenu(); true }
        setupMediaFab()
        btnReload.setOnClickListener {
            val t = cur
            when {
                t.home -> dialAdapter.reload()
                t.loading -> t.web?.stopLoading()
                else -> t.web?.reload()
            }
        }

        btnBack.setOnClickListener {
            val w = cur.web
            if (w != null && w.canGoBack() && !cur.home) w.goBack() else if (!cur.home) goHome()
        }
        btnForward.setOnClickListener { if (cur.web?.canGoForward() == true) cur.web?.goForward() }
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnBookmarks).setOnClickListener { openLibrary("bookmarks") }
        val tabsBtn = findViewById<View>(R.id.btnTabs)
        tabsBtn.setOnClickListener { TabSwitcher(this).show() }
        tabsBtn.setOnLongClickListener { tabsQuickMenu(); true }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu() }

        fabTop.setOnClickListener { cur.web?.scrollTo(0, 0); fabTop.visibility = View.GONE }

        findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) { cur.web?.clearMatches(); findCount.text = "" } else cur.web?.findAllAsync(q)
            }
        })
        findViewById<View>(R.id.findNext).setOnClickListener { cur.web?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { cur.web?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    /** Re-applies settings that change layout or behaviour. Called on resume (after Settings). */
    private fun applyPrefs() {
        tabBarRow.visibility = if (prefs.showTabBar) View.VISIBLE else View.GONE
        webHolder.isEnabled = prefs.pullRefresh

        // address bar position: top (default) or bottom
        root.removeView(tabBarRow)
        root.removeView(addrRow)
        if (prefs.addrBottom) {
            val i = root.indexOfChild(navRow)
            root.addView(tabBarRow, i)
            root.addView(addrRow, i + 1)
        } else {
            root.addView(addrRow, 0)
            root.addView(tabBarRow, 1)
        }
        navRow.visibility = if (prefs.bool(K.NAV_BAR)) View.VISIBLE else View.GONE
        if (!prefs.scrollHide) showBars()

        tabs.forEach { applyWebSettings(it) }
        updateSecureFlag()
        applyDisplayPrefs()
        updateAddress()
        updateMediaFab()
    }

    /** Orientation, brightness, screen-on and full screen from Settings > Display / Composition. */
    private fun applyDisplayPrefs() {
        requestedOrientation = when (prefs.int(K.ROTATION)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (prefs.int(K.SCREEN_TIMEOUT) == 1) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = when (prefs.int(K.BRIGHTNESS)) {
            1 -> 0.15f
            2 -> 0.5f
            3 -> 0.9f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
        val deco = window.decorView as ViewGroup
        var filter = deco.findViewWithTag<View>("orbit_filter")
        val level = prefs.int(K.SCREEN_FILTER)
        if (level > 0) {
            if (filter == null) {
                filter = View(this).apply {
                    tag = "orbit_filter"
                    isClickable = false; isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                deco.addView(filter)
            }
            val alpha = when (level) { 1 -> 40; 2 -> 90; else -> 140 }
            filter.setBackgroundColor((prefs.int(K.FILTER_COLOR) and 0x00FFFFFF) or (alpha shl 24))
            filter.bringToFront()
        } else if (filter != null) deco.removeView(filter)
        if (customView == null) {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            if (prefs.bool(K.FULLSCREEN)) {
                c.hide(WindowInsetsCompat.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateSecureFlag() {
        val secure = prefs.appLock || (tabs.isNotEmpty() && cur.incognito && prefs.incogSecure)
        if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun checkLock() {
        if (!prefs.appLock) { root.visibility = View.VISIBLE; return }
        if (lockShown) return
        if (unlocked && SystemClock.elapsedRealtime() - lastPause < 30_000) return
        unlocked = false
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("Orbit Browser", null) else null
        if (intent == null) { unlocked = true; root.visibility = View.VISIBLE; return }
        root.visibility = View.INVISIBLE
        lockShown = true
        lockLauncher.launch(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) newTab(data.toString())
    }

    // ------------------------------------------------------------------ tabs

    fun newTab(url: String?, incognito: Boolean = false) {
        val t = Tab(null, incognito)
        val idx = if (prefs.tabNext && tabs.isNotEmpty()) (current + 1).coerceAtMost(tabs.size) else tabs.size
        tabs.add(idx, t)
        current = idx
        val target = when {
            !url.isNullOrEmpty() -> url
            incognito -> null
            prefs.int(K.NEW_TAB) == 1 && prefs.homepage.isNotEmpty() -> prefs.homepage
            prefs.int(K.NEW_TAB) == 2 -> "about:blank"
            else -> null
        }
        if (target == null) showTab() else navigate(t, target)
        if (prefs.bool(K.KEYBOARD_ADD) && target == null) urlField.post { urlField.requestFocus(); urlField.showKeyboard() }
        enforceTabLimit()
    }

    fun select(p: Int) {
        if (p !in tabs.indices) return
        current = p
        showTab()
    }

    fun closeTab(p: Int) {
        if (p !in tabs.indices) return
        val t = tabs.removeAt(p)
        destroyTab(t)
        if (tabs.isEmpty()) {
            tabs.add(Tab(null)); current = 0
        } else if (p < current) current--
        else if (p == current) current = p.coerceAtMost(tabs.size - 1)
        showTab()
    }

    fun closeWhere(pred: (Tab) -> Boolean) {
        val keep = tabs.getOrNull(current)
        tabs.filter(pred).forEach { tabs.remove(it); destroyTab(it) }
        if (tabs.isEmpty()) {
            tabs.add(Tab(null)); current = 0
        } else {
            val k = tabs.indexOf(keep)
            current = if (k >= 0) k else current.coerceAtMost(tabs.size - 1)
        }
        showTab()
    }

    private fun destroyTab(t: Tab) {
        t.web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        t.web = null
        upgraded.remove(t)
    }

    /** Small preview image for the tab switcher. */
    fun capture(t: Tab) {
        val w = t.web ?: return
        if (w.width <= 0 || w.height <= 0) return
        try {
            val bmp = Bitmap.createBitmap(w.width / 4, w.height / 4, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(0.25f, 0.25f)
            w.draw(c)
            t.thumb = bmp
        } catch (_: Throwable) {
        }
    }

    private fun refreshTabs() {
        stripAdapter.notifyDataSetChanged()
        tabCount.text = tabs.size.toString()
        topTabCount.text = tabs.size.toString()
        if (current in tabs.indices) tabStrip.scrollToPosition(current)
    }

    private fun restoreTabs() {
        if (prefs.keepTabs) {
            val (saved, idx) = store.loadSession()
            saved.forEach { (u, title) -> tabs.add(Tab(u.ifEmpty { null }).also { it.title = title }) }
            current = idx.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        }
        if (tabs.isEmpty()) tabs.add(Tab(null))
        if (!prefs.keepTabs && prefs.homepage.isNotEmpty()) { current = 0; navigate(tabs[0], prefs.homepage) } else showTab()
    }

    private fun saveSession() {
        if (!prefs.keepTabs || tabs.isEmpty()) return
        val normal = tabs.filter { !it.incognito }
        val idx = normal.indexOf(cur).coerceAtLeast(0)
        store.saveSession(normal.map { (if (it.home) "" else it.url ?: "") to it.title }, idx)
    }

    /** Shows the selected tab: start page or its WebView. */
    private fun showTab() {
        val t = cur
        closeFind()
        if (t.home) {
            webHolder.visibility = View.GONE
            startList.visibility = View.VISIBLE
            dialAdapter.reload()
        } else {
            var w = t.web
            if (w == null) {
                w = createWebView(t)
                applyWebSettings(t)
                w.loadUrl(t.url ?: "about:blank")
            }
            (w.parent as? ViewGroup)?.removeView(w)
            webHolder.removeAllViews()
            webHolder.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webHolder.visibility = View.VISIBLE
            startList.visibility = View.GONE
        }
        urlField.imeOptions = EditorInfo.IME_ACTION_GO or
            (if (t.incognito && prefs.keySecure) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        urlField.hint = if (t.incognito) "Incognito: search or type URL" else "Search or type URL"
        showBars()
        updateSecureFlag()
        updateAddress()
        updateNav()
        refreshTabs()
        updateMediaFab()
        enforceTabLimit()
    }

    /** Settings > Memory saving: open limit closes the oldest tabs, memory limit releases the farthest loaded ones. */
    private fun enforceTabLimit() {
        val open = prefs.int(K.TAB_OPEN_LIMIT)
        if (open > 0) {
            while (tabs.size > open) {
                val victim = tabs.firstOrNull { it !== cur } ?: break
                val i = tabs.indexOf(victim)
                tabs.removeAt(i); destroyTab(victim)
                if (i < current) current--
            }
            stripAdapter.notifyDataSetChanged()
        }
        val limit = prefs.int("mem_tab_limit")
        if (limit <= 0) return
        val live = tabs.filter { it.web != null && it !== cur && !it.incognito && !prefs.siteHas(K.MEM_KEEP, it.host) }
        val loaded = tabs.count { it.web != null }
        val excess = loaded - limit
        if (excess <= 0) return
        live.sortedByDescending { kotlin.math.abs(tabs.indexOf(it) - current) }.take(excess).forEach {
            capture(it); destroyTab(it)
        }
    }

    // ------------------------------------------------------------------ navigation

    fun openUrl(u: String) = navigate(cur, u)

    fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        val low = t.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("file://") ||
            low.startsWith("about:") || low.startsWith("data:") || low.startsWith("javascript:")
        ) return t
        val hostLike = !t.contains(' ') && !t.endsWith(".") &&
            (t.contains('.') || low.startsWith("localhost"))
        if (!hostLike) return prefs.searchUrl(t)
        val host = t.substringBefore('/').substringBefore(':')
        val local = host == "localhost" || host.all { it.isDigit() || it == '.' }
        return (if (local) "http://" else "https://") + t
    }

    private fun canUpgrade(host: String): Boolean =
        host.isNotEmpty() && host != "localhost" && !host.endsWith(".local") && !host.contains(':') &&
            !host.all { it.isDigit() || it == '.' }

    fun navigate(tab: Tab, input: String) {
        var url = normalizeUrl(input)
        tab.home = false
        tab.readerOn = false
        if (prefs.httpsFirst && url.startsWith("http://") && canUpgrade(hostOf(url))) {
            upgraded[tab] = url
            url = "https://" + url.substring(7)
        }
        tab.url = url
        tab.host = hostOf(url)
        val w = tab.web ?: createWebView(tab)
        applyWebSettings(tab)
        if (tab === tabs.getOrNull(current)) showTab()
        w.loadUrl(url)
    }

    private fun goHome() {
        if (prefs.bool(K.HOME_CLOSE_ALL) && tabs.size > 1) closeWhere { it !== cur }
        val hp = prefs.homepage
        if (hp.isNotEmpty()) { navigate(cur, hp); return }
        val t = cur
        t.home = true
        t.url = null
        t.title = ""
        t.host = ""
        t.readerOn = false
        destroyTab(t)
        showTab()
    }

    private fun openLibrary(mode: String) {
        libLauncher.launch(Intent(this, LibraryActivity::class.java).putExtra("mode", mode))
    }

    // ------------------------------------------------------------------ address bar state

    private fun updateAddress() {
        if (tabs.isEmpty()) return
        val t = cur
        if (!urlField.hasFocus()) {
            // like Soul: show only the site name, centred, until the field is tapped
            urlField.gravity = Gravity.CENTER
            val host = hostOf(t.url).removePrefix("www.")
            val label = when (prefs.int(K.ADDR_LABEL)) {
                1 -> t.title.ifBlank { host }
                2 -> t.url ?: ""
                else -> host.ifEmpty { t.url ?: "" }
            }
            urlField.setText(if (t.home) "" else label)
        }
        val https = t.url?.startsWith("https://") == true && !t.home
        lockIcon.visibility = if (https) View.VISIBLE else View.GONE
        btnReload.setImageResource(if (t.loading && !t.home) R.drawable.ic_stop else R.drawable.ic_refresh)
        if (t.loading && t.progress < 100 && !t.home) {
            progress.visibility = View.VISIBLE
            progress.progress = t.progress
        } else progress.visibility = View.GONE
    }

    private fun updateNav() {
        val w = cur.web
        btnBack.alpha = if (!cur.home) 1f else 0.4f
        btnForward.alpha = if (w != null && w.canGoForward()) 1f else 0.4f
    }

    // ------------------------------------------------------------------ bars hide on scroll

    private fun hideBars() {
        if (!barsShown) return
        barsShown = false
        lastToggle = SystemClock.uptimeMillis()
        tabBarRow.visibility = View.GONE
        addrRow.visibility = View.GONE
    }

    private fun showBars() {
        if (barsShown && addrRow.visibility == View.VISIBLE) return
        barsShown = true
        lastToggle = SystemClock.uptimeMillis()
        tabBarRow.visibility = if (prefs.showTabBar) View.VISIBLE else View.GONE
        addrRow.visibility = View.VISIBLE
    }

    private fun onScrolled(tab: Tab, y: Int, oldY: Int) {
        if (tab !== cur) return
        val dy = y - oldY
        if (prefs.scrollHide) {
            // Resizing the page while it scrolls makes it jump, so: ignore the scroll events
            // right after a toggle, and only toggle after a clear, sustained movement.
            if (y <= 0) { barAcc = 0; showBars() }
            else if (SystemClock.uptimeMillis() - lastToggle > 450) {
                if (dy * barAcc < 0) barAcc = 0
                barAcc += dy
                if (barAcc > dp(120) && barsShown) { barAcc = 0; hideBars() }
                else if (barAcc < -dp(60) && !barsShown) { barAcc = 0; showBars() }
            }
        }
        if (prefs.backTop && y > 600 && dy < -4) fabTop.visibility = View.VISIBLE
        else if (y <= 600 || dy > 4) fabTop.visibility = View.GONE
    }

    // ------------------------------------------------------------------ WebView

    private fun desktopUa(): String {
        val v = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    private fun applyWebSettings(tab: Tab) {
        val w = tab.web ?: return
        val s = w.settings
        val desktop = tab.desktop || prefs.desktopAll
        val uaMode = prefs.int(K.UA)            // 0 Android, 1 Desktop, 2 iPhone
        s.userAgentString = when {
            desktop || uaMode == 1 -> desktopUa()
            uaMode == 2 -> IPHONE_UA
            else -> defaultUa
        }
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = prefs.textZoom
        s.setSupportZoom(prefs.bool("allow_zoom"))
        s.domStorageEnabled = prefs.bool("adv_dom_storage")
        s.mixedContentMode = if (prefs.bool("adv_mixed")) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        w.isVerticalScrollBarEnabled = prefs.int(K.SHOW_SCROLLBAR) == 1
        s.blockNetworkImage = prefs.imagesOff
        s.javaScriptEnabled = prefs.js && !prefs.siteHas(K.SITE_JS_OFF, tab.host)
        s.cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(prefs.cookies)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, prefs.cookies && prefs.thirdCookies)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView {
        val w = WebView(this)
        tab.web = w
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            safeBrowsingEnabled = prefs.safeBrowsing
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        w.webViewClient = client(tab)
        w.webChromeClient = chrome(tab)
        w.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime, tab.url) }
        w.addJavascriptInterface(BlobBridge(), "OrbitBlob")
        w.setFindListener { active, total, _ ->
            if (tab === cur) findCount.text = if (total == 0) "0" else "${active + 1}/$total"
        }
        w.setOnScrollChangeListener { _, _, y, _, oy -> onScrolled(tab, y, oy) }
        w.setOnLongClickListener { onLongPress(w) }
        return w
    }

    private fun client(tab: Tab) = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            if (!req.isForMainFrame) return false
            return handleNav(tab, view, req.url.toString())
        }

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (prefs.bool(K.MEDIA_DETECT) && !AdBlocker.isBlocked(req.url.host)) {
                val u = req.url.toString()
                MediaDetector.classify(u)?.let { (kind, ext) ->
                    val h = req.requestHeaders
                    addMedia(tab, MediaItem(u, kind, ext, h["Referer"] ?: tab.url, h["Origin"], h["User-Agent"]))
                }
            }
            if (prefs.adBlock && !req.isForMainFrame && !prefs.siteHas(K.SITE_AD_OFF, tab.host) &&
                AdBlocker.isBlocked(req.url.host)
            ) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            tab.loading = true
            tab.progress = 0
            tab.home = false
            if (!url.isNullOrEmpty() && url != tab.url) { tab.media.clear(); ui.post(mediaTick) }
            if (!url.isNullOrEmpty() && !url.startsWith("data:")) {
                tab.url = url
                tab.host = hostOf(url)
            }
            if (tab === cur) { updateAddress(); updateNav() }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectPageDark(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.loading = false
            tab.progress = 100
            val title = view.title ?: ""
            if (title.isNotBlank() && !title.startsWith("data:")) tab.title = title
            if (!tab.incognito && prefs.saveHistory && !tab.url.isNullOrEmpty()) {
                store.addHistory(Entry(tab.title, tab.url!!))
            }
            injectPageDark(view)
            if (prefs.bool(K.MEDIA_DETECT)) {
                view.postDelayed({ scanMedia(tab) }, 800)
                view.postDelayed({ scanMedia(tab) }, 3500)
            }
            if (prefs.bool(K.OVERLAY) && !prefs.siteHas(K.SITE_OVERLAY_OFF, tab.host)) view.postDelayed({ view.evaluateJavascript(OVERLAY_JS, null) }, 1200)
            if (tab === cur) {
                webHolder.isRefreshing = false
                updateAddress(); updateNav()
            }
            refreshTabs()
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            val back = upgraded.remove(tab)
            if (back != null) { view.loadUrl(back); return }
            if (err.errorCode == WebViewClient.ERROR_UNKNOWN) return
            view.loadDataWithBaseURL(
                req.url.toString(), errorHtml("Can't open this page", err.description.toString(), req.url.toString()),
                "text/html", "utf-8", req.url.toString()
            )
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (prefs.sslBlock) {
                handler.cancel()
                view.loadDataWithBaseURL(
                    error.url, errorHtml("Connection is not private", "The site's security certificate could not be verified. Orbit blocked it. You can turn this protection off in Settings.", error.url ?: ""),
                    "text/html", "utf-8", error.url
                )
            } else handler.proceed()
        }
    }

    /** Returns true when Orbit handled the navigation itself. */
    private fun handleNav(tab: Tab, view: WebView, url: String): Boolean {
        val uri = Uri.parse(url)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                tab.readerOn = false
                if (prefs.bool(K.AMP_OFF)) deAmp(url)?.let { view.loadUrl(it); return true }
                if (prefs.httpsFirst && uri.scheme == "http" && canUpgrade(uri.host ?: "")) {
                    upgraded[tab] = url
                    view.loadUrl("https" + url.substring(4))
                    return true
                }
                tab.host = uri.host ?: ""
                applyWebSettings(tab)
                return false
            }
            "about", "data", "blob", "javascript", "file" -> return false
            "intent" -> {
                try {
                    val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (_: Exception) {
                    try {
                        val fb = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                        if (fb != null) view.loadUrl(fb)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            else -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("No app can open this link") }
                return true
            }
        }
    }

    /** Turns Google AMP / AMP cache links into the publisher's normal page. */
    private fun deAmp(url: String): String? {
        val u = Uri.parse(url)
        val host = u.host ?: return null
        val path = u.encodedPath ?: return null
        if ((host.endsWith("google.com") && path.startsWith("/amp/s/")) ||
            (host.endsWith("cdn.ampproject.org") && path.startsWith("/c/s/"))
        ) {
            val rest = path.substringAfter("/s/")
            return "https://$rest" + (u.encodedQuery?.let { "?$it" } ?: "")
        }
        return null
    }

    private fun injectPageDark(view: WebView) {
        if (!prefs.pageDark) {
            view.evaluateJavascript("var e=document.getElementById('orbit-dark');if(e)e.remove();", null)
            return
        }
        view.evaluateJavascript(DARK_JS, null)
    }

    private fun chrome(tab: Tab) = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === cur) updateAddress()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) { tab.title = title; refreshTabs() }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon
            if (!tab.incognito) store.saveFavicon(tab.host, icon)
            stripAdapter.notifyDataSetChanged()
        }

        override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
            if (customView != null) { cb.onCustomViewHidden(); return }
            customView = view
            customCb = cb
            view.setBackgroundColor(Color.BLACK)
            (window.decorView as ViewGroup).addView(
                view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        override fun onHideCustomView() = hideCustomView()

        override fun onShowFileChooser(
            view: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try {
                fileLauncher.launch(params.createIntent()); true
            } catch (_: Exception) {
                fileCb = null; false
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val mode = if (prefs.siteHas(K.SITE_POPUP_OFF, tab.host)) 0 else prefs.popup   // 0 allow, 1 block suspected ads, 2 block all
            if (mode == 2 || (mode == 1 && !isUserGesture)) return false
            val t = Tab(null, tab.incognito)
            t.home = false
            val idx = if (prefs.tabNext) (current + 1).coerceAtMost(tabs.size) else tabs.size
            tabs.add(idx, t)
            current = idx
            val nw = createWebView(t)
            applyWebSettings(t)
            showTab()
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = nw
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(
            origin: String, callback: android.webkit.GeolocationPermissions.Callback
        ) = callback.invoke(origin, false, false)
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------ long press on page

    private fun onLongPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> if (extra != null) {
                when (prefs.int(K.LP_LINK)) {
                    1 -> newTab(extra)
                    2 -> copy(extra)
                    else -> linkMenu(extra, null)
                }
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE -> if (extra != null) {
                when (prefs.int(K.LP_IMAGE)) {
                    1 -> startDownload(extra, null, null, null)
                    2 -> newTab(extra)
                    else -> linkMenu(null, extra)
                }
                return true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = Handler(Looper.getMainLooper()) { m ->
                    linkMenu(m.data.getString("url"), m.data.getString("src") ?: extra)
                    true
                }
                w.requestFocusNodeHref(h.obtainMessage())
                return true
            }
        }
        return false
    }

    private fun linkMenu(link: String?, img: String?) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (link != null) {
            items.add("Open in new tab" to { newTab(link) })
            items.add("Open in incognito tab" to { newTab(link, true) })
            items.add("Copy link" to { copy(link) })
            items.add("Share link" to { share(link) })
            val h = hostOf(link)
            if (h.isNotEmpty()) items.add("Block $h" to {
                AdBlocker.importList(this, h); toast("Blocked $h")
            })
        }
        if (img != null) {
            items.add("Save image" to { startDownload(img, null, null, null) })
            items.add("Open image in new tab" to { newTab(img) })
            items.add("Copy image address" to { copy(img) })
        }
        Ui.listSheet(this, link ?: img, items)
    }

    // ------------------------------------------------------------------ downloads and sharing

    private val blobTokens: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private var askedNotifications = false

    /**
     * Normal files go to Android's download manager (keeps running in the background and survives the app
     * being closed). blob: and data: files can't be fetched by the system, so they are read from the page
     * and saved by Orbit itself. Everything lands in Downloads/Orbit and shows in the Downloads list.
     */
    private fun startDownload(
        url: String, ua: String?, cd: String?, mime: String?,
        referer: String? = tabs.getOrNull(current)?.url
    ) {
        when {
            url.startsWith("blob:") -> { downloadBlob(url, cd, mime); return }
            url.startsWith("data:") -> { downloadData(url, cd, mime); return }
            !url.startsWith("http") -> { toast("This download type isn't supported"); return }
        }
        val name = URLUtil.guessFileName(url, cd, mime).replace('/', '_').ifBlank { "download" }
        val go = { enqueueDownload(url, ua, mime, referer, name) }
        if (prefs.bool("dl_ask")) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage(name)
                .setPositiveButton("Download") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        } else go()
    }

    private fun enqueueDownload(url: String, ua: String?, mime: String?, referer: String?, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            if (!referer.isNullOrEmpty() && referer.startsWith("http")) req.addRequestHeader("Referer", referer)
            req.setTitle(name)
            req.setNotificationVisibility(
                if (prefs.bool("dl_notify")) DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                else DownloadManager.Request.VISIBILITY_VISIBLE
            )
            if (prefs.bool("dl_wifi")) req.setAllowedOverMetered(false)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Orbit/$name")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
            if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedNotifications = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            }
        } catch (_: Exception) {
            toast("Couldn't start the download")
        }
    }

    /** Replaces a generic ".bin" name with the extension that belongs to the file type. */
    private fun fileNameFor(guess: String, type: String): String {
        val clean = guess.replace('/', '_').ifBlank { "download" }
        if (clean.endsWith(".bin")) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(type)?.let { return clean.removeSuffix(".bin") + "." + it }
        }
        return clean
    }

    private fun downloadData(url: String, cd: String?, mime: String?) {
        Thread {
            try {
                val comma = url.indexOf(',')
                val header = url.substring(5, comma)
                val type = header.substringBefore(';').ifEmpty { mime ?: "application/octet-stream" }
                val payload = url.substring(comma + 1)
                val bytes = if (header.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                val name = fileNameFor(URLUtil.guessFileName(url, cd, type), type)
                runOnUiThread { Downloader.saveBytes(this, name, bytes); toast("Saving $name") }
            } catch (_: Exception) {
                runOnUiThread { toast("Couldn't read this download") }
            }
        }.start()
    }

    private fun downloadBlob(url: String, cd: String?, mime: String?) {
        val w = cur.web ?: return
        val token = java.util.UUID.randomUUID().toString()
        blobTokens.add(token)
        val name = fileNameFor(URLUtil.guessFileName(url, cd, mime), mime ?: "")
        fun q(x: String) = JSONObject.quote(x)
        val js = "(function(){fetch(" + q(url) + ").then(function(r){return r.blob();}).then(function(b){" +
            "var f=new FileReader();f.onloadend=function(){OrbitBlob.save(" + q(token) + "," + q(name) + ",String(f.result));};" +
            "f.readAsDataURL(b);}).catch(function(){OrbitBlob.save(" + q(token) + "," + q(name) + ",'');});})();"
        w.evaluateJavascript(js, null)
        toast("Preparing $name")
    }

    /** Receives the bytes of a blob: file from the page. Only accepts a call that carries a one-time token. */
    private inner class BlobBridge {
        @JavascriptInterface
        fun save(token: String, name: String, dataUrl: String) {
            if (!blobTokens.remove(token)) return
            if (!dataUrl.startsWith("data:") || dataUrl.length > 120_000_000) {
                runOnUiThread { toast("Couldn't save the file") }
                return
            }
            try {
                val comma = dataUrl.indexOf(',')
                val type = dataUrl.substring(5, comma).substringBefore(';')
                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                val finalName = fileNameFor(name, type)
                runOnUiThread { Downloader.saveBytes(this@MainActivity, finalName, bytes); toast("Saving $finalName") }
            } catch (_: Exception) {
                runOnUiThread { toast("Couldn't save the file") }
            }
        }
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Copied")
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share"))
    }

    // ------------------------------------------------------------------ find in page

    private fun openFind() {
        if (cur.home) return
        findBar.visibility = View.VISIBLE
        findField.setText("")
        findCount.text = ""
        findField.showKeyboard()
    }

    private fun closeFind() {
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE) return
        cur.web?.clearMatches()
        findBar.visibility = View.GONE
        findField.hideKeyboard()
    }

    // ------------------------------------------------------------------ reader mode

    private fun toggleReader() {
        val t = cur
        if (t.home) return
        if (t.readerOn) {
            t.readerOn = false
            t.url?.let { t.web?.loadUrl(it) }
            updateAddress()
            return
        }
        val w = t.web ?: return
        w.evaluateJavascript(READER_JS) { raw ->
            try {
                val s = JSONTokener(raw).nextValue() as? String
                if (s == null) { toast("Nothing to show in reader mode"); return@evaluateJavascript }
                val o = JSONObject(s)
                val arr = o.getJSONArray("b")
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)
                    val txt = TextUtils.htmlEncode(p.getString(1))
                    sb.append(
                        when (p.getString(0)) {
                            "H1" -> "<h1>$txt</h1>"
                            "H2", "H3" -> "<h2>$txt</h2>"
                            "BLOCKQUOTE" -> "<blockquote>$txt</blockquote>"
                            "PRE" -> "<pre>$txt</pre>"
                            "LI" -> "<p>&bull; $txt</p>"
                            else -> "<p>$txt</p>"
                        }
                    )
                }
                if (sb.isEmpty()) { toast("Nothing to show in reader mode"); return@evaluateJavascript }
                t.readerOn = true
                w.loadDataWithBaseURL(t.url, readerHtml(o.optString("t"), sb.toString()), "text/html", "utf-8", t.url)
                updateAddress()
            } catch (_: Exception) {
                toast("Reader mode isn't available for this page")
            }
        }
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun readerHtml(title: String, body: String): String {
        val bg = if (isNight()) "#121212" else "#FAFAFA"
        val fg = if (isNight()) "#E0E0E0" else "#212121"
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>" +
            "body{background:$bg;color:$fg;font-family:serif;font-size:19px;line-height:1.65;margin:0 auto;padding:20px;max-width:42em}" +
            "h1{font-size:1.5em;line-height:1.25}h2{font-size:1.2em}blockquote{border-left:3px solid #888;margin-left:0;padding-left:14px;opacity:.85}" +
            "pre{white-space:pre-wrap;font-size:14px}</style></head><body><h1>${TextUtils.htmlEncode(title)}</h1>$body</body></html>"
    }

    private fun errorHtml(title: String, detail: String, url: String): String {
        val bg = if (isNight()) "#121212" else "#FFFFFF"
        val fg = if (isNight()) "#EEEEEE" else "#212121"
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;background:$bg;color:$fg;padding:32px'>" +
            "<h2>${TextUtils.htmlEncode(title)}</h2><p>${TextUtils.htmlEncode(detail)}</p>" +
            "<p style='opacity:.6;word-break:break-all'>${TextUtils.htmlEncode(url)}</p></body></html>"
    }

    // ------------------------------------------------------------------ menu

    private class MenuItem(val icon: Int, val label: String, val run: () -> Unit)

    private fun needPage(run: (Tab) -> Unit): () -> Unit = {
        val t = cur
        if (t.home || t.web == null) toast("Open a page first") else run(t)
    }

    private fun showMenu() {
        if (prefs.int(K.MENU_STYLE) == 1) showGridMenu() else showPopupMenu()
    }

    private fun readAloud(t: Tab) {
        val engine = tts
        if (engine != null && engine.isSpeaking) { engine.stop(); toast("Stopped"); return }
        t.web?.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
            val text = try { JSONTokener(raw).nextValue() as? String } catch (_: Exception) { null }
            if (text.isNullOrBlank()) { toast("Nothing to read"); return@evaluateJavascript }
            val rates = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            val speak = { e: TextToSpeech ->
                e.setSpeechRate(rates[prefs.int(K.TTS_RATE).coerceIn(0, rates.size - 1)])
                text.chunked(3500).forEachIndexed { i, c ->
                    e.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "orbit$i")
                }
            }
            val existing = tts
            if (existing != null) speak(existing) else {
                var created: TextToSpeech? = null
                created = TextToSpeech(applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) created?.let { speak(it) } else toast("Text-to-speech is not available")
                }
                tts = created
            }
        }
    }

    /** Soul-style popup: quick buttons on top, grouped cards, Incognito / Desktop / DNS at the bottom. */
    private fun showPopupMenu() {
        val t = cur
        val starred = store.isBookmarked(t.url)
        val d = AppCompatDialog(this, R.style.PopupDialog)
        val inf = LayoutInflater.from(this)
        val matchW = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        val rippleRes = tv.resourceId

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        fun newCard(): LinearLayout {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.setBackgroundResource(R.drawable.bg_c_single)
            root.addView(c, LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(8) })
            return c
        }
        fun row(card: LinearLayout, icon: Int, label: String, act: () -> Unit) {
            val v = inf.inflate(R.layout.item_pmenu, card, false)
            v.findViewById<ImageView>(R.id.pmIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.pmLabel).text = label
            v.setOnClickListener { d.dismiss(); act() }
            card.addView(v)
        }

        // quick buttons
        val top = newCard()
        top.orientation = LinearLayout.HORIZONTAL
        fun quick(icon: Int, desc: String, dim: Boolean, act: () -> Unit) {
            val b = ImageButton(this)
            b.setImageResource(icon)
            b.setBackgroundResource(rippleRes)
            b.contentDescription = desc
            b.alpha = if (dim) 0.4f else 1f
            b.setOnClickListener { d.dismiss(); act() }
            top.addView(b, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        quick(R.drawable.ic_refresh, "Reload", false) { if (cur.home) dialAdapter.reload() else cur.web?.reload() }
        quick(R.drawable.ic_shield, "Ad blocker", !prefs.adBlock) {
            prefs.setBool(K.ADBLOCK, !prefs.adBlock)
            toast(if (prefs.adBlock) "Ad blocker on" else "Ad blocker off")
            cur.web?.reload()
        }
        quick(if (starred) R.drawable.ic_star else R.drawable.ic_star_border, "Bookmark", false, needPage { toggleBookmark(it) })
        quick(R.drawable.ic_bookmarks, "Bookmarks", false) { openLibrary("bookmarks") }
        quick(R.drawable.ic_settings, "Settings", false) { startActivity(Intent(this, SettingsActivity::class.java)) }

        val a = newCard()
        row(a, R.drawable.ic_power, "Exit app") { finishAndRemoveTask() }
        row(a, R.drawable.ic_history, "History") { openLibrary("history") }
        row(a, R.drawable.ic_download, "Downloads") {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        row(a, R.drawable.ic_add, "New tab") { newTab(null) }

        val b = newCard()
        row(b, R.drawable.ic_fullscreen_enter, if (prefs.bool(K.FULLSCREEN)) "Exit full screen" else "Full screen") {
            prefs.setBool(K.FULLSCREEN, !prefs.bool(K.FULLSCREEN)); applyDisplayPrefs()
        }
        row(b, R.drawable.ic_dark, if (prefs.pageDark) "Light mode" else "Dark mode") {
            prefs.setBool(K.PAGE_DARK, !prefs.pageDark)
            tabs.forEach { tb -> tb.web?.let { w -> injectPageDark(w) } }
        }
        row(b, R.drawable.ic_translate, "Translate", needPage { tb ->
            navigate(tb, "https://translate.google.com/translate?sl=auto&tl=${Langs.target(prefs)}&u=" + Uri.encode(tb.url))
        })
        row(b, R.drawable.ic_volume, "Read aloud", needPage { readAloud(it) })
        row(b, R.drawable.ic_search, "Find in page") { openFind() }

        val c = newCard()
        row(c, R.drawable.ic_share, "Share URL", needPage { share(it.url ?: "") })
        row(c, R.drawable.ic_copy, "Copy link", needPage { copy(it.url ?: "") })
        row(c, R.drawable.ic_open_new, "Open in app", needPage { openWithApp(it.url ?: "") })
        row(c, R.drawable.ic_add, "Add current page", needPage { tb ->
            val list = store.dial()
            list.add(Dial(tb.label().take(24), tb.url))
            store.saveDial(list)
            toast("Added to start page")
        })
        row(c, R.drawable.ic_print, "Print / PDF", needPage { tb ->
            val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val name = tb.label()
            pm.print(name, tb.web!!.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
        })
        row(c, R.drawable.ic_reader, if (t.readerOn) "Exit reader" else "Reader mode", needPage { toggleReader() })
        row(c, R.drawable.ic_dashboard, "Page source", needPage { showSource(it) })
        row(c, R.drawable.ic_video_library, "Page media") {
            if (cur.media.isEmpty()) toast("No media found on this page yet") else MediaUi.showSheet(this, cur)
        }
        row(c, R.drawable.ic_text, "Text size") {
            Ui.listSheet(this, "Text size", listOf(80, 90, 100, 115, 130, 150).map { sz ->
                "$sz%" to {
                    prefs.setInt(K.TEXT_ZOOM, sz)
                    tabs.forEach { tb -> applyWebSettings(tb) }
                }
            })
        }
        row(c, R.drawable.ic_close, "Hide overlays", needPage { tb ->
            tb.web?.evaluateJavascript(OVERLAY_JS) { n -> toast("Removed ${n ?: "0"} overlay(s)") }
        })
        row(c, R.drawable.ic_lock, "Site settings", needPage { siteSettings(it) })

        // bottom row
        val bottom = newCard()
        bottom.orientation = LinearLayout.HORIZONTAL
        fun bItem(icon: Int, label: String, act: () -> Unit) {
            val v = inf.inflate(R.layout.item_menu, bottom, false)
            v.findViewById<ImageView>(R.id.menuIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.menuLabel).text = label
            v.setOnClickListener { d.dismiss(); act() }
            bottom.addView(v)
        }
        bItem(R.drawable.ic_incognito, "Incognito mode") { newTab(null, true) }
        bItem(R.drawable.ic_desktop, if (t.desktop) "Mobile" else "Desktop", needPage {
            it.desktop = !it.desktop; applyWebSettings(it); it.web?.reload()
        })
        bItem(R.drawable.ic_key, "DNS") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "dns"))
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        scroll.setOnClickListener { d.dismiss() }
        d.setContentView(scroll)
        d.window?.let { w ->
            val dm = resources.displayMetrics
            w.setLayout((dm.widthPixels * 0.68f).toInt(), (dm.heightPixels * 0.86f).toInt())
            w.setGravity(Gravity.TOP or Gravity.END)
            val lp = w.attributes
            lp.y = dp(52)
            lp.x = dp(4)
            w.attributes = lp
        }
        d.show()
    }

    private fun showGridMenu() {
        val t = cur
        val starred = store.isBookmarked(t.url)
        val items = listOf(
            MenuItem(R.drawable.ic_back, "Back") { if (cur.web?.canGoBack() == true) cur.web?.goBack() },
            MenuItem(R.drawable.ic_forward, "Forward") { if (cur.web?.canGoForward() == true) cur.web?.goForward() },
            MenuItem(R.drawable.ic_refresh, "Reload") { if (cur.home) dialAdapter.reload() else cur.web?.reload() },
            MenuItem(if (starred) R.drawable.ic_star else R.drawable.ic_star_border, if (starred) "Bookmarked" else "Bookmark", needPage { toggleBookmark(it) }),

            MenuItem(R.drawable.ic_add, "New tab") { newTab(null) },
            MenuItem(R.drawable.ic_incognito, "Incognito tab") { newTab(null, true) },
            MenuItem(R.drawable.ic_bookmarks, "Bookmarks") { openLibrary("bookmarks") },
            MenuItem(R.drawable.ic_history, "History") { openLibrary("history") },

            MenuItem(R.drawable.ic_download, "Downloads") {
                startActivity(Intent(this, DownloadsActivity::class.java))
            },
            MenuItem(R.drawable.ic_search, "Find in page") { openFind() },
            MenuItem(R.drawable.ic_desktop, if (t.desktop) "Mobile site" else "Desktop site", needPage {
                it.desktop = !it.desktop; applyWebSettings(it); it.web?.reload()
            }),
            MenuItem(R.drawable.ic_dark, if (prefs.pageDark) "Light pages" else "Dark pages") {
                prefs.setBool(K.PAGE_DARK, !prefs.pageDark)
                tabs.forEach { tb -> tb.web?.let { w -> injectPageDark(w) } }
            },

            MenuItem(R.drawable.ic_share, "Share", needPage { share(it.url ?: "") }),
            MenuItem(R.drawable.ic_copy, "Copy link", needPage { copy(it.url ?: "") }),
            MenuItem(R.drawable.ic_open_new, "Open in app", needPage { openWithApp(it.url ?: "") }),
            MenuItem(R.drawable.ic_print, "Print / PDF", needPage { tb ->
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = tb.label()
                pm.print(name, tb.web!!.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            }),

            MenuItem(R.drawable.ic_translate, "Translate", needPage { tb ->
                val lang = Langs.target(prefs)
                navigate(tb, "https://translate.google.com/translate?sl=auto&tl=$lang&u=" + Uri.encode(tb.url))
            }),
            MenuItem(R.drawable.ic_dashboard, "Page source", needPage { showSource(it) }),
            MenuItem(R.drawable.ic_lock, "Site settings", needPage { siteSettings(it) }),
            MenuItem(R.drawable.ic_close, "Hide overlays", needPage { tb ->
                tb.web?.evaluateJavascript(OVERLAY_JS) { n -> toast("Removed ${n ?: "0"} overlay(s)") }
            }),

            MenuItem(R.drawable.ic_reader, if (t.readerOn) "Exit reader" else "Reader mode", needPage { toggleReader() }),
            MenuItem(R.drawable.ic_download, "Page media", {
                if (cur.media.isEmpty()) toast("No media found on this page yet") else MediaUi.showSheet(this, cur)
            }),
            MenuItem(R.drawable.ic_add, "Add to start page", needPage { tb ->
                val d = store.dial()
                d.add(Dial(tb.label().take(24), tb.url))
                store.saveDial(d)
                toast("Added to start page")
            }),
            MenuItem(R.drawable.ic_tune, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
            MenuItem(R.drawable.ic_stop, "Exit") { finishAndRemoveTask() },
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        val holder = arrayOfNulls<androidx.appcompat.app.AppCompatDialog>(1)
        items.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { mi ->
                val v = LayoutInflater.from(this).inflate(R.layout.item_menu, row, false)
                v.findViewById<ImageView>(R.id.menuIcon).setImageResource(mi.icon)
                v.findViewById<TextView>(R.id.menuLabel).text = mi.label
                v.setOnClickListener { holder[0]?.dismiss(); mi.run() }
                row.addView(v)
            }
            repeat(4 - rowItems.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            box.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        holder[0] = Ui.sheet(this, scroll)
    }

    private fun openWithApp(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val chooser = Intent.createChooser(i, "Open with")
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        try { startActivity(chooser) } catch (_: Exception) { toast("No app can open this") }
    }

    private fun showSource(t: Tab) {
        t.web?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val src = try { JSONTokener(raw).nextValue() as? String } catch (_: Exception) { null }
            if (src == null) { toast("Couldn't read the page source"); return@evaluateJavascript }
            val nt = Tab(null, t.incognito)
            nt.home = false
            nt.title = "Source: " + t.label()
            val idx = (current + 1).coerceAtMost(tabs.size)
            tabs.add(idx, nt)
            current = idx
            val w = createWebView(nt)
            applyWebSettings(nt)
            showTab()
            w.loadDataWithBaseURL(null, src, "text/plain", "utf-8", null)
        }
    }

    private fun siteSettings(t: Tab) {
        val host = t.host
        val adOff = prefs.siteHas(K.SITE_AD_OFF, host)
        val jsOff = prefs.siteHas(K.SITE_JS_OFF, host)
        Ui.listSheet(
            this, host, listOf(
                (if (adOff) "Ad blocker: off for this site (tap to enable)" else "Ad blocker: on (tap to allow ads on this site)") to {
                    prefs.siteSet(K.SITE_AD_OFF, host, !adOff); t.web?.reload()
                },
                (if (jsOff) "JavaScript: blocked on this site (tap to allow)" else "JavaScript: allowed (tap to block on this site)") to {
                    prefs.siteSet(K.SITE_JS_OFF, host, !jsOff); applyWebSettings(t); t.web?.reload()
                },
                "Block ${host} permanently (add to filter)" to {
                    AdBlocker.importList(this, host); toast("Blocked $host")
                }
            )
        )
    }


    // ------------------------------------------------------------------ bookmarks, quick tab menu

    private fun toggleBookmark(t: Tab) {
        val url = t.url
        if (t.home || url.isNullOrEmpty()) return
        if (store.isBookmarked(url)) { store.removeBookmark(url); toast("Bookmark removed") }
        else { store.addBookmark(Entry(t.label(), url)); toast("Bookmarked") }
    }

    private fun tabsQuickMenu() {
        Ui.listSheet(
            this, null, listOf(
                "New tab" to { newTab(null) },
                "New incognito tab" to { newTab(null, true) },
                "Close this tab" to { closeTab(current) }
            )
        )
    }

    // ------------------------------------------------------------------ media detection (floating button)

    fun userAgent(): String = if (cur.desktop || prefs.desktopAll) desktopUa() else defaultUa

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Called from the network thread when a request looks like media. */
    private fun addMedia(tab: Tab, item: MediaItem) {
        if (tab.media.any { it.url == item.url }) return
        tab.media.add(item)
        if (tab.media.size > 80) tab.media.removeAt(0)
        ui.removeCallbacks(mediaTick)
        ui.postDelayed(mediaTick, 250)
    }

    /** Looks for <video>, <audio>, <source> and <track> addresses inside the page itself. */
    private fun scanMedia(tab: Tab) {
        val w = tab.web ?: return
        w.evaluateJavascript(
            "(function(){var o=[];document.querySelectorAll('video,audio,source,track').forEach(function(e){" +
                "var u=e.currentSrc||e.src||e.getAttribute('src');if(u&&/^https?:/i.test(u))o.push([e.tagName,u])});" +
                "return JSON.stringify(o)})()"
        ) { raw ->
            try {
                val s = JSONTokener(raw).nextValue() as? String ?: return@evaluateJavascript
                val arr = org.json.JSONArray(s)
                for (i in 0 until arr.length()) {
                    val pair = arr.getJSONArray(i)
                    val tag = pair.getString(0)
                    val u = pair.getString(1)
                    val known = MediaDetector.classify(u)
                    val item = when {
                        known != null -> MediaItem(u, known.first, known.second, tab.url, null, null)
                        tag == "TRACK" -> MediaItem(u, MediaKind.SUB, "vtt", tab.url, null, null)
                        tag == "AUDIO" -> MediaItem(u, MediaKind.AUDIO, "mp3", tab.url, null, null)
                        else -> MediaItem(u, MediaKind.VIDEO, "mp4", tab.url, null, null)
                    }
                    addMedia(tab, item)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun updateMediaFab() {
        if (!::fabMedia.isInitialized || tabs.isEmpty()) return
        val show = prefs.bool(K.MEDIA_DETECT) && !cur.home && cur.media.isNotEmpty()
        if (show) {
            if (fabMedia.visibility != View.VISIBLE) {
                fabMedia.scaleX = 0f
                fabMedia.scaleY = 0f
                fabMedia.visibility = View.VISIBLE
                fabMedia.post {
                    placeMediaFab()
                    fabMedia.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
            }
        } else if (fabMedia.visibility == View.VISIBLE) {
            fabMedia.animate().scaleX(0f).scaleY(0f).setDuration(140).withEndAction {
                fabMedia.visibility = View.GONE
            }.start()
        }
    }

    private fun placeMediaFab() {
        val parent = fabMedia.parent as? View ?: return
        if (parent.width <= 0) { fabMedia.post { placeMediaFab() }; return }
        val fx = prefs.int(K.MEDIA_X) / 1000f
        val fy = prefs.int(K.MEDIA_Y) / 1000f
        fabMedia.x = (parent.width - fabMedia.width) * fx
        fabMedia.y = (parent.height - fabMedia.height) * fy
    }

    /** Round button you can drag anywhere; tap opens the small action panel. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMediaFab() {
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var moved = false
        fabMedia.setOnTouchListener { v, e ->
            val parent = v.parent as View
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - e.rawX; dY = v.y - e.rawY; downX = e.rawX; downY = e.rawY; moved = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > dp(8)) moved = true
                    if (moved) {
                        v.x = (e.rawX + dX).coerceIn(0f, (parent.width - v.width).toFloat())
                        v.y = (e.rawY + dY).coerceIn(0f, (parent.height - v.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (moved) {
                            val rx = (parent.width - v.width).coerceAtLeast(1)
                            val ry = (parent.height - v.height).coerceAtLeast(1)
                            prefs.setInt(K.MEDIA_X, (v.x / rx * 1000).toInt().coerceIn(0, 1000))
                            prefs.setInt(K.MEDIA_Y, (v.y / ry * 1000).toInt().coerceIn(0, 1000))
                        } else v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        fabMedia.setOnClickListener { showMediaPanel() }
    }

    private fun showMediaPanel() {
        val t = cur
        if (t.media.isEmpty()) return
        MediaUi.showPanel(
            this, fabMedia,
            onPip = { playBest(true) },
            onFull = { playBest(false) },
            onDownload = { MediaUi.showSheet(this, t) }
        )
    }

    private fun playBest(pip: Boolean) {
        val vids = cur.media.filter { it.playable }
        when {
            vids.isEmpty() -> toast("No playable video found yet")
            vids.size == 1 -> playMedia(vids[0], pip)
            else -> Ui.listSheet(this, "Play", vids.mapIndexed { i, m ->
                "${i + 1}   ${m.name}   (${m.typeLabel})" to { playMedia(m, pip) }
            })
        }
    }

    fun playMedia(item: MediaItem, pip: Boolean) {
        val subs = ArrayList(cur.media.filter { it.kind == MediaKind.SUB }.map { it.url })
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", item.url)
                .putExtra("title", item.name)
                .putExtra("referer", item.referer ?: cur.url ?: "")
                .putExtra("ua", item.ua ?: userAgent())
                .putExtra("cookie", CookieManager.getInstance().getCookie(item.url) ?: "")
                .putStringArrayListExtra("subs", subs)
                .putExtra("pip", pip)
        )
    }

    companion object {
        private const val DARK_JS =
            "(function(){if(document.getElementById('orbit-dark'))return;var s=document.createElement('style');s.id='orbit-dark';" +
                "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}" +
                "img,video,picture,canvas,iframe,svg image{filter:invert(1) hue-rotate(180deg)!important}';" +
                "(document.head||document.documentElement).appendChild(s)})()"

        private const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val OVERLAY_JS =
            "(function(){var n=0;[].forEach.call(document.querySelectorAll('body *'),function(e){var s=getComputedStyle(e);" +
                "if((s.position==='fixed'||s.position==='sticky')&&parseInt(s.zIndex||'0')>=100&&" +
                "e.offsetHeight>window.innerHeight*0.25&&e.offsetWidth>window.innerWidth*0.5){e.remove();n++}});" +
                "document.documentElement.style.overflow='auto';if(document.body)document.body.style.overflow='auto';return n})()"

        private const val READER_JS =
            "(function(){var n=document.querySelector('article')||document.querySelector('main')||document.body;" +
                "var ps=[].slice.call(n.querySelectorAll('h1,h2,h3,p,li,blockquote,pre'));" +
                "return JSON.stringify({t:document.title,b:ps.map(function(p){return [p.tagName,p.innerText]})" +
                ".filter(function(x){return x[1]&&x[1].trim().length>0&&(x[0]!=='LI'||x[1].length>40)})})})()"
    }
}
