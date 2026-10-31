package com.bjlo.browser

import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Archivos descargados: ver, instalar/abrir y eliminar (uno a uno o todos) para liberar espacio.
 * Seleccionar = abrir menú de acciones · mantener pulsado = eliminar.
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var empty: TextView
    private lateinit var actionBtn: TextView
    private val adapter by lazy { RowAdapter(this) }
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.files_title)
        findViewById<TextView>(R.id.screenHint).setText(R.string.files_hint)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        info = findViewById(R.id.screenInfo)
        empty = findViewById(R.id.emptyText)
        empty.setText(R.string.files_empty)
        actionBtn = findViewById(R.id.actionBtn)
        actionBtn.setText(R.string.files_delete_all)
        actionBtn.setOnClickListener { confirmDeleteAll() }

        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> files.getOrNull(pos)?.let { showActions(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            files.getOrNull(pos)?.let { confirmDelete(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val current = dir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        files = current

        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val base = current.map { f ->
            Row(f.name, "${df.format(Date(f.lastModified()))} · ${Formatter.formatFileSize(this, f.length())}")
        }
        adapter.rows = base

        val used = current.fold(0L) { acc, f -> acc + f.length() }
        info.text = getString(
            R.string.files_summary,
            current.size,
            Formatter.formatFileSize(this, used),
            Formatter.formatFileSize(this, dir?.usableSpace ?: 0L)
        )
        val isEmpty = current.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        actionBtn.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (!isEmpty) {
            list.requestFocus()
            list.post { list.setSelection(0) }
        }

        // Para las APK se muestra paquete y versión (leer el manifiesto puede tardar: se hace aparte)
        Thread {
            val enriched = current.mapIndexed { i, f ->
                if (!FileOpener.isApk(f)) {
                    base[i]
                } else {
                    val pi = packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                    if (pi == null) {
                        base[i].copy(meta = getString(R.string.files_apk_invalid))
                    } else {
                        base[i].copy(meta = "${pi.packageName} · v${pi.versionName ?: "?"}")
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && files === current) adapter.rows = enriched
            }
        }.start()
    }

    private fun showActions(f: File) {
        val apk = FileOpener.isApk(f)
        val items = arrayOf(
            getString(if (apk) R.string.files_install else R.string.files_open),
            getString(R.string.files_delete)
        )
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(f.name)
            .setItems(items) { _, which ->
                if (which == 0) openFile(f, apk) else confirmDelete(f)
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun openFile(f: File, apk: Boolean) {
        if (apk && !FileOpener.canInstall(this)) {
            FileOpener.askInstallPermission(this, f.name)
            return
        }
        if (!FileOpener.launch(this, f)) {
            Toast.makeText(
                this,
                if (apk) R.string.install_missing else R.string.files_open_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.files_delete)
            .setMessage(getString(R.string.files_confirm_delete, f.name))
            .setPositiveButton(R.string.files_delete) { _, _ ->
                f.delete()
                Toast.makeText(this, R.string.files_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val current = files
        if (current.isEmpty()) return
        val total = current.fold(0L) { acc, f -> acc + f.length() }
        AlertDialog.Builder(this, R.style.BjloDialog)
            .setTitle(R.string.files_delete_all)
            .setMessage(
                getString(R.string.files_confirm_all, current.size, Formatter.formatFileSize(this, total))
            )
            .setPositiveButton(R.string.files_delete_all) { _, _ ->
                current.forEach { it.delete() }
                Toast.makeText(this, R.string.files_all_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }
}
