package com.bjlo.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import java.io.File

/**
 * Descarga archivos con DownloadManager y, si son APK, lanza el instalador del sistema.
 *
 * Los archivos se guardan en la carpeta propia de la app (Android/data/<paquete>/files/Download),
 * así no se necesita permiso de almacenamiento y se borran al desinstalar.
 */
class DownloadInstaller(private val activity: AppCompatActivity) {

    private val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val apkIds = HashSet<Long>()
    private val completedInBackground = ArrayList<Long>()
    private var waitingForPermission: File? = null

    init {
        cleanOldFiles()
    }

    fun isApk(url: String, mime: String?, contentDisposition: String?): Boolean {
        if (mime?.lowercase() == APK_MIME) return true
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
            if (apk) apkIds.add(id)
            true
        } catch (e: Exception) {
            toast(activity.getString(R.string.download_failed, -1))
            false
        }
    }

    /** Llamado por el BroadcastReceiver de ACTION_DOWNLOAD_COMPLETE. */
    fun onDownloadComplete(id: Long) {
        if (id !in apkIds) return
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handleFinished(id)
        } else {
            completedInBackground.add(id) // Android 10+ no permite abrir pantallas desde segundo plano
        }
    }

    /** Llamar desde onResume: procesa descargas terminadas en segundo plano y reintenta tras conceder el permiso. */
    fun onResume() {
        if (completedInBackground.isNotEmpty()) {
            val ids = ArrayList(completedInBackground)
            completedInBackground.clear()
            ids.forEach { handleFinished(it) }
        }
        val waiting = waitingForPermission
        if (waiting != null && canInstall()) {
            waitingForPermission = null
            launchInstall(waiting)
        }
    }

    private fun handleFinished(id: Long) {
        apkIds.remove(id)
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
        val path = localUri?.let { Uri.parse(it).path } ?: return
        val file = File(path)

        // Comprueba que realmente sea un paquete Android antes de pasarlo al instalador.
        val info = activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info == null) {
            file.delete()
            toast(activity.getString(R.string.apk_invalid))
            return
        }
        if (canInstall()) {
            launchInstall(file)
        } else {
            waitingForPermission = file
            askInstallPermission(info.packageName ?: file.name)
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun askInstallPermission(label: String) {
        AlertDialog.Builder(activity, R.style.BjloDialog)
            .setTitle(R.string.perm_title)
            .setMessage(activity.getString(R.string.perm_msg, label))
            .setPositiveButton(R.string.perm_open) { _, _ -> openUnknownSourcesSettings() }
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> waitingForPermission = null }
            .show()
    }

    private fun openUnknownSourcesSettings() {
        val specific = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            activity.startActivity(specific)
        } catch (e: ActivityNotFoundException) {
            // Algunos TV Box no incluyen esta pantalla: caemos a los ajustes de seguridad.
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun launchInstall(file: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file) // Android 5/6: el instalador no admite content://
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(activity.getString(R.string.install_missing))
        }
    }

    /** Borra descargas con más de 24 h para no acumular APKs en equipos con poco espacio. */
    private fun cleanOldFiles() {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
