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
        fun rebuild() {
            rows.removeAllViews()
            items.forEachIndexed { i, m -> rows.addView(row(a, i, m)) }
        }
        Downloader.listener = { rebuild() }
        dialog.setOnDismissListener { Downloader.listener = null }
        rebuild()
    }

    private fun row(a: MainActivity, index: Int, m: MediaItem): View {
        val job = Downloader.jobFor(m)
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dp(66)
            setPadding(a.dp(18), a.dp(6), a.dp(6), a.dp(6))
            val tv = TypedValue()
            a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { download(a, m) }
        }
        row.addView(TextView(a).apply {
            text = (index + 1).toString()
            textSize = 15f
            setTextColor(a.color(R.color.text_sub))
        }, LinearLayout.LayoutParams(a.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))

        val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(a).apply {
            text = m.name
            textSize = 16f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(a.color(R.color.text))
        })
        if (job != null) {
            val label = when (job.state) {
                1 -> "Saved in Downloads/Orbit"
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

    private fun download(a: MainActivity, m: MediaItem) {
        if (!m.downloadable) { a.toast("Orbit can't download DASH streams yet. Use Play."); return }
        val running = Downloader.jobFor(m)
        if (running != null && running.state == 0) { a.toast("Already downloading"); return }
        a.requestNotifications()
        val ua = a.userAgent()
        val cookie = cookieFor(m.url)
        if (m.kind != MediaKind.HLS) {
            Downloader.start(a, m, null, ua, cookie)
            a.toast("Downloading ${m.name}")
            return
        }
        // stream: offer the available qualities when it is a master playlist
        Thread {
            val body = try { Downloader.fetchText(m.url, m, ua, cookie) } catch (_: Exception) { null }
            a.runOnUiThread {
                val vs = if (body != null && Hls.isMaster(body)) Hls.variants(body, m.url).sortedByDescending { it.bandwidth } else emptyList()
                if (vs.size > 1) {
                    Ui.listSheet(a, "Quality", vs.map { v ->
                        val res = v.resolution?.substringAfter('x')?.let { "${it}p" } ?: "Auto"
                        ("$res   ·   ${v.bandwidth / 1000} kbps") to {
                            Downloader.start(a, m, v.url, ua, cookie); a.toast("Downloading ${m.name}")
                        }
                    })
                } else {
                    Downloader.start(a, m, null, ua, cookie)
                    a.toast("Downloading ${m.name}")
                }
            }
        }.start()
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
