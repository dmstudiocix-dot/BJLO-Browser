package com.bjlo.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** Fila de las listas de Archivos y Favoritos: título, subtítulo y una línea extra opcional. */
data class Row(val title: String, val sub: String, val meta: String = "")

class RowAdapter(private val ctx: Context) : BaseAdapter() {

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_row, parent, false)
        val r = rows[position]
        v.findViewById<TextView>(R.id.rowTitle).text = r.title
        v.findViewById<TextView>(R.id.rowSub).text = r.sub
        val meta = v.findViewById<TextView>(R.id.rowMeta)
        meta.text = r.meta
        meta.visibility = if (r.meta.isEmpty()) View.GONE else View.VISIBLE
        return v
    }
}
