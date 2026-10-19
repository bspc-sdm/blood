package com.lifolo.notify

import com.lifolo.support.IntegrationTest
import com.lifolo.users.PhoneService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/** inbox.js, devices.js and phone.js (no Node unit tests — the e2e suites covered them) */
class InboxDevicesIT : IntegrationTest() {
    @Autowired lateinit var pushService: PushService

    @Autowired lateinit var phone: PhoneService

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, action, body).also { assertEquals(200, it.status, "$action: $it") }.body

    private fun tokens(uid: String) = jdbc.queryForList("SELECT token FROM device_tokens WHERE uid = ? ORDER BY updated_at", String::class.java, uid)

    @Test
    fun `inbox - newest first, read after inbox_read, only your own`() {
        user("inb1", mapOf("name" to "ক"))
        user("inb2", mapOf("name" to "খ"))
        pushService.send(listOf(PushItem("inb1", "first", "a", mapOf("type" to "x"))))
        Thread.sleep(5)
        pushService.send(listOf(PushItem("inb1", "second", "b", mapOf("type" to "support", "ticketId" to "t1"))))
        pushService.send(listOf(PushItem("inb2", "other", "", emptyMap())))
        val items = ok("inb1", "inbox")["items"]
        assertEquals(listOf("second", "first"), items.map { it["title"].textValue() })
        assertTrue(items.all { !it["read"].booleanValue() })
        assertEquals("t1", items[0]["data"]["ticketId"].textValue())
        assertTrue(items[0]["createdAt"].isNumber)
        assertEquals(2, ok("inb1", "me")["unread"].intValue())
        ok("inb1", "inbox_read")
        assertTrue(ok("inb1", "inbox")["items"].all { it["read"].booleanValue() })
        assertEquals(0, ok("inb1", "me")["unread"].intValue())
        assertEquals(1, ok("inb2", "inbox")["items"].size())
        assertEquals(401, call(null, "inbox").status)
    }

    @Test
    fun `devices - one account per phone, at most 5, logout removes only that phone`() {
        user("dev1")
        user("dev2")
        assertEquals(400, call("dev1", "device_register", mapOf("token" to "short")).status)
        assertEquals(400, call("dev1", "device_register", mapOf("token" to "has space in it but long enough")).status)
        val t = (1..7).map { "token-number-$it-abcdefghijklmnop" }
        for (x in t) {
            ok("dev1", "device_register", mapOf("token" to x))
            Thread.sleep(3)
        }
        assertEquals(t.takeLast(5), tokens("dev1"))
        // the same phone logs into another account: the first account loses it
        ok("dev2", "device_register", mapOf("token" to t[6]))
        assertFalse(tokens("dev1").contains(t[6]))
        assertEquals(listOf(t[6]), tokens("dev2"))
        // logout: only this phone
        ok("dev1", "device_unregister", mapOf("token" to t[5]))
        assertEquals(t.subList(2, 5), tokens("dev1"))
        // an invalid token is just ok
        ok("dev1", "device_unregister", mapOf("token" to 5))
        // pushes go to the registered phones
        pushService.send(listOf(PushItem("dev2", "hello")))
        assertEquals(listOf(t[6]), push.messages.map { it.token })
    }

    @Test
    fun `phone verification - off without an SMS gateway; codes expire, 5 tries`() {
        user("ph1", mapOf("phone" to "01711222333"))
        val off = call("ph1", "phone_send_code")
        assertEquals(400, off.status)
        assertEquals("ফোন যাচাই এখনো চালু হয়নি", off.error)
        assertEquals("Phone verification is not available yet", call("ph1", "phone_send_code", lang = "en").error)
        // no code yet
        assertEquals("কোডের মেয়াদ শেষ। নতুন কোড নিন।", call("ph1", "phone_verify_code", mapOf("code" to "123456")).error)
        // a code (as phone_send_code stores it)
        val p = "8801711222333"
        jdbc.update(
            "INSERT INTO user_state (uid, phone_code_hash, phone_code_phone, phone_code_expires_at) VALUES (?, ?, ?, ?)",
            "ph1", phone.codeHash("ph1", p, "654321"), p, Timestamp(System.currentTimeMillis() + 600_000),
        )
        assertEquals("কোড মেলেনি", call("ph1", "phone_verify_code", mapOf("code" to "111111")).error)
        assertEquals(1, row("SELECT phone_code_tries FROM user_state WHERE uid = 'ph1'")["phone_code_tries"])
        ok("ph1", "phone_verify_code", mapOf("code" to "65-43-21"))
        assertEquals(p, row("SELECT verified_phone FROM user_state WHERE uid = 'ph1'")["verified_phone"])
        // used up
        assertEquals(400, call("ph1", "phone_verify_code", mapOf("code" to "654321")).status)
        // 5 wrong tries end a code
        jdbc.update(
            "UPDATE user_state SET phone_code_hash = ?, phone_code_phone = ?, phone_code_expires_at = ?, phone_code_tries = 5 WHERE uid = 'ph1'",
            phone.codeHash("ph1", p, "222222"), p, Timestamp(System.currentTimeMillis() + 600_000),
        )
        assertEquals("কোডের মেয়াদ শেষ। নতুন কোড নিন।", call("ph1", "phone_verify_code", mapOf("code" to "222222")).error)
    }
}
