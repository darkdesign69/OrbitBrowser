package com.redowan.orbit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String)

/** Tiny SharedPreferences-backed store for settings, bookmarks and history. */
class Store(context: Context) {

    private val sp = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    var adBlock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) { sp.edit().putBoolean("adblock", v).apply() }

    var dark: Boolean
        get() = sp.getBoolean("dark", false)
        set(v) { sp.edit().putBoolean("dark", v).apply() }

    var desktop: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    // ---- bookmarks ----
    fun bookmarks(): List<Entry> = read("bookmarks")

    fun addBookmark(e: Entry) {
        val list = read("bookmarks").filter { it.url != e.url }.toMutableList()
        list.add(0, e)
        write("bookmarks", list)
    }

    fun removeBookmark(e: Entry) =
        write("bookmarks", read("bookmarks").filter { it.url != e.url })

    // ---- history ----
    fun history(): List<Entry> = read("history")

    fun addHistory(e: Entry) {
        if (!e.url.startsWith("http")) return
        val list = read("history").toMutableList()
        if (list.firstOrNull()?.url == e.url) return
        list.add(0, e)
        write("history", list.take(300))
    }

    fun removeHistory(e: Entry) =
        write("history", read("history").filter { it.url != e.url })

    fun clearHistory() = write("history", emptyList())

    // ---- helpers ----
    private fun read(key: String): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.optString("t"), o.optString("u")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun write(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
