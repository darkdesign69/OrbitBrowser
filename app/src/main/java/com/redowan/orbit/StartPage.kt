package com.redowan.orbit

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The start page (Soul style): a grid of shortcuts and groups.
 *  - position 0 is a header bar (arrow + name of the open group); tap it to jump between groups
 *  - a group tile shows a 2x2 preview of its first four shortcuts; tapping opens it in place (back goes up)
 *  - long-press and drag to reorder; drop on a group to move the shortcut into it;
 *    long-press without moving opens the menu (edit, move to group, move up, delete)
 */
class DialAdapter(private val a: MainActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: FrameLayout = v.findViewById(R.id.dialTile)
        val icon: ImageView = v.findViewById(R.id.dialIcon)
        val letter: TextView = v.findViewById(R.id.dialLetter)
        val label: TextView = v.findViewById(R.id.dialLabel)
        val mini: GridLayout = v.findViewById(R.id.dialMini)
    }

    class HeadVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.dialHeadTitle)
    }

    private var root: MutableList<Dial> = a.store.dial()
    /** Indices of the open groups, outermost first. Indices (not objects) so a reload cannot leave stale references. */
    private val pathIdx = ArrayList<Int>()

    /** The list that is currently shown. */
    private fun level(): MutableList<Dial> {
        var l = root
        for (i in pathIdx.indices) {
            val kids = l.getOrNull(pathIdx[i])?.children
            if (kids == null) {
                while (pathIdx.size > i) pathIdx.removeAt(pathIdx.size - 1)
                break
            }
            l = kids
        }
        return l
    }

    private fun pathTitles(): List<String> {
        val out = ArrayList<String>()
        var l = root
        for (i in pathIdx) {
            val d = l.getOrNull(i) ?: break
            out.add(d.title)
            l = d.children ?: break
        }
        return out
    }

    fun reload() {
        root = a.store.dial()
        notifyDataSetChanged()
    }

    /** Back button on the start page: leave the open group. Returns true when it did something. */
    fun goUp(): Boolean {
        if (pathIdx.isEmpty()) return false
        pathIdx.removeAt(pathIdx.size - 1)
        notifyDataSetChanged()
        return true
    }

    fun inGroup() = pathIdx.isNotEmpty()

    // ------------------------------------------------------------ adapter basics

    override fun getItemCount() = level().size + 2            // header + shortcuts + "Add"
    override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeadVH(inf.inflate(R.layout.item_dial_header, parent, false))
        else VH(inf.inflate(R.layout.item_dial, parent, false))
    }

    private fun tile(ctx: android.content.Context, d: Dial, h: VH) {
        val radius = ctx.dp(12).toFloat()
        h.icon.imageTintList = null
        h.mini.visibility = View.GONE
        h.mini.removeAllViews()
        if (d.isFolder) {
            h.tile.background = Ui.roundRect(ctx.color(R.color.pill), radius)
            h.letter.visibility = View.GONE
            h.icon.visibility = View.GONE
            h.mini.visibility = View.VISIBLE
            val kids = d.children ?: mutableListOf()
            val cell = ctx.dp(17)
            val gap = ctx.dp(2)
            for (i in 0 until 4) {
                val k = kids.getOrNull(i)
                val lp = GridLayout.LayoutParams().apply {
                    width = cell; height = cell; setMargins(gap, gap, gap, gap)
                }
                val v: View = when {
                    k == null -> View(ctx)
                    k.isFolder -> ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_folder)
                        imageTintList = ColorStateList.valueOf(ctx.color(R.color.accent_dark))
                    }
                    else -> {
                        val fav: Bitmap? = a.store.favicon(hostOf(k.url))
                        if (fav != null) ImageView(ctx).apply {
                            setImageBitmap(fav); scaleType = ImageView.ScaleType.FIT_CENTER
                        } else TextView(ctx).apply {
                            text = Ui.letterOf(k.title, k.url); textSize = 9f
                            setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
                            background = Ui.roundRect(Ui.tileColor(hostOf(k.url).ifEmpty { k.title }), cell / 2f)
                        }
                    }
                }
                h.mini.addView(v, lp)
            }
            return
        }
        val fav = a.store.favicon(hostOf(d.url))
        if (fav != null) {
            h.tile.background = Ui.roundRect(ctx.color(R.color.pill), radius)
            h.letter.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageBitmap(fav)
        } else {
            h.tile.background = Ui.roundRect(Ui.tileColor(hostOf(d.url).ifEmpty { d.title }), ctx.dp(22).toFloat())
            h.icon.visibility = View.GONE
            h.letter.visibility = View.VISIBLE
            h.letter.text = Ui.letterOf(d.title, d.url)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeadVH) {
            val names = pathTitles()
            holder.title.text = names.joinToString("  ›  ")
            holder.itemView.setOnClickListener { groupJump() }
            return
        }
        val h = holder as VH
        val ctx = h.itemView.context
        val items = level()
        val idx = position - 1
        h.icon.imageTintList = null
        h.itemView.setOnLongClickListener(null)
        if (idx == items.size) {
            h.tile.background = Ui.roundRect(ctx.color(R.color.pill), ctx.dp(22).toFloat())
            h.mini.visibility = View.GONE
            h.letter.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(R.drawable.ic_add)
            h.label.text = "Add"
            h.itemView.setOnClickListener { addDialog() }
            return
        }
        val d = items[idx]
        h.label.text = d.title
        tile(ctx, d, h)
        if (d.isFolder) h.itemView.setOnClickListener { open(d) }
        else h.itemView.setOnClickListener { d.url?.let { u -> a.openUrl(u) } }
    }

    // ------------------------------------------------------------ drag and drop

    private var dragged: Dial? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragFar = false
    private var hoverFolder: Dial? = null

    fun attach(rv: RecyclerView) {
        (rv.layoutManager as? GridLayoutManager)?.let { gm ->
            gm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position == 0) gm.spanCount else 1
            }
        }
        val cb = object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = true
            override fun isItemViewSwipeEnabled() = false

            override fun getMovementFlags(r: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                val n = level().size
                if (pos < 1 || pos > n) return 0            // header and "Add" stay put
                return ItemTouchHelper.Callback.makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
                )
            }

            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val items = level()
                val from = vh.bindingAdapterPosition - 1
                val to = target.bindingAdapterPosition - 1
                if (from !in items.indices || to !in items.indices) return false
                // never swap onto a group: dropping there moves the shortcut into it (handled when released)
                if (items[to].isFolder && !items[from].isFolder) return false
                items.add(to, items.removeAt(from))
                notifyItemMoved(from + 1, to + 1)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onChildDraw(
                c: android.graphics.Canvas, r: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive)
                if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return
                if (abs(dX) > r.context.dp(12) || abs(dY) > r.context.dp(12)) dragFar = true
                val d = dragged ?: return
                val cx = vh.itemView.left + dX + vh.itemView.width / 2f
                val cy = vh.itemView.top + dY + vh.itemView.height / 2f
                val under = r.findChildViewUnder(cx, cy)
                val pos = if (under != null) r.getChildAdapterPosition(under) else -1
                val f = level().getOrNull(pos - 1)
                val newHover = if (f != null && f.isFolder && f !== d && !d.isFolder) f else null
                if (newHover !== hoverFolder) {
                    hoverFolder = newHover
                    // highlight the group the shortcut would go into
                    for (i in 0 until r.childCount) {
                        val ch = r.getChildAt(i)
                        if (ch !== vh.itemView) ch.alpha = if (ch === under && newHover != null) 0.55f else 1f
                    }
                }
            }

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    dragged = level().getOrNull(vh.bindingAdapterPosition - 1)
                    dragFar = false
                    hoverFolder = null
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    val d = dragged ?: return
                    val f = hoverFolder
                    dragged = null; hoverFolder = null
                    for (i in 0 until rv.childCount) rv.getChildAt(i).alpha = 1f
                    val items = level()
                    when {
                        f != null && items.contains(d) -> {
                            items.remove(d); f.children?.add(d)
                            a.toast("Moved to ${f.title}")
                            save()
                        }
                        !dragFar -> if (items.contains(d)) { if (d.isFolder) folderMenu(d) else siteMenu(d) }
                        else -> a.store.saveDial(root)       // plain reorder
                    }
                }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                vh.itemView.alpha = 1f
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
    }

    // ------------------------------------------------------------ actions

    private fun save() {
        a.store.saveDial(root)
        notifyDataSetChanged()
    }

    private fun open(f: Dial) {
        val i = level().indexOf(f)
        if (i < 0) return
        pathIdx.add(i)
        notifyDataSetChanged()
    }

    /** Header bar: jump to the start page root or to any group. */
    private fun groupJump() {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries.add("Home" to { pathIdx.clear(); notifyDataSetChanged() })
        fun walk(list: List<Dial>, trail: List<Int>, depth: Int) {
            list.forEachIndexed { i, d ->
                val kids = d.children ?: return@forEachIndexed
                val t = trail + i
                entries.add(("    ".repeat(depth) + d.title) to {
                    pathIdx.clear(); pathIdx.addAll(t); notifyDataSetChanged()
                })
                walk(kids, t, depth + 1)
            }
        }
        walk(root, emptyList(), 0)
        entries.add("New group" to {
            Ui.prompt(a, "New group", "Group name", "Group") { n ->
                if (n.isNotEmpty()) { level().add(Dial(n, null, mutableListOf())); save() }
            }
        })
        Ui.listSheet(a, "Groups", entries)
    }

    private fun addDialog() {
        editDialog("Add shortcut", "", "") { t, u ->
            val url = a.normalizeUrl(u)
            level().add(Dial(t.ifBlank { hostOf(url).removePrefix("www.") }, url))
            save()
        }
    }

    private fun editDialog(title: String, t0: String, u0: String, onOk: (String, String) -> Unit) {
        val ctx = a
        val titleField = EditText(ctx).apply {
            hint = "Title"; setText(t0); setSingleLine()
            setTextColor(ctx.color(R.color.text)); setHintTextColor(ctx.color(R.color.text_sub))
        }
        val urlField = EditText(ctx).apply {
            hint = "Address"; setText(u0); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ctx.color(R.color.text)); setHintTextColor(ctx.color(R.color.text_sub))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), 0)
            addView(titleField)
            addView(urlField)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val u = urlField.text.toString().trim()
                if (u.isNotEmpty()) onOk(titleField.text.toString().trim(), u)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun siteMenu(d: Dial) {
        val items = level()
        val entries = mutableListOf<Pair<String, () -> Unit>>(
            "Open in new tab" to { d.url?.let { a.newTab(it) }; Unit },
            "Edit" to {
                editDialog("Edit shortcut", d.title, d.url ?: "") { t, u ->
                    d.title = t.ifBlank { hostOf(u) }
                    d.url = a.normalizeUrl(u)
                    save()
                }
            },
            "Move to group" to { pickFolder(d) }
        )
        if (pathIdx.isNotEmpty()) entries.add("Move up one level" to {
            // the parent list is the root or the group one level above
            val parent = parentList()
            items.remove(d); parent.add(d); save()
        })
        entries.add("Delete" to { items.remove(d); save() })
        Ui.listSheet(a, d.title, entries)
    }

    private fun folderMenu(f: Dial) {
        val items = level()
        Ui.listSheet(
            a, f.title, listOf(
                "Open" to { open(f) },
                "Rename" to {
                    Ui.prompt(a, "Rename group", "Group name", f.title) { n ->
                        if (n.isNotEmpty()) { f.title = n; save() }
                    }
                },
                "Ungroup" to {
                    val i = items.indexOf(f)
                    if (i >= 0) {
                        items.remove(f)
                        items.addAll(i, f.children ?: mutableListOf())
                        save()
                    }
                },
                "Delete group" to { items.remove(f); save() }
            )
        )
    }

    private fun parentList(): MutableList<Dial> {
        var l = root
        for (n in 0 until pathIdx.size - 1) l = l.getOrNull(pathIdx[n])?.children ?: return root
        return l
    }

    /** Lists every group in the tree (not the item itself) and a "New group" entry. */
    private fun pickFolder(d: Dial) {
        val items = level()
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        fun walk(list: List<Dial>, depth: Int) {
            list.forEach { f ->
                val kids = f.children ?: return@forEach
                if (f === d) return@forEach
                entries.add(("    ".repeat(depth) + f.title) to {
                    items.remove(d); kids.add(d); save()
                })
                walk(kids, depth + 1)
            }
        }
        walk(root, 0)
        entries.add("New group…" to {
            Ui.prompt(a, "New group", "Group name", "Group") { n ->
                if (n.isNotEmpty()) {
                    items.remove(d)
                    items.add(Dial(n, null, mutableListOf(d)))
                    save()
                }
            }
        })
        Ui.listSheet(a, "Move to", entries)
    }
}
