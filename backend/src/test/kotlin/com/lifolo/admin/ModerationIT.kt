package com.lifolo.admin

import com.lifolo.admin.kernel.Sanctions
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** moderation.js — report, admin_reports, admin_report_resolve (demo-health / demo-services report steps and the rules) */
class ModerationIT : IntegrationTest() {
    private val OWNER = "modOwner"

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, action, body).also { assertEquals(200, it.status, "$action: $it") }.body

    private fun setup() {
        user(OWNER, emptyMap(), email = "owner@example.com")
        user("asker1", mapOf("name" to "প্রশ্নকারী"))
        user("rep1", mapOf("name" to "রিপোর্টকারী ১"))
        user("rep2", mapOf("name" to "রিপোর্টকারী ২"))
        user("doc1", mapOf("name" to "ডা. এক"))
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, status) VALUES ('doc1', 'doctor', 'A-1', 'হাসপাতাল', 'ডা. এক', 'approved')")
        jdbc.update("INSERT INTO questions (id, uid, author_name, anonymous, text, answer_count) VALUES ('q1', 'asker1', 'প্রশ্নকারী', FALSE, 'বিজ্ঞাপন এখানে', 1)")
        jdbc.update("INSERT INTO questions (id, uid, author_name, anonymous, text) VALUES ('q2', 'asker1', 'প্রশ্নকারী', TRUE, 'গোপন প্রশ্ন')")
        jdbc.update("INSERT INTO answers (id, question_id, uid, author_name, badge, text) VALUES ('a1', 'q1', 'doc1', 'ডা. এক', 'doctor', 'ভুল উত্তর')")
    }

    @Test
    fun `community content - one report per person, grouped, anonymous hidden, removed or kept`() {
        setup()
        assertEquals(400, call("rep1", "report", mapOf("kind" to "x", "id" to "q1")).status)
        assertEquals(404, call("rep1", "report", mapOf("kind" to "question", "id" to "nope")).status)
        ok("rep1", "report", mapOf("kind" to "question", "id" to "q1"))
        ok("rep1", "report", mapOf("kind" to "question", "id" to "q1"))
        ok("rep2", "report", mapOf("kind" to "question", "id" to "q1"))
        ok("rep1", "report", mapOf("kind" to "answer", "id" to "q1", "answerId" to "a1"))
        ok("rep2", "report", mapOf("kind" to "question", "id" to "q2"))
        assertEquals("বিজ্ঞাপন এখানে", row("SELECT snapshot FROM mod_reports WHERE id = 'questions_q1__rep1'")["snapshot"])
        assertEquals(403, call("rep1", "admin_reports").status)
        val items = ok(OWNER, "admin_reports")["items"]
        val q1 = items.first { it["targetPath"].textValue() == "questions/q1" }
        assertEquals(2, q1["count"].intValue())
        assertEquals("প্রশ্নকারী", q1["authorName"].textValue())
        assertEquals("বিজ্ঞাপন এখানে", q1["text"].textValue())
        assertFalse(q1.has("snapshot"), "unchanged text: no snapshot")
        assertEquals("বেনামি", items.first { it["targetPath"].textValue() == "questions/q2" }["authorName"].textValue())
        assertEquals(1, items.first { it["targetPath"].textValue() == "questions/q1/answers/a1" }["count"].intValue())

        // keep the answer; the author edits it later → a new report is possible
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "questions/q1/answers/a1", "kind" to "answer", "remove" to false))
        assertEquals("dismissed", row("SELECT status FROM mod_reports WHERE id = 'questions_q1_answers_a1__rep1'")["status"])
        ok("rep1", "report", mapOf("kind" to "answer", "id" to "q1", "answerId" to "a1"))
        assertEquals("dismissed", row("SELECT status FROM mod_reports WHERE id = 'questions_q1_answers_a1__rep1'")["status"], "not edited: nothing new")
        jdbc.update("UPDATE answers SET edited_at = now() + interval '1 second', text = 'ঠিক করা উত্তর' WHERE id = 'a1'")
        ok("rep1", "report", mapOf("kind" to "answer", "id" to "q1", "answerId" to "a1"))
        assertEquals("open", row("SELECT status FROM mod_reports WHERE id = 'questions_q1_answers_a1__rep1'")["status"])
        assertEquals("ঠিক করা উত্তর", row("SELECT snapshot FROM mod_reports WHERE id = 'questions_q1_answers_a1__rep1'")["snapshot"])

        // remove the question (with its answers); the admin log has it
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "questions/q1", "kind" to "question", "remove" to true))
        assertTrue(row("SELECT id FROM questions WHERE id = 'q1'").isEmpty())
        assertTrue(row("SELECT id FROM answers WHERE id = 'a1'").isEmpty())
        assertEquals(listOf("removed", "removed"), jdbc.queryForList("SELECT status FROM mod_reports WHERE target_path = 'questions/q1'", String::class.java))
        assertEquals("removed", row("SELECT detail FROM admin_log WHERE action = 'admin_report_resolve' AND target = 'questions/q1'")["detail"])
        // reports on deleted things are closed when the list is read
        assertTrue(ok(OWNER, "admin_reports")["items"].none { it["targetPath"].textValue().startsWith("questions/q1") })
        assertEquals("dismissed", row("SELECT status FROM mod_reports WHERE id = 'questions_q1_answers_a1__rep1'")["status"])
        assertEquals(400, call(OWNER, "admin_report_resolve", mapOf("targetPath" to "users/x")).status)
    }

    private fun ambulance(id: String, owner: String) {
        jdbc.update(
            "INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, district, status) VALUES (?, ?, 'মমতা', '01711111111', 'GA-12-0004', 'গাজীপুর', 'approved')",
            id, owner,
        )
    }

    @Test
    fun `listings - only after a call (or a medicine-find answer), a reason, upheld = suspended with a reason and a ban`() {
        setup()
        user("ambOwner", mapOf("name" to "মালিক"))
        ambulance("amb1", "ambOwner")
        val r = call("rep1", "report", mapOf("kind" to "ambulance", "id" to "amb1", "reason" to "দেরি করেছে"))
        assertEquals(403, r.status)
        assertEquals("অ্যাপ থেকে ফোন করার পর রিপোর্ট করতে পারবেন", r.error)
        jdbc.update("INSERT INTO ambulance_calls (uid, ambulance_id) VALUES ('rep1', 'amb1')")
        assertEquals("কী হয়েছে লিখুন", call("rep1", "report", mapOf("kind" to "ambulance", "id" to "amb1")).error)
        ok("rep1", "report", mapOf("kind" to "ambulance", "id" to "amb1", "reason" to "দেড় ঘণ্টা দেরি"))
        val item = ok(OWNER, "admin_reports")["items"].first { it["kind"].textValue() == "ambulance" }
        assertEquals("মমতা · 01711111111 · গাজীপুর\nদেড় ঘণ্টা দেরি", item["text"].textValue())
        assertEquals("দেড় ঘণ্টা দেরি", item["reasons"][0].textValue())
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "ambulances/amb1", "kind" to "ambulance", "remove" to true))
        val a = row("SELECT status, status_reason, suspended_at FROM ambulances WHERE id = 'amb1'")
        assertEquals("suspended", a["status"])
        assertEquals("ব্যবহারকারীর রিপোর্ট যাচাই করে সত্য পাওয়া গেছে", a["status_reason"])
        assertTrue(a["suspended_at"] != null)
        val keys = Sanctions.listingKeys("ambulance", mapOf("vehicleNo" to "GA-12-0004"))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sanctions WHERE key = ? AND listing", Int::class.java, keys[0]))

        // a pharmacy that answered a medicine find may be reported without a call
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES ('rep2', 'দোকান', 'L-9', '01722222222', 'ঢাকা', 'approved')")
        user("buyer1")
        assertEquals(403, call("buyer1", "report", mapOf("kind" to "pharmacy", "id" to "rep2", "reason" to "নেই")).status)
        jdbc.update("INSERT INTO med_finds (id, uid, district, drug_name, expires_at) VALUES ('f1', 'buyer1', 'ঢাকা', 'Napa', now() + interval '1 day')")
        jdbc.update("INSERT INTO med_find_targets (find_id, pharmacy_uid, have, answered_at) VALUES ('f1', 'rep2', TRUE, now())")
        ok("buyer1", "report", mapOf("kind" to "pharmacy", "id" to "rep2", "reason" to "বলেছিল আছে, গিয়ে দেখি নেই"))
        // kept: the report is dismissed, the shop stays
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "pharmacies/rep2", "kind" to "pharmacy", "remove" to false))
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = 'rep2'")["status"])
    }

    @Test
    fun `professionals - chamber reports only in the panel; upheld chamber report suspends, home care report blocks`() {
        setup()
        user("nurse1", mapOf("name" to "নার্স"))
        jdbc.update(
            "INSERT INTO providers (uid, type, name, listed, active, appointments, home_visit_enabled, home_visit_set, home_phone) VALUES ('nurse1', 'nurse', 'নার্স', TRUE, TRUE, TRUE, TRUE, TRUE, '01733333333')",
        )
        // home care: after a call
        assertEquals(403, call("rep1", "report", mapOf("kind" to "homecare", "id" to "nurse1", "reason" to "আসেননি")).status)
        jdbc.update("INSERT INTO home_calls (uid, provider_uid) VALUES ('rep1', 'nurse1')")
        ok("rep1", "report", mapOf("kind" to "homecare", "id" to "nurse1", "reason" to "আসেননি"))
        // the chamber: no call needed, never yourself
        assertEquals(404, call("nurse1", "report", mapOf("kind" to "provider", "id" to "nurse1", "reason" to "x")).status)
        ok("rep2", "report", mapOf("kind" to "provider", "id" to "nurse1", "reason" to "ভুয়া চেম্বার"))
        val app = ok(OWNER, "admin_reports")["items"]
        assertEquals(listOf("homecare"), app.map { it["kind"].textValue() }, "the app list has no chamber reports")
        assertEquals("নার্স: নার্স · 01733333333\nআসেননি", app[0]["text"].textValue())
        val panel = ok(OWNER, "admin_reports", mapOf("panel" to true))["items"]
        assertEquals(setOf("homecare", "provider"), panel.map { it["kind"].textValue() }.toSet())
        // upheld home-care report: home care blocked, the chamber report stays open
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "providers/nurse1", "kind" to "homecare", "remove" to true))
        val p = row("SELECT home_visit_enabled, home_visit_blocked, admin_suspended FROM providers WHERE uid = 'nurse1'")
        assertEquals(false, p["home_visit_enabled"])
        assertEquals(true, p["home_visit_blocked"])
        assertEquals(false, p["admin_suspended"])
        assertEquals("open", row("SELECT status FROM mod_reports WHERE kind = 'provider'")["status"])
        // upheld chamber report: the professional is suspended (reason "report")
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "providers/nurse1", "kind" to "provider", "remove" to true))
        val s = row("SELECT admin_suspended, suspend_reason, listed FROM providers WHERE uid = 'nurse1'")
        assertEquals(true, s["admin_suspended"])
        assertEquals("report", s["suspend_reason"])
        assertEquals(false, s["listed"])
        assertNull(row("SELECT id FROM mod_reports WHERE status = 'open'")["id"])
    }
}
