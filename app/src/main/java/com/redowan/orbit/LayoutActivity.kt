package com.redowan.orbit

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Settings > Layout. Arrange what sits on the top bar and the bottom bar, and set the sizes of the bars, tabs and icons.
 * Everything here is read by MainActivity.rebuildBars().
 */
class LayoutActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var top = mutableListOf<String>()
    private var bottom = mutableListOf<String>()
    private var dirty = false
    private lateinit var topAdapter: Zone
    private lateinit var botAdapter: Zone
    private lateinit var topRv: RecyclerView
    private lateinit var botRv: RecyclerView
    private lateinit var botEmpty: TextView
    private lateinit var tabToggle: ImageButton
    private var ripple = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        top = Bars.parse(prefs.str(K.LAY_TOP))
        bottom = Bars.parse(prefs.str(K.LAY_BOTTOM))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        ripple = tv.resourceId

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        setContentView(root)

        // header: back | title | position | reset | apply
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
        }
        header.addView(iconBtn("outline_keyboard_arrow_left", "Back") { onBack() })
        header.addView(TextView(this).apply {
            text = "Layout"; textSize = 20f; setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        })
        header.addView(iconBtn("outline_space_dashboard", "Bars position") { positionSheet() })
        header.addView(iconBtn("outline_autorenew", "Reset") { resetAll() })
        header.addView(iconBtn("outline_check", "Apply") { save() })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        // top zone
        topRv = zoneList(false)
        topAdapter = Zone(top, false)
        topRv.adapter = topAdapter
        attachDrag(topRv, topAdapter)
        root.addView(card(topRv), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // controls under the top zone
        val topControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), 0) }
        topControls.addView(iconBtn("outline_settings", "Sizes") { sizesDialog() })
        topControls.addView(iconBtn("outline_delete", "Delete all") { clearZone(top, topAdapter) })
        topControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabToggle = iconBtn("outline_keyboard_arrow_down", "Tab bar") {
            prefs.setBool(K.TAB_BAR, !prefs.bool(K.TAB_BAR)); refreshToggle()
        }
        tabToggle.background = Ui.roundRect(color(R.color.pill), dp(30).toFloat())
        topControls.addView(tabToggle, LinearLayout.LayoutParams(dp(60), dp(60)))
        topControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topControls.addView(iconBtn("outline_border_clear", "Add space") { add("space", top, topAdapter) })
        topControls.addView(iconBtn("outline_add", "Add item") { pickItem(top, topAdapter) })
        root.addView(topControls)

        // page preview area
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom zone
        botRv = zoneList(true)
        botAdapter = Zone(bottom, true)
        botRv.adapter = botAdapter
        attachDrag(botRv, botAdapter)
        botEmpty = TextView(this).apply {
            text = "Not used"; textSize = 20f; setTextColor(color(R.color.text)); gravity = Gravity.CENTER
        }
        val botCard = FrameLayout(this).apply {
            background = Ui.roundRect(color(R.color.card), dp(26).toFloat())
            addView(botRv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(botEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        }
        root.addView(botCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val botControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(dp(12), dp(10), dp(12), dp(22)) }
        botControls.addView(iconBtn("outline_delete", "Delete all") { clearZone(bottom, botAdapter) })
        botControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        botControls.addView(iconBtn("outline_border_clear", "Add space") { add("space", bottom, botAdapter) })
        botControls.addView(iconBtn("outline_add", "Add item") { pickItem(bottom, botAdapter) })
        root.addView(botControls)

        refreshToggle()
        refreshEmpty()
    }

    // ------------------------------------------------------------------ helpers

    private fun iconBtn(name: String, desc: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(Bars.icon(this@LayoutActivity, name))
        contentDescription = desc
        setBackgroundResource(ripple)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { click() }
    }

    private fun zoneList(bottomZone: Boolean) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@LayoutActivity, LinearLayoutManager.HORIZONTAL, false)
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, dp(if (bottomZone) 14 else 12), 0, dp(if (bottomZone) 14 else 12))
        clipToPadding = false
    }

    private fun card(v: View) = FrameLayout(this).apply {
        background = Ui.roundRect(color(R.color.card), dp(26).toFloat())
        addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshToggle() {
        val on = prefs.bool(K.TAB_BAR)
        tabToggle.setImageResource(Bars.icon(this, if (on) "outline_keyboard_arrow_down" else "outline_keyboard_arrow_up"))
        tabToggle.contentDescription = if (on) "Tab bar shown" else "Tab bar hidden"
    }

    private fun refreshEmpty() { botEmpty.visibility = if (bottom.isEmpty()) View.VISIBLE else View.GONE }

    private fun markDirty() { dirty = true }

    private fun used() = (top + bottom).toSet()

    private fun available(): List<Bars.Item> = Bars.ALL.filter { it.id == "space" || it.id !in used() }

    private fun add(id: String, list: MutableList<String>, ad: Zone) {
        list.add(id); markDirty(); ad.notifyDataSetChanged(); refreshEmpty()
    }

    private fun pickItem(list: MutableList<String>, ad: Zone) {
        val opts = available()
        if (opts.isEmpty()) { toast("Not enough space."); return }
        Ui.listSheet(this, "Add", opts.map { it.label to { add(it.id, list, ad) } })
    }

    private fun clearZone(list: MutableList<String>, ad: Zone) {
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete all")
            .setMessage("Are you sure you want to delete all?")
            .setPositiveButton("Delete all") { _, _ -> list.clear(); markDirty(); ad.notifyDataSetChanged(); refreshEmpty() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun itemSheet(list: MutableList<String>, ad: Zone, pos: Int) {
        val it0 = Bars.item(list[pos]) ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Change" to {
            val opts = available().filter { o -> o.id != it0.id }
            if (opts.isEmpty()) toast("Not enough space.")
            else Ui.listSheet(this, "Change", opts.map { o -> o.label to { list[pos] = o.id; markDirty(); ad.notifyDataSetChanged() } })
        })
        if (pos > 0) acts.add("Up" to { list.add(pos - 1, list.removeAt(pos)); markDirty(); ad.notifyDataSetChanged() })
        if (pos < list.size - 1) acts.add("Down" to { list.add(pos + 1, list.removeAt(pos)); markDirty(); ad.notifyDataSetChanged() })
        acts.add("Delete" to { list.removeAt(pos); markDirty(); ad.notifyDataSetChanged(); refreshEmpty() })
        Ui.listSheet(this, it0.label, acts)
    }

    private fun attachDrag(rv: RecyclerView, ad: Zone) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val a = v.bindingAdapterPosition; val b = t.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                ad.list.add(b, ad.list.removeAt(a)); ad.notifyItemMoved(a, b); markDirty()
                return true
            }
            override fun onSwiped(v: RecyclerView.ViewHolder, d: Int) {}
        }).attachToRecyclerView(rv)
    }

    // ------------------------------------------------------------------ actions

    private fun positionSheet() {
        Ui.listSheet(this, "Bars position", listOf(
            "Address bar at top" to { prefs.setBool(K.ADDR_BOTTOM, false); toast("Address bar at top") },
            "Address bar at bottom" to { prefs.setBool(K.ADDR_BOTTOM, true); toast("Address bar at bottom") }
        ))
    }

    private fun sizesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun slider(title: String, key: String, min: Int, max: Int) {
            val label = TextView(this).apply { setTextColor(color(R.color.text)); textSize = 15f; setPadding(0, dp(12), 0, 0) }
            val bar = SeekBar(this).apply {
                this.max = max - min
                progress = prefs.int(key).coerceIn(min, max) - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        prefs.setInt(key, p + min); label.text = "$title: ${p + min}"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            label.text = "$title: ${prefs.int(key)}"
            box.addView(label); box.addView(bar)
        }
        slider("Top bar height", K.LAY_TOP_H, 40, 80)
        slider("Icon size", K.LAY_ICON, 32, 56)
        slider("Tab bar height", K.LAY_TAB_H, 34, 64)
        slider("Tab width", K.LAY_TAB_W, 100, 240)
        slider("Bottom bar height", K.LAY_BOT_H, 40, 72)
        slider("Home screen columns", K.LAY_COLS, 3, 6)
        android.widget.ScrollView(this).also { it.addView(box) }.let { sv ->
            AlertDialog.Builder(this).setTitle("Size").setView(sv)
                .setPositiveButton("OK") { _, _ -> topAdapter.notifyDataSetChanged(); botAdapter.notifyDataSetChanged() }
                .setNeutralButton("Reset") { _, _ ->
                    prefs.sp.edit().remove(K.LAY_TOP_H).remove(K.LAY_ICON).remove(K.LAY_TAB_H).remove(K.LAY_TAB_W)
                        .remove(K.LAY_BOT_H).remove(K.LAY_COLS).apply()
                    topAdapter.notifyDataSetChanged(); botAdapter.notifyDataSetChanged()
                }
                .show()
        }
    }

    private fun resetAll() {
        AlertDialog.Builder(this).setTitle("Reset").setMessage("Do you want to reset settings?")
            .setPositiveButton("Reset") { _, _ ->
                top.clear(); top.addAll(Bars.parse(Bars.DEFAULT_TOP)); bottom.clear()
                prefs.sp.edit().remove(K.LAY_TOP_H).remove(K.LAY_ICON).remove(K.LAY_TAB_H).remove(K.LAY_TAB_W)
                    .remove(K.LAY_BOT_H).remove(K.LAY_COLS).apply()
                markDirty(); topAdapter.notifyDataSetChanged(); botAdapter.notifyDataSetChanged(); refreshEmpty()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun save() {
        prefs.setStr(K.LAY_TOP, Bars.join(top))
        prefs.setStr(K.LAY_BOTTOM, Bars.join(bottom))
        finish()
    }

    private fun onBack() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setMessage("Exit without saving")
            .setPositiveButton("Save and exit") { _, _ -> save() }
            .setNegativeButton("Exit without saving") { _, _ -> finish() }
            .setNeutralButton("Cancel", null).show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { onBack() }

    // ------------------------------------------------------------------ zone adapter

    inner class Zone(val list: MutableList<String>, private val bottomZone: Boolean) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val col = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val col = h.itemView as LinearLayout
            col.removeAllViews()
            val id = list[position]
            val item = Bars.item(id) ?: return
            val total = (if (bottomZone) botRv else topRv).width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val others = list.count { it != "address" }
            val fixed = if (bottomZone) total / list.size.coerceAtLeast(1) else dp(prefs.int(K.LAY_ICON) + 16)
            val w = if (id == "address") (total - others * fixed).coerceAtLeast(dp(120)) else fixed
            col.layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            val box = dp(prefs.int(K.LAY_ICON))
            when (id) {
                "address" -> col.addView(View(col.context).apply {
                    background = Ui.roundRect(color(R.color.pill), dp(14).toFloat())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp((prefs.int(K.LAY_TOP_H) - 12).coerceAtLeast(30))).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                })
                "tabs" -> col.addView(FrameLayout(col.context).apply {
                    addView(TextView(col.context).apply {
                        text = "1"; gravity = Gravity.CENTER; textSize = 12f
                        setTextColor(color(R.color.icon)); setBackgroundResource(R.drawable.bg_tabcount)
                    }, FrameLayout.LayoutParams(dp(28), dp(26), Gravity.CENTER))
                }, LinearLayout.LayoutParams(box, box))
                else -> col.addView(ImageView(col.context).apply {
                    setImageResource(Bars.icon(col.context, item.icon))
                }, LinearLayout.LayoutParams(box, box))
            }
            col.addView(TextView(col.context).apply {
                text = item.label; textSize = 13f; setTextColor(color(R.color.text)); gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) itemSheet(list, this, p)
            }
        }
    }
}
