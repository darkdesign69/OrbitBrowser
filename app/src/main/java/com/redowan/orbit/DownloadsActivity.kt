package com.redowan.orbit

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Soul-style download list: "All" filter, refresh / search / menu in the header, one row per file
 * (thumbnail or type icon, date, size, three-dot menu) and the running downloads on top with
 * progress, pause and close. It shows every file Orbit saved in Downloads (also after a restart)
 * plus the transfers that are still running (own downloader and Android's DownloadManager).
 */
class DownloadsActivity : AppCompatActivity() {

    private enum class Kind { VIDEO, AUDIO, IMAGE, DOC, ARCHIVE, APP, OTHER }

    private class Item(
        val title: String,
        val kind: Kind,
        val date: Long,                 // ms, 0 = unknown
        val size: Long,
        val uri: Uri?,
        val mime: String?,
        val job: Downloader.Job? = null,
        val dmId: Long = -1L,
        val dmUrl: String = "",
        val running: Boolean = false,
        val failed: Boolean = false,
        val percent: Int = -1,          // 0..100, -1 no bar, -2 unknown length
        val status: String = "",
        val right: String = ""
    )

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private var items: List<Item> = emptyList()
    private var stored: List<Item> = emptyList()
    private var lastFinished = -1
    private var filter: Kind? = null
    private var filterName = "All"
    private var query = ""
    private lateinit var adapter: Adapter
    private lateinit var empty: View
    private lateinit var dm: DownloadManager
    private lateinit var headTitle: TextView
    private lateinit var searchBox: EditText
    private lateinit var spacer: View
    private val thumbs = android.util.LruCache<String, Bitmap>(80)
    private val thumbPool = Executors.newFixedThreadPool(2)

    private val tick = object : Runnable {
        override fun run() {
            val fin = Downloader.jobs.count { it.state == 1 }
            if (fin != lastFinished) { lastFinished = fin; stored = loadStored() }
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        headTitle = findViewById(R.id.dlHeadTitle)
        searchBox = findViewById(R.id.dlSearchBox)
        spacer = findViewById(R.id.dlSpacer)
        empty = findViewById(R.id.dlEmpty)

        findViewById<View>(R.id.dlBack).setOnClickListener { finish() }
        findViewById<View>(R.id.dlRefresh).setOnClickListener { stored = loadStored(); refresh(); toast("Refreshed") }
        findViewById<View>(R.id.dlSearch).setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.dlMoreTop).setOnClickListener { topMenu(it) }
        headTitle.setOnClickListener { filterSheet() }
        setTitleText()
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBox.visibility == View.VISIBLE) toggleSearch() else finish()
            }
        })

        adapter = Adapter()
        val list = findViewById<RecyclerView>(R.id.dlList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        stored = loadStored()
    }

    override fun onStart() {
        super.onStart()
        Downloader.listener = { refresh() }
        lastFinished = -1
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        Downloader.listener = null
        handler.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------ header

    private fun setTitleText() {
        headTitle.text = filterName
        headTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, Bars.icon(this, "outline_arrow_drop_down", 24), 0)
    }

    private fun toggleSearch() {
        val show = searchBox.visibility != View.VISIBLE
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        headTitle.visibility = if (show) View.GONE else View.VISIBLE
        spacer.visibility = if (show) View.GONE else View.VISIBLE
        if (show) searchBox.showKeyboard() else { searchBox.setText(""); searchBox.hideKeyboard() }
    }

    private fun filterSheet() {
        fun pick(name: String, k: Kind?) = name to { filter = k; filterName = name; setTitleText(); refresh() }
        Ui.listSheet(this, null, listOf(
            pick("All", null), pick("Video", Kind.VIDEO), pick("Audio", Kind.AUDIO), pick("Image", Kind.IMAGE),
            pick("Document", Kind.DOC), pick("Archive", Kind.ARCHIVE), pick("App", Kind.APP), pick("Other", Kind.OTHER),
            "Bookmarks" to { startActivity(Intent(this, LibraryActivity::class.java).putExtra("mode", "bookmarks")); finish() },
            "History" to { startActivity(Intent(this, LibraryActivity::class.java).putExtra("mode", "history")); finish() }
        ))
    }

    /** Small drop-down like Soul's: Sort, Show detail info, Single line title (+ Settings). */
    private fun topMenu(anchor: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundRect(color(R.color.card), dp(14).toFloat())
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(box, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(10).toFloat()
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        fun row(label: String, click: (() -> Unit)?, key: String? = null) {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(18), 0, dp(14), 0)
            }
            r.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(color(R.color.text)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (key != null) {
                val sw = SwitchCompat(this).apply { isChecked = prefs.bool(key) }
                sw.setOnCheckedChangeListener { _, v -> prefs.setBool(key, v); refresh() }
                r.addView(sw)
                r.setOnClickListener { sw.toggle() }
            } else r.setOnClickListener { pw.dismiss(); click?.invoke() }
            box.addView(r)
        }
        row("Sort", {
            val names = listOf("Date", "Name", "Size")
            Ui.listSheet(this, "Sort", names.mapIndexed { i, n ->
                (if (prefs.int("dl_sort") == i) "$n  ✓" else n) to { prefs.setInt("dl_sort", i); refresh() }
            })
        })
        row("Show detail info", null, "dl_detail")
        row("Single line title", null, "dl_single")
        row("Settings", { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "download")) })
        pw.showAsDropDown(anchor, -dp(190), -dp(8))
    }

    // ------------------------------------------------------------------ data

    private fun kindOf(name: String, mime: String?): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = (mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "").lowercase()
        return when {
            m.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "ts", "m4v", "mov", "3gp", "flv", "avi") -> Kind.VIDEO
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> Kind.AUDIO
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> Kind.IMAGE
            ext in setOf("apk", "apks", "xapk", "apkm") || m == "application/vnd.android.package-archive" -> Kind.APP
            ext in setOf("zip", "rar", "7z", "tar", "gz") || m == "application/zip" -> Kind.ARCHIVE
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub") || m.startsWith("text/") -> Kind.DOC
            else -> Kind.OTHER
        }
    }

    /** Files Orbit saved in Downloads (the system only lets an app list its own files, which is what we want). */
    private fun loadStored(): List<Item> {
        val out = ArrayList<Item>()
        try {
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_ADDED
            )
            contentResolver.query(base, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val mime = c.getString(iMime)
                    out.add(Item(name, kindOf(name, mime), c.getLong(iDate) * 1000L, c.getLong(iSize),
                        ContentUris.withAppendedId(base, c.getLong(iId)), mime))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.US, "%.2f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }

    private fun eta(sec: Long) = when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }

    private fun jobItem(j: Downloader.Job): Item {
        val name = j.fileName.ifEmpty { j.wantName ?: j.item.name }
        val kind = kindOf(name, null)
        if (j.state == 2) return Item(name, kind, j.startedAt, 0, null, null, job = j, failed = true, status = "Failed: ${j.error ?: "unknown error"}")
        // for streams "total" counts segments, so the size is estimated from the bytes of the finished segments
        val est = if (j.item.kind == MediaKind.HLS) (if (j.total > 0 && j.done > 0) j.bytes * j.total / j.done else -1L) else j.total
        val status = when {
            j.bytes == 0L -> "Starting…"
            est > 0 -> "${fmt(j.bytes)} / ${fmt(est)}"
            else -> fmt(j.bytes)
        }
        val sp = j.speed
        val right = when {
            j.paused -> "Paused"
            sp > 0 && est > j.bytes -> eta((est - j.bytes) / sp)
            else -> ""
        }
        return Item(name, kind, j.startedAt, j.bytes, null, null, job = j, running = true,
            percent = if (j.percent >= 0) j.percent else -2, status = status, right = right)
    }

    private fun build() {
        val active = ArrayList<Item>()
        for (j in Downloader.jobs.reversed()) if (j.state != 1) active.add(jobItem(j))

        val dmDone = ArrayList<Item>()
        try {
            dm.query(DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val iUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iMime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                val iTime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val url = c.getString(iUri) ?: ""
                    val name = (c.getString(iTitle) ?: "").ifBlank { Uri.parse(url).lastPathSegment ?: "download" }
                    val mime = c.getString(iMime)
                    val total = c.getLong(iTotal)
                    val done = c.getLong(iDone)
                    val kind = kindOf(name, mime)
                    when (c.getInt(iStatus)) {
                        DownloadManager.STATUS_SUCCESSFUL ->
                            if (stored.none { it.title == name }) dmDone.add(
                                Item(name, kind, c.getLong(iTime), total.coerceAtLeast(0), dm.getUriForDownloadedFile(id), mime, dmId = id, dmUrl = url))
                        DownloadManager.STATUS_FAILED ->
                            active.add(Item(name, kind, c.getLong(iTime), 0, null, mime, dmId = id, dmUrl = url, failed = true, status = "Failed"))
                        DownloadManager.STATUS_PAUSED ->
                            active.add(Item(name, kind, c.getLong(iTime), done, null, mime, dmId = id, dmUrl = url, running = true,
                                percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -2,
                                status = "Paused, waiting for connection"))
                        DownloadManager.STATUS_PENDING ->
                            active.add(Item(name, kind, c.getLong(iTime), 0, null, mime, dmId = id, dmUrl = url, running = true, percent = -2, status = "Waiting…"))
                        else ->
                            active.add(Item(name, kind, c.getLong(iTime), done, null, mime, dmId = id, dmUrl = url, running = true,
                                percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -2,
                                status = if (total > 0) "${fmt(done)} / ${fmt(total)}" else fmt(done)))
                    }
                }
            }
        } catch (_: Exception) {
        }

        var files: List<Item> = stored + dmDone
        filter?.let { f -> files = files.filter { it.kind == f } }
        var act: List<Item> = active
        if (query.isNotEmpty()) {
            files = files.filter { it.title.contains(query, true) }
            act = act.filter { it.title.contains(query, true) }
        }
        files = when (prefs.int("dl_sort")) {
            1 -> files.sortedBy { it.title.lowercase() }
            2 -> files.sortedByDescending { it.size }
            else -> files.sortedByDescending { it.date }
        }
        items = act + files
    }

    private fun refresh() {
        build()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ actions

    private fun open(it: Item) {
        val uri = it.uri
        if (uri == null) { toast("The file is gone"); return }
        var mime = it.mime
        if (mime.isNullOrEmpty() || mime == "application/octet-stream") {
            mime = if (it.kind == Kind.VIDEO) "video/*" else MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(it.title.substringAfterLast('.', "").lowercase()) ?: "*/*"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast("No app can open this file")
        }
    }

    private fun share(it: Item) {
        val uri = it.uri ?: return
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(it.mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
        } catch (_: Exception) {
            toast("Can't share this file")
        }
    }

    private fun rename(it: Item) {
        val uri = it.uri ?: return
        Ui.prompt(this, "Rename", "Name", it.title) { n ->
            if (n.isBlank()) return@prompt
            try {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, n) }, null, null)
                stored = loadStored(); refresh()
            } catch (_: Exception) {
                toast("Couldn't rename the file")
            }
        }
    }

    private fun delete(it: Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete file?")
            .setMessage(it.title)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (it.dmId >= 0) dm.remove(it.dmId) else it.uri?.let { u -> contentResolver.delete(u, null, null) }
                } catch (_: Exception) {
                    toast("Couldn't delete the file")
                }
                stored = loadStored(); refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyLink(url: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("link", url))
        toast("Copied")
    }

    private fun retryDm(url: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val sub = Downloader.folder(this)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, (if (sub.isEmpty()) "" else "$sub/") + name)
            dm.enqueue(req)
            toast("Downloading $name")
            refresh()
        } catch (_: Exception) {
            toast("Couldn't start the download")
        }
    }

    private fun closeRunning(it: Item) {
        val j = it.job
        if (j != null) { Downloader.cancel(j); Downloader.jobs.remove(j) } else if (it.dmId >= 0) dm.remove(it.dmId)
        refresh()
    }

    private fun menu(it: Item) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        val j = it.job
        when {
            j != null && it.failed -> {
                if (j.item.url.startsWith("http")) acts.add("Try again" to {
                    Downloader.jobs.remove(j)
                    Downloader.start(this, j.item, null, j.item.ua ?: "Mozilla/5.0", CookieManager.getInstance().getCookie(j.item.url), j.wantName)
                    refresh()
                })
                acts.add("Remove from list" to { Downloader.jobs.remove(j); refresh() })
                if (j.item.url.startsWith("http")) acts.add("Copy link" to { copyLink(j.item.url) })
            }
            it.failed -> {
                if (it.dmUrl.startsWith("http")) acts.add("Try again" to { dm.remove(it.dmId); retryDm(it.dmUrl, it.title) })
                acts.add("Remove from list" to { dm.remove(it.dmId); refresh() })
            }
            else -> {
                acts.add("Open" to { open(it) })
                acts.add("Share" to { share(it) })
                if (it.dmId < 0) acts.add("Rename" to { rename(it) })
                if (it.dmUrl.startsWith("http")) acts.add("Copy link" to { copyLink(it.dmUrl) })
                acts.add("Delete" to { delete(it) })
            }
        }
        Ui.listSheet(this, it.title, acts)
    }

    // ------------------------------------------------------------------ list

    private fun iconFor(k: Kind): Int {
        if (k == Kind.ARCHIVE) return R.drawable.ic_folder
        val name = when (k) {
            Kind.VIDEO -> "baseline_play_arrow"
            Kind.AUDIO -> "baseline_music_note"
            Kind.APP -> "outline_android"
            else -> "outline_description"
        }
        return Bars.icon(this, name, 24)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.dlThumb)
            val icon: ImageView = v.findViewById(R.id.dlIcon)
            val title: TextView = v.findViewById(R.id.dlTitle)
            val status: TextView = v.findViewById(R.id.dlStatus)
            val right: TextView = v.findViewById(R.id.dlRight)
            val bar: ProgressBar = v.findViewById(R.id.dlProgress)
            val pause: ImageButton = v.findViewById(R.id.dlPause)
            val act: ImageButton = v.findViewById(R.id.dlAct)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))
            h.thumb.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
            }
            h.thumb.clipToOutline = true
            return h
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.title.text = it.title
            h.title.maxLines = if (prefs.bool("dl_single")) 1 else 3
            val showThumb = !it.running && !it.failed && it.uri != null && (it.kind == Kind.VIDEO || it.kind == Kind.IMAGE)
            h.thumb.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(if (it.running) Bars.icon(this@DownloadsActivity, "baseline_play_arrow", 24) else iconFor(it.kind))
            h.itemView.tag = it.uri?.toString()
            if (showThumb) loadThumb(h, it)

            if (it.running) {
                h.bar.visibility = View.VISIBLE
                if (it.percent >= 0) { h.bar.isIndeterminate = false; h.bar.progress = it.percent } else h.bar.isIndeterminate = true
                h.status.text = it.status
                h.status.visibility = View.VISIBLE
                h.right.text = it.right
                h.right.visibility = View.VISIBLE
            } else {
                h.bar.visibility = View.GONE
                h.right.visibility = View.GONE
                if (it.failed) { h.status.text = it.status; h.status.visibility = View.VISIBLE }
                else if (prefs.bool("dl_detail")) {
                    val d = if (it.date > 0) java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(java.util.Date(it.date)) else ""
                    h.status.text = (d + "  " + fmt(it.size)).trim()
                    h.status.visibility = View.VISIBLE
                } else h.status.visibility = View.GONE
            }

            // buttons: running own job = pause + close, running system download = close, others = three dots
            val j = it.job
            if (it.running && j != null) {
                h.pause.visibility = View.VISIBLE
                h.pause.setImageResource(Bars.icon(this@DownloadsActivity, if (j.paused) "baseline_play_arrow" else "baseline_pause", 24))
                h.pause.setOnClickListener { _ -> Downloader.togglePause(j); refresh() }
            } else h.pause.visibility = View.GONE
            if (it.running) {
                h.act.setImageResource(Bars.icon(this@DownloadsActivity, "outline_close", 24))
                h.act.setOnClickListener { _ -> closeRunning(it) }
            } else {
                h.act.setImageResource(R.drawable.ic_more)
                h.act.setOnClickListener { _ -> menu(it) }
            }
            h.itemView.setOnClickListener { _ -> if (!it.running && !it.failed) open(it) else if (!it.running) menu(it) }
            h.itemView.setOnLongClickListener { _ -> if (!it.running) { menu(it); true } else false }
        }

        private fun loadThumb(h: VH, it: Item) {
            val uri = it.uri ?: return
            val key = uri.toString()
            val cached = thumbs.get(key)
            if (cached != null) { h.thumb.setImageBitmap(cached); h.thumb.visibility = View.VISIBLE; h.icon.visibility = View.GONE; return }
            thumbPool.execute {
                val bmp = try { contentResolver.loadThumbnail(uri, Size(192, 192), null) } catch (_: Exception) { null }
                if (bmp != null) {
                    thumbs.put(key, bmp)
                    handler.post {
                        if (h.itemView.tag == key) {
                            h.thumb.setImageBitmap(bmp); h.thumb.visibility = View.VISIBLE; h.icon.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
}
