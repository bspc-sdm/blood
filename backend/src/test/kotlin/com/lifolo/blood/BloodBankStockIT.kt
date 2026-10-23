package com.lifolo.blood

import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

/** LISTINGS.md: blood bank stock per group (bloodbank_stock_set: owner only, validation, refresh, quota), staleness, list facts */
class BloodBankStockIT : BloodTestBase() {
    private val sadar = 466

    private fun bank(id: String, owner: String?, status: String = "approved", name: String = "ব্যাংক $id", open24: Boolean = true, upazilaId: Int? = sadar) {
        jdbc.update(
            """
            INSERT INTO blood_banks (id, owner_uid, added_by_admin, name, kind, phone, district, upazila_id, address, open24, open_at, close_at, services, status)
            VALUES (?, ?, ?, ?, 'org', '01712345678', ?, ?, 'চরপাড়া', ?, ?, ?, '{whole_blood}', ?)
            """.trimIndent(),
            id, owner, owner == null, name, DIST, upazilaId, open24, if (open24) "" else "08:00", if (open24) "" else "20:00", status,
        )
    }

    private val full = mapOf("A+" to "have", "A-" to "low", "B+" to "none", "B-" to null, "AB+" to "have", "AB-" to "none", "O+" to "have", "O-" to "low")

    @Test
    fun `owner sets the stock, everyone sees it, other people cannot, bad input is refused`() {
        user("own", mapOf("name" to "মালিক"))
        user("other", emptyMap())
        bank("bb1", "own")
        bank("bb2", "other", name = "অন্য ব্যাংক")

        // before: never set
        val before = call("other", "bloodbanks", mapOf("district" to DIST)).body["items"].first { it["id"].textValue() == "bb1" }
        assertTrue(before["stock"].isNull)
        assertTrue(before["stockUpdatedAt"].isNull)
        assertFalse(before["stockStale"].booleanValue())

        val r = call("own", "bloodbank_stock_set", mapOf("groups" to full))
        assertEquals(200, r.status, r.toString())
        assertEquals("bb1", r.body["id"].textValue())
        assertEquals("have", r.body["stock"]["A+"].textValue())
        assertTrue(r.body["stock"]["B-"].isNull)
        assertEquals(8, r.body["stock"].size())
        assertFalse(r.body["stockStale"].booleanValue())
        val at = r.body["stockUpdatedAt"].longValue()
        assertTrue(System.currentTimeMillis() - at < 60_000)
        assertEquals("own", row("SELECT updated_by FROM blood_bank_stock WHERE bank_id = 'bb1'")["updated_by"])

        // the public list (guest too) and the owner's list
        val pub = call(null, "bloodbanks", mapOf("district" to DIST), ip = "7.7.7.7").body["items"].first { it["id"].textValue() == "bb1" }
        assertEquals("low", pub["stock"]["O-"].textValue())
        assertEquals(at, pub["stockUpdatedAt"].longValue())
        assertEquals("have", call("own", "bloodbank_mine").body["items"].single()["stock"]["AB+"].textValue())

        // someone else's bank, unknown id, a non-owner without banks
        assertEquals(404, call("own", "bloodbank_stock_set", mapOf("id" to "bb2", "groups" to full)).status)
        assertEquals(404, call("own", "bloodbank_stock_set", mapOf("id" to "nope", "groups" to full)).status)
        user("nobank", emptyMap())
        assertEquals(404, call("nobank", "bloodbank_stock_set", mapOf("groups" to full)).status)
        assertEquals(401, call(null, "bloodbank_stock_set", mapOf("groups" to full), ip = "7.7.7.8").status)
        // bad input
        assertEquals(400, call("own", "bloodbank_stock_set", emptyMap()).status)
        assertEquals(400, call("own", "bloodbank_stock_set", mapOf("groups" to mapOf("C+" to "have"))).status)
        assertEquals(400, call("own", "bloodbank_stock_set", mapOf("groups" to mapOf("A+" to "many"))).status)
        assertEquals(400, call("own", "bloodbank_stock_set", mapOf("groups" to "A+")).status)
        // a partial map = the rest unknown
        val part = call("own", "bloodbank_stock_set", mapOf("id" to "bb1", "groups" to mapOf("O+" to "none"))).body
        assertEquals("none", part["stock"]["O+"].textValue())
        assertTrue(part["stock"]["A+"].isNull)
    }

    @Test
    fun `refresh without changes, staleness after 48 hours, two banks need the id, suspended, daily limit`() {
        user("own", emptyMap())
        bank("bb1", "own")
        assertEquals(400, call("own", "bloodbank_stock_set", mapOf("refresh" to true)).status, "nothing to refresh yet")
        call("own", "bloodbank_stock_set", mapOf("groups" to full))
        jdbc.update("UPDATE blood_bank_stock SET updated_at = ? WHERE bank_id = 'bb1'", Timestamp(System.currentTimeMillis() - 49 * 3600 * 1000L))
        val stale = call("own", "bloodbanks", mapOf("district" to DIST)).body["items"].single()
        assertTrue(stale["stockStale"].booleanValue())
        assertEquals("have", stale["stock"]["A+"].textValue())
        // "সব ঠিক আছে — এখন হালনাগাদ"
        val fresh = call("own", "bloodbank_stock_set", mapOf("refresh" to true)).body
        assertFalse(fresh["stockStale"].booleanValue())
        assertEquals("have", fresh["stock"]["A+"].textValue())
        assertEquals("low", fresh["stock"]["A-"].textValue())
        assertFalse(call("own", "bloodbanks", mapOf("district" to DIST)).body["items"].single()["stockStale"].booleanValue())

        // a second bank: the id is needed
        bank("bb3", "own", status = "pending")
        assertEquals(400, call("own", "bloodbank_stock_set", mapOf("groups" to full)).status)
        assertEquals(200, call("own", "bloodbank_stock_set", mapOf("id" to "bb3", "groups" to full)).status)
        // suspended
        jdbc.update("UPDATE blood_banks SET status = 'suspended' WHERE id = 'bb3'")
        assertEquals(403, call("own", "bloodbank_stock_set", mapOf("id" to "bb3", "groups" to full)).status)
        // 60 a day
        jdbc.update(
            """
            INSERT INTO user_quotas (uid, kind, day, count) VALUES ('own', 'bbStock', (now() AT TIME ZONE 'Asia/Dhaka')::date, 60)
            ON CONFLICT (uid, kind) DO UPDATE SET count = 60, day = EXCLUDED.day
            """.trimIndent(),
        )
        assertEquals(429, call("own", "bloodbank_stock_set", mapOf("id" to "bb1", "refresh" to true)).status)
        // removing the bank removes its stock
        jdbc.update("DELETE FROM blood_banks WHERE id = 'bb1'")
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_bank_stock WHERE bank_id = 'bb1'")["n"])
    }

    @Test
    fun `list facts - hours, distance from the upazila centre, no ratings, q, group filter, sort`() {
        user("own", emptyMap())
        user("p1", emptyMap())
        bank("bb1", "own", name = "জীবন ব্লাড ব্যাংক", open24 = true)
        bank("bb2", null, name = "আলো ক্লাব", open24 = false, upazilaId = 463)
        bank("bb3", null, name = "নো এরিয়া", open24 = false, upazilaId = null)
        call("own", "bloodbank_stock_set", mapOf("id" to "bb1", "groups" to mapOf("O-" to "low", "A+" to "none")))

        val c = Places.UPAZILA_CENTER.getValue(sadar)
        val list = call("p1", "bloodbanks", mapOf("district" to DIST, "lat" to c.first, "lng" to c.second)).body["items"]
        val b1 = list.first { it["id"].textValue() == "bb1" }
        assertTrue(b1["is24h"].booleanValue())
        assertTrue(b1["opensAt"].isNull)
        assertTrue(b1["ratingSummary"].isNull)
        assertEquals(0.0, b1["distanceKm"].doubleValue())
        assertTrue(b1["distanceApprox"].booleanValue())
        val b2 = list.first { it["id"].textValue() == "bb2" }
        assertFalse(b2["is24h"].booleanValue())
        assertEquals("08:00", b2["opensAt"].textValue())
        assertEquals("20:00", b2["closesAt"].textValue())
        assertTrue(b2["distanceKm"].doubleValue() > 1)
        assertTrue(list.first { it["id"].textValue() == "bb3" }["distanceKm"].isNull)
        // no lat/lng: the request's upazila centre
        assertEquals(0.0, call("p1", "bloodbanks", mapOf("district" to DIST, "upazilaId" to sadar)).body["items"].first { it["id"].textValue() == "bb1" }["distanceKm"].doubleValue())
        // no place at all → null
        assertTrue(call("p1", "bloodbanks", mapOf("district" to DIST)).body["items"].all { it["distanceKm"].isNull })

        fun ids(extra: Map<String, Any?>) = call("p1", "bloodbanks", mapOf("district" to DIST) + extra).body["items"].map { it["id"].textValue() }
        assertEquals(listOf("bb1"), ids(mapOf("q" to "জীবন")))
        assertEquals(listOf("bb1"), ids(mapOf("group" to "O-")))
        assertEquals(emptyList<String>(), ids(mapOf("group" to "A+")))
        assertEquals(3, ids(mapOf("group" to "X")).size)
        assertEquals(listOf("bb1", "bb2", "bb3"), ids(mapOf("sort" to "near", "upazilaId" to sadar)))
    }
}
