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

/** Entries of the three-dot menu (Settings > Menu). "-" starts a new card. */
object MenuCat {
    val ALL = listOf(
        Bars.Item("reload", "Reload", "ic_refresh"),
        Bars.Item("adblock", "Ad blocker", "ic_shield"),
        Bars.Item("bookmark", "Bookmark", "ic_star_border"),
        Bars.Item("bookmarks", "Bookmarks", "ic_bookmarks"),
        Bars.Item("settings", "Settings", "ic_settings"),
        Bars.Item("back", "Back", "ic_back"),
        Bars.Item("forward", "Forward", "ic_forward"),
        Bars.Item("exit", "Exit app", "ic_power"),
        Bars.Item("history", "History", "ic_history"),
        Bars.Item("downloads", "Downloads", "ic_download"),
        Bars.Item("newtab", "New tab", "ic_add"),
        Bars.Item("fullscreen", "Full screen", "ic_fullscreen_enter"),
        Bars.Item("dark", "Dark mode", "ic_dark"),
        Bars.Item("translate", "Translate", "ic_translate"),
        Bars.Item("read", "Read aloud", "ic_volume"),
        Bars.Item("find", "Find in page", "ic_search"),
        Bars.Item("share", "Share URL", "ic_share"),
        Bars.Item("copy", "Copy link", "ic_copy"),
        Bars.Item("openapp", "Open in app", "ic_open_new"),
        Bars.Item("addpage", "Add current page", "ic_add"),
        Bars.Item("print", "Print / PDF", "ic_print"),
        Bars.Item("reader", "Reader mode", "ic_reader"),
        Bars.Item("source", "Page source", "ic_dashboard"),
        Bars.Item("media", "Page media", "ic_video_library"),
        Bars.Item("textsize", "Text size", "ic_text"),
        Bars.Item("overlays", "Hide overlays", "ic_close"),
        Bars.Item("site", "Site settings", "ic_lock"),
        Bars.Item("incognito", "Incognito mode", "ic_incognito"),
        Bars.Item("desktop", "Desktop", "ic_desktop"),
        Bars.Item("dns", "DNS", "ic_key"),
        Bars.Item("sep", "Separator", "")
    )
    const val DEFAULT_QUICK = "reload,adblock,bookmark,bookmarks,settings"
    const val DEFAULT_LIST = "exit,history,downloads,newtab,sep,fullscreen,dark,translate,read,find,sep," +
        "share,copy,openapp,addpage,print,reader,source,media,textsize,overlays,site"
    const val DEFAULT_BOTTOM = "incognito,desktop,dns"
    fun item(id: String) = ALL.firstOrNull { it.id == id }
    fun parse(s: String): MutableList<String> = s.split(',').map { it.trim() }.filter { item(it) != null }.toMutableList()
}
