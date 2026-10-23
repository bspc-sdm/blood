package com.lifolo.lab

import com.lifolo.listings.ListingFacts
import com.lifolo.rx.RxLabTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md provider side: lab "খোলা/বন্ধ এখন" (folded into openNow) and "বাসা থেকে নমুনা চালু/বন্ধ" */
class LabSwitchesIT : RxLabTest() {
    private fun card(id: String) = ok("viewer", "labs", mapOf("district" to dist))["items"].first { it["id"].textValue() == id }

    @Test
    fun `open-now override and home collection switch`() {
        person("viewer", "viewer")
        person("lbS", "lbS")
        lab("lbS", "এস ল্যাব", upazilaId = 466)
        jdbc.update("UPDATE labs SET hours = 'ফোন করে আসুন' WHERE uid = 'lbS'")
        assertTrue(card("lbS")["openNow"].isNull, "hours not understood")

        val before = ListingFacts.nextDhakaMidnight(System.currentTimeMillis())
        val on = ok("lbS", "lab_open_override", mapOf("open" to true))
        val after = ListingFacts.nextDhakaMidnight(System.currentTimeMillis())
        assertTrue(on["openNow"].booleanValue())
        assertTrue(on["openOverride"]["until"].longValue() in setOf(before, after), "unknown hours → until midnight")
        assertTrue(card("lbS")["openNow"].booleanValue())
        assertEquals(listOf("lbS"), ids(ok("viewer", "labs", mapOf("district" to dist, "openNow" to true))["items"]))
        val me = ok("lbS", "lab_me")["lab"]
        assertTrue(me["openOverride"]["open"].booleanValue())
        assertTrue(me["openNow"].booleanValue())
        assertTrue(me.has("ratingSummary"))

        assertFalse(ok("lbS", "lab_open_override", mapOf("open" to false))["openNow"].booleanValue())
        assertFalse(card("lbS")["openNow"].booleanValue())
        ok("lbS", "lab_open_override", mapOf("open" to null))
        assertTrue(card("lbS")["openNow"].isNull)
        assertTrue(ok("lbS", "lab_me")["lab"]["openOverride"].isNull)

        // home collection
        assertFalse(card("lbS")["homeCollection"].booleanValue())
        assertTrue(ok("lbS", "lab_home_collection_set", mapOf("homeCollection" to true))["homeCollection"].booleanValue())
        assertTrue(card("lbS")["homeCollection"].booleanValue())
        assertEquals("approved", jdbc.queryForObject("SELECT status FROM labs WHERE uid = 'lbS'", String::class.java))
        assertEquals(400, call("lbS", "lab_home_collection_set", mapOf("homeCollection" to "x")).status)

        // only one's own lab; suspended refused; logged
        assertEquals(404, call("viewer", "lab_open_override", mapOf("open" to true)).status)
        jdbc.update("UPDATE labs SET status = 'suspended' WHERE uid = 'lbS'")
        assertEquals(403, call("lbS", "lab_home_collection_set", mapOf("homeCollection" to false)).status)
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM listing_switch_log WHERE kind = 'lab' AND owner_uid = 'lbS'", Int::class.java))
    }
}
