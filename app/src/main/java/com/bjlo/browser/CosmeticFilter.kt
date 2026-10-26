package com.bjlo.browser

/**
 * Limpieza estética: oculta los huecos que dejan los anuncios bloqueados.
 * Se inyecta con evaluateJavascript al terminar de cargar cada página.
 *
 * Importante: NO se ocultan todos los <iframe> (rompería reproductores de vídeo incrustados);
 * solo los que apuntan a redes publicitarias conocidas.
 */
object CosmeticFilter {
    private const val CSS =
        "ins.adsbygoogle,.adsbygoogle,[id^='google_ads'],[id^='div-gpt-ad'],[id^='aswift_']," +
            "iframe[src*='doubleclick'],iframe[src*='googlesyndication'],iframe[src*='/ads/']," +
            "iframe[src*='adservice'],iframe[id^='google_ads_iframe']," +
            ".ads,.adsbox,.ad-banner,.ad-container,.ad-slot,.advert,.advertisement,.sponsored-ad" +
            "{display:none!important;height:0!important;min-height:0!important;visibility:hidden!important}"

    val SCRIPT: String =
        "(function(){try{" +
            "if(document.getElementById('__bjlo_css'))return;" +
            "var s=document.createElement('style');s.id='__bjlo_css';" +
            "s.textContent=\"" + CSS + "\";" +
            "(document.head||document.documentElement).appendChild(s);" +
            "}catch(e){}})();"
}
