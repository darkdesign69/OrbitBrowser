package com.redowan.orbit

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Customize Theme: background picture, accent colour, transparency, blur and dark overlay.
 * Works like the same screen in the OnlyPlay video player. Changes are saved at once and applied
 * to every Orbit screen (see [ThemeBg]).
 */
class ThemeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var previewHost: FrameLayout
    private lateinit var previewCard: FrameLayout
    private lateinit var previewRows: LinearLayout
    private lateinit var tiles: LinearLayout
    private lateinit var colors: LinearLayout
    private lateinit var header: TextView
    private lateinit var pickBtn: Button
    private val seekBars = HashMap<String, SeekBar>()
    private val valueTexts = HashMap<String, TextView>()

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri)
    }

    private fun accentNow(): Int = ThemeBg.accent(this, prefs) ?: color(R.color.accent_dark)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        setContentView(root)

        // ---- header
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), 0, dp(12), 0)
        val back = ImageButton(this)
        back.setImageResource(R.drawable.ic_back)
        back.setBackgroundColor(Color.TRANSPARENT)
        back.contentDescription = "Back"
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header = TextView(this)
        header.text = "Customize Theme"
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        val hp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hp.marginStart = dp(12)
        bar.addView(header, hp)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        // ---- live preview
        previewHost = FrameLayout(this)
        previewHost.setPadding(0, dp(8), 0, dp(8))
        root.addView(previewHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        previewCard = FrameLayout(this)
        previewCard.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
            }
        }
        previewCard.clipToOutline = true
        previewCard.tag = ThemeBg.NOSOFT
        previewRows = LinearLayout(this)
        previewRows.orientation = LinearLayout.VERTICAL
        previewRows.setPadding(dp(12), dp(12), dp(12), dp(12))
        previewCard.addView(previewRows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val cp = FrameLayout.LayoutParams(dp(150), dp(300))
        cp.gravity = Gravity.CENTER
        previewHost.addView(previewCard, cp)
        previewHost.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val h = (b - t) - dp(16)
            val w = h / 2
            val lp = previewCard.layoutParams
            if (h > 0 && (lp.height != h || lp.width != w)) {
                lp.height = h
                lp.width = w
                previewHost.post { previewCard.layoutParams = lp; refreshPreview() }
            }
        }

        // ---- controls sheet
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(dp(16), dp(16), dp(16), dp(16))
        val sheetBg = GradientDrawable()
        sheetBg.setColor(color(R.color.card))
        val r = dp(28).toFloat()
        sheetBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = sheetBg
        root.addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tileScroll = HorizontalScrollView(this)
        tileScroll.isHorizontalScrollBarEnabled = false
        tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        tileScroll.addView(tiles)
        sheet.addView(tileScroll)

        val colorLine = LinearLayout(this)
        colorLine.orientation = LinearLayout.HORIZONTAL
        colorLine.gravity = Gravity.CENTER_VERTICAL
        val colorLabel = TextView(this)
        colorLabel.text = "Color"
        colorLabel.setTextColor(color(R.color.text))
        colorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        colorLine.addView(colorLabel)
        val colorScroll = HorizontalScrollView(this)
        colorScroll.isHorizontalScrollBarEnabled = false
        colors = LinearLayout(this)
        colors.orientation = LinearLayout.HORIZONTAL
        colorScroll.addView(colors)
        val cl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cl.marginStart = dp(14)
        colorLine.addView(colorScroll, cl)
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.topMargin = dp(10)
        sheet.addView(colorLine, clp)

        sheet.addView(sliderBlock("Transparency", ThemeBg.TRANS))
        sheet.addView(sliderBlock("Blur", ThemeBg.BLUR))
        sheet.addView(sliderBlock("Dark overlay", ThemeBg.DIM))

        pickBtn = Button(this)
        pickBtn.isAllCaps = false
        pickBtn.setTextColor(Color.WHITE)
        pickBtn.setOnClickListener { pick.launch("image/*") }
        val bp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        bp.topMargin = dp(12)
        sheet.addView(pickBtn, bp)

        refreshAll()
    }

    // ------------------------------------------------------------------ pieces

    private fun sliderBlock(label: String, key: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val name = TextView(this)
        name.text = label
        name.setTextColor(color(R.color.text))
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stepper("\u2212", key, -5))
        val value = TextView(this)
        value.gravity = Gravity.CENTER
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        valueTexts[key] = value
        row.addView(value, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(stepper("+", key, 5))
        box.addView(row)

        val sb = SeekBar(this)
        sb.max = 100
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.setInt(key, p)
                valueTexts[key]?.text = "$p%"
                refreshPreview()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { ThemeBg.apply(this@ThemeActivity) }
        })
        seekBars[key] = sb
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        box.layoutParams = lp
        return box
    }

    private fun stepper(text: String, key: String, delta: Int): View {
        val t = TextView(this)
        t.text = text
        t.gravity = Gravity.CENTER
        t.setTextColor(color(R.color.text))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        t.layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        t.setOnClickListener {
            val v = (prefs.int(key) + delta).coerceIn(0, 100)
            prefs.setInt(key, v)
            seekBars[key]?.progress = v
            valueTexts[key]?.text = "$v%"
            refreshPreview()
            ThemeBg.apply(this)
        }
        return t
    }

    private fun tile(selected: Boolean, content: View, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(color(R.color.pill))
        bg.setStroke(if (selected) dp(2) else dp(1), if (selected) accentNow() else color(R.color.outline))
        f.background = bg
        f.outlineProvider = ViewOutlineProvider.BACKGROUND
        f.clipToOutline = true
        f.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (selected) {
            val badge = TextView(this)
            badge.text = "\u2713"
            badge.gravity = Gravity.CENTER
            badge.setTextColor(Color.WHITE)
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bb = GradientDrawable()
            bb.shape = GradientDrawable.OVAL
            bb.setColor(accentNow())
            badge.background = bb
            badge.tag = ThemeBg.NOSOFT
            val bl = FrameLayout.LayoutParams(dp(18), dp(18))
            bl.gravity = Gravity.BOTTOM or Gravity.END
            bl.setMargins(0, 0, dp(3), dp(3))
            f.addView(badge, bl)
        }
        f.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(dp(56), dp(56))
        lp.marginEnd = dp(10)
        f.layoutParams = lp
        return f
    }

    private fun iconView(res: Int): View {
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.alpha = 0.8f
        return iv
    }

    private fun imageView(bmp: android.graphics.Bitmap?): View {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        if (bmp != null) iv.setImageBitmap(bmp)
        return iv
    }

    // ------------------------------------------------------------------ state -> views

    private fun refreshAll() {
        val acc = accentNow()
        header.setTextColor(acc)
        val pb = GradientDrawable()
        pb.cornerRadius = dp(26).toFloat()
        pb.setColor(acc)
        pickBtn.background = pb
        pickBtn.text = if (ThemeBg.imageFile(this).exists()) "Change picture" else "Select picture"

        val mode = prefs.str(ThemeBg.MODE)
        tiles.removeAllViews()
        tiles.addView(tile(mode == ThemeBg.NONE, iconView(R.drawable.ic_close)) {
            prefs.setStr(ThemeBg.MODE, ThemeBg.NONE); refreshAll()
        })
        val hasOwn = ThemeBg.imageFile(this).exists()
        val ownContent = if (hasOwn) imageView(ThemeBg.thumb(this, ThemeBg.IMAGE)) else iconView(R.drawable.ic_add)
        tiles.addView(tile(mode == ThemeBg.IMAGE, ownContent) {
            if (hasOwn) { prefs.setStr(ThemeBg.MODE, ThemeBg.IMAGE); refreshAll() } else pick.launch("image/*")
        })
        for (i in 1..ThemeBg.WALLS.size) {
            val m = ThemeBg.wallMode(i)
            tiles.addView(tile(mode == m, imageView(ThemeBg.thumb(this, m))) {
                prefs.setStr(ThemeBg.MODE, m); refreshAll()
            })
        }

        colors.removeAllViews()
        val cur = prefs.int(ThemeBg.ACCENT)
        colors.addView(dot(0, cur == 0))
        for (c in ThemeBg.ACCENTS) colors.addView(dot(c, cur == c))

        for ((key, sb) in seekBars) {
            val v = prefs.int(key)
            sb.progress = v
            sb.progressTintList = ColorStateList.valueOf(acc)
            sb.thumbTintList = ColorStateList.valueOf(acc)
            valueTexts[key]?.text = "$v%"
            valueTexts[key]?.setTextColor(acc)
        }
        refreshPreview()
        ThemeBg.apply(this)
    }

    private fun dot(c: Int, selected: Boolean): View {
        val v = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (c == 0) color(R.color.pill) else c)
        bg.setStroke(if (selected) dp(3) else dp(1), if (selected) Color.WHITE else color(R.color.outline))
        v.background = bg
        v.tag = ThemeBg.NOSOFT
        if (c == 0) {
            val x = ImageView(this)
            x.setImageResource(R.drawable.ic_close)
            x.scaleType = ImageView.ScaleType.CENTER
            x.alpha = 0.7f
            v.addView(x, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        v.setOnClickListener { prefs.setInt(ThemeBg.ACCENT, c); refreshAll() }
        val lp = LinearLayout.LayoutParams(dp(38), dp(38))
        lp.marginEnd = dp(10)
        v.layoutParams = lp
        return v
    }

    private fun refreshPreview() {
        val w = previewCard.layoutParams.width
        val h = previewCard.layoutParams.height
        if (w <= 0 || h <= 0) return
        val d = ThemeBg.drawable(this, prefs, w, h, false)
        previewCard.background = d ?: android.graphics.drawable.ColorDrawable(color(R.color.bg))
        previewRows.removeAllViews()
        val title = TextView(this)
        title.text = "Orbit"
        title.setTextColor(Color.WHITE.takeIf { d != null } ?: color(R.color.text))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.setPadding(dp(4), 0, 0, dp(8))
        previewRows.addView(title)
        val alpha = ThemeBg.panelAlpha(this, prefs)
        for (i in 1..5) {
            val t = TextView(this)
            t.text = "Sample page $i"
            t.gravity = Gravity.CENTER_VERTICAL
            t.setTextColor(color(R.color.text))
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            t.setPadding(dp(12), 0, dp(12), 0)
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            bg.setColor(color(R.color.card))
            bg.alpha = alpha
            t.background = bg
            t.tag = ThemeBg.NOSOFT
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            lp.bottomMargin = dp(8)
            previewRows.addView(t, lp)
        }
    }

    // ------------------------------------------------------------------ own picture

    private fun importImage(uri: Uri) {
        Thread {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var s = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 2400) s *= 2
                val o = BitmapFactory.Options().apply { inSampleSize = s }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (bmp == null) false else {
                    ThemeBg.imageFile(this).outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }
                    bmp.recycle()
                    true
                }
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (ok) {
                    prefs.setInt(ThemeBg.VER, prefs.int(ThemeBg.VER) + 1)
                    prefs.setStr(ThemeBg.MODE, ThemeBg.IMAGE)
                    refreshAll()
                } else toast("Couldn't load that picture")
            }
        }.start()
    }
}
