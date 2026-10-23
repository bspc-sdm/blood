package com.lifolo.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md on the pharmacy list: openNow/is24h/opensAt/closesAt, distanceKm, ratingSummary, delivery filter, q, sort */
class PharmacyListingFactsIT : P4Test() {
    @Test
    fun `facts on every card, q, delivery filter and the three sorts`() {
        setDhaka("2026-10-20 23:00")
        shop("phA", mapOf("name" to "আলফা ফার্মেসি", "lat" to 24.75, "lng" to 90.40, "open_at" to "08:00", "close_at" to "22:00", "rating_sum" to 9, "rating_count" to 2, "delivery" to true))
        shop("phB", mapOf("name" to "বিটা মেডিসিন", "lat" to 24.75, "lng" to 90.45, "open24" to true, "open_at" to "", "close_at" to "", "rating_sum" to 46, "rating_count" to 10, "address" to "গাঙ্গিনারপাড়"))
        shop("phC", mapOf("name" to "সি ড্রাগ হাউস", "lat" to null, "lng" to null, "open_at" to "09:00", "close_at" to "23:30"))

        val r = ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "lat" to 24.75, "lng" to 90.40))
        val a = r["items"].first { it["id"].textValue() == "phA" }
        assertFalse(a["openNow"].booleanValue())
        assertFalse(a["is24h"].booleanValue())
        assertEquals("08:00", a["opensAt"].textValue())
        assertEquals("22:00", a["closesAt"].textValue())
        assertEquals(0.0, a["distanceKm"].doubleValue())
        assertFalse(a["distanceApprox"].booleanValue())
        assertEquals(4.5, a["ratingSummary"]["avg"].doubleValue())
        assertEquals(2, a["ratingSummary"]["count"].intValue())
        // the old fields stay as they were
        assertEquals(4.5, a["rating"].doubleValue())
        assertEquals(2, a["ratingCount"].intValue())
        assertTrue(a["delivery"].booleanValue())
        val b = r["items"].first { it["id"].textValue() == "phB" }
        assertTrue(b["is24h"].booleanValue())
        assertTrue(b["opensAt"].isNull)
        assertTrue(b["openNow"].booleanValue())
        assertEquals(5.0, b["distanceKm"].doubleValue(), 0.11)
        val c = r["items"].first { it["id"].textValue() == "phC" }
        assertTrue(c["openNow"].booleanValue())
        assertTrue(c["ratingSummary"].isNull)
        assertTrue(c["distanceKm"].isNull)
        // without lat/lng: from the upazila centre, approximate
        val up = ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "upazilaId" to 466))["items"].first { it["id"].textValue() == "phA" }
        assertTrue(up["distanceKm"].isNumber)
        assertTrue(up["distanceApprox"].booleanValue())

        fun list(extra: Map<String, Any?>) = ids(ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "lat" to 24.75, "lng" to 90.40) + extra))
        assertEquals(listOf("phA"), list(mapOf("delivery" to true)))
        assertEquals(listOf("phB"), list(mapOf("q" to "গাঙ্গিনা")))
        assertEquals(listOf("phB"), list(mapOf("q" to "বিটা")))
        assertEquals(emptyList<String>(), list(mapOf("q" to "নেই এমন")))
        assertEquals(listOf("phA", "phB", "phC"), list(mapOf("sort" to "near")))
        assertEquals(listOf("phB", "phA", "phC"), list(mapOf("sort" to "rating")))
        assertEquals("phB", list(mapOf("sort" to "rating"))[0], "4.6 before 4.5")
        assertEquals("phA", list(mapOf("sort" to "open")).last(), "closed last")
        // unknown sort = the old order (open first, then near)
        assertEquals(list(emptyMap()), list(mapOf("sort" to "cheap")))
    }
}
