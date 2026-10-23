package com.lifolo.lab

import com.lifolo.common.Places
import com.lifolo.rx.RxLabTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md on the lab list: hours from the free text, homeCollection, distance to the upazila centre, no ratings, q, filters, sort */
class LabListingFactsIT : RxLabTest() {
    @Test
    fun `facts, filters, q and sort`() {
        for (u in listOf("la", "lb", "lc")) person(u, u)
        lab("la", "এ ডায়াগনস্টিক", upazilaId = 466, homeCollection = true)
        lab("lb", "বি প্যাথলজি", upazilaId = 463)
        lab("lc", "সি ল্যাব", upazilaId = null)
        jdbc.update("UPDATE labs SET hours = '২৪ ঘণ্টা' WHERE uid = 'la'")
        jdbc.update("UPDATE labs SET hours = 'সকাল ৮টা – রাত ১০টা' WHERE uid = 'lb'")
        jdbc.update("UPDATE labs SET hours = 'ফোন করে আসুন' WHERE uid = 'lc'")

        val c = Places.UPAZILA_CENTER.getValue(466)
        val items = ok(null, "labs", mapOf("district" to dist, "lat" to c.first, "lng" to c.second))["items"]
        val a = items.first { it["id"].textValue() == "la" }
        assertTrue(a["is24h"].booleanValue())
        assertTrue(a["openNow"].booleanValue())
        assertTrue(a["homeCollection"].booleanValue())
        assertTrue(a["ratingSummary"].isNull)
        assertEquals(0.0, a["distanceKm"].doubleValue())
        assertTrue(a["distanceApprox"].booleanValue())
        val b = items.first { it["id"].textValue() == "lb" }
        assertEquals("08:00", b["opensAt"].textValue())
        assertEquals("22:00", b["closesAt"].textValue())
        assertTrue(b["openNow"].isBoolean)
        assertFalse(b["is24h"].booleanValue())
        assertTrue(b["distanceKm"].doubleValue() > 1)
        val l = items.first { it["id"].textValue() == "lc" }
        assertTrue(l["openNow"].isNull)
        assertTrue(l["opensAt"].isNull)
        assertTrue(l["distanceKm"].isNull)
        // the old fields stay
        assertEquals("সকাল ৮টা – রাত ১০টা", b["hours"].textValue())

        fun list(extra: Map<String, Any?>) = ids(ok(null, "labs", mapOf("district" to dist) + extra)["items"])
        assertEquals(listOf("la"), list(mapOf("homeCollection" to true)))
        assertTrue("la" in list(mapOf("openNow" to true)))
        assertFalse("lc" in list(mapOf("openNow" to true)), "unknown hours are not \"open now\"")
        assertEquals(listOf("lb"), list(mapOf("q" to "প্যাথলজি")))
        assertEquals(listOf("la", "lb", "lc"), list(mapOf("sort" to "near", "upazilaId" to 466)))
        assertEquals("la", list(mapOf("sort" to "open"))[0])
        assertEquals("lc", list(mapOf("sort" to "open")).let { if (b["openNow"].booleanValue()) it[2] else it[1] })
    }
}
