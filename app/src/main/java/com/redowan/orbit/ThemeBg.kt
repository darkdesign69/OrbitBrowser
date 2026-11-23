package com.redowan.orbit

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.WeakHashMap

/**
 * Customize Theme engine (same idea as the OnlyPlay video player):
 *  - a background picture (own picture or one of the built-in wallpapers) drawn behind every screen
 *  - Transparency: how see-through the bars/cards are over the picture
 *  - Blur: how blurred the picture is
 *  - Dark overlay: how dark the picture is
 *  - Color: accent colour used for highlights
 * Values are 0..100 and live in [Prefs] (keys below; defaults in Prefs.DEFAULTS).
 */
object ThemeBg {
    const val MODE = "theme_bg_mode"       // "none", "image" or "wall:<1..9>"
    const val VER = "theme_bg_ver"
    const val TRANS = "theme_bg_trans"
    const val BLUR = "theme_bg_blur"
    const val DIM = "theme_bg_dim"
    const val ACCENT = "theme_accent"      // 0 = default colour
    const val WEB_FONT = "web_font"

    const val NONE = "none"
    /** Views with this tag keep their background as it is (preview, colour dots). */
    const val NOSOFT = "nosoft"
    const val IMAGE = "image"
    private const val WALL = "wall:"

    val WALLS = intArrayOf(
        R.drawable.wall_1, R.drawable.wall_2, R.drawable.wall_3, R.drawable.wall_4, R.drawable.wall_5,
        R.drawable.wall_6, R.drawable.wall_7, R.drawable.wall_8, R.drawable.wall_9
    )

    val ACCENTS = intArrayOf(
        0xFF43A047.toInt(), 0xFFFFB300.toInt(), 0xFFFF5722.toInt(), 0xFFEC407A.toInt(), 0xFFC0CA33.toInt(),
        0xFF00BCD4.toInt(), 0xFF26A69A.toInt(), 0xFF2196F3.toInt(), 0xFF7E57C2.toInt(), 0xFFE53935.toInt()
    )

    fun wallMode(index1: Int) = WALL + index1

    fun wallRes(mode: String): Int? {
        if (!mode.startsWith(WALL)) return null
        val i = mode.removePrefix(WALL).toIntOrNull() ?: return null
        return WALLS.getOrNull(i - 1)
    }

    fun imageFile(c: Context) = File(c.filesDir, "theme_background.jpg")

    fun isPicture(c: Context, mode: String): Boolean =
        (mode == IMAGE && imageFile(c).exists()) || wallRes(mode) != null

    fun isActive(c: Context, p: Prefs): Boolean = isPicture(c, p.str(MODE))

    /** Alpha (0..255) the bars and cards get over the picture; 255 when no picture is used. */
    fun panelAlpha(c: Context, p: Prefs): Int =
        if (!isActive(c, p)) 255 else ((1f - p.int(TRANS).coerceIn(0, 100) / 100f * 0.6f) * 255f).toInt()

    fun accent(@Suppress("UNUSED_PARAMETER") c: Context, p: Prefs): Int? = p.int(ACCENT).takeIf { it != 0 }

    // ------------------------------------------------------------------ bitmaps

    private var cacheKey = ""
    private var cacheBmp: Bitmap? = null

    private fun decodeSource(c: Context, mode: String, reqW: Int): Bitmap? = try {
        val res = wallRes(mode)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (res != null) BitmapFactory.decodeResource(c.resources, res, bounds)
        else BitmapFactory.decodeFile(imageFile(c).absolutePath, bounds)
        var s = 1
        while (bounds.outWidth / (s * 2) >= reqW) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        if (res != null) BitmapFactory.decodeResource(c.resources, res, o)
        else BitmapFactory.decodeFile(imageFile(c).absolutePath, o)
    } catch (_: Throwable) {
        null
    }

    /** Centre-crops the picture to w x h, blurred by [blur] (0..100). */
    private fun cover(c: Context, mode: String, w: Int, h: Int, blur: Int): Bitmap? {
        val f = 1f + blur.coerceIn(0, 100) / 100f * 14f
        val tw = maxOf(8, (w / f).toInt())
        val th = maxOf(8, (h / f).toInt())
        val src = decodeSource(c, mode, tw) ?: return null
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val scale = maxOf(tw / src.width.toFloat(), th / src.height.toFloat())
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate((tw - src.width * scale) / 2f, (th - src.height * scale) / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        if (blur > 0) boxBlur(out, 2)
        return out
    }

    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width
        val h = b.height
        val a = IntArray(w * h)
        val t = IntArray(w * h)
        b.getPixels(a, 0, w, 0, 0, w, h)
        val n = r * 2 + 1
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0
            for (k in -r..r) {
                val p = a[y * w + (x + k).coerceIn(0, w - 1)]
                rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255
            }
            t[y * w + x] = (0xFF shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0
            for (k in -r..r) {
                val p = t[(y + k).coerceIn(0, h - 1) * w + x]
                rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255
            }
            a[y * w + x] = (0xFF shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
        }
        b.setPixels(a, 0, w, 0, 0, w, h)
    }

    /** The background (picture + dark overlay) sized for w x h, or null when no picture is chosen. */
    fun drawable(c: Context, p: Prefs, w: Int, h: Int, cache: Boolean = true): Drawable? {
        val mode = p.str(MODE)
        if (!isPicture(c, mode)) return null
        val blur = p.int(BLUR)
        val key = "$mode|${p.int(VER)}|$blur|$w|$h"
        var bmp = if (cache && key == cacheKey) cacheBmp else null
        if (bmp == null) {
            bmp = cover(c, mode, w, h, blur) ?: return null
            if (cache) { cacheKey = key; cacheBmp = bmp }
        }
        val bd = BitmapDrawable(c.resources, bmp)
        bd.gravity = Gravity.FILL
        bd.isFilterBitmap = true
        val dim = (p.int(DIM).coerceIn(0, 100) / 100f * 0.9f * 255f).toInt()
        return LayerDrawable(arrayOf(bd, ColorDrawable(Color.argb(dim, 0, 0, 0))))
    }

    /** Small square thumbnail of a built-in wallpaper for the picker. */
    fun thumb(c: Context, mode: String): Bitmap? = decodeSource(c, mode, 160)

    // ------------------------------------------------------------------ applying to a screen

    private val hooked = WeakHashMap<RecyclerView, Boolean>()

    /** Puts the picture behind this screen and makes its bars and cards see-through. Safe to call again. */
    fun apply(a: Activity) {
        val p = Prefs(a)
        val dm = a.resources.displayMetrics
        val d = drawable(a, p, dm.widthPixels, dm.heightPixels)
        val on = d != null
        val plain = a.color(R.color.bg)
        a.window.setBackgroundDrawable(d ?: ColorDrawable(plain))
        a.window.statusBarColor = if (on) Color.TRANSPARENT else plain
        a.window.navigationBarColor = if (on) Color.TRANSPARENT else plain
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        root.setBackgroundColor(if (on) Color.TRANSPARENT else plain)
        refresh(root, a, true)
    }

    /** Applies transparency and the accent colour to [v] and everything inside it. */
    fun refresh(v: View, c: Context, isRoot: Boolean = false) {
        if (v is WebView) return
        val p = Prefs(c)
        walk(v, panelAlpha(c, p), accent(c, p), isRoot, c)
    }

    private fun walk(v: View, alpha: Int, acc: Int?, skipSelf: Boolean, c: Context) {
        if (v is WebView) return
        if (!skipSelf) soften(v, alpha)
        if (v is TextView && acc != null) tint(v, acc, c)
        if (v is RecyclerView && !hooked.containsKey(v)) {
            hooked[v] = true
            v.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) { refresh(view, view.context) }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), alpha, acc, false, c)
    }

    private fun soften(v: View, alpha: Int) {
        if (v.tag == NOSOFT) return
        val bg = v.background ?: return
        if (bg is RippleDrawable) return
        if (bg is GradientDrawable || bg is StateListDrawable || bg is ColorDrawable || bg is LayerDrawable) {
            bg.mutate().alpha = alpha
        }
    }

    private fun tint(t: TextView, acc: Int, c: Context) {
        val cur = t.currentTextColor
        if (cur == c.color(R.color.accent) || cur == c.color(R.color.accent_dark) || cur == c.color(R.color.value)) {
            t.setTextColor(acc)
        }
    }
}
