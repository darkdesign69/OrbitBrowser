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
import android.util.TypedValue
import android.widget.FrameLayout
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
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
    private lateinit var urlPill: View
    private val countViews = ArrayList<TextView>()
    private val backViews = ArrayList<View>()
    private val fwdViews = ArrayList<View>()
    private val reloadViews = ArrayList<ImageButton>()
    private lateinit var fabMedia: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var webHolder: SwipeRefreshLayout
    private lateinit var startList: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var fabTop: ImageButton
    private lateinit var findBar: View
    private lateinit var findField: EditText
    private lateinit var findCount: TextView

    private val stripAdapter by lazy { TabStripAdapter(this) }
    private val dialAdapter by lazy { DialAdapter(this) }

    private val defaultUa by lazy {
        // the plain WebView UA contains "; wv" and "Version/x.y", which makes many sites serve a broken or limited page
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex(" Version/\\d+\\.\\d+"), "")
    }
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
        if (prefs.int(K.LAY_VER) < 2) {      // bar sizes now measured from Soul (top 50dp, tab row 42dp): drop old saved sizes once
            prefs.sp.edit().remove(K.LAY_TOP_H).remove(K.LAY_TAB_H).putInt(K.LAY_VER, 2).apply()
        }
        store = Store(this)
        AdBlocker.load(this)
        setContentView(R.layout.activity_main)
        bindViews()

        tabStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tabStrip.adapter = stripAdapter
        startList.layoutManager = GridLayoutManager(this, 4)
        startList.adapter = dialAdapter
        dialAdapter.attach(startList)
        webHolder.setColorSchemeResources(R.color.accent_dark)
        webHolder.setOnRefreshListener { cur.web?.reload() ?: run { webHolder.isRefreshing = false } }
        webHolder.setOnChildScrollUpCallback { _, _ -> (cur.web?.scrollY ?: 0) > 0 }

        wireClicks()
        applyThemeBg()
        WebView.setWebContentsDebuggingEnabled(prefs.bool(K.DEBUG_WEB))
        restoreTabs()
        if (prefs.bool(K.KEYBOARD_START) && cur.home) urlField.post { urlField.requestFocus(); urlField.showKeyboard() }
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    cur.home && dialAdapter.goUp() -> {}
                    cur.web?.canGoBack() == true && !cur.home -> goBackPage(cur.web!!)
                    !cur.home && tabs.size == 1 && prefs.int(K.BACK_LAST) == 0 -> {
                        if (prefs.bool(K.LAST_NOTI)) toast("This is the last page of the tab.")
                        goHome()
                    }
                    !cur.home && prefs.int(K.BACK_LAST) == 1 -> {
                        if (prefs.bool(K.LAST_NOTI)) toast("This is the last page of the tab.")
                        finish()
                    }
                    tabs.size > 1 -> {
                        if (!cur.home && prefs.bool(K.LAST_NOTI)) toast("This is the last page of the tab.")
                        closeTab(current)
                    }
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
        applyThemeBg()
        cur.web?.onResume()
    }

    override fun onPause() {
        super.onPause()
        lastPause = SystemClock.elapsedRealtime()
        cur.web?.onPause()
        CookieManager.getInstance().flush()
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

    private var swipeGd: android.view.GestureDetector? = null
    private var swipeActive = false

    /** Swipe left / right on the address bar or tab bar to go to the tab on that side. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (prefs.bool("tab_swipe_switch") && ::addrRow.isInitialized && tabs.size > 1) {
            if (swipeGd == null) {
                swipeGd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent) = true
                    override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                        val dx = e2.x - (e1?.x ?: e2.x)
                        val dy = e2.y - (e1?.y ?: e2.y)
                        if (Math.abs(dx) < dp(56) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false
                        val to = if (dx < 0) current - 1 else current + 1     // swipe left: tab on the left
                        if (to !in tabs.indices) return false
                        select(to)
                        return true
                    }
                })
            }
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                swipeActive = inside(addrRow, ev) || (tabBarRow.visibility == View.VISIBLE && inside(tabBarRow, ev) && !tabStripScrolls())
            }
            if (swipeActive) {
                val flung = swipeGd!!.onTouchEvent(ev)
                if (flung && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    swipeActive = false
                    val c = android.view.MotionEvent.obtain(ev)
                    c.action = android.view.MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c); c.recycle()
                    return true
                }
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swipeActive = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun inside(v: View, ev: android.view.MotionEvent): Boolean {
        val r = IntArray(2); v.getLocationOnScreen(r)
        return v.visibility == View.VISIBLE && ev.rawX >= r[0] && ev.rawX <= r[0] + v.width && ev.rawY >= r[1] && ev.rawY <= r[1] + v.height
    }

    /** When the tab strip is wider than the screen it scrolls sideways itself, so the swipe is left to it. */
    private fun tabStripScrolls() = tabStrip.canScrollHorizontally(-1) || tabStrip.canScrollHorizontally(1)

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
        urlPill = findViewById(R.id.urlPill)
        fabMedia = findViewById(R.id.fabMedia)
        btnReload = findViewById(R.id.btnReload)
        webHolder = findViewById(R.id.webHolder)
        startList = findViewById(R.id.startList)
        progress = findViewById(R.id.progress)
        fabTop = findViewById(R.id.fabTop)
        findBar = findViewById(R.id.findBar)
        findField = findViewById(R.id.findField)
        findCount = findViewById(R.id.findCount)
        setupTabBarSwipe()
    }

    private fun wireClicks() {
        findViewById<View>(R.id.btnNewTab).setOnClickListener { newTab(null) }
        findViewById<ImageButton>(R.id.btnNewTab).setImageResource(Bars.icon(this, "outline_add"))

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

        setupMediaFab()
        btnReload.setOnClickListener {
            val t = cur
            when {
                t.home -> dialAdapter.reload()
                t.loading -> t.web?.stopLoading()
                else -> t.web?.reload()
            }
        }


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

    // ------------------------------------------------------------------ configurable bars (Settings > Layout)

    private fun reloadClick() {
        val t = cur
        when {
            t.home -> dialAdapter.reload()
            t.loading -> t.web?.stopLoading()
            else -> t.web?.reload()
        }
    }

    private fun goBackClick() {
        val w = cur.web
        if (w != null && w.canGoBack() && !cur.home) goBackPage(w) else if (!cur.home) goHome()
    }

    private fun barItem(id: String, bottom: Boolean): View? {
        val box = dp(prefs.int(K.LAY_ICON))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        val lp = if (bottom) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        else LinearLayout.LayoutParams(box, box)
        fun btn(icon: Int, desc: String, click: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = desc
            setBackgroundResource(tv.resourceId)
            layoutParams = lp
            setOnClickListener { click() }
        }
        fun ic(name: String) = Bars.icon(this, name)
        return when (id) {
            "address" -> null
            "space" -> View(this).apply { layoutParams = lp }
            "home" -> btn(ic("outline_home"), "Home") { goHome() }
            "menu" -> btn(ic("outline_more_vert"), "Menu") { showMenu() }
            "back" -> btn(ic("outline_keyboard_arrow_left"), "Back") { goBackClick() }.also { backViews.add(it) }
            "forward" -> btn(ic("outline_keyboard_arrow_right"), "Forward") {
                if (cur.web?.canGoForward() == true) cur.web?.goForward()
            }.also { fwdViews.add(it) }
            "reload" -> btn(ic("outline_refresh"), "Refresh") { reloadClick() }.also { reloadViews.add(it) }
            "newtab" -> btn(ic("outline_add"), "New tab") { newTab(null) }
            "bookmarks" -> btn(ic("outline_kid_star"), "Bookmark") { openLibrary("bookmarks") }
            "history" -> btn(ic("outline_history"), "History") { openLibrary("history") }
            "downloads" -> btn(ic("outline_download"), "Downloads") { startActivity(Intent(this, DownloadsActivity::class.java)) }
            "settings" -> btn(ic("outline_settings"), "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            "share" -> btn(ic("outline_share"), "Share") { cur.url?.let { share(it) } }
            "find" -> btn(ic("outline_find_in_page"), "Find in page") { openFind() }
            "tabs" -> FrameLayout(this).apply {
                layoutParams = lp
                setBackgroundResource(tv.resourceId)
                contentDescription = "Tabs"
                val c = TextView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(22), Gravity.CENTER)
                    setBackgroundResource(R.drawable.bg_tabcount)
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.icon))
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    text = tabs.size.toString()
                }
                addView(c); countViews.add(c)
                setOnClickListener { TabSwitcher(this@MainActivity).show() }
                setOnLongClickListener { tabsQuickMenu(); true }
            }
            else -> null
        }
    }

    private fun fillBar(row: LinearLayout, ids: List<String>, bottom: Boolean) {
        row.removeAllViews()
        var addressDone = false
        for (id in ids) {
            if (id == "address") {
                if (addressDone || bottom) continue
                addressDone = true
                (urlPill.parent as? ViewGroup)?.removeView(urlPill)
                val pillH = (prefs.int(K.LAY_TOP_H) - 16).coerceAtLeast(26)
                urlPill.layoutParams = LinearLayout.LayoutParams(0, dp(pillH), 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                btnReload.layoutParams = btnReload.layoutParams.apply { width = dp(pillH - 2); height = dp(pillH - 2) }
                row.addView(urlPill)
            } else barItem(id, bottom)?.let { row.addView(it) }
        }
    }

    /** Builds the top bar, bottom bar and sizes from Settings > Layout. */
    private fun rebuildBars() {
        countViews.clear(); backViews.clear(); fwdViews.clear(); reloadViews.clear()
        val top = Bars.parse(prefs.str(K.LAY_TOP))
        val bottom = Bars.parse(prefs.str(K.LAY_BOTTOM))
        fillBar(addrRow as LinearLayout, top, false)
        fillBar(navRow as LinearLayout, bottom, true)
        if (top.contains("address")) { /* pill attached above */ }
        else (urlPill.parent as? ViewGroup)?.removeView(urlPill)
        addrRow.layoutParams = addrRow.layoutParams.apply { height = dp(prefs.int(K.LAY_TOP_H)) }
        addrRow.visibility = if (top.isEmpty()) View.GONE else View.VISIBLE
        // the row is never shorter than the tabs themselves (Settings > Tab > Detailed settings > Height)
        val tabRowH = TabStyle.rowDp(prefs)
        tabBarRow.layoutParams = tabBarRow.layoutParams.apply { height = dp(tabRowH) }
        navRow.layoutParams = navRow.layoutParams.apply { height = dp(prefs.int(K.LAY_BOT_H)) }
        navRow.visibility = if (bottom.isEmpty()) View.GONE else View.VISIBLE
        (startList.layoutManager as? GridLayoutManager)?.spanCount = prefs.int(K.LAY_COLS).coerceIn(2, 8)
        findViewById<ImageButton>(R.id.btnNewTab).layoutParams =
            findViewById<ImageButton>(R.id.btnNewTab).layoutParams.apply { width = dp(tabRowH); height = dp(tabRowH) }
        val addBtn = findViewById<ImageButton>(R.id.btnNewTab)
        (tabBarRow as LinearLayout).let { r ->
            r.removeView(addBtn)
            r.addView(addBtn, if (prefs.int(K.TAB_ADD_RIGHT) == 1) r.childCount else 0)
        }
        tabCountSync()
        fullStrip()
        applyHeader()
    }

    // ------------------------------------------------------------------ tab groups and strip

    /** Tab indices shown as chips: a group shows ONE chip (its most recently used tab) with a count badge. */
    var slots: List<Int> = emptyList()
        private set

    private var groupSeq = 0

    private fun computeSlots(): List<Int> {
        val best = HashMap<Int, Int>()
        for (i in tabs.indices) {
            val g = tabs[i].group
            if (g != 0) { val b = best[g]; if (b == null || tabs[i].touched > tabs[b].touched) best[g] = i }
        }
        val out = ArrayList<Int>(tabs.size)
        val done = HashSet<Int>()
        for (i in tabs.indices) {
            val g = tabs[i].group
            if (g == 0) out.add(i) else if (done.add(g)) out.add(best[g] ?: i)
        }
        return out
    }

    fun groupSize(g: Int): Int = if (g == 0) 0 else tabs.count { it.group == g }

    private fun fullStrip() { slots = computeSlots(); stripAdapter.notifyDataSetChanged() }

    /** Tap on the chip of the open group: list its tabs. */
    fun showGroupMenu(anchor: View, g: Int) {
        val m = android.widget.PopupMenu(this, anchor)
        tabs.indices.filter { tabs[it].group == g }.forEachIndexed { n, i ->
            m.menu.add(0, i, n, (if (i == current) "\u2713 " else "") + tabs[i].label().take(40))
        }
        m.setOnMenuItemClickListener { select(it.itemId); true }
        m.show()
    }

    // ------------------------------------------------------------------ header colour (Display > Header)

    private fun hdrColor(): Int? = if (prefs.int(K.HDR_MODE) == 1) prefs.int(K.HDR_COLOR) else null

    /** Text/icon colour on the header: dark on a light colour, white on a dark one; 0 = no custom header. */
    fun hdrFg(): Int {
        val c = hdrColor() ?: return 0
        return if (androidx.core.graphics.ColorUtils.calculateLuminance(c) > 0.6) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
    }

    /** Paints top bar, tab bar and status bar in the custom header colour and recolours their icons and text. */
    private fun applyHeader() {
        if (!::addrRow.isInitialized || !::urlPill.isInitialized) return
        val c = hdrColor()
        val fg = hdrFg()
        if (c != null) {
            addrRow.setBackgroundColor(c); tabBarRow.setBackgroundColor(c)
            window.statusBarColor = c
        }
        fun tint(v: View) {
            if (v === tabStrip) return
            if (v is ImageButton) { if (fg != 0) v.setColorFilter(fg) else v.clearColorFilter() }
            else if (v is ViewGroup) for (i in 0 until v.childCount) tint(v.getChildAt(i))
        }
        tint(addrRow); tint(tabBarRow)
        if (fg != 0) {
            val soft = (fg and 0xFFFFFF) or 0x30000000
            urlField.setTextColor(fg); urlField.setHintTextColor((fg and 0xFFFFFF) or 0x99000000.toInt())
            urlPill.background = GradientDrawable().apply { setColor(soft); cornerRadius = dp(24).toFloat() }
            countViews.forEach { v ->
                v.setTextColor(fg)
                v.background = GradientDrawable().apply { setColor(0); setStroke(dp(2), fg); cornerRadius = dp(8).toFloat() }
            }
        } else {
            urlField.setTextColor(color(R.color.text)); urlField.setHintTextColor(color(R.color.text_sub))
            urlPill.setBackgroundResource(R.drawable.bg_pill)
            countViews.forEach { v -> v.setTextColor(color(R.color.icon)); v.setBackgroundResource(R.drawable.bg_tabcount) }
        }
    }

    private fun countText() =
        if (prefs.bool(K.TAB_INDI)) "${(current + 1).coerceAtLeast(1)}/${tabs.size}" else tabs.size.toString()

    private fun tabCountSync() { val s = countText(); countViews.forEach { it.text = s } }

    /** Swipe up / down on the tab bar runs the action chosen in Settings > Tab. */
    private fun tabBarAction(code: Int) {
        when (code) {
            1 -> TabSwitcher(this).show()
            2 -> closeTab(current)
            3 -> newTab(null)
        }
    }

    private fun setupTabBarSwipe() {
        val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                val dx = e2.x - (e1?.x ?: e2.x)
                if (Math.abs(dy) < dp(24) || Math.abs(dy) < Math.abs(dx)) return false
                tabBarAction(prefs.int(if (dy < 0) K.TAB_SWIPE_UP else K.TAB_SWIPE_DN))
                return true
            }
        })
        tabStrip.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                gd.onTouchEvent(e); return false
            }
        })
        tabBarRow.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
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
            if (prefs.int(K.TAB_BAR_MODE) == 2) { root.addView(tabBarRow, 0); root.addView(addrRow, 1) }
            else { root.addView(addrRow, 0); root.addView(tabBarRow, 1) }
        }
        rebuildBars()
        if (!prefs.scrollHide) showBars()

        tabs.forEach { applyWebSettings(it) }
        updateSecureFlag()
        applyDisplayPrefs()
        updateAddress()
        updateMediaFab()
    }

    /** Orientation, brightness, screen-on and full screen from Settings > Display / Composition. */
    /** Wallpaper behind the bars and start page, see [ThemeBg]. */
    private fun applyThemeBg() {
        val on = ThemeBg.isActive(this, prefs)
        val barBg = if (on) color(R.color.bg) else Color.TRANSPARENT
        for (id in intArrayOf(R.id.addrRow, R.id.tabBarRow, R.id.navRow)) findViewById<View>(id)?.setBackgroundColor(barBg)
        ThemeBg.apply(this)
        val acc = ThemeBg.accent(this, prefs)
        if (acc != null) {
            findViewById<android.widget.ProgressBar>(R.id.progress)?.progressTintList = android.content.res.ColorStateList.valueOf(acc)
            webHolder.setColorSchemeColors(acc)
        } else {
            webHolder.setColorSchemeResources(R.color.accent_dark)
        }
        applyHeader()
    }

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
        val old = current
        current = p
        showTab(old)
        if (p != old) slideIn(if (p > old) 1 else -1)
    }

    /** Short slide + fade of the page area from the side the new tab lies on (swipe on the address bar or tap on a chip). */
    private fun slideIn(dir: Int) {
        for (v in arrayOf<View>(webHolder, startList)) {
            if (v.visibility != View.VISIBLE) continue
            v.animate().cancel()
            v.translationX = dir * v.width * 0.15f
            v.alpha = 0.5f
            v.animate().translationX(0f).alpha(1f).setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    fun closeTab(p: Int) {
        if (p !in tabs.indices) return
        val t = tabs.removeAt(p)
        val undoUrl = if (!t.home) t.url else null
        val undoInc = t.incognito
        destroyTab(t)
        if (tabs.isEmpty()) {
            tabs.add(Tab(null)); current = 0
        } else if (p < current) current--
        else if (p == current) current = p.coerceAtMost(tabs.size - 1)
        showTab()
        if (prefs.bool(K.TAB_UNDO) && !undoInc && !undoUrl.isNullOrEmpty()) {
            showUndoBar {
                val nt = Tab(undoUrl, false)
                val at = p.coerceAtMost(tabs.size)
                tabs.add(at, nt); current = at
                navigate(nt, undoUrl)
            }
        }
    }

    /** Small "Tab closed - Undo" bar (no Material theme needed). */
    private fun showUndoBar(undo: () -> Unit) {
        val deco = window.decorView as ViewGroup
        deco.findViewWithTag<View>("orbit_undo")?.let { deco.removeView(it) }
        val bar = LinearLayout(this).apply {
            tag = "orbit_undo"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE323232.toInt()); cornerRadius = dp(8).toFloat() }
        }
        bar.addView(TextView(this).apply { text = "Tab closed"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "UNDO"; setTextColor(0xFF8AB4F8.toInt()); textSize = 14f; setPadding(dp(16), dp(12), dp(12), dp(12))
            setOnClickListener { deco.removeView(bar); undo() }
        })
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(12), 0, dp(12), dp(72))
        deco.addView(bar, lp)
        bar.postDelayed({ if (bar.parent != null) deco.removeView(bar) }, 5000)
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

    /** [oldCur] given = only the selection changed: when the chips are the same just the two affected ones are rebound. */
    private fun refreshTabs(oldCur: Int = -1) {
        val old = slots
        slots = computeSlots()
        val pos = slots.indexOf(current)
        if (oldCur >= 0 && old == slots) {
            val op = slots.indexOf(oldCur)
            if (op >= 0) stripAdapter.notifyItemChanged(op)
            if (pos >= 0) { stripAdapter.notifyItemChanged(pos); tabStrip.smoothScrollToPosition(pos) }
        } else {
            stripAdapter.notifyDataSetChanged()
            if (pos >= 0) tabStrip.scrollToPosition(pos)
        }
        tabCountSync()
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
    private val webFrame by lazy {
        android.widget.FrameLayout(this).also {
            webHolder.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Safety net for a blank page: the current page must be attached to webFrame and have a size; if not, attach it again. */
    private fun healWeb(t: Tab) {
        val w = t.web ?: return
        if (t !== cur || t.home) return
        if (w.parent !== webFrame || w.width == 0 || w.height == 0) {
            (w.parent as? ViewGroup)?.removeView(w)
            webFrame.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            w.visibility = View.VISIBLE
            webHolder.visibility = View.VISIBLE
            webHolder.requestLayout()
        }
    }

    private fun showTab(oldCur: Int = -1) {
        val t = cur
        t.touched = System.nanoTime()
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
            // Pages stay attached to webFrame and are only shown / hidden. Detaching and re-attaching a WebView on every
            // switch was the lag and the blank page. webFrame is the one permanent child of the SwipeRefreshLayout.
            if (w.parent !== webFrame) {
                (w.parent as? ViewGroup)?.removeView(w)
                webFrame.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            for (i in 0 until webFrame.childCount) {
                val c = webFrame.getChildAt(i)
                c.visibility = if (c === w) View.VISIBLE else View.GONE
            }
            webHolder.visibility = View.VISIBLE
            startList.visibility = View.GONE
            w.alpha = 1f
            w.onResume()
            w.resumeTimers()
            ui.postDelayed({ healWeb(t) }, 400)
        }
        urlField.imeOptions = EditorInfo.IME_ACTION_GO or
            (if (t.incognito && prefs.keySecure) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
        urlField.hint = if (t.incognito) "Incognito: search or type URL" else "Search or type URL"
        showBars()
        updateSecureFlag()
        updateAddress()
        updateNav()
        refreshTabs(oldCur)
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
            fullStrip()
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
        tab.progress = 0
        if (tab === tabs.getOrNull(current)) showTab()
        w.loadUrl(url)
        watchLoad(tab, w, url, true)
    }

    /**
     * A pasted link sometimes never started loading in an old tab, while the same link worked in a new tab (fresh WebView).
     * If the page shows no progress after 5 s, the tab gets a fresh WebView and loads the link again (once).
     */
    private fun watchLoad(tab: Tab, w: WebView, url: String, retry: Boolean) {
        ui.postDelayed({
            if (tab.web !== w || tab.home || tab.progress >= 15) return@postDelayed
            if (!retry) return@postDelayed
            destroyTab(tab)
            val nw = createWebView(tab)
            applyWebSettings(tab)
            tab.progress = 0
            if (tab === tabs.getOrNull(current)) showTab()
            nw.loadUrl(url)
            watchLoad(tab, nw, url, false)
        }, 5000)
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
        reloadViews.forEach { it.setImageResource(if (t.loading && !t.home) R.drawable.ic_stop else R.drawable.ic_refresh) }
        if (t.loading && t.progress < 100 && !t.home) {
            progress.visibility = View.VISIBLE
            progress.progress = t.progress
        } else progress.visibility = View.GONE
    }

    private fun updateNav() {
        val w = cur.web
        backViews.forEach { it.alpha = if (!cur.home) 1f else 0.4f }
        fwdViews.forEach { it.alpha = if (w != null && w.canGoForward()) 1f else 0.4f }
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

    /** Back; with "Go back without refresh" the page comes from the cache instead of the network. */
    private fun goBackPage(w: WebView) {
        if (prefs.bool("back_nofresh")) {
            w.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            w.goBack()
            w.postDelayed({ w.settings.cacheMode = WebSettings.LOAD_DEFAULT }, 2500)
        } else w.goBack()
    }

    private fun isDesktop(tab: Tab) = tab.desktop || prefs.desktopAll || prefs.int(K.UA) == 1

    /** Desktop site: pages lay out on a 1280px wide canvas (like Chrome's "Desktop site"), not the phone width. */
    private fun injectDesktopView(tab: Tab, view: WebView) {
        if (!isDesktop(tab)) return
        view.evaluateJavascript(DESKTOP_JS, null)
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
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = if (desktop || uaMode == 1) 100 else prefs.textZoom
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
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            safeBrowsingEnabled = prefs.safeBrowsing
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // an opaque background: the wallpaper behind the app must never show through a page
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        w.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        w.setLayerType(View.LAYER_TYPE_NONE, null)
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
                return blockedResponse(req.url.toString())
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            injectDesktopView(tab, view)
            tab.loading = true
            tab.progress = 0
            tab.home = false
            ui.postDelayed({ healWeb(tab) }, 700)
            if (!url.isNullOrEmpty() && url != tab.url) { tab.media.clear(); ui.post(mediaTick) }
            if (!url.isNullOrEmpty() && !url.startsWith("data:")) {
                tab.url = url
                tab.host = hostOf(url)
            }
            if (tab === cur) { updateAddress(); updateNav() }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectDesktopView(tab, view)
            injectPageDark(view)
            injectFont(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectDesktopView(tab, view)
            tab.loading = false
            tab.progress = 100
            val title = view.title ?: ""
            if (title.isNotBlank() && !title.startsWith("data:")) tab.title = title
            if (!tab.incognito && prefs.saveHistory && !tab.url.isNullOrEmpty()) {
                store.addHistory(Entry(tab.title, tab.url!!))
            }
            injectPageDark(view)
            injectFont(view)
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

    /** Empty answer for a blocked request, with a matching type so pages do not choke on it. */
    private fun blockedResponse(url: String): WebResourceResponse {
        val name = url.substringBefore('?').substringAfterLast('/').lowercase()
        val mime = when {
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".json") -> "application/json"
            else -> "text/plain"
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
    }

    /** Script that gives web pages the Orbit font (Source Code Pro). Zero specificity and no !important: any font a page sets itself on an element (icon fonts, player glyphs) wins, so icons never turn into empty boxes. */
    private val fontJs: String by lazy {
        try {
            val b64 = android.util.Base64.encodeToString(
                assets.open("fonts/SourceCodePro-Regular.ttf").use { it.readBytes() }, android.util.Base64.NO_WRAP
            )
            "(function(){if(window.__orbitFont)return;window.__orbitFont=1;try{var b=atob('" + b64 + "');" +
                "var a=new Uint8Array(b.length);for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);" +
                "var f=new FontFace('OrbitMono',a.buffer);document.fonts.add(f);f.load();" +
                "var css=':where(html,body,body *:not(i):not(svg):not(svg *):not([class*=icon i]):not([class*=fa-]):not([class^=fa]):not([class*=material i]):not([class*=glyph i])){font-family:OrbitMono,monospace}';" +
                "var s=new CSSStyleSheet();s.replaceSync(css);" +
                "document.adoptedStyleSheets=Array.from(document.adoptedStyleSheets).concat([s]);}catch(e){}})()"
        } catch (_: Exception) {
            ""
        }
    }

    private fun injectFont(view: WebView) {
        if (!prefs.bool(ThemeBg.WEB_FONT) || fontJs.isEmpty()) return
        view.evaluateJavascript(fontJs, null)
    }

    private fun injectPageDark(view: WebView) {
        if (!prefs.pageDark) {
            view.evaluateJavascript("var e=document.getElementById('orbit-dark');if(e)e.remove();", null)
            return
        }
        val b = if (prefs.bool("dark_img")) prefs.int("img_bright").coerceIn(20, 100) else 100
        view.evaluateJavascript(
            "(function(){var o=document.getElementById('orbit-dark');if(o)o.remove();var s=document.createElement('style');s.id='orbit-dark';" +
                "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}" +
                "img,video,picture,canvas,iframe,svg image{filter:invert(1) hue-rotate(180deg) brightness(" + b + "%)!important}';" +
                "(document.head||document.documentElement).appendChild(s)})()", null
        )
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
            fullStrip()
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
            if (prefs.bool(K.AUTO_GROUP) && !tab.home) {       // Tab > Automatic grouping: a new window opened by a page joins its group
                if (tab.group == 0) tab.group = ++groupSeq
                t.group = tab.group
            }
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

        override fun onCloseWindow(window: WebView) {
            val i = tabs.indexOf(tab)
            if (i >= 0 && tabs.size > 1) closeTab(i)
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
        if (prefs.int("dl_mgr") == 1) { MediaUi.openWithOtherApp(this, url, mime, referer, name); return }
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
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, (Downloader.folder(this).let { f -> if (f.isEmpty()) "" else "$f/" }) + name)
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
    private class ME(val icon: Int, val label: String, val dim: Boolean = false, val act: () -> Unit)

    private fun menuEntry(id: String): ME? {
        val t = cur
        return when (id) {
            "reload" -> ME(R.drawable.ic_refresh, "Reload") { if (cur.home) dialAdapter.reload() else cur.web?.reload() }
            "adblock" -> ME(R.drawable.ic_shield, "Ad blocker", !prefs.adBlock) {
                prefs.setBool(K.ADBLOCK, !prefs.adBlock)
                toast(if (prefs.adBlock) "Ad blocker on" else "Ad blocker off")
                cur.web?.reload()
            }
            "bookmark" -> ME(if (store.isBookmarked(t.url)) R.drawable.ic_star else R.drawable.ic_star_border,
                if (store.isBookmarked(t.url)) "Bookmarked" else "Bookmark", false, needPage { toggleBookmark(it) })
            "bookmarks" -> ME(R.drawable.ic_bookmarks, "Bookmarks") { openLibrary("bookmarks") }
            "settings" -> ME(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            "back" -> ME(R.drawable.ic_back, "Back") { if (cur.web?.canGoBack() == true) goBackPage(cur.web!!) }
            "forward" -> ME(R.drawable.ic_forward, "Forward") { if (cur.web?.canGoForward() == true) cur.web?.goForward() }
            "exit" -> ME(R.drawable.ic_power, "Exit app") { finishAndRemoveTask() }
            "history" -> ME(R.drawable.ic_history, "History") { openLibrary("history") }
            "downloads" -> ME(R.drawable.ic_download, "Downloads") { startActivity(Intent(this, DownloadsActivity::class.java)) }
            "newtab" -> ME(R.drawable.ic_add, "New tab") { newTab(null) }
            "fullscreen" -> ME(R.drawable.ic_fullscreen_enter, if (prefs.bool(K.FULLSCREEN)) "Exit full screen" else "Full screen") {
                prefs.setBool(K.FULLSCREEN, !prefs.bool(K.FULLSCREEN)); applyDisplayPrefs()
            }
            "dark" -> ME(R.drawable.ic_dark, if (prefs.pageDark) "Light mode" else "Dark mode") {
                prefs.setBool(K.PAGE_DARK, !prefs.pageDark)
                tabs.forEach { tb -> tb.web?.let { w -> injectPageDark(w) } }
            }
            "translate" -> ME(R.drawable.ic_translate, "Translate", false, needPage { tb ->
                navigate(tb, "https://translate.google.com/translate?sl=auto&tl=${Langs.target(prefs)}&u=" + Uri.encode(tb.url))
            })
            "read" -> ME(R.drawable.ic_volume, "Read aloud", false, needPage { readAloud(it) })
            "find" -> ME(R.drawable.ic_search, "Find in page") { openFind() }
            "share" -> ME(R.drawable.ic_share, "Share URL", false, needPage { share(it.url ?: "") })
            "copy" -> ME(R.drawable.ic_copy, "Copy link", false, needPage { copy(it.url ?: "") })
            "openapp" -> ME(R.drawable.ic_open_new, "Open in app", false, needPage { openWithApp(it.url ?: "") })
            "addpage" -> ME(R.drawable.ic_add, "Add current page", false, needPage { tb ->
                val list = store.dial()
                list.add(Dial(tb.label().take(24), tb.url))
                store.saveDial(list)
                toast("Added to start page")
            })
            "print" -> ME(R.drawable.ic_print, "Print / PDF", false, needPage { tb ->
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = tb.label()
                pm.print(name, tb.web!!.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            })
            "reader" -> ME(R.drawable.ic_reader, if (t.readerOn) "Exit reader" else "Reader mode", false, needPage { toggleReader() })
            "source" -> ME(R.drawable.ic_dashboard, "Page source", false, needPage { showSource(it) })
            "media" -> ME(R.drawable.ic_video_library, "Page media") {
                if (cur.media.isEmpty()) toast("No media found on this page yet") else MediaUi.showSheet(this, cur)
            }
            "textsize" -> ME(R.drawable.ic_text, "Text size") {
                Ui.listSheet(this, "Text size", listOf(80, 90, 100, 115, 130, 150).map { sz ->
                    "$sz%" to {
                        prefs.setInt(K.TEXT_ZOOM, sz)
                        tabs.forEach { tb -> applyWebSettings(tb) }
                    }
                })
            }
            "overlays" -> ME(R.drawable.ic_close, "Hide overlays", false, needPage { tb ->
                tb.web?.evaluateJavascript(OVERLAY_JS) { n -> toast("Removed ${n ?: "0"} overlay(s)") }
            })
            "site" -> ME(R.drawable.ic_lock, "Site settings", false, needPage { siteSettings(it) })
            "incognito" -> ME(R.drawable.ic_incognito, "Incognito mode") { newTab(null, true) }
            "desktop" -> ME(R.drawable.ic_desktop, if (isDesktop(t)) "Mobile" else "Desktop", false, needPage {
                it.desktop = !isDesktop(it); applyWebSettings(it); it.web?.reload()
            })
            "dns" -> ME(R.drawable.ic_key, "DNS") {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "dns"))
            }
            else -> null
        }
    }

    /** Column that is never taller than [maxH]: the weighted list inside shrinks and scrolls, the other rows stay. */
    private class CappedColumn(ctx: Context) : LinearLayout(ctx) {
        var maxH = Int.MAX_VALUE
        override fun onMeasure(w: Int, h: Int) {
            val cap = minOf(View.MeasureSpec.getSize(h), maxH)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    /**
     * Soul-style popup. Like Soul the quick buttons (top) and Incognito / Desktop / DNS (bottom) are FIXED; only the list
     * between them scrolls. It opens about 24dp below the top of the toolbar, 62% wide, icon-only bottom row.
     */
    private fun showPopupMenu() {
        val d = AppCompatDialog(this, R.style.PopupDialog)
        val inf = LayoutInflater.from(this)
        val matchW = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        val rippleRes = tv.resourceId

        // ---- scrolling list in the middle
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        fun newCard(): LinearLayout {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.setBackgroundResource(R.drawable.bg_c_single)
            list.addView(c, LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(8) })
            return c
        }
        fun row(card: LinearLayout, icon: Int, label: String, act: () -> Unit) {
            val v = inf.inflate(R.layout.item_pmenu, card, false)
            v.findViewById<ImageView>(R.id.pmIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.pmLabel).text = label
            v.setOnClickListener { d.dismiss(); act() }
            card.addView(v)
        }
        var card: LinearLayout? = null
        for (id in MenuCat.parse(prefs.str(K.MENU_LIST))) {
            if (id == "sep") { card = null; continue }
            val e = menuEntry(id) ?: continue
            if (card == null) card = newCard()
            row(card, e.icon, e.label, e.act)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }

        // ---- fixed top row (quick buttons)
        fun fixedBar(top: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.bg))
                val r = dp(24).toFloat()
                cornerRadii = if (top) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        val topBar = fixedBar(true)
        MenuCat.parse(prefs.str(K.MENU_QUICK)).forEach { id ->
            val e = menuEntry(id) ?: return@forEach
            val b = ImageButton(this)
            b.setImageResource(e.icon)
            b.setBackgroundResource(rippleRes)
            b.contentDescription = e.label
            b.alpha = if (e.dim) 0.4f else 1f
            b.setOnClickListener { d.dismiss(); e.act() }
            topBar.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
        }

        // ---- fixed bottom row (icons only, like Soul)
        val bottomBar = fixedBar(false)
        MenuCat.parse(prefs.str(K.MENU_BOTTOM)).forEach { id ->
            val e = menuEntry(id) ?: return@forEach
            val b = ImageButton(this)
            b.setImageResource(e.icon)
            b.setBackgroundResource(rippleRes)
            b.contentDescription = e.label
            b.setOnClickListener { d.dismiss(); e.act() }
            bottomBar.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
        }

        val panel = CappedColumn(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true                       // taps on the panel must not reach the dismiss layer
            visibility = View.INVISIBLE              // shown after it has been positioned
        }
        if (topBar.childCount > 0) {
            panel.addView(topBar, LinearLayout.LayoutParams(matchW, wrapH))
            panel.addView(View(this).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(matchW, dp(1)))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(matchW, 0, 1f))
        if (bottomBar.childCount > 0) panel.addView(bottomBar, LinearLayout.LayoutParams(matchW, wrapH).apply { topMargin = dp(4) })

        val frame = android.widget.FrameLayout(this)
        frame.setOnClickListener { d.dismiss() }
        val dm = resources.displayMetrics
        frame.addView(panel, android.widget.FrameLayout.LayoutParams((dm.widthPixels * 0.62f).toInt(), wrapH,
            Gravity.TOP or Gravity.END).apply { rightMargin = dp(2) })
        d.setContentView(frame, ViewGroup.LayoutParams(matchW, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setGravity(Gravity.TOP)
        }
        d.show()
        frame.post {
            // measured in screen coordinates, so it does not matter where the dialog window starts
            val bar = IntArray(2); addrRow.getLocationOnScreen(bar)
            val own = IntArray(2); frame.getLocationOnScreen(own)
            val topMargin = (bar[1] - own[1]).coerceAtLeast(0) + dp(24)
            panel.maxH = (frame.height - topMargin - dp(4)).coerceAtLeast(dp(120))
            (panel.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = topMargin
            panel.requestLayout()
            panel.visibility = View.VISIBLE
        }
    }

    private fun showGridMenu() {
        val t = cur
        val starred = store.isBookmarked(t.url)
        val items = listOf(
            MenuItem(R.drawable.ic_back, "Back") { if (cur.web?.canGoBack() == true) goBackPage(cur.web!!) },
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
            MenuItem(R.drawable.ic_desktop, if (isDesktop(t)) "Mobile site" else "Desktop site", needPage {
                it.desktop = !isDesktop(it); applyWebSettings(it); it.web?.reload()
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

        private const val DESKTOP_JS =
            "(function(){function f(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m)}" +
                "if((m.getAttribute('content')||'').indexOf('width=1280')<0)m.setAttribute('content','width=1280,minimum-scale=0.1,maximum-scale=5,user-scalable=yes')}" +
                "f();if(!window.__orbitDesk){window.__orbitDesk=1;new MutationObserver(f).observe(document.documentElement,{childList:true,subtree:true})}})()"

        private const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val OVERLAY_JS =
            "(function(){var n=0;[].forEach.call(document.querySelectorAll('body *'),function(e){var s=getComputedStyle(e);" +
                "if(s.position==='fixed'&&parseInt(s.zIndex||'0')>=1000&&!e.querySelector('video,input,textarea,[contenteditable]')&&" +
                "e.offsetHeight>=window.innerHeight*0.9&&e.offsetWidth>=window.innerWidth*0.9){e.remove();n++}});" +
                "document.documentElement.style.overflow='auto';if(document.body)document.body.style.overflow='auto';return n})()"

        private const val READER_JS =
            "(function(){var n=document.querySelector('article')||document.querySelector('main')||document.body;" +
                "var ps=[].slice.call(n.querySelectorAll('h1,h2,h3,p,li,blockquote,pre'));" +
                "return JSON.stringify({t:document.title,b:ps.map(function(p){return [p.tagName,p.innerText]})" +
                ".filter(function(x){return x[1]&&x[1].trim().length>0&&(x[0]!=='LI'||x[1].length>40)})})})()"
    }
}
