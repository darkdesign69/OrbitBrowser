package com.redowan.orbit

import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
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
    private lateinit var btnStar: ImageButton
    private lateinit var btnReader: ImageButton
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

    // full-screen video
    private var customView: View? = null
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
        restoreTabs()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    cur.web?.canGoBack() == true && !cur.home -> cur.web?.goBack()
                    !cur.home && tabs.size == 1 -> goHome()
                    tabs.size > 1 -> closeTab(current)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
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
        btnStar = findViewById(R.id.btnStar)
        btnReader = findViewById(R.id.btnReader)
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
        urlField.setOnFocusChangeListener { _, focus -> if (!focus) updateAddress() }

        btnStar.setOnClickListener {
            val t = cur
            val url = t.url
            if (t.home || url.isNullOrEmpty()) return@setOnClickListener
            if (store.isBookmarked(url)) {
                store.removeBookmark(url); toast("Bookmark removed")
            } else {
                store.addBookmark(Entry(t.label(), url)); toast("Bookmarked")
            }
            updateAddress()
        }
        btnReader.setOnClickListener { toggleReader() }
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
        tabsBtn.setOnLongClickListener {
            Ui.listSheet(
                this, null, listOf(
                    "New tab" to { newTab(null) },
                    "New incognito tab" to { newTab(null, true) },
                    "Close this tab" to { closeTab(current) }
                )
            )
            true
        }
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
            root.addView(tabBarRow, 0)
            root.addView(addrRow, 1)
        }
        if (!prefs.scrollHide) showBars()

        tabs.forEach { applyWebSettings(it) }
        updateSecureFlag()
        updateAddress()
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
        if (url.isNullOrEmpty()) showTab() else navigate(t, url)
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
        if (current in tabs.indices) tabStrip.scrollToPosition(current)
    }

    private fun restoreTabs() {
        if (prefs.keepTabs) {
            val (saved, idx) = store.loadSession()
            saved.forEach { (u, title) -> tabs.add(Tab(u.ifEmpty { null }).also { it.title = title }) }
            current = idx.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        }
        if (tabs.isEmpty()) tabs.add(Tab(null))
        showTab()
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
        urlField.hint = if (t.incognito) "Incognito: search or enter address" else "Search or enter address"
        showBars()
        updateSecureFlag()
        updateAddress()
        updateNav()
        refreshTabs()
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
        if (!urlField.hasFocus()) urlField.setText(if (t.home) "" else (t.url ?: ""))
        val https = t.url?.startsWith("https://") == true && !t.home
        lockIcon.visibility = if (https) View.VISIBLE else View.GONE
        btnStar.setImageResource(if (store.isBookmarked(t.url)) R.drawable.ic_star else R.drawable.ic_star_border)
        btnReload.setImageResource(if (t.loading && !t.home) R.drawable.ic_stop else R.drawable.ic_refresh)
        btnReader.alpha = if (t.home) 0.35f else if (t.readerOn) 1f else 0.7f
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
        tabBarRow.visibility = View.GONE
        addrRow.visibility = View.GONE
    }

    private fun showBars() {
        barsShown = true
        tabBarRow.visibility = if (prefs.showTabBar) View.VISIBLE else View.GONE
        addrRow.visibility = View.VISIBLE
    }

    private fun onScrolled(tab: Tab, y: Int, oldY: Int) {
        if (tab !== cur) return
        val dy = y - oldY
        if (prefs.scrollHide) {
            if (dy > 24 && y > 200) hideBars() else if (dy < -24) showBars()
        }
        if (prefs.backTop && y > 600 && dy < 0) fabTop.visibility = View.VISIBLE
        else if (y <= 600 || dy > 0) fabTop.visibility = View.GONE
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
        s.userAgentString = if (desktop) desktopUa() else defaultUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = prefs.textZoom
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
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        w.webViewClient = client(tab)
        w.webChromeClient = chrome(tab)
        w.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }
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
            if (prefs.bool(K.OVERLAY)) view.postDelayed({ view.evaluateJavascript(OVERLAY_JS, null) }, 1200)
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
            val mode = prefs.popup            // 0 allow, 1 block suspected ads, 2 block all
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
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> if (extra != null) { linkMenu(extra, null); return true }
            WebView.HitTestResult.IMAGE_TYPE -> if (extra != null) { linkMenu(null, extra); return true }
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

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) { toast("This download type isn't supported"); return }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (_: Exception) {
            toast("Download failed")
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
        val t = cur
        val items = listOf(
            MenuItem(R.drawable.ic_add, "New tab") { newTab(null) },
            MenuItem(R.drawable.ic_incognito, "Incognito tab") { newTab(null, true) },
            MenuItem(R.drawable.ic_bookmarks, "Bookmarks") { openLibrary("bookmarks") },
            MenuItem(R.drawable.ic_history, "History") { openLibrary("history") },

            MenuItem(R.drawable.ic_download, "Downloads") {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("No downloads app") }
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
                val lang = Locale.getDefault().language
                navigate(tb, "https://translate.google.com/translate?sl=auto&tl=$lang&u=" + Uri.encode(tb.url))
            }),
            MenuItem(R.drawable.ic_dashboard, "Page source", needPage { showSource(it) }),
            MenuItem(R.drawable.ic_lock, "Site settings", needPage { siteSettings(it) }),
            MenuItem(R.drawable.ic_close, "Hide overlays", needPage { tb ->
                tb.web?.evaluateJavascript(OVERLAY_JS) { n -> toast("Removed ${n ?: "0"} overlay(s)") }
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

    companion object {
        private const val DARK_JS =
            "(function(){if(document.getElementById('orbit-dark'))return;var s=document.createElement('style');s.id='orbit-dark';" +
                "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}" +
                "img,video,picture,canvas,iframe,svg image{filter:invert(1) hue-rotate(180deg)!important}';" +
                "(document.head||document.documentElement).appendChild(s)})()"

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
