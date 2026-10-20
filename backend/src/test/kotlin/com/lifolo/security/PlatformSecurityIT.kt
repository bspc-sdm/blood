package com.lifolo.security

import com.lifolo.common.ApiErrorHandler
import com.lifolo.common.Places
import com.lifolo.notify.SseController
import com.lifolo.notify.SseHub
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.sql.Timestamp

/**
 * Everything around the actions: ops endpoints (CRON_SECRET), the live stream, share pages, headers, CORS, error
 * bodies, rate limits (login codes, demo login, per account), injection strings and size limits.
 */
class PlatformSecurityIT : IntegrationTest() {
    @Autowired
    lateinit var hub: SseHub

    private val dist: String get() = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn

    // ------------------------------------------------------------------ CRON_SECRET endpoints

    @Test
    fun `ops endpoints need the exact CRON_SECRET`() {
        val bad = listOf(
            emptyMap(),
            mapOf("Authorization" to "Bearer "),
            mapOf("Authorization" to "Bearer wrong"),
            mapOf("Authorization" to "Bearer test-cron-secret "),
            mapOf("Authorization" to "bearer test-cron-secret"),
            mapOf("Authorization" to "test-cron-secret"),
            mapOf("Authorization" to "Bearer test-cron-secre"),
        )
        for (h in bad) {
            assertEquals(401, get("/api/cron/daily", h).status, h.toString())
            assertEquals(401, get("/api/cron/storage", h).status, h.toString())
            assertEquals(401, get("/api/cron/storage/probe", h).status, h.toString())
            assertEquals(401, post("/api/cron/storage/migrate", emptyMap<String, Any>(), h).status, h.toString())
            assertEquals(401, post("/api/cron/sentry-test", emptyMap<String, Any>(), h).status, h.toString())
        }
        // a logged-in user's token is not the secret
        user("cronUser")
        assertEquals(401, get("/api/cron/daily", mapOf("Authorization" to "Bearer cronUser")).status)
        assertEquals(200, get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret")).status)
        assertEquals(200, get("/api/cron/storage", mapOf("Authorization" to "Bearer test-cron-secret")).status)
    }

    // ------------------------------------------------------------------ SSE

    @Test
    fun `live stream - login required, own topic only, at most a few streams per account`() {
        val anon = mvc.perform(MockMvcRequestBuilders.get("/api/events")).andReturn().response
        assertTrue(anon.status in 400..499, "status ${anon.status}")
        val bad = mvc.perform(MockMvcRequestBuilders.get("/api/events").header("Authorization", "Bearer nobody")).andReturn().response
        assertTrue(bad.status in 400..499, "status ${bad.status}")
        user("sseCap")
        // the topic is the caller's; a uid in the query changes nothing
        repeat(7) {
            mvc.perform(MockMvcRequestBuilders.get("/api/events?uid=other&topic=user:other").header("Authorization", "Bearer sseCap")).andReturn()
        }
        assertEquals(SseController.MAX_STREAMS_PER_USER, hub.subscribers("user:sseCap"))
        assertEquals(0, hub.subscribers("user:other"))
    }

    @Test
    fun `the hub keeps at most maxStreams per topic, oldest closed first`() {
        val h = SseHub(0)
        try {
            val first = h.subscribe("t", maxStreams = 2)
            h.subscribe("t", maxStreams = 2)
            h.subscribe("t", maxStreams = 2)
            assertEquals(2, h.subscribers("t"))
            var failed = false
            try {
                first.send("x")
            } catch (e: Exception) {
                failed = true
            }
            assertTrue(failed, "the oldest stream was completed")
            // without a cap (other topics): unlimited
            repeat(10) { h.subscribe("u") }
            assertEquals(10, h.subscribers("u"))
        } finally {
            h.stop()
        }
    }

    // ------------------------------------------------------------------ share pages

    @Test
    fun `prescription and shop pages show what they must and nothing more`() {
        user("shDoc", mapOf("name" to "ডা. শেয়ার"))
        user("shPat", mapOf("name" to "শেয়ার রোগী", "phone" to "01711000061"))
        jdbc.update(
            """
            INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status, patient_name, patient_age, diagnosis, complaints, advice,
              doctor_name, doctor_chamber, doctor_chamber_phone, created_at)
            VALUES ('shRx', 'ABCDEFGH23', 'shDoc', 'shPat', 'active', '<b>শেয়ার রোগী</b>', 40, 'গোপন রোগনির্ণয়', 'গোপন উপসর্গ', 'গোপন পরামর্শ',
              'ডা. শেয়ার', 'সিটি হাসপাতাল', '01711000062', ?)
            """.trimIndent(),
            Timestamp(System.currentTimeMillis()),
        )
        val rx = mvc.perform(MockMvcRequestBuilders.get("/rx/ABCDEFGH23")).andReturn().response
        assertEquals(200, rx.status)
        val html = rx.getContentAsString(Charsets.UTF_8)
        assertTrue(html.contains("&lt;b&gt;শেয়ার রোগী&lt;/b&gt;"), "the name is shown, escaped")
        for (s in listOf("01711000061", "01711000062", "গোপন", "shPat", "shDoc", "<b>")) assertFalse(html.contains(s), s)
        assertEquals("no-store", rx.getHeader("Cache-Control"))
        assertEquals("noindex, nofollow", rx.getHeader("X-Robots-Tag"))
        assertEquals(404, mvc.perform(MockMvcRequestBuilders.get("/rx/ABCDEFGH24")).andReturn().response.status)
        assertEquals(404, mvc.perform(MockMvcRequestBuilders.get("/rx/' OR 1=1")).andReturn().response.status)

        user("shShop")
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, address, status, shop_code)
            VALUES ('shShop', 'শেয়ার ফার্মেসি', 'DL-SECRET-9', '01711000063', ?, 'গোপন ঠিকানা', 'approved', 'ABCDEF')
            """.trimIndent(),
            dist,
        )
        val shop = mvc.perform(MockMvcRequestBuilders.get("/shop/ABCDEF")).andReturn().response
        assertEquals(200, shop.status)
        val sh = shop.getContentAsString(Charsets.UTF_8)
        assertTrue(sh.contains("শেয়ার ফার্মেসি"))
        for (s in listOf("01711000063", "গোপন ঠিকানা", "DL-SECRET-9", "shShop")) assertFalse(sh.contains(s), s)
        // a suspended shop has no page
        jdbc.update("UPDATE pharmacies SET status = 'suspended' WHERE uid = 'shShop'")
        assertEquals(404, mvc.perform(MockMvcRequestBuilders.get("/shop/ABCDEF")).andReturn().response.status)
    }

    @Test
    fun `app links - the debug and the release signing keys`() {
        val a = get("/.well-known/assetlinks.json").body[0]["target"]["sha256_cert_fingerprints"].map { it.asText() }
        assertEquals(
            listOf(
                "FC:BE:F9:19:39:40:5B:C2:32:72:18:DB:DA:CD:DD:BE:86:47:F6:4B:F9:0A:06:F7:37:AC:42:99:D2:F0:F8:39",
                "07:6E:13:D0:F9:0A:22:81:86:AE:53:15:54:55:FD:C6:A0:48:72:9B:75:8E:DA:AF:52:32:A3:F9:2C:19:69:43",
            ),
            a,
        )
    }

    // ------------------------------------------------------------------ headers, CORS, errors

    @Test
    fun `security headers on the API and the pages, no CORS for other sites`() {
        val api = mvc.perform(
            MockMvcRequestBuilders.post("/api/community").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "https://evil.example").content("""{"action":"catalog_get"}"""),
        ).andReturn().response
        assertEquals(200, api.status)
        assertEquals("nosniff", api.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", api.getHeader("X-Frame-Options"))
        assertTrue(api.getHeader("Cache-Control")!!.contains("no-store"), api.getHeader("Cache-Control"))
        assertNull(api.getHeader("Access-Control-Allow-Origin"))
        assertNull(api.getHeader("Access-Control-Allow-Credentials"))
        val pre = mvc.perform(
            MockMvcRequestBuilders.options("/api/community").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"),
        ).andReturn().response
        assertNull(pre.getHeader("Access-Control-Allow-Origin"), "preflight from another site is not allowed")
        val page = mvc.perform(MockMvcRequestBuilders.get("/r/nope")).andReturn().response
        assertEquals("nosniff", page.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", page.getHeader("X-Frame-Options"))
        // OpenAPI docs and actuator details are not part of the public surface
        assertTrue(get("/actuator/env").status in 400..499)
    }

    @Test
    fun `errors never carry stack traces or internals`() {
        val broken = mvc.perform(
            MockMvcRequestBuilders.post("/api/community").contentType(MediaType.APPLICATION_JSON).content("""{"action": "questions", """),
        ).andReturn().response
        assertEquals(400, broken.status)
        val text = broken.getContentAsString(Charsets.UTF_8)
        for (s in listOf("Exception", "jackson", "at com.", "line:", "column")) assertFalse(text.contains(s), "$s in $text")
        val xml = mvc.perform(MockMvcRequestBuilders.post("/api/community").contentType(MediaType.APPLICATION_XML).content("<a/>")).andReturn().response
        assertTrue(xml.status in 400..499, "status ${xml.status}")
        assertFalse(xml.getContentAsString(Charsets.UTF_8).contains("Exception"))
        // an unexpected failure: the generic message only (and the details only in the log / scrubbed Sentry)
        val req = MockHttpServletRequest("POST", "/api/community")
        val r = ApiErrorHandler().other(IllegalStateException("password=hunter2 at row 01711000001"), req)
        assertEquals(500, r.statusCode.value())
        assertEquals(ApiErrorHandler.body(500, "", null, null), r.body)
        assertFalse(r.body.toString().contains("hunter2") || r.body.toString().contains("01711000001"))
        // a cursor far outside the timestamp range is no cursor (it used to be a database error → 500), also for guests
        assertEquals(200, call(null, "questions", mapOf("before" to 1e300)).status)
        assertEquals(200, call(null, "tips", mapOf("before" to 9007199254740993L)).status)
        assertEquals(405, get("/api/community").status)
        assertFalse(get("/api/community").body.toString().contains("Exception"))
    }

    // ------------------------------------------------------------------ rate limits

    @Test
    fun `one-click demo login is limited per address`() {
        repeat(30) { assertEquals(404, post("/api/auth/verify-code", mapOf("demo" to "patient"), mapOf("X-Forwarded-For" to "7.7.7.7")).status) }
        val r = post("/api/auth/verify-code", mapOf("demo" to "patient", "lang" to "en"), mapOf("X-Forwarded-For" to "7.7.7.7"))
        assertEquals(429, r.status, r.toString())
        assertEquals("Too many requests. Wait a minute and try again.", r.error)
        assertEquals(404, post("/api/auth/verify-code", mapOf("demo" to "patient"), mapOf("X-Forwarded-For" to "7.7.7.8")).status)
        // the role list stays free
        assertEquals(200, post("/api/auth/verify-code", mapOf("demoRoles" to true), mapOf("X-Forwarded-For" to "7.7.7.7")).status)
    }

    @Test
    fun `logged-in calls are limited per account and address`() {
        user("rlUser")
        val h = mapOf("Authorization" to "Bearer rlUser", "X-Forwarded-For" to "6.6.6.6")
        repeat(600) { assertEquals(400, post("/api/community", mapOf("action" to "__nope__"), h).status) }
        val r = post("/api/community", mapOf("action" to "me", "lang" to "en"), h)
        assertEquals(429, r.status, r.toString())
        // another address of the same account, and another account, are not affected
        assertEquals(200, post("/api/community", mapOf("action" to "me"), mapOf("Authorization" to "Bearer rlUser", "X-Forwarded-For" to "6.6.6.7")).status)
        user("rlOther")
        assertEquals(200, post("/api/community", mapOf("action" to "me"), mapOf("Authorization" to "Bearer rlOther", "X-Forwarded-For" to "6.6.6.6")).status)
    }

    @Test
    fun `login codes - per address and per email limits, 6 digits, 5 tries per code`() {
        // one address asking for many emails stops at 50 a day
        repeat(50) { i -> assertEquals(200, post("/api/auth/send-code", mapOf("email" to "rl$i@example.com"), mapOf("X-Forwarded-For" to "8.8.4.4")).status) }
        assertEquals(429, post("/api/auth/send-code", mapOf("email" to "rl99@example.com"), mapOf("X-Forwarded-For" to "8.8.4.4")).status)
        // a code dies after 5 wrong tries even if the 6th is right
        val rid = post("/api/auth/send-code", mapOf("email" to "victim@example.com"), mapOf("X-Forwarded-For" to "8.8.4.5")).body["rid"].asText()
        val code = mail.lastCode("victim@example.com")
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(5) { assertEquals(400, post("/api/auth/verify-code", mapOf("email" to "victim@example.com", "code" to wrong, "rids" to listOf(rid))).status) }
        assertEquals(400, post("/api/auth/verify-code", mapOf("email" to "victim@example.com", "code" to code, "rids" to listOf(rid))).status)
        // someone without the rid cannot burn the owner's code with wrong guesses (they count on their own codes)
        val rid2 = post("/api/auth/send-code", mapOf("email" to "victim2@example.com"), mapOf("X-Forwarded-For" to "8.8.4.6")).body["rid"].asText()
        val code2 = mail.lastCode("victim2@example.com")
        val wrong2 = if (code2 == "000000") "111111" else "000000"
        repeat(6) { post("/api/auth/verify-code", mapOf("email" to "victim2@example.com", "code" to wrong2, "rids" to listOf("0".repeat(32)))) }
        assertEquals(200, post("/api/auth/verify-code", mapOf("email" to "victim2@example.com", "code" to code2, "rids" to listOf(rid2))).status)
    }

    // ------------------------------------------------------------------ injection and size

    @Test
    fun `injection strings are just text, oversized input is refused`() {
        user("injUser", mapOf("name" to "ইনজেকশন"))
        for (q in listOf("' OR '1'='1", "%' OR 1=1 --", "\\'; DROP TABLE accounts; --", "%%%%", "_")) {
            assertTrue(call(null, "drug_search", mapOf("q" to q)).status < 500, q)
            assertTrue(call("injUser", "rx_get", mapOf("id" to q)).status in 400..404, q)
            assertTrue(call("injUser", "pharmacies", mapOf("district" to dist, "q" to q)).status < 500, q)
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE uid = 'injUser'", Long::class.java))
        // stored as text, returned as text
        ok("injUser", "profile_update", mapOf("set" to mapOf("name" to "Robert'); DROP TABLE accounts;--")))
        assertEquals("Robert'); DROP TABLE accounts;--", call("injUser", "profile_get").body["profile"]["name"].asText())
        // sizes
        assertEquals(400, call("injUser", "profile_update", mapOf("set" to mapOf("photo" to "A".repeat(600 * 1024 + 4)))).status)
        assertEquals(400, call("injUser", "profile_update", mapOf("set" to mapOf("photo" to "<svg onload=alert(1)>"))).status)
        assertEquals(400, call("injUser", "profile_update", mapOf("set" to mapOf("name" to "x".repeat(101)))).status)
        assertEquals(400, call("injUser", "ask", mapOf("text" to "x".repeat(1001))).status)
        assertEquals(400, call("injUser", "ticket_create", mapOf("category" to "other", "text" to "x".repeat(5000))).status)
        assertEquals(400, call("injUser", "family_set", mapOf("family" to (1..7).map { mapOf("name" to "স$it") })).status)
        // listing document photos: JPEG/PNG base64 only
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val gif = "R0lGODlhAQABAAAAACw="
        assertTrue(com.lifolo.admin.kernel.VerifySpec.cleanB64(png) != null)
        assertNull(com.lifolo.admin.kernel.VerifySpec.cleanB64(gif))
        assertNull(com.lifolo.admin.kernel.VerifySpec.cleanB64("PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+"))
        assertNull(com.lifolo.admin.kernel.VerifySpec.cleanB64("A".repeat(700 * 1024 + 4)))
    }

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action: $r")
    }
}
