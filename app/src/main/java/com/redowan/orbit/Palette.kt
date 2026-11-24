package com.redowan.orbit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.content.Context

/** Colour grid for Display > Header color and the tab outline "more" dot: 15 hues x 4 shades (Material 600-900). */
object Palette {
    val ROWS = arrayOf(
        intArrayOf(0xE53935, 0xD32F2F, 0xC62828, 0xB71C1C), intArrayOf(0xD81B60, 0xC2185B, 0xAD1457, 0x880E4F),
        intArrayOf(0x8E24AA, 0x7B1FA2, 0x6A1B9A, 0x4A148C), intArrayOf(0x5E35B1, 0x512DA8, 0x4527A0, 0x311B92),
        intArrayOf(0x3949AB, 0x303F9F, 0x283593, 0x1A237E), intArrayOf(0x1E88E5, 0x1976D2, 0x1565C0, 0x0D47A1),
        intArrayOf(0x039BE5, 0x0288D1, 0x0277BD, 0x01579B), intArrayOf(0x00ACC1, 0x0097A7, 0x00838F, 0x006064),
        intArrayOf(0x00897B, 0x00796B, 0x00695C, 0x004D40), intArrayOf(0x43A047, 0x388E3C, 0x2E7D32, 0x1B5E20),
        intArrayOf(0x7CB342, 0x689F38, 0x558B2F, 0x33691E), intArrayOf(0xC0CA33, 0xAFB42B, 0x9E9D24, 0x827717),
        intArrayOf(0x6D4C41, 0x5D4037, 0x4E342E, 0x3E2723), intArrayOf(0x546E7A, 0x455A64, 0x37474F, 0x263238),
        intArrayOf(0x616161, 0x424242, 0x212121, 0x000000)
    )

    /** Shows the grid; [onPick] gets an opaque ARGB colour when the user taps Apply. */
    fun pick(ctx: Context, current: Int, onPick: (Int) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        var chosen = current or 0xFF000000.toInt()
        val cells = ArrayList<Pair<TextView, Int>>()
        fun mark() = cells.forEach { (t, c) -> t.text = if (c == chosen) "\u2713" else "" }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (row in ROWS) {
            val r = LinearLayout(ctx)
            for (rgb in row) {
                val c = rgb or 0xFF000000.toInt()
                val t = TextView(ctx).apply {
                    gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.WHITE)
                    setBackgroundColor(c)
                    setOnClickListener { chosen = c; mark() }
                }
                cells.add(t to c)
                r.addView(t, LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f))
            }
            col.addView(r)
        }
        mark()
        val sv = ScrollView(ctx).apply { addView(col); setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), 0) }
        AlertDialog.Builder(ctx).setView(sv)
            .setPositiveButton("Apply") { _, _ -> onPick(chosen) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Round colour dot used by the settings rows. */
    fun dot(c: Int, strokeDp: Int, strokeColor: Int, d: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(c); if (strokeDp > 0) setStroke((strokeDp * d).toInt(), strokeColor)
    }
}
