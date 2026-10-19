package com.lifolo.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** server/test/pharmacyrate.test.js */
class PharmacyRateIT : P4Test() {
    @Autowired
    lateinit var step: PharmacyDeletionStep

    private fun approvedShop(id: String, sum: Int = 0, count: Int = 0) = shop(id, mapOf("rating_sum" to sum, "rating_count" to count))

    private fun agg(id: String): List<Int> {
        val r = row("SELECT rating_sum, rating_count FROM pharmacies WHERE uid = ?", id)
        return listOf((r["rating_sum"] as Number).toInt(), (r["rating_count"] as Number).toInt())
    }

    private fun aspAgg(id: String): Map<String, List<Int>> {
        val r = row("SELECT * FROM pharmacies WHERE uid = ?", id)
        val out = LinkedHashMap<String, List<Int>>()
        for (k in ASPECTS) {
            val n = (r["aspect_${k}_count"] as Number).toInt()
            if (n != 0) out[k] = listOf((r["aspect_${k}_sum"] as Number).toInt(), n)
        }
        return out
    }

    private fun listItem(uid: String, id: String) = by(ok(uid, "pharmacies", mapOf("district" to MYMENSINGH)), id)

    private fun rating(uid: String, id: String) = row("SELECT * FROM pharmacy_ratings WHERE uid = ? AND pharmacy_uid = ?", uid, id)

    private fun deleteNow(uid: String) {
        step.deleteNow(uid, "$uid@example.com")
    }

    @Test
    fun `after a call - from 1 hour to 7 days, one rating per person, editing keeps the average right`() {
        val a = "phshopA0001"
        val u = "phpatient001"
        approvedShop(a, 8, 2)
        person(u)

        var r = call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 5)))
        assertEquals(403, r.status)
        assertEquals(false, listItem(u, a)["canRate"].booleanValue())
        assertEquals(0, listItem(u, a)["rateAspects"].size())
        assertEquals(400, call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 6))).status)
        assertEquals(400, call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("price" to 4))).status)
        assertEquals(400, call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 4, "medicine" to 5))).status)

        ok(u, "pharmacy_called", mapOf("id" to a))
        assertTrue(row("SELECT first_at FROM pharmacy_calls WHERE uid = ? AND pharmacy_uid = ?", u, a)["first_at"] != null)
        r = call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 5)))
        assertEquals(403, r.status)
        assertTrue(r.error!!.contains("১ ঘণ্টা"))
        assertEquals(false, listItem(u, a)["canRate"].booleanValue())

        jdbc.update("UPDATE pharmacy_calls SET first_at = ? WHERE uid = ? AND pharmacy_uid = ?", ts(System.currentTimeMillis() - 2 * HOUR), u, a)
        val it = listItem(u, a)
        assertEquals(true, it["canRate"].booleanValue())
        assertEquals(json(listOf("behaviour")), it["rateAspects"])
        r = call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 4, "price" to 2)))
        assertEquals(403, r.status)
        assertTrue(r.error!!.contains("ওষুধ নেওয়ার পর"))
        assertEquals(listOf(8, 2), agg(a))
        ok(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 4), "comment" to "  ভালো ব্যবহার  "))
        assertEquals(listOf(12, 3), agg(a))
        assertEquals(mapOf("behaviour" to listOf(4, 1)), aspAgg(a))
        val mine = rating(u, a)
        assertEquals(4, (mine["rating"] as Number).toInt())
        assertEquals(4, (mine["behaviour"] as Number).toInt())
        assertEquals(null, mine["price"])
        assertEquals("ভালো ব্যবহার", mine["comment"])
        val card = listItem(u, a)
        assertEquals(3, card["ratingCount"].intValue())
        assertEquals(4.0, card["rating"].doubleValue())
        assertEquals(json(mapOf("behaviour" to mapOf("rating" to 4, "count" to 1))), card["aspects"])

        // edit (old app: "rating" = behaviour)
        ok(u, "pharmacy_rate", mapOf("id" to a, "rating" to 2, "comment" to "দাম বেশি"))
        assertEquals(listOf(10, 3), agg(a))
        assertEquals(mapOf("behaviour" to listOf(2, 1)), aspAgg(a))
        val st = ok(a, "pharmacy_stats")
        assertEquals(3, st["ratingCount"].intValue())
        assertEquals(listOf(listOf("2", "দাম বেশি")), st["comments"].map { c -> listOf(c["rating"].asText(), c["comment"].textValue()) })
        assertTrue(st["comments"].all { c -> !c.has("who") })
        assertTrue(st["calls"]["30"].isNumber)
        assertEquals(json(mapOf("behaviour" to mapOf("rating" to 2, "count" to 1))), st["aspects"])

        // last call more than 7 days ago
        jdbc.update(
            "UPDATE pharmacy_calls SET at = ?, first_at = ? WHERE uid = ? AND pharmacy_uid = ?",
            ts(System.currentTimeMillis() - 8 * DAY), ts(System.currentTimeMillis() - 8 * DAY), u, a,
        )
        assertEquals(403, call(u, "pharmacy_rate", mapOf("id" to a, "aspects" to mapOf("behaviour" to 5))).status)
        assertEquals(false, listItem(u, a)["canRate"].booleanValue())
        assertEquals(listOf(10, 3), agg(a))
    }

    @Test
    fun `after buying - done order or dispensing within 7 days, price when bought, delivery when online`() {
        val b = "phshopB0001"
        val c2 = "phshopC0001"
        val d = "phshopD0001"
        val f2 = "phshopF0001"
        val u = "phbuyer0001"
        listOf(b, c2, d, f2).forEach { approvedShop(it) }
        person(u)
        val now = System.currentTimeMillis()
        for (x in listOf("rx0", "rx1", "rx3")) rx(x, x.uppercase().padEnd(10, 'A'), u, listOf(Med("Napa")))
        order("o1", mapOf("rx_id" to "rx1", "pharmacy_uid" to b, "patient_uid" to u, "status" to "done", "done_at" to ts(now - 2 * DAY), "created_at" to ts(now - 3 * DAY)))
        order("o2", mapOf("rx_id" to "rx1", "pharmacy_uid" to c2, "patient_uid" to u, "status" to "out", "mode" to "online", "created_at" to ts(now - DAY)))
        order("o3", mapOf("rx_id" to "rx0", "pharmacy_uid" to d, "patient_uid" to u, "status" to "done", "mode" to "online", "done_at" to ts(now - 8 * DAY), "created_at" to ts(now - 9 * DAY)))
        order("o4", mapOf("rx_id" to "rx3", "pharmacy_uid" to f2, "patient_uid" to u, "status" to "done", "mode" to "online", "done_at" to ts(now - DAY), "created_at" to ts(now - 29 * HOUR)))

        val items = ok(u, "pharmacies", mapOf("district" to MYMENSINGH))
        assertEquals(true, by(items, b)["canRate"].booleanValue())
        assertEquals(json(listOf("behaviour", "price")), by(items, b)["rateAspects"])
        assertEquals(false, by(items, c2)["canRate"].booleanValue())
        assertEquals(false, by(items, d)["canRate"].booleanValue())
        assertEquals(0, by(items, d)["rateAspects"].size())
        assertEquals(json(listOf("behaviour", "price", "delivery")), by(items, f2)["rateAspects"])

        var r = call(u, "pharmacy_rate", mapOf("id" to b, "aspects" to mapOf("behaviour" to 5, "price" to 4, "delivery" to 5)))
        assertEquals(403, r.status)
        assertTrue(r.error!!.contains("অনলাইন অর্ডার"))
        r = call(u, "pharmacy_rate", mapOf("id" to b, "aspects" to mapOf("behaviour" to 5, "price" to 4)))
        assertEquals(5, r.body["rating"].intValue())
        assertEquals(listOf(5, 1), agg(b))
        assertEquals(mapOf("behaviour" to listOf(5, 1), "price" to listOf(4, 1)), aspAgg(b))
        assertEquals(403, call(u, "pharmacy_rate", mapOf("id" to c2, "aspects" to mapOf("behaviour" to 5))).status)
        assertEquals(403, call(u, "pharmacy_rate", mapOf("id" to d, "aspects" to mapOf("behaviour" to 5))).status)

        r = call(u, "pharmacy_rate", mapOf("id" to f2, "aspects" to mapOf("behaviour" to 5, "price" to 4, "delivery" to 2), "comment" to "দেরিতে এসেছে"))
        assertEquals(4, r.body["rating"].intValue())
        assertEquals(listOf(4, 1), agg(f2))
        assertEquals(mapOf("behaviour" to listOf(5, 1), "price" to listOf(4, 1), "delivery" to listOf(2, 1)), aspAgg(f2))
        assertEquals(
            json(mapOf("behaviour" to mapOf("rating" to 5, "count" to 1), "price" to mapOf("rating" to 4, "count" to 1), "delivery" to mapOf("rating" to 2, "count" to 1))),
            listItem(u, f2)["aspects"],
        )
        ok(u, "pharmacy_rate", mapOf("id" to f2, "aspects" to mapOf("behaviour" to 3, "price" to 2)))
        assertEquals(listOf(3, 1), agg(f2))
        assertEquals(mapOf("behaviour" to listOf(3, 1), "price" to listOf(2, 1)), aspAgg(f2))
        assertEquals(null, rating(u, f2)["delivery"], "the earlier delivery rating does not stay")
        assertTrue(rating(u, f2)["first_rated_at"] != null)
        // old app (only "rating"): only behaviour changes, the price rating stays; combined (5 + 2) / 2 → 4
        r = call(u, "pharmacy_rate", mapOf("id" to f2, "rating" to 5))
        assertEquals(4, r.body["rating"].intValue())
        assertEquals(listOf(5, 2), listOf((rating(u, f2)["behaviour"] as Number).toInt(), (rating(u, f2)["price"] as Number).toInt()))
        assertEquals(listOf(4, 1), agg(f2))
        assertEquals(mapOf("behaviour" to listOf(5, 1), "price" to listOf(2, 1)), aspAgg(f2))
        ok(u, "pharmacy_rate", mapOf("id" to f2, "aspects" to mapOf("behaviour" to 3, "price" to 2)))
        assertEquals(mapOf("behaviour" to listOf(3, 1), "price" to listOf(2, 1)), aspAgg(f2))

        // dispensed at the counter from a prescription (no order): behaviour and price
        rx("rx2", "RXTWOAAAAA", u, listOf(Med("Napa")))
        dispensed("rx2", c2, now - 5 * DAY, listOf(0 to 10))
        assertEquals(json(listOf("behaviour", "price")), listItem(u, c2)["rateAspects"])
        ok(u, "pharmacy_rate", mapOf("id" to c2, "aspects" to mapOf("behaviour" to 3, "price" to 3)))
        assertEquals(listOf(3, 1), agg(c2))
        jdbc.update("UPDATE prescription_dispensings SET at = ? WHERE rx_id = 'rx2'", ts(now - 8 * DAY))
        assertEquals(false, listItem(u, c2)["canRate"].booleanValue())
    }

    @Test
    fun `not the owner on their own shop, not demo with real`() {
        val o = "phowner0001"
        approvedShop(o)
        rx("rxo", "RXOWNAAAAA", o, listOf(Med("Napa")))
        order("own1", mapOf("rx_id" to "rxo", "pharmacy_uid" to o, "patient_uid" to o, "status" to "done", "done_at" to ts(System.currentTimeMillis() - DAY)))
        val r = call(o, "pharmacy_rate", mapOf("id" to o, "aspects" to mapOf("behaviour" to 5)))
        assertEquals(403, r.status)
        assertTrue(r.error!!.contains("নিজের"))
        assertEquals(false, listItem(o, o)["canRate"].booleanValue())
        assertEquals(listOf(0, 0), agg(o))

        val dp = "demo-patient"
        person(dp)
        val two = ts(System.currentTimeMillis() - 2 * HOUR)
        jdbc.update("INSERT INTO pharmacy_calls (uid, pharmacy_uid, at, first_at) VALUES (?, ?, ?, ?)", dp, o, two, two)
        assertEquals(404, call(dp, "pharmacy_rate", mapOf("id" to o, "aspects" to mapOf("behaviour" to 1))).status)
        assertEquals(false, listItem(dp, o)["canRate"].booleanValue())

        val dph = "demo-pharmacy"
        approvedShop(dph)
        val ru = "phreal00001"
        person(ru)
        jdbc.update("INSERT INTO pharmacy_calls (uid, pharmacy_uid, at, first_at) VALUES (?, ?, ?, ?)", ru, dph, two, two)
        assertEquals(404, call(ru, "pharmacy_rate", mapOf("id" to dph, "aspects" to mapOf("behaviour" to 1))).status)
        assertEquals(false, listItem(ru, dph)["canRate"].booleanValue())
        assertEquals(listOf(0, 0), agg(dph))
        jdbc.update("INSERT INTO pharmacy_calls (uid, pharmacy_uid, at, first_at) VALUES (?, ?, ?, ?)", dp, dph, two, two)
        ok(dp, "pharmacy_rate", mapOf("id" to dph, "aspects" to mapOf("behaviour" to 4)))
        assertEquals(listOf(4, 1), agg(dph))
    }

    @Test
    fun `account deletion - ratings leave the averages and the owner's comments - deleting the shop drops its ratings`() {
        val e = "phshopE0001"
        val u = "phleaver001"
        val k2 = "phstayer001"
        approvedShop(e, 9, 2)
        val two = ts(System.currentTimeMillis() - 2 * HOUR)
        for (x in listOf(u, k2)) {
            person(x, "ক্রেতা")
            jdbc.update("INSERT INTO pharmacy_calls (uid, pharmacy_uid, at, first_at) VALUES (?, ?, ?, ?)", x, e, two, two)
        }
        rx("rxE", "RXEEEAAAAA", u, listOf(Med("Napa")))
        order("oE", mapOf("rx_id" to "rxE", "pharmacy_uid" to e, "patient_uid" to u, "status" to "done", "mode" to "online", "done_at" to ts(System.currentTimeMillis() - DAY)))
        ok(u, "pharmacy_rate", mapOf("id" to e, "aspects" to mapOf("behaviour" to 1, "price" to 2, "delivery" to 1), "comment" to "খারাপ"))
        ok(k2, "pharmacy_rate", mapOf("id" to e, "aspects" to mapOf("behaviour" to 5), "comment" to "ভালো"))
        assertEquals(listOf(15, 4), agg(e))
        assertEquals(mapOf("behaviour" to listOf(6, 2), "price" to listOf(2, 1), "delivery" to listOf(1, 1)), aspAgg(e))

        deleteNow(u)
        assertEquals(listOf(14, 3), agg(e))
        assertEquals(mapOf("behaviour" to listOf(5, 1)), aspAgg(e))
        assertTrue(rating(u, e).isEmpty())
        assertTrue(row("SELECT 1 AS x FROM pharmacy_calls WHERE uid = ? AND pharmacy_uid = ?", u, e).isEmpty())
        assertEquals(listOf("ভালো"), ok(e, "pharmacy_stats")["comments"].map { it["comment"].textValue() })
        assertEquals(3, listItem(k2, e)["ratingCount"].intValue())

        ok(e, "pharmacy_delete")
        assertTrue(rating(k2, e).isEmpty())
        approvedShop(e)
        ok(k2, "pharmacy_rate", mapOf("id" to e, "aspects" to mapOf("behaviour" to 3)))
        assertEquals(listOf(3, 1), agg(e))
        assertEquals(mapOf("behaviour" to listOf(3, 1)), aspAgg(e))

        deleteNow(e)
        assertTrue(rating(k2, e).isEmpty())
        assertFalse(row("SELECT 1 AS x FROM pharmacies WHERE uid = ?", e).isNotEmpty())
    }
}
