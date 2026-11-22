package com.redowan.orbit

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** The start page: a grid of shortcut icons with folders ("speed dial"). */
class DialAdapter(private val a: MainActivity) : RecyclerView.Adapter<DialAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: FrameLayout = v.findViewById(R.id.dialTile)
        val icon: ImageView = v.findViewById(R.id.dialIcon)
        val letter: TextView = v.findViewById(R.id.dialLetter)
        val label: TextView = v.findViewById(R.id.dialLabel)
    }

    private var items: MutableList<Dial> = a.store.dial()

    fun reload() {
        items = a.store.dial()
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dial, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.itemView.context
        val radius = ctx.dp(18).toFloat()
        h.icon.imageTintList = null
        if (position == items.size) {
            h.tile.background = Ui.roundRect(ctx.color(R.color.pill), radius)
            h.letter.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(R.drawable.ic_add)
            h.label.text = "Add"
            h.itemView.setOnClickListener { addDialog() }
            h.itemView.setOnLongClickListener(null)
            return
        }
        val d = items[position]
        h.label.text = d.title
        if (d.isFolder) {
            h.tile.background = Ui.roundRect(ctx.color(R.color.pill), radius)
            h.letter.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(R.drawable.ic_folder)
            h.icon.imageTintList = ColorStateList.valueOf(ctx.color(R.color.accent_dark))
            h.itemView.setOnClickListener { showFolder(d) }
            h.itemView.setOnLongClickListener { folderMenu(d); true }
        } else {
            val fav = a.store.favicon(hostOf(d.url))
            if (fav != null) {
                h.tile.background = Ui.roundRect(ctx.color(R.color.pill), radius)
                h.letter.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.setImageBitmap(fav)
            } else {
                h.tile.background = Ui.roundRect(Ui.tileColor(hostOf(d.url).ifEmpty { d.title }), radius)
                h.icon.visibility = View.GONE
                h.letter.visibility = View.VISIBLE
                h.letter.text = Ui.letterOf(d.title, d.url)
            }
            h.itemView.setOnClickListener { d.url?.let { u -> a.openUrl(u) } }
            h.itemView.setOnLongClickListener { siteMenu(d, items); true }
        }
    }

    // ------------------------------------------------------------ actions

    private fun save() {
        a.store.saveDial(items)
        notifyDataSetChanged()
    }

    private fun addDialog() {
        editDialog("Add shortcut", "", "") { t, u ->
            val url = a.normalizeUrl(u)
            items.add(Dial(t.ifBlank { hostOf(url).removePrefix("www.") }, url))
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

    private fun siteMenu(d: Dial, parent: MutableList<Dial>) {
        Ui.listSheet(
            a, d.title, listOf(
                "Open in new tab" to { d.url?.let { a.newTab(it) } },
                "Edit" to {
                    editDialog("Edit shortcut", d.title, d.url ?: "") { t, u ->
                        d.title = t.ifBlank { hostOf(u) }
                        d.url = a.normalizeUrl(u)
                        save()
                    }
                },
                "Move to folder" to { pickFolder(d, parent) },
                "Delete" to { parent.remove(d); save() }
            )
        )
    }

    private fun folderMenu(f: Dial) {
        Ui.listSheet(
            a, f.title, listOf(
                "Open" to { showFolder(f) },
                "Rename" to {
                    Ui.prompt(a, "Rename folder", "Folder name", f.title) { n ->
                        if (n.isNotEmpty()) { f.title = n; save() }
                    }
                },
                "Delete folder" to { items.remove(f); save() }
            )
        )
    }

    private fun pickFolder(d: Dial, parent: MutableList<Dial>) {
        val folders = items.filter { it.isFolder && it !== d }
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        folders.forEach { f ->
            entries.add(f.title to {
                parent.remove(d)
                f.children?.add(d)
                save()
            })
        }
        entries.add("New folder…" to {
            Ui.prompt(a, "New folder", "Folder name", "") { n ->
                if (n.isNotEmpty()) {
                    parent.remove(d)
                    items.add(Dial(n, null, mutableListOf(d)))
                    save()
                }
            }
        })
        Ui.listSheet(a, "Move to", entries)
    }

    private fun showFolder(f: Dial) {
        val kids = f.children ?: return
        if (kids.isEmpty()) {
            a.toast("Folder is empty. Long-press a shortcut and choose Move to folder.")
            return
        }
        Ui.listSheet(
            a, f.title,
            kids.map { k -> k.title to { k.url?.let { u -> a.openUrl(u) }; Unit } },
            longPress = { index ->
                val k = kids[index]
                Ui.listSheet(
                    a, k.title, listOf(
                        "Open in new tab" to { k.url?.let { u -> a.newTab(u) }; Unit },
                        "Move out of folder" to { kids.remove(k); items.add(k); save() },
                        "Delete" to { kids.remove(k); save() }
                    )
                )
            }
        )
    }
}
