package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md provider side: "আজ সিরিয়াল চলছে / আজ বন্ধ" per chamber — doctor and assistant, nextSlot, openNow, book, card */
class ChamberDaysIT : P2TestBase() {
    private val doc = "cdDoc"
    private val asst = "cdAsst"
    private val pt = "cdPt"
    private val day = 24L * 3600 * 1000

    private fun seed() {
        val gzp = district("Gazipur")
        person(pt)
        person("cdOther")
        person(asst, "সহকারী", "cdasst@q.test")
        person(doc, "ডা. বন্ধ")
        badge(doc, "doctor", "ডা. বন্ধ")
        provider(
            doc,
            listOf(
                Ch(hospitalName = "Every Day Clinic", district = gzp, upazilaId = 320, days = listOf(0, 1, 2, 3, 4, 5, 6), start = "17:00", end = "21:00", fee = "৫০০ টাকা", appointments = true),
                Ch(hospitalName = "Monday Clinic", district = gzp, upazilaId = 320, days = listOf(1), start = "09:00", end = "22:00", fee = "৩০০ টাকা", appointments = true),
            ),
            name = "ডা. বন্ধ",
            assistants = listOf("cdasst@q.test"),
        )
    }

    private fun card(): JsonNode =
        ok(pt, "providers", mapOf("type" to "doctor", "district" to district("Gazipur"), "homeVisit" to false))["items"].first { it["uid"].textValue() == doc }

    @Test
    fun `doctor and assistant close a chamber day - nextSlot, openNow and book follow, reopen, permissions, log`() {
        clock.fixedMs = dhaka("2026-10-19 18:00") // Monday
        seed()
        val today = today()
        assertEquals(today, card()["nextSlot"]["date"].longValue())
        assertTrue(card()["openNow"].booleanValue())

        // the doctor's own view: chambers with today's state + the public card
        val mine = ok(doc, "chamber_days")
        assertEquals("doctor", mine["role"].textValue())
        assertTrue(mine["items"][0]["sitsToday"].booleanValue())
        assertFalse(mine["items"][0]["closedToday"].booleanValue())
        assertEquals(today, mine["card"]["nextSlot"]["date"].longValue())
        assertEquals(today, ok(doc, "provider_me")["card"]["nextSlot"]["date"].longValue())

        // the assistant closes both of today's chambers
        val r = ok(asst, "chamber_day_set", mapOf("doctorUid" to doc, "chamberIndex" to 0, "closed" to true))
        assertTrue(r["closed"].booleanValue())
        assertEquals(today, r["date"].longValue())
        assertEquals(0, r["requests"].intValue())
        ok(asst, "chamber_day_set", mapOf("doctorUid" to doc, "chamberKey" to "Monday Clinic", "closed" to true))
        val c = card()
        assertEquals(today + day, c["nextSlot"]["date"].longValue(), "today closed → tomorrow")
        assertEquals("কাল", c["nextSlot"]["label"].textValue())
        assertFalse(c["openNow"].booleanValue())
        val list = ok(asst, "chamber_days", mapOf("doctorUid" to doc))
        assertEquals("assistant", list["role"].textValue())
        assertTrue(list["items"][0]["closedToday"].booleanValue())
        assertEquals(listOf(today), list["items"][1]["closedDates"].map { it.longValue() })
        // booking a closed day is refused like a day the chamber does not sit
        val book = call(pt, "book", mapOf("providerUid" to doc, "date" to today, "chamberIndex" to 0, "note" to "জ্বর"))
        assertEquals(400, book.status)
        assertEquals("সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।", book.error)
        assertEquals(200, call(pt, "book", mapOf("providerUid" to doc, "date" to today + day, "chamberIndex" to 0, "note" to "জ্বর")).status)

        // a later day: tomorrow's request is counted for the doctor
        assertEquals(1, ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "date" to today + day, "closed" to true))["requests"].intValue())
        ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "date" to today + day, "closed" to false))

        // reopen today
        ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to false))
        assertEquals(today, card()["nextSlot"]["date"].longValue())
        assertTrue(card()["openNow"].booleanValue())

        // a day the chamber does not sit, out of range, unknown chamber, a stranger, bad input
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 1, "date" to today + day, "closed" to true)).status)
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "date" to today + 61 * day, "closed" to true)).status)
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "date" to today - day, "closed" to true)).status)
        assertEquals(404, call(doc, "chamber_day_set", mapOf("chamberIndex" to 5, "closed" to true)).status)
        assertEquals(409, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "chamberKey" to "Monday Clinic", "closed" to true)).status)
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0)).status)
        assertEquals(403, call("cdOther", "chamber_day_set", mapOf("doctorUid" to doc, "chamberIndex" to 0, "closed" to true)).status)
        assertEquals(403, call("cdOther", "chamber_days", mapOf("doctorUid" to doc)).status)

        // the log: who did it, as what
        val roles = jdbc.queryForList("SELECT role FROM listing_switch_log WHERE kind = 'chamber' AND owner_uid = ? ORDER BY id", String::class.java, doc)
        assertEquals(listOf("assistant", "assistant", "owner", "owner", "owner"), roles)
    }
}
