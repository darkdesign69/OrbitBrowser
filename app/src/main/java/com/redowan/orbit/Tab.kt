package com.redowan.orbit

import android.graphics.Bitmap
import android.webkit.WebView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One browser tab. The WebView is created lazily ([web] is null for a tab that only shows
 * the start page, or that was restored/released and has not been opened yet).
 */
class Tab(var url: String?, val incognito: Boolean = false) {
    var title: String = ""
    var home: Boolean = url.isNullOrEmpty()
    var web: WebView? = null
    var favicon: Bitmap? = null
    var thumb: Bitmap? = null
    var desktop: Boolean = false
    var loading: Boolean = false
    var progress: Int = 0
    var readerOn: Boolean = false

    /** Media files seen on this page (video, streams, audio, subtitles). Written from the network thread. */
    val media = CopyOnWriteArrayList<MediaItem>()

    /** Host of the page being loaded; read from the IO thread by the ad blocker. */
    @Volatile
    var host: String = hostOf(url)

    fun label(): String = when {
        title.isNotBlank() -> title
        home -> "Home"
        else -> hostOf(url).removePrefix("www.").ifEmpty { url ?: "New tab" }
    }
}
