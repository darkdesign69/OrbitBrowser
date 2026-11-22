package com.redowan.orbit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class Entry(val title: String, val url: String, val time: Long = 0L)

/** A speed-dial item. When [children] is not null it is a folder. */
class Dial(var title: String, var url: String?, var children: MutableList<Dial>? = null) {
    val isFolder: Boolean get() = children != null
}

/** SharedPreferences / file backed storage for bookmarks, history, speed dial, favicons, session. */
class Store(context: Context) {

    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private val favDir = File(ctx.filesDir, "fav").apply { mkdirs() }
    private val favCache = LruCache<String, Bitmap>(80)

    // ------------------------------------------------------------ bookmarks
    fun bookmarks(): List<Entry> = readEntries("bookmarks")

    fun isBookmarked(url: String?): Boolean = url != null && bookmarks().any { it.url == url }

    fun addBookmark(e: Entry) {
        val list = bookmarks().filter { it.url != e.url }.toMutableList()
        list.add(0, e.copy(time = System.currentTimeMillis()))
        writeEntries("bookmarks", list)
    }

    fun removeBookmark(url: String) =
        writeEntries("bookmarks", bookmarks().filter { it.url != url })

    // ------------------------------------------------------------ history
    fun history(): List<Entry> = readEntries("history")

    fun addHistory(e: Entry) {
        if (!e.url.startsWith("http")) return
        val list = history().toMutableList()
        val first = list.firstOrNull()
        if (first != null && first.url == e.url) {
            list[0] = first.copy(title = e.title.ifBlank { first.title })
        } else {
            list.add(0, e.copy(time = System.currentTimeMillis()))
        }
        writeEntries("history", list.take(600))
    }

    fun removeHistory(url: String) = writeEntries("history", history().filter { it.url != url })
    fun clearHistory() = writeEntries("history", emptyList())

    // ------------------------------------------------------------ speed dial
    fun dial(): MutableList<Dial> {
        val raw = sp.getString("dial", null) ?: return defaultDial().also { saveDial(it) }
        return try { readDial(JSONArray(raw)) } catch (_: Exception) { defaultDial() }
    }

    fun saveDial(list: List<Dial>) {
        sp.edit().putString("dial", writeDial(list).toString()).apply()
    }

    fun resetDial() { saveDial(defaultDial()) }

    private fun defaultDial() = mutableListOf(
        Dial("DuckDuckGo", "https://duckduckgo.com"),
        Dial("Wikipedia", "https://www.wikipedia.org"),
        Dial("YouTube", "https://m.youtube.com"),
        Dial("GitHub", "https://github.com"),
        Dial("Reddit", "https://www.reddit.com"),
        Dial("OpenStreetMap", "https://www.openstreetmap.org"),
        Dial("Hacker News", "https://news.ycombinator.com"),
        Dial("F-Droid", "https://f-droid.org")
    )

    private fun readDial(arr: JSONArray): MutableList<Dial> {
        val out = mutableListOf<Dial>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kids = o.optJSONArray("c")
            out.add(
                if (kids != null) Dial(o.optString("t"), null, readDial(kids))
                else Dial(o.optString("t"), o.optString("u"))
            )
        }
        return out
    }

    private fun writeDial(list: List<Dial>): JSONArray {
        val arr = JSONArray()
        list.forEach { d ->
            val o = JSONObject().put("t", d.title)
            val kids = d.children
            if (kids != null) o.put("c", writeDial(kids)) else o.put("u", d.url ?: "")
            arr.put(o)
        }
        return arr
    }

    // ------------------------------------------------------------ favicons
    fun favicon(host: String?): Bitmap? {
        if (host.isNullOrEmpty()) return null
        val key = host.removePrefix("www.")
        favCache.get(key)?.let { return it }
        val f = File(favDir, "$key.png")
        if (!f.exists()) return null
        val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
        if (bmp != null) favCache.put(key, bmp)
        return bmp
    }

    fun saveFavicon(host: String?, icon: Bitmap?) {
        if (host.isNullOrEmpty() || icon == null || icon.width < 8) return
        val key = host.removePrefix("www.")
        try {
            val scaled = Bitmap.createScaledBitmap(icon, 64, 64, true)
            FileOutputStream(File(favDir, "$key.png")).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            favCache.put(key, scaled)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ session
    /** Saves non-incognito tabs. Each entry is (url or "", title). */
    fun saveSession(tabs: List<Pair<String, String>>, current: Int) {
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject().put("u", it.first).put("t", it.second)) }
        sp.edit().putString("session", arr.toString()).putInt("session_cur", current).apply()
    }

    fun loadSession(): Pair<List<Pair<String, String>>, Int> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(sp.getString("session", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.optString("u") to o.optString("t"))
            }
        } catch (_: Exception) {
        }
        return out to sp.getInt("session_cur", 0)
    }

    // ------------------------------------------------------------ helpers
    private fun readEntries(key: String): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.optString("t"), o.optString("u"), o.optLong("d", 0L)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeEntries(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
