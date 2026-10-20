package com.lifolo.unit

import com.lifolo.common.ClientIp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpTest {
    private fun req(peer: String, xff: String?) = MockHttpServletRequest().apply {
        remoteAddr = peer
        if (xff != null) addHeader("X-Forwarded-For", xff)
    }

    @Test
    fun `X-Forwarded-For only from this machine's proxy, and its last entry`() {
        assertEquals("203.0.113.5", ClientIp.of(req("127.0.0.1", "203.0.113.5")))
        assertEquals("203.0.113.5", ClientIp.of(req("127.0.0.1", "1.2.3.4, 203.0.113.5")))
        assertEquals("203.0.113.5", ClientIp.of(req("::1", "spoofed,203.0.113.5 ")))
        assertEquals("127.0.0.1", ClientIp.of(req("127.0.0.1", null)))
        assertEquals("127.0.0.1", ClientIp.of(req("127.0.0.1", " , ")))
        assertEquals("198.51.100.9", ClientIp.of(req("198.51.100.9", "203.0.113.5")))
        assertEquals("unknown", ClientIp.of(req("", null)))
        assertEquals("2001:db8:1:2", ClientIp.key("2001:db8:1:2:3:4:5:6"))
    }
}
