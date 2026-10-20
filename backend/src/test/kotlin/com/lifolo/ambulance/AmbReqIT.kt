package com.lifolo.ambulance

import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** port of server/test/ambulance-request.test.js */
class AmbReqIT : AmbTestBase() {
    private val P = "req-patient"

    private fun gps(dx: Double, dy: Double) = at(dx, dy).let { mapOf("lat" to it.first, "lng" to it.second, "gps" to true) }

    private fun ask(uid: String, body: Map<String, Any?> = emptyMap()) = call(
        uid, "ambreq_create",
        mapOf("district" to MYM, "upazilaId" to SADAR) + gps(0.0, 0.0) +
            mapOf("pickup" to "চরপাড়া মোড়, মেডিকেল গেট", "note" to "রোগী হাঁটতে পারেন না", "phone" to "01811000000") + body,
    )

    private fun askId(uid: String = P, body: Map<String, Any?> = emptyMap()): String {
        val r = ask(uid, body)
        assertEquals(200, r.status, r.toString())
        return r.body["id"].textValue()
    }

    private fun km(id: String): Map<String, Int?> = jdbc.queryForList(
        "SELECT owner_uid, km FROM ambreq_targets WHERE request_id = ? ORDER BY seq", id,
    ).associate { it["owner_uid"] as String to (it["km"] as Number?)?.toInt() }

    /** 8 cars 1–8 km east (d1…d8), plus cars that must never be asked */
    private fun seed() {
        AmbClock.reset()
        person(P)
        for (i in 1..8) amb("a$i", "d$i", base = at(i.toDouble(), 0.0), name = "গাড়ি $i")
        amb("xBusy", "dBusy", base = at(0.5, 0.0), available = false, busyAt = now() - HOUR)
        amb("xFreezer", "dFreezer", base = at(0.3, 0.0), types = listOf("freezer"))
        amb("xPending", "dPending", base = at(0.2, 0.0), status = "pending")
        amb("xOther", "dOther", base = at(0.1, 0.0), district = DHAKA)
        amb("demo-svc-x", "demo-x-drv", base = at(0.1, 0.1))
        amb("xMine", P, base = at(0.1, 0.2))
        // the owner's second car (2.5 km) — one owner once
        amb("a2b", "d2", base = at(2.5, 0.0))
        clearPushes()
    }

    @Test
    fun `choice - the 5 nearest owners, busy, freezer, unapproved, other district, demo and own excluded, one car per owner`() {
        seed()
        val r = ask(P)
        assertEquals(200, r.status, r.toString())
        val id = r.body["id"].textValue()
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), sentTo(id).sorted())
        assertEquals("a2", row("SELECT ambulance_id FROM ambreq_targets WHERE request_id = ? AND owner_uid = 'd2'", id)["ambulance_id"], "the owner's nearest car")
        assertEquals(mapOf("d1" to 1, "d2" to 2, "d3" to 3, "d4" to 4, "d5" to 5), km(id))
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), pushedTo().sorted())
        assertTrue(pushes().none { (it["title"] as String + it["body"]).let { t -> t.contains("চরপাড়া") || t.contains("হাঁটতে") } }, "no address/note in pushes")
        assertEquals("open", r.body["request"]["status"].textValue())
        assertEquals(5, r.body["request"]["notified"].intValue())
    }

    @Test
    fun `choice - type, freezer only when chosen, not beyond 130 km, the sheet's car first`() {
        seed()
        amb("xIcu", "dIcu", base = at(7.5, 0.0), types = listOf("icu", "ac"))
        val icu = askId(body = mapOf("type" to "icu"))
        assertEquals(listOf("dIcu"), sentTo(icu))
        ok(P, "ambreq_cancel", mapOf("id" to icu, "reason" to "wrong"))
        val fr = askId(body = mapOf("type" to "freezer"))
        assertEquals(listOf("dFreezer"), sentTo(fr))
        ok(P, "ambreq_cancel", mapOf("id" to fr))
        val one = askId(body = mapOf("ambulanceId" to "a8"))
        assertEquals(listOf("d8", "d1", "d2", "d3", "d4"), sentTo(one))
        assertEquals("a8", reqRow(one)["chosen_id"])
        ok(P, "ambreq_cancel", mapOf("id" to one))
    }

    @Test
    fun `cars further than 130 km are not asked`() {
        seed()
        jdbc.update("UPDATE ambulances SET lat = ? WHERE id IN ('a1','a2','a3','a4','a5','a6','a7','a8','a2b')", r2(MYM_D.lat + 1.3))
        val far = ask(P)
        assertEquals(409, far.status)
        assertEquals("no_ambulance", far.code)
    }

    @Test
    fun `the first to accept gets it (even at the same time), the others lose it, the patient is told`() {
        seed()
        val id = askId()
        clearPushes()
        val drivers = listOf("d3", "d1", "d2")
        val res = drivers.parallelStream().map { call(it, "ambreq_accept", mapOf("id" to id)) }.toList()
        assertEquals(listOf(200, 409, 409), res.map { it.status }.sorted())
        val winner = drivers[res.indexOfFirst { it.status == 200 }]
        val x = reqRow(id)
        assertEquals("accepted", x["status"])
        assertEquals(winner, x["accepted_by"])
        assertEquals(listOf(winner), jdbc.queryForList("SELECT owner_uid FROM ambreq_targets WHERE request_id = ? AND active", String::class.java, id))
        assertTrue(res.first { it.status == 409 }.error!!.contains("অন্য একজন চালক"))
        for (u in listOf("d1", "d2", "d3", "d4", "d5").filter { it != winner }) assertEquals(0, driverList(u).size, "$u lost it")
        val a = ambRow(x["ambulance_id"] as String)
        assertEquals(false, a["available"])
        assertEquals(id, a["req_busy"])
        assertEquals(listOf(P), pushedTo())
        val v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("accepted", v["status"].textValue())
        assertEquals("01711000000", v["ambulance"]["phone"].textValue())
        assertEquals(winner.drop(1).toInt(), v["ambulance"]["km"].intValue())
        assertFalse(v.toString().contains("V-a"), "the vehicle number is not shown to the patient")
    }

    @Test
    fun `nobody in 60 s - the circle widens once and 3 phone buttons, expired after 3 minutes`() {
        seed()
        for (i in 9..11) amb("a$i", "d$i", base = at(i.toDouble(), 0.0))
        val id = askId()
        clearPushes()
        AmbClock.advance(30 * SEC)
        var v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals(1, v["wave"].intValue())
        assertEquals(0, v["fallback"].size(), "no phone buttons before 60 s")
        AmbClock.advance(31 * SEC)
        val res = listOf<() -> com.fasterxml.jackson.databind.JsonNode>(
            { ok(P, "ambreq_status", mapOf("id" to id)) }, { ok("d1", "ambreq_driver") }, { ok(P, "ambreq_status", mapOf("id" to id)) },
        ).parallelStream().map { it() }.toList()
        v = res[0]["request"]
        assertEquals(2, v["wave"].intValue())
        assertEquals(1, res[1]["items"].size())
        assertEquals(listOf("d1", "d10", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9"), sentTo(id).sorted())
        assertEquals(listOf("d10", "d6", "d7", "d8", "d9"), pushedTo().sorted(), "the new 5 once")
        assertEquals(listOf("a1", "a2", "a3"), v["fallback"].map { it["id"].textValue() })
        assertTrue(v["fallback"].all { it["phone"].textValue().isNotEmpty() && it["km"].isInt })
        clearPushes()
        ok(P, "ambreq_status", mapOf("id" to id))
        ok("d7", "ambreq_driver")
        assertEquals(emptyList<String>(), pushedTo(), "reading again sends nothing")
        AmbClock.advance(2 * MIN)
        assertEquals(0, ok("d7", "ambreq_driver")["items"].size())
        v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("expired", v["status"].textValue())
        assertEquals(listOf("a1", "a2", "a3"), v["fallback"].map { it["id"].textValue() })
        assertEquals(listOf(P), pushedTo())
        assertEquals(409, call("d1", "ambreq_accept", mapOf("id" to id)).status)
        AmbClock.advance(11 * MIN)
        assertTrue(ok(P, "ambreq_status")["request"].isNull, "10 minutes later no longer 'active'")
    }

    @Test
    fun `everyone declined - the circle widens before 60 s, a declining driver loses it`() {
        seed()
        val id = askId()
        ok("d1", "ambreq_decline", mapOf("id" to id))
        assertEquals(0, driverList("d1").size)
        assertEquals(1, driverList("d2").size)
        for (u in listOf("d2", "d3", "d4", "d5")) ok(u, "ambreq_decline", mapOf("id" to id))
        assertEquals(2, reqRow(id)["wave"])
        assertEquals(listOf("d6", "d7", "d8"), jdbc.queryForList("SELECT owner_uid FROM ambreq_targets WHERE request_id = ? AND active", String::class.java, id).sorted())
    }

    @Test
    fun `patient cancel (open or accepted) frees the car, driver arrived - done - rating at once, driver cancel`() {
        seed()
        var id = askId()
        ok(P, "ambreq_cancel", mapOf("id" to id, "reason" to "not_needed"))
        var x = reqRow(id)
        assertEquals("cancelled", x["status"])
        assertEquals("not_needed", x["cancel_reason"])
        assertEquals(0, driverList("d1").size)
        id = askId()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        ok(P, "ambreq_cancel", mapOf("id" to id, "reason" to "found_other"))
        assertEquals(listOf("d1"), pushedTo())
        var a1 = ambRow("a1")
        assertEquals(true, a1["available"], "free again after cancelling")
        assertNull(a1["req_busy"])
        assertNull(a1["busy_at"])
        id = askId()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        ok("d1", "ambreq_step", mapOf("id" to id, "step" to "arrived"))
        assertEquals("arrived", ok(P, "ambreq_status", mapOf("id" to id))["request"]["status"].textValue())
        assertEquals(403, call(P, "ambulance_rate", mapOf("id" to "a1", "rating" to 5)).status, "not before done")
        ok("d1", "ambreq_step", mapOf("id" to id, "step" to "done"))
        assertEquals(listOf(P, P), pushedTo())
        val v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("done", v["status"].textValue())
        assertTrue(v["canRate"].booleanValue())
        a1 = ambRow("a1")
        assertEquals(true, a1["available"])
        assertTrue((a1["remind_at"] as java.sql.Timestamp).time > now(), "'still available?' again")
        val list = ok(P, "ambulances", mapOf("district" to MYM))
        assertTrue(list["items"].first { it["id"].textValue() == "a1" }["canRate"].booleanValue())
        assertFalse(list["items"].first { it["id"].textValue() == "a2" }["canRate"].booleanValue())
        ok(P, "ambulance_rate", mapOf("id" to "a1", "rating" to 5, "comment" to "সময়মতো এসেছে"))
        assertEquals(1, ambRow("a1")["rating_count"])
        assertEquals(409, call("d1", "ambreq_step", mapOf("id" to id, "step" to "cancel")).status)
        id = askId()
        ok("d2", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        ok("d2", "ambreq_step", mapOf("id" to id, "step" to "cancel", "reason" to "vehicle_problem"))
        x = reqRow(id)
        assertEquals("driver", x["cancelled_by"])
        assertEquals("vehicle_problem", x["cancel_reason"])
        assertEquals(listOf(P), pushedTo())
        assertEquals(true, ambRow("a2")["available"])
        person("d9")
        assertEquals(404, call("d9", "ambreq_accept", mapOf("id" to id)).status)
        assertEquals(404, call("d1", "ambreq_status", mapOf("id" to id)).status)
        // the owner's statistics: a finished trip counts as a call
        assertEquals(1, row("SELECT total FROM listing_stats WHERE kind = 'ambulance' AND listing_id = 'a1'")["total"])
        assertEquals(1, ok("d1", "ambulance_stats")["items"][0]["comments"].size())
    }

    @Test
    fun `privacy - before accepting only distance, address and note, the accepted driver gets name, phone, exact place, purged 24 h after closing`() {
        seed()
        val id = askId()
        val before = driverList("d2")[0]
        assertEquals(2, before["km"].intValue())
        assertEquals("চরপাড়া মোড়, মেডিকেল গেট", before["pickup"].textValue())
        assertEquals("রোগী হাঁটতে পারেন না", before["note"].textValue())
        for (k in listOf("lat", "lng", "phone", "patientName")) assertNull(before[k], "no $k before accepting")
        ok("d2", "ambreq_accept", mapOf("id" to id))
        val mine = driverList("d2")[0]
        assertEquals("accepted", mine["status"].textValue())
        assertEquals("01811000000", mine["phone"].textValue())
        assertEquals("নাম $P", mine["patientName"].textValue())
        assertEquals(listOf(at(0.0, 0.0).first, at(0.0, 0.0).second), listOf(mine["lat"].doubleValue(), mine["lng"].doubleValue()))
        assertEquals(0, driverList("d1").size)
        assertEquals(409, call("d1", "ambreq_step", mapOf("id" to id, "step" to "done")).status)
        ok("d2", "ambreq_step", mapOf("id" to id, "step" to "done"))
        reqs.dailyCleanup(now() + 23 * HOUR)
        assertEquals("01811000000", reqRow(id)["phone"])
        assertEquals(1, reqs.dailyCleanup(now() + 25 * HOUR)["purged"])
        val x = reqRow(id)
        for (k in listOf("lat", "lng", "phone", "pickup", "note", "purge_at")) assertNull(x[k], "$k purged")
        assertEquals("", x["patient_name"])
        assertEquals("done", x["status"])
        assertEquals("a2", x["ambulance_id"], "history stays (rating)")
        val noGps = askId(body = mapOf("gps" to false))
        val y = reqRow(noGps)
        assertTrue(y["lat"] == null && y["gps"] == false)
        assertTrue(driverList("d1")[0]["km"].isNull)
    }

    @Test
    fun `limits - one active at a time, 5 an hour, guests cannot call`() {
        seed()
        val first = askId()
        val again = ask(P)
        assertEquals(409, again.status)
        assertEquals("amb_req_active", again.code)
        ok(P, "ambreq_cancel", mapOf("id" to first))
        val two = listOf(1, 2).parallelStream().map { ask(P) }.toList()
        assertEquals(listOf(200, 409), two.map { it.status }.sorted())
        ok(P, "ambreq_cancel", mapOf("id" to two.first { it.status == 200 }.body["id"].textValue()))
        repeat(2) { ok(P, "ambreq_cancel", mapOf("id" to askId())) }
        assertEquals(200, ask(P).status)
        val sixth = ask(P)
        assertTrue(sixth.status == 409 || sixth.status == 429)
        ok(P, "ambreq_cancel", mapOf("id" to ok(P, "ambreq_status")["request"]["id"].textValue()))
        assertEquals(429, ask(P).status)
        AmbClock.advance(HOUR + SEC)
        assertEquals(200, ask(P).status)
    }

    @Test
    fun `an expired open request does not block a new one, guests cannot call, bad input`() {
        seed()
        askId()
        AmbClock.advance(3 * MIN + SEC)
        assertEquals(200, ask(P).status)
        AmbClock.reset()
        assertEquals(401, call(null, "ambreq_create", mapOf("district" to MYM, "pickup" to "x", "phone" to "01811000000")).status)
        assertEquals(200, call(null, "ambulances", mapOf("district" to MYM)).status)
        assertEquals(400, ask(P, mapOf("phone" to "123")).status)
        assertEquals(400, ask(P, mapOf("pickup" to "  ", "gps" to false)).status)
    }

    @Test
    fun `demo and real are separate`() {
        seed()
        val dp = "demo-patient-req"
        person(dp)
        val d = askId(dp)
        assertEquals(listOf("demo-x-drv"), sentTo(d))
        assertEquals(true, reqRow(d)["is_demo"])
        ok("demo-x-drv", "ambreq_accept", mapOf("id" to d))
        assertEquals("accepted", ok(dp, "ambreq_status", mapOf("id" to d))["request"]["status"].textValue())
        assertFalse(sentTo(askId()).contains("demo-x-drv"))
        person("demo-patient-req2")
        val d2 = ask("demo-patient-req2")
        assertEquals(409, d2.status, "not to real cars")
        assertEquals("no_ambulance", d2.code)
    }

    // ---------- phone and request together ----------

    @Test
    fun `a call while waiting joins the request, the phoned driver sees it marked, widening skips phoned cars`() {
        seed()
        for (i in 9..12) amb("a$i", "d$i", base = at(i.toDouble(), 0.0))
        val id = askId()
        clearPushes()
        ok(P, "ambulance_called", mapOf("id" to "a6", "reqId" to id))
        ok(P, "ambulance_called", mapOf("id" to "a7", "reqId" to id))
        ok(P, "ambulance_called", mapOf("id" to "a6", "reqId" to id))
        assertEquals(listOf("d6", "d7"), pushedTo(), "one push on the first call")
        assertEquals(listOf("a6", "a7"), jdbc.queryForList("SELECT ambulance_id FROM ambreq_calls WHERE request_id = ? ORDER BY seq", String::class.java, id))
        assertEquals(6, km(id)["d6"])
        assertTrue(driverList("d6")[0]["called"].booleanValue())
        assertFalse(driverList("d1")[0]["called"].booleanValue())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'a6'", Int::class.java, P))
        clearPushes()
        AmbClock.advance(61 * SEC)
        ok(P, "ambreq_status", mapOf("id" to id))
        assertEquals(listOf("d10", "d11", "d12", "d8", "d9"), pushedTo().sorted())
        assertEquals(12, sentTo(id).size)
        ok("d7", "ambreq_accept", mapOf("id" to id))
        val x = reqRow(id)
        assertEquals("accepted", x["status"])
        assertEquals("a7", x["ambulance_id"])
        assertEquals(409, call("d6", "ambreq_accept", mapOf("id" to id)).status)
        person("other-p")
        ok("other-p", "ambulance_called", mapOf("id" to "a1", "reqId" to id))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ambreq_calls WHERE request_id = ?", Int::class.java, id))
    }

    @Test
    fun `got a car by phone - byPhone closes, the others are told, accepting afterwards gets a clear message`() {
        seed()
        val id = askId()
        ok(P, "ambulance_called", mapOf("id" to "a6", "reqId" to id))
        assertEquals(400, call(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a1")).status)
        clearPushes()
        val v = ok(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a6"))["request"]
        assertEquals("byPhone", v["status"].textValue())
        assertEquals("a6", v["ambulance"]["id"].textValue())
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), pushedTo().sorted(), "everyone but the booked driver (d6)")
        assertTrue(pushes().all { it["title"] == "রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই" })
        for (u in listOf("d1", "d6")) assertEquals(0, driverList(u).size)
        val late = call("d1", "ambreq_accept", mapOf("id" to id))
        assertEquals(409, late.status)
        assertEquals("রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন — আর দরকার নেই", late.error)
        assertEquals(409, call("d6", "ambreq_accept", mapOf("id" to id)).status)
        assertEquals(id, row("SELECT by_phone_req FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'a6'", P)["by_phone_req"])
        assertEquals(200, ask(P).status)
    }

    @Test
    fun `replacing the accepted driver - byPhone or open again, one accepted car at a time`() {
        seed()
        var id = askId()
        ok(P, "ambulance_called", mapOf("id" to "a7", "reqId" to id))
        ok("d1", "ambreq_accept", mapOf("id" to id))
        var v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("accepted", v["status"].textValue())
        assertEquals(listOf("a7"), v["called"].map { it["id"].textValue() })
        assertEquals(409, call(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a7")).status, "with an accepted driver: replace")
        clearPushes()
        v = ok(P, "ambreq_replace", mapOf("id" to id, "ambulanceId" to "a7"))["request"]
        assertEquals("byPhone", v["status"].textValue())
        assertEquals("a7", v["ambulance"]["id"].textValue())
        assertEquals(listOf("d1"), pushedTo())
        assertTrue((pushes()[0]["title"] as String).contains("দুঃখিত — রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন"))
        assertNull(reqRow(id)["accepted_by"])
        assertEquals("patient_found_other", row("SELECT reason FROM ambreq_replaced WHERE request_id = ?", id)["reason"])
        assertEquals(true, ambRow("a1")["available"], "the dropped driver's car is free again")
        assertEquals(0, driverList("d1").size)

        id = askId()
        ok(P, "ambulance_called", mapOf("id" to "a7", "reqId" to id))
        ok("d2", "ambreq_accept", mapOf("id" to id))
        AmbClock.advance(2 * MIN + 50 * SEC)
        clearPushes()
        v = ok(P, "ambreq_replace", mapOf("id" to id))["request"]
        assertEquals("open", v["status"].textValue())
        assertTrue(v["expiresAt"].longValue() > now() + 2 * MIN, "time starts again")
        val pushed = pushedTo()
        assertTrue(pushes().first { it["uid"] == "d2" }["title"].toString().contains("দুঃখিত"), "the dropped driver gets the polite message")
        assertEquals(listOf("d1", "d3", "d4", "d5", "d7"), pushed.filter { it != "d2" }.sorted())
        assertEquals(0, driverList("d2").size)
        assertEquals(404, call("d2", "ambreq_accept", mapOf("id" to id)).status, "the dropped driver not again")
        assertEquals(1, driverList("d3").size)
        val res = listOf<() -> com.lifolo.support.IntegrationTest.Res>(
            { call("d3", "ambreq_accept", mapOf("id" to id)) }, { call("d4", "ambreq_accept", mapOf("id" to id)) },
            { call(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a7")) },
        ).parallelStream().map { it() }.toList()
        assertEquals(1, res.count { it.status == 200 }, res.map { it.status }.toString())
        val x = reqRow(id)
        assertTrue(x["status"] == "accepted" || x["status"] == "byPhone")
        assertEquals(if (x["status"] == "accepted") 1 else 0, jdbc.queryForObject("SELECT count(*) FROM ambulances WHERE req_busy = ?", Int::class.java, id))
    }

    @Test
    fun `account deletion - the patient's requests are cancelled and deleted, the driver is told`() {
        seed()
        val id = askId()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        reqs.closeForUser(P, full = true)
        assertTrue(reqRow(id).isEmpty())
        assertEquals(listOf("d1"), pushedTo())
        assertEquals(true, ambRow("a1")["available"])
    }

    @Test
    fun `review fixes - no accept after the time ran out, a much later daily close does not push, the driver's own busy stays`() {
        seed()
        var id = askId()
        AmbClock.advance(3 * MIN + SEC)
        assertEquals(409, call("d1", "ambreq_accept", mapOf("id" to id)).status)
        assertEquals("expired", reqRow(id)["status"])
        AmbClock.reset()
        id = askId()
        clearPushes()
        reqs.dailyCleanup(now() + 24 * HOUR)
        assertEquals("expired", reqRow(id)["status"])
        assertEquals(emptyList<String>(), pushedTo())
        id = askId()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        ok("d1", "ambulance_available", mapOf("id" to "a1", "available" to false))
        ok("d1", "ambreq_step", mapOf("id" to id, "step" to "done"))
        assertEquals(false, ambRow("a1")["available"])
    }

    @Test
    fun `an accepted trip the driver never finished closes after 12 hours (no rating)`() {
        seed()
        val id = askId()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        AmbClock.advance(12 * HOUR + SEC)
        val v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("done", v["status"].textValue())
        assertFalse(v["canRate"].booleanValue())
        assertEquals("auto", reqRow(id)["closed_by"])
        assertEquals(true, ambRow("a1")["available"])
    }

    // ---------------- 2.29.0: type choice and calling by area only ----------------

    @Test
    fun `types - AC, non-AC, ICU only that type, any and other types never a freezer van, freezer only when chosen`() {
        seed()
        amb("tAc", "tdAc", base = at(0.4, 0.0), types = listOf("ac"))
        amb("tIcu", "tdIcu", base = at(0.6, 0.0), types = listOf("icu", "ac"))
        amb("tMix", "tdMix", base = at(0.05, 0.0), types = listOf("freezer", "general"))
        var n = 0
        fun sent(body: Map<String, Any?>): Pair<List<String>, Map<String, Any?>> {
            // past the hourly limits on purpose: the account's log is cleared and each call uses another contact number
            jdbc.update("DELETE FROM ambreq_create_log WHERE uid = ?", P)
            val r = ask(P, mapOf("phone" to "0181100010${n++}") + body)
            assertEquals(200, r.status, r.toString())
            val id = r.body["id"].textValue()
            val owners = sentTo(id)
            val x = reqRow(id)
            ok(P, "ambreq_cancel", mapOf("id" to id, "reason" to "wrong"))
            return owners to x
        }
        val any = sent(emptyMap())
        assertFalse(any.first.contains("dFreezer") || any.first.contains("tdMix"), "any: no freezer — ${any.first}")
        assertNull(any.second["type"])
        assertEquals(listOf("tdAc", "tdIcu"), sent(mapOf("type" to "ac")).first, "AC: only cars listing AC, nearest first")
        val nonAc = sent(mapOf("type" to "general")).first
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), nonAc)
        assertEquals(listOf("tdIcu"), sent(mapOf("type" to "icu")).first)
        assertEquals(listOf("dFreezer", "tdMix"), sent(mapOf("type" to "freezer")).first.sorted(), "freezer only when chosen")
        assertNull(sent(mapOf("type" to "boat")).second["type"], "an unknown type = any")
        jdbc.update("DELETE FROM ambreq_create_log WHERE uid = ?", P)
        val id = askId(body = mapOf("type" to "ac"))
        assertEquals("ac", ok(P, "ambreq_status", mapOf("id" to id))["request"]["type"].textValue())
        assertEquals("ac", driverList("tdAc").first { it["id"].textValue() == id }["type"].textValue())
    }

    @Test
    fun `area only (no GPS) - own upazila first, no distance, union for the driver, a wrong union dropped`() {
        seed()
        amb("uTri1", "udTri1", upazilaId = TRISHAL, base = at(3.0, -19.0))
        amb("uTri2", "udTri2", upazilaId = TRISHAL)
        amb("uMuk", "udMuk", upazilaId = 465, base = at(-14.0, 1.0))
        val triUnion = Places.UNION.values.first { it.upazilaId == TRISHAL }.id
        val sadarUnion = Places.UNION.values.first { it.upazilaId == SADAR }.id
        val r = call(P, "ambreq_create", mapOf("district" to MYM, "upazilaId" to TRISHAL, "unionId" to triUnion, "pickup" to "বাসস্ট্যান্ডের পাশে, কলেজ রোড", "phone" to "01811000000"))
        assertEquals(200, r.status, r.toString())
        val id = r.body["id"].textValue()
        val x = reqRow(id)
        assertEquals(false, x["gps"])
        assertNull(x["lat"])
        assertEquals(triUnion, x["union_id"])
        val owners = sentTo(id)
        assertEquals(5, owners.size)
        assertEquals(listOf("udTri1", "udTri2"), owners.take(2).sorted(), "own upazila first: $owners")
        assertFalse(owners.contains("dFreezer"))
        assertTrue(km(id).values.all { it == null }, "by area — no distance")
        val dv = driverList("udTri1")[0]
        assertTrue(dv["km"].isNull)
        assertEquals(listOf(TRISHAL, triUnion), listOf(dv["upazilaId"].intValue(), dv["unionId"].intValue()))
        assertEquals("বাসস্ট্যান্ডের পাশে, কলেজ রোড", dv["pickup"].textValue())
        ok("udTri1", "ambreq_accept", mapOf("id" to id))
        assertNull(driverList("udTri1")[0]["lat"])
        val pv = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals(false, pv["gps"].booleanValue())
        assertEquals(TRISHAL, pv["upazilaId"].intValue())
        assertEquals(triUnion, pv["unionId"].intValue())
        ok(P, "ambreq_cancel", mapOf("id" to id, "reason" to "not_needed"))
        val r2 = call(P, "ambreq_create", mapOf("district" to MYM, "upazilaId" to TRISHAL, "unionId" to sadarUnion, "pickup" to "কলেজ রোড", "phone" to "01811000000"))
        assertEquals(200, r2.status, r2.toString())
        val y = reqRow(r2.body["id"].textValue())
        assertEquals(TRISHAL, y["upazila_id"])
        assertNull(y["union_id"])
        ok(P, "ambreq_cancel", mapOf("id" to r2.body["id"].textValue(), "reason" to "wrong"))
        val r3 = call(P, "ambreq_create", mapOf("district" to MYM, "pickup" to "কলেজ রোড", "phone" to "01811000000"))
        assertEquals(200, r3.status, r3.toString())
        assertEquals(5, sentTo(r3.body["id"].textValue()).size)
        ok(P, "ambreq_cancel", mapOf("id" to r3.body["id"].textValue(), "reason" to "wrong"))
        jdbc.update("DELETE FROM ambreq_create_log WHERE uid = ?", P)
        val r4 = askId()
        assertEquals(listOf("d1", "d2", "d3", "d4"), sentTo(r4).take(4))
    }
}
