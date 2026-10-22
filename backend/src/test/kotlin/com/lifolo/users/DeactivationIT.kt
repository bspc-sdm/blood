package com.lifolo.users

import com.lifolo.appointments.Ch
import com.lifolo.appointments.P2TestBase
import com.lifolo.common.DhakaTime
import com.lifolo.common.Geo
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Temporary deactivation (account_deactivate / account_reactivate) and the safer deletion request: what others stop
 * seeing, the 423 for everything else, pushes, caregiver links, the emails, and how deletion supersedes it.
 */
class DeactivationIT : P2TestBase() {
    @Autowired lateinit var pushService: PushService

    @Autowired lateinit var deletion: AccountDeletionService

    private val DAY = 86_400_000L
    private val LAT = 24.75
    private val LNG = 90.40
    private val mym: String get() = district("Mymensingh")

    private val LOCKED = "আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে। চালু করতে \"আবার চালু করুন\" চাপুন।" // the contract text
    private val GONE_NAME = "নিষ্ক্রিয় ব্যবহারকারী"

    private fun birth(y: Int): Long = LocalDate.of(y, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** an eligible A+ donor near (LAT, LNG) with a phone */
    private fun donor(uid: String, north: Double = 0.01) {
        val lat = LAT + north
        val gh = Geo.hash(lat, LNG)
        person(
            uid, "Donor $uid",
            extra = mapOf(
                "phone" to "01811111111", "blood_group" to "A+", "lat" to lat, "lng" to LNG, "geohash" to gh,
                "donor_geo" to Geo.donorKey("A+", gh), "birth_date_ms" to birth(1995), "weight_kg" to 60.0, "place_name" to mym,
            ),
        )
    }

    /** an owner with every kind of listing, posts in the Health Discussion and tips */
    private fun owner(uid: String) {
        person(uid, "মালিক $uid")
        badge(uid, "doctor", "ডা. মালিক")
        provider(
            uid,
            listOf(
                Ch(
                    hospitalId = "dghs_1", hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল", district = mym,
                    days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, fee = "500", appointments = true,
                ),
            ),
            name = "ডা. মালিক",
        )
        jdbc.update("UPDATE providers SET home_visit_set = TRUE, home_visit_enabled = TRUE, home_phone = '01700000180' WHERE uid = ?", uid)
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES (?, 'ডি ফার্মেসি', 'L-1', '01711111111', ?, 'approved')", uid, mym)
        jdbc.update("INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, 'ডি ল্যাব', ?, 'চরপাড়া', 'DGHS-1', '01711000498', 'pending')", uid, mym)
        jdbc.update(
            """
            INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, available, available_at, status)
            VALUES ('amb-$uid', ?, 'ডি অ্যাম্বুলেন্স', '01711111112', 'V-1', '{ac}', ?, TRUE, now(), 'approved')
            """.trimIndent(),
            uid, mym,
        )
        jdbc.update(
            "INSERT INTO blood_banks (id, owner_uid, name, kind, phone, district, status) VALUES ('bb-$uid', ?, 'ডি ব্যাংক', 'org', '01711111113', ?, 'approved')",
            uid, mym,
        )
        jdbc.update("INSERT INTO questions (id, uid, author_name, text) VALUES ('q-$uid', ?, 'মালিক $uid', 'প্রশ্ন')", uid)
        jdbc.update("INSERT INTO answers (id, question_id, uid, author_name, badge, text) VALUES ('a-$uid', 'q-$uid', ?, 'ডা. মালিক', 'doctor', 'উত্তর')", uid)
        jdbc.update("UPDATE questions SET answer_count = 1 WHERE id = 'q-$uid'")
        jdbc.update("INSERT INTO tips (id, uid, author_name, badge, title, body) VALUES ('t-$uid', ?, 'ডা. মালিক', 'doctor', 'টিপ', 'লেখা')", uid)
    }

    private fun listingState(uid: String): Map<String, Any?> = linkedMapOf(
        "pharmacy" to row("SELECT status, hidden_for_deactivation AS h FROM pharmacies WHERE uid = ?", uid).let { "${it["status"]}/${it["h"]}" },
        "lab" to row("SELECT status, hidden_for_deactivation AS h FROM labs WHERE uid = ?", uid).let { "${it["status"]}/${it["h"]}" },
        "ambulance" to row("SELECT status, hidden_for_deactivation AS h FROM ambulances WHERE owner_uid = ?", uid).let { "${it["status"]}/${it["h"]}" },
        "bloodbank" to row("SELECT status, hidden_for_deactivation AS h FROM blood_banks WHERE owner_uid = ?", uid).let { "${it["status"]}/${it["h"]}" },
        "provider" to row("SELECT listed, active, home_visit_enabled AS home FROM providers WHERE uid = ?", uid).let { "${it["listed"]}/${it["active"]}/${it["home"]}" },
    )

    private fun bloodRequest(id: String) {
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, requester_name, patient_group, bags, hospital, place_name, lat, lng, geohash,
              needed_by_ms, urgent, status, accepted_count)
            VALUES (?, 'req', 'রোগী', 'A+', 2, 'মমেক', ?, ?, ?, ?, ?, FALSE, 'open', 0)
            """.trimIndent(),
            id, mym, LAT, LNG, Geo.hash(LAT, LNG), System.currentTimeMillis() + DAY,
        )
    }

    private fun mailsTo(to: String, re: String) = mail.sent.filter { it.to == to && Regex(re).containsMatchIn(it.subject + "\n" + it.text) }

    private fun seen(viewer: String, action: String, body: Map<String, Any?>, needle: String) = ok(viewer, action, body).toString().contains(needle)

    @Test
    fun `deactivate hides profile, listings and posts, blocks every other action with 423, reactivate restores`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        owner("own1")
        person("viewer", "দর্শক")
        val active = listingState("own1")
        assertEquals("approved/null", active["pharmacy"])
        assertEquals("true/true/true", active["provider"])
        assertTrue(seen("viewer", "ambulances", mapOf("district" to mym), "ডি অ্যাম্বুলেন্স"))
        assertTrue(seen("viewer", "bloodbanks", mapOf("district" to mym), "ডি ব্যাংক"))
        assertTrue(seen("viewer", "providers", mapOf("type" to "doctor", "homeVisit" to false), "own1"))
        assertEquals(listOf("active"), listOf(ok("own1", "profile_get")["account"]["state"].textValue()))

        val r = call("own1", "account_deactivate")
        assertEquals(200, r.status, r.toString())
        assertEquals("deactivated", r.body["account"]["state"].textValue())
        assertTrue(r.body["account"]["deactivatedAt"].longValue() > 0)
        assertEquals("ctok_own1", r.body["token"].textValue(), "a fresh login for this device")
        assertTrue(identity.revokedCalls.contains("own1"), "every other device signed out")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM device_tokens WHERE uid = 'own1'", Int::class.java))
        assertEquals(1, mailsTo("own1@example.com", "অ্যাকাউন্ট সাময়িক বন্ধ করা হয়েছে — আবার লগইন করে চালু করতে পারবেন").size)

        // others: listings, chambers (no booking), home care, posts' author
        val hidden = listingState("own1")
        assertEquals("suspended/approved", hidden["pharmacy"])
        assertEquals("suspended/pending", hidden["lab"])
        assertEquals("suspended/approved", hidden["ambulance"])
        assertEquals("suspended/approved", hidden["bloodbank"])
        assertEquals("false/false/false", hidden["provider"])
        assertFalse(seen("viewer", "ambulances", mapOf("district" to mym), "ডি অ্যাম্বুলেন্স"))
        assertFalse(seen("viewer", "bloodbanks", mapOf("district" to mym), "ডি ব্যাংক"))
        assertFalse(seen("viewer", "providers", mapOf("type" to "doctor", "homeVisit" to false), "own1"))
        assertEquals(404, call("viewer", "book", mapOf("providerUid" to "own1", "date" to today() + DAY, "chamberIndex" to 0, "note" to "জ্বর")).status)
        val q = ok("viewer", "question", mapOf("id" to "q-own1"))
        assertEquals(GONE_NAME, q["question"]["authorName"].textValue())
        assertEquals(GONE_NAME, q["answers"][0]["authorName"].textValue())
        assertTrue(q["answers"][0]["badge"].isNull)
        assertEquals("উত্তর", q["answers"][0]["text"].textValue(), "the post itself stays")
        assertEquals(GONE_NAME, ok("viewer", "questions")["items"][0]["authorName"].textValue())
        assertEquals(GONE_NAME, ok("viewer", "tips")["items"][0]["authorName"].textValue())

        // the account itself: only the few allowed actions
        for (a in listOf("me", "profile_update", "inbox", "device_register", "request_create", "account_deactivate", "questions", "care_list")) {
            val x = call("own1", a)
            assertEquals(423, x.status, "$a: $x")
            assertEquals("account_deactivated", x.code, a)
            assertEquals(LOCKED, x.error, a)
        }
        assertEquals(423, post("/api/respond", mapOf("requestId" to "r1"), mapOf("Authorization" to "Bearer own1")).status, "legacy blood endpoints too")
        assertEquals("Your account is temporarily deactivated. Tap \"Turn on again\" to reactivate it.", call("own1", "me", lang = "en").error)
        val pg = ok("own1", "profile_get")
        assertEquals("deactivated", pg["account"]["state"].textValue())
        assertEquals("মালিক own1", pg["profile"]["name"].textValue())
        assertEquals(200, call("own1", "notify_prefs_get").status)

        // back on
        val back = call("own1", "account_reactivate")
        assertEquals(200, back.status, back.toString())
        assertEquals("active", back.body["account"]["state"].textValue())
        assertEquals(active, listingState("own1"))
        assertEquals(1, mailsTo("own1@example.com", "অ্যাকাউন্ট আবার চালু হয়েছে").size)
        assertTrue(seen("viewer", "ambulances", mapOf("district" to mym), "ডি অ্যাম্বুলেন্স"))
        assertTrue(seen("viewer", "providers", mapOf("type" to "doctor", "homeVisit" to false), "own1"))
        assertEquals("মালিক own1", ok("viewer", "question", mapOf("id" to "q-own1"))["question"]["authorName"].textValue())
        assertEquals(200, call("own1", "me").status)
        assertTrue(ok("own1", "account_reactivate")["unchanged"].booleanValue())
        assertEquals(1, mailsTo("own1@example.com", "আবার চালু").size, "no second email")
    }

    @Test
    fun `an admin change meanwhile is kept on reactivation`() {
        owner("own2")
        assertEquals(200, call("own2", "account_deactivate").status)
        jdbc.update("UPDATE ambulances SET status = 'rejected' WHERE owner_uid = 'own2'")
        jdbc.update("UPDATE providers SET home_visit_blocked = TRUE WHERE uid = 'own2'")
        assertEquals(200, call("own2", "account_reactivate").status)
        val s = listingState("own2")
        assertEquals("rejected/null", s["ambulance"])
        assertEquals("approved/null", s["pharmacy"])
        assertEquals("true/true/false", s["provider"], "home care blocked meanwhile stays off")
    }

    @Test
    fun `a deactivated donor is nobody's donor and gets only account pushes`() {
        person("req", "রোগী", extra = mapOf("phone" to "01711111111"))
        donor("d-on")
        donor("d-off", north = 0.02)
        assertEquals(200, call("d-off", "account_deactivate").status)
        bloodRequest("r1")
        val n = post("/api/notify-request", mapOf("lang" to "bn", "requestId" to "r1"), mapOf("Authorization" to "Bearer req"))
        assertEquals(200, n.status, n.toString())
        assertEquals(setOf("d-on"), push.messages.mapNotNull { it.uid }.toSet())
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_request_notified WHERE request_id = 'r1' AND uid = 'd-off'")["n"])

        // a phone registered again while deactivated: only `account` news gets through; the bell list keeps the rest
        push.messages.clear()
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('tok_again', 'd-off')")
        pushService.send(
            listOf(
                PushItem("d-off", "উত্তর এসেছে", "", mapOf("type" to "answer")),
                PushItem("d-off", "Lifolo টিমের উত্তর", "", mapOf("type" to "support")),
                PushItem("d-on", "উত্তর এসেছে", "", mapOf("type" to "answer")),
            ),
        )
        assertEquals(listOf("Lifolo টিমের উত্তর"), push.messages.filter { it.uid == "d-off" }.map { it.data["title"] })
        assertEquals(1, push.messages.count { it.uid == "d-on" })
        assertEquals(2, pushesTo("d-off").count { it.first in setOf("উত্তর এসেছে", "Lifolo টিমের উত্তর") })

        // back on: a donor again
        assertEquals(200, call("d-off", "account_reactivate").status)
        push.messages.clear()
        bloodRequest("r2")
        val again = post("/api/notify-request", mapOf("lang" to "bn", "requestId" to "r2"), mapOf("Authorization" to "Bearer req"))
        assertEquals(200, again.status, again.toString())
        assertTrue("d-off" in push.messages.mapNotNull { it.uid }.toSet(), push.messages.toString())
    }

    @Test
    fun `caregiver links are paused both ways`() {
        person("pat", "রহিম")
        person("cg", "রুমা")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat', 'cg', 'active')")
        assertEquals(200, call("cg", "rx_mine", mapOf("asUid" to "pat")).status)

        assertEquals(200, call("pat", "account_deactivate").status)
        val paused = call("cg", "rx_mine", mapOf("asUid" to "pat"))
        assertEquals(403, paused.status, paused.toString())
        assertEquals("care_paused", paused.code)
        push.messages.clear()
        jdbc.update("DELETE FROM inbox_items")
        pushService.pushPatient("pat", "New prescription", "নতুন প্রেসক্রিপশন", data = mapOf("type" to "rx"))
        assertTrue(pushesTo("cg").isEmpty(), "no copy to the caregiver")
        assertTrue(push.messages.isEmpty(), "no rx push to the patient either")
        assertEquals(200, call("pat", "account_reactivate").status)
        assertEquals(200, call("cg", "rx_mine", mapOf("asUid" to "pat")).status)

        // the caregiver deactivated: cannot act for anyone
        assertEquals(200, call("cg", "account_deactivate").status)
        assertEquals(423, call("cg", "rx_mine", mapOf("asUid" to "pat")).status)
        // and nobody can deactivate someone else's account through a caregiver link
        assertEquals(200, call("cg", "account_reactivate").status)
        assertEquals(403, call("cg", "account_deactivate", mapOf("asUid" to "pat")).status)
        assertNull(row("SELECT deactivated_at FROM accounts WHERE uid = 'pat'")["deactivated_at"])
    }

    @Test
    fun `deletion - old clients without ack, email with the date, pending deletion blocks deactivation, login cancels with an email`() {
        person("del1", "মুছব")
        val r = call("del1", "delete_account") // an old app: no ack
        assertEquals(200, r.status, r.toString())
        val deleteAt = r.body["deleteAt"].longValue()
        val m = mailsTo("del1@example.com", "মোছার").single()
        assertTrue(m.text.contains(DhakaTime.longDate(deleteAt, false)), m.text)
        assertTrue(m.text.contains("৩০ দিনের মধ্যে লগইন করলে মোছা বাতিল হবে"), m.text)
        assertTrue(m.text.contains("https://lifolo.com"), m.text)
        assertTrue(m.text.contains("Your Lifolo account will be deleted on ${DhakaTime.longDate(deleteAt, true)}"), m.text)
        // asking again keeps the date and sends nothing new
        assertEquals(deleteAt, call("del1", "delete_account", mapOf("ack" to true)).body["deleteAt"].longValue())
        assertEquals(1, mailsTo("del1@example.com", "মোছার").size)

        val st = ok("del1", "profile_get")["account"]
        assertEquals("deleting", st["state"].textValue())
        assertEquals(deleteAt, st["deletesAt"].longValue())
        assertTrue(st["deletionRequestedAt"].longValue() > 0)

        val blocked = call("del1", "account_deactivate")
        assertEquals(409, blocked.status, blocked.toString())
        assertEquals("deletion_pending", blocked.code)
        assertEquals("আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে, তাই সাময়িক বন্ধ করা যাবে না। আগে আবার লগইন করে মোছা বাতিল করুন।", blocked.error)
        assertNull(row("SELECT deactivated_at FROM accounts WHERE uid = 'del1'")["deactivated_at"])

        // logging in again cancels, the login answer says "active", and an email says so
        post("/api/auth/send-code", mapOf("email" to "del1@example.com"), mapOf("X-Forwarded-For" to "7.7.7.7"))
        val v = post("/api/auth/verify-code", mapOf("email" to "del1@example.com", "code" to mail.lastCode("del1@example.com")))
        assertEquals(200, v.status, v.toString())
        assertTrue(v.body["restored"].booleanValue())
        assertEquals("active", v.body["account"]["state"].textValue())
        assertEquals(1, mailsTo("del1@example.com", "মোছা বাতিল হয়েছে").size)
    }

    @Test
    fun `deletion supersedes deactivation, the daily job never deletes a deactivated account, emails never block`() {
        owner("own3")
        mail.fail = true // SES down: the actions still work
        assertEquals(200, call("own3", "account_deactivate").status)
        mail.fail = false
        val r = call("own3", "delete_account", mapOf("ack" to true))
        assertEquals(200, r.status, r.toString())
        val a = row("SELECT deactivated_at, delete_at FROM accounts WHERE uid = 'own3'")
        assertNull(a["deactivated_at"], "the deletion replaces the deactivation")
        assertNotNull(a["delete_at"])
        assertEquals(
            listOf("suspended", null, "approved"),
            row("SELECT status, hidden_for_deactivation, hidden_for_deletion FROM pharmacies WHERE uid = 'own3'").values.toList(),
        )
        assertEquals("deleting", ok("own3", "profile_get")["account"]["state"].textValue())
        assertEquals(1, mailsTo("own3@example.com", "মোছার").size)
        assertTrue(mailsTo("own3@example.com", "আবার চালু").isEmpty(), "no reactivation email on the way to deletion")

        // deactivated for a year: still there after the daily run
        person("sleep1", "ঘুম")
        assertEquals(200, call("sleep1", "account_deactivate").status)
        jdbc.update("UPDATE accounts SET deactivated_at = ? WHERE uid = 'sleep1'", Timestamp(System.currentTimeMillis() - 400 * DAY))
        deletion.runDue(System.currentTimeMillis() + 31 * DAY)
        assertFalse(row("SELECT uid FROM user_profiles WHERE uid = 'sleep1'").isEmpty())
        assertTrue(identity.users.containsKey("sleep1"))
    }
}
