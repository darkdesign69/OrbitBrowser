package com.redowan.orbit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** The popup panel and the "Download" list for media found on a page. */
object MediaUi {

    private fun cookieFor(url: String): String? = android.webkit.CookieManager.getInstance().getCookie(url)

    // ------------------------------------------------------------------ small round-button panel

    /** Black rounded panel next to the floating button: pop-out player, full screen, download. */
    fun showPanel(a: MainActivity, anchor: View, onPip: () -> Unit, onFull: () -> Unit, onDownload: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_media_panel)
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        }
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = a.dp(8).toFloat()
        fun btn(icon: Int, desc: String, run: () -> Unit) {
            val b = ImageButton(a).apply {
                setBackgroundResource(R.drawable.bg_media_btn)
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = desc
                setOnClickListener { pw.dismiss(); run() }
            }
            val lp = LinearLayout.LayoutParams(a.dp(52), a.dp(52))
            lp.setMargins(a.dp(8), a.dp(4), a.dp(8), a.dp(4))
            box.addView(b, lp)
        }
        btn(R.drawable.ic_pip, "Pop-out player", onPip)
        btn(R.drawable.ic_fullscreen, "Full-screen player", onFull)
        btn(R.drawable.ic_download, "Download", onDownload)

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = a.resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width - box.measuredWidth).coerceIn(a.dp(8), (screenW - box.measuredWidth - a.dp(8)).coerceAtLeast(a.dp(8)))
        var y = loc[1] - box.measuredHeight - a.dp(10)
        if (y < a.dp(60)) y = loc[1] + anchor.height + a.dp(10)
        pw.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
    }

    // ------------------------------------------------------------------ download list

    fun showSheet(a: MainActivity, tab: Tab) {
        val items = tab.media.toList()
        if (items.isEmpty()) { a.toast("No media found on this page yet"); return }

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, a.dp(14), 0, a.dp(10))
        }
        val head = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(24), 0, a.dp(24), a.dp(12))
            addView(ImageView(a).apply { setImageResource(R.drawable.ic_download) }, LinearLayout.LayoutParams(a.dp(26), a.dp(26)))
            addView(TextView(a).apply {
                text = "Download"
                textSize = 20f
                setTextColor(a.color(R.color.text))
                setPadding(a.dp(34), 0, 0, 0)
            })
        }
        box.addView(head)
        box.addView(View(a).apply { setBackgroundColor(a.color(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(a.dp(16), 0, a.dp(16), 0) })

        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(rows) }
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val maxH = (a.resources.displayMetrics.heightPixels * 0.62f).toInt()
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.also { it.height = maxH }
        }

        val dialog = Ui.sheet(a, box)
        // Like Soul: the qualities of every stream (master playlist) are listed as their own rows
        class Var(val item: MediaItem, val label: String)
        val vars = java.util.concurrent.CopyOnWriteArrayList<Var>()
        val pageTitle = cleanName(a.cur.title)
        fun rebuild() {
            rows.removeAllViews()
            items.forEachIndexed { i, m -> rows.addView(row(a, i, m)) }
            vars.forEachIndexed { k, v ->
                rows.addView(row(a, items.size + k, v.item, v.label) {
                    askDialog(a, v.item, null, (pageTitle.ifEmpty { v.item.name } + " " + v.label).trim(), a.userAgent(), cookieFor(v.item.url))
                })
            }
        }
        Downloader.listener = { rebuild() }
        dialog.setOnDismissListener { Downloader.listener = null }
        rebuild()
        val ua0 = a.userAgent()
        Thread {
            for (m in items) {
                if (m.kind != MediaKind.HLS) continue
                val body = try { Downloader.fetchText(m.url, m, ua0, cookieFor(m.url)) } catch (_: Exception) { null }
                if (body == null || !Hls.isMaster(body)) continue
                Hls.variants(body, m.url).sortedByDescending { it.bandwidth }.forEach { v ->
                    val res = v.resolution ?: (if (v.bandwidth > 0) "${v.bandwidth / 1000} kbps" else "Auto")
                    if (vars.none { it.item.url == v.url }) vars.add(Var(MediaItem(v.url, MediaKind.HLS, "m3u8", m.referer, m.origin, m.ua), res))
                }
            }
            if (vars.isNotEmpty()) a.runOnUiThread { rebuild() }
        }.start()
    }

    private fun row(a: MainActivity, index: Int, m: MediaItem, label: String? = null, run: (() -> Unit)? = null): View {
        val job = Downloader.jobFor(m)
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(66)
            setPadding(a.dp(18), a.dp(6), a.dp(6), a.dp(6))
            val tv = TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { if (run != null) run() else download(a, m) }
        }
        row.addView(TextView(a).apply {
            text = (index + 1).toString()
            textSize = 15f
            setTextColor(a.color(R.color.text_sub))
        }, LinearLayout.LayoutParams(a.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))

        val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(a).apply {
            text = label ?: m.name
            textSize = 16f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(a.color(R.color.text))
        })
        if (job != null) {
            val label = when (job.state) {
                1 -> "Saved in " + Downloader.folderLabel(a).removePrefix("Device/")
                2 -> "Failed: ${job.error ?: ""}"
                else -> if (job.percent >= 0) "${job.percent}%" else "Downloading…"
            }
            mid.addView(TextView(a).apply {
                text = label
                textSize = 12f
                setTextColor(a.color(if (job.state == 2) R.color.accent_dark else R.color.text_sub))
            })
            if (job.state == 0) {
                mid.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    if (job.percent >= 0) progress = job.percent else isIndeterminate = true
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(6)).also { it.topMargin = a.dp(4) })
            }
        }
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(a).apply {
            text = m.typeLabel
            textSize = 15f
            setTextColor(a.color(R.color.text_sub))
            setPadding(a.dp(8), 0, a.dp(4), 0)
        })
        row.addView(iconButton(a, R.drawable.ic_info, "Details") { info(a, m) })
        if (m.playable) row.addView(iconButton(a, R.drawable.ic_play_arrow, "Play") { a.playMedia(m, false) })
        return row
    }

    private fun iconButton(a: MainActivity, icon: Int, desc: String, click: () -> Unit) = ImageButton(a).apply {
        setImageResource(icon)
        contentDescription = desc
        val tv = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(a.dp(44), a.dp(44))
    }

    // ------------------------------------------------------------------ actions

    /** Hands a link to another app (1DM, ADM, a file manager ...). Used when Download manager is "Other apps". */
    fun openWithOtherApp(a: MainActivity, url: String, mime: String?, referer: String?, title: String?) {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(url), mime?.takeIf { it.isNotEmpty() } ?: "video/*")
            putExtra("title", title ?: "")
            putExtra("android.intent.extra.REFERRER", android.net.Uri.parse(referer ?: url))
            putExtra("referer", referer ?: "")
            putExtra("user_agent", a.userAgent())
            cookieFor(url)?.let { putExtra("cookie", it) }
            putExtra("headers", arrayOf("Referer", referer ?: url, "User-Agent", a.userAgent()))
        }
        val own = a.packageManager.queryIntentActivities(view, 0)
            .filter { it.activityInfo.packageName == a.packageName }
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val chooser = android.content.Intent.createChooser(view, "Download with")
        if (own.isNotEmpty()) chooser.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
        try { a.startActivity(chooser) } catch (_: Exception) { a.toast("No app can download this") }
    }

    private fun cleanName(t: String?): String =
        (t ?: "").replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(80)

    private fun download(a: MainActivity, m: MediaItem) {
        if (!m.downloadable) { a.toast("Orbit can't download DASH streams yet. Use Play."); return }
        val running = Downloader.jobFor(m)
        if (running != null && running.state == 0) { a.toast("Already downloading"); return }
        val ua = a.userAgent()
        val cookie = cookieFor(m.url)
        val pageTitle = cleanName(a.cur.title).ifEmpty { m.name }
        if (a.prefs.int("dl_mgr") == 1) {
            a.toast("Choose an app")
            openWithOtherApp(a, m.url, if (m.kind == MediaKind.HLS) "application/x-mpegURL" else null, m.referer ?: a.cur.url, pageTitle)
            return
        }
        a.requestNotifications()
        if (m.kind != MediaKind.HLS) { askDialog(a, m, null, pageTitle, ua, cookie); return }
        // stream: offer the available qualities when it is a master playlist
        Thread {
            val body = try { Downloader.fetchText(m.url, m, ua, cookie) } catch (_: Exception) { null }
            a.runOnUiThread {
                val vs = if (body != null && Hls.isMaster(body)) Hls.variants(body, m.url).sortedByDescending { it.bandwidth } else emptyList()
                if (vs.size > 1) {
                    Ui.listSheet(a, "Quality", vs.map { v ->
                        val res = v.resolution ?: (if (v.bandwidth > 0) "${v.bandwidth / 1000} kbps" else "Auto")
                        res to { askDialog(a, m, v.url, "$pageTitle $res".trim(), ua, cookie) }
                    })
                } else askDialog(a, m, null, pageTitle, ua, cookie)
            }
        }.start()
    }

    /** Round grey icon button used in the download dialog (Soul: 40dp circles). */
    private fun roundBtn(a: MainActivity, icon: Int, desc: String, click: () -> Unit) = ImageButton(a).apply {
        setBackgroundResource(R.drawable.bg_media_btn)
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(a.dp(9), a.dp(9), a.dp(9), a.dp(9))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(a.dp(40), a.dp(40)).also { it.marginStart = a.dp(10) }
    }

    /** Lets the user type the folder inside Downloads (used by the dialog and by Settings > Download). */
    fun pickFolder(a: android.app.Activity, done: () -> Unit) {
        val cur = Downloader.folder(a)
        Ui.listSheet(a, "Download location", listOf(
            "Downloads" to { Prefs(a).setStr("dl_dir", ""); done() },
            "Downloads/Orbit" to { Prefs(a).setStr("dl_dir", "Orbit"); done() },
            "Other folder in Downloads…" to {
                Ui.prompt(a, "Folder name", "e.g. Videos", cur) { t -> Prefs(a).setStr("dl_dir", t.trim().trim('/')); done() }
            }
        ))
    }

    /** Soul-style download dialog: title row, name, location with 4 buttons, Download / Fast download. */
    private fun askDialog(a: MainActivity, m: MediaItem, variantUrl: String?, defName: String, ua: String, cookie: String?) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun small(t: String) = TextView(a).apply { text = t; textSize = 13f; setTextColor(a.color(R.color.text_sub)) }
        fun line() = View(a).apply { setBackgroundColor(a.color(R.color.divider)) }

        // header: play icon + file name (like Soul)
        val ext = if (m.kind == MediaKind.HLS) "" else "." + m.ext
        val head = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(72)
            setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(8))
        }
        if (m.playable) head.addView(ImageButton(a).apply {
            setImageResource(R.drawable.ic_play_arrow)
            contentDescription = "Play"
            background = null
            setOnClickListener { a.playMedia(m, false) }
        }, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))
        head.addView(TextView(a).apply {
            text = defName + ext
            textSize = 16f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(a.color(R.color.text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = a.dp(18) })
        box.addView(head)
        box.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(a.dp(16), 0, a.dp(16), 0) })

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(22), a.dp(16), a.dp(22), a.dp(6)) }
        body.addView(small("Name"))
        val nameRow = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val nameField = android.widget.EditText(a).apply {
            setText(defName); setSingleLine(true); setTextColor(a.color(R.color.text)); textSize = 16f
            setSelection(text.length)
        }
        nameRow.addView(nameField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(ImageButton(a).apply {
            setImageResource(Bars.icon(a, "outline_help", 24)); background = null; contentDescription = "Help"
            setOnClickListener {
                AlertDialog.Builder(a).setTitle("File name")
                    .setMessage("The name the file gets in the download folder. The file type (for example .mp4) is added automatically.")
                    .setPositiveButton("OK", null).show()
            }
        }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        lateinit var dlg: androidx.appcompat.app.AppCompatDialog
        nameRow.addView(ImageButton(a).apply {
            setImageResource(Bars.icon(a, "outline_settings", 24)); background = null; contentDescription = "Download settings"
            setOnClickListener { dlg.dismiss(); a.startActivity(android.content.Intent(a, SettingsActivity::class.java).putExtra("page", "download")) }
        }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        body.addView(nameRow)
        body.addView(View(a), LinearLayout.LayoutParams(1, a.dp(22)))

        val loc = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val locText = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        locText.addView(small("Download location"))
        val locValue = TextView(a).apply {
            text = Downloader.folderLabel(a); textSize = 16f; setTextColor(a.color(R.color.text))
            setPadding(0, a.dp(4), 0, 0)
        }
        locText.addView(locValue)
        loc.addView(locText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        loc.addView(roundBtn(a, Bars.icon(a, "outline_open_with", 24), "Change location") {
            pickFolder(a) { locValue.text = Downloader.folderLabel(a) }
        })
        loc.addView(roundBtn(a, R.drawable.ic_share, "Share link") {
            a.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, m.url), "Share"))
        })
        loc.addView(roundBtn(a, R.drawable.ic_info, "Details") { info(a, m) })
        if (m.playable) loc.addView(roundBtn(a, R.drawable.ic_play_arrow, "Play") { a.playMedia(m, false) })
        body.addView(loc)
        box.addView(body)

        fun go(fast: Boolean) {
            val n = cleanName(nameField.text.toString()).ifEmpty { defName }
            dlg.dismiss()
            Downloader.start(a, m, variantUrl, ua, cookie, n, fast)
            a.toast("Download started")
        }
        box.addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(0, a.dp(12), 0, 0) })
        val btns = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        fun textBtn(t: String, run: () -> Unit) = TextView(a).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(a.color(R.color.text))
            minHeight = a.dp(60)
            val tv = TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { run() }
        }
        btns.addView(textBtn("Download") { go(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(View(a).apply { setBackgroundColor(a.color(R.color.divider)) }, LinearLayout.LayoutParams(1, a.dp(28)))
        btns.addView(textBtn("Fast download") { go(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(btns)
        dlg = Ui.sheet(a, box)
    }

    private fun info(a: MainActivity, m: MediaItem) {
        val dlg = AlertDialog.Builder(a)
            .setTitle(m.name)
            .setMessage("${m.url}\n\nType: ${m.typeLabel}\nSize: checking…")
            .setPositiveButton("Copy link") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("media", m.url))
                a.toast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
        Thread {
            val size = try {
                val c = java.net.URL(m.url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", m.ua ?: a.userAgent())
                m.referer?.let { c.setRequestProperty("Referer", it) }
                cookieFor(m.url)?.let { c.setRequestProperty("Cookie", it) }
                val len = c.contentLengthLong
                if (len > 0) android.text.format.Formatter.formatFileSize(a, len) else "unknown"
            } catch (_: Exception) { "unknown" }
            a.runOnUiThread { dlg.setMessage("${m.url}\n\nType: ${m.typeLabel}\nSize: $size") }
        }.start()
    }
}
