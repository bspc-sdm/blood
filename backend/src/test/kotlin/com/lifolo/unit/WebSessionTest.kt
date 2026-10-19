package com.lifolo.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.WebSession
import com.lifolo.common.LifoloProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Base64

/** Port of server/test/websession.test.js */
class WebSessionTest {
    private val s = WebSession(LifoloProperties(webSessionSecret = "test-secret"), ObjectMapper())

    private fun reqWith(cookie: String, csrf: Boolean = true) = MockHttpServletRequest().apply {
        addHeader("Cookie", cookie)
        if (csrf) addHeader(WebSession.CSRF_HEADER, "1")
    }

    private fun issued(now: Long = System.currentTimeMillis()): String {
        val res = MockHttpServletResponse()
        s.start(res, "uid1", "a@b.com", now)
        return res.getHeader("Set-Cookie")!!.split(';')[0]
    }

    private fun userOf(r: WebSession.Result?) = (r as? WebSession.Result.User)?.user

    @Test
    fun `same signature as the Node server`() {
        assertEquals(
            "eyJ1IjoidWlkMSIsImUiOiJhQGIuY29tIiwiaSI6MTcwMDAwMDAwMDAwMCwicyI6MTcwMDAwMDAwMDAwMH0.CyXSnhuOUxXGS4ak31qXnbJHwUoNSGVwnzGglMT9irY",
            s.encode(WebSession.Session("uid1", "a@b.com", 1700000000000, 1700000000000)),
        )
    }

    @Test
    fun `login cookie is httpOnly, Secure, SameSite=Strict`() {
        val res = MockHttpServletResponse()
        s.start(res, "uid1", "a@b.com")
        val c = res.getHeader("Set-Cookie")!!
        assertTrue(c.startsWith("__Host-lf_admin="))
        assertTrue(c.contains("HttpOnly"))
        assertTrue(c.contains("Secure"))
        assertTrue(c.contains("SameSite=Strict"))
        assertTrue(c.contains("Path=/"))
    }

    @Test
    fun `valid cookie and header give the user`() {
        val u = userOf(s.user(reqWith(issued()), MockHttpServletResponse()))!!
        assertEquals("uid1", u.uid)
        assertEquals("a@b.com", u.email)
        assertTrue(u.emailVerified)
        assertTrue(u.webSession)
    }

    @Test
    fun `no cookie means null`() {
        assertNull(s.user(MockHttpServletRequest(), MockHttpServletResponse()))
    }

    @Test
    fun `no CSRF header is rejected`() {
        assertEquals(WebSession.Result.Error("csrf"), s.user(reqWith(issued(), csrf = false), MockHttpServletResponse()))
    }

    @Test
    fun `tampered cookie is rejected`() {
        val c = issued()
        val (name, value) = c.split('=', limit = 2)
        val (payload, sig) = value.split('.')
        val now = System.currentTimeMillis()
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"u":"other","e":"x@y.com","i":$now,"s":$now}""".toByteArray())
        assertEquals(WebSession.Result.Error("expired"), s.user(reqWith("$name=$forged.$sig"), MockHttpServletResponse()))
        assertEquals(WebSession.Result.Error("expired"), s.user(reqWith("$name=$payload.${sig.dropLast(2)}xx"), MockHttpServletResponse()))
    }

    @Test
    fun `two idle hours end the session`() {
        val t0 = System.currentTimeMillis()
        val c = issued(t0)
        assertNotNull(userOf(s.user(reqWith(c), MockHttpServletResponse(), t0 + 119 * 60000)))
        val res = MockHttpServletResponse()
        assertEquals(WebSession.Result.Error("expired"), s.user(reqWith(c), res, t0 + 121 * 60000))
        assertTrue(res.getHeader("Set-Cookie")!!.contains("Max-Age=0"))
    }

    @Test
    fun `cookie refreshes while used but not past 12 hours`() {
        val t0 = System.currentTimeMillis() - 13 * 3600 * 1000
        var cookie = issued(t0)
        var now = t0
        while (now - t0 < 11.5 * 3600 * 1000) {
            now += 20 * 60000
            val res = MockHttpServletResponse()
            assertNotNull(userOf(s.user(reqWith(cookie), res, now)), "should still be active")
            res.getHeader("Set-Cookie")?.let { cookie = it.split(';')[0] }
        }
        now = t0 + 12 * 3600 * 1000 + 60000
        assertEquals(WebSession.Result.Error("expired"), s.user(reqWith(cookie), MockHttpServletResponse(), now))
    }

    @Test
    fun `finds our cookie among others`() {
        val c = issued()
        assertNotNull(userOf(s.user(reqWith("foo=bar; $c; x=y"), MockHttpServletResponse())))
    }
}
