package com.lifolo.content

import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.appointments.P2TestBase
import com.lifolo.contracts.ContentModeration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * community.js: me, Health Discussion (questions, answers by verified professionals), Health Tips, professional
 * badges (apply, correction, admin list/resolve) — the demo-health / rules / guest cases on seeded data.
 */
class CommunityIT : P2TestBase() {
    @Autowired lateinit var content: ContentModeration

    private val owner = "owner1"
    private val jpeg = "/9j/4AAQSkZJRgABAQ"

    private fun seed() {
        person(owner, "মালিক", "owner@example.com")
        person("pt", "ডেমো রোগী রহিম")
        person("doc", "প্রোফাইলের নাম")
        badge("doc", "doctor", "ডা. ডেমো হাসান")
        person("nurse", "নার্স")
        badge("nurse", "nurse", "নার্স নাসরিন")
    }

    @Test
    fun `me - badge, admin flags, phone check, unread count`() {
        seed()
        val me = ok("doc", "me")
        assertEquals("approved", me["badge"]["status"].textValue())
        assertEquals("doctor", me["badge"]["type"].textValue())
        assertEquals(false, me["badge"]["changePending"].booleanValue())
        assertFalse(me["badge"].has("docs"), "approved without a request: no document list")
        assertEquals(listOf(false, false, false, true), listOf(me["isAdmin"], me["isOwner"], me["phoneCheck"], me["phoneVerified"]).map { it.booleanValue() })
        assertTrue(ok("pt", "me")["badge"].isNull)
        val om = ok(owner, "me")
        assertEquals(true, om["isAdmin"].booleanValue())
        assertEquals(true, om["isOwner"].booleanValue())
        // unread = inbox items after inboxSeenAt
        jdbc.update("INSERT INTO inbox_items (id, uid, title, created_at) VALUES ('i1', 'pt', 'a', now() - interval '2 hour'), ('i2', 'pt', 'b', now())")
        assertEquals(2, ok("pt", "me")["unread"].intValue())
        jdbc.update("INSERT INTO user_state (uid, inbox_seen_at) VALUES ('pt', now() - interval '1 hour')")
        assertEquals(1, ok("pt", "me")["unread"].intValue())
        assertEquals(401, call(null, "me").status)
    }

    @Test
    fun `questions and answers - guests see no names, professionals answer, the asker is told`() {
        seed()
        val q1 = ok("pt", "ask", mapOf("text" to "ডায়াবেটিসে ভাত কতটা খাওয়া যায়?"))["id"].textValue()
        val q2 = ok("pt", "ask", mapOf("text" to "  রাতে ঘুম আসে না\n\n\n\nকী করব?  ", "anonymous" to true))["id"].textValue()
        assertEquals("রাতে ঘুম আসে না\n\nকী করব?", row("SELECT text FROM questions WHERE id = ?", q2)["text"])
        assertEquals("প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)", call("pt", "ask", mapOf("text" to "x".repeat(1001))).error)
        assertEquals("Write your question (up to 1000 characters)", call("pt", "ask", mapOf("text" to ""), lang = "en").error)

        // guests: questions without names
        val guest = ok(null, "questions")["items"]
        assertTrue(guest.all { it["authorName"].isNull })
        val mine = ok("pt", "questions")["items"]
        assertEquals("ডেমো রোগী রহিম", mine.first { it["id"].textValue() == q1 }["authorName"].textValue())
        val sleep = mine.first { it["id"].textValue() == q2 }
        assertTrue(sleep["authorName"].isNull && sleep["mine"].booleanValue())
        assertFalse(ok("doc", "questions")["items"].first { it["id"].textValue() == q1 }["mine"].booleanValue())

        // only verified professionals answer
        assertEquals(403, call("pt", "answer", mapOf("id" to q1, "text" to "উত্তর")).status)
        assertEquals("প্রশ্নটা পাওয়া যায়নি", call("doc", "answer", mapOf("id" to "nope", "text" to "উত্তর")).error)
        val t0 = System.currentTimeMillis()
        clock.fixedMs = t0
        ok("doc", "answer", mapOf("id" to q1, "text" to "ডাক্তারের পরামর্শে পরিমাণ ঠিক করুন।"))
        clock.fixedMs = t0 + 1000
        ok("nurse", "answer", mapOf("id" to q1, "text" to "নার্সের উত্তর"))
        clock.fixedMs = null
        assertEquals(2, pushesTo("pt", "আপনার প্রশ্নের উত্তর এসেছে").size)
        val q = ok("pt", "question", mapOf("id" to q1))
        assertEquals(2, q["question"]["answerCount"].intValue())
        assertEquals(false, q["question"]["anonymous"].booleanValue(), "own question: the hidden-name flag")
        assertEquals(listOf("doctor", "nurse"), q["answers"].map { it["badge"].textValue() })
        // the verified (badge) name, not the profile name
        assertEquals("ডা. ডেমো হাসান", q["answers"][0]["authorName"].textValue())
        assertFalse(ok("doc", "question", mapOf("id" to q1))["question"].has("anonymous"))
        assertEquals(400, call(null, "question", mapOf("id" to "bad id")).status)
        assertEquals(404, call(null, "question", mapOf("id" to "nope")).status)
        // a badge that is gone: no "doctor" next to old answers
        jdbc.update("UPDATE badges SET status = 'rejected' WHERE uid = 'nurse'")
        val after = ok(null, "question", mapOf("id" to q1))["answers"]
        assertTrue(after[1]["badge"].isNull)
        assertEquals("নার্স", after[1]["authorName"].textValue(), "the name stored with the answer")
        val nurseAnswer = after[1]["id"].textValue()
        assertEquals("আপনার পেশাদার ব্যাজ এখন চালু নেই", call("nurse", "edit_answer", mapOf("id" to q1, "answerId" to nurseAnswer, "text" to "নতুন")).error)

        // paging: 30 per page, cursor = createdAt
        for (i in 1..31) jdbc.update("INSERT INTO questions (id, uid, text, created_at) VALUES (?, 'pt', 'q', now() - (? || ' minutes')::interval)", "old$i", (i + 10).toString())
        val page1 = ok(null, "questions")["items"]
        assertEquals(30, page1.size())
        val page2 = ok(null, "questions", mapOf("before" to page1[29]["createdAt"].longValue()))["items"]
        assertEquals(3, page2.size())
    }

    @Test
    fun `edit and delete - authors, admins (logged), reports reopen on edit`() {
        seed()
        val q = ok("pt", "ask", mapOf("text" to "প্রশ্ন"))["id"].textValue()
        ok("doc", "answer", mapOf("id" to q, "text" to "উত্তর"))
        val a = row("SELECT id FROM answers WHERE question_id = ?", q)["id"] as String
        // a dismissed report opens again after an edit
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('r1', ?, 'question', 'doc', 'dismissed')", "questions/$q")
        assertEquals(403, call("doc", "edit_question", mapOf("id" to q, "text" to "অন্য")).status)
        // only the hidden-name flag: no "edited", no reopening
        ok("pt", "edit_question", mapOf("id" to q, "text" to "প্রশ্ন", "anonymous" to true))
        assertEquals(true, row("SELECT anonymous FROM questions WHERE id = ?", q)["anonymous"])
        assertNull(row("SELECT edited_at FROM questions WHERE id = ?", q)["edited_at"])
        assertEquals("dismissed", row("SELECT status FROM mod_reports WHERE id = 'r1'")["status"])
        ok("pt", "edit_question", mapOf("id" to q, "text" to "বদলানো প্রশ্ন"))
        assertEquals("open", row("SELECT status FROM mod_reports WHERE id = 'r1'")["status"])
        assertTrue(ok("pt", "question", mapOf("id" to q))["question"]["editedAt"].longValue() > 0)
        ok("doc", "edit_answer", mapOf("id" to q, "answerId" to a, "text" to "বদলানো উত্তর"))
        assertEquals("বদলানো উত্তর", row("SELECT text FROM answers WHERE id = ?", a)["text"])
        assertEquals(403, call("pt", "edit_answer", mapOf("id" to q, "answerId" to a, "text" to "x")).status)

        // delete: someone else no, an admin yes (and logged)
        assertEquals("অনুমতি নেই", call("pt", "delete_answer", mapOf("id" to q, "answerId" to a)).error)
        ok(owner, "delete_answer", mapOf("id" to q, "answerId" to a))
        assertEquals(0, row("SELECT answer_count FROM questions WHERE id = ?", q)["answer_count"])
        assertEquals("questions/$q/answers/$a", row("SELECT target FROM admin_log WHERE action = 'admin_delete_post'")["target"])
        ok(owner, "delete_answer", mapOf("id" to q, "answerId" to a)) // already gone: ok
        ok("pt", "delete_question", mapOf("id" to q))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM questions", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_log", Int::class.java), "the author's own delete is not logged")

        // ContentModeration (admin_report_resolve removes reported content)
        val q2 = ok("pt", "ask", mapOf("text" to "আরেকটা"))["id"].textValue()
        val t1 = System.currentTimeMillis()
        clock.fixedMs = t1
        ok("doc", "answer", mapOf("id" to q2, "text" to "উত্তর ১"))
        clock.fixedMs = t1 + 1000
        ok("doc", "answer", mapOf("id" to q2, "text" to "উত্তর ২"))
        clock.fixedMs = null
        val a2 = row("SELECT id FROM answers WHERE question_id = ? ORDER BY created_at LIMIT 1", q2)["id"] as String
        content.removeAnswer(q2, a2)
        content.removeAnswer(q2, a2)
        assertEquals(1, row("SELECT answer_count FROM questions WHERE id = ?", q2)["answer_count"])
        content.removeQuestion(q2)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM answers", Int::class.java))
    }

    @Test
    fun `tips - professionals post, the live badge and name, edit and delete`() {
        seed()
        assertEquals(403, call("pt", "post_tip", mapOf("title" to "x", "body" to "y")).status)
        assertEquals("শিরোনাম দিন (১২০ অক্ষরের মধ্যে)", call("doc", "post_tip", mapOf("title" to "", "body" to "y")).error)
        assertEquals("টিপস লিখুন (৩০০০ অক্ষরের মধ্যে)", call("doc", "post_tip", mapOf("title" to "x", "body" to "y".repeat(3001))).error)
        val t = ok("doc", "post_tip", mapOf("title" to "হাত ধোয়া", "body" to "খাবারের আগে সাবান দিয়ে হাত ধুয়ে নিন।"))["id"].textValue()
        val tip = ok(null, "tips")["items"][0]
        assertEquals(listOf("doctor", "ডা. ডেমো হাসান", "false"), listOf(tip["badge"].textValue(), tip["authorName"].textValue(), tip["mine"].asText()))
        assertTrue(ok("doc", "tips")["items"][0]["mine"].booleanValue())
        ok("doc", "edit_tip", mapOf("id" to t, "title" to "হাত ধোয়া", "body" to "নতুন লেখা"))
        assertTrue(ok(null, "tips")["items"][0]["editedAt"].longValue() > 0)
        assertEquals(403, call("nurse", "edit_tip", mapOf("id" to t, "title" to "x", "body" to "y")).status)
        assertEquals(404, call("doc", "edit_tip", mapOf("id" to "nope", "title" to "x", "body" to "y")).status)
        assertEquals(403, call("pt", "delete_tip", mapOf("id" to t)).status)
        ok(owner, "delete_tip", mapOf("id" to t))
        assertEquals(0, ok(null, "tips")["items"].size())
        assertEquals("tips/$t", row("SELECT target FROM admin_log WHERE action = 'admin_delete_post'")["target"])
    }

    @Test
    fun `badges - apply, review, reject with a reason, correction of an approved badge (demo-health)`() {
        seed()
        person("donor", "ডোনার করিম")
        assertEquals("পেশা বেছে নিন", call("donor", "badge_apply", mapOf("type" to "pilot")).error)
        assertEquals("কর্মস্থল লিখুন", call("donor", "badge_apply", mapOf("type" to "doctor", "regNo" to "A-777")).error)
        // a doctor's photos are optional; a nurse's certificate is required
        person("nurse2")
        assertEquals("docs_required", call("nurse2", "badge_apply", mapOf("type" to "nurse", "regNo" to "N-1", "workplace" to "X")).code)
        ok("donor", "badge_apply", mapOf("type" to "doctor", "regNo" to "ডেমো-A-777", "workplace" to "ডেমো হাসপাতাল"))
        assertEquals("আপনার আবেদন যাচাই চলছে", call("donor", "badge_apply", mapOf("type" to "doctor", "regNo" to "x", "workplace" to "y")).error)
        val b = row("SELECT status, name, email, docs_have FROM badges WHERE uid = 'donor'")
        assertEquals(listOf("pending", "ডোনার করিম", "donor@example.com"), listOf(b["status"], b["name"], b["email"]))
        val pend = ok("donor", "me")["badge"]
        assertEquals("pending", pend["status"].textValue())
        assertTrue(pend.has("docs") && pend.has("docsMissing"))

        // a nutritionist with the certificate photo: the panel shows the photo and the checks
        person("nutri", "পুষ্টিবিদ")
        ok("nutri", "badge_apply", mapOf("type" to "nutritionist", "regNo" to "NU-1", "workplace" to "Clinic", "docs" to mapOf("cert" to jpeg)))
        assertEquals(403, call("pt", "admin_badges").status)
        val list = ok(owner, "admin_badges", mapOf("status" to "pending", "panel" to true))["items"]
        val nb = list.first { it["uid"].textValue() == "nutri" }
        assertEquals("nutritionist", nb["type"].textValue())
        assertTrue(nb["verify"]["docs"].first { it["type"].textValue() == "cert" }["has"].booleanValue(), "the certificate photo is there")
        assertFalse(ok(owner, "admin_badges")["items"].first().has("verify"), "the app list has no panel info")
        assertEquals(400, call(owner, "admin_badge_resolve", mapOf("uid" to "nutri", "approve" to true)).status, "no approval without the ticks")
        ok(owner, "admin_badge_resolve", mapOf("uid" to "nutri", "approve" to true, "checks" to VerifySpec.checks("badge", mapOf("type" to "nutritionist")).map { it.id }))
        assertEquals("approved", row("SELECT status FROM badges WHERE uid = 'nutri'")["status"])

        // rejection with a reason (the user sees it)
        ok(owner, "admin_badge_resolve", mapOf("uid" to "donor", "approve" to false, "reason" to "BMDC-তে নম্বর পাওয়া যায়নি"))
        val dme = ok("donor", "me")["badge"]
        assertEquals("rejected", dme["status"].textValue())
        assertEquals("BMDC-তে নম্বর পাওয়া যায়নি", dme["statusReason"].textValue())
        assertTrue(ok(owner, "admin_badges", mapOf("status" to "rejected", "panel" to true))["items"].any { it["uid"].textValue() == "donor" })
        // a new application replaces the rejected one
        ok("donor", "badge_apply", mapOf("type" to "doctor", "regNo" to "A-778", "workplace" to "হাসপাতাল"))
        assertNull(row("SELECT status_reason FROM badges WHERE uid = 'donor'")["status_reason"])

        // a correction of an approved badge: stays approved until reviewed, listed in the panel's pending list
        val corr = ok("doc", "badge_apply", mapOf("type" to "nurse", "regNo" to "A-999", "workplace" to "নতুন হাসপাতাল"))
        assertEquals(true, corr["correction"].booleanValue())
        val docBadge = row("SELECT status, type, change_type, change_reg_no FROM badges WHERE uid = 'doc'")
        assertEquals(listOf("approved", "doctor", "doctor", "A-999"), listOf(docBadge["status"], docBadge["type"], docBadge["change_type"], docBadge["change_reg_no"]), "the profession cannot change")
        assertEquals(true, ok("doc", "me")["badge"]["changePending"].booleanValue())
        val withChange = ok(owner, "admin_badges", mapOf("status" to "pending", "panel" to true))["items"].first { it["uid"].textValue() == "doc" }
        assertEquals("A-999", withChange["change"]["regNo"].textValue())
        assertTrue(withChange.has("verify"))
        assertFalse(ok(owner, "admin_badges", mapOf("status" to "pending"))["items"].any { it["uid"].textValue() == "doc" }, "not in the app list")
        assertEquals(true, ok(owner, "admin_badge_resolve", mapOf("uid" to "nutri", "correction" to true))["unchanged"].booleanValue())
        ok(owner, "admin_badge_resolve", mapOf("uid" to "doc", "correction" to true, "approve" to true, "checks" to VerifySpec.checks("badge", mapOf("type" to "doctor")).map { it.id }))
        val fixed = row("SELECT reg_no, workplace, change_at FROM badges WHERE uid = 'doc'")
        assertEquals(listOf("A-999", "নতুন হাসপাতাল", null), listOf(fixed["reg_no"], fixed["workplace"], fixed["change_at"]))
        assertEquals(1, pushesTo("doc", "আপনার ব্যাজের তথ্য সংশোধন হয়েছে").size)
        // a correction turned down: the request goes, the badge stays
        ok("doc", "badge_apply", mapOf("type" to "doctor", "regNo" to "A-1000", "workplace" to "Y"))
        ok(owner, "admin_badge_resolve", mapOf("uid" to "doc", "correction" to true, "approve" to false))
        assertEquals(listOf("approved", "A-999", null), row("SELECT status, reg_no, change_at FROM badges WHERE uid = 'doc'").values.toList())
        assertEquals(1, pushesTo("doc", "ব্যাজের তথ্য সংশোধনের অনুরোধ গ্রহণ হয়নি").size)
    }
}
