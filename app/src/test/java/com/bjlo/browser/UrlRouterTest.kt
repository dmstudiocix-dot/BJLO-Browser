package com.bjlo.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlRouterTest {

    @Test fun empty_goes_home() {
        assertEquals(UrlRouter.HOME, UrlRouter.resolve(""))
        assertEquals(UrlRouter.HOME, UrlRouter.resolve("   "))
    }

    @Test fun numeric_code_goes_to_aftv_shortener() {
        assertEquals("https://aftv.news/357951852", UrlRouter.resolve("357951852"))
        assertEquals("https://aftv.news/123456", UrlRouter.resolve(" 123456 "))
    }

    @Test fun explicit_protocol_is_kept() {
        assertEquals("https://example.com/a?b=1", UrlRouter.resolve("https://example.com/a?b=1"))
        assertEquals("http://example.com", UrlRouter.resolve("http://example.com"))
        assertEquals("HTTP://EXAMPLE.COM", UrlRouter.resolve("HTTP://EXAMPLE.COM"))
    }

    @Test fun domains_get_https() {
        assertEquals("https://facebook.com", UrlRouter.resolve("facebook.com"))
        assertEquals("https://www.google.com", UrlRouter.resolve("www.google.com"))
        assertEquals("https://aftv.news/123", UrlRouter.resolve("aftv.news/123"))
    }

    @Test fun local_servers_get_http() {
        assertEquals("http://192.168.1.5:8080", UrlRouter.resolve("192.168.1.5:8080"))
        assertEquals("http://localhost:3000/panel", UrlRouter.resolve("localhost:3000/panel"))
    }

    @Test fun anything_else_is_a_search() {
        assertEquals("https://www.google.com/search?q=hola+mundo", UrlRouter.resolve("hola mundo"))
        assertEquals("https://www.google.com/search?q=clima", UrlRouter.resolve("clima"))
        assertEquals("https://www.google.com/search?q=mi.web+con+espacio", UrlRouter.resolve("mi.web con espacio"))
    }
}
