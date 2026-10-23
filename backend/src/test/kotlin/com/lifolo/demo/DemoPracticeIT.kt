package com.lifolo.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * backend/docs/PRACTICE.md §4: the demo world's non-allopathic doctors, physio and psychologist (in-person + online
 * chambers), their appointments — in the demo world only, gone after the wipe.
 */
class DemoPracticeIT : DemoTestBase() {
    @Test
    fun `practice demo - one doctor per system, a physio and a psychologist with two chambers each, separate from the real world`() {
        owner()
        seedAll()
        // one doctor of each non-allopathic system, labelled, never "ডা."
        for ((system, label) in listOf("homeopathy" to "হোমিওপ্যাথি", "unani" to "ইউনানি", "ayurveda" to "আয়ুর্বেদ")) {
            val items = ok(null, "providers", mapOf("type" to "doctor", "district" to GZP, "homeVisit" to false, "system" to system))["items"]
            assertEquals(1, items.size(), system)
            assertEquals(label, items[0]["systemLabel"].textValue())
            assertFalse(items[0]["name"].textValue().startsWith("ডা."), items[0]["name"].textValue())
            assertTrue(items[0]["uid"].textValue().startsWith("demo-x-pr-"))
        }
        // the allopathic list still has the demo doctor, labelled
        val allo = ok(null, "providers", mapOf("type" to "doctor", "district" to GZP, "homeVisit" to false, "system" to "allopathy"))["items"]
        assertEquals("এলোপ্যাথি", allo.first { it["uid"].textValue() == U("doctor") }["systemLabel"].textValue())

        // the physio and the psychologist: an in-person and an online chamber each
        for ((type, key) in listOf("physio" to DemoPractice.PHYSIO, "psychologist" to DemoPractice.PSYCHOLOGIST)) {
            val items = ok(U("patient"), "providers", mapOf("type" to type, "district" to GZP, "homeVisit" to false))["items"]
            val p = items.first { it["uid"].textValue() == X(key) }
            assertEquals(listOf("in_person", "online"), p["chambers"].map { if (it["mode"]?.textValue() == "online") "online" else "in_person" }, type)
        }
        // the physio visits homes; the psychologist does not
        assertTrue(ok(null, "home_providers", mapOf("type" to "physio", "district" to GZP))["items"].any { it["uid"].textValue() == X(DemoPractice.PHYSIO) })
        assertEquals(400, req(null, "home_providers", mapOf("type" to "psychologist", "district" to GZP)).status)

        // appointments: the physio's patient today, the demo patient's private request with the psychologist
        val physioAppts = ok(X(DemoPractice.PHYSIO), "my_appointments")["items"]
        assertTrue(physioAppts.any { it["status"].textValue() == "confirmed" && it["role"].textValue() == "provider" })
        val mine = ok(U("patient"), "my_appointments")["items"].filter { it["type"].textValue() == "psychologist" }
        assertEquals(listOf("pending" to true), mine.map { it["status"].textValue() to it["private"].booleanValue() })
        // the demo caregiver (Ruma, acting for Rahim) does not see it
        val cg = ok(U("caregiver"), "my_appointments", mapOf("asUid" to U("patient")))["items"]
        assertTrue(cg.size() > 0 && cg.none { it["type"].textValue() == "psychologist" })

        // demo and real never meet: a real patient cannot book the demo professionals
        user("realPt", mapOf("name" to "আসল রোগী"))
        val tomorrow = com.lifolo.common.DhakaTime.todayUtcMidnight() + DAY
        for (k in listOf(DemoPractice.PHYSIO, DemoPractice.PSYCHOLOGIST, DemoPractice.SYSTEM_DOCTORS.getValue("homeopathy"))) {
            assertEquals(404, req("realPt", "book", mapOf("providerUid" to X(k), "date" to tomorrow, "chamberIndex" to 0, "note" to "x")).status, k)
        }

        // the wipe takes all of it
        wipe()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM providers WHERE uid LIKE 'demo-%'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM badges WHERE uid LIKE 'demo-%'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointments WHERE id LIKE 'demo-pr-%'", Int::class.java))
    }
}
