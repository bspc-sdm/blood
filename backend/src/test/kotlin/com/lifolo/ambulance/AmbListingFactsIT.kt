package com.lifolo.ambulance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md on the ambulance list: no hours (openNow null), distanceKm (+ approx), ratingSummary, q, sort; old order without sort */
class AmbListingFactsIT : AmbTestBase() {
    @Test
    fun `facts, q and sort`() {
        person("viewer")
        amb("a1", "o1", name = "সেবা অ্যাম্বুলেন্স", base = at(0.0, 0.0), ratingSum = 9, ratingCount = 2)
        amb("a2", "o2", name = "জীবন রক্ষা", base = at(6.0, 0.0), ratingSum = 24, ratingCount = 5, available = false, availableAt = now() - 20 * HOUR)
        amb("a3", "o3", name = "দ্রুত সেবা", base = null)

        val p = at(0.0, 0.0)
        val r = ok("viewer", "ambulances", mapOf("district" to MYM, "lat" to p.first, "lng" to p.second, "gps" to true))
        val a1 = r["items"].first { it["id"].textValue() == "a1" }
        assertTrue(a1["openNow"].isNull)
        assertFalse(a1["is24h"].booleanValue())
        assertTrue(a1["opensAt"].isNull)
        assertEquals(0.0, a1["distanceKm"].doubleValue(), 1.0)
        assertFalse(a1["distanceApprox"].booleanValue())
        assertEquals(4.5, a1["ratingSummary"]["avg"].doubleValue())
        assertEquals(4.5, a1["rating"].doubleValue(), "old field unchanged")
        val a2 = r["items"].first { it["id"].textValue() == "a2" }
        assertEquals(6.0, a2["distanceKm"].doubleValue(), 1.0)
        assertEquals(4.8, a2["ratingSummary"]["avg"].doubleValue())
        // no base: the district centre, approximate
        val a3 = r["items"].first { it["id"].textValue() == "a3" }
        assertTrue(a3["distanceApprox"].booleanValue())
        assertTrue(a3["ratingSummary"].isNull)
        // without gps: true the viewer's point is approximate too (the old km rule)
        val nogps = ok("viewer", "ambulances", mapOf("district" to MYM, "lat" to p.first, "lng" to p.second))
        assertTrue(nogps["items"].first { it["id"].textValue() == "a1" }["distanceApprox"].booleanValue())
        assertFalse(nogps["items"].first { it["id"].textValue() == "a1" }.has("km"))

        fun order(extra: Map<String, Any?>) = ids(ok("viewer", "ambulances", mapOf("district" to MYM, "lat" to p.first, "lng" to p.second, "gps" to true) + extra))
        assertEquals(listOf("a1"), order(mapOf("q" to "সেবা অ্যা")))
        assertEquals(listOf("a1", "a3").sorted(), order(mapOf("q" to "সেবা")).sorted())
        assertEquals("a2", order(mapOf("sort" to "rating"))[0])
        assertEquals("a2", order(mapOf("sort" to "open")).last(), "not available last")
        val near = order(mapOf("sort" to "near"))
        assertTrue(near.indexOf("a1") < near.indexOf("a2"))
        assertEquals("a2", near.last())
        // no sort: available first as before
        assertEquals("a2", order(emptyMap()).last())
    }
}
