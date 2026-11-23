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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Settings > Menu > Edit menu. Quick buttons, menu list and bottom buttons of the three-dot popup menu. */
class MenuEditorActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val quick = ArrayList<String>()
    private val list = ArrayList<String>()
    private val bottom = ArrayList<String>()
    private var dirty = false
    private var ripple = 0
    private lateinit var qa: Sec
    private lateinit var la: Sec
    private lateinit var ba: Sec

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        quick.addAll(MenuCat.parse(prefs.str(K.MENU_QUICK)))
        list.addAll(MenuCat.parse(prefs.str(K.MENU_LIST)))
        bottom.addAll(MenuCat.parse(prefs.str(K.MENU_BOTTOM)))
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        ripple = tv.resourceId

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.bg)) }
        setContentView(root)
        ThemeBg.apply(this)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(12), 0) }
        header.addView(iconBtn("outline_keyboard_arrow_left", "Back") { onBack() })
        header.addView(TextView(this).apply {
            text = "Menu"; textSize = 20f; setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        })
        header.addView(iconBtn("outline_autorenew", "Reset") { resetAll() })
        header.addView(iconBtn("outline_check", "Apply") { save() })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        qa = Sec(quick, true, 5)
        la = Sec(list, false, 40)
        ba = Sec(bottom, true, 4)
        section(body, "Quick buttons", qa)
        section(body, "Menu items", la)
        section(body, "Bottom buttons", ba)
    }

    private fun section(body: LinearLayout, title: String, sec: Sec) {
        body.addView(TextView(this).apply {
            text = title; textSize = 14f; setTextColor(color(R.color.text_sub))
            setPadding(dp(20), dp(18), dp(20), dp(6))
        })
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MenuEditorActivity,
                if (sec.horizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL, false)
            adapter = sec
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(6), 0, dp(6))
        }
        sec.rv = rv
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            if (sec.horizontal) ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT else ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val a = v.bindingAdapterPosition; val b = t.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                sec.list.add(b, sec.list.removeAt(a)); sec.notifyItemMoved(a, b); dirty = true
                return true
            }
            override fun onSwiped(v: RecyclerView.ViewHolder, d: Int) {}
        }).attachToRecyclerView(rv)
        body.addView(FrameLayout(this).apply {
            background = Ui.roundRect(color(R.color.card), dp(26).toFloat())
            addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(dp(12), dp(4), dp(12), 0) }
        controls.addView(iconBtn("outline_delete", "Delete all") { clear(sec) })
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(iconBtn("outline_add", "Add item") { pick(sec) })
        body.addView(controls)
    }

    private fun iconBtn(name: String, desc: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(Bars.icon(this@MenuEditorActivity, name))
        contentDescription = desc
        setBackgroundResource(ripple)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { click() }
    }

    private fun used(): Set<String> = (quick + list + bottom).toSet()

    private fun available(sec: Sec): List<Bars.Item> =
        MenuCat.ALL.filter { (it.id == "sep" && !sec.horizontal) || (it.id != "sep" && it.id !in used()) }

    private fun pick(sec: Sec) {
        if (sec.list.size >= sec.max) { toast("Not enough space."); return }
        val opts = available(sec)
        if (opts.isEmpty()) { toast("Nothing left to add."); return }
        Ui.listSheet(this, "Add", opts.map { o -> o.label to { sec.list.add(o.id); dirty = true; sec.notifyDataSetChanged() } })
    }

    private fun clear(sec: Sec) {
        if (sec.list.isEmpty()) return
        AlertDialog.Builder(this).setMessage("Are you sure you want to delete all?")
            .setPositiveButton("Delete all") { _, _ -> sec.list.clear(); dirty = true; sec.notifyDataSetChanged() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun itemSheet(sec: Sec, pos: Int) {
        val it0 = MenuCat.item(sec.list[pos]) ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (it0.id != "sep") acts.add("Change" to {
            val opts = available(sec).filter { o -> o.id != "sep" && o.id != it0.id }
            if (opts.isEmpty()) toast("Nothing left to add.")
            else Ui.listSheet(this, "Change", opts.map { o -> o.label to { sec.list[pos] = o.id; dirty = true; sec.notifyDataSetChanged() } })
        })
        if (pos > 0) acts.add((if (sec.horizontal) "Left" else "Up") to { sec.list.add(pos - 1, sec.list.removeAt(pos)); dirty = true; sec.notifyDataSetChanged() })
        if (pos < sec.list.size - 1) acts.add((if (sec.horizontal) "Right" else "Down") to { sec.list.add(pos + 1, sec.list.removeAt(pos)); dirty = true; sec.notifyDataSetChanged() })
        acts.add("Delete" to { sec.list.removeAt(pos); dirty = true; sec.notifyDataSetChanged() })
        Ui.listSheet(this, it0.label, acts)
    }

    private fun resetAll() {
        AlertDialog.Builder(this).setMessage("Do you want to reset settings?")
            .setPositiveButton("Reset") { _, _ ->
                quick.clear(); quick.addAll(MenuCat.parse(MenuCat.DEFAULT_QUICK))
                list.clear(); list.addAll(MenuCat.parse(MenuCat.DEFAULT_LIST))
                bottom.clear(); bottom.addAll(MenuCat.parse(MenuCat.DEFAULT_BOTTOM))
                dirty = true; qa.notifyDataSetChanged(); la.notifyDataSetChanged(); ba.notifyDataSetChanged()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun save() {
        prefs.setStr(K.MENU_QUICK, quick.joinToString(","))
        prefs.setStr(K.MENU_LIST, list.joinToString(","))
        prefs.setStr(K.MENU_BOTTOM, bottom.joinToString(","))
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

    inner class Sec(val list: MutableList<String>, val horizontal: Boolean, val max: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        lateinit var rv: RecyclerView
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LinearLayout(parent.context)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val box = h.itemView as LinearLayout
            box.removeAllViews()
            val item = MenuCat.item(list[position]) ?: return
            val total = rv.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            if (horizontal) {
                box.orientation = LinearLayout.VERTICAL
                box.gravity = Gravity.CENTER_HORIZONTAL
                box.layoutParams = RecyclerView.LayoutParams(total / max.coerceAtLeast(1), ViewGroup.LayoutParams.WRAP_CONTENT)
                box.addView(ImageView(box.context).apply { setImageResource(iconOf(item)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
                box.addView(TextView(box.context).apply {
                    text = item.label; textSize = 12f; setTextColor(color(R.color.text)); gravity = Gravity.CENTER; maxLines = 2
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                box.orientation = LinearLayout.HORIZONTAL
                box.gravity = Gravity.CENTER_VERTICAL
                box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (item.id == "sep") 34 else 52))
                box.setPadding(dp(20), 0, dp(20), 0)
                if (item.id == "sep") {
                    box.addView(View(box.context).apply { setBackgroundColor(color(R.color.divider)) },
                        LinearLayout.LayoutParams(0, dp(2), 1f))
                } else {
                    box.addView(ImageView(box.context).apply { setImageResource(iconOf(item)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                    box.addView(TextView(box.context).apply {
                        text = item.label; textSize = 16f; setTextColor(color(R.color.text))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
                }
            }
            box.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) itemSheet(this, p)
            }
        }

        private fun iconOf(i: Bars.Item): Int = resources.getIdentifier(i.icon, "drawable", packageName)
    }
}
