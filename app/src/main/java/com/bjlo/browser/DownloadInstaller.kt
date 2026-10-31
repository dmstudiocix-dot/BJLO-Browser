package com.bjlo.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import java.io.File

/**
 * Descarga archivos con DownloadManager y, si son APK, lanza el instalador del sistema.
 *
 * Los archivos se guardan en la carpeta propia de la app (Android/data/<paquete>/files/Download),
 * así no se necesita permiso de almacenamiento y se borran al desinstalar.
 * La pantalla "Archivos" permite verlos y eliminarlos.
 */
class DownloadInstaller(
    private val activity: AppCompatActivity,
    private val prefs: Prefs
) {

    private class Pending(val name: String, val apk: Boolean)

    /** Estado para la barra de progreso (percent = -1 si el tamaño es desconocido). */
    class Progress(val name: String, val percent: Int)

    private val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val pending = LinkedHashMap<Long, Pending>()
    private val finishedInBackground = ArrayList<Pair<Long, Pending>>()
    private var waitingForPermission: File? = null

    init {
        cleanOldFiles()
    }

    fun isApk(url: String, mime: String?, contentDisposition: String?): Boolean {
        if (mime?.lowercase() == FileOpener.APK_MIME) return true
        val guessed = URLUtil.guessFileName(url, contentDisposition, mime).lowercase()
        if (guessed.endsWith(".apk")) return true
        return Uri.parse(url).path.orEmpty().lowercase().endsWith(".apk")
    }

    fun suggestName(url: String, contentDisposition: String?, mime: String?, apk: Boolean): String {
        var name = URLUtil.guessFileName(url, contentDisposition, mime)
        if (apk && !name.lowercase().endsWith(".apk")) {
            name = name.substringBeforeLast('.', name) + ".apk"
        }
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /** Encola la descarga. Devuelve false si no hay dónde guardar. */
    fun enqueue(url: String, userAgent: String?, mime: String?, name: String, apk: Boolean): Boolean {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            toast(activity.getString(R.string.download_no_storage))
            return false
        }
        File(dir, name).takeIf { it.exists() }?.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        if (!mime.isNullOrBlank()) request.setMimeType(mime)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }

        return try {
            val id = dm.enqueue(request)
            pending[id] = Pending(name, apk)
            true
        } catch (e: Exception) {
            toast(activity.getString(R.string.download_failed, -1))
            false
        }
    }

    fun hasActive(): Boolean = pending.isNotEmpty()

    fun cancelAll() {
        pending.keys.toList().forEach { dm.remove(it) }
        pending.clear()
    }

    /** Progreso de la descarga en curso, o null si no hay ninguna. Se consulta cada ~500 ms desde la pantalla. */
    fun progress(): Progress? {
        val id = pending.keys.firstOrNull() ?: return null
        val p = pending[id] ?: return null
        var status = -1
        var done = 0L
        var total = -1L
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                pending.remove(id)
                return null
            }
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
            onDownloadComplete(id) // red de seguridad por si el broadcast no llegó
            return null
        }
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        return Progress(p.name, percent)
    }

    /** Llamado por el BroadcastReceiver de ACTION_DOWNLOAD_COMPLETE (o por la red de seguridad de progress()). */
    fun onDownloadComplete(id: Long) {
        val p = pending.remove(id) ?: return
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (p.apk && prefs.autoInstall && !resumed) {
            finishedInBackground.add(id to p) // Android 10+ no permite abrir pantallas desde segundo plano
        } else {
            handleFinished(id, p)
        }
    }

    /** Llamar desde onResume: procesa descargas terminadas en segundo plano y reintenta tras conceder el permiso. */
    fun onResume() {
        if (finishedInBackground.isNotEmpty()) {
            val items = ArrayList(finishedInBackground)
            finishedInBackground.clear()
            items.forEach { handleFinished(it.first, it.second) }
        }
        val waiting = waitingForPermission
        if (waiting != null && FileOpener.canInstall(activity)) {
            waitingForPermission = null
            launchInstall(waiting)
        }
    }

    private fun handleFinished(id: Long, p: Pending) {
        var status = -1
        var reason = 0
        var localUri: String? = null
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        }

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            toast(activity.getString(R.string.download_failed, reason))
            return
        }
        if (!p.apk || !prefs.autoInstall) {
            toast(activity.getString(R.string.download_done_hint, p.name))
            return
        }

        val path = localUri?.let { Uri.parse(it).path } ?: return
        val file = File(path)

        // Comprueba que realmente sea un paquete Android antes de pasarlo al instalador.
        val info = activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info == null) {
            file.delete()
            toast(activity.getString(R.string.apk_invalid))
            return
        }
        if (FileOpener.canInstall(activity)) {
            launchInstall(file)
        } else {
            waitingForPermission = file
            FileOpener.askInstallPermission(activity, info.packageName ?: file.name) {
                waitingForPermission = null
            }
        }
    }

    private fun launchInstall(file: File) {
        if (!FileOpener.launch(activity, file)) toast(activity.getString(R.string.install_missing))
    }

    /** Borra descargas más antiguas que el límite elegido en Ajustes (0 = nunca). */
    fun cleanOldFiles() {
        val days = prefs.autoCleanDays
        if (days <= 0) return
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val limit = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
