package com.redowan.orbit

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Orbit's built-in player: HLS, DASH, MP4/WebM, subtitles (VTT, SRT, ASS), speed menu,
 * picture-in-picture, swipe left side for brightness and right side for volume.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var swiping = false
    private var brightness = -1f
    private lateinit var hud: TextView
    private var mode = 0                // 0 none, 1 brightness/volume, 2 seek
    private var seekBase = 0L
    private var seekTarget = 0L
    private var fastHold = false
    private val hudHide = Runnable { hud.visibility = android.view.View.GONE }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = android.view.View.VISIBLE
        hud.removeCallbacks(hudHide)
        hud.postDelayed(hudHide, 800)
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
        }
        val box = FrameLayout(this)
        box.addView(view, FrameLayout.LayoutParams(-1, -1))
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding((18 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(),
                (18 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xAA000000.toInt()); cornerRadius = 40 * resources.displayMetrics.density
            }
            visibility = android.view.View.GONE
        }
        box.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(box)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val url = intent.getStringExtra("url")
        if (url == null) { finish(); return }

        val props = HashMap<String, String>()
        intent.getStringExtra("referer")?.takeIf { it.isNotEmpty() }?.let { props["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { props["Cookie"] = it }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(intent.getStringExtra("ua") ?: "Orbit")
            .setDefaultRequestProperties(props)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()

        val subs = (intent.getStringArrayListExtra("subs") ?: arrayListOf()).take(6).mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s))
                .setMimeType(subMime(s))
                .setLanguage("und")
                .setLabel("Subtitle ${i + 1}")
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        p.setMediaItem(
            MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs)
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(intent.getStringExtra("title")).build())
                .build()
        )
        p.prepare()
        p.playWhenReady = true
        view.player = p
        player = p

        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pl = player ?: return true
                when {
                    e.x < view.width / 3f -> { pl.seekTo((pl.currentPosition - 10000).coerceAtLeast(0)); showHud("-10 s") }
                    e.x > view.width * 2 / 3f -> { pl.seekTo(pl.currentPosition + 10000); showHud("+10 s") }
                    else -> pl.playWhenReady = !pl.playWhenReady
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val pl = player ?: return
                fastHold = true
                pl.setPlaybackSpeed(2f)
                showHud("2x")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val pl = player ?: return false
                if (e1 == null) return false
                val tx = e2.x - e1.x
                val ty = e2.y - e1.y
                if (mode == 0) {
                    if (abs(tx) + abs(ty) < slop) return false
                    if (abs(tx) > abs(ty) * 1.2f) { mode = 2; seekBase = pl.currentPosition } else mode = 1
                }
                swiping = true
                if (mode == 2) {
                    val dur = pl.duration.takeIf { it > 0 } ?: Long.MAX_VALUE / 4
                    seekTarget = (seekBase + (tx / view.width * 120000f).toLong()).coerceIn(0L, dur)
                    showHud(clock(seekTarget) + (if (pl.duration > 0) " / " + clock(pl.duration) else ""))
                } else {
                    val frac = dy / view.height
                    if (e1.x < view.width / 2) changeBrightness(frac) else changeVolume(frac)
                }
                return true
            }
        })
        view.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) { mode = 0; swiping = false }
            gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                if (fastHold) { fastHold = false; player?.setPlaybackSpeed(1f) }
                if (mode == 2 && ev.action == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                val was = swiping
                mode = 0
                swiping = false
                if (was) return@setOnTouchListener true
            }
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            swiping
        }

        if (intent.getBooleanExtra("pip", false)) view.post { enterPip() }
    }

    private fun subMime(u: String): String = when (u.substringBefore('?').substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_VTT
    }

    private fun changeBrightness(frac: Float) {
        if (brightness < 0) brightness = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
        brightness = (brightness + frac * 1.2f).coerceIn(0.02f, 1f)
        window.attributes = window.attributes.also { it.screenBrightness = brightness }
        showHud("Brightness " + (brightness * 100).toInt() + "%")
    }

    private fun changeVolume(frac: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (now + frac * max * 1.4f).coerceIn(0f, max.toFloat())
        // accumulate small movements so the volume steps smoothly
        volumeCarry += next - now
        val step = volumeCarry.toInt()
        if (step != 0) {
            volumeCarry -= step
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (now + step).coerceIn(0, max), 0)
        }
        showHud("Volume " + (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)) + "%")
    }

    private var volumeCarry = 0f

    private fun enterPip() {
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (_: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
