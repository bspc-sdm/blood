package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Sql
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LISTINGS.md on the doctor list: nextSlot (today, tomorrow, full by capacity), feeAmount, specialtyLabel, openNow, distance, q, sort */
class DoctorListingFactsIT : P2TestBase() {
    private val viewer = "lfViewer"

    private fun ch(gzp: String, name: String, days: List<Int>, start: String, end: String, fee: String = "", noFee: Boolean = false, patientMin: Int? = null, upazilaId: Int = 320) =
        Ch(hospitalName = name, district = gzp, upazilaId = upazilaId, days = days, start = start, end = end, fee = fee, noFee = noFee, appointments = true, patientMin = patientMin)

    private fun list(extra: Map<String, Any?> = emptyMap(), lang: String = "bn"): JsonNode =
        ok(viewer, "providers", mapOf("type" to "doctor", "district" to district("Gazipur"), "homeVisit" to false) + extra, lang)

    private fun item(r: JsonNode, uid: String) = r["items"].first { it["uid"].textValue() == uid }

    private fun seed() {
        val gzp = district("Gazipur")
        person(viewer)
        val docs = listOf(
            // Mon 17–21, 500 taka, 60 min per patient → 4 serials
            Triple("lfA", "ডা. আলম", listOf(ch(gzp, "Tongi Hospital", listOf(1, 2), "17:00", "21:00", "৫০০ টাকা", patientMin = 60))),
            // every day 09–12 (over at 18:00) → tomorrow
            Triple("lfB", "ডা. বিথি", listOf(ch(gzp, "Heart Care", listOf(0, 1, 2, 3, 4, 5, 6), "09:00", "12:00", "৳1,200", upazilaId = 318))),
            // Saturday only, no fee
            Triple("lfC", "ডা. চন্দন", listOf(ch(gzp, "Child Clinic", listOf(6), "20:00", "22:00", noFee = true))),
        )
        for ((uid, name, chambers) in docs) {
            person(uid, name)
            badge(uid, "doctor", name)
            provider(uid, chambers, name = name, specialties = if (uid == "lfB") listOf("cardiology", "medicine") else listOf("medicine"))
        }
        jdbc.update("UPDATE providers SET rating_sum = 9, rating_count = 2 WHERE uid = 'lfB'")
        jdbc.update("UPDATE providers SET rating_sum = 50, rating_count = 10 WHERE uid = 'lfC'")
    }

    @Test
    fun `nextSlot today, tomorrow, Saturday; fee; labels; openNow; rating; distance`() {
        clock.fixedMs = dhaka("2026-10-19 18:00") // Monday evening
        seed()
        val today = today()
        val r = list()
        val a = item(r, "lfA")
        assertEquals(today, a["nextSlot"]["date"].longValue())
        assertEquals("আজ", a["nextSlot"]["label"].textValue())
        assertTrue(a["nextSlot"]["available"].booleanValue())
        assertEquals(0, a["nextSlot"]["chamberIndex"].intValue())
        assertEquals(500, a["feeAmount"].intValue())
        assertTrue(a["openNow"].booleanValue())
        assertTrue(a["ratingSummary"].isNull)
        assertEquals("মেডিসিন", a["specialtyLabel"].textValue())
        assertTrue(a.has("systemLabel"))
        val b = item(r, "lfB")
        assertEquals(today + DAY_MS, b["nextSlot"]["date"].longValue())
        assertEquals("কাল", b["nextSlot"]["label"].textValue())
        assertEquals(1200, b["feeAmount"].intValue())
        assertFalse(b["openNow"].booleanValue())
        assertEquals(4.5, b["ratingSummary"]["avg"].doubleValue())
        assertEquals(2, b["specialtyLabel"].textValue().split(", ").size)
        val c = item(r, "lfC")
        assertEquals(today + 5 * DAY_MS, c["nextSlot"]["date"].longValue())
        assertEquals("২৪ অক্টোবর", c["nextSlot"]["label"].textValue())
        assertEquals(0, c["feeAmount"].intValue())
        assertEquals("Tomorrow", item(list(lang = "en"), "lfB")["nextSlot"]["label"].textValue())
        assertEquals("Medicine", item(list(lang = "en"), "lfA")["specialtyLabel"].textValue())
        // the old `fee` text stays
        assertEquals("৫০০ টাকা", a["fee"].textValue())
        // distance: to the chamber's upazila centre (approximate)
        val near = list(mapOf("upazilaId" to 320))
        assertEquals(0.0, item(near, "lfA")["distanceKm"].doubleValue())
        assertTrue(item(near, "lfA")["distanceApprox"].booleanValue())
        assertTrue(item(near, "lfB")["distanceKm"].doubleValue() > 0)
        assertTrue(item(list(), "lfA")["distanceKm"].isNull)

        // sorts
        fun ids(extra: Map<String, Any?>) = list(extra)["items"].map { it["uid"].textValue() }
        assertEquals(listOf("lfC", "lfB", "lfA"), ids(mapOf("sort" to "rating")))
        assertEquals(listOf("lfA", "lfB", "lfC"), ids(mapOf("sort" to "open")))
        assertEquals("lfB", ids(mapOf("sort" to "near", "upazilaId" to 320)).last())
        // q: name, specialty label, chamber
        assertEquals(listOf("lfB"), ids(mapOf("q" to "বিথি")))
        assertEquals(listOf("lfC"), ids(mapOf("q" to "child")))
        assertEquals(listOf("lfB"), ids(mapOf("q" to "heart")))
    }

    @Test
    fun `nextSlot - a full day by capacity moves to the next day`() {
        clock.fixedMs = dhaka("2026-10-19 18:00") // Monday
        seed()
        val today = today()
        for (i in 1..4) {
            person("pt$i")
            jdbc.update(
                """
                INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, chamber_key, visit_date, note, status, created_at)
                VALUES (?, ?, 'lfA', 'P', 'D', 'doctor', 'Tongi Hospital', 'Tongi Hospital', ?, 'x', ?, now())
                """.trimIndent(),
                "ap$i", "pt$i", Sql.date(com.lifolo.common.DhakaTime.fromUtcMidnightMs(today)), if (i % 2 == 0) "confirmed" else "pending",
            )
        }
        val a = item(list(), "lfA")
        assertEquals(today + DAY_MS, a["nextSlot"]["date"].longValue(), "Monday full (4 of 4) → Tuesday")
        assertEquals("কাল", a["nextSlot"]["label"].textValue())
        assertTrue(a["nextSlot"]["available"].booleanValue())
        // a cancelled one frees a place
        jdbc.update("UPDATE appointments SET status = 'cancelled' WHERE id = 'ap1'")
        assertEquals(today, item(list(), "lfA")["nextSlot"]["date"].longValue())
    }

    companion object {
        const val DAY_MS = 24L * 3600 * 1000
    }
}
