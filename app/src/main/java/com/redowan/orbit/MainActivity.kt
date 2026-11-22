package com.redowan.orbit

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView, var title: String = "")

    private lateinit var store: Store
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var tabCount: TextView

    private val tabs = mutableListOf<Tab>()
    private var current = 0

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            )
            fileCallback = null
        }

    private val cur: Tab get() = tabs[current]

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.container)
        tabCount = findViewById(R.id.tabCount)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (cur.web.canGoBack()) cur.web.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            if (cur.web.canGoForward()) cur.web.goForward()
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { cur.web.reload() }
        findViewById<View>(R.id.btnTabs).setOnClickListener { showTabs() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                go(urlBar.text.toString())
                true
            } else false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    cur.web.canGoBack() -> cur.web.goBack()
                    tabs.size > 1 -> closeTab(current)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        newTab(intent?.dataString)
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tabs

    private fun newTab(url: String? = null) {
        val w = WebView(this)
        configure(w)
        tabs.add(Tab(w))
        switchTo(tabs.size - 1)
        w.loadUrl(url ?: HOME)
    }

    private fun switchTo(index: Int) {
        container.removeAllViews()
        current = index
        container.addView(
            cur.web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        urlBar.setText(cur.web.url ?: "")
        progress.visibility = View.GONE
        tabCount.text = tabs.size.toString()
    }

    private fun closeTab(index: Int) {
        val t = tabs.removeAt(index)
        container.removeAllViews()
        t.web.destroy()
        if (tabs.isEmpty()) {
            newTab()
        } else {
            switchTo(minOf(index, tabs.size - 1))
        }
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t ->
            (if (i == current) "\u25CF " else "") +
                t.title.ifBlank { t.web.url ?: "New tab" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(names) { _, i -> switchTo(i) }
            .setPositiveButton("New tab") { _, _ -> newTab() }
            .setNeutralButton("Close current") { _, _ -> closeTab(current) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ WebView setup

    @Suppress("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            userAgentString = if (store.desktop) DESKTOP_UA else null
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val scheme = r.url.scheme ?: return false
                if (scheme in WEB_SCHEMES) return false
                // mailto:, tel:, intent:, market: ... hand over to other apps
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, r.url))
                } catch (_: Exception) {
                }
                return true
            }

            override fun shouldInterceptRequest(
                v: WebView,
                r: WebResourceRequest
            ): WebResourceResponse? {
                if (store.adBlock && AdBlocker.isBlocked(r.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (tabs.isNotEmpty() && v === cur.web) urlBar.setText(url ?: "")
            }

            override fun onPageCommitVisible(v: WebView, url: String?) {
                applyDark(v)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                applyDark(v)
                if (url != null) store.addHistory(Entry(v.title ?: url, url))
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (tabs.isNotEmpty() && v === cur.web) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                tabs.firstOrNull { it.web === v }?.title = title ?: ""
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                (window.decorView as FrameLayout).addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                WindowInsetsControllerCompat(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            } catch (_: Exception) {
                toast("Download failed")
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun applyDark(v: WebView) {
        v.evaluateJavascript(if (store.dark) DARK_ON else DARK_OFF, null)
    }

    // ------------------------------------------------------------------ navigation

    private fun go(input: String) {
        cur.web.loadUrl(resolve(input))
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
        cur.web.requestFocus()
    }

    private fun resolve(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return HOME
        val hasScheme = t.startsWith("http://") || t.startsWith("https://")
        val looksLikeUrl = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
        return when {
            hasScheme -> t
            looksLikeUrl -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 0, "Add bookmark")
        m.add(0, 3, 0, "Bookmarks")
        m.add(0, 4, 0, "History")
        m.add(0, 5, 0, "Find in page")
        m.add(0, 6, 0, "Reader mode")
        m.add(0, 7, 0, "Share link")
        m.add(0, 8, 0, "Desktop site").apply { isCheckable = true; isChecked = store.desktop }
        m.add(0, 9, 0, "Dark pages").apply { isCheckable = true; isChecked = store.dark }
        m.add(0, 10, 0, "Ad blocker").apply { isCheckable = true; isChecked = store.adBlock }
        m.add(0, 11, 0, "Clear history")

        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab()
                2 -> addBookmark()
                3 -> showEntries("Bookmarks", store.bookmarks()) { store.removeBookmark(it) }
                4 -> showEntries("History", store.history()) { store.removeHistory(it) }
                5 -> findInPage()
                6 -> cur.web.evaluateJavascript(READER_JS, null)
                7 -> share()
                8 -> {
                    store.desktop = !store.desktop
                    tabs.forEach {
                        it.web.settings.userAgentString = if (store.desktop) DESKTOP_UA else null
                    }
                    cur.web.reload()
                }
                9 -> {
                    store.dark = !store.dark
                    tabs.forEach { applyDark(it.web) }
                }
                10 -> {
                    store.adBlock = !store.adBlock
                    cur.web.reload()
                }
                11 -> {
                    store.clearHistory()
                    toast("History cleared")
                }
            }
            true
        }
        pm.show()
    }

    private fun addBookmark() {
        val url = cur.web.url ?: return
        store.addBookmark(Entry(cur.web.title ?: url, url))
        toast("Bookmarked")
    }

    private fun share() {
        val url = cur.web.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun findInPage() {
        val input = EditText(this).apply {
            hint = "Find in page"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setPositiveButton("Find") { _, _ -> cur.web.findAllAsync(input.text.toString()) }
            .setNeutralButton("Next") { _, _ -> cur.web.findNext(true) }
            .setNegativeButton("Clear") { _, _ -> cur.web.clearMatches() }
            .show()
    }

    private fun showEntries(title: String, entries: List<Entry>, delete: (Entry) -> Unit) {
        if (entries.isEmpty()) {
            toast("Nothing here yet")
            return
        }
        val shown = entries.toMutableList()
        val adapter = object : ArrayAdapter<Entry>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, shown
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = e.title.ifBlank { e.url }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = e.url
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                return v
            }
        }
        val list = ListView(this)
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle("$title (long-press to delete)")
            .setView(list)
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            cur.web.loadUrl(adapter.getItem(i)!!.url)
            dialog.dismiss()
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val e = adapter.getItem(i)!!
            delete(e)
            adapter.remove(e)
            true
        }
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val HOME = "https://duckduckgo.com"

        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        // "Dark pages": inverts the page colours, then flips images/video back.
        private const val DARK_ON =
            "(function(){var s=document.getElementById('orbit-dark');" +
                "if(!s){s=document.createElement('style');s.id='orbit-dark';" +
                "document.documentElement.appendChild(s);}" +
                "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff}" +
                "img,video,picture,canvas{filter:invert(1) hue-rotate(180deg)!important}';})();"

        private const val DARK_OFF =
            "(function(){var s=document.getElementById('orbit-dark');if(s)s.remove();})();"

        // Reader mode: keeps headings, paragraphs and big images, restyles the page.
        private const val READER_JS =
            "(function(){var title=document.title;" +
                "var root=document.querySelector('article')||document.querySelector('main')||document.body;" +
                "var out='';" +
                "root.querySelectorAll('h1,h2,h3,p,li,blockquote,img').forEach(function(e){" +
                "if(e.tagName==='IMG'){if(e.naturalWidth>250){out+='<img src=\"'+e.src+'\">';}return;}" +
                "var t=e.innerText.trim();if(t.length<2)return;" +
                "var tag=e.tagName.toLowerCase();if(tag==='li')tag='p';" +
                "out+='<'+tag+'>'+t.replace(/</g,'&lt;')+'</'+tag+'>';});" +
                "document.head.innerHTML='<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{font:19px/1.7 Georgia,serif;max-width:680px;margin:0 auto;padding:16px;" +
                "background:#faf6ee;color:#222}img{max-width:100%;height:auto}</style>';" +
                "document.body.className='';document.body.removeAttribute('style');" +
                "document.body.innerHTML='<h1>'+title.replace(/</g,'&lt;')+'</h1>'+out;" +
                "window.scrollTo(0,0);})();"
    }
}
