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
        if (url.contains("_TPL_") || url.contains("%s") || url.contains("{{") || url.contains("\${") || url.contains("%7B%7B")) return null
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
    class Seg(val url: String, val keyUri: String?, val iv: String?, val seq: Long, val off: Long = -1, val len: Long = -1)
    class Playlist(val segs: List<Seg>, val mapUri: String?, val sampleAes: Boolean, val mapOff: Long = -1, val mapLen: Long = -1)

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
        var mapOff = -1L
        var mapLen = -1L
        var sample = false
        var rangeLen = -1L
        var rangeOff = -1L
        val ends = HashMap<String, Long>()
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
                val a = attrs(line)
                map = a["URI"]?.let { resolve(base, it) }
                val br = a["BYTERANGE"]
                if (br != null) {
                    mapLen = br.substringBefore('@').toLongOrNull() ?: -1L
                    mapOff = br.substringAfter('@', "0").toLongOrNull() ?: 0L
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                val v = line.substringAfter(':').trim()
                rangeLen = v.substringBefore('@').toLongOrNull() ?: -1L
                rangeOff = if (v.contains('@')) v.substringAfter('@').toLongOrNull() ?: -1L else -2L
            } else if (!line.startsWith("#")) {
                val u = resolve(base, line)
                var off = -1L
                var len = -1L
                if (rangeLen >= 0) {
                    off = if (rangeOff >= 0) rangeOff else (ends[u] ?: 0L)
                    len = rangeLen
                    ends[u] = off + len
                }
                segs.add(Seg(u, keyUri, iv, seq, off, len))
                rangeLen = -1L; rangeOff = -1L
                seq++
            }
        }
        return Playlist(segs, map, sample, mapOff, mapLen)
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
        @Volatile var cancelled = false
        @Volatile var uri: Uri? = null
        @Volatile var wantName: String? = null
        @Volatile var workers = 1
        val startedAt = System.currentTimeMillis()
        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    val jobs = CopyOnWriteArrayList<Job>()

    /** Called on the main thread whenever a job changes. */
    @Volatile var listener: (() -> Unit)? = null

    private val pool = Executors.newCachedThreadPool()
    private val running = java.util.concurrent.atomic.AtomicInteger(0)

    /** Waits while the number of running downloads is at the "Concurrent download" limit. */
    private fun waitTurn(ctx: Context, job: Job) {
        val limit = Prefs(ctx).int("dl_conc")
        if (limit <= 0) { running.incrementAndGet(); return }
        while (true) {
            if (job.cancelled) throw IllegalStateException("Cancelled")
            val n = running.get()
            if (n < limit && running.compareAndSet(n, n + 1)) return
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private const val CHANNEL = "orbit_downloads"
    private var nextId = 1000

    fun jobFor(item: MediaItem): Job? = jobs.lastOrNull { it.item.url == item.url }

    /** Stops a running job; the file that was being written is deleted. */
    fun cancel(job: Job) {
        job.cancelled = true
    }

    /** Saves bytes that are already in memory (blob: and data: downloads) to Downloads/Orbit. */
    fun saveBytes(ctx: Context, name: String, data: ByteArray): Job {
        val app = ctx.applicationContext
        val clean = uniqueName(name.substringBeforeLast('.', name), name.substringAfterLast('.', "bin"))
        val item = MediaItem("saved:$clean", MediaKind.VIDEO, clean.substringAfterLast('.', "bin"), null, null, null)
        val job = Job(item)
        job.fileName = clean
        job.total = data.size.toLong()
        jobs.add(job)
        val id = nextId++
        pool.execute {
            try {
                val sink = Sink(app, clean)
                try {
                    sink.write(data)
                    job.done = data.size.toLong()
                    job.uri = sink.finish()
                } catch (e: Exception) {
                    sink.abort(); throw e
                }
                job.state = 1
            } catch (e: Exception) {
                job.state = 2
                job.error = e.message ?: "Couldn't save the file"
            }
            notifyUser(app, job, id, true)
            main.post { listener?.invoke() }
        }
        main.post { listener?.invoke() }
        return job
    }

    fun start(
        ctx: Context, item: MediaItem, variantUrl: String?, ua: String, cookie: String?,
        name: String? = null, fast: Boolean = false
    ): Job {
        val app = ctx.applicationContext
        val job = Job(item)
        job.wantName = name?.trim()?.takeIf { it.isNotEmpty() }
        job.workers = if (fast) Prefs(app).int("dl_fast").coerceIn(1, 10) else 1
        jobs.add(job)
        val id = nextId++
        pool.execute {
            var counted = false
            try {
                waitTurn(app, job); counted = true
                if (item.kind == MediaKind.HLS) hls(app, job, id, variantUrl ?: item.url, ua, cookie)
                else direct(app, job, id, ua, cookie)
                job.state = 1
            } catch (e: Exception) {
                job.state = 2
                job.error = e.message ?: e.javaClass.simpleName
            }
            if (counted) running.decrementAndGet()
            notifyUser(app, job, id, true)
            main.post { listener?.invoke() }
        }
        main.post { listener?.invoke() }
        return job
    }

    // ------------------------------------------------------------ networking

    private fun open(url: String, item: MediaItem, ua: String, cookie: String?, off: Long = -1, len: Long = -1): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", item.ua ?: ua)
        item.referer?.let { c.setRequestProperty("Referer", it) }
        item.origin?.let { c.setRequestProperty("Origin", it) }
        if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Accept", "*/*")
        if (off >= 0 && len > 0) c.setRequestProperty("Range", "bytes=$off-${off + len - 1}")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c
    }

    fun fetchText(url: String, item: MediaItem, ua: String, cookie: String?): String {
        val c = open(url, item, ua, cookie)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun bytes(url: String, item: MediaItem, ua: String, cookie: String?, off: Long = -1, len: Long = -1): ByteArray {
        val c = open(url, item, ua, cookie, off, len)
        val all = c.inputStream.use { it.readBytes() }
        // the server ignored the Range header and sent the whole file: cut the wanted part out
        if (off >= 0 && len > 0 && c.responseCode == 200 && all.size.toLong() > len) {
            val from = off.toInt().coerceAtMost(all.size)
            return all.copyOfRange(from, (from + len).toInt().coerceAtMost(all.size))
        }
        return all
    }

    // ------------------------------------------------------------ jobs

    private fun direct(ctx: Context, job: Job, id: Int, ua: String, cookie: String?) {
        val item = job.item
        val c = open(item.url, item, ua, cookie)
        job.total = c.contentLengthLong
        val name = uniqueName(job.wantName ?: item.name, item.ext)
        job.fileName = name
        val sink = Sink(ctx, name)
        try {
            c.inputStream.use { copy(it, sink, job, ctx, id) }
            job.uri = sink.finish()
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
        val name = uniqueName(job.wantName ?: item.name, ext)
        job.fileName = name
        job.total = pl.segs.size.toLong()
        val sink = Sink(ctx, name)
        val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val tries = (Prefs(ctx).int("dl_retry") + 1).coerceIn(1, 20)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(job.workers.coerceIn(1, 10))
        try {
            pl.mapUri?.let { sink.write(bytes(it, item, ua, cookie, pl.mapOff, pl.mapLen)) }
            fun fetch(s: Hls.Seg): ByteArray {
                var lastErr: Exception? = null
                for (attempt in 1..tries) {
                    if (job.cancelled) throw IllegalStateException("Cancelled")
                    try {
                        var b = bytes(s.url, item, ua, cookie, s.off, s.len)
                        val ku = s.keyUri
                        if (ku != null) {
                            val key = keys.getOrPut(ku) { bytes(ku, item, ua, cookie) }
                            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Hls.ivBytes(s)))
                            b = cipher.doFinal(b)
                        }
                        return b
                    } catch (e: Exception) {
                        lastErr = e
                        try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {}
                    }
                }
                throw lastErr ?: IllegalStateException("Segment failed")
            }
            val window = job.workers.coerceIn(1, 10) * 2
            val pending = java.util.ArrayDeque<java.util.concurrent.Future<ByteArray>>()
            var next = 0
            while (next < pl.segs.size && pending.size < window) {
                val seg = pl.segs[next++]; pending.add(workers.submit(java.util.concurrent.Callable { fetch(seg) }))
            }
            var written = 0L
            while (pending.isNotEmpty()) {
                if (job.cancelled) throw IllegalStateException("Cancelled")
                val data = try { pending.poll()!!.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e }
                sink.write(data)
                written += data.size
                job.done++
                if (next < pl.segs.size) { val seg = pl.segs[next++]; pending.add(workers.submit(java.util.concurrent.Callable { fetch(seg) })) }
                notifyUser(ctx, job, id, false)
                main.post { listener?.invoke() }
            }
            if (written < 1024) throw IllegalStateException("The stream returned no video data")
            job.uri = sink.finish()
        } catch (e: Exception) {
            sink.abort(); throw e
        } finally {
            workers.shutdownNow()
        }
    }

    private fun copy(input: InputStream, sink: Sink, job: Job, ctx: Context, id: Int) {
        val buf = ByteArray(64 * 1024)
        var last = 0L
        while (true) {
            if (job.cancelled) throw IllegalStateException("Cancelled")
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

        fun finish(): Uri {
            out.close()
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, v, null, null)
            return uri
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
            if (final && (job.cancelled || !Prefs(ctx).bool("dl_notify"))) { nm.cancel(id); return }
            val open = android.app.PendingIntent.getActivity(
                ctx, 0, android.content.Intent(ctx, DownloadsActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val b = android.app.Notification.Builder(ctx, CHANNEL)
                .setContentTitle(job.fileName.ifEmpty { job.item.name })
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setAutoCancel(true)
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
