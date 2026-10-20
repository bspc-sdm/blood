package com.lifolo.notify

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request

/**
 * /api/events stays alive behind proxies: Cloudflare closes a proxied connection after 100 s without bytes, so the
 * hub writes an SSE comment (": ping") to every open stream every 25 s.
 */
class SseKeepAliveIT : IntegrationTest() {
    @Autowired lateinit var hub: SseHub

    @Test
    fun `open stream gets a comment ping, then events still arrive`() {
        user("sse1")
        val res = mvc.perform(MockMvcRequestBuilders.get("/api/events").header("Authorization", "Bearer sse1"))
            .andExpect(request().asyncStarted())
            .andReturn().response
        assertEquals(1, hub.subscribers("user:sse1"))

        assertTrue(hub.pingAll() >= 1)
        val text = res.getContentAsString(Charsets.UTF_8)
        assertTrue(text.contains(":ping\n\n"), "ping comment written: <$text>")

        assertEquals(1, hub.publish("user:sse1", "hello", mapOf("x" to 1)))
        val after = res.getContentAsString(Charsets.UTF_8)
        assertTrue(after.contains("event:hello\ndata:{\"x\":1}\n\n"), "event after ping: <$after>")
        // still subscribed after the ping (a ping never drops a live stream)
        assertEquals(1, hub.subscribers("user:sse1"))
    }

    @Test
    fun `keep-alive is every 25 seconds (under Cloudflare's 100 s idle limit)`() {
        assertEquals(25_000L, hub.keepAliveMs)
    }

    @Test
    fun `the schedule runs by itself`() {
        val quick = SseHub(30)
        try {
            quick.subscribe("user:t")
            val until = System.currentTimeMillis() + 3_000
            while (quick.pingRounds.get() < 3 && System.currentTimeMillis() < until) Thread.sleep(10)
            assertTrue(quick.pingRounds.get() >= 3, "rounds=${quick.pingRounds.get()}")
        } finally {
            quick.stop()
        }
    }

    @Test
    fun `guest cannot open a stream`() {
        val before = hub.subscribers("user:null")
        val r = mvc.perform(MockMvcRequestBuilders.get("/api/events")).andReturn().response
        assertTrue(r.status >= 400, "status ${r.status}")
        assertEquals(before, hub.subscribers("user:null"))
    }
}
