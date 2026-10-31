package com.bjlo.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Favoritos: seleccionar abre la página · mantener pulsado = abrir / renombrar / eliminar.
 * Devuelve la URL elegida a MainActivity con EXTRA_URL.
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var store: FavoritesStore
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val adapter by lazy { RowAdapter(this) }
    private var items: List<Favorite> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        store = FavoritesStore(this)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.fav_title)
        findViewById<TextView>(R.id.screenHint).setText(R.string.fav_hint)
        findViewById<View>(R.id.screenInfo).visibility = View.GONE
        findViewById<View>(R.id.actionBtn).visibility = View.GONE
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        empty = findViewById(R.id.emptyText)
        empty.setText(R.string.fav_empty)

        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { open(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { showOptions(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items = store.all()
        adapter.rows = items.map { Row(it.name, it.url) }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty()) {
            list.requestFocus()
            list.post { list.setSelection(0) }
        }
    }

    private fun open(f: Favorite) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, f.url))
        finish()
    }

    private fun showOptions(f: Favorite) {
        val options = arrayOf(
            getString(R.string.fav_open),
            getString(R.string.fav_rename),
            getString(R.string.fav_delete)
        )
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(f.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> open(f)
                    1 -> rename(f)
                    else -> {
                        store.remove(f.url)
                        Toast.makeText(this, R.string.fav_deleted, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun rename(f: Favorite) {
        val input = EditText(this).apply {
            setText(f.name)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.fav_rename_title)
            .setView(input)
            .setPositiveButton(R.string.dlg_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.rename(f.url, name)
                    reload()
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
