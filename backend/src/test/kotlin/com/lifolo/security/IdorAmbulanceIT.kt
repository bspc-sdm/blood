package com.lifolo.security

import com.lifolo.ambulance.AmbTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Call an ambulance": the request belongs to the patient and to the drivers it was sent to. The patient's phone,
 * name and exact point go to the driver only after accepting; nobody else reads or changes the request.
 */
class IdorAmbulanceIT : AmbTestBase() {
    private val pat = "ar-patient"

    private fun ask(): String {
        val p = at(0.0, 0.0)
        val r = call(
            pat, "ambreq_create",
            mapOf(
                "district" to MYM, "upazilaId" to SADAR, "lat" to p.first, "lng" to p.second, "gps" to true,
                "pickup" to "চরপাড়া মোড়", "note" to "রোগী হাঁটতে পারেন না", "phone" to "01811000077",
            ),
        )
        assertEquals(200, r.status, r.toString())
        return r.body["id"].textValue()
    }

    @Test
    fun `ambulance requests - the patient and the drivers it was sent to`() {
        person(pat, name = "রোগীর গোপন নাম", phone = "01811000077")
        person("ar-stranger")
        amb("ar1", "ar-d1", base = at(1.0, 0.0))
        amb("ar2", "ar-d2", base = at(2.0, 0.0))
        amb("arFar", "ar-dFar", district = DHAKA, base = null, upazilaId = null)
        val id = ask()
        val body = mapOf("id" to id)
        // a stranger and a driver it was not sent to: not found, cannot change it
        for (u in listOf("ar-stranger", "ar-dFar")) {
            for (a in listOf("ambreq_status", "ambreq_cancel", "ambreq_agree", "ambreq_budget", "ambreq_byphone", "ambreq_replace")) {
                val r = call(u, a, body + mapOf("reason" to "x", "maxFare" to 500))
                assertEquals(404, r.status, "$u $a: $r")
            }
            for (a in listOf("ambreq_accept", "ambreq_decline", "ambreq_step")) {
                val r = call(u, a, body + mapOf("step" to "arrived"))
                assertEquals(404, r.status, "$u $a: $r")
            }
            assertFalse(ok(u, "ambreq_driver").toString().contains(id))
        }
        // a driver it was sent to sees the area, not the phone, name or exact point before accepting
        val before = driverList("ar-d1").first { it["id"].textValue() == id }.toString()
        assertFalse(before.contains("01811000077") || before.contains("রোগীর গোপন নাম"), before)
        assertFalse(before.contains("\"lat\""), before)
        // the drivers cannot act as the patient
        assertEquals(404, call("ar-d1", "ambreq_status", body).status)
        assertEquals(404, call("ar-d1", "ambreq_cancel", body).status)
        // after accepting: the phone for that driver only
        ok("ar-d1", "ambreq_accept", body)
        val after = driverList("ar-d1").first { it["id"].textValue() == id }.toString()
        assertTrue(after.contains("01811000077"), after)
        assertFalse(driverList("ar-d2").toString().contains("01811000077"))
        // the other driver cannot move it on
        val step = call("ar-d2", "ambreq_step", body + mapOf("step" to "done"))
        assertTrue(step.status in 400..409, step.toString())
        assertEquals("accepted", reqRow(id)["status"])
    }

    @Test
    fun `an ambulance listing is changed by its owner only`() {
        amb("own1", "ar-owner")
        person("ar-other")
        call("ar-other", "ambulance_available", mapOf("id" to "own1", "available" to false))
        call("ar-other", "ambulance_delete", mapOf("id" to "own1"))
        call("ar-other", "ambulance_save", mapOf("id" to "own1", "name" to "দখল", "phone" to "01711000000"))
        val r = ambRow("own1")
        assertEquals("গাড়ি own1", r["name"])
        assertEquals(true, r["available"])
        assertEquals("ar-owner", r["owner_uid"])
        assertTrue(ok("ar-other", "ambulance_mine")["items"].none { it["id"].textValue() == "own1" })
        assertFalse(ok("ar-other", "ambulance_stats").toString().contains("own1"))
    }
}
