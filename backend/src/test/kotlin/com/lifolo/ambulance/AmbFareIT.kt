package com.lifolo.ambulance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** port of server/test/ambulance-fare.test.js */
class AmbFareIT : AmbTestBase() {
    private val P = "fare-patient"
    private val HOSP = "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল"
    private val LOCAL = mapOf("kind" to "upazila", "upazilaId" to SADAR)
    private val base get() = mapOf("district" to MYM, "upazilaId" to SADAR, "lat" to at(0.0, 0.0).first, "lng" to at(0.0, 0.0).second, "gps" to true)
    private val sadarLabel get() = "${com.lifolo.common.Places.UPAZILA.getValue(SADAR).bn}, $MYM"

    private fun ask(uid: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, "ambreq_create", base + mapOf("pickup" to "চরপাড়া মোড়", "note" to "", "phone" to "01811000000", "dest" to LOCAL, "maxFare" to 1000) + body)

    private fun count(uid: String, body: Map<String, Any?> = emptyMap()) = ok(uid, "ambreq_count", base + mapOf("dest" to LOCAL, "maxFare" to 1000) + body)

    private fun status(id: String) = ok(P, "ambreq_status", mapOf("id" to id))["request"]

    private fun est(id: String): Map<String, Int?> = jdbc.queryForList(
        "SELECT owner_uid, est_fare FROM ambreq_targets WHERE request_id = ? ORDER BY seq", id,
    ).associate { it["owner_uid"] as String to (it["est_fare"] as Number?)?.toInt() }

    private fun seed() {
        AmbClock.setDhaka("2026-10-19 12:00")
        person(P)
        amb(
            "a1", "d1", base = at(1.0, 0.0), ratingSum = 9, ratingCount = 2,
            chart = fc(800, listOf(FareRow(Dest("upazila", upazilaId = TRISHAL), 1200), toDhaka(6000), FareRow(Dest("hospital", name = HOSP), 700)), oxygen = 300, night = 500),
        )
        amb("a2", "d2", base = at(2.0, 0.0), types = listOf("general", "ac"), chart = fc(1000, listOf(toDhaka(5000))))
        amb("a3", "d3", base = at(3.0, 0.0), chart = fc(1500, listOf(toDhaka(4500))))
        amb("a4", "d4", base = at(4.0, 0.0))
        amb("a5", "d5", base = at(5.0, 0.0), chart = fc(2500, listOf(toDhaka(9000))))
        amb("a6", "d6", base = at(6.0, 0.0), types = listOf("icu", "ac"), chart = fc(null, listOf(FareRow(Dest("district", district = MYM), 1800), toDhaka(8000)), icu = 3000))
    }

    @Test
    fun `estimated fare - own upazila, other upazila, district, hospital name, unknown; extras and night`() {
        val veh = Veh(MYM, SADAR)
        val c = AmbFare.normalizeChart(
            json(
                mapOf(
                    "local" to 800,
                    "rows" to listOf(mapOf("kind" to "upazila", "upazilaId" to TRISHAL, "amount" to 1200), mapOf("kind" to "district", "district" to DHAKA, "amount" to 6000), mapOf("kind" to "hospital", "name" to HOSP, "amount" to 700)),
                    "oxygen" to 300, "icu" to 2000, "night" to "৫০০",
                ),
            ),
            veh, places::facility,
        ).chart!!
        assertEquals(500, c.night, "Bangla digits too")
        fun e(d: Dest, type: String? = null, oxygen: Boolean = false, night: Boolean = false) = AmbFare.estimate(c, veh, d, type, oxygen, night)
        assertEquals(800, e(Dest("upazila", upazilaId = SADAR)))
        assertEquals(1200, e(Dest("upazila", upazilaId = TRISHAL)))
        assertEquals(6000, e(Dest("district", district = DHAKA)))
        assertEquals(6000, e(Dest("upazila", upazilaId = SAVAR)), "an upazila of Dhaka → the Dhaka fare")
        assertEquals(700, e(Dest("hospital", name = "ময়মনসিংহ মেডিকেল কলেজ")), "name match (without 'hospital', part)")
        assertNull(e(Dest("hospital", name = "এভারকেয়ার")))
        assertNull(e(Dest("upazila", upazilaId = 470)))
        assertNull(e(Dest("district", district = GAZIPUR)))
        assertEquals(2800, e(Dest("upazila", upazilaId = SADAR), type = "icu"))
        assertEquals(1100, e(Dest("upazila", upazilaId = SADAR), type = "ac", oxygen = true))
        assertEquals(6000 + 2000 + 300 + 500, e(Dest("district", district = DHAKA), type = "icu", oxygen = true, night = true))
        // extras missing from the chart = included (0)
        val plain = AmbFare.normalizeChart(json(mapOf("local" to 900)), veh, places::facility).chart
        assertEquals(900, AmbFare.estimate(plain, veh, Dest("upazila", upazilaId = SADAR), "icu", true, true))
        // own district row = anywhere in the district (no upazila row)
        val d = AmbFare.normalizeChart(json(mapOf("rows" to listOf(mapOf("kind" to "district", "district" to MYM, "amount" to 1800)))), veh, places::facility).chart
        assertEquals(1800, AmbFare.estimate(d, veh, Dest("upazila", upazilaId = TRISHAL), null, false, false))
        assertNull(AmbFare.estimate(null, veh, Dest("upazila", upazilaId = SADAR), null, false, false))
        // night: 22:00–06:00 Dhaka time
        for ((t, night) in listOf("2026-10-19 21:59" to false, "2026-10-19 22:00" to true, "2026-10-20 03:00" to true, "2026-10-20 05:59" to true, "2026-10-20 06:00" to false, "2026-10-19 12:00" to false)) {
            assertEquals(night, AmbFare.isNight(AmbClock.setDhaka(t)), t)
        }
        // budget, raise-to, taka
        assertEquals(1200L, AmbFare.taka(json("১,২০০ ৳")))
        assertEquals(AmbFare.NAN, AmbFare.taka(json(800.5)))
        assertNull(AmbFare.budgetOf(json(0)))
        assertEquals(AmbFare.NAN, AmbFare.budgetOf(json(50)))
        assertEquals(5000L, AmbFare.raiseTo(listOf(4500, 5000, null), 1000))
        assertEquals(11000L, AmbFare.raiseTo(listOf(11000), 10000))
        assertNull(AmbFare.raiseTo(listOf(800), null))
        assertEquals("নিজের উপজেলা ৳৮০০; ${DHAKA} ৳৫০০০; অক্সিজেন +৳৩০০", AmbFare.chartText(fc(800, listOf(toDhaka(5000)), oxygen = 300)))
    }

    @Test
    fun `fare chart validation and saving - whole taka, limits, 15 rows, no duplicates, own district; old apps keep the chart`() {
        val veh = Veh(MYM, SADAR)
        fun e(x: Any?) = AmbFare.normalizeChart(json(x), veh, places::facility).error?.second ?: ""
        fun row(kind: String, k: String, v: Any?, amount: Any? = 1000) = mapOf("kind" to kind, k to v, "amount" to amount)
        assertTrue(e(mapOf("local" to 800.5)).contains("পুরো টাকায়"))
        assertTrue(e(mapOf("local" to 50)).contains("১০০ থেকে"))
        assertTrue(e(mapOf("local" to 800, "rows" to listOf(row("district", "district", DHAKA, 200000)))).contains("১০০ থেকে"))
        assertTrue(e(mapOf("local" to 800, "oxygen" to 10)).contains("বাড়তি"))
        assertTrue(e(mapOf("rows" to (0 until 16).map { row("hospital", "name", "হাসপাতাল নম্বর $it") })).contains("১৫টা"))
        assertTrue(e(mapOf("rows" to listOf(row("district", "district", DHAKA, 5000), row("district", "district", DHAKA, 6000)))).contains("দুবার"))
        assertTrue(e(mapOf("rows" to listOf(row("hospital", "name", "সিটি হাসপাতাল", 900), row("hospital", "name", "সিটি  Hospital", 900)))).contains("দুবার"), "same hospital, other spelling")
        assertTrue(e(mapOf("rows" to listOf(row("upazila", "upazilaId", SAVAR, 5000)))).contains("নিজের জেলার"))
        assertTrue(e(mapOf("rows" to listOf(row("upazila", "upazilaId", SADAR, 5000)))).contains("নিজের উপজেলার ভেতরে"))
        assertTrue(e(mapOf("rows" to listOf(row("district", "district", DHAKA, null)))).contains("ভাড়া লিখুন"))
        assertTrue(e(mapOf("oxygen" to 300)).contains("অন্তত একটা"))
        assertNull(AmbFare.normalizeChart(json(mapOf("local" to null, "rows" to emptyList<Any>())), veh, places::facility).chart)
        assertEquals(
            15,
            AmbFare.normalizeChart(json(mapOf("rows" to (0 until 15).map { row("hospital", "name", "হাসপাতাল ${"ক".repeat(it + 1)}") })), veh, places::facility).chart!!.rows.size,
        )

        seed()
        fun save(fc: Any?, send: Boolean = true) = call(
            "d4", "ambulance_save",
            mapOf(
                "id" to "a4", "name" to "গাড়ি a4", "phone" to "01711000000", "vehicleNo" to "V-a4", "types" to listOf("general"), "district" to MYM,
                "upazilaId" to SADAR, "fare" to "আলোচনা সাপেক্ষে", "termsAccepted" to true,
            ) + if (send) mapOf("fareChart" to fc) else emptyMap(),
        )
        assertEquals(400, save(mapOf("local" to 900.5)).status)
        assertEquals(200, save(mapOf("local" to 900, "rows" to listOf(row("district", "district", DHAKA, 5500)), "oxygen" to null, "icu" to null, "night" to 400)).status)
        val a = repo.get("a4")!!
        assertEquals(fc(900, listOf(toDhaka(5500)), night = 400), a.chart)
        assertEquals("আলোচনা সাপেক্ষে", a.fare, "the old written fare stays as a note")
        assertEquals("approved", a.status, "a fare change needs no new review")
        // old app (no fareChart) → kept; null → removed
        save(null, send = false)
        assertEquals(900, repo.get("a4")!!.chart!!.local)
        assertEquals(900, ok("d4", "ambulance_mine")["items"][0]["fareChart"]["local"].intValue())
        save(null)
        assertNull(repo.get("a4")!!.chart)
    }

    @Test
    fun `budget filter and count - chips, unknown fares, raise to; pushes only within the budget`() {
        seed()
        var c = count(P)
        assertEquals(2, c["count"].intValue(), "800 and 1000")
        assertEquals(1, c["unknown"].intValue(), "a4")
        assertEquals(json(mapOf("1000" to 2, "2000" to 4, "5000" to 5, "10000" to 5)), c["levels"])
        assertTrue(c["raiseTo"].isNull)
        assertEquals(json(listOf(HOSP)), c["hospitals"])
        assertEquals(3, count(P, mapOf("includeUnknown" to true))["count"].intValue())
        assertEquals(5, count(P, mapOf("maxFare" to null))["count"].intValue(), "any — still without unknown fares")
        assertEquals(0, count(P, mapOf("type" to "icu", "maxFare" to 2000))["count"].intValue(), "ICU 1800 + 3000")
        assertEquals(1, count(P, mapOf("type" to "icu", "maxFare" to 5000))["count"].intValue())
        c = count(P, mapOf("dest" to mapOf("kind" to "district", "district" to DHAKA)))
        assertEquals(0, c["count"].intValue())
        assertEquals(5000, c["raiseTo"].intValue())
        c = count(P, mapOf("dest" to mapOf("kind" to "district", "district" to DHAKA), "type" to "icu", "maxFare" to 10000))
        assertEquals(0, c["count"].intValue())
        assertEquals(11000, c["raiseTo"].intValue())
        assertEquals(1, count(P, mapOf("dest" to mapOf("kind" to "hospital", "name" to "ময়মনসিংহ মেডিকেল কলেজ"), "maxFare" to null))["count"].intValue())
        assertEquals(400, call(P, "ambreq_count", base + mapOf("dest" to mapOf("kind" to "upazila", "upazilaId" to 99999))).status)
        assertEquals(400, call(P, "ambreq_count", base + mapOf("dest" to LOCAL, "maxFare" to 50)).status)
        assertEquals(401, call(null, "ambreq_count", base + mapOf("dest" to LOCAL)).status, "login needed")

        clearPushes()
        val r = ask(P)
        assertEquals(200, r.status, r.toString())
        val id = r.body["id"].textValue()
        assertEquals(listOf("d1", "d2"), sentTo(id).sorted())
        assertEquals(mapOf("d1" to 800, "d2" to 1000), est(id))
        assertEquals(listOf("d1", "d2"), pushedTo().sorted())
        assertTrue((pushes()[0]["body"] as String).contains("ভাড়াসহ \"আসছি\""))
        assertTrue(pushes().none { (it["title"] as String + it["body"]).contains("মেডিকেল") || (it["title"] as String + it["body"]).contains("চরপাড়া") }, "no destination/address in pushes")
        assertEquals(1000, r.body["request"]["maxFare"].intValue())
        assertEquals(sadarLabel, r.body["request"]["destLabel"].textValue())
        ok(P, "ambreq_cancel", mapOf("id" to id))
        // with unknown fares: a4 too (no estimate)
        val id2 = ask(P, mapOf("includeUnknown" to true)).body["id"].textValue()
        assertEquals(listOf("d1", "d2", "d4"), sentTo(id2).sorted())
        assertNull(est(id2)["d4"])
        ok(P, "ambreq_cancel", mapOf("id" to ok(P, "ambreq_status")["request"]["id"].textValue()))
        // nothing within the fare: clear message
        val none = ask(P, mapOf("dest" to mapOf("kind" to "district", "district" to DHAKA)))
        assertEquals(409, none.status)
        assertEquals("no_fare_match", none.code)
        assertTrue(none.error!!.contains("ভাড়া বাড়ান"))
        assertEquals(400, ask(P, mapOf("dest" to mapOf("kind" to "hospital", "name" to "ক"))).status)
        // at night with oxygen: a1 = 800 + 300 + 500 = 1600
        AmbClock.setDhaka("2026-10-19 23:30")
        assertTrue(count(P, mapOf("oxygen" to true, "maxFare" to 2000))["night"].booleanValue())
        val y = ask(P, mapOf("oxygen" to true, "maxFare" to 2000)).body["id"].textValue()
        assertEquals(1600, est(y)["d1"])
        assertEquals(true, reqRow(y)["night"])
        assertEquals(true, reqRow(y)["oxygen"])
    }

    @Test
    fun `offers - estimate prefilled, not above the budget, unknown fare must be written; cheapest first; no phone or GPS before agreeing`() {
        seed()
        val id = ask(P, mapOf("maxFare" to 2000, "includeUnknown" to true)).body["id"].textValue()
        assertEquals(listOf("d1", "d2", "d3", "d4", "d6"), sentTo(id).sorted())
        val card = driverList("d3")[0]
        assertEquals(1500, card["suggestedFare"].intValue())
        assertEquals(2000, card["maxFare"].intValue())
        assertEquals(sadarLabel, card["destLabel"].textValue())
        assertEquals(3, card["km"].intValue())
        assertTrue(card["myQuote"].isNull)
        for (k in listOf("lat", "lng", "phone", "patientName")) assertNull(card[k], "no $k before agreeing")
        clearPushes()
        val over = call("d3", "ambreq_accept", mapOf("id" to id, "fare" to 2100))
        assertEquals(409, over.status)
        assertEquals("over_budget", over.code)
        assertTrue(over.error!!.contains("৳২০০০"))
        assertEquals(400, call("d3", "ambreq_accept", mapOf("id" to id, "fare" to 1500.5)).status)
        val blank = call("d4", "ambreq_accept", mapOf("id" to id))
        assertEquals(400, blank.status)
        assertEquals("fare_needed", blank.code)
        val q3 = ok("d3", "ambreq_accept", mapOf("id" to id))
        assertEquals(1500, q3["request"]["myQuote"]["fare"].intValue())
        assertEquals("open", q3["request"]["status"].textValue(), "not confirmed yet")
        ok("d1", "ambreq_accept", mapOf("id" to id, "fare" to 1500))
        ok("d4", "ambreq_accept", mapOf("id" to id, "fare" to "১২০০"))
        assertEquals("open", reqRow(id)["status"])
        assertEquals(listOf(P, P, P), pushedTo(), "the patient is pushed for every offer")
        assertTrue((pushes()[0]["title"] as String).contains("৳১৫০০-তে আসতে রাজি"))
        var v = status(id)
        assertEquals(listOf(listOf("a4", 1200, 4), listOf("a1", 1500, 1), listOf("a3", 1500, 3)), v["quotes"].map { listOf(it["id"].textValue(), it["fare"].intValue(), it["km"].intValue()) })
        assertEquals(4.5, v["quotes"][1]["rating"].doubleValue())
        assertTrue(v["quotes"].none { it.has("phone") || it.has("owner") })
        ok("d1", "ambreq_accept", mapOf("id" to id, "fare" to 1100))
        v = status(id)
        assertEquals(listOf("a1", "a4", "a3"), v["quotes"].map { it["id"].textValue() })
        val wait = driverList("d1")[0]
        assertEquals(1100, wait["myQuote"]["fare"].intValue())
        assertNull(wait["phone"])
    }

    @Test
    fun `agree - only one of two concurrent agrees wins; the others are released; the agreed fare is kept and rated`() {
        seed()
        val id = ask(P, mapOf("maxFare" to 2000)).body["id"].textValue()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        ok("d2", "ambreq_accept", mapOf("id" to id, "fare" to 900))
        ok("d3", "ambreq_accept", mapOf("id" to id))
        assertEquals(409, call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a6")).status)
        clearPushes()
        val res = listOf("a2", "a3").parallelStream().map { call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to it)) }.toList()
        assertEquals(listOf(200, 409), res.map { it.status }.sorted())
        val x = reqRow(id)
        assertEquals("accepted", x["status"])
        val won = x["ambulance_id"] as String
        assertEquals(if (won == "a2") 900 else 1500, x["agreed_fare"])
        val winner = x["accepted_by"] as String
        assertEquals(listOf(winner), jdbc.queryForList("SELECT owner_uid FROM ambreq_targets WHERE request_id = ? AND active", String::class.java, id))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ambulances WHERE req_busy = ?", Int::class.java, id))
        val released = pushes().filter { it["title"] == "রোগী অন্য গাড়ি নিয়েছেন" }.map { it["uid"] as String }.sorted()
        assertEquals(listOf("d1", "d2", "d3", "d6").filter { it != winner }, released)
        assertTrue(pushes().any { it["uid"] == winner && (it["title"] as String).startsWith("রোগী রাজি — ৳") })
        for (u in listOf("d1", "d2", "d3", "d6").filter { it != winner }) assertEquals(emptyList<Any>(), driverList(u), "$u released")
        val mine = driverList(winner)[0]
        assertEquals("accepted", mine["status"].textValue())
        assertEquals(x["agreed_fare"], mine["agreedFare"].intValue())
        assertEquals("01811000000", mine["phone"].textValue())
        assertTrue(mine["lat"].isNumber)
        val v = status(id)
        assertEquals("accepted", v["status"].textValue())
        assertEquals(0, v["quotes"].size())
        assertEquals(409, call("d1", "ambreq_accept", mapOf("id" to id, "fare" to 800)).status)
        // done → the trip record keeps the agreed fare → "charged more" counted once
        ok(winner, "ambreq_step", mapOf("id" to id, "step" to "done"))
        assertEquals(x["agreed_fare"], row("SELECT agreed_fare FROM ambulance_calls WHERE uid = ? AND ambulance_id = ?", P, won)["agreed_fare"])
        ok(P, "ambulance_rate", mapOf("id" to won, "rating" to 2, "overcharged" to true))
        ok(P, "ambulance_rate", mapOf("id" to won, "rating" to 2, "overcharged" to true))
        assertEquals(1, ambRow(won)["overcharge_count"])
        person(OWNER, email = "owner@example.com")
        val adm = ok(OWNER, "admin_ambulances", mapOf("status" to "approved"))["items"].first { it["id"].textValue() == won }
        assertEquals(1, adm["overchargeCount"].intValue())
        assertTrue(adm["fareText"].textValue().isNotEmpty())
        ok(P, "ambulance_rate", mapOf("id" to won, "rating" to 3, "overcharged" to false))
        assertEquals(0, ambRow(won)["overcharge_count"])
        // only by phone (no agreed fare): "charged more" is not counted
        jdbc.update("INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at) VALUES (?, 'a5', ?, ?)", P, com.lifolo.common.Sql.ts(now() - 2 * HOUR), com.lifolo.common.Sql.ts(now() - 2 * HOUR))
        ok(P, "ambulance_rate", mapOf("id" to "a5", "rating" to 1, "overcharged" to true))
        assertEquals(0, ambRow("a5")["overcharge_count"])
        // 24 h later the destination goes too; the agreed fare stays
        reqs.dailyCleanup(now() + 25 * HOUR)
        val purged = reqRow(id)
        assertNull(purged["dest_kind"])
        assertEquals(x["agreed_fare"], purged["agreed_fare"])
    }

    @Test
    fun `offers last 2 minutes - the driver is released; a pending offer extends the 3 minutes; no new offers after 3 minutes`() {
        seed()
        val id = ask(P, mapOf("maxFare" to 2000)).body["id"].textValue()
        AmbClock.advance(10 * SEC)
        ok("d1", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        AmbClock.advance(2 * MIN + SEC)
        var v = status(id)
        assertEquals("open", v["status"].textValue())
        assertEquals(0, v["quotes"].size())
        assertTrue(pushes().any { it["uid"] == "d1" && (it["title"] as String).contains("উত্তর দেননি") }, "the driver is told")
        assertEquals(emptyList<Any>(), driverList("d1"))
        assertEquals(409, call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a1")).status)
        assertEquals(404, call("d1", "ambreq_accept", mapOf("id" to id, "fare" to 900)).status, "released driver not again")
        AmbClock.advance(39 * SEC) // 2:50
        ok("d2", "ambreq_accept", mapOf("id" to id))
        AmbClock.advance(20 * SEC) // 3:10
        v = status(id)
        assertEquals("open", v["status"].textValue(), "an offer is pending")
        assertEquals(1, v["quotes"].size())
        assertTrue(v["expiresAt"].longValue() > now() + 90 * SEC)
        assertEquals(409, call("d3", "ambreq_accept", mapOf("id" to id)).status, "no new offer after 3 minutes")
        AmbClock.advance(101 * SEC)
        v = status(id)
        assertEquals("expired", v["status"].textValue())
        assertTrue(pushes().any { it["uid"] == P && it["title"] == "ভাড়ার প্রস্তাবের সময় শেষ" })
    }

    @Test
    fun `raising the budget - raise-to after 60 s, new matching cars asked, never lower, unknown fares too`() {
        seed()
        val id = ask(P).body["id"].textValue()
        AmbClock.advance(61 * SEC)
        val v = status(id)
        assertEquals(2, v["wave"].intValue())
        assertEquals(2, v["notified"].intValue(), "nobody else within the budget")
        assertEquals(2000, v["raiseTo"].intValue())
        assertEquals(1, v["unknownFree"].intValue())
        assertTrue(v["fallback"].size() == 3 && v["fallback"][0]["id"].textValue() == "a1")
        clearPushes()
        assertEquals(400, call(P, "ambreq_budget", mapOf("id" to id, "maxFare" to 900)).status, "not lower")
        val up = ok(P, "ambreq_budget", mapOf("id" to id, "maxFare" to 2000))
        assertEquals(2, up["added"].intValue())
        assertEquals(listOf("d3", "d6"), pushedTo().sorted())
        assertEquals(mapOf("d1" to 800, "d2" to 1000, "d3" to 1500, "d6" to 1800), est(id))
        assertEquals(2000, reqRow(id)["max_fare"])
        assertTrue(up["request"]["expiresAt"].longValue() >= now() + 3 * MIN - SEC, "expiry starts again")
        assertEquals(5000, up["request"]["raiseTo"].intValue(), "a5 (2500) left → the next chip")
        ok("d6", "ambreq_accept", mapOf("id" to id))
        ok("d2", "ambreq_accept", mapOf("id" to id, "fare" to 1900))
        clearPushes()
        assertEquals(1, ok(P, "ambreq_budget", mapOf("id" to id, "maxFare" to 2000, "includeUnknown" to true))["added"].intValue())
        assertEquals(listOf("d4"), pushedTo())
        clearPushes()
        ok(P, "ambreq_budget", mapOf("id" to id, "maxFare" to null))
        assertEquals(listOf("d5"), pushedTo())
        assertNull(reqRow(id)["max_fare"])
        assertEquals(400, call(P, "ambreq_budget", mapOf("id" to id, "maxFare" to 5000)).status, "not lower than 'any'")
        ok(P, "ambreq_cancel", mapOf("id" to id))
        val legacy = call(P, "ambreq_create", base + mapOf("pickup" to "চরপাড়া", "phone" to "01811000000")).body["id"].textValue()
        assertEquals(409, call(P, "ambreq_budget", mapOf("id" to legacy, "maxFare" to 5000)).status)
    }

    @Test
    fun `phone rules as before - a phoned car can offer, byPhone closes, replacing clears offers; one accepted car at a time`() {
        seed()
        var id = ask(P).body["id"].textValue()
        ok(P, "ambulance_called", mapOf("id" to "a5", "reqId" to id))
        val card = driverList("d5")[0]
        assertTrue(card["called"].booleanValue())
        assertEquals(2500, card["suggestedFare"].intValue())
        assertEquals("over_budget", call("d5", "ambreq_accept", mapOf("id" to id)).code)
        ok("d5", "ambreq_accept", mapOf("id" to id, "fare" to 1000))
        ok("d1", "ambreq_accept", mapOf("id" to id))
        clearPushes()
        ok(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a5"))
        assertEquals(listOf("d1", "d2"), pushedTo().sorted())
        assertEquals(409, call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a1")).status)
        assertEquals(409, call("d2", "ambreq_accept", mapOf("id" to id, "fare" to 900)).status)

        id = ask(P, mapOf("maxFare" to 2000)).body["id"].textValue()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        ok("d2", "ambreq_accept", mapOf("id" to id))
        ok(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a1"))
        val v = ok(P, "ambreq_replace", mapOf("id" to id))["request"]
        assertEquals("open", v["status"].textValue())
        assertEquals(0, v["quotes"].size(), "earlier offers go")
        assertTrue(v["agreedFare"].isNull)
        assertEquals(409, call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a2")).status)
        assertEquals(404, call("d1", "ambreq_accept", mapOf("id" to id)).status, "the dropped driver not again")
        ok(P, "ambulance_called", mapOf("id" to "a5", "reqId" to id))
        ok("d2", "ambreq_accept", mapOf("id" to id))
        ok("d3", "ambreq_accept", mapOf("id" to id))
        val res = listOf(
            "agree:a2" to { call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a2")) },
            "agree:a3" to { call(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a3")) },
            "phone:a5" to { call(P, "ambreq_byphone", mapOf("id" to id, "ambulanceId" to "a5")) },
        ).parallelStream().map { it.second() }.toList()
        assertEquals(1, res.count { it.status == 200 }, res.map { it.status }.toString())
        val x = reqRow(id)
        val busy = jdbc.queryForObject("SELECT count(*) FROM ambulances WHERE req_busy = ?", Int::class.java, id)
        assertEquals(if (x["status"] == "accepted") 1 else 0, busy)
    }

    @Test
    fun `a confirmed driver's offer on another patient's request is withdrawn`() {
        seed()
        person("fare-patient-2")
        val a = ask(P, mapOf("maxFare" to 2000)).body["id"].textValue()
        val b = ask("fare-patient-2", mapOf("maxFare" to 2000)).body["id"].textValue()
        ok("d1", "ambreq_accept", mapOf("id" to a))
        ok("d1", "ambreq_accept", mapOf("id" to b))
        ok(P, "ambreq_agree", mapOf("id" to a, "ambulanceId" to "a1"))
        assertEquals(0, ok("fare-patient-2", "ambreq_status", mapOf("id" to b))["request"]["quotes"].size())
        assertEquals(409, call("fare-patient-2", "ambreq_agree", mapOf("id" to b, "ambulanceId" to "a1")).status)
    }

    @Test
    fun `demo is separate - a demo patient counts and calls only demo cars`() {
        seed()
        amb("demo-svc-fx", "demo-fx-drv", base = at(0.5, 0.0), chart = fc(700))
        val dp = "demo-patient-fare"
        person(dp)
        val dc = count(dp)
        assertEquals(1, dc["count"].intValue())
        assertEquals(0, dc["unknown"].intValue())
        assertEquals(2, count(P)["count"].intValue(), "a real patient: demo cars not counted")
        val r = ask(dp).body["id"].textValue()
        assertEquals(listOf("demo-fx-drv"), sentTo(r))
        ok("demo-fx-drv", "ambreq_accept", mapOf("id" to r))
        ok(dp, "ambreq_agree", mapOf("id" to r, "ambulanceId" to "demo-svc-fx"))
        assertEquals(700, reqRow(r)["agreed_fare"])
        assertEquals(404, call("d1", "ambreq_accept", mapOf("id" to r)).status)
        assertFalse(sentTo(r).contains("d1"))
        assertNotNull(reqRow(r)["is_demo"])
        assertEquals(true, reqRow(r)["is_demo"])
    }
}
