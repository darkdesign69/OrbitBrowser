package com.redowan.orbit

import android.content.Context
import android.content.res.Configuration

/** Items that can be placed on the top bar or the bottom bar (Settings > Layout). */
object Bars {
    class Item(val id: String, val label: String, val icon: String)

    val ALL = listOf(
        Item("home", "Home page", "outline_home"),
        Item("address", "Address bar", ""),
        Item("tabs", "Tab list", ""),
        Item("menu", "Menu", "outline_more_vert"),
        Item("back", "Back", "outline_keyboard_arrow_left"),
        Item("forward", "Forward", "outline_keyboard_arrow_right"),
        Item("reload", "Refresh", "outline_refresh"),
        Item("newtab", "New tab", "outline_add"),
        Item("bookmarks", "Bookmark", "outline_kid_star"),
        Item("history", "History", "outline_history"),
        Item("downloads", "Downloads", "outline_download"),
        Item("settings", "Settings", "outline_settings"),
        Item("share", "Share", "outline_share"),
        Item("find", "Find in page", "outline_find_in_page"),
        Item("space", "Space", "outline_border_clear")
    )

    const val DEFAULT_TOP = "home,address,tabs,menu"
    const val DEFAULT_BOTTOM = ""

    fun item(id: String): Item? = ALL.firstOrNull { it.id == id }

    fun parse(s: String): MutableList<String> =
        s.split(',').map { it.trim() }.filter { item(it) != null }.toMutableList()

    fun join(l: List<String>) = l.joinToString(",")

    /** Soul's own icon for the current theme. */
    fun icon(ctx: Context, name: String, size: Int = 24): Int {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return ctx.resources.getIdentifier("${name}_${if (night) "dark" else "black"}_$size", "drawable", ctx.packageName)
    }
}
