package com.lifolo.ambulance

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ambreq_finish (docs/INVOICES.md, security review I8): the trip's receipt against the fare the patient agreed to */
class AmbFinishIT : AmbTestBase() {
    private val P = "fin-patient"
    private val LOCAL = mapOf("kind" to "upazila", "upazilaId" to SADAR)
    private val base get() = mapOf("district" to MYM, "upazilaId" to SADAR, "lat" to at(0.0, 0.0).first, "lng" to at(0.0, 0.0).second, "gps" to true)

    private fun seed() {
        AmbClock.setDhaka("2026-10-19 12:00")
        person(P)
        amb("a1", "d1", base = at(1.0, 0.0), chart = fc(800, listOf(toDhaka(6000)), oxygen = 300))
    }

    private fun fails(uid: String, action: String, body: Map<String, Any?>, status: Int, code: String? = null) {
        val r = call(uid, action, body)
        assertEquals(status, r.status, "$action: $r")
        if (code != null) assertEquals(code, r.code, "$action: $r")
    }

    /** a quote-mode trip the patient agreed to (driver offered the chart's estimate) */
    private fun agreedTrip(body: Map<String, Any?> = emptyMap()): String {
        val id = call(P, "ambreq_create", base + mapOf("pickup" to "চরপাড়া", "phone" to "01811000000", "dest" to LOCAL, "maxFare" to 2000) + body).body["id"].textValue()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        ok(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a1"))
        return id
    }

    private fun d(n: JsonNode) = n.doubleValue()

    @Test
    fun `finish - receipt for the patient, the trip is done, the chart edited later changes nothing, extras capped`() {
        seed()
        val id = agreedTrip()
        val r = reqRow(id)
        assertEquals(800, r["agreed_fare"])
        assertEquals(800, r["snap_fare"])
        // the driver raises the chart after the patient agreed
        repo.saveChart("a1", fc(2000, listOf(toDhaka(6000)), oxygen = 300))
        fails("d2".also { person(it) }, "ambreq_finish", mapOf("reqId" to id, "fare" to 900), 404)
        fails(P, "ambreq_finish", mapOf("reqId" to id, "fare" to 900), 404)
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 0), 400)
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 900, "extras" to listOf(mapOf("label" to "toll", "amount" to 50))), 400, "extras")
        // oxygen was not asked for → not in the frozen chart; "other" at most ৳500
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 900, "extras" to listOf(mapOf("label" to "oxygen", "amount" to 100))), 400, "extras_cap")
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 900, "extras" to listOf(mapOf("label" to "other", "amount" to 600))), 400, "extras_cap")
        assertEquals("accepted", reqRow(id)["status"], "nothing changed on a refused finish")
        clearPushes()
        val res = ok("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 1500, "extras" to listOf(mapOf("label" to "other", "amount" to 200)), "method" to "bkash"))
        assertEquals(true, res["overFare"].booleanValue(), "above the agreed ৳800 even though the chart now says ৳2000")
        assertEquals(800, res["chartFare"].intValue())
        val inv = res["invoice"]
        assertEquals("ambulance", inv["kind"].textValue())
        assertEquals("a1", inv["bizId"].textValue())
        assertEquals(P, inv["patientUid"].textValue())
        assertEquals(1700.0, d(inv["total"]))
        assertEquals(1700.0, d(inv["paid"]))
        assertEquals("অন্যান্য", inv["fees"][0]["label"].textValue())
        assertEquals(true, inv["overFare"].booleanValue())
        assertEquals("ambreq", inv["refType"].textValue())
        assertEquals("done", reqRow(id)["status"])
        val toPatient = pushes().filter { it["uid"] == P }
        val receipt = toPatient.first { (it["data"] as String).contains("invoice_new") }
        assertTrue((receipt["body"] as String).contains("রাজি হয়েছিলেন"), receipt.toString())
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 900), 409, "already_issued")
        // the patient: "রসিদের চেয়ে বেশি নিয়েছে" → the car's overcharge count
        val before = ambRow("a1")["overcharge_count"] as Int
        fails(P, "invoice_report", mapOf("id" to inv["id"].textValue(), "paid" to 100), 400, "paid")
        ok(P, "invoice_report", mapOf("id" to inv["id"].textValue(), "paid" to 2500, "note" to "নগদ বেশি নিয়েছে"))
        assertEquals(before + 1, ambRow("a1")["overcharge_count"])
        fails(P, "invoice_report", mapOf("id" to inv["id"].textValue()), 409, "already_reported")
        fails("d1", "invoice_report", mapOf("id" to inv["id"].textValue()), 404)
    }

    @Test
    fun `finish - at the agreed fare no flag; chart extras within the frozen chart; a trip closed by step done can still get its receipt`() {
        seed()
        val id = agreedTrip(mapOf("oxygen" to true))
        assertEquals(300, reqRow(id)["snap_oxygen"])
        fails("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 800, "extras" to listOf(mapOf("label" to "oxygen", "amount" to 301))), 400, "extras_cap")
        val agreed = reqRow(id)["agreed_fare"] as Int
        val res = ok("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to agreed - 300, "extras" to listOf(mapOf("label" to "oxygen", "amount" to 300))))
        assertFalse(res["overFare"].booleanValue(), res.toString())
        // closed the old way first (ambreq_step done), the receipt afterwards
        AmbClock.advance(HOUR)
        val id2 = agreedTrip()
        ok("d1", "ambreq_step", mapOf("id" to id2, "step" to "done"))
        val r2 = ok("d1", "ambreq_finish", mapOf("reqId" to id2, "fare" to 800, "method" to "cash"))
        assertFalse(r2["overFare"].booleanValue())
        assertEquals("cash", r2["invoice"]["payments"][0]["method"].textValue())
        // a day later: too late
        AmbClock.advance(HOUR)
        val id3 = agreedTrip()
        ok("d1", "ambreq_step", mapOf("id" to id3, "step" to "done"))
        AmbClock.advance(25 * HOUR)
        fails("d1", "ambreq_finish", mapOf("reqId" to id3, "fare" to 800), 409)
    }
}
