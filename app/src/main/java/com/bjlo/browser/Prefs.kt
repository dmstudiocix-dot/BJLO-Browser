package com.bjlo.browser

import android.content.Context

/** Proveedores de DNS sobre HTTPS (DoH) usados para decidir qué dominios son publicidad/rastreadores. */
enum class DnsProvider(
    val id: String,
    val label: String,
    val dohUrl: String?,
    val bootstrap: List<String>
) {
    SISTEMA("SISTEMA", "Automático (Sistema)", null, emptyList()),
    ADGUARD(
        "ADGUARD", "AdGuard DNS (Recomendado)",
        "https://dns.adguard-dns.com/dns-query",
        listOf("94.140.14.14", "94.140.15.15")
    ),
    // NextDNS necesita el ID de tu configuración al final de la URL.
    NEXTDNS(
        "NEXTDNS", "NextDNS Ads",
        "https://dns.nextdns.io/",
        listOf("45.90.28.0", "45.90.30.0")
    ),
    CONTROLD("CONTROLD", "Control D Blocker", "https://freedns.controld.com/p2", emptyList());

    companion object {
        fun fromId(id: String?): DnsProvider = values().firstOrNull { it.id == id } ?: ADGUARD
    }
}

/** Preferencias persistentes (SharedPreferences "BJLO_PREFS"). */
class Prefs(context: Context, private val tvDefault: Boolean) {
    private val sp = context.getSharedPreferences("BJLO_PREFS", Context.MODE_PRIVATE)

    var dns: DnsProvider
        get() = DnsProvider.fromId(sp.getString(KEY_DNS, null))
        set(v) = sp.edit().putString(KEY_DNS, v.id).apply()

    var nextDnsId: String
        get() = sp.getString(KEY_NEXTDNS_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_NEXTDNS_ID, v.trim()).apply()

    var adBlock: Boolean
        get() = sp.getBoolean(KEY_ADBLOCK, true)
        set(v) = sp.edit().putBoolean(KEY_ADBLOCK, v).apply()

    /** Cursor virtual controlado con el D-pad. Activado por defecto en TV. */
    var cursorMode: Boolean
        get() = sp.getBoolean(KEY_CURSOR, tvDefault)
        set(v) = sp.edit().putBoolean(KEY_CURSOR, v).apply()

    /** User-Agent de escritorio. Activado por defecto en TV (sitios más cómodos en pantalla grande). */
    var desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP, tvDefault)
        set(v) = sp.edit().putBoolean(KEY_DESKTOP, v).apply()

    /** Tamaño del texto en % (50..200), ajustable con el zoom. */
    var textZoom: Int
        get() = sp.getInt(KEY_TEXT_ZOOM, 100)
        set(v) = sp.edit().putInt(KEY_TEXT_ZOOM, v.coerceIn(50, 200)).apply()

    /** Instalar automáticamente las APK al terminar la descarga (como "APK Auto-Install" de Downloader). */
    var autoInstall: Boolean
        get() = sp.getBoolean(KEY_AUTOINSTALL, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOINSTALL, v).apply()

    /** Días tras los cuales se borran las descargas antiguas (0 = nunca). */
    var autoCleanDays: Int
        get() = sp.getInt(KEY_CLEAN_DAYS, 7)
        set(v) = sp.edit().putInt(KEY_CLEAN_DAYS, v).apply()

    companion object {
        const val KEY_TEXT_ZOOM = "KEY_TEXT_ZOOM"
        const val KEY_AUTOINSTALL = "KEY_AUTOINSTALL"
        const val KEY_CLEAN_DAYS = "KEY_CLEAN_DAYS"
        const val KEY_DNS = "KEY_DNS"
        const val KEY_NEXTDNS_ID = "KEY_NEXTDNS_ID"
        const val KEY_ADBLOCK = "KEY_ADBLOCK"
        const val KEY_CURSOR = "KEY_CURSOR"
        const val KEY_DESKTOP = "KEY_DESKTOP"
    }
}
