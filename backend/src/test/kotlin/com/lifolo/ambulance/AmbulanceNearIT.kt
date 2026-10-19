package com.lifolo.ambulance

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Places
import com.lifolo.common.Sql
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * port of server/test/ambulance-near.test.js. The Node fake Firestore lists documents in insertion order; PostgreSQL
 * (like real Firestore) lists them by id — ties are ordered by id here.
 */
class AmbulanceNearIT : AmbTestBase() {
    private val REAL = "near-patient"
    private val DEMO_P = "demo-patient"

    /** dx km east, dy km north, 3 decimals (the Node near test) */
    private fun at3(dx: Double, dy: Double): Pair<Double, Double> = Pair(
        Math.round((MYM_D.lat + dy / 111) * 1000) / 1000.0,
        Math.round((MYM_D.lng + dx / (111 * Math.cos(MYM_D.lat * Math.PI / 180))) * 1000) / 1000.0,
    )

    private fun gps(dx: Double, dy: Double) = at3(dx, dy).let { mapOf("lat" to it.first, "lng" to it.second, "gps" to true) }

    private fun pt(dx: Double, dy: Double) = at3(dx, dy).let { mapOf("lat" to it.first, "lng" to it.second) }

    /** Node amb(): remindAt on every car (even busy/pending) — the reminder must filter by itself */
    private fun car(
        id: String,
        owner: String,
        name: String = "গাড়ি $id",
        types: List<String> = listOf("general"),
        upazilaId: Int = SADAR,
        status: String = "approved",
        available: Boolean = true,
        availableAt: Long = now() - HOUR,
        busyAt: Long? = null,
        base: Pair<Double, Double>? = null,
        ratingSum: Int = 0,
        ratingCount: Int = 0,
        remind: Boolean = true,
        remindAt: Long? = null,
    ) = amb(
        id, owner, name = name, types = types, upazilaId = upazilaId, status = status, available = available, availableAt = availableAt,
        busyAt = busyAt, base = base, ratingSum = ratingSum, ratingCount = ratingCount,
        remindAt = if (remind) remindAt ?: (availableAt + AmbRules.REMIND_AT_MS) else null,
    )

    private fun list(uid: String?, body: Map<String, Any?> = emptyMap()): JsonNode {
        redis.delete(AmbulanceService.REMIND_KEY)
        val r = ok(uid, "ambulances", mapOf("district" to MYM) + body)
        idle()
        return r
    }

    private fun idle() {
        ambs.lastRemind.get(20, TimeUnit.SECONDS)
    }

    private fun seedNear() {
        person(REAL)
        person(DEMO_P)
        car("nA", "drvA", name = "আলফা", base = at3(0.0, 4.0), ratingSum = 25, ratingCount = 5)
        car("nB", "drvB", name = "ব্রাভো", types = listOf("ac", "general"), base = at3(1.0, 0.0), ratingSum = 9, ratingCount = 3)
        car("nF", "drvF", name = "ফ্রিজার", types = listOf("freezer"), base = at3(0.3, 0.0))
        car("nD", "drvD", name = "ডেল্টা আইসিইউ", types = listOf("icu", "ac"), upazilaId = TRISHAL, ratingSum = 16, ratingCount = 4)
        car("nE", "drvE", name = "ইকো", base = at3(0.0, 0.2), availableAt = now() - 20 * HOUR)
        car("nT", "drvT", name = "ট্রিপ", base = at3(0.0, 0.1), available = false, availableAt = now() - HOUR, busyAt = now() - HOUR)
        car("demo-svc-x-near", "demo-x-drv", name = "ডেমো কাছের", base = at3(0.1, 0.0))
        clearPushes()
    }

    @Test
    fun `without lat-lng (old app) - available first, rating, no km`() {
        seedNear()
        val r = list(REAL)
        // nF and the demo car tie (no rating): by id
        assertEquals(listOf("nA", "nD", "nB", "demo-svc-x-near", "nF", "nE"), ids(r))
        assertTrue(r["items"].all { !it.has("km") && !it.has("kmApprox") })
        assertEquals(listOf("nT"), r["busy"].map { it["id"].textValue() })
        assertEquals("nA", r["nearestId"].textValue(), "without distance the first free car")
        assertEquals(ids(r), ids(list(REAL, mapOf("lat" to 51.5, "lng" to -0.12))), "outside Bangladesh ignored")
    }

    @Test
    fun `with GPS - nearest first, whole km, cars without a base are approximate`() {
        seedNear()
        val r = list(REAL, gps(0.0, 0.0) + mapOf("upazilaId" to SADAR))
        assertEquals(listOf("demo-svc-x-near", "nF"), ids(r).take(2).sorted())
        assertEquals(listOf("nB", "nA", "nD", "nE"), ids(r).drop(2))
        val by = r["items"].associateBy { it["id"].textValue() }
        assertEquals(1, by.getValue("nB")["km"].intValue())
        assertEquals(3, by.getValue("nA")["km"].intValue(), "4 km really — 3 after cutting to the cell")
        assertEquals(0, by.getValue("nF")["km"].intValue())
        assertTrue(r["items"].all { it["km"].isInt })
        assertTrue(by.getValue("nD")["kmApprox"].booleanValue())
        assertEquals(0, by.getValue("nD")["km"].intValue())
        assertFalse(by.getValue("nB").has("kmApprox"))
        assertEquals(listOf("nT"), r["busy"].map { it["id"].textValue() })
        assertFalse(r["busy"][0].has("km"))
        assertEquals("nB", r["nearestId"].textValue(), "not a demo car for a real person, not a freezer van")
        val north = list(REAL, gps(0.0, 3.8))
        assertEquals("nA", north["items"][0]["id"].textValue())
        assertEquals(0, north["items"][0]["km"].intValue())
        assertEquals("nA", north["nearestId"].textValue())
    }

    @Test
    fun `filtering by type gives the nearest of that type, freezer only when asked`() {
        seedNear()
        val icu = list(REAL, gps(0.0, 0.0) + mapOf("type" to "icu"))
        assertEquals(listOf("nD"), ids(icu))
        assertEquals("nD", icu["nearestId"].textValue())
        assertTrue(icu["items"][0]["kmApprox"].booleanValue())
        val ac = list(REAL, gps(0.0, 0.0) + mapOf("type" to "ac"))
        assertEquals(listOf("nB", "nD"), ids(ac))
        assertEquals("nB", ac["nearestId"].textValue())
        assertEquals("nF", list(REAL, gps(0.0, 0.0) + mapOf("type" to "freezer"))["nearestId"].textValue())
    }

    @Test
    fun `demo and real - demo patients get demo cars, guests and real people not, own car not, calls counted per world`() {
        seedNear()
        assertEquals("demo-svc-x-near", list(DEMO_P, gps(0.0, 0.0))["nearestId"].textValue())
        assertEquals("nB", list(null, gps(0.0, 0.0))["nearestId"].textValue(), "guest = real world")
        assertEquals("nA", list("drvB", gps(0.0, 0.0))["nearestId"].textValue(), "not the driver's own car")
        ok(REAL, "ambulance_called", mapOf("id" to "nB"))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'nB'", Int::class.java, REAL))
        ok(REAL, "ambulance_called", mapOf("id" to "demo-svc-x-near"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'demo-svc-x-near'", Int::class.java, REAL))
        ok(DEMO_P, "ambulance_called", mapOf("id" to "demo-svc-x-near"))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'demo-svc-x-near'", Int::class.java, DEMO_P))
        // the owner's count: one call, one caller
        assertEquals(1, row("SELECT total FROM listing_stats WHERE kind = 'ambulance' AND listing_id = 'nB'")["total"])
        ok(REAL, "ambulance_called", mapOf("id" to "nB"))
        assertEquals(1, row("SELECT total FROM listing_stats WHERE kind = 'ambulance' AND listing_id = 'nB'")["total"], "again within 10 minutes: not a new call")
    }

    private val form = mapOf("phone" to "01711000099", "types" to listOf("general"), "district" to MYM, "upazilaId" to SADAR, "termsAccepted" to true)

    private fun ownCar(id: String, owner: String, name: String, vehicleNo: String, status: String = "approved") {
        person(owner)
        jdbc.update(
            """
            INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, upazila_id, available, available_at, status, created_at)
            VALUES (?, ?, ?, '01711000099', ?, '{general}', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, owner, name, vehicleNo, MYM, SADAR, status == "approved", if (status == "approved") Sql.ts(now()) else null, status, Sql.ts(now()),
        )
    }

    @Test
    fun `the driver's base - cut to the cell, kept without a new one, removed with another district, ignored outside the district`() {
        val d = "base-driver"
        ownCar("nBase", d, "জায়গা টেস্ট", "V-base")
        val base = form + mapOf("id" to "nBase", "name" to "জায়গা টেস্ট", "vehicleNo" to "V-base")
        ok(d, "ambulance_save", base + pt(2.0, 0.0))
        var s = ambRow("nBase")
        assertEquals(listOf(r2(at3(2.0, 0.0).first), r2(at3(2.0, 0.0).second)), listOf(s["lat"], s["lng"]), "2 decimals (≈1 km), not the real GPS")
        assertEquals("approved", s["status"], "a base needs no review")
        assertTrue(ok(d, "ambulance_mine")["items"][0]["hasBase"].booleanValue())
        ok(d, "ambulance_save", base + mapOf("fare" to "৫০০ টাকা"))
        assertEquals(r2(at3(2.0, 0.0).first), ambRow("nBase")["lat"], "kept without a new place")
        val far = ok(d, "ambulance_save", base + mapOf("fare" to "৬০০ টাকা", "lat" to 22.33, "lng" to 91.83))
        assertTrue(far["baseIgnored"].booleanValue())
        s = ambRow("nBase")
        assertEquals("৬০০ টাকা", s["fare"])
        assertEquals(r2(at3(2.0, 0.0).first), s["lat"])
        val other = Places.DISTRICTS.first { it.en == "Jamalpur" }.bn
        ok(d, "ambulance_save", base + mapOf("district" to other, "upazilaId" to null))
        assertNull(ambRow("nBase")["lat"], "another district: the old base goes")
    }

    // ---------------- "still available?" ----------------

    @Test
    fun `reminder - once in the last hour, not outside the window, on a trip, expired or unapproved`() {
        val now = now()
        car("rDue", "rOwn1", name = "জীবন মিনি", availableAt = now - 11 * HOUR - 30 * MIN)
        car("rEarly", "rOwn2", availableAt = now - 10 * HOUR)
        car("rGone", "rOwn3", availableAt = now - 12 * HOUR - 30 * MIN)
        car("rBusy", "rOwn4", available = false, availableAt = now - 11 * HOUR - 30 * MIN, busyAt = now - 11 * HOUR - 30 * MIN)
        car("rPend", "rOwn5", status = "pending", availableAt = now - 11 * HOUR - 30 * MIN)
        clearPushes()
        assertEquals(1, ambs.remindAvailable(now))
        val p = pushes().single()
        assertEquals("rOwn1", p["uid"])
        assertEquals("জীবন মিনি — এখনো খালি আছেন?", p["title"])
        assertEquals(json(mapOf("type" to "services", "kind" to "ambulance", "ambulanceId" to "rDue")), mapper.readTree(p["data"] as String))
        assertEquals(now, (ambRow("rDue")["reminder_sent_at"] as java.sql.Timestamp).time)
        for (id in listOf("rDue", "rGone", "rBusy", "rPend")) assertNull(ambRow(id)["remind_at"], id)
        assertEquals(now - 10 * HOUR + 11 * HOUR, (ambRow("rEarly")["remind_at"] as java.sql.Timestamp).time)
        assertEquals(0, ambs.remindAvailable(now + 20 * MIN))
        assertEquals(1, pushes().size)
        val mine = ok("rOwn1", "ambulance_mine")["items"][0]
        assertTrue(mine["available"].booleanValue())
        assertTrue(Math.abs(mine["availableUntil"].longValue() - (now + 30 * MIN)) < 1000, "30 more minutes")
    }

    @Test
    fun `keep available again - a new 12 hours, reminded once more in its last hour, off removes the reminder`() {
        val now = now() - 1000
        car("rRenew", "rOwnR", name = "নবায়ন", availableAt = now - 11 * HOUR - 30 * MIN)
        clearPushes()
        assertEquals(1, ambs.remindAvailable(now))
        ok("rOwnR", "ambulance_available", mapOf("id" to "rRenew", "available" to true))
        val s = repo.get("rRenew")!!
        assertTrue(s.availableAt!! >= now && s.available)
        assertEquals(s.availableAt!! + 11 * HOUR, s.remindAt, "the new shift's remindAt")
        assertTrue(ok("rOwnR", "ambulance_mine")["items"][0]["availableUntil"].longValue() - now() > 11.9 * HOUR)
        assertEquals(0, ambs.remindAvailable(now() + HOUR))
        assertEquals(1, ambs.remindAvailable(s.availableAt!! + 11 * HOUR + 30 * MIN))
        assertEquals(2, pushes().size)
        ok("rOwnR", "ambulance_available", mapOf("id" to "rRenew", "available" to false))
        val s2 = repo.get("rRenew")!!
        assertNull(s2.remindAt, "off removes remindAt")
        assertEquals(0, ambs.remindAvailable(s2.availableAt!! + 11 * HOUR + 30 * MIN))
    }

    @Test
    fun `reading the list looks for reminders - once per 10 minutes, demo cars only to demo drivers, guests too`() {
        val now = now()
        person(REAL)
        car("lDue", "lOwn", availableAt = now - 11 * HOUR - 12 * MIN)
        clearPushes()
        ok(REAL, "ambulances", mapOf("district" to MYM))
        idle()
        assertEquals(listOf("lDue"), pushes().map { mapper.readTree(it["data"] as String)["ambulanceId"].textValue() }, "opening the list searches")
        car("demo-svc-x-due", "demo-x-drv2", name = "ডেমো মনে করানো", availableAt = now - 11 * HOUR - 36 * MIN)
        ok(REAL, "ambulances", mapOf("district" to MYM, "upazilaId" to SADAR))
        idle()
        assertEquals(1, pushes().size, "within 10 minutes this does not search again")
        redis.delete(AmbulanceService.REMIND_KEY)
        ok(REAL, "ambulances", mapOf("district" to MYM))
        idle()
        assertEquals(2, pushes().size)
        assertEquals("demo-x-drv2", pushes()[1]["uid"])
        assertEquals("ডেমো মনে করানো — এখনো খালি আছেন?", pushes()[1]["title"])
        redis.delete(AmbulanceService.REMIND_KEY)
        ok(null, "ambulances", mapOf("district" to MYM))
        idle()
        assertEquals(2, pushes().size)
    }

    @Test
    fun `privacy - the driver's real GPS is not kept, probes inside one cell all get the same answer`() {
        person(REAL)
        val d = "priv-driver"
        ownCar("pv", d, "গোপন জায়গা", "V-priv")
        ok(d, "ambulance_save", form + mapOf("id" to "pv", "name" to "গোপন জায়গা", "vehicleNo" to "V-priv", "lat" to 24.75341, "lng" to 90.41893))
        val s = ambRow("pv")
        assertEquals(listOf(24.75, 90.42), listOf(s["lat"], s["lng"]))
        // an older 3-decimal base is cut when read
        amb("pOld", "pOldDrv", base = Pair(24.783, 90.407), exactBase = true)
        val probes = listOf(24.7451 to 90.4051, 24.7549 to 90.4149, 24.75 to 90.41, 24.7462 to 90.4138, 24.7537 to 90.4066)
        val seen = probes.map { (lat, lng) ->
            list(REAL, mapOf("lat" to lat, "lng" to lng, "gps" to true))["items"].map { it["id"].textValue() to it["km"].intValue() }
        }
        assertEquals(1, seen.toSet().size, "where in the cell the request came from changes nothing")
        val by = seen[0].toMap()
        assertEquals(1, by["pv"])
        assertEquals(3, by["pOld"])
        val mine = ok(d, "ambulance_mine")["items"][0]
        assertTrue(mine["hasBase"].booleanValue())
        assertFalse(mine.has("lat"))
    }

    @Test
    fun `clear base - removed even with new lat-lng, district centre then, within 130 km is fine`() {
        person(REAL)
        val d = "clr-driver"
        ownCar("cl", d, "মুছুন টেস্ট", "V-clr")
        val f = form + mapOf("id" to "cl", "name" to "মুছুন টেস্ট", "vehicleNo" to "V-clr")
        val r = ok(d, "ambulance_save", f + pt(0.0, -110.0))
        assertFalse(r.has("baseIgnored"))
        assertTrue(ambRow("cl")["lat"] is Double)
        assertTrue(ok(d, "ambulance_save", f + pt(0.0, -140.0))["baseIgnored"].booleanValue())
        ok(d, "ambulance_save", f + mapOf("clearBase" to true) + pt(1.0, 1.0))
        val s = ambRow("cl")
        assertNull(s["lat"])
        assertNull(s["lng"])
        assertEquals("approved", s["status"])
        assertFalse(ok(d, "ambulance_mine")["items"][0]["hasBase"].booleanValue())
        val l = ok(REAL, "ambulances", mapOf("district" to MYM) + gps(0.0, 0.0))
        assertTrue(l["items"].first { it["id"].textValue() == "cl" }["kmApprox"].booleanValue())
    }

    @Test
    fun `coordinates without gps are approximate - own upazila first, no km, real GPS - distance first`() {
        seedNear()
        val approx = list(REAL, pt(0.0, 0.0) + mapOf("upazilaId" to TRISHAL))
        assertEquals("nD", approx["items"][0]["id"].textValue())
        assertTrue(approx["items"].all { !it.has("km") && !it.has("kmApprox") })
        assertEquals(listOf("nB", "nA", "nE"), ids(approx).drop(3))
        val real = list(REAL, gps(0.0, 0.0) + mapOf("upazilaId" to TRISHAL))
        assertNotEquals("nD", real["items"][0]["id"].textValue())
        assertTrue(ids(real).indexOf("nD") > ids(real).indexOf("nA"))
        val noUpa = list(REAL, pt(0.0, 0.0))
        assertEquals(listOf("nB", "nA", "nD", "nE"), ids(noUpa).drop(2))
    }

    @Test
    fun `reminder paging - 230 due cars all once, approval and review rules for remindAt`() {
        val now = now()
        person("pgOwn")
        for (i in 0 until 230) {
            val id = "pg${i.toString().padStart(3, '0')}"
            jdbc.update(
                """
                INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, upazila_id, status, available, available_at, remind_at, created_at)
                VALUES (?, 'pgOwn', ?, '01711000000', ?, '{general}', ?, ?, 'approved', TRUE, ?, ?, ?)
                """.trimIndent(),
                id, "পাতা $i", "V-$id", MYM, SADAR, Sql.ts(now - 11 * HOUR - 30 * MIN), Sql.ts(now - 30 * MIN), Sql.ts(now - 30 * DAY),
            )
        }
        assertEquals(230, ambs.remindAvailable(now))
        assertEquals(230, pushes().size)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ambulances WHERE remind_at IS NOT NULL", Int::class.java))
        assertEquals(0, ambs.remindAvailable(now + MIN))

        val d = "ra-driver"
        ownCar("ra", d, "রিমাইন্ড টেস্ট", "V-ra", status = "pending")
        jdbc.update("UPDATE ambulances SET available = FALSE WHERE id = 'ra'")
        ok(d, "ambulance_available", mapOf("id" to "ra", "available" to true))
        assertNull(ambRow("ra")["remind_at"])
        jdbc.update("UPDATE ambulances SET status = 'approved' WHERE id = 'ra'")
        ok(d, "ambulance_available", mapOf("id" to "ra", "available" to true))
        val x = repo.get("ra")!!
        assertEquals(x.availableAt!! + 11 * HOUR, x.remindAt)
        ok(d, "ambulance_save", form + mapOf("id" to "ra", "name" to "রিমাইন্ড টেস্ট", "vehicleNo" to "V-ra-2"))
        val y = repo.get("ra")!!
        assertEquals("pending", y.status)
        assertNull(y.remindAt, "back to review: no reminder")
    }

    @Test
    fun `old available cars without remindAt - the daily backfill sets it and reminds when due`() {
        val now = now()
        car("old1", "oldOwn1", name = "পুরোনো", availableAt = now - 11 * HOUR - 30 * MIN, remind = false)
        car("old2", "oldOwn2", availableAt = now - 3 * HOUR, remind = false)
        car("oldOff", "oldOwn3", available = false, availableAt = now - 3 * HOUR, busyAt = now - 3 * HOUR, remind = false)
        clearPushes()
        assertEquals(0, ambs.remindAvailable(now), "without remindAt not read at all")
        assertEquals(1, ambs.remindAvailable(now, backfill = true))
        assertEquals(listOf("old1"), pushes().map { mapper.readTree(it["data"] as String)["ambulanceId"].textValue() })
        assertEquals(now - 3 * HOUR + 11 * HOUR, (ambRow("old2")["remind_at"] as java.sql.Timestamp).time)
        assertNull(ambRow("oldOff")["remind_at"])
        assertEquals(0, ambs.remindAvailable(now + MIN, backfill = true))
        assertNull(ambRow("old1")["remind_at"])
    }

    @Test
    fun `daily job steps 12 and 13 run in the cron answer`() {
        car("cron1", "cronOwn", availableAt = now() - 11 * HOUR - 30 * MIN)
        val r = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, r.status)
        assertEquals(1, r.body["ambulanceReminders"].intValue())
        assertEquals(0, r.body["ambulanceRequests"]["advanced"].intValue())
        assertEquals(0, r.body["ambulanceRequests"]["purged"].intValue())
    }
}
