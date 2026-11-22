package com.redowan.orbit

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun hostOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
}

fun View.hideKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .hideSoftInputFromWindow(windowToken, 0)
}

fun View.showKeyboard() {
    requestFocus()
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

object Ui {

    private val TILE_COLORS = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(),
        0xFF43A047.toInt(), 0xFFD81B60.toInt(), 0xFF3949AB.toInt(), 0xFF00897B.toInt(),
        0xFFF4511E.toInt(), 0xFF6D4C41.toInt()
    )

    fun tileColor(seed: String): Int =
        TILE_COLORS[(seed.lowercase().hashCode() and 0x7fffffff) % TILE_COLORS.size]

    fun roundRect(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    fun letterOf(title: String, url: String?): String {
        val src = title.ifBlank { hostOf(url).removePrefix("www.") }
        return src.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    }

    /** Bottom sheet that slides up, like the reference app's dialogs. */
    fun sheet(a: Activity, content: View): AppCompatDialog {
        val d = AppCompatDialog(a, R.style.SheetDialog)
        content.setBackgroundResource(R.drawable.bg_sheet)
        d.setContentView(content)
        d.window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setGravity(Gravity.BOTTOM)
        }
        d.show()
        return d
    }

    /** Plain list sheet: optional title and tappable rows. */
    fun listSheet(
        a: Activity, title: String?, items: List<Pair<String, () -> Unit>>,
        longPress: ((Int) -> Unit)? = null
    ): AppCompatDialog {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, a.dp(8), 0, a.dp(16))
        }
        if (title != null) {
            box.addView(TextView(a).apply {
                text = title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(a.color(R.color.text_sub))
                textSize = 13f
                setPadding(a.dp(22), a.dp(14), a.dp(22), a.dp(8))
            })
        }
        val holder = arrayOfNulls<AppCompatDialog>(1)
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, (label, action) ->
            rows.addView(TextView(a).apply {
                text = label
                textSize = 16f
                setTextColor(a.color(R.color.text))
                gravity = Gravity.CENTER_VERTICAL
                minHeight = a.dp(52)
                setPadding(a.dp(22), 0, a.dp(22), 0)
                val tv = android.util.TypedValue()
                a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { holder[0]?.dismiss(); action() }
                if (longPress != null) setOnLongClickListener { holder[0]?.dismiss(); longPress(index); true }
            })
        }
        val scroll = ScrollView(a)
        scroll.addView(rows)
        val maxH = (a.resources.displayMetrics.heightPixels * 0.6f).toInt()
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxH) {
                scroll.layoutParams = scroll.layoutParams.also { it.height = maxH }
            }
        }
        val d = sheet(a, box)
        holder[0] = d
        return d
    }

    /** Small text-entry dialog. */
    fun prompt(
        a: Activity, title: String, hint: String, initial: String = "", multiline: Boolean = false,
        onOk: (String) -> Unit
    ) {
        val input = EditText(a).apply {
            this.hint = hint
            setText(initial)
            setTextColor(a.color(R.color.text))
            setHintTextColor(a.color(R.color.text_sub))
            if (multiline) {
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            } else setSingleLine()
            setSelection(text.length)
        }
        val wrap = LinearLayout(a).apply {
            setPadding(a.dp(22), a.dp(8), a.dp(22), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        androidx.appcompat.app.AlertDialog.Builder(a)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun parseColorSafe(s: String, def: Int = Color.GRAY): Int = try { Color.parseColor(s) } catch (_: Exception) { def }
}
