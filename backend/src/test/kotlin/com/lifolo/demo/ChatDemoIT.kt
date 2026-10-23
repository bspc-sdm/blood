package com.lifolo.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The demo world has a few message threads (ChatDemoSeed); the daily rebuild brings them back, the wipe removes them */
class ChatDemoIT : DemoTestBase() {
    @Autowired lateinit var refresh: DemoRefresh

    private fun threads(): Int = jdbc.queryForObject("SELECT count(*) FROM chat_threads WHERE is_demo", Int::class.java) ?: 0

    @Test
    fun `demo threads - seeded, usable by the demo roles, rebuilt by DemoRefresh, gone after the wipe`() {
        owner()
        seedAll()
        assertEquals(3, threads())
        // the demo donor talks with requester রেহানা: one unread message, may answer
        val d = ok(U("donor"), "chat_threads")["items"]
        assertEquals(1, d.size())
        assertEquals("blood", d[0]["ctx"].textValue())
        assertEquals(1, d[0]["unread"].intValue())
        assertTrue(d[0]["canSend"].booleanValue(), d.toString())
        val t = d[0]["threadId"].textValue()
        val msgs = ok(U("donor"), "chat_messages", mapOf("threadId" to t))["messages"]
        assertTrue(msgs.any { it.has("location") }, "a location message")
        ok(U("donor"), "chat_send", mapOf("threadId" to t, "clientId" to "demo-test-1", "text" to "ডেমো উত্তর"))
        // the demo patient ↔ the demo lab
        val p = ok(U("patient"), "chat_threads")["items"]
        assertTrue(p.any { it["ctx"].textValue() == "lab_order" && it["peer"]["uid"].textValue() == U("lab") }, p.toString())
        assertEquals(1, ok(U("pharmacy"), "chat_unread")["total"].intValue())
        // a new Dhaka day: the demo is built again, with its threads (the tester's message is gone)
        assertEquals("rebuilt", refresh.refresh(System.currentTimeMillis() + DAY)["done"])
        assertEquals(3, threads())
        assertFalse(jdbc.queryForList("SELECT 1 FROM chat_messages WHERE client_id = 'demo-test-1'").isNotEmpty())
        wipe()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_threads", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_messages", Int::class.java))
    }
}
