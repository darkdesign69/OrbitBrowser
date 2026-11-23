package com.redowan.orbit

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * One list for everything Orbit downloads: normal files (handled by Android's DownloadManager,
 * which keeps going in the background) and media saved by Orbit itself (video, streams, blob files).
 */
class DownloadsActivity : AppCompatActivity() {

    private class Item(
        val title: String,
        val status: String,
        val percent: Int,          // 0..100, -1 = no bar, -2 = unknown length
        val onClick: () -> Unit
    )

    private val handler = Handler(Looper.getMainLooper())
    private var items: List<Item> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var empty: View
    private lateinit var dm: DownloadManager

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        ThemeBg.apply(this)
        dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        findViewById<View>(R.id.dlBack).setOnClickListener { finish() }
        findViewById<View>(R.id.dlSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "download"))
        }
        findViewById<View>(R.id.dlHeadTitle).setOnClickListener {
            Ui.listSheet(this, null, listOf(
                "Bookmarks" to { startActivity(Intent(this, LibraryActivity::class.java).putExtra("mode", "bookmarks")); finish() },
                "History" to { startActivity(Intent(this, LibraryActivity::class.java).putExtra("mode", "history")); finish() },
                "Downloads" to { }
            ))
        }
        empty = findViewById(R.id.dlEmpty)
        adapter = Adapter()
        val list = findViewById<RecyclerView>(R.id.dlList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        Downloader.listener = { refresh() }
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        Downloader.listener = null
        handler.removeCallbacks(tick)
    }

    private fun refresh() {
        items = load()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun size(b: Long) = Formatter.formatShortFileSize(this, b)

    private fun load(): List<Item> {
        val out = ArrayList<Item>()

        // media Orbit downloads itself
        for (j in Downloader.jobs.reversed()) out.add(jobItem(j))

        // files handled by the system download manager
        class Row(val id: Long, val title: String, val uri: String, val status: Int, val total: Long, val done: Long, val mime: String?)
        val rows = ArrayList<Row>()
        try {
            dm.query(DownloadManager.Query())?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val iUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iMime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                while (c.moveToNext()) {
                    rows.add(Row(c.getLong(iId), c.getString(iTitle) ?: "", c.getString(iUri) ?: "",
                        c.getInt(iStatus), c.getLong(iTotal), c.getLong(iDone), c.getString(iMime)))
                }
            }
        } catch (_: Exception) {
        }
        rows.sortByDescending { it.id }
        for (r in rows) {
            val name = r.title.ifBlank { Uri.parse(r.uri).lastPathSegment ?: "download" }
            val item = when (r.status) {
                DownloadManager.STATUS_SUCCESSFUL -> Item(name, "${size(r.total.coerceAtLeast(0))} · Done", -1) { fileSheet(r.id, name, r.uri, r.mime, true) }
                DownloadManager.STATUS_FAILED -> Item(name, "Failed", -1) { fileSheet(r.id, name, r.uri, r.mime, false) }
                DownloadManager.STATUS_PAUSED -> Item(name, "Paused, waiting for connection", pct(r.done, r.total)) { fileSheet(r.id, name, r.uri, r.mime, false, running = true) }
                DownloadManager.STATUS_PENDING -> Item(name, "Waiting…", -2) { fileSheet(r.id, name, r.uri, r.mime, false, running = true) }
                else -> Item(
                    name,
                    if (r.total > 0) "${size(r.done)} of ${size(r.total)}" else size(r.done),
                    if (r.total > 0) pct(r.done, r.total) else -2
                ) { fileSheet(r.id, name, r.uri, r.mime, false, running = true) }
            }
            out.add(item)
        }
        return out
    }

    private fun pct(done: Long, total: Long) = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -2

    private fun jobItem(j: Downloader.Job): Item {
        val name = j.fileName.ifEmpty { j.item.name }
        return when (j.state) {
            1 -> Item(name, "${size(j.done.coerceAtLeast(0))} · Done", -1) { jobSheet(j, name) }
            2 -> Item(name, "Failed: ${j.error ?: "unknown error"}", -1) { jobSheet(j, name) }
            else -> {
                val streams = j.item.kind == MediaKind.HLS
                val status = when {
                    j.total <= 0 -> "Starting…"
                    streams -> "Segment ${j.done} of ${j.total}"
                    else -> "${size(j.done)} of ${size(j.total)}"
                }
                Item(name, status, if (j.percent >= 0) j.percent else -2) { jobSheet(j, name) }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun open(uri: Uri?, mime: String?) {
        if (uri == null) { toast("The file is gone"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("No app can open this file")
        }
    }

    private fun copyLink(url: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("link", url))
        toast("Copied")
    }

    private fun fileSheet(id: Long, name: String, url: String, mime: String?, done: Boolean, running: Boolean = false) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (done) acts.add("Open" to { open(dm.getUriForDownloadedFile(id), mime) })
        if (!done && !running) acts.add("Try again" to { retry(url, name) })
        if (url.startsWith("http")) acts.add("Copy link" to { copyLink(url) })
        acts.add(
            (if (running) "Cancel download" else "Delete from list and device") to {
                dm.remove(id); refresh()
            }
        )
        Ui.listSheet(this, name, acts)
    }

    private fun retry(url: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Orbit/$name")
            dm.enqueue(req)
            toast("Downloading $name")
            refresh()
        } catch (_: Exception) {
            toast("Couldn't start the download")
        }
    }

    private fun jobSheet(j: Downloader.Job, name: String) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        when (j.state) {
            1 -> {
                val ext = name.substringAfterLast('.', "")
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                acts.add("Open" to { open(j.uri, mime) })
                acts.add("Remove from list" to { Downloader.jobs.remove(j); refresh() })
            }
            2 -> {
                if (j.item.url.startsWith("http")) {
                    acts.add("Try again" to {
                        Downloader.jobs.remove(j)
                        Downloader.start(
                            this, j.item, null, j.item.ua ?: "Mozilla/5.0",
                            CookieManager.getInstance().getCookie(j.item.url)
                        )
                        refresh()
                    })
                }
                acts.add("Remove from list" to { Downloader.jobs.remove(j); refresh() })
            }
            else -> acts.add("Cancel download" to { Downloader.cancel(j); refresh() })
        }
        if (j.item.url.startsWith("http")) acts.add("Copy link" to { copyLink(j.item.url) })
        Ui.listSheet(this, name, acts)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.dlTitle)
            val status: TextView = v.findViewById(R.id.dlStatus)
            val bar: ProgressBar = v.findViewById(R.id.dlProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.title.text = it.title
            h.status.text = it.status
            when {
                it.percent == -1 -> h.bar.visibility = View.GONE
                it.percent == -2 -> { h.bar.visibility = View.VISIBLE; h.bar.isIndeterminate = true }
                else -> { h.bar.visibility = View.VISIBLE; h.bar.isIndeterminate = false; h.bar.progress = it.percent }
            }
            h.itemView.setOnClickListener { _ -> it.onClick() }
        }
    }
}
