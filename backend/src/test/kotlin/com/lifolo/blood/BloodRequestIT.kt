package com.lifolo.blood

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of server/test/clientdata.test.js (blood parts) and guest.test.js (guest_requests, emergencies_active,
 * login needed for writes): who may read requests, responses and the requester's phone.
 */
class BloodRequestIT : BloodTestBase() {
    @Test
    fun `reading requests - every logged-in user, demo and real apart, no guests`() {
        user("rA", emptyMap())
        user("rB", emptyMap())
        user("demo-patient", emptyMap())
        request("rq-real", "rA", createdAt = 1700000000000)
        jdbc.update("UPDATE blood_requests SET notified_at = to_timestamp(1700000001) WHERE id = 'rq-real'")
        request("rq-demo", "demo-patient")
        val g = call("rB", "blood_request_get", mapOf("requestId" to "rq-real"), lang = "en")
        assertEquals(200, g.status, g.toString())
        val r = g.body["request"]
        assertEquals("rA", r["requesterUid"].textValue())
        assertEquals("secret note", r["note"].textValue())
        assertEquals(1700000000000, r["createdAt"].longValue())
        assertEquals(1700000000000, r["createdAtMillis"].longValue())
        assertEquals(1700000001000, r["notifiedAt"].longValue())
        assertEquals(0, r["closedAt"].longValue())
        assertEquals("", r["closedBy"].textValue())
        assertTrue(r["neededAtMillis"].isNull)
        assertTrue(call("rB", "blood_request_get", mapOf("requestId" to "nope")).body["request"].isNull)
        // demo / real
        assertTrue(call("rB", "blood_request_get", mapOf("requestId" to "rq-demo")).body["request"].isNull)
        assertTrue(call("demo-patient", "blood_request_get", mapOf("requestId" to "rq-real")).body["request"].isNull)
        assertEquals("rq-demo", call("demo-patient", "blood_request_get", mapOf("requestId" to "rq-demo")).body["request"]["id"].textValue())
        assertEquals(404, call("rB", "blood_request_get", mapOf("requestId" to "../x")).status)
        // own list: only own, newest first
        request("rq-real2", "rA", createdAt = System.currentTimeMillis() + 1000, status = "closed")
        val mine = call("rA", "blood_requests_mine")
        assertEquals(listOf("rq-real2", "rq-real"), mine.body["items"].map { it["id"].textValue() })
        assertEquals(0, call("rB", "blood_requests_mine").body["items"].size())
        for (action in listOf("blood_request_get", "blood_requests_mine", "blood_requests_near", "blood_response_get", "blood_responses_list", "blood_contact_get", "report_request")) {
            assertEquals(401, call(null, action, mapOf("requestId" to "rq-real", "lat" to LAT, "lng" to LNG)).status, action)
        }
    }

    @Test
    fun `nearby requests - active, not own, group, distance, demo apart, urgent first`() {
        user("nMe", emptyMap())
        user("nOther", emptyMap())
        user("demo-donor", emptyMap())
        user("demo-patient", emptyMap())
        fun north(km: Double) = LAT + km / 111
        request("n-near", "nOther", lat = north(1.0))
        request("n-urgent", "nOther", lat = north(3.0), urgent = true)
        request("n-mine", "nMe")
        request("n-closed", "nOther", status = "closed")
        request("n-expired", "nOther", neededBy = System.currentTimeMillis() - 3 * DAY)
        request("n-group", "nOther", group = "AB-")
        request("n-far", "nOther", lat = north(30.0))
        request("n-60", "nOther", lat = north(60.0))
        request("n-demo", "demo-patient", lat = north(2.0))
        val r = call("nMe", "blood_requests_near", mapOf("lat" to LAT, "lng" to LNG, "maxKm" to 10, "groups" to listOf("O+")), lang = "en")
        assertEquals(200, r.status, r.toString())
        assertEquals(listOf("n-urgent", "n-near"), r.body["items"].map { it["id"].textValue() })
        val km = r.body["items"][1]["km"].doubleValue()
        assertTrue(km > 0.9 && km < 1.2, "$km")
        val all = call("nMe", "blood_requests_near", mapOf("lat" to LAT, "lng" to LNG, "maxKm" to 25, "groups" to null))
        assertEquals(listOf("n-group", "n-near", "n-urgent"), all.body["items"].map { it["id"].textValue() }.sorted())
        // at most 50 km, even when more is asked
        val big = call("nMe", "blood_requests_near", mapOf("lat" to LAT, "lng" to LNG, "maxKm" to 1000)).body["items"].map { it["id"].textValue() }
        assertTrue("n-far" in big && "n-60" !in big, big.toString())
        // a demo user sees demo requests only
        val demo = call("demo-donor", "blood_requests_near", mapOf("lat" to LAT, "lng" to LNG, "maxKm" to 10))
        assertEquals(listOf("n-demo"), demo.body["items"].map { it["id"].textValue() })
        assertEquals(400, call("nMe", "blood_requests_near", mapOf("lat" to "x", "lng" to LNG)).status)
        // the guest list as before — no name, uid, note
        val gr = call(null, "guest_requests", mapOf("lat" to LAT, "lng" to LNG, "maxKm" to 10), ip = "7.7.7.7")
        assertEquals(200, gr.status, gr.toString())
        assertTrue(gr.body["items"].size() >= 2)
        for (it in gr.body["items"]) for (k in listOf("requesterName", "requesterUid", "note", "lat", "lng")) assertFalse(it.has(k), k)
    }

    @Test
    fun `responses and the requester's phone - who may see them`() {
        for (u in listOf("cReq", "cAcc", "cDon", "cCan", "cStr")) user(u, emptyMap())
        request("c1", "cReq")
        contact("c1", "রক্তপ্রার্থী", "01799999999")
        response("c1", "cAcc", "accepted", 2)
        response("c1", "cDon", "donated", 1)
        response("c1", "cCan", "cancelled", 3)
        assertEquals("accepted", call("cAcc", "blood_response_get", mapOf("requestId" to "c1")).body["response"]["status"].textValue())
        assertTrue(call("cStr", "blood_response_get", mapOf("requestId" to "c1", "donorUid" to "cAcc")).body["response"].isNull)
        assertTrue(call("cReq", "blood_response_get", mapOf("requestId" to "c1")).body["response"].isNull)
        // the list: requester only, accepted/donated only, oldest first
        val list = call("cReq", "blood_responses_list", mapOf("requestId" to "c1"))
        assertEquals(listOf("cDon", "cAcc"), list.body["items"].map { it["donorUid"].textValue() })
        assertEquals("D cAcc", list.body["items"][1]["donorName"].textValue())
        for (u in listOf("cAcc", "cStr", "cCan")) assertEquals(403, call(u, "blood_responses_list", mapOf("requestId" to "c1")).status, u)
        assertEquals(403, call("cReq", "blood_responses_list", mapOf("requestId" to "none")).status)
        // the phone: requester, accepted and donated donors (open request)
        for (u in listOf("cReq", "cAcc", "cDon")) {
            assertEquals("01799999999", call(u, "blood_contact_get", mapOf("requestId" to "c1")).body["contact"]["phone"].textValue(), u)
        }
        for (u in listOf("cCan", "cStr")) assertEquals(403, call(u, "blood_contact_get", mapOf("requestId" to "c1")).status, u)
        assertEquals(403, call("cReq", "blood_contact_get", mapOf("requestId" to "none")).status)
        // closed: donors no longer see it, the requester does
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE id = 'c1'")
        for (u in listOf("cAcc", "cDon")) assertEquals(403, call(u, "blood_contact_get", mapOf("requestId" to "c1")).status, u)
        assertEquals("রক্তপ্রার্থী", call("cReq", "blood_contact_get", mapOf("requestId" to "c1")).body["contact"]["name"].textValue())
    }

    @Test
    fun `report - once per user in own name`() {
        user("rpA", emptyMap())
        user("rpB", emptyMap())
        assertEquals(200, call("rpA", "report_request", mapOf("requestId" to "rq-x", "reporterUid" to "rpB", "extra" to "x")).status)
        assertEquals(1L, row("SELECT count(*) AS n FROM blood_request_reports WHERE request_id = 'rq-x' AND reporter_uid = 'rpA'")["n"])
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_request_reports WHERE reporter_uid = 'rpB'")["n"])
        val again = call("rpA", "report_request", mapOf("requestId" to "rq-x"))
        assertEquals(409, again.status)
        assertEquals("আগেই রিপোর্ট করা হয়েছে", again.error)
        assertEquals(200, call("rpB", "report_request", mapOf("requestId" to "rq-x")).status)
        assertEquals(404, call("rpA", "report_request", mapOf("requestId" to "")).status)
    }

    @Test
    fun `guest - nearby open requests without private data, appeals of a district, writes need login`() {
        user("u1", emptyMap())
        user("u2", emptyMap())
        request("r1", "u1", urgent = true)
        request("r2", "u2", group = "A+", status = "closed")
        val r = call(null, "guest_requests", mapOf("lat" to LAT, "lng" to LNG), ip = "1.2.3.4")
        assertEquals(200, r.status, r.toString())
        assertEquals(1, r.body["items"].size())
        val item = r.body["items"][0]
        assertEquals("r1", item["id"].textValue())
        assertEquals("O+", item["patientGroup"].textValue())
        assertEquals(0.0, item["km"].doubleValue())
        for (k in listOf("requesterName", "requesterUid", "note", "lat", "lng")) assertFalse(item.has(k), k)
        val e = call(null, "emergencies_active", mapOf("district" to DIST), ip = "1.2.3.4")
        assertEquals(200, e.status, e.toString())
        assertEquals(0, e.body["ask"].size())
        assertEquals(400, call(null, "guest_requests", mapOf("lat" to 51.5, "lng" to LNG), ip = "1.2.3.4").status)
        for (action in listOf("request_create", "report_request", "bloodbank_called", "emergency_respond", "response_update")) {
            assertEquals(401, call(null, action, mapOf("text" to "হ্যালো"), ip = "1.2.3.4").status, action)
        }
    }

    @Test
    fun `request_create - checks, rounding, private contact, limits`() {
        user("cr1", emptyMap())
        val day = today() + DAY
        val ok = mapOf(
            "lat" to 24.75432, "lng" to 90.40789, "patientGroup" to "A+", "bags" to 2, "hospital" to "  মমেক  ", "placeName" to DIST,
            "neededByMillis" to day, "requesterPhone" to "+8801712-345678", "requesterName" to "রহিম", "note" to " দরকার ", "urgent" to true,
        )
        val bad = call("cr1", "request_create", ok + mapOf("bags" to 50), lang = "en")
        assertEquals(400, bad.status)
        assertEquals("Invalid number of bags", bad.error)
        assertEquals("তারিখটা পার হয়ে গেছে", call("cr1", "request_create", ok + mapOf("neededByMillis" to today() - 2 * DAY)).error)
        assertEquals("সঠিক মোবাইল নম্বর দিন", call("cr1", "request_create", ok + mapOf("requesterPhone" to "123")).error)
        assertEquals("নাম লিখুন", call("cr1", "request_create", ok + mapOf("requesterName" to "  ")).error)
        val c = call("cr1", "request_create", ok)
        assertEquals(200, c.status, c.toString())
        val id = c.body["id"].textValue()
        val x = row("SELECT * FROM blood_requests WHERE id = ?", id)
        assertEquals(24.75, x["lat"])
        assertEquals(90.41, x["lng"])
        assertEquals("মমেক", x["hospital"])
        assertEquals("দরকার", x["note"])
        assertEquals(true, x["urgent"])
        assertEquals(false, x["travel_fare"])
        assertEquals(com.lifolo.common.Geo.hash(24.75, 90.41), x["geohash"])
        assertEquals("01712345678", row("SELECT phone FROM blood_request_contacts WHERE request_id = ?", id)["phone"])
        // two open at once, then the open limit
        assertEquals(200, call("cr1", "request_create", ok).status)
        val third = call("cr1", "request_create", ok)
        assertEquals(429, third.status)
        assertEquals("একসাথে সর্বোচ্চ 2টা খোলা রিকোয়েস্ট রাখা যায়", third.error)
        // the daily limit (3 a day) counts closed ones too
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE requester_uid = 'cr1'")
        assertEquals(200, call("cr1", "request_create", ok).status)
        jdbc.update("UPDATE blood_requests SET status = 'closed' WHERE requester_uid = 'cr1'")
        val daily = call("cr1", "request_create", ok, lang = "en")
        assertEquals(429, daily.status)
        assertEquals("Up to 3 requests a day. If it's urgent, tell people you know directly.", daily.error)
    }
}
