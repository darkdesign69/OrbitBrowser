package com.redowan.orbit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private fun ImageView.setTabIcon(t: Tab) {
    when {
        t.favicon != null -> setImageBitmap(t.favicon)
        t.incognito -> setImageResource(R.drawable.ic_incognito)
        t.home -> setImageResource(R.drawable.ic_home)
        else -> setImageResource(R.drawable.ic_globe)
    }
}

object TabStyle {
    val NAMES = listOf("Red", "Orange", "Purple", "Light green", "Dark green", "Lavender", "Blue", "Gray")
    val COLORS = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF7CB342.toInt(),
        0xFF1E8E3E.toInt(), 0xFFB39DDB.toInt(), 0xFF1E88E5.toInt(), 0xFF9E9E9E.toInt()
    )
    /** Soul 1.4.86 measured on a 411dp screen: 81% x 73% is about 115dp x 22dp, so 100% is 142dp x 30dp. */
    fun widthDp(pct: Int) = pct * 142 / 100
    fun heightDp(pct: Int) = (pct * 30 / 100).coerceIn(20, 64)
    fun widthDp(p: Prefs) = widthDp(p.int(K.TAB_W_PCT))
    fun heightDp(p: Prefs) = heightDp(p.int(K.TAB_H_PCT))

    /**
     * Paints one pill tab (item_tab_strip). The real tab bar and the live preview in
     * Settings > Tab > Detailed settings both call this, so they always look the same.
     */
    fun style(v: View, selected: Boolean, outline: Boolean, color: Int, closeBtn: Boolean, wPct: Int, hPct: Int) {
        val ctx = v.context
        val d = ctx.resources.displayMetrics.density
        val h = heightDp(hPct)
        val hPx = (h * d).toInt()
        v.layoutParams?.let { lp ->
            lp.width = (widthDp(wPct) * d).toInt()
            lp.height = hPx
            v.layoutParams = lp
        }
        v.isSelected = selected
        v.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.pill))
                cornerRadius = hPx / 2f
                if (outline) setStroke((2 * d).toInt(), COLORS[color.coerceIn(0, 7)])
            }
        } else androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_tab_off)
        v.setPadding((((h / 4).coerceIn(5, 10)) * d).toInt(), 0, (2 * d).toInt(), 0)
        v.findViewById<ImageView>(R.id.tabIcon)?.let { ic ->
            val s = ((h * 0.62f).coerceIn(12f, 18f) * d).toInt()
            ic.layoutParams = ic.layoutParams.apply { width = s; height = s }
        }
        v.findViewById<TextView>(R.id.tabTitle)?.textSize = if (h < 26) 11f else 13f
        v.findViewById<ImageButton>(R.id.tabClose)?.let { c ->
            c.visibility = if (closeBtn) View.VISIBLE else View.GONE
            val s = ((h - 2).coerceIn(16, 30) * d).toInt()
            c.layoutParams = c.layoutParams.apply { width = s; height = s }
            val pd = (s * 0.25f).toInt()
            c.setPadding(pd, pd, pd, pd)
        }
    }
}

/** The pill tabs along the top ("tab bar"). */
class TabStripAdapter(private val a: MainActivity) : RecyclerView.Adapter<TabStripAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.tabIcon)
        val title: TextView = v.findViewById(R.id.tabTitle)
        val close: ImageButton = v.findViewById(R.id.tabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab_strip, parent, false)
        return VH(v)
    }

    override fun getItemCount() = a.tabs.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = a.tabs[position]
        val selected = position == a.current
        val pf = Prefs(h.itemView.context)
        TabStyle.style(h.itemView, selected, pf.bool(K.TAB_OUTLINE), pf.int(K.TAB_COLOR),
            pf.bool(K.TAB_CLOSE_BTN), pf.int(K.TAB_W_PCT), pf.int(K.TAB_H_PCT))
        h.title.text = t.label()
        h.title.setTypeface(h.title.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        h.icon.setTabIcon(t)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) a.select(p)
        }
        h.close.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) a.closeTab(p)
        }
    }
}

/** Full-screen tab list: grid / list / small list, normal and incognito. */
class TabSwitcher(private val a: MainActivity) {

    private var showPrivate = false
    private var viewMode = a.prefs.int(K.TAB_VIEW)
    private var appliedMode = -1
    private lateinit var dialog: AppCompatDialog
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var chipNormal: TextView
    private lateinit var chipPrivate: TextView
    private lateinit var btnMode: ImageButton
    private val shown = ArrayList<Tab>()
    private val adapter = Adapter()

    fun show() {
        if (a.current in a.tabs.indices) {
            a.capture(a.cur)
            showPrivate = a.cur.incognito
        }
        dialog = AppCompatDialog(a, R.style.FullDialog)
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_tabs, null)
        list = v.findViewById(R.id.tabList)
        empty = v.findViewById(R.id.tabEmpty)
        chipNormal = v.findViewById(R.id.chipNormal)
        chipPrivate = v.findViewById(R.id.chipPrivate)
        btnMode = v.findViewById(R.id.btnViewMode)
        list.adapter = adapter

        chipNormal.setOnClickListener { showPrivate = false; refresh() }
        chipPrivate.setOnClickListener { showPrivate = true; refresh() }
        btnMode.setOnClickListener {
            viewMode = (viewMode + 1) % 3
            a.prefs.setInt(K.TAB_VIEW, viewMode)
            refresh()
        }
        v.findViewById<View>(R.id.btnTabsMore).setOnClickListener { more() }
        v.findViewById<View>(R.id.btnDone).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.btnAddTab).setOnClickListener {
            a.newTab(null, showPrivate)
            dialog.dismiss()
        }
        dialog.setContentView(v)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        refresh()
    }

    private fun refresh() {
        shown.clear()
        shown.addAll(a.tabs.filter { it.incognito == showPrivate })
        chipNormal.text = "Tabs  ${a.tabs.count { !it.incognito }}"
        chipPrivate.text = "Incognito  ${a.tabs.count { it.incognito }}"
        chipNormal.setBackgroundResource(if (!showPrivate) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        chipPrivate.setBackgroundResource(if (showPrivate) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (appliedMode != viewMode) {
            appliedMode = viewMode
            list.layoutManager = if (viewMode == 0) GridLayoutManager(a, 2) else LinearLayoutManager(a)
        }
        btnMode.setImageResource(
            when (viewMode) { 0 -> R.drawable.ic_grid; else -> R.drawable.ic_list }
        )
        adapter.notifyDataSetChanged()
    }

    private fun more() {
        Ui.listSheet(
            a, null, listOf(
                "Close all ${if (showPrivate) "incognito " else ""}tabs" to {
                    a.closeWhere { it.incognito == showPrivate }
                    dialog.dismiss()
                },
                "Close other tabs" to {
                    val keep = a.tabs.getOrNull(a.current)
                    a.closeWhere { it !== keep }
                    refresh()
                },
                "New ${if (showPrivate) "incognito " else ""}tab" to {
                    a.newTab(null, showPrivate)
                    dialog.dismiss()
                }
            )
        )
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemViewType(position: Int) = if (viewMode == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = viewType == 0
            val v = LayoutInflater.from(parent.context)
                .inflate(if (card) R.layout.item_tab_card else R.layout.item_tab_row, parent, false)
            return VH(v, card)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = shown[position]
            h.itemView.isSelected = t === a.tabs.getOrNull(a.current)
            h.title.text = t.label()
            h.icon.setTabIcon(t)
            if (h.card) {
                h.thumb?.setImageBitmap(t.thumb)
            } else {
                h.url?.text = if (t.home) "Start page" else (t.url ?: "")
                h.url?.visibility = if (viewMode == 2) View.GONE else View.VISIBLE
            }
            h.itemView.setOnClickListener {
                val idx = a.tabs.indexOf(t)
                if (idx >= 0) a.select(idx)
                dialog.dismiss()
            }
            h.close.setOnClickListener {
                val idx = a.tabs.indexOf(t)
                if (idx >= 0) a.closeTab(idx)
                refresh()
            }
        }
    }

    private class VH(v: View, val card: Boolean) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(if (card) R.id.cardIcon else R.id.rowIcon)
        val title: TextView = v.findViewById(if (card) R.id.cardTitle else R.id.rowTitle)
        val close: ImageButton = v.findViewById(if (card) R.id.cardClose else R.id.rowClose)
        val thumb: ImageView? = if (card) v.findViewById(R.id.cardThumb) else null
        val url: TextView? = if (card) null else v.findViewById(R.id.rowUrl)
    }
}
