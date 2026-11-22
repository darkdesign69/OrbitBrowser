package com.redowan.orbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class MediaKind { VIDEO, HLS, DASH, AUDIO, SUB }

/** One media file the page asked for (video, stream, audio or subtitle). */
class MediaItem(
    val url: String,
    val kind: MediaKind,
    val ext: String,
    val referer: String?,
    val origin: String?,
    val ua: String?
) {
    /** Short name from the address, e.g. "master" or "index-f3". */
    val name: String by lazy {
        val seg = Uri.parse(url).lastPathSegment?.let { Uri.decode(it) } ?: ""
        val base = seg.substringBeforeLast('.', seg).ifBlank { hostOf(url) }
        base.take(80)
    }

    val typeLabel: String
        get() = when (kind) {
            MediaKind.HLS -> "HLS"
            MediaKind.DASH -> "DASH"
            else -> ext.uppercase()
        }

    val playable: Boolean get() = kind != MediaKind.SUB
    val downloadable: Boolean get() = kind != MediaKind.DASH
}

object MediaDetector {

    private val VIDEO = setOf("mp4", "webm", "mkv", "m4v", "mov", "3gp", "flv", "avi")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val SUB = setOf("vtt", "srt", "ass", "ssa")

    /** Decides from the address alone. Returns kind and extension, or null when it isn't media. */
    fun classify(url: String): Pair<MediaKind, String>? {
        if (!url.startsWith("http")) return null
        val path = (Uri.parse(url).path ?: return null).lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            ext in VIDEO -> MediaKind.VIDEO to ext
            ext == "m3u8" -> MediaKind.HLS to ext
            ext == "mpd" -> MediaKind.DASH to ext
            ext in AUDIO -> MediaKind.AUDIO to ext
            ext in SUB -> MediaKind.SUB to ext
            else -> null
        }
    }
}

/** HLS playlist helpers (own implementation of the public RFC 8216 format). */
object Hls {
    class Variant(val url: String, val bandwidth: Long, val resolution: String?)
    class Seg(val url: String, val keyUri: String?, val iv: String?, val seq: Long)
    class Playlist(val segs: List<Seg>, val mapUri: String?, val sampleAes: Boolean)

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim()).toString()
    } catch (_: Exception) { ref }

    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        ATTR.findAll(line.substringAfter(':')).forEach { out[it.groupValues[1]] = it.groupValues[2].trim('"') }
        return out
    }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun variants(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val a = attrs(lines[i])
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            out.add(Variant(resolve(base, next), a["BANDWIDTH"]?.toLongOrNull() ?: 0L, a["RESOLUTION"]))
        }
        return out
    }

    fun parse(text: String, base: String): Playlist {
        val segs = ArrayList<Seg>()
        var seq = 0L
        var keyUri: String? = null
        var iv: String? = null
        var map: String? = null
        var sample = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            else if (line.startsWith("#EXT-X-KEY:")) {
                val a = attrs(line)
                when (a["METHOD"]) {
                    "AES-128" -> { keyUri = a["URI"]?.let { resolve(base, it) }; iv = a["IV"] }
                    "NONE" -> { keyUri = null; iv = null }
                    else -> sample = true
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                map = attrs(line)["URI"]?.let { resolve(base, it) }
            } else if (!line.startsWith("#")) {
                segs.add(Seg(resolve(base, line), keyUri, iv, seq))
                seq++
            }
        }
        return Playlist(segs, map, sample)
    }

    fun ivBytes(seg: Seg): ByteArray {
        val iv = ByteArray(16)
        val hex = seg.iv
        if (hex != null) {
            val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            for (i in 0 until 16) iv[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var s = seg.seq
            for (i in 15 downTo 8) { iv[i] = (s and 0xff).toByte(); s = s shr 8 }
        }
        return iv
    }
}

/** Downloads media into Downloads/Orbit using the system file store (no storage permission). */
object Downloader {

    class Job(val item: MediaItem) {
        @Volatile var done = 0L
        @Volatile var total = -1L          // bytes, or segment count for streams
        @Volatile var state = 0            // 0 running, 1 finished, 2 failed
        @Volatile var error: String? = null
        @Volatile var fileName: String = ""
        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    val jobs = CopyOnWriteArrayList<Job>()

    /** Called on the main thread whenever a job changes. */
    @Volatile var listener: (() -> Unit)? = null

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private const val CHANNEL = "orbit_downloads"
    private var nextId = 1000

    fun jobFor(item: MediaItem): Job? = jobs.lastOrNull { it.item.url == item.url }

    fun start(ctx: Context, item: MediaItem, variantUrl: String?, ua: String, cookie: String?): Job {
        val app = ctx.applicationContext
        val job = Job(item)
        jobs.add(job)
        val id = nextId++
        pool.execute {
            try {
                if (item.kind == MediaKind.HLS) hls(app, job, id, variantUrl ?: item.url, ua, cookie)
                else direct(app, job, id, ua, cookie)
                job.state = 1
            } catch (e: Exception) {
                job.state = 2
                job.error = e.message ?: e.javaClass.simpleName
            }
            notifyUser(app, job, id, true)
            main.post { listener?.invoke() }
        }
        main.post { listener?.invoke() }
        return job
    }

    // ------------------------------------------------------------ networking

    private fun open(url: String, item: MediaItem, ua: String, cookie: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", item.ua ?: ua)
        item.referer?.let { c.setRequestProperty("Referer", it) }
        item.origin?.let { c.setRequestProperty("Origin", it) }
        if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Accept", "*/*")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c
    }

    fun fetchText(url: String, item: MediaItem, ua: String, cookie: String?): String {
        val c = open(url, item, ua, cookie)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun bytes(url: String, item: MediaItem, ua: String, cookie: String?): ByteArray {
        val c = open(url, item, ua, cookie)
        return c.inputStream.use { it.readBytes() }
    }

    // ------------------------------------------------------------ jobs

    private fun direct(ctx: Context, job: Job, id: Int, ua: String, cookie: String?) {
        val item = job.item
        val c = open(item.url, item, ua, cookie)
        job.total = c.contentLengthLong
        val name = uniqueName(item.name, item.ext)
        job.fileName = name
        val sink = Sink(ctx, name)
        try {
            c.inputStream.use { copy(it, sink, job, ctx, id) }
            sink.finish()
        } catch (e: Exception) {
            sink.abort(); throw e
        }
    }

    private fun hls(ctx: Context, job: Job, id: Int, startUrl: String, ua: String, cookie: String?) {
        val item = job.item
        var url = startUrl
        var body = fetchText(url, item, ua, cookie)
        if (Hls.isMaster(body)) {
            val best = Hls.variants(body, url).maxByOrNull { it.bandwidth } ?: throw IllegalStateException("No stream found")
            url = best.url
            body = fetchText(url, item, ua, cookie)
        }
        val pl = Hls.parse(body, url)
        if (pl.sampleAes) throw IllegalStateException("This stream uses SAMPLE-AES protection")
        if (pl.segs.isEmpty()) throw IllegalStateException("Stream has no segments")
        val ext = if (pl.mapUri != null) "mp4" else "ts"
        val name = uniqueName(item.name, ext)
        job.fileName = name
        job.total = pl.segs.size.toLong()
        val sink = Sink(ctx, name)
        val keys = HashMap<String, ByteArray>()
        try {
            pl.mapUri?.let { sink.write(bytes(it, item, ua, cookie)) }
            for (s in pl.segs) {
                if (job.state == 2) break
                var data: ByteArray? = null
                var lastErr: Exception? = null
                for (attempt in 1..3) {
                    try {
                        var b = bytes(s.url, item, ua, cookie)
                        val ku = s.keyUri
                        if (ku != null) {
                            val key = keys.getOrPut(ku) { bytes(ku, item, ua, cookie) }
                            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Hls.ivBytes(s)))
                            b = cipher.doFinal(b)
                        }
                        data = b
                        break
                    } catch (e: Exception) { lastErr = e }
                }
                if (data == null) throw lastErr ?: IllegalStateException("Segment failed")
                sink.write(data)
                job.done++
                notifyUser(ctx, job, id, false)
                main.post { listener?.invoke() }
            }
            sink.finish()
        } catch (e: Exception) {
            sink.abort(); throw e
        }
    }

    private fun copy(input: InputStream, sink: Sink, job: Job, ctx: Context, id: Int) {
        val buf = ByteArray(64 * 1024)
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sink.write(buf, 0, n)
            job.done += n
            val now = System.currentTimeMillis()
            if (now - last > 700) {
                last = now
                notifyUser(ctx, job, id, false)
                main.post { listener?.invoke() }
            }
        }
    }

    private fun uniqueName(base: String, ext: String): String {
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "media" }
        return "$clean.$ext"
    }

    // ------------------------------------------------------------ MediaStore file sink

    private class Sink(ctx: Context, name: String) {
        private val resolver = ctx.contentResolver
        private val uri: Uri
        private val out: java.io.OutputStream

        init {
            val ext = name.substringAfterLast('.', "")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, if (ext in setOf("vtt", "srt", "ass", "ssa", "ts")) "application/octet-stream" else mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Orbit")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Can't create the file")
            out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Can't write the file")
        }

        fun write(b: ByteArray) = out.write(b)
        fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        fun finish() {
            out.close()
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, v, null, null)
        }

        fun abort() {
            try { out.close() } catch (_: Exception) {}
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ notification

    private var lastNote = 0L

    private fun notifyUser(ctx: Context, job: Job, id: Int, final: Boolean) {
        val now = System.currentTimeMillis()
        if (!final && now - lastNote < 900) return
        lastNote = now
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val b = android.app.Notification.Builder(ctx, CHANNEL)
                .setContentTitle(job.fileName.ifEmpty { job.item.name })
                .setOnlyAlertOnce(true)
            when {
                !final -> b.setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true)
                    .setProgress(100, job.percent.coerceAtLeast(0), job.percent < 0)
                job.state == 1 -> b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Saved in Downloads/Orbit")
                else -> b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Download failed: ${job.error ?: ""}")
            }
            nm.notify(id, b.build())
        } catch (_: Exception) {
        }
    }
}
