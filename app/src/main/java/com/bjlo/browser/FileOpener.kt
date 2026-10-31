package com.bjlo.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/** Abre o instala archivos descargados (compartido por las descargas y la pantalla de Archivos). */
object FileOpener {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun isApk(file: File) = file.name.endsWith(".apk", ignoreCase = true)

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(ctx: Context) {
        val specific = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        )
        try {
            ctx.startActivity(specific)
        } catch (e: ActivityNotFoundException) {
            // Algunos TV Box no incluyen esta pantalla: caemos a los ajustes de seguridad.
            try {
                ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    fun askInstallPermission(activity: AppCompatActivity, label: String, onCancel: () -> Unit = {}) {
        AlertDialog.Builder(activity, R.style.BjloDialog)
            .setTitle(R.string.perm_title)
            .setMessage(activity.getString(R.string.perm_msg, label))
            .setPositiveButton(R.string.perm_open) { _, _ -> openUnknownSourcesSettings(activity) }
            .setNegativeButton(R.string.dlg_cancel) { _, _ -> onCancel() }
            .show()
    }

    private fun uriFor(ctx: Context, file: File): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file) // Android 5/6: el instalador no admite content://
        }

    private fun mimeFor(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    /** Instala (si es APK) o abre el archivo con la app adecuada. Devuelve false si no hay quién lo abra. */
    fun launch(ctx: Context, file: File): Boolean {
        val mime = if (isApk(file)) APK_MIME else mimeFor(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(ctx, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
