package com.lifolo.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of server/test/demo.test.js (seed, one-click login, wipe, step by step) and demo-health.test.js (Q&A, tips,
 * badges, reports, tickets, the bell list, caregivers, the admin overview, account deletion and the wipe).
 */
class DemoIT : DemoTestBase() {
    @Test
    fun `seed - every account, one-click login, only the owner`() {
        owner()
        assertEquals(false, verify(mapOf("demoRoles" to true)).body["active"].booleanValue())
        assertEquals(404, verify(mapOf("demo" to "doctor")).status)

        val r = seedAll()
        assertEquals(DemoWorld.ROLES.size, r["users"].size())
        assertFalse(DemoWorld.ROLES.any { it.key == "admin" }, "no admin in the demo")
        for (u in r["users"]) assertTrue(identity.users.containsKey(U(u["key"].textValue())), u["key"].textValue())
        assertTrue(r["docs"].intValue() > 100)

        val list = verify(mapOf("demoRoles" to true)).body
        assertEquals(true, list["active"].booleanValue())
        assertEquals(DemoWorld.ROLES.size, list["roles"].size())
        val login = verify(mapOf("demo" to "doctor"))
        assertEquals(200, login.status)
        assertEquals("ctok_demo-doctor", login.body["token"].textValue())
        assertEquals(404, verify(mapOf("demo" to "admin")).status)

        assertEquals(403, req(U("patient"), "admin_demo_seed").status)
        assertEquals("শুধু মালিক", req(U("patient"), "admin_demo_wipe").error)
        val st = ok(OWNER, "admin_demo_status")
        assertTrue(st["active"].booleanValue())
        assertFalse(st["partial"].booleanValue())
        assertEquals(DemoWorld.ROLES.size, st["roles"].size())
    }

    @Test
    fun `wipe - every demo row and account goes, nothing else`() {
        owner()
        user("realuser0001", mapOf("name" to "আসল"))
        seedAll()
        val r = ok(OWNER, "admin_demo_wipe")
        assertTrue(r["users"].intValue() >= 10)
        assertEquals(emptyList<String>(), demoLeft())
        assertFalse(row("SELECT uid FROM user_profiles WHERE uid = 'realuser0001'").isEmpty())
        assertEquals(404, verify(mapOf("demo" to "doctor")).status, "no login after the wipe")
        assertFalse(ok(OWNER, "admin_demo_status")["active"].booleanValue())
        assertEquals("seed", row("SELECT detail FROM admin_log WHERE action = 'admin_demo_seed'")["detail"])
        assertEquals("wipe", row("SELECT detail FROM admin_log WHERE action = 'admin_demo_wipe'")["detail"])
    }

    @Test
    fun `step by step like the panel, then wipe`() {
        owner()
        var step: Int? = 0
        val seen = ArrayList<Int>()
        while (step != null) {
            val r = ok(OWNER, "admin_demo_seed", mapOf("step" to step))
            assertEquals(6, r["steps"].intValue())
            seen.add(step)
            step = r["next"]?.takeIf { !it.isNull }?.intValue()
            // the login list stays off while it is half built
            if (step != null) assertEquals(false, verify(mapOf("demoRoles" to true)).body["active"].booleanValue())
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), seen)
        assertEquals(true, verify(mapOf("demoRoles" to true)).body["active"].booleanValue())
        val st = ok(OWNER, "admin_demo_status")
        assertTrue(st["docs"].intValue() > 100 && !st["partial"].booleanValue())
        assertEquals(400, req(OWNER, "admin_demo_seed", mapOf("step" to 6)).status)
        // a later step without step 0 → 409
        wipe()
        assertEquals("আগে প্রথম ধাপ চালান", req(OWNER, "admin_demo_seed", mapOf("step" to 2)).error)
        assertEquals(emptyList<String>(), demoLeft())
    }

    @Test
    fun `seeding again replaces the old demo`() {
        owner()
        seedAll()
        val n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE is_demo", Int::class.java)
        seedAll()
        assertEquals(n, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE is_demo", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pharmacies WHERE uid = ?", Int::class.java, U("pharmacy")))
    }

    // ------------------------------------------------------------------ demo-health.test.js

    @Test
    fun `health demo - read, the tester's steps, and nothing left after the wipe`() {
        owner()
        user("realuser0002", mapOf("name" to "আসল ব্যবহারকারী"))
        jdbc.update("INSERT INTO questions (id, uid, author_name, text) VALUES ('realq1', 'realuser0002', 'আসল', 'আসল প্রশ্ন')")
        seedAll()

        // ---- guests: questions and tips, not the askers' names ----
        val gq = ok(null, "questions")["items"]
        assertTrue(gq.any { it["id"].textValue() == "demo-q-diabetes" } && gq.any { it["id"].textValue() == "demo-q-ad" })
        assertTrue(gq.all { it["authorName"].isNull }, "guests do not see names")
        val gt = ok(null, "tips")["items"]
        val dengue = gt.first { it["id"].textValue() == "demo-tip-dengue" }
        assertEquals("doctor", dengue["badge"].textValue())
        assertEquals("ডা. ডেমো হাসান", dengue["authorName"].textValue())
        assertEquals("nurse", gt.first { it["id"].textValue() == "demo-tip-insulin" }["badge"].textValue())

        // ---- the patient: own questions, answers, badges ----
        val pq = ok(U("patient"), "questions")["items"]
        val sleep = pq.first { it["id"].textValue() == "demo-q-sleep" }
        assertTrue(sleep["mine"].booleanValue() && sleep["authorName"].isNull && sleep["answerCount"].intValue() == 1, "own anonymous question")
        assertEquals("ডেমো রোগী রহিম", pq.first { it["id"].textValue() == "demo-q-diabetes" }["authorName"].textValue())
        val qd = ok(U("patient"), "question", mapOf("id" to "demo-q-diabetes"))
        assertEquals(listOf("doctor", "expert"), qd["answers"].map { it["badge"].textValue() })
        assertEquals(2, qd["question"]["answerCount"].intValue())
        assertEquals(0, ok(U("patient"), "question", mapOf("id" to "demo-q-knee"))["answers"].size())
        val meDoc = ok(U("doctor"), "me")
        assertEquals("approved", meDoc["badge"]["status"].textValue())
        assertEquals("doctor", meDoc["badge"]["type"].textValue())
        assertEquals("nurse", ok(U("homecare"), "me")["badge"]["type"].textValue())
        assertTrue(ok(U("patient"), "me")["badge"].isNull)

        // ---- the tester: the patient asks (cannot answer); doctor/nurse answer and post tips ----
        val asked = ok(U("patient"), "ask", mapOf("text" to "ডেমো পরীক্ষা: গ্যাস্ট্রিকের ওষুধ কতদিন খাওয়া যায়?", "anonymous" to false))
        assertEquals(403, req(U("patient"), "answer", mapOf("id" to "demo-q-knee", "text" to "উত্তর")).status)
        ok(U("doctor"), "answer", mapOf("id" to "demo-q-knee", "text" to "ডেমো উত্তর: প্রতিদিন হালকা হাঁটা।"))
        ok(U("doctor"), "answer", mapOf("id" to asked["id"].textValue(), "text" to "ডেমো উত্তর: ২ সপ্তাহের বেশি নয়।"))
        ok(U("homecare"), "answer", mapOf("id" to "demo-q-bedsore", "text" to "ডেমো উত্তর: প্রতি ২ ঘণ্টায় পাশ ফেরান।"))
        assertEquals(1, ok(U("patient"), "question", mapOf("id" to "demo-q-knee"))["question"]["answerCount"].intValue())
        ok(U("doctor"), "post_tip", mapOf("title" to "ডেমো টিপস: হাত ধোয়া", "body" to "খাবারের আগে সাবান দিয়ে হাত ধুয়ে নিন।"))
        assertEquals(403, req(U("patient"), "post_tip", mapOf("title" to "x", "body" to "y")).status)
        // anonymous question: even the admin sees "বেনামি"
        ok(U("caregiver"), "report", mapOf("kind" to "answer", "id" to "demo-q-fever", "answerId" to "demo-a-fever-exp"))
        ok(U("caregiver"), "report", mapOf("kind" to "question", "id" to "demo-q-sleep"))

        // ---- the owner: reports ----
        val reps = ok(OWNER, "admin_reports")["items"]
        val ad = reps.first { it["targetPath"].textValue() == "questions/demo-q-ad" }
        assertEquals(2, ad["count"].intValue())
        assertEquals("ডেমো হারবাল সেন্টার", ad["authorName"].textValue())
        assertEquals(2, reps.first { it["targetPath"].textValue() == "questions/demo-q-fever/answers/demo-a-fever-exp" }["count"].intValue())
        assertEquals("বেনামি", reps.first { it["targetPath"].textValue() == "questions/demo-q-sleep" }["authorName"].textValue())
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "questions/demo-q-ad", "kind" to "question", "remove" to true))
        assertTrue(row("SELECT id FROM questions WHERE id = 'demo-q-ad'").isEmpty(), "the advertisement is gone")
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "questions/demo-q-fever/answers/demo-a-fever-exp", "kind" to "answer", "remove" to false))
        assertFalse(row("SELECT id FROM answers WHERE id = 'demo-a-fever-exp'").isEmpty(), "a kept answer stays")

        // ---- the owner: badges (a nutritionist waiting, a trainer rejected) ----
        val pend = ok(OWNER, "admin_badges", mapOf("status" to "pending", "panel" to true))["items"]
        val nb = pend.first { it["uid"].textValue() == X("h-nutri") }
        assertEquals("nutritionist", nb["type"].textValue())
        assertTrue(nb["verify"]["docs"].first { it["type"].textValue() == "cert" }["has"].booleanValue(), "the certificate photo is there")
        val vd = ok(OWNER, "admin_verify_docs", mapOf("kind" to "badge", "id" to X("h-nutri")))
        assertTrue(vd["photos"].first { it["type"].textValue() == "cert" }["data"].textValue().startsWith("/9j/"))
        assertEquals(400, req(OWNER, "admin_badge_resolve", mapOf("uid" to X("h-nutri"), "approve" to true)).status, "no approval without the ticks")
        ok(OWNER, "admin_badge_resolve", mapOf("uid" to X("h-nutri"), "approve" to true, "checks" to listOf("cert_name", "cert_relevant", "workplace")))
        assertEquals("approved", row("SELECT status FROM badges WHERE uid = ?", X("h-nutri"))["status"])
        assertTrue(ok(OWNER, "admin_badges", mapOf("status" to "rejected", "panel" to true))["items"].any { it["uid"].textValue() == X("h-trainer") })
        // the donor applies (doctor — photos optional), the owner rejects with a reason
        ok(U("donor"), "badge_apply", mapOf("type" to "doctor", "regNo" to "ডেমো-A-777", "workplace" to "ডেমো হাসপাতাল"))
        ok(OWNER, "admin_badge_resolve", mapOf("uid" to U("donor"), "approve" to false, "reason" to "BMDC-তে নম্বর পাওয়া যায়নি"))
        val dme = ok(U("donor"), "me")
        assertEquals("rejected", dme["badge"]["status"].textValue())
        assertEquals("BMDC-তে নম্বর পাওয়া যায়নি", dme["badge"]["statusReason"].textValue())

        // ---- help & appeals ----
        val mine = ok(U("patient"), "tickets_mine")["items"]
        for (id in listOf("demo-t-open", "demo-t-answered", "demo-t-closed")) assertTrue(mine.any { it["id"].textValue() == id }, id)
        val openT = ok(OWNER, "admin_tickets", mapOf("status" to "open"))["items"]
        assertTrue(openT.any { it["id"].textValue() == "demo-t-open" } && openT.any { it["id"].textValue() == "demo-t-appeal" })
        ok(OWNER, "admin_ticket_reply", mapOf("id" to "demo-t-open", "text" to "ডেমো উত্তর: ৪৮ ঘণ্টায় সাড়া না দিলে অনুরোধ নিজে থেকে শেষ হয়।"))
        assertEquals("answered", row("SELECT status FROM tickets WHERE id = 'demo-t-open'")["status"])
        ok(U("patient"), "ticket_create", mapOf("category" to "other", "text" to "ডেমো পরীক্ষা: অ্যাপে বাংলা লেখা ছোট দেখায়।"))
        assertEquals(429, req(U("patient"), "ticket_create", mapOf("category" to "other", "text" to "আরেকটা ডেমো সমস্যা লিখলাম")).status, "at most 3 open")

        // ---- the bell list ----
        val inb = ok(U("patient"), "inbox")["items"]
        assertTrue(inb.size() >= 5)
        fun idx(p: (com.fasterxml.jackson.databind.JsonNode) -> Boolean) = inb.indexOfFirst(p)
        assertFalse(inb.first { it["data"]["questionId"]?.textValue() == "demo-q-sleep" }["read"].booleanValue(), "a new answer is unread")
        assertFalse(inb.first { it["data"]["ticketId"]?.textValue() == "demo-t-answered" }["read"].booleanValue())
        assertTrue(inb.first { it["data"]["type"].textValue() == "care" }["read"].booleanValue(), "older ones are read")
        assertTrue(idx { it["data"]["questionId"]?.textValue() == "demo-q-sleep" } < idx { it["data"]["type"].textValue() == "care" }, "newest first")
        assertTrue(ok(U("caregiver"), "inbox")["items"].count { it["data"]["type"].textValue() == "care" } >= 2)
        assertTrue(ok(U("donor"), "inbox")["items"].any { it["data"]["questionId"]?.textValue() == "demo-q-donate" })
        ok(U("patient"), "inbox_read")
        assertTrue(ok(U("patient"), "inbox")["items"].all { it["read"].booleanValue() })

        // ---- caregivers ----
        val cl = ok(U("caregiver"), "care_list")
        assertEquals("active", cl["caringFor"].first { it["patientUid"].textValue() == U("patient") }["status"].textValue())
        assertEquals("invited", cl["caringFor"].first { it["patientUid"].textValue() == X("h-mother") }["status"].textValue())
        assertEquals(2, ok(U("patient"), "care_list")["family"].size())
        ok(U("caregiver"), "rx_mine", mapOf("asUid" to U("patient")))
        ok(U("caregiver"), "my_appointments", mapOf("asUid" to U("patient")))
        ok(U("caregiver"), "family_get", mapOf("asUid" to U("patient")))
        for (a in listOf("ask", "delete_account", "family_set", "care_invite", "ticket_create")) {
            assertEquals(403, req(U("caregiver"), a, mapOf("asUid" to U("patient"), "text" to "x")).status, "$a not for the patient")
        }
        ok(U("caregiver"), "care_respond", mapOf("patientUid" to X("h-mother"), "accept" to true))
        // no hint whether an address has an account; a demo patient's invitations to unknown addresses are not kept
        assertEquals(200, req(U("patient"), "care_invite", mapOf("email" to "nobody@lifolo-demo.test")).status)
        ok(U("patient"), "care_invite", mapOf("email" to DemoWorld.demoEmail("donor")))
        assertEquals("invited", ok(U("donor"), "care_list")["caringFor"][0]["status"].textValue())
        assertEquals(409, req(U("patient"), "care_invite", mapOf("email" to DemoWorld.demoEmail("lab"))).status, "at most 2")
        ok(U("patient"), "care_remove", mapOf("caretakerUid" to U("caregiver")))
        val revoked = req(U("caregiver"), "rx_mine", mapOf("asUid" to U("patient")))
        assertEquals(403, revoked.status)
        assertEquals("care_revoked", revoked.code)

        // ---- the admin overview and one person's history ----
        val ov = ok(OWNER, "admin_overview")
        assertTrue(ov["corrections"]["items"].any { it["id"].textValue() == "demo-corr-masud" })
        assertTrue(ov["tickets"]["items"].any { it["id"].textValue() == "demo-t-appeal" })
        val act = ok(OWNER, "admin_user_section", mapOf("uid" to U("patient"), "section" to "community"))
        assertTrue(act["questions"].any { it["id"].textValue() == "demo-q-diabetes" })
        assertTrue(act["reportsMade"].size() >= 2)
        assertTrue(ok(OWNER, "admin_user_section", mapOf("uid" to U("patient"), "section" to "support"))["items"].size() >= 4)

        // ---- account deletion (caregiver): 30 days, then the one-click demo login restores ----
        val del = ok(U("caregiver"), "delete_account")
        assertTrue(del["deleteAt"].longValue() > System.currentTimeMillis() + 29 * DAY)
        val relog = verify(mapOf("demo" to "caregiver"))
        assertEquals(200, relog.status)
        assertEquals(true, relog.body["restored"].booleanValue(), "the demo login cancels the deletion like a normal login")
        assertNull(row("SELECT delete_at FROM accounts WHERE uid = ?", U("caregiver"))["delete_at"])

        // ---- the wipe: the demo and everything made while testing ----
        ok(OWNER, "admin_demo_wipe")
        assertEquals(emptyList<String>(), demoLeft())
        for (t in listOf("questions", "tips", "mod_reports", "tickets", "care_links", "inbox_items", "badges", "verify_docs", "correction_requests")) {
            val rest = jdbc.queryForObject("SELECT count(*) FROM $t" + if (t == "questions") " WHERE id <> 'realq1'" else "", Int::class.java)
            assertEquals(0, rest, t)
        }
        assertFalse(row("SELECT id FROM questions WHERE id = 'realq1'").isEmpty(), "the real question stays")
        assertFalse(row("SELECT uid FROM user_profiles WHERE uid = 'realuser0002'").isEmpty())
    }

    // the caregiver's journey (demo-health second test): as the patient — serial, prescription, report, pharmacy, lab
    @Test
    fun `caregiver journey - serial, prescription, report, pharmacy and lab for the patient`() {
        owner()
        seedAll()
        val cg = U("caregiver")
        val p = U("patient")
        val asP = mapOf("asUid" to p)
        assertEquals("active", ok(cg, "care_list")["caringFor"].first { it["patientUid"].textValue() == p }["status"].textValue())

        val rxs = ok(cg, "rx_mine", asP)["items"]
        assertTrue(rxs.size() >= 3, "Rahim's prescriptions")
        assertEquals(0, ok(cg, "rx_mine")["items"].size(), "without asUid: Ruma's own — none")
        assertEquals(U("doctor"), ok(cg, "rx_get", asP + mapOf("id" to "demo-rx-b"))["item"]["doctorUid"].textValue())

        val act = ok(cg, "rx_activity", asP + mapOf("rxId" to "demo-rx-b"))
        assertTrue(act["labOrders"].any { it["id"].textValue() == "demo-rx-lab-me-lipid" && it["status"].textValue() == "reported" })
        val rep = ok(cg, "lab_order_get", asP + mapOf("id" to "demo-rx-lab-me-lipid"))
        assertTrue(rep["item"]["report"]["items"].size() > 0)
        assertEquals(404, req(cg, "lab_order_get", mapOf("id" to "demo-rx-lab-me-lipid")).status, "not without acting for the patient")
        assertTrue(ok(cg, "labs", asP + mapOf("district" to GZP))["items"].any { it["id"].textValue() == U("lab") })
        val lo = ok(cg, "rx_send_lab", asP + mapOf("rxId" to "demo-rx-a", "labId" to U("lab")))
        assertEquals(p, row("SELECT patient_uid FROM lab_orders WHERE id = ?", lo["id"].textValue())["patient_uid"])
        // the CBC sample of demo-rx-b was already taken at this lab
        assertEquals(409, req(cg, "rx_send_lab", asP + mapOf("rxId" to "demo-rx-b", "labId" to U("lab"))).status)

        val opts = ok(cg, "rx_pharmacy_options", asP + mapOf("rxId" to "demo-rx-b", "district" to GZP, "mode" to "shop"))["items"]
        assertTrue(opts.any { it["id"].textValue() == U("pharmacy") })
        val ord = ok(cg, "rx_send_pharmacy", asP + mapOf("rxId" to "demo-rx-b", "pharmacyId" to U("pharmacy"), "mode" to "shop"))
        val od = row("SELECT patient_uid, by_caretaker FROM rx_orders WHERE id = ?", ord["id"].textValue())
        assertEquals(p, od["patient_uid"])
        assertEquals(cg, od["by_caretaker"])
        ok(cg, "rx_order_withdraw", asP + mapOf("id" to ord["id"].textValue()))

        assertTrue(ok(cg, "providers", asP + mapOf("type" to "doctor", "district" to GZP))["items"].any { it["uid"].textValue() == U("doctor") })
        val today = com.lifolo.common.DhakaTime.todayUtcMidnight()
        val mineBefore = ok(cg, "my_appointments", asP)["items"]
        // the demo doctor's main chamber is open every day
        var date: Long? = null
        for (i in 1 until 30) {
            val d = today + i * DAY
            if (mineBefore.none { it["date"].longValue() == d && it["status"].textValue() != "cancelled" }) {
                date = d
                break
            }
        }
        val bk = ok(cg, "book", asP + mapOf("providerUid" to U("doctor"), "chamberIndex" to 0, "date" to date, "note" to "ডেমো: রুমা রহিমের হয়ে সিরিয়াল নিলেন"))
        val ap = row("SELECT patient_uid, patient_name, by_caretaker FROM appointments WHERE id = ?", bk["id"].textValue())
        assertEquals(p, ap["patient_uid"])
        assertEquals("ডেমো রোগী রহিম", ap["patient_name"])
        assertEquals(cg, ap["by_caretaker"])
        val appts = ok(cg, "my_appointments", asP)["items"]
        assertTrue(appts.any { it["id"].textValue() == bk["id"].textValue() && it["status"].textValue() == "pending" })
        assertTrue(appts.any { it["status"].textValue() == "confirmed" })
        assertTrue(appts.count { it["role"].textValue() == "patient" } >= 5)
        assertTrue(ok(U("doctor"), "my_appointments")["items"].any { it["id"].textValue() == bk["id"].textValue() && it["otherName"].textValue() == "ডেমো রোগী রহিম" })
        ok(cg, "appointment_update", asP + mapOf("id" to bk["id"].textValue(), "op" to "cancel"))
        assertEquals("cancelled", row("SELECT status FROM appointments WHERE id = ?", bk["id"].textValue())["status"])
        assertEquals(2, ok(cg, "family_get", asP)["family"].size())

        for (a in listOf("med_find", "pharmacy_called", "family_set", "inbox", "request_create", "ask", "report", "ticket_create", "care_invite", "care_remove", "delete_account")) {
            assertEquals(403, req(cg, a, asP + mapOf("text" to "x")).status, a)
        }
        ok(p, "care_remove", mapOf("caretakerUid" to cg))
        for (a in listOf("rx_mine", "my_appointments", "book")) {
            val r = req(cg, a, asP)
            assertEquals(403, r.status, a)
            assertEquals("care_revoked", r.code)
        }
        ok(OWNER, "admin_demo_wipe")
        for (t in listOf("appointments", "rx_orders", "lab_orders")) assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $t", Int::class.java), t)
        assertEquals(emptyList<String>(), demoLeft())
    }
}
