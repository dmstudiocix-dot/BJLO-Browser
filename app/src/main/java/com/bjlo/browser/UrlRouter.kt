package com.bjlo.browser

import java.net.URLEncoder

/**
 * Enrutador inteligente de la barra de direcciones. Lógica pura (sin Android) para poder probarla con JUnit.
 *
 * Orden de reglas:
 *  1. Vacío                       -> inicio (Google)
 *  2. Solo dígitos                -> acortador de AFTVnews (https://aftv.news/<código>)
 *  3. Con http:// o https://      -> tal cual
 *  4. localhost / IP (+ puerto)   -> http://  (servidores en la red local)
 *  5. Contiene '.' y sin espacios -> dominio, se antepone https://
 *  6. Cualquier otra cosa         -> búsqueda en Google
 */
object UrlRouter {
    const val HOME = "https://www.google.com"
    const val AFTV_SHORTENER = "https://aftv.news/"
    const val SEARCH = "https://www.google.com/search?q="

    private val localHost = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d{1,5})?(/.*)?$", RegexOption.IGNORE_CASE)

    fun resolve(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return HOME

        if (text.all { it in '0'..'9' }) return AFTV_SHORTENER + text

        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text

        if (!text.any { it.isWhitespace() }) {
            if (localHost.matches(text)) return "http://$text"
            if (text.contains('.') && !text.startsWith(".") && !text.endsWith(".")) return "https://$text"
        }

        return SEARCH + URLEncoder.encode(text, "UTF-8")
    }
}
