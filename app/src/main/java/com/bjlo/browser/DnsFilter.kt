package com.bjlo.browser

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Decide si un dominio es publicidad/rastreador.
 *
 * Estrategia (más ligera y segura que reenviar todo el tráfico por OkHttp):
 *  1. Lista local de dominios de anuncios conocidos (funciona sin red y con cualquier proveedor).
 *  2. Si hay un proveedor DoH, se CONSULTA el dominio; si el resolvedor lo bloquea (responde 0.0.0.0 / :: / loopback)
 *     se considera publicidad. El tráfico real de la página sigue su camino normal por el WebView.
 *
 * Los resultados se guardan en caché por dominio. Ante cualquier error de red el dominio se deja pasar.
 */
class DnsFilter(provider: DnsProvider, nextDnsId: String?) {

    private val cache = ConcurrentHashMap<String, Boolean>()
    private val doh: Dns? = buildDoh(provider, nextDnsId)

    /** Se llama desde el hilo de red del WebView, por eso puede bloquear unos milisegundos. */
    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trim('.')
        if (h.isEmpty() || isIpLiteral(h)) return false
        cache[h]?.let { return it }

        val blocked = BuiltInBlocklist.matches(h) || sinkholed(h)
        if (cache.size > MAX_CACHE) cache.clear()
        cache[h] = blocked
        return blocked
    }

    private fun sinkholed(host: String): Boolean {
        val dns = doh ?: return false
        return try {
            val answers = dns.lookup(host)
            answers.isNotEmpty() && answers.all { it.isAnyLocalAddress || it.isLoopbackAddress }
        } catch (e: Exception) {
            false // fail-open: nunca rompemos una página por un fallo del DNS
        }
    }

    private fun isIpLiteral(h: String) = h.contains(':') || h.all { it.isDigit() || it == '.' }

    companion object {
        private const val MAX_CACHE = 5000

        private fun buildDoh(provider: DnsProvider, nextDnsId: String?): Dns? {
            var url = provider.dohUrl ?: return null
            if (provider == DnsProvider.NEXTDNS) {
                val id = nextDnsId?.trim().orEmpty()
                if (id.isEmpty()) return null
                url += id
            }
            return try {
                val bootstrapClient = OkHttpClient.Builder()
                    .callTimeout(4, TimeUnit.SECONDS)
                    .build()
                val builder = DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(url.toHttpUrl())
                    .includeIPv6(false)
                if (provider.bootstrap.isNotEmpty()) {
                    builder.bootstrapDnsHosts(provider.bootstrap.map { InetAddress.getByName(it) })
                }
                builder.build()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Dominios de publicidad y rastreo muy extendidos. Coincide con el dominio y todos sus subdominios. */
object BuiltInBlocklist {
    private val domains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "2mdn.net", "adnxs.com", "adsrvr.org", "adform.net", "advertising.com",
        "amazon-adsystem.com", "casalemedia.com", "criteo.com", "criteo.net", "moatads.com",
        "openx.net", "pubmatic.com", "rubiconproject.com", "smartadserver.com", "yieldmo.com",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "zedo.com",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "adcash.com",
        "adsterra.com", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.com",
        "clickadu.com", "hilltopads.net", "scorecardresearch.com", "quantserve.com"
    )

    fun matches(host: String): Boolean {
        var h = host
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
