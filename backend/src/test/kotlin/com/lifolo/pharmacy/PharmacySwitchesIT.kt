package com.lifolo.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md provider side: pharmacy "খোলা/বন্ধ এখন" (override with expiry, folded into openNow) and "ডেলিভারি চালু/বন্ধ" */
class PharmacySwitchesIT : P4Test() {
    /** a logged-in viewer (guest answers are cached for a minute) */
    private fun card(id: String) = by(ok("viewer", "pharmacies", mapOf("district" to MYMENSINGH)), id)

    @Test
    fun `open-now override - closed during hours until closing, open after hours until opening, cleared, pause wins`() {
        setDhaka("2026-10-20 10:00")
        person("viewer")
        shop("phS")
        assertTrue(card("phS")["openNow"].booleanValue())

        val off = ok("phS", "pharmacy_open_override", mapOf("open" to false))
        assertFalse(off["openNow"].booleanValue())
        assertFalse(off["openOverride"]["open"].booleanValue())
        assertEquals(setDhaka("2026-10-20 22:00").also { setDhaka("2026-10-20 10:00") }, off["openOverride"]["until"].longValue())
        assertFalse(card("phS")["openNow"].booleanValue())
        assertEquals(listOf<String>(), ids(ok("viewer", "pharmacies", mapOf("district" to MYMENSINGH, "openNow" to true))))
        // the owner's view: the override and the card facts
        val me = ok("phS", "pharmacy_me")["pharmacy"]
        assertFalse(me["openNow"].booleanValue())
        assertFalse(me["openOverride"]["open"].booleanValue())
        assertEquals("08:00", me["opensAt"].textValue())
        assertTrue(me.has("ratingSummary"))
        // it ends at 22:00 by itself; next morning the hours decide again
        setDhaka("2026-10-21 09:00")
        assertTrue(card("phS")["openNow"].booleanValue())
        assertTrue(ok("phS", "pharmacy_me")["pharmacy"]["openOverride"].isNull)

        // open after hours → until 08:00
        setDhaka("2026-10-21 23:00")
        val on = ok("phS", "pharmacy_open_override", mapOf("open" to true))
        assertTrue(on["openNow"].booleanValue())
        assertTrue(card("phS")["openNow"].booleanValue())
        setDhaka("2026-10-22 07:59")
        assertTrue(card("phS")["openNow"].booleanValue())
        // back to the hours
        val clear = ok("phS", "pharmacy_open_override", mapOf("open" to null))
        assertFalse(clear["openNow"].booleanValue())
        assertTrue(clear["openOverride"].isNull)

        // a pause wins; opening during a pause is refused
        jdbc.update("UPDATE pharmacies SET closed_until = ? WHERE uid = 'phS'", ts(clock.now() + 2 * DAY))
        assertEquals(409, call("phS", "pharmacy_open_override", mapOf("open" to true)).status)
        assertEquals(200, call("phS", "pharmacy_open_override", mapOf("open" to false)).status)

        // bad input, someone without a pharmacy, a guest
        assertEquals(400, call("phS", "pharmacy_open_override", mapOf("open" to "yes")).status)
        person("nobody")
        assertEquals(404, call("nobody", "pharmacy_open_override", mapOf("open" to false)).status)
        assertEquals(401, call(null, "pharmacy_open_override", mapOf("open" to false), ip = "9.9.9.1").status)
        // every switch is logged
        assertEquals(4L, row("SELECT count(*) AS n FROM listing_switch_log WHERE kind = 'pharmacy' AND listing_id = 'phS' AND field = 'open'")["n"])
        assertEquals("phS", row("SELECT by_uid FROM listing_switch_log WHERE listing_id = 'phS' ORDER BY id LIMIT 1")["by_uid"])
    }

    @Test
    fun `delivery switch - list and filter follow, approved snapshot follows, suspended refused, daily limit`() {
        setDhaka("2026-10-20 10:00")
        person("viewer")
        shop("phD", mapOf("delivery" to false))
        assertEquals(400, call("phD", "pharmacy_delivery_set", emptyMap()).status)
        val r = ok("phD", "pharmacy_delivery_set", mapOf("delivery" to true))
        assertTrue(r["delivery"].booleanValue())
        assertTrue(card("phD")["delivery"].booleanValue())
        assertEquals(listOf("phD"), ids(ok("viewer", "pharmacies", mapOf("district" to MYMENSINGH, "delivery" to true))))
        // no new review: still approved, the approved snapshot has it
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = 'phD'")["status"])
        assertEquals("true", row("SELECT last_approved->>'delivery' AS d FROM pharmacies WHERE uid = 'phD'")["d"])
        ok("phD", "pharmacy_delivery_set", mapOf("delivery" to false))
        assertFalse(card("phD")["delivery"].booleanValue())

        jdbc.update(
            """
            INSERT INTO user_quotas (uid, kind, day, count) VALUES ('phD', 'quickSwitch', (now() AT TIME ZONE 'Asia/Dhaka')::date, 200)
            ON CONFLICT (uid, kind) DO UPDATE SET count = 200, day = EXCLUDED.day
            """.trimIndent(),
        )
        assertEquals(429, call("phD", "pharmacy_delivery_set", mapOf("delivery" to true)).status)
        jdbc.update("DELETE FROM user_quotas WHERE uid = 'phD'")
        jdbc.update("UPDATE pharmacies SET status = 'suspended' WHERE uid = 'phD'")
        assertEquals(403, call("phD", "pharmacy_delivery_set", mapOf("delivery" to true)).status)
        assertEquals(403, call("phD", "pharmacy_open_override", mapOf("open" to true)).status)
    }
}
