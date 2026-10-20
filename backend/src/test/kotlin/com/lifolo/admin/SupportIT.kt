package com.lifolo.admin

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** support.js — help & appeals tickets (the demo-health ticket steps, plus the limits) */
class SupportIT : IntegrationTest() {
    private val OWNER = "own1"

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, action, body).also { assertEquals(200, it.status, "$action: $it") }.body

    @Test
    fun `a user writes, the admin answers and closes, the user writes again`() {
        user(OWNER, emptyMap(), email = "owner@example.com")
        user("tu1", mapOf("name" to "টিকিট ব্যবহারকারী", "lang" to "bn"), email = "TU1@Example.com")
        assertEquals("কী নিয়ে, বেছে নিন", call("tu1", "ticket_create", mapOf("category" to "x", "text" to "অনেক বড় সমস্যা হয়েছে")).error)
        assertEquals(400, call("tu1", "ticket_create", mapOf("category" to "other", "text" to "ছোট")).status)
        val id = ok("tu1", "ticket_create", mapOf("category" to "account", "text" to "লগইন করতে পারছি না, কোড আসে না"))["id"].textValue()
        val mine = ok("tu1", "tickets_mine")["items"]
        assertEquals(id, mine[0]["id"].textValue())
        assertEquals("open", mine[0]["status"].textValue())
        assertEquals("user", mine[0]["messages"][0]["from"].textValue())
        assertTrue(mine[0]["createdAt"].isNumber && mine[0]["updatedAt"].isNumber)
        assertTrue(mine[0]["uid"] == null, "admin fields only for admins")

        // not an admin
        assertEquals(403, call("tu1", "admin_tickets").status)
        val open = ok(OWNER, "admin_tickets")["items"]
        assertEquals(id, open[0]["id"].textValue())
        assertEquals("tu1@example.com", open[0]["email"].textValue())
        assertEquals("টিকিট ব্যবহারকারী", open[0]["name"].textValue())
        assertEquals("user", open[0]["lastFrom"].textValue())
        assertEquals(400, call(OWNER, "admin_ticket_reply", mapOf("id" to id)).status, "a reply needs text")
        ok(OWNER, "admin_ticket_reply", mapOf("id" to id, "text" to "কোডটা স্প্যাম ফোল্ডারে দেখুন।"))
        val one = ok(OWNER, "admin_ticket", mapOf("id" to id))["item"]
        assertEquals("answered", one["status"].textValue())
        assertEquals("admin", one["lastFrom"].textValue())
        assertEquals(2, one["messages"].size())
        assertEquals("owner@example.com", row("SELECT by_email FROM ticket_messages WHERE ticket_id = ? AND idx = 1", id)["by_email"])
        // the user is told (bell list)
        val inbox = ok("tu1", "inbox")["items"]
        assertEquals("Lifolo টিমের উত্তর এসেছে", inbox[0]["title"].textValue())
        assertEquals("support", inbox[0]["data"]["type"].textValue())
        assertEquals(id, inbox[0]["data"]["ticketId"].textValue())
        // closing alone needs no text and sends nothing
        ok(OWNER, "admin_ticket_reply", mapOf("id" to id, "close" to true))
        assertEquals("closed", ok("tu1", "tickets_mine")["items"][0]["status"].textValue())
        assertEquals(1, ok("tu1", "inbox")["items"].size())
        assertEquals(1, ok(OWNER, "admin_tickets", mapOf("status" to "closed"))["items"].size())
        // writing again opens it
        ok("tu1", "ticket_reply", mapOf("id" to id, "text" to "এখনো আসেনি"))
        assertEquals("open", ok("tu1", "tickets_mine")["items"][0]["status"].textValue())
        // someone else's ticket
        user("tu2")
        assertEquals(404, call("tu2", "ticket_reply", mapOf("id" to id, "text" to "হ্যাক")).status)
        assertEquals(404, call(OWNER, "admin_ticket", mapOf("id" to "nope")).status)
    }

    @Test
    fun `at most 3 open tickets, 40 messages`() {
        user("tu3")
        repeat(3) { ok("tu3", "ticket_create", mapOf("category" to "other", "text" to "সমস্যা নম্বর $it লিখলাম")) }
        val r = call("tu3", "ticket_create", mapOf("category" to "other", "text" to "আরেকটা সমস্যা লিখলাম"))
        assertEquals(429, r.status)
        assertEquals("আপনার ৩টা অনুরোধ খোলা আছে। উত্তরের অপেক্ষা করুন বা আগেরটাতেই লিখুন।", r.error)
        val id = ok("tu3", "tickets_mine")["items"][0]["id"].textValue()
        for (i in 1 until 40) jdbc.update("INSERT INTO ticket_messages (ticket_id, idx, sender, text) VALUES (?, ?, 'user', 'x')", id, i)
        assertEquals("এই কথোপকথন ভরে গেছে। নতুন করে লিখুন।", call("tu3", "ticket_reply", mapOf("id" to id, "text" to "আরও")).error)
    }
}
