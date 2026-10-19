package com.lifolo.care

import com.lifolo.appointments.P2TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * careaccess.js: caregivers by Lifolo email (≤ 2), accept/decline, remove (either side), family members; what a caregiver
 * may and may not do for the patient (rxflow / demo-health / labtests care parts).
 */
class CareIT : P2TestBase() {
    @Test
    fun `caregivers - invite, accept, act for the patient, removed means revoked`() {
        person("pat", "রহিম", "pat@example.com")
        person("cg", "রুমা", "cg@example.com")
        person("other", "করিম", "other@example.com")
        person("third", "তৃতীয়", "third@example.com")
        assertEquals("তাঁর ইমেইল দিন", call("pat", "care_invite", mapOf("email" to "not-an-email")).error)
        assertEquals("নিজেকে যোগ করা যায় না", call("pat", "care_invite", mapOf("email" to "PAT@example.com")).error)
        val none = call("pat", "care_invite", mapOf("email" to "nobody@example.com"))
        assertEquals(404, none.status)
        assertEquals("এই ইমেইলে কোনো Lifolo অ্যাকাউন্ট নেই। আগে তাঁকে Lifolo খুলে লগইন করতে বলুন।", none.error)
        // an Auth account that never used the server is fine too
        identity.add("fresh", "fresh@example.com")
        ok("pat", "care_invite", mapOf("email" to " CG@example.com "))
        assertEquals(1, pushesTo("cg", "রহিম আপনাকে কেয়ারগিভার করতে চান").size)
        assertEquals("আগেই যোগ করা আছে", call("pat", "care_invite", mapOf("email" to "cg@example.com")).error)
        val invited = ok("cg", "care_list")["caringFor"][0]
        assertEquals(listOf("pat", "cg", "রহিম", "রুমা", "cg@example.com", "invited"), listOf("patientUid", "caretakerUid", "patientName", "caretakerName", "caretakerEmail", "status").map { invited[it].textValue() })
        // not yet accepted: no acting for the patient
        assertEquals("care_revoked", call("cg", "my_appointments", mapOf("asUid" to "pat")).code)
        ok("cg", "care_respond", mapOf("patientUid" to "pat", "accept" to true))
        assertEquals(1, pushesTo("pat", "রুমা এখন আপনার কেয়ারগিভার").size)
        val active = ok("pat", "care_list")["caretakers"][0]
        assertEquals("active", active["status"].textValue())
        assertTrue(active["acceptedAt"].longValue() > 0)
        assertEquals(404, call("cg", "care_respond", mapOf("patientUid" to "nobody", "accept" to true)).status)

        // what a caregiver may do for the patient, and what not
        ok("cg", "my_appointments", mapOf("asUid" to "pat"))
        ok("cg", "family_get", mapOf("asUid" to "pat"))
        for (a in listOf("care_invite", "family_set", "care_remove", "ask", "badge_apply")) {
            assertEquals(403, call("cg", a, mapOf("asUid" to "pat", "email" to "other@example.com", "family" to emptyList<Any>())).status, a)
        }
        // up to two caregivers
        ok("pat", "care_invite", mapOf("email" to "fresh@example.com"))
        assertEquals(1, row("SELECT count(*) AS n FROM accounts WHERE uid = 'fresh'")["n"].toString().toInt())
        val third = call("pat", "care_invite", mapOf("email" to "third@example.com"))
        assertEquals(409, third.status)
        assertEquals("সর্বোচ্চ 2 জন কেয়ারগিভার। আগে একজনকে সরান।", third.error)
        // the invited one declines: the link goes
        ok("fresh", "care_respond", mapOf("patientUid" to "pat", "accept" to false))
        assertEquals(1, ok("pat", "care_list")["caretakers"].size())

        // the patient removes the caregiver: at once no more acting for them
        ok("pat", "care_remove", mapOf("caretakerUid" to "cg"))
        val revoked = call("cg", "my_appointments", mapOf("asUid" to "pat"))
        assertEquals(403, revoked.status)
        assertEquals("care_revoked", revoked.code)
        assertEquals(404, call("pat", "care_remove", emptyMap()).status)
        // the caregiver can also step back
        ok("pat", "care_invite", mapOf("email" to "cg@example.com"))
        ok("cg", "care_remove", mapOf("patientUid" to "pat"))
        assertEquals(0, ok("pat", "care_list")["caretakers"].size())
    }

    @Test
    fun `family members - up to 6, checked, ids kept, the caregiver sees them`() {
        person("pat")
        person("cg")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat', 'cg', 'active')")
        assertEquals("সেভ করার কিছু নেই", call("pat", "family_set", mapOf("family" to "x")).error)
        assertEquals("সর্বোচ্চ 6 জন সদস্য", call("pat", "family_set", mapOf("family" to List(7) { mapOf("name" to "x") })).error)
        assertEquals("সদস্য 2: নাম লিখুন", call("pat", "family_set", mapOf("family" to listOf(mapOf("name" to "a"), mapOf("name" to " ")))).error)
        assertEquals("আব্দুল: মোবাইল নম্বর দেখে নিন", call("pat", "family_set", mapOf("family" to listOf(mapOf("name" to "আব্দুল", "phone" to "123")))).error)
        val fam = ok(
            "pat", "family_set",
            mapOf(
                "family" to listOf(
                    mapOf("name" to "আব্দুল করিম", "relation" to "স্বামী", "age" to 60, "gender" to "male", "phone" to "01811000002"),
                    mapOf("id" to "m_abcdef1", "name" to "শিশু", "age" to 200, "gender" to "x"),
                ),
            ),
        )["family"]
        assertTrue(Regex("^m_[0-9a-f]{10}$").matches(fam[0]["id"].textValue()))
        assertEquals(listOf("আব্দুল করিম", "স্বামী", "60", "male", "01811000002"), listOf("name", "relation", "age", "gender", "phone").map { fam[0][it].asText() })
        assertEquals("m_abcdef1", fam[1]["id"].textValue())
        assertTrue(fam[1]["age"].isNull)
        assertEquals("", fam[1]["gender"].textValue())
        assertEquals(fam, ok("pat", "family_get")["family"])
        assertEquals(fam, ok("cg", "family_get", mapOf("asUid" to "pat"))["family"])
        assertEquals(fam, ok("pat", "care_list")["family"])
        // replaced as a whole
        ok("pat", "family_set", mapOf("family" to emptyList<Any>()))
        assertEquals(0, ok("pat", "family_get")["family"].size())
        // no profile yet: the family still saves (Node set(merge) creates the user document)
        identity.add("noprof")
        jdbc.update("INSERT INTO accounts (uid) VALUES ('noprof')")
        ok("noprof", "family_set", mapOf("family" to listOf(mapOf("name" to "মা"))))
        assertEquals(1, ok("noprof", "family_get")["family"].size())
    }
}
