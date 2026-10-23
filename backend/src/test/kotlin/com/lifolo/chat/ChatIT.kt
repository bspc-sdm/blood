package com.lifolo.chat

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.contact.BlockService
import com.lifolo.jobs.DailyJobRunner
import com.lifolo.support.IntegrationTest
import com.lifolo.users.AccountDeletionService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.ImageIO

/**
 * In-app messages (backend/docs/CHAT.md): relations (allowed / denied per ctx), the donor rule, blocks, limits,
 * idempotent send, unsend window, read-only threads, retention, photo privacy (signed URLs, EXIF stripped), pushes.
 */
class ChatIT : IntegrationTest() {
    @Autowired lateinit var chat: ChatService

    @Autowired lateinit var photos: ChatPhotos

    @Autowired lateinit var blocks: BlockService

    @Autowired lateinit var daily: DailyJobRunner

    @Autowired lateinit var deletion: AccountDeletionService

    @Autowired lateinit var features: ChatFeatures

    @Autowired lateinit var callFeatures: com.lifolo.calls.CallFeatures

    private val dhaka = ZoneId.of("Asia/Dhaka")

    @BeforeEach
    fun clockNow() {
        chat.clock = { System.currentTimeMillis() }
        jdbc.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS online BOOLEAN NOT NULL DEFAULT FALSE")
    }

    @AfterEach
    fun clockBack() {
        chat.clock = { System.currentTimeMillis() }
        features.override = null
        callFeatures.override = null
    }

    // ------------------------------------------------------------------ helpers

    private fun person(uid: String, name: String = "নাম $uid") {
        user(uid, mapOf("name" to name, "phone" to "01711000000"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", "tok-$uid", uid)
    }

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action $body → $r")
        return r.body
    }

    private fun open(uid: String, ctx: String, refId: String, peer: String? = null) =
        call(uid, "chat_open", linkedMapOf<String, Any?>("ctx" to ctx, "refId" to refId).apply { if (peer != null) put("peerUid", peer) })

    private var cid = 0

    private fun send(uid: String, threadId: String, text: String? = "হ্যালো", extra: Map<String, Any?> = emptyMap(), clientId: String = "c${++cid}") =
        call(uid, "chat_send", linkedMapOf<String, Any?>("threadId" to threadId, "clientId" to clientId).apply {
            if (text != null) put("text", text)
            putAll(extra)
        })

    private fun pushes(uid: String, type: String) = push.messages.filter { it.uid == uid && it.data["type"] == type }

    private fun bloodRequest(id: String, requester: String, responses: Map<String, String> = emptyMap(), confirmed: Set<String> = emptySet()) {
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, patient_group, bags, hospital, lat, lng, geohash, needed_by_ms, status)
            VALUES (?, ?, 'A+', 1, 'ঢাকা মেডিকেল', 23.7, 90.4, 'wh0r', ?, 'open')
            """.trimIndent(),
            id, requester, System.currentTimeMillis() + 86_400_000L,
        )
        for ((d, st) in responses) {
            jdbc.update("INSERT INTO blood_responses (request_id, donor_uid, status, confirmed) VALUES (?, ?, ?, ?)", id, d, st, d in confirmed)
        }
    }

    private fun appointment(id: String, patient: String, doctor: String, slotOffsetMin: Long = -10) {
        val at = Instant.now().plusSeconds(slotOffsetMin * 60).atZone(dhaka)
        jdbc.update(
            "INSERT INTO providers (uid, type, name, listed, active, appointments) VALUES (?, 'doctor', 'ডাক্তার', TRUE, TRUE, TRUE) ON CONFLICT (uid) DO NOTHING",
            doctor,
        )
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, visit_date, start_time, status, serial)
            VALUES (?, ?, ?, 'রোগী', 'ডাক্তার', 'doctor', ?, ?, 'confirmed', 1)
            """.trimIndent(),
            id, patient, doctor, java.sql.Date.valueOf(at.toLocalDate()), at.format(DateTimeFormatter.ofPattern("HH:mm")),
        )
    }

    /** a blood thread a (requester) ↔ b (confirmed donor) */
    private fun pair(a: String = "a", b: String = "b", aName: String = "আলী হাসান", bName: String = "বাবু"): String {
        person(a, aName)
        person(b, bName)
        bloodRequest("r-$a-$b", a, mapOf(b to "accepted"), confirmed = setOf(b))
        return ok(a, "chat_open", mapOf("ctx" to "blood", "refId" to "r-$a-$b", "peerUid" to b))["threadId"].textValue()
    }

    /** a JPEG w×h with an EXIF APP1 segment (orientation [orientation], a fake GPS marker string) */
    private fun jpegWithExif(w: Int, h: Int, orientation: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if (x < w / 2) 0xff0000 else 0x0000ff)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        val plain = out.toByteArray()
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // header, IFD0 at 8
            0, 1, // 1 entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // orientation SHORT
            0, 0, 0, 0, // no next IFD
        ) + "GPS-SECRET-23.7N".toByteArray()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), (len shr 8).toByte(), (len and 0xff).toByte()) + payload
        // like a camera file: SOI, APP1 Exif, then the image (the encoder's JFIF APP0 left out)
        var rest = 2
        if (plain[2] == 0xff.toByte() && plain[3] == 0xe0.toByte()) rest = 4 + (((plain[4].toInt() and 0xff) shl 8) or (plain[5].toInt() and 0xff))
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(rest, plain.size)
    }

    private fun fetch(url: String): MockHttpServletResponse {
        val path = "/api/" + url.substringAfter("/api/")
        return mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response
    }

    // ------------------------------------------------------------------ relations

    @Test
    fun `blood - requester and confirmed donor allowed, strangers and non-donors denied`() {
        for (u in listOf("req", "don", "gone", "stranger")) person(u)
        bloodRequest("r1", "req", mapOf("don" to "accepted", "gone" to "cancelled"), confirmed = setOf("don"))
        assertEquals("chat_not_allowed", open("req", "blood", "r1").code) // the requester names the donor
        assertEquals(403, open("req", "blood", "r1", "gone").status)
        assertEquals(403, open("req", "blood", "r1", "stranger").status)
        assertEquals(403, open("stranger", "blood", "r1").status)
        assertEquals(403, open("gone", "blood", "r1").status)
        assertEquals(403, open("req", "blood", "nope", "don").status)
        val o = ok("req", "chat_open", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "don"))
        assertTrue(o["canSend"].booleanValue())
        assertEquals("don", o["peer"]["uid"].textValue())
        assertNull(o["peer"]["phone"])
        assertEquals(0, o["messages"].size())
        assertTrue(o["before"].isNull)
        // the confirmed donor gets the same thread
        assertEquals(o["threadId"], ok("don", "chat_open", mapOf("ctx" to "blood", "refId" to "r1"))["threadId"])
        // bad input
        assertEquals(400, call("req", "chat_open", mapOf("ctx" to "pizza", "refId" to "r1")).status)
        assertEquals(400, call("req", "chat_open", mapOf("ctx" to "blood")).status)
        assertEquals(401, call(null, "chat_open", mapOf("ctx" to "blood", "refId" to "r1")).status)
    }

    @Test
    fun `donor rule - an unconfirmed donor may write only after the requester wrote first`() {
        person("req")
        person("don")
        bloodRequest("r1", "req", mapOf("don" to "accepted"))
        assertEquals("chat_not_allowed", open("don", "blood", "r1").code)
        val t = ok("req", "chat_open", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "don"))["threadId"].textValue()
        // the thread exists but is empty: the donor still cannot write
        val seen = ok("don", "chat_open", mapOf("ctx" to "blood", "refId" to "r1"))
        assertFalse(seen["canSend"].booleanValue())
        assertNull(seen["closed"])
        assertEquals("chat_not_allowed", send("don", t).code)
        // the requester writes → the donor may answer (ContactHistory), and contact_can agrees
        ok("req", "chat_send", mapOf("threadId" to t, "clientId" to "x1", "text" to "আপনি কি আসতে পারবেন?"))
        assertTrue(ok("don", "chat_open", mapOf("ctx" to "blood", "refId" to "r1"))["canSend"].booleanValue())
        assertEquals(200, send("don", t, "জি, আসছি").status)
        val can = ok("don", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "blood", "refId" to "r1"))))["items"][0]
        assertTrue(can["canMessage"].booleanValue())
    }

    @Test
    fun `appointment - patient and doctor around the slot, later the thread is read-only`() {
        for (u in listOf("pat", "doc", "other")) person(u)
        appointment("ap1", "pat", "doc", slotOffsetMin = -10)
        appointment("ap2", "pat", "doc", slotOffsetMin = 300)
        val t = ok("pat", "chat_open", mapOf("ctx" to "appointment", "refId" to "ap1"))
        assertEquals("doc", t["peer"]["uid"].textValue())
        assertEquals(200, send("doc", t["threadId"].textValue(), "আসুন").status)
        assertEquals(403, open("other", "appointment", "ap1").status)
        assertEquals(403, open("pat", "appointment", "ap2").status) // 5 h ahead: not yet
        // 4 hours later the relation has ended
        chat.clock = { System.currentTimeMillis() + 4 * 3600_000L }
        val r = send("pat", t["threadId"].textValue())
        assertEquals(409, r.status)
        assertEquals("chat_closed", r.code)
        val again = ok("pat", "chat_open", mapOf("ctx" to "appointment", "refId" to "ap1"))
        assertFalse(again["canSend"].booleanValue())
        assertTrue(again["closed"].booleanValue())
        assertEquals(1, again["messages"].size())
    }

    @Test
    fun `ambulance - patient and the accepting driver only`() {
        for (u in listOf("pat", "drv", "drv2")) person(u)
        jdbc.update("INSERT INTO ambulance_requests (id, uid, district, status, active, accepted_by) VALUES ('am1', 'pat', 'ঢাকা', 'accepted', TRUE, 'drv')")
        val t = ok("pat", "chat_open", mapOf("ctx" to "ambulance", "refId" to "am1"))
        assertEquals("drv", t["peer"]["uid"].textValue())
        assertEquals(200, send("drv", t["threadId"].textValue(), "আসছি").status)
        assertEquals(403, open("drv2", "ambulance", "am1").status)
        assertEquals(403, open("pat", "ambulance", "am1", "drv2").status)
        jdbc.update("UPDATE ambulance_requests SET status = 'done', active = FALSE WHERE id = 'am1'")
        assertEquals("chat_closed", send("pat", t["threadId"].textValue()).code)
    }

    @Test
    fun `rx_order and lab_order - patient and the shop or lab of an active order`() {
        for (u in listOf("pat", "shop", "lab", "doc", "other")) person(u)
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES ('shop', 'দোকান', 'L-1', '01711111111', 'ঢাকা', 'approved')")
        jdbc.update("INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES ('lab', 'ল্যাব', 'ঢাকা', 'ঠিকানা', 'D-1', '01711000498', 'approved')")
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status) VALUES ('rx1', 'AB23CD45EF', 'doc', 'pat', 'active')")
        jdbc.update("INSERT INTO rx_orders (id, rx_id, code, patient_uid, pharmacy_uid, mode, status) VALUES ('o1', 'rx1', 'AB23CD45EF', 'pat', 'shop', 'online', 'accepted')")
        jdbc.update("INSERT INTO lab_orders (id, lab_uid, patient_uid, status) VALUES ('l1', 'lab', 'pat', 'received')")
        assertEquals("shop", ok("pat", "chat_open", mapOf("ctx" to "rx_order", "refId" to "o1"))["peer"]["uid"].textValue())
        assertEquals("pat", ok("shop", "chat_open", mapOf("ctx" to "rx_order", "refId" to "o1"))["peer"]["uid"].textValue())
        assertEquals("lab", ok("pat", "chat_open", mapOf("ctx" to "lab_order", "refId" to "l1"))["peer"]["uid"].textValue())
        assertEquals(403, open("pat", "lab_order", "o1").status) // the id of another table
        assertEquals(403, open("other", "rx_order", "o1").status)
        assertEquals(403, open("pat", "rx_order", "o1", "lab").status)
        // suspended shop: the existing thread is read-only (closed), no new thread for the lab order's other ids
        jdbc.update("UPDATE pharmacies SET status = 'suspended'")
        val sus = ok("pat", "chat_open", mapOf("ctx" to "rx_order", "refId" to "o1"))
        assertFalse(sus["canSend"].booleanValue())
        assertTrue(sus["closed"].booleanValue())
        assertEquals("chat_closed", send("pat", sus["threadId"].textValue()).code)
    }

    @Test
    fun `never demo with real, deactivated peer read-only, deactivated caller 423, caregiver refused`() {
        person("req")
        person("demo-d1")
        bloodRequest("r1", "req", mapOf("demo-d1" to "accepted"), confirmed = setOf("demo-d1"))
        assertEquals("chat_not_allowed", open("req", "blood", "r1", "demo-d1").code)
        assertEquals("chat_not_allowed", open("demo-d1", "blood", "r1").code)
        val t = pair("p1", "p2")
        ok("p1", "chat_send", mapOf("threadId" to t, "clientId" to "k1", "text" to "এক"))
        jdbc.update("UPDATE accounts SET deactivated_at = now() WHERE uid = 'p2'")
        accounts.forgetDeactivation("p2")
        assertEquals("chat_not_allowed", send("p1", t).code)
        assertFalse(ok("p1", "chat_open", mapOf("threadId" to t))["canSend"].booleanValue())
        assertEquals(423, call("p2", "chat_threads").status)
        jdbc.update("UPDATE accounts SET deactivated_at = NULL, disabled_reason = 'x' WHERE uid = 'p2'")
        accounts.forgetDeactivation("p2")
        assertEquals("chat_not_allowed", send("p1", t).code)
        person("cg")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('p1', 'cg', 'active')")
        assertEquals(403, call("cg", "chat_threads", mapOf("asUid" to "p1")).status)
    }

    // ------------------------------------------------------------------ block

    @Test
    fun `block - call_blocked for the blocker, chat_not_allowed for the blocked, both ways no sending`() {
        val t = pair()
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "k1", "text" to "এক"))
        blocks.block("a", "b")
        assertEquals("call_blocked", send("a", t).code)
        assertEquals(403, send("a", t).status)
        val r = send("b", t)
        assertEquals(403, r.status)
        assertEquals("chat_not_allowed", r.code)
        val o = ok("a", "chat_open", mapOf("threadId" to t))
        assertFalse(o["canSend"].booleanValue())
        assertTrue(o["blocked"].booleanValue())
        assertNull(ok("b", "chat_open", mapOf("threadId" to t))["blocked"])
        assertFalse(ok("b", "chat_threads")["items"][0]["canSend"].booleanValue())
        // no thread yet and blocked → 403 call_blocked straight away
        bloodRequest("r2", "a", mapOf("b" to "accepted"), confirmed = setOf("b"))
        assertEquals("call_blocked", open("a", "blood", "r2", "b").code)
        blocks.unblock("a", "b")
        assertEquals(200, send("a", t).status)
    }

    // ------------------------------------------------------------------ send: idempotent, limits

    @Test
    fun `send - idempotent per clientId, validation, members only`() {
        val t = pair()
        val m1 = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "same-1", "text" to "  প্রথম  "))["message"]
        assertEquals("প্রথম", m1["text"].textValue())
        assertEquals("a", m1["from"].textValue())
        val m2 = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "same-1", "text" to "প্রথম"))["message"]
        assertEquals(m1["id"], m2["id"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chat_messages", Int::class.java))
        assertEquals(1, pushes("b", "chat_message").size, "a retry sends no second push")
        assertEquals(400, call("a", "chat_send", mapOf("threadId" to t, "text" to "x")).status) // no clientId
        assertEquals(400, call("a", "chat_send", mapOf("threadId" to t, "clientId" to "x2")).status) // nothing to send
        assertEquals(400, call("a", "chat_send", mapOf("threadId" to t, "clientId" to "x3", "text" to "x".repeat(1001))).status)
        assertEquals(400, call("a", "chat_send", mapOf("threadId" to t, "clientId" to "x4", "location" to mapOf("lat" to 99, "lng" to 90))).status)
        assertEquals(400, call("a", "chat_send", mapOf("threadId" to t, "clientId" to "x5", "text" to "x", "location" to mapOf("lat" to 23.7, "lng" to 90.4))).status)
        assertEquals("bad_photo", call("a", "chat_send", mapOf("threadId" to t, "clientId" to "x6", "photo" to "bm90IGEgcGhvdG8=")).code)
        val loc = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "x7", "location" to mapOf("lat" to 23.7123456789, "lng" to 90.4)))["message"]
        assertEquals(23.712346, loc["location"]["lat"].doubleValue())
        assertNull(loc["text"])
        person("c")
        assertEquals(404, send("c", t).status)
        assertEquals(404, call("c", "chat_messages", mapOf("threadId" to t)).status)
        assertEquals("chat_not_allowed", call("c", "chat_open", mapOf("threadId" to t)).code)
    }

    @Test
    fun `limits - 20 in a row before a reply, 30 per minute, 20 photos a day - 429 chat_rate`() {
        val t = pair()
        repeat(20) { assertEquals(200, send("a", t, "m$it").status) }
        val run = send("a", t, "২১")
        assertEquals(429, run.status)
        assertEquals("chat_rate", run.code)
        assertEquals("আরও পাঠানোর আগে উত্তরের অপেক্ষা করুন", run.error)
        assertEquals(200, send("b", t, "উত্তর").status)
        // the reply resets the run; a sent 21 so far this minute (the refused one counted too)
        repeat(9) { assertEquals(200, send("a", t, "n$it").status, "message ${it + 22}") }
        val fast = send("a", t, "বেশি")
        assertEquals(429, fast.status)
        assertEquals("chat_rate", fast.code)
        assertEquals("অনেক মেসেজ পাঠিয়েছেন — এক মিনিট পরে চেষ্টা করুন", fast.error)
        // photos: 20 sent today already (by b) → the 21st is refused
        val now = Timestamp(System.currentTimeMillis())
        repeat(20) {
            jdbc.update(
                "INSERT INTO chat_messages (id, thread_id, from_uid, client_id, kind, thumb, at) VALUES (?, ?, 'b', ?, 'photo', 'x', ?)", "p$it", t, "p$it", now,
            )
        }
        val photo = Base64.getEncoder().encodeToString(jpegWithExif(10, 10, 1))
        val p = send("b", t, null, mapOf("photo" to photo))
        assertEquals(429, p.status)
        assertEquals("chat_rate", p.code)
        assertEquals("দিনে ২০টা ছবি পাঠানো যায়", p.error)
    }

    // ------------------------------------------------------------------ read, unread, threads, paging

    @Test
    fun `read receipts, unread counts, threads list, paging with an opaque cursor`() {
        val t = pair()
        // 55 messages at the same ms (52 from a, 3 from b): paging must still be exact — ordered by (at, id)
        val at = System.currentTimeMillis() - 60_000
        for (i in 1..55) {
            val from = if (i % 15 == 0) "b" else "a"
            jdbc.update(
                "INSERT INTO chat_messages (id, thread_id, from_uid, client_id, kind, text, at) VALUES (?, ?, ?, ?, 'text', ?, ?)",
                "m%03d".format(i), t, from, "c$i", "বার্তা $i", Timestamp(at),
            )
        }
        jdbc.update("UPDATE chat_threads SET last_at = ? WHERE id = ?", Timestamp(at), t)
        val ids = jdbc.queryForList("SELECT id FROM chat_messages ORDER BY at, id", String::class.java)
        val p1 = ok("b", "chat_messages", mapOf("threadId" to t))
        assertEquals(50, p1["messages"].size())
        assertEquals(ids.takeLast(50), p1["messages"].map { it["id"].textValue() })
        assertEquals("a", p1["peer"]["uid"].textValue())
        assertTrue(p1["canSend"].booleanValue())
        assertEquals("blood", p1["ctx"].textValue())
        val p2 = ok("b", "chat_messages", mapOf("threadId" to t, "before" to p1["before"].textValue()))
        assertEquals(ids.take(5), p2["messages"].map { it["id"].textValue() })
        assertTrue(p2["before"].isNull)
        assertEquals(400, call("b", "chat_messages", mapOf("threadId" to t, "before" to "%%%")).status)
        assertEquals(52, ok("b", "chat_unread")["total"].intValue())
        val th = ok("b", "chat_threads")["items"]
        assertEquals(1, th.size())
        assertEquals(52, th[0]["unread"].intValue())
        assertEquals("a", th[0]["peer"]["uid"].textValue())
        assertTrue(th[0]["canSend"].booleanValue())
        assertNotNull(th[0]["last"]["at"])
        // read up to the newest at (only a's messages are marked for b)
        val upTo = at
        assertEquals(0, ok("b", "chat_read", mapOf("threadId" to t, "upTo" to upTo - 1))["read"].intValue())
        assertEquals(52, ok("b", "chat_read", mapOf("threadId" to t, "upTo" to upTo))["read"].intValue())
        assertEquals(0, ok("b", "chat_unread")["total"].intValue())
        val r = pushes("a", "chat_read").single()
        assertEquals(t, r.data["threadId"])
        assertEquals(upTo.toString(), r.data["upTo"])
        assertEquals("b", r.data["by"])
        assertEquals("messages", r.data["cat"])
        assertNotNull(ok("a", "chat_messages", mapOf("threadId" to t))["messages"].first { it["from"].textValue() == "a" }["readAt"])
        assertEquals(400, call("b", "chat_read", mapOf("threadId" to t)).status)
    }

    @Test
    fun `threads list - newest first, previews for photo, location and unsent`() {
        val t1 = pair("a", "b")
        val t2 = pair("a", "c", bName = "চম্পা")
        ok("a", "chat_send", mapOf("threadId" to t1, "clientId" to "1", "location" to mapOf("lat" to 23.7, "lng" to 90.4)))
        Thread.sleep(5)
        val m = ok("a", "chat_send", mapOf("threadId" to t2, "clientId" to "2", "text" to "গোপন কথা"))["message"]
        ok("a", "chat_unsend", mapOf("messageId" to m["id"].textValue()))
        val items = ok("a", "chat_threads")["items"]
        assertEquals(listOf(t2, t1), items.map { it["threadId"].textValue() })
        assertEquals("মেসেজ মুছে ফেলা হয়েছে", items[0]["last"]["text"].textValue())
        assertEquals("📍", items[1]["last"]["text"].textValue())
        assertTrue(items[1]["last"]["mine"].booleanValue())
    }

    // ------------------------------------------------------------------ unsend, report

    @Test
    fun `unsend - own message within 10 minutes, content erased, report copies the content`() {
        val t = pair()
        val m = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "u1", "text" to "ভুল মেসেজ"))["message"]
        val id = m["id"].textValue()
        assertEquals(404, call("b", "chat_unsend", mapOf("messageId" to id)).status) // not b's
        val u = ok("a", "chat_unsend", mapOf("messageId" to id))["message"]
        assertTrue(u["unsent"].booleanValue())
        assertNull(u["text"])
        assertNull(row("SELECT text FROM chat_messages WHERE id = ?", id)["text"])
        assertTrue(ok("b", "chat_messages", mapOf("threadId" to t))["messages"][0]["unsent"].booleanValue())
        assertEquals(200, call("a", "chat_unsend", mapOf("messageId" to id)).status) // again: same answer
        val late = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "u2", "text" to "দেরি"))["message"]["id"].textValue()
        chat.clock = { System.currentTimeMillis() + 11 * 60_000L }
        val r = call("a", "chat_unsend", mapOf("messageId" to late))
        assertEquals(409, r.status)
        assertEquals("chat_unsend_window", r.code)
        // report: the peer's message only, content copied
        assertEquals(404, call("a", "chat_report", mapOf("messageId" to late, "reason" to "spam")).status)
        assertEquals(400, call("b", "chat_report", mapOf("messageId" to late, "reason" to "nope")).status)
        ok("b", "chat_report", mapOf("messageId" to late, "reason" to "harassment", "note" to "বিরক্ত করছে"))
        ok("b", "chat_report", mapOf("messageId" to late, "reason" to "spam"))
        val rep = row("SELECT reason, reported_uid, message_meta::text AS meta FROM chat_reports")
        assertEquals("spam", rep["reason"])
        assertEquals("a", rep["reported_uid"])
        assertTrue((rep["meta"] as String).contains("দেরি"))
        // the team sees it
        user("owner1", emptyMap(), email = "owner@example.com")
        val items = ok("owner1", "admin_chat_reports")["items"]
        assertEquals(1, items.size())
        assertEquals("দেরি", items[0]["message"]["text"].textValue())
        ok("owner1", "admin_chat_report_resolve", mapOf("id" to items[0]["id"].textValue(), "status" to "resolved"))
        assertEquals(0, ok("owner1", "admin_chat_reports")["items"].size())
        assertEquals(403, call("b", "admin_chat_reports").status)
    }

    // ------------------------------------------------------------------ read-only, retention, account deletion

    @Test
    fun `relation ends - read-only (409 chat_closed), daily job closes untouched threads and deletes them 90 days later`() {
        val t = pair()
        val t2 = pair("a", "c")
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "1", "text" to "এক"))
        val photo = Base64.getEncoder().encodeToString(jpegWithExif(30, 20, 1))
        ok("b", "chat_send", mapOf("threadId" to t, "clientId" to "2", "photo" to photo))
        ok("a", "chat_send", mapOf("threadId" to t2, "clientId" to "3", "text" to "দুই"))
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = 'r-a-b'")
        val now = System.currentTimeMillis()
        val out = daily.runAll(now)["chatDeleted"] as Map<*, *>
        assertEquals(1, out["closed"])
        assertEquals(0, out["deleted"])
        assertNotNull(row("SELECT closed_at FROM chat_threads WHERE id = ?", t)["closed_at"])
        assertNull(row("SELECT closed_at FROM chat_threads WHERE id = ?", t2)["closed_at"])
        assertEquals("chat_closed", send("a", t).code)
        assertFalse(ok("a", "chat_threads")["items"].first { it["threadId"].textValue() == t }["canSend"].booleanValue())
        // still readable
        assertEquals(2, ok("b", "chat_messages", mapOf("threadId" to t))["messages"].size())
        // 89 days: kept; 91 days: gone with all messages
        assertEquals(0, (daily.runAll(now + 89 * 86_400_000L)["chatDeleted"] as Map<*, *>)["deleted"])
        assertEquals(1, (daily.runAll(now + 91 * 86_400_000L)["chatDeleted"] as Map<*, *>)["deleted"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_threads WHERE id = ?", Int::class.java, t))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE thread_id = ?", Int::class.java, t))
    }

    @Test
    fun `account deletion - every thread of the person goes, both sides`() {
        val t = pair()
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "1", "text" to "এক"))
        ok("b", "chat_send", mapOf("threadId" to t, "clientId" to "2", "text" to "দুই"))
        deletion.deleteNow("a", "a@example.com")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_threads", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_messages", Int::class.java))
        assertEquals(0, ok("b", "chat_threads")["items"].size())
        assertEquals(0, ok("b", "chat_unread")["total"].intValue())
    }

    // ------------------------------------------------------------------ photos

    @Test
    fun `photo - EXIF stripped and turned upright, signed URL for members only, expiry and tampering refused`() {
        val t = pair()
        val photo = Base64.getEncoder().encodeToString(jpegWithExif(40, 20, 6))
        val m = ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "ph1", "photo" to photo, "text" to "রিপোর্ট"))["message"]
        assertEquals("রিপোর্ট", m["text"].textValue())
        val url = m["photoUrl"].textValue()
        assertTrue(url.contains("/api/chat/photo/${m["id"].textValue()}?e="), url)
        // inline thumbnail: a small JPEG
        val thumb = ImageIO.read(Base64.getDecoder().decode(m["photoThumb"].textValue()).inputStream())
        assertTrue(maxOf(thumb.width, thumb.height) <= 160)
        // the URL works without a login, gives a new JPEG: rotated (40×20 + orientation 6 → 20×40), no EXIF / GPS text
        val res = fetch(url)
        assertEquals(200, res.status)
        assertEquals("image/jpeg", res.contentType)
        val bytes = res.contentAsByteArray
        val img = ImageIO.read(bytes.inputStream())
        assertEquals(20, img.width)
        assertEquals(40, img.height)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Exif"), "no EXIF segment")
        assertFalse(text.contains("GPS-SECRET"), "no hidden data")
        assertTrue(res.getHeader("Cache-Control")!!.contains("private"))
        // the peer sees the same photo; a stranger never gets the URL (404 / 403 on the thread)
        val forB = ok("b", "chat_messages", mapOf("threadId" to t))["messages"][0]["photoUrl"].textValue()
        assertArrayEquals(bytes, fetch(forB).contentAsByteArray)
        person("c")
        assertEquals(404, call("c", "chat_messages", mapOf("threadId" to t)).status)
        // tampered signature, another message's signature, expired
        val id = m["id"].textValue()
        assertEquals(403, fetch(url.replace(Regex("s=.*$"), "s=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).status)
        assertEquals(403, fetch("/api/chat/photo/$id").status)
        val past = System.currentTimeMillis() / 1000 - 10
        assertEquals(403, fetch("/api/chat/photo/$id?e=$past&s=${photos.sign(id, past)}").status)
        val far = System.currentTimeMillis() / 1000 + 30 * 86400
        assertEquals(403, fetch("/api/chat/photo/$id?e=$far&s=${photos.sign(id, far)}").status, "no long-lived links")
        // unsent → the link stops working
        ok("a", "chat_unsend", mapOf("messageId" to id))
        assertEquals(404, fetch(url).status)
        assertNull(row("SELECT photo_data FROM chat_messages WHERE id = ?", id)["photo_data"])
    }

    // ------------------------------------------------------------------ push / SSE / category

    @Test
    fun `push - generic lock-screen text with the first name only, collapsed per thread, never the content, category off = no push`() {
        val t = pair(aName = "আলী হাসান")
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "1", "text" to "আমার রক্তের গ্রুপ A+, ঠিকানা ১২ নম্বর বাড়ি"))
        val p = pushes("b", "chat_message").single()
        assertEquals("messages", p.data["cat"])
        assertEquals(t, p.data["threadId"])
        assertNotNull(p.data["messageId"])
        assertEquals("আলী", p.data["from"])
        assertEquals("লাইফলো মেসেজ — আলী", p.data["title"])
        assertEquals("", p.data["body"])
        assertEquals("chat_$t", p.collapseKey)
        assertFalse(p.data.values.any { it.contains("ঠিকানা") || it.contains("A+") }, "no message text in the push: ${p.data}")
        assertFalse(p.data.containsKey("ctx") || p.data.containsKey("refId"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_items WHERE uid = 'b'", Int::class.java), "no bell entry")
        assertTrue(pushes("a", "chat_message").isEmpty())
        // category messages: on by default, settable; off → the message still arrives, without a push
        assertTrue(ok("b", "notify_prefs_get")["cats"]["messages"].booleanValue())
        ok("b", "notify_prefs_set", mapOf("cats" to mapOf("messages" to false)))
        push.messages.clear()
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "2", "text" to "আবার"))
        assertTrue(pushes("b", "chat_message").isEmpty())
        assertEquals(2, ok("b", "chat_unread")["total"].intValue())
    }

    // ------------------------------------------------------------------ feature switches (chat, chat_media)

    @Test
    fun `feature chat off - 404 feature_off for the person, peer without it cannot be written to, no push, contact_can false`() {
        val t = pair()
        ok("a", "chat_send", mapOf("threadId" to t, "clientId" to "1", "text" to "এক"))
        push.messages.clear()
        features.override = { uid, key -> if (uid == "b" && key == "chat") false else null }
        for (a in listOf("chat_threads", "chat_unread", "chat_messages", "chat_open", "chat_send", "chat_read", "chat_unsend", "chat_report")) {
            val r = call("b", a, mapOf("threadId" to t))
            assertEquals(404, r.status, "$a → $r")
            assertEquals("feature_off", r.code)
            assertEquals("এই সুবিধা এখনো চালু হয়নি", r.error)
        }
        // a still has the feature, b does not: read-only for a, nothing reaches b
        assertEquals("chat_not_allowed", send("a", t).code)
        assertFalse(ok("a", "chat_open", mapOf("threadId" to t))["canSend"].booleanValue())
        assertTrue(pushes("b", "chat_message").isEmpty())
        // no new thread with someone who does not have it
        person("c")
        bloodRequest("r-a-c", "a", mapOf("c" to "accepted"), confirmed = setOf("c"))
        features.override = { uid, key -> if (uid == "c" && key == "chat") false else null }
        assertEquals("chat_not_allowed", open("a", "blood", "r-a-c", "c").code)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_threads WHERE ref_id = 'r-a-c'", Int::class.java))
        // everything off (the production default: the shared switch, which contact_can reads too)
        features.override = null
        assertTrue(ok("a", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "blood", "refId" to "r-a-b", "peerUid" to "b"))))["items"][0]["canMessage"].booleanValue())
        callFeatures.override = emptySet()
        assertEquals("feature_off", call("a", "chat_unread").code)
        assertFalse(ok("a", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "blood", "refId" to "r-a-b", "peerUid" to "b"))))["items"][0]["canMessage"].booleanValue())
        // back on: works again
        callFeatures.override = null
        assertEquals(200, send("a", t).status)
        assertEquals(1, pushes("b", "chat_message").size)
    }

    @Test
    fun `feature chat_media off - photos and locations refused with feature_off for either side, text still works`() {
        val t = pair()
        val photo = Base64.getEncoder().encodeToString(jpegWithExif(20, 20, 1))
        features.override = { uid, key -> if (uid == "a" && key == "chat_media") false else null }
        assertEquals("feature_off", send("a", t, null, mapOf("photo" to photo)).code)
        assertEquals("feature_off", send("a", t, null, mapOf("location" to mapOf("lat" to 23.7, "lng" to 90.4))).code)
        assertEquals("feature_off", send("b", t, null, mapOf("photo" to photo)).code, "the peer cannot receive media")
        assertEquals(200, send("a", t, "লেখা চলবে").status)
        // chat_media needs chat: chat off → media off too
        features.override = { uid, key -> if (uid == "a" && key == "chat") false else null }
        assertFalse(features.isOn("a", "chat_media"))
        features.override = null
        assertEquals(200, send("a", t, null, mapOf("photo" to photo)).status)
    }
}
