package com.redowan.orbit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Bookmarks and history list. Returns the chosen address to [MainActivity]. */
class LibraryActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var query = ""
    private val isBookmarks get() = intent.getStringExtra("mode") != "history"
    private val adapter = EntryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(R.layout.activity_library)
        list = findViewById(R.id.libList)
        empty = findViewById(R.id.libEmpty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.libTitle).text = if (isBookmarks) "Bookmarks" else "History"
        findViewById<View>(R.id.libBack).setOnClickListener { finish() }
        findViewById<View>(R.id.libClear).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(if (isBookmarks) "Delete all bookmarks?" else "Clear history?")
                .setPositiveButton("Delete") { _, _ ->
                    if (isBookmarks) store.bookmarks().forEach { store.removeBookmark(it.url) } else store.clearHistory()
                    reload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<EditText>(R.id.libSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; filter() }
        })
        reload()
    }

    private fun reload() {
        all = if (isBookmarks) store.bookmarks() else store.history()
        filter()
    }

    private fun filter() {
        shown = if (query.isEmpty()) all else all.filter {
            it.title.contains(query, true) || it.url.contains(query, true)
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun pick(e: Entry, mode: String) {
        setResult(RESULT_OK, Intent().putExtra("url", e.url).putExtra("mode", mode))
        finish()
    }

    private fun menu(e: Entry) {
        Ui.listSheet(
            this, e.title.ifBlank { e.url }, listOf(
                "Open in new tab" to { pick(e, "new") },
                "Open in incognito tab" to { pick(e, "incognito") },
                "Copy link" to {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("link", e.url))
                    toast("Copied")
                },
                "Delete" to {
                    if (isBookmarks) store.removeBookmark(e.url) else store.removeHistory(e.url)
                    reload()
                }
            )
        )
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tile: FrameLayout = v.findViewById(R.id.entryTile)
            val icon: ImageView = v.findViewById(R.id.entryIcon)
            val letter: TextView = v.findViewById(R.id.entryLetter)
            val title: TextView = v.findViewById(R.id.entryTitle)
            val url: TextView = v.findViewById(R.id.entryUrl)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = shown[position]
            val host = hostOf(e.url)
            h.title.text = e.title.ifBlank { host.ifEmpty { e.url } }
            h.url.text = if (!isBookmarks && e.time > 0)
                host + "  ·  " + DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            else e.url
            val fav = store.favicon(host)
            if (fav != null) {
                h.tile.background = Ui.roundRect(color(R.color.pill), dp(10).toFloat())
                h.letter.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.setImageBitmap(fav)
            } else {
                h.tile.background = Ui.roundRect(Ui.tileColor(host.ifEmpty { e.title }), dp(10).toFloat())
                h.icon.visibility = View.GONE
                h.letter.visibility = View.VISIBLE
                h.letter.text = Ui.letterOf(e.title, e.url)
            }
            h.itemView.setOnClickListener { pick(e, "same") }
            h.itemView.setOnLongClickListener { menu(e); true }
        }
    }
}
