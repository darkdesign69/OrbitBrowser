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
import android.view.GestureDetector
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
        }
        setContentView(view)
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

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null || abs(dy) < abs(dx) * 1.5f) return false
                swiping = true
                val frac = dy / view.height
                if (e1.x < view.width / 2) changeBrightness(frac) else changeVolume(frac)
                return true
            }
        })
        view.setOnTouchListener { v, ev ->
            gd.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                val was = swiping
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
