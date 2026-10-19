package com.lifolo.auth

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** api/auth/send-code + verify-code (email login, web admin login, demo login, deletion cancel on login). */
class AuthIT : IntegrationTest() {
    private fun send(email: Any?, lang: String? = "bn", ip: String = "1.1.1.1") =
        post("/api/auth/send-code", mapOf("email" to email, "lang" to lang), mapOf("X-Forwarded-For" to ip))

    private fun verify(body: Map<String, Any?>) = post("/api/auth/verify-code", body)

    @Test
    fun `send-code validates the email`() {
        assertEquals(400, send("not-an-email").status)
        assertEquals("সঠিক ইমেইল দিন", send("not-an-email").error)
        assertEquals("Enter a valid email", send("x", lang = "en").error)
        assertEquals(405, get("/api/auth/send-code").status)
    }

    @Test
    fun `email code login creates the account and returns a custom token`() {
        val r = send("New.User@Example.com")
        assertEquals(200, r.status, r.toString())
        val rid = r.body["rid"].textValue()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(rid))
        val code = mail.lastCode("new.user@example.com")
        assertTrue(mail.sent.last().subject.contains(code))

        // a second code within a minute is refused
        assertEquals(429, send("new.user@example.com").status)

        val wrong = if (code == "000000") "111111" else "000000"
        val bad = verify(mapOf("email" to "new.user@example.com", "code" to wrong, "rids" to listOf(rid)))
        assertEquals(400, bad.status)
        assertEquals("কোড মেলেনি। আবার দেখে লিখুন।", bad.error)

        val ok = verify(mapOf("email" to "new.user@example.com", "code" to code, "rids" to listOf(rid)))
        assertEquals(200, ok.status, ok.toString())
        val uid = identity.users.values.first { it.email == "new.user@example.com" }.uid
        assertEquals("ctok_$uid", ok.body["token"].textValue())
        assertFalse(ok.body["restored"].booleanValue())
        assertEquals("new.user@example.com", row("SELECT email FROM accounts WHERE uid = ?", uid)["email"])
        // a used code is gone
        assertEquals(400, verify(mapOf("email" to "new.user@example.com", "code" to code, "rids" to listOf(rid))).status)
    }

    @Test
    fun `wrong codes burn only the caller's code (rid) after 5 tries`() {
        val rid = send("a@b.com").body["rid"].textValue()
        val code = mail.lastCode("a@b.com")
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(5) { assertEquals("কোড মেলেনি। আবার দেখে লিখুন।", verify(mapOf("email" to "a@b.com", "code" to wrong, "rids" to listOf(rid))).error) }
        val dead = verify(mapOf("email" to "a@b.com", "code" to code, "rids" to listOf(rid)))
        assertEquals(400, dead.status)
        assertEquals("কোডের মেয়াদ শেষ বা অনেকবার ভুল হয়েছে। নতুন কোড চান।", dead.error)
    }

    @Test
    fun `old apps without rid - 10 wrong codes a day`() {
        send("c@d.com")
        val code = mail.lastCode("c@d.com")
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(10) { assertEquals(400, verify(mapOf("email" to "c@d.com", "code" to wrong)).status) }
        val blocked = verify(mapOf("email" to "c@d.com", "code" to code, "lang" to "en"))
        assertEquals(429, blocked.status)
        assertEquals("Too many wrong codes today. Try again tomorrow.", blocked.error)
    }

    @Test
    fun `code format and missing codes`() {
        assertEquals("৬ সংখ্যার কোড দিন", verify(mapOf("email" to "e@f.com", "code" to "12")).error)
        assertEquals("কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।", verify(mapOf("email" to "e@f.com", "code" to "123456")).error)
        // a numeric code works like String(code)
        send("e@f.com")
        val code = mail.lastCode("e@f.com")
        if (!code.startsWith("0")) assertEquals(200, verify(mapOf("email" to "e@f.com", "code" to code.toInt())).status)
    }

    @Test
    fun `email failure lets the user ask again at once`() {
        mail.fail = true
        val r = send("g@h.com")
        assertEquals(502, r.status)
        mail.fail = false
        assertEquals(200, send("g@h.com").status)
    }

    @Test
    fun `per-IP daily cap`() {
        repeat(50) { i -> assertEquals(200, send("ip$i@x.com", ip = "9.9.9.9").status) }
        val r = send("ip50@x.com", ip = "9.9.9.9")
        assertEquals(429, r.status)
        assertEquals("আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।", r.error)
        assertEquals(200, send("ip50@x.com", ip = "8.8.8.8").status)
    }

    @Test
    fun `disabled account gets the reason`() {
        identity.add("dis1", "dis@x.com").disabled = true
        jdbc.update("INSERT INTO accounts (uid, email, disabled_reason) VALUES ('dis1', 'dis@x.com', 'ভুয়া তথ্য')")
        send("dis@x.com")
        val r = verify(mapOf("email" to "dis@x.com", "code" to mail.lastCode("dis@x.com"), "lang" to "en"))
        assertEquals(403, r.status)
        assertEquals("account_disabled", r.code)
        assertEquals("Your account was suspended by the Lifolo team. Reason: ভুয়া তথ্য. To appeal, email ekapkbd@gmail.com", r.error)
    }

    @Test
    fun `logging in again cancels a pending deletion and restores listings`() {
        identity.add("del1", "del@x.com")
        jdbc.update("INSERT INTO accounts (uid, email, delete_at, delete_requested_at) VALUES ('del1', 'del@x.com', now() + interval '29 days', now())")
        jdbc.update(
            "INSERT INTO pharmacies (uid, name, license_no, phone, district, status, hidden_for_deletion) VALUES ('del1', 'ফার্মেসি', 'L1', '01711000000', 'ঢাকা', 'suspended', 'approved')",
        )
        jdbc.update(
            "INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, district, status, hidden_for_deletion) VALUES ('a1', 'del1', 'অ্যাম্বুলেন্স', '017', 'V1', 'ঢাকা', 'rejected', 'pending')",
        )
        jdbc.update("INSERT INTO providers (uid, type, listed, appointments, admin_suspended, suspend_reason, listed_before_suspend) VALUES ('del1', 'doctor', false, true, true, 'deletion', true)")
        send("del@x.com")
        val r = verify(mapOf("email" to "del@x.com", "code" to mail.lastCode("del@x.com")))
        assertEquals(200, r.status, r.toString())
        assertTrue(r.body["restored"].booleanValue())
        assertNull(row("SELECT delete_at FROM accounts WHERE uid = 'del1'")["delete_at"])
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = 'del1'")["status"])
        // an admin changed the ambulance meanwhile: status stays, only the marker goes
        val amb = row("SELECT status, hidden_for_deletion FROM ambulances WHERE id = 'a1'")
        assertEquals("rejected", amb["status"])
        assertNull(amb["hidden_for_deletion"])
        val p = row("SELECT listed, active, admin_suspended FROM providers WHERE uid = 'del1'")
        assertEquals(true, p["listed"])
        assertEquals(true, p["active"])
        assertEquals(false, p["admin_suspended"])
    }

    @Test
    fun `web panel login - admins only, cookie session`() {
        send("someone@x.com")
        val no = verify(mapOf("email" to "someone@x.com", "code" to mail.lastCode("someone@x.com"), "web" to true))
        assertEquals(403, no.status)
        assertEquals("এই ইমেইল অ্যাডমিন হিসেবে যোগ করা নেই", no.error)

        send("owner@example.com")
        val ok = verify(mapOf("email" to "owner@example.com", "code" to mail.lastCode("owner@example.com"), "web" to true))
        assertEquals(200, ok.status, ok.toString())
        val cookie = ok.response.getHeader("Set-Cookie")!!.split(';')[0]
        assertTrue(cookie.startsWith("__Host-lf_admin="))
        assertNull(ok.body.get("token"))

        val h = mapOf("Cookie" to cookie, WebSession.CSRF_HEADER to "1")
        // only admin_* (and a few lists) through the cookie
        val notPanel = post("/api/community", mapOf("action" to "profile_get", "lang" to "en"), h)
        assertEquals(403, notPanel.status)
        assertEquals("Not available on the web panel", notPanel.error)
        // admin action not ported yet → passes the panel checks, then "unknown"
        assertEquals(400, post("/api/community", mapOf("action" to "admin_overview", "lang" to "en"), h).status)
        // without the CSRF header → session_expired
        val noCsrf = post("/api/community", mapOf("action" to "admin_overview"), mapOf("Cookie" to cookie))
        assertEquals(401, noCsrf.status)
        assertEquals("session_expired", noCsrf.code)
        // logout clears the cookie
        val out = post("/api/community", mapOf("action" to "web_logout"), h)
        assertEquals(200, out.status)
        assertTrue(out.response.getHeader("Set-Cookie")!!.contains("Max-Age=0"))
    }

    @Test
    fun `web panel closes for removed admins`() {
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('helper@x.com')")
        send("helper@x.com")
        val ok = verify(mapOf("email" to "helper@x.com", "code" to mail.lastCode("helper@x.com"), "web" to true))
        assertEquals(200, ok.status, ok.toString())
        val cookie = ok.response.getHeader("Set-Cookie")!!.split(';')[0]
        val h = mapOf("Cookie" to cookie, WebSession.CSRF_HEADER to "1")
        assertEquals(400, post("/api/community", mapOf("action" to "admin_x"), h).status)
        // removed from admins (cache evicted like admin_remove does)
        jdbc.update("DELETE FROM admin_emails")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        val r = post("/api/community", mapOf("action" to "admin_x", "lang" to "en"), h)
        assertEquals(403, r.status)
        assertEquals("session_expired", r.code)
        assertEquals("Admins only", r.error)
    }

    @Test
    fun `demo login - role list and one-click token only while a demo exists`() {
        val off = verify(mapOf("demoRoles" to true))
        assertEquals(false, off.body["active"].booleanValue())
        assertEquals(0, off.body["roles"].size())
        assertEquals(404, verify(mapOf("demo" to "doctor")).status)

        jdbc.update("INSERT INTO demo_state (partial) VALUES (true)")
        assertEquals(false, verify(mapOf("demoRoles" to true)).body["active"].booleanValue(), "partial demo hides the list")
        jdbc.update("UPDATE demo_state SET partial = false")
        jdbc.update("INSERT INTO demo_accounts (uid, email, role_key) VALUES ('demo-doctor', 'doctor@lifolo-demo.test', 'doctor')")
        identity.add("demo-doctor", "doctor@lifolo-demo.test")
        val list = verify(mapOf("demoRoles" to true))
        assertEquals(true, list.body["active"].booleanValue())
        assertEquals(10, list.body["roles"].size())
        val login = verify(mapOf("demo" to "doctor"))
        assertEquals(200, login.status, login.toString())
        assertEquals("ctok_demo-doctor", login.body["token"].textValue())
        assertEquals(true, login.body["demo"].booleanValue())
        assertNotNull(row("SELECT uid FROM accounts WHERE uid = 'demo-doctor'")["uid"])
        assertEquals(404, verify(mapOf("demo" to "admin")).status)
        assertEquals(404, verify(mapOf("demo" to "patient")).status, "no account for this role")
    }
}
