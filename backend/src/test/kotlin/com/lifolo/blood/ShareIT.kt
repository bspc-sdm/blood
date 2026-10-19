package com.lifolo.blood

import com.lifolo.admin.kernel.AccountDeletionStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** api/share.js pages and QR codes; the daily blood steps (expire requests, eligible again); the account-deletion step */
class ShareIT : BloodTestBase() {
    @Autowired lateinit var steps: List<AccountDeletionStep>

    private fun text(r: org.springframework.mock.web.MockHttpServletResponse) = r.getContentAsString(Charsets.UTF_8)

    @Test
    fun `request page - facts without name, phone or note; closed and missing requests`() {
        user("req", emptyMap())
        request("r1", "req", group = "B+", bags = 3, urgent = true, hospital = "মমেক <b>")
        jdbc.update("UPDATE blood_requests SET confirmed_count = 1, accepted_count = 2 WHERE id = 'r1'")
        val r = page("/r/r1")
        assertEquals(200, r.status)
        assertTrue(r.contentType!!.startsWith("text/html"), r.contentType)
        val html = text(r)
        assertTrue(html.contains("<title>B+ রক্ত দরকার / B+ blood needed</title>"), html)
        assertTrue(html.contains("<h1>2 ব্যাগ রক্ত দরকার</h1>"))
        assertTrue(html.contains(" · মোট 3 ব্যাগের বাকি / of 3"))
        assertTrue(html.contains("মমেক &lt;b&gt;"))
        assertTrue(html.contains("জরুরি · Urgent"))
        assertTrue(html.contains("2 জন রাজি · 2 agreed"))
        assertTrue(html.contains("$DIST · Mymensingh"))
        assertTrue(html.contains("intent://r/r1#Intent;scheme=getblood;package=com.bspc.blood;S.browser_fallback_url=https%3A%2F%2Fgithub.com%2Fbspc-sdm%2Fblood%2Freleases%2Fdownload%2Flatest%2Froktodan.apk;end"))
        assertFalse(html.contains("secret note"))
        assertFalse(html.contains("Requester req"))
        assertEquals(200, page("/api/share?id=r1").status)
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = 'r1'")
        val closed = text(page("/r/r1"))
        assertTrue(closed.contains("<h1>3 ব্যাগ রক্ত দরকার</h1>"))
        assertTrue(closed.contains("বন্ধ · Closed"))
        assertFalse(closed.contains("Open in app"))
        val missing = page("/r/nope")
        assertEquals(404, missing.status)
        assertTrue(text(missing).contains("রিকোয়েস্টটা পাওয়া যায়নি। / Request not found."))
        assertEquals(404, page("/api/share").status)
    }

    @Test
    fun `asset links, QR codes, shop and prescription pages`() {
        val a = get("/.well-known/assetlinks.json")
        assertEquals(200, a.status)
        assertEquals("com.bspc.blood", a.body[0]["target"]["package_name"].textValue())
        assertEquals("delegate_permission/common.handle_all_urls", a.body[0]["relation"][0].textValue())
        val qr = page("/api/share?rxqr=abcdefgh23")
        assertEquals(200, qr.status)
        assertTrue(qr.contentType!!.startsWith("image/svg+xml"), qr.contentType)
        assertTrue(text(qr).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertEquals(404, page("/api/share?rxqr=bad").status)
        assertEquals(200, page("/api/share?shopqr=ABC234").status)
        val shop = page("/shop/ABC234")
        assertEquals(404, shop.status)
        assertTrue(text(shop).contains("No pharmacy with this code."))
        user("ph1", emptyMap())
        jdbc.update("INSERT INTO pharmacies (uid, name, phone, district, license_no, status, shop_code) VALUES ('ph1', 'সেবা ফার্মেসি', '01711111111', ?, 'DL-1', 'approved', 'ABC234')", DIST)
        val ok = page("/shop/abc234")
        assertEquals(200, ok.status)
        assertTrue(text(ok).contains("<h1 style=\"margin-top:10px\">সেবা ফার্মেসি</h1>"))
        assertTrue(text(ok).contains("<svg"))
        val rx = page("/rx/ABCDEFGH23")
        assertEquals(404, rx.status)
        assertTrue(text(rx).contains("No prescription with this code."))
    }

    @Test
    fun `daily - expired requests close and accepted donors are told; donors eligible again today are reminded`() {
        user("req", emptyMap())
        donor("a", "O+")
        donor("again", "A+", extra = mapOf("last_donation_ms" to System.currentTimeMillis() - 120 * DAY - 3_600_000))
        donor("quiet", "A+", extra = mapOf("last_donation_ms" to System.currentTimeMillis() - 120 * DAY - 3_600_000, "notify" to false))
        donor("old", "A+", extra = mapOf("last_donation_ms" to System.currentTimeMillis() - 125 * DAY))
        request("old1", "req", neededBy = System.currentTimeMillis() - 3 * DAY)
        request("live", "req")
        response("old1", "a")
        val r = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, r.status, r.toString())
        assertEquals(1, r.body["closed"].intValue())
        assertEquals(1, r.body["eligibleReminders"].intValue())
        val x = row("SELECT status, closed_by FROM blood_requests WHERE id = 'old1'")
        assertEquals("closed", x["status"])
        assertEquals("expired", x["closed_by"])
        assertEquals("open", row("SELECT status FROM blood_requests WHERE id = 'live'")["status"])
        assertTrue(inbox("a").contains("রিকোয়েস্টের সময় শেষ"))
        assertTrue(inbox("again").contains("আপনি আবার রক্ত দিতে পারবেন"))
        assertTrue(inbox("quiet").isEmpty() && inbox("old").isEmpty())
    }

    @Test
    fun `account deletion - banks hidden, then requests, responses and banks gone; counterparts told`() {
        user("gone", mapOf("name" to "যাবেন"))
        user("req", mapOf("name" to "রোগী"))
        donor("a", "O+")
        request("mine", "gone", accepted = 0)
        request("theirs", "req", accepted = 0, bags = 1)
        assertEquals(200, http("/api/respond", "a", mapOf("requestId" to "mine")).status)
        donor("gone2", "O+")
        jdbc.update("UPDATE user_profiles SET name = 'যাবেন' WHERE uid = 'gone2'")
        assertEquals(200, http("/api/respond", "gone2", mapOf("requestId" to "theirs")).status)
        assertEquals(200, call("req", "confirm_donor", mapOf("requestId" to "theirs", "donorUid" to "gone2")).status)
        jdbc.update("INSERT INTO blood_banks (id, owner_uid, name, kind, phone, district, status) VALUES ('bb1', 'gone', 'ব্যাংক', 'org', '01711111111', ?, 'approved')", DIST)
        val step = steps.single { it is BloodDeletionStep }
        step.hide("gone")
        val b = row("SELECT status, hidden_for_deletion FROM blood_banks WHERE id = 'bb1'")
        assertEquals("suspended", b["status"])
        assertEquals("approved", b["hidden_for_deletion"])
        step.deleteNow("gone", "gone@example.com")
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_requests WHERE requester_uid = 'gone'")["n"])
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_banks WHERE owner_uid = 'gone'")["n"])
        assertTrue(inbox("a").contains("রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে"))
        step.deleteNow("gone2", null)
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_responses WHERE donor_uid = 'gone2'")["n"])
        assertEquals(0, row("SELECT accepted_count FROM blood_requests WHERE id = 'theirs'")["accepted_count"])
        assertEquals(0, row("SELECT confirmed_count FROM blood_requests WHERE id = 'theirs'")["confirmed_count"])
        assertTrue(inbox("req").contains("একজন ডোনার আর থাকছেন না"))
    }
}
