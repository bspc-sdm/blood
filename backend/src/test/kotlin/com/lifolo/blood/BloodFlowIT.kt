package com.lifolo.blood

import com.lifolo.contracts.RequestEnd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The request life cycle over the legacy endpoints and the donor actions (the demo-blood.test.js flow without the
 * demo world, plus notify-request / reopen / delete / RequestEnd, which only the e2e suites covered in Node).
 */
class BloodFlowIT : BloodTestBase() {
    @Autowired lateinit var requestEnd: RequestEnd

    private fun st(id: String) = row("SELECT * FROM blood_requests WHERE id = ?", id)

    private fun resp(id: String, uid: String) = row("SELECT * FROM blood_responses WHERE request_id = ? AND donor_uid = ?", id, uid)

    @Test
    fun `respond - checks, response, counts, requester told`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111", "lang" to "bn"))
        donor("d1", "O+", phone = "01822222222")
        donor("dNeg", "O-")
        donor("young", "O+", extra = mapOf("birth_date_ms" to birth(2015)))
        user("noProfile", emptyMap())
        user("unknown", mapOf("phone" to "01733333333", "blood_group" to "unknown"))
        user("demo-donor", mapOf("phone" to "01744444444", "blood_group" to "O+", "birth_date_ms" to birth(1990), "weight_kg" to 70.0))
        request("r1", "req", group = "A+", accepted = 0)
        contact("r1", "রোগী", "01711111111")
        assertEquals("নিজের রিকোয়েস্টে সাড়া দেওয়া যায় না", http("/api/respond", "req", mapOf("requestId" to "r1")).error)
        assertEquals("আগে প্রোফাইল পূরণ করুন", http("/api/respond", "noProfile", mapOf("requestId" to "r1")).error)
        assertEquals("রক্ত দিতে আগে প্রোফাইলে আপনার রক্তের গ্রুপ দিন", http("/api/respond", "unknown", mapOf("requestId" to "r1")).error)
        val young = http("/api/respond", "young", mapOf("requestId" to "r1"))
        assertEquals(400, young.status)
        assertTrue(young.error!!.contains("যোগ্য নন"))
        assertEquals("You are not eligible right now (age 18–60, weight 50 kg+, and 120 days since your last donation)",
            http("/api/respond", "young", mapOf("requestId" to "r1"), lang = "en").error)
        assertEquals(403, http("/api/respond", "demo-donor", mapOf("requestId" to "r1")).status)
        assertEquals("requestId ঠিক নেই", http("/api/respond", "d1", mapOf("requestId" to "a/b")).error)
        assertEquals("Invalid request", http("/api/respond", "d1", mapOf("requestId" to "a/b"), lang = "en").error)
        assertEquals(401, http("/api/respond", null, mapOf("requestId" to "r1")).status)
        assertEquals("Please log in", http("/api/respond", null, mapOf("requestId" to "r1"), lang = "en").error)
        assertEquals(404, http("/api/respond", "d1", mapOf("requestId" to "nope")).status)

        val ok = http("/api/respond", "d1", mapOf("requestId" to "r1"))
        assertEquals(200, ok.status, ok.toString())
        assertEquals(true, ok.body["ok"].booleanValue())
        val r = resp("r1", "d1")
        assertEquals("accepted", r["status"])
        assertEquals("01822222222", r["donor_phone"])
        assertEquals("O+", r["donor_group"])
        assertEquals(0, r["distance_km"])
        assertEquals(1, st("r1")["accepted_count"])
        assertEquals(1L, row("SELECT count(*) AS n FROM user_commitments WHERE uid = 'd1' AND request_id = 'r1'")["n"])
        assertTrue(inbox("req").contains("একজন ডোনার রাজি হয়েছেন"))
        // again: already
        assertEquals(true, http("/api/respond", "d1", mapOf("requestId" to "r1")).body["already"].booleanValue())
        assertEquals(1, st("r1")["accepted_count"])
        // O- donor can give to A+, the contact is visible to them now
        assertEquals(200, http("/api/respond", "dNeg", mapOf("requestId" to "r1")).status)
        assertEquals("01711111111", call("dNeg", "blood_contact_get", mapOf("requestId" to "r1")).body["contact"]["phone"].textValue())
        // wrong group
        request("rB", "req", group = "O-", accepted = 0)
        assertEquals("আপনার গ্রুপ (O+) এই রোগীকে দেওয়া যায় না", http("/api/respond", "d1", mapOf("requestId" to "rB")).error)
        assertEquals("Your group (O+) can't donate to this patient", http("/api/respond", "d1", mapOf("requestId" to "rB"), lang = "en").error)
        // closed request
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = 'rB'")
        assertEquals("রিকোয়েস্টটা বন্ধ হয়ে গেছে", http("/api/respond", "d1", mapOf("requestId" to "rB")).error)
        assertEquals(405, get("/api/respond").status)
    }

    @Test
    fun `confirm, remove and restore, cancel, donated - counts and pushes`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        for (d in listOf("a", "b", "c")) donor(d, "O+")
        request("r1", "req", group = "O+", bags = 1, accepted = 0, neededAt = null)
        for (d in listOf("a", "b", "c")) assertEquals(200, http("/api/respond", d, mapOf("requestId" to "r1")).status)
        assertEquals(3, st("r1")["accepted_count"])
        // not the requester
        assertEquals(403, call("a", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "b")).status)
        val cf = call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "a"))
        assertEquals(200, cf.status, cf.toString())
        assertEquals(1, st("r1")["confirmed_count"])
        assertEquals(true, st("r1")["fulfilled"])
        assertEquals(true, resp("r1", "a")["confirmed"])
        assertTrue(inbox("a").contains("আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন"))
        assertTrue(inbox("b").contains("আপাতত যথেষ্ট ডোনার পাওয়া গেছে, ধন্যবাদ!"))
        assertEquals(true, call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "a")).body["already"].booleanValue())
        // more than the bags
        assertEquals(
            "সব ব্যাগের ডোনার নিশ্চিত হয়ে গেছে। আরেকজন নিশ্চিত করতে আগে ব্যাগের সংখ্যা বাড়ান।",
            call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "b")).error,
        )
        // requester removes b → declined, b told; restore within 30 min
        assertEquals(200, call("req", "response_update", mapOf("requestId" to "r1", "op" to "remove", "donorUid" to "b")).status)
        assertEquals("declined", resp("r1", "b")["status"])
        assertEquals("requester", resp("r1", "b")["removed_by"])
        assertEquals(2, st("r1")["accepted_count"])
        assertTrue(inbox("b").contains("এই রিকোয়েস্টে আপনাকে আর লাগবে না"))
        assertEquals("রক্তপ্রার্থী অন্য ডোনার বেছে নিয়েছেন", http("/api/respond", "b", mapOf("requestId" to "r1")).error)
        val rs = call("req", "response_update", mapOf("requestId" to "r1", "op" to "restore", "donorUid" to "b"))
        assertEquals(true, rs.body["restored"].booleanValue())
        assertEquals("accepted", resp("r1", "b")["status"])
        assertEquals(3, st("r1")["accepted_count"])
        assertTrue(inbox("b").contains("আপনাকে আবার রিকোয়েস্টে রাখা হয়েছে"))
        assertEquals(400, call("req", "response_update", mapOf("requestId" to "r1", "op" to "restore", "donorUid" to "b")).status)
        // the confirmed donor cancels → requester told, standby donors told "may be needed again"
        val cancel = call("a", "response_update", mapOf("requestId" to "r1", "op" to "cancel"))
        assertEquals(200, cancel.status, cancel.toString())
        assertEquals("cancelled", resp("r1", "a")["status"])
        assertEquals(0, st("r1")["confirmed_count"])
        assertEquals(false, st("r1")["fulfilled"])
        assertTrue(inbox("req").contains("একজন নিশ্চিত ডোনার বাতিল করেছেন"))
        assertTrue(inbox("c").contains("আবার ডোনার লাগতে পারে"))
        assertEquals("এখন এটা বদলানো যায় না", call("a", "response_update", mapOf("requestId" to "r1", "op" to "cancel")).error)
        assertEquals(404, call("a", "response_update", mapOf("requestId" to "nope", "op" to "cancel")).status)
        assertEquals(400, call("a", "response_update", mapOf("requestId" to "r1", "op" to "cancel", "donorUid" to "x/y")).status)
        // c gave blood: date set today, counted, other "accepted" released
        user("req2", mapOf("name" to "অন্য", "phone" to "01799999999"))
        request("r2", "req2", group = "O+", accepted = 0)
        assertEquals(200, http("/api/respond", "c", mapOf("requestId" to "r2")).status)
        val dn = call("c", "response_update", mapOf("requestId" to "r1", "op" to "donated"))
        assertEquals(200, dn.status, dn.toString())
        assertEquals("donated", resp("r1", "c")["status"])
        assertEquals(1, st("r1")["confirmed_count"])
        assertEquals(true, st("r1")["fulfilled"])
        assertEquals(today(), lastDonation("c"))
        assertEquals("cancelled", resp("r2", "c")["status"])
        assertEquals("donated_elsewhere", resp("r2", "c")["cancelled_reason"])
        assertEquals(0, st("r2")["accepted_count"])
        assertTrue(inbox("req2").contains("একজন ডোনার আর থাকছেন না"))
        assertEquals(0L, row("SELECT count(*) AS n FROM user_commitments WHERE uid = 'c'")["n"])
    }

    @Test
    fun `close - marks donors, sets dates, tells the others, reopen within an hour undoes it`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        for (d in listOf("a", "b", "c")) donor(d, "O+", extra = mapOf("last_donation_ms" to today() - 200 * DAY))
        request("r1", "req", group = "O+", bags = 2, accepted = 0)
        for (d in listOf("a", "b", "c")) assertEquals(200, http("/api/respond", d, mapOf("requestId" to "r1")).status)
        assertEquals(200, call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "a")).status)
        // partial: 1 of 2 bags → stays open
        val part = http("/api/close-request", "req", mapOf("requestId" to "r1", "donatedUids" to listOf("a"), "bagsReceived" to 1))
        assertEquals(200, part.status, part.toString())
        assertEquals(1, part.body["marked"].intValue())
        assertEquals(false, part.body["closed"].booleanValue())
        assertEquals("open", st("r1")["status"])
        assertEquals(1, st("r1")["bags_received"])
        assertEquals("donated", resp("r1", "a")["status"])
        assertEquals(today(), lastDonation("a"))
        // full close: b marked (only 1 allowed: 2 bags − 1 donated before), c told "no need to come"
        val cl = http("/api/close-request", "req", mapOf("requestId" to "r1", "donatedUids" to listOf("b", "c", "bad/id"), "bagsReceived" to 2))
        assertEquals(200, cl.status, cl.toString())
        assertEquals(1, cl.body["marked"].intValue())
        assertEquals(true, cl.body["closed"].booleanValue())
        assertEquals(false, cl.body["notNeeded"].booleanValue())
        val x = st("r1")
        assertEquals("closed", x["status"])
        assertEquals("requester", x["closed_by"])
        assertEquals(2, x["bags_received"])
        assertEquals(1, x["bags_received_before"])
        assertEquals("donated", resp("r1", "b")["status"])
        assertEquals("accepted", resp("r1", "c")["status"])
        assertTrue(inbox("c").contains("রিকোয়েস্ট বন্ধ হয়েছে, আর আসতে হবে না"))
        assertTrue(inbox("b").contains("রক্ত দেওয়ার জন্য ধন্যবাদ!"))
        assertEquals(today(), lastDonation("b"))
        assertEquals(true, http("/api/close-request", "req", mapOf("requestId" to "r1")).body["already"].booleanValue())
        // the nearby list no longer shows it
        assertEquals(0, call("c", "blood_requests_near", mapOf("lat" to LAT, "lng" to LNG)).body["items"].size())

        // reopen: b back to accepted with the old date, counts as before; donors told
        assertEquals(403, call("c", "request_reopen", mapOf("requestId" to "r1")).status)
        val ro = call("req", "request_reopen", mapOf("requestId" to "r1"))
        assertEquals(200, ro.status, ro.toString())
        val y = st("r1")
        assertEquals("open", y["status"])
        assertNull(y["closed_at"])
        assertEquals(1, y["bags_received"])
        assertEquals(1, y["confirmed_count"])
        assertEquals("accepted", resp("r1", "b")["status"])
        assertEquals(today() - 200 * DAY, lastDonation("b"))
        assertEquals("donated", resp("r1", "a")["status"])
        assertTrue(inbox("c").contains("রিকোয়েস্ট আবার খোলা হয়েছে"))
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_request_close_marks WHERE request_id = 'r1'")["n"])
        // open already → nothing
        assertEquals(200, call("req", "request_reopen", mapOf("requestId" to "r1")).status)
        // "not needed any more" (0 bags): nobody marked
        val nn = http("/api/close-request", "req", mapOf("requestId" to "r1", "donatedUids" to listOf("c"), "bagsReceived" to 0))
        assertEquals(true, nn.body["notNeeded"].booleanValue())
        assertEquals(0, nn.body["marked"].intValue())
        assertEquals("not_needed", st("r1")["closed_by"])
        assertTrue(inbox("c").contains("আর রক্ত লাগবে না, আসতে হবে না"))
        // more than an hour later: no reopening
        jdbc.update("UPDATE blood_requests SET closed_at = now() - interval '2 hours' WHERE id = 'r1'")
        assertEquals(
            "এই রিকোয়েস্ট আর খোলা যায় না। দরকার হলে নতুন রিকোয়েস্ট দিন।",
            call("req", "request_reopen", mapOf("requestId" to "r1")).error,
        )
    }

    @Test
    fun `edit - checks, recount on bags, accepted donors told what changed`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        donor("a", "O+")
        donor("b", "O+")
        request("r1", "req", group = "O+", bags = 1, accepted = 0)
        contact("r1", "রোগী", "01711111111")
        for (d in listOf("a", "b")) assertEquals(200, http("/api/respond", d, mapOf("requestId" to "r1")).status)
        assertEquals(200, call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "a")).status)
        assertEquals("কিছু বদলানো হয়নি", http("/api/edit-request", "req", mapOf("requestId" to "r1")).error)
        assertEquals("ব্যাগের সংখ্যা ঠিক নেই", http("/api/edit-request", "req", mapOf("requestId" to "r1", "bags" to 11)).error)
        assertEquals("ব্যাগের সংখ্যা ঠিক নেই", http("/api/edit-request", "req", mapOf("requestId" to "r1", "bags" to null)).error)
        assertEquals("হাসপাতালের নাম ঠিক নেই", http("/api/edit-request", "req", mapOf("requestId" to "r1", "hospital" to " ")).error)
        assertEquals("আজ বা পরের কোনো তারিখ দিন", http("/api/edit-request", "req", mapOf("requestId" to "r1", "neededByMillis" to today() - DAY)).error)
        assertEquals("জরুরি ঠিক নেই", http("/api/edit-request", "req", mapOf("requestId" to "r1", "urgent" to "yes")).error)
        assertEquals("সঠিক মোবাইল নম্বর দিন", http("/api/edit-request", "req", mapOf("requestId" to "r1", "contactPhone" to "12")).error)
        assertEquals("সময় ঠিক নেই", http("/api/edit-request", "req", mapOf("requestId" to "r1", "neededAtMillis" to today() + 5 * DAY)).error)
        assertEquals(403, http("/api/edit-request", "a", mapOf("requestId" to "r1", "note" to "x")).status)
        // 2 bags: no longer fulfilled → b (standby) told; accepted donors told the changes
        val e = http("/api/edit-request", "req", mapOf("requestId" to "r1", "bags" to 2, "hospital" to "নতুন হাসপাতাল", "contactPhone" to "01812345678", "urgent" to true))
        assertEquals(200, e.status, e.toString())
        assertEquals(2, e.body["notified"].intValue())
        val x = st("r1")
        assertEquals(2, x["bags"])
        assertEquals(false, x["fulfilled"])
        assertEquals(1, x["confirmed_count"])
        assertEquals(1, x["edit_count"])
        assertEquals(true, x["urgent"])
        assertEquals("01812345678", row("SELECT phone FROM blood_request_contacts WHERE request_id = 'r1'")["phone"])
        assertTrue(inbox("b").contains("আবার ডোনার লাগতে পারে"))
        val msg = push.messages.last { it.uid == "a" }.data
        assertEquals("O+ রক্তের রিকোয়েস্টে পরিবর্তন", msg["title"])
        assertEquals("হাসপাতাল: নতুন হাসপাতাল · ২ ব্যাগ · এখন জরুরি · নতুন যোগাযোগ নম্বর: 01812345678".replace("২", "2"), msg["body"])
        // edit limit
        jdbc.update("UPDATE blood_requests SET edit_count = 10 WHERE id = 'r1'")
        val lim = http("/api/edit-request", "req", mapOf("requestId" to "r1", "note" to "x"), lang = "en")
        assertEquals(429, lim.status)
        assertEquals("A request can be edited at most 10 times. Close it and create a new one if needed.", lim.error)
    }

    @Test
    fun `notify-request - nearby eligible donors by steps, daily push limit, again rules, lock`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        fun north(km: Double) = LAT + km / 111
        donor("near", "O-", lat = north(1.0))
        donor("en", "A+", lat = north(2.0), extra = mapOf("lang" to "en"))
        donor("wrong", "B+", lat = north(1.0))
        donor("recent", "A+", lat = north(1.0), extra = mapOf("last_donation_ms" to System.currentTimeMillis() - 10 * DAY))
        donor("off", "A+", lat = north(1.0), extra = mapOf("notify" to false))
        donor("notoken", "A+", lat = north(1.0), token = false)
        donor("far20", "A+", lat = north(20.0), extra = mapOf("max_km" to 25))
        donor("far20default", "A+", lat = north(20.0))
        donor("limited", "A+", lat = north(1.5), extra = mapOf("max_push_per_day" to 1))
        jdbc.update("INSERT INTO user_state (uid, push_day, push_count) VALUES ('limited', ?, 1)", com.lifolo.common.Sql.date(com.lifolo.common.DhakaTime.day()))
        donor("demo-x", "A+", lat = north(1.0))
        request("r1", "req", group = "A+", accepted = 0, urgent = true, hospital = "মমেক")
        assertEquals(403, http("/api/notify-request", "near", mapOf("requestId" to "r1")).status)
        assertEquals("আগে একবার ডোনারদের জানান", http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true)).error)
        val n = http("/api/notify-request", "req", mapOf("requestId" to "r1"))
        assertEquals(200, n.status, n.toString())
        assertEquals(3, n.body["sent"].intValue())
        assertEquals(setOf("near", "en", "far20"), push.messages.mapNotNull { it.uid }.toSet())
        val en = pushedTo("en").single()
        assertEquals("A+ blood needed (urgent)", en["title"])
        assertEquals("2 bag(s) · মমেক, Mymensingh · about 2 km away", en["body"])
        assertEquals("r1", en["requestId"])
        assertEquals("request", en["type"])
        val bn = pushedTo("near").single()
        assertEquals("A+ রক্ত দরকার (জরুরি)", bn["title"])
        assertEquals("2 ব্যাগ · মমেক, $DIST · প্রায় ১ কিমি দূরে", bn["body"])
        val x = st("r1")
        assertEquals(3, x["notified_count"])
        assertEquals(1, x["notify_rounds"])
        assertNull(x["notifying_until"])
        assertEquals(1, row("SELECT push_count FROM user_state WHERE uid = 'near'")["push_count"])
        assertEquals(3L, row("SELECT count(*) AS n FROM blood_request_notified WHERE request_id = 'r1'")["n"])
        // already notified → no second round without "again"
        val again0 = http("/api/notify-request", "req", mapOf("requestId" to "r1"))
        assertEquals(true, again0.body["already"].booleanValue())
        assertEquals(3, again0.body["sent"].intValue())
        // again: 6 hours apart
        val tooSoon = http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true))
        assertEquals(429, tooSoon.status)
        assertEquals("6 ঘণ্টা পর আবার জানাতে পারবেন", tooSoon.error)
        assertEquals("You can notify again in 6 hour(s)", http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true), lang = "en").error)
        // the lock (someone is sending)
        jdbc.update("UPDATE blood_requests SET last_notified_at = now() - interval '7 hours', notifying_until = now() + interval '1 minute' WHERE id = 'r1'")
        assertEquals(409, http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true)).status)
        jdbc.update("UPDATE blood_requests SET notifying_until = NULL WHERE id = 'r1'")
        donor("newcomer", "O+", lat = north(3.0))
        push.messages.clear()
        val ag = http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true))
        assertEquals(200, ag.status, ag.toString())
        assertEquals(setOf("newcomer"), push.messages.mapNotNull { it.uid }.toSet())
        assertEquals(4, st("r1")["notified_count"])
        assertEquals(2, st("r1")["notify_rounds"])
        // at most 3 rounds
        jdbc.update("UPDATE blood_requests SET notify_rounds = 3 WHERE id = 'r1'")
        assertEquals(
            "একটা রিকোয়েস্টে সর্বোচ্চ 3 বার জানানো যায়। পরিচিতদের সরাসরি জানান।",
            http("/api/notify-request", "req", mapOf("requestId" to "r1", "again" to true)).error,
        )
        // the requester's own daily limit: 3 requests notified a day
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = 'r1'")
        for (i in 2..3) {
            request("r$i", "req", group = "A+", accepted = 0)
            assertEquals(200, http("/api/notify-request", "req", mapOf("requestId" to "r$i")).status, "r$i")
            jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = ?", "r$i")
        }
        request("r4", "req", group = "A+", accepted = 0)
        val lim = http("/api/notify-request", "req", mapOf("requestId" to "r4"))
        assertEquals(429, lim.status)
        assertEquals("দিনে সর্বোচ্চ 3টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।", lim.error)
        assertEquals("রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ", http("/api/notify-request", "req", mapOf("requestId" to "r1")).error)
    }

    @Test
    fun `delete - own only, accepted donors of an open request told`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        donor("a", "O+")
        request("r1", "req", accepted = 0, hospital = "মমেক")
        assertEquals(200, http("/api/respond", "a", mapOf("requestId" to "r1")).status)
        assertEquals(403, call("a", "request_delete", mapOf("requestId" to "r1")).status)
        val d = call("req", "request_delete", mapOf("requestId" to "r1"))
        assertEquals(200, d.status, d.toString())
        assertEquals(1, d.body["notified"].intValue())
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_requests WHERE id = 'r1'")["n"])
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_responses WHERE request_id = 'r1'")["n"])
        val p = pushedTo("a").last()
        assertEquals("রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে", p["title"])
        assertEquals("মমেক-এর O+ রিকোয়েস্টটা তুলে নেওয়া হয়েছে। আপনার যাওয়ার দরকার নেই। ধন্যবাদ!", p["body"])
        assertEquals("request_removed", p["type"])
        assertFalse(call("req", "request_delete", mapOf("requestId" to "r1")).body.has("notified"))
        assertEquals(400, call("req", "request_delete", mapOf("requestId" to "../x")).status)
    }

    @Test
    fun `donation date change - risk confirmation, log, daily limit`() {
        val t0 = today() - 100 * DAY
        user("p1", mapOf("name" to "পি", "last_donation_ms" to t0))
        user("noProfile")
        assertEquals(404, call("noProfile", "donation_date_set", mapOf("millis" to t0)).status)
        assertEquals(400, call("p1", "donation_date_set", mapOf()).status)
        assertEquals(400, call("p1", "donation_date_set", mapOf("millis" to today() + 3 * DAY)).status)
        assertEquals(true, call("p1", "donation_date_set", mapOf("millis" to t0)).body["unchanged"].booleanValue())
        val back = call("p1", "donation_date_set", mapOf("millis" to t0 - 200 * DAY))
        assertEquals(409, back.status)
        assertEquals("confirm_needed", back.code)
        val ok = call("p1", "donation_date_set", mapOf("millis" to t0 - 200 * DAY, "confirmed" to true))
        assertEquals(200, ok.status, ok.toString())
        assertEquals("backward", ok.body["risk"].textValue())
        assertEquals(t0 - 200 * DAY, lastDonation("p1"))
        val fwd = call("p1", "donation_date_set", mapOf("millis" to t0 - 150 * DAY))
        assertEquals(409, fwd.status) // short gap
        val clear = call("p1", "donation_date_set", mapOf("millis" to null, "confirmed" to true))
        assertEquals(200, clear.status)
        assertNull(lastDonation("p1"))
        val first = call("p1", "donation_date_set", mapOf("millis" to t0))
        assertTrue(first.body.has("risk") && first.body["risk"].isNull)
        assertEquals(3L, row("SELECT count(*) AS n FROM donation_date_log WHERE uid = 'p1'")["n"])
        assertEquals(429, call("p1", "donation_date_set", mapOf("millis" to t0 + DAY, "confirmed" to true)).status)
    }

    @Test
    fun `RequestEnd - donorLeft fixes the counts and tells the requester`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111", "lang" to "en"))
        donor("a", "O+")
        donor("b", "O+")
        request("r1", "req", bags = 1, accepted = 0)
        for (d in listOf("a", "b")) assertEquals(200, http("/api/respond", d, mapOf("requestId" to "r1")).status)
        assertEquals(200, call("req", "confirm_donor", mapOf("requestId" to "r1", "donorUid" to "a")).status)
        requestEnd.donorLeft("r1", "a")
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_responses WHERE request_id = 'r1' AND donor_uid = 'a'")["n"])
        assertEquals(1, st("r1")["accepted_count"])
        assertEquals(0, st("r1")["confirmed_count"])
        assertEquals(false, st("r1")["fulfilled"])
        val p = pushedTo("req").last()
        assertEquals("A donor is no longer available", p["title"])
        assertEquals("A confirmed donor left the app. Please confirm another from My Request.", p["body"])
        assertEquals(0, requestEnd.notifyEnded("r1", "H", "O+", "closed", emptyList()))
        assertEquals(1, requestEnd.notifyEnded("r1", "H", "O+", "expired"))
        assertEquals("রিকোয়েস্টের সময় শেষ", pushedTo("b").last()["title"])
    }

    @Test
    fun `date corrections - admin list and resolve (Bearer or web cookie)`() {
        admin()
        user("u1", mapOf("name" to "ইউ", "last_donation_ms" to today() - 40 * DAY))
        jdbc.update(
            "INSERT INTO correction_requests (id, uid, name, reason, current_ms, requested_ms, status) VALUES ('c1', 'u1', 'ইউ', 'ভুল', ?, ?, 'pending')",
            today() - 40 * DAY, today() - 130 * DAY,
        )
        user("plain", emptyMap())
        assertEquals(403, http("/api/admin/corrections", "plain", emptyMap()).status)
        assertEquals("Admins only", http("/api/admin/corrections", "plain", emptyMap(), lang = "en").error)
        assertEquals(401, http("/api/admin/corrections", null, emptyMap()).status)
        val list = http("/api/admin/corrections", ADMIN, emptyMap())
        assertEquals(200, list.status, list.toString())
        assertEquals("c1", list.body["items"][0]["id"].textValue())
        assertEquals(today() - 130 * DAY, list.body["items"][0]["requestedMillis"].longValue())
        assertEquals("id ঠিক নেই", http("/api/admin/resolve", ADMIN, mapOf("id" to "")).error)
        assertEquals(404, http("/api/admin/resolve", ADMIN, mapOf("id" to "nope")).status)
        val r = http("/api/admin/resolve", ADMIN, mapOf("id" to "c1", "approve" to true))
        assertEquals(200, r.status, r.toString())
        assertEquals("u1", r.body["uid"].textValue())
        assertEquals(today() - 130 * DAY, lastDonation("u1"))
        assertEquals("approved", row("SELECT status FROM correction_requests WHERE id = 'c1'")["status"])
        assertTrue(inbox("u1").contains("তারিখ সংশোধন হয়েছে"))
        assertEquals(1L, row("SELECT count(*) AS n FROM admin_log WHERE action = 'correction_resolve'")["n"])
        assertEquals(true, http("/api/admin/resolve", ADMIN, mapOf("id" to "c1")).body["already"].booleanValue())
        assertEquals(0, http("/api/admin/corrections", ADMIN, emptyMap()).body["items"].size())
    }
}
