package com.lifolo.unit

import com.fasterxml.jackson.databind.json.JsonMapper
import com.lifolo.appointments.Ch
import com.lifolo.appointments.NextSlots
import com.lifolo.appointments.ProviderDoc
import com.lifolo.common.Body
import com.lifolo.common.DhakaTime
import com.lifolo.common.Places
import com.lifolo.listings.ListingFacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** LISTINGS.md pure rules: free-text hours, origin/distance, ratingSummary, q, sort, nextSlot (capacity, labels) */
class ListingFactsTest {
    private val mapper = JsonMapper()

    private fun body(m: Map<String, Any?>) = Body.of(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(m))

    private fun dhaka(s: String): Long = LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()

    @Test
    fun `free-text lab hours - Bangla periods, 24h clock, am-pm, 24 hours, unknown`() {
        fun h(t: String) = ListingFacts.parseHours(t)?.let { if (it.open24) "24h" else "${it.openAt}-${it.closeAt}" }
        assertEquals("08:00-22:00", h("সকাল ৮টা – রাত ১০টা"))
        assertEquals("08:00-22:00", h("সকাল ৮টা – রাত ১০টা (শুক্রবার বিকাল ৩টা থেকে)"))
        assertEquals("07:00-21:00", h("সকাল ৭টা – রাত ৯টা"))
        assertEquals("09:00-19:00", h("সকাল ৯টা – সন্ধ্যা ৭টা"))
        assertEquals("09:30-17:00", h("সকাল ৯:৩০ - বিকেল ৫টা"))
        assertEquals("08:00-00:00", h("সকাল ৮টা - রাত ১২টা"))
        assertEquals("08:00-22:00", h("08:00-22:00"))
        assertEquals("08:30-21:00", h("০৮:৩০ – ২১:০০"))
        assertEquals("08:00-22:00", h("8am - 10pm"))
        assertEquals("24h", h("২৪ ঘণ্টা খোলা"))
        assertEquals("24h", h("24 hours"))
        assertEquals("24h", h("24/7"))
        assertNull(h("সব সময়"))
        assertNull(h(""))
        assertNull(h(" "))
        assertNull(h("সকাল ৮টা"))
        // understood → openNow follows the hours; not understood → null, never a guess
        val v = LinkedHashMap<String, Any?>()
        ListingFacts.putTextHours(v, "সকাল ৮টা – রাত ১০টা", dhaka("2026-10-20 23:00"))
        assertEquals(false, v["openNow"])
        assertEquals("08:00", v["opensAt"])
        assertEquals("22:00", v["closesAt"])
        assertEquals(false, v["is24h"])
        ListingFacts.putTextHours(v, "সকাল ৮টা – রাত ১০টা", dhaka("2026-10-20 09:00"))
        assertEquals(true, v["openNow"])
        ListingFacts.putTextHours(v, "ফোন করে জেনে নিন", dhaka("2026-10-20 09:00"))
        assertNull(v["openNow"])
        assertNull(v["opensAt"])
    }

    @Test
    fun `origin - gps point, upazila centre, nothing; distance 1 decimal and approx flag`() {
        val gps = ListingFacts.origin(body(mapOf("lat" to 24.75, "lng" to 90.4)))!!
        assertTrue(gps.exact)
        assertFalse(ListingFacts.origin(body(mapOf("lat" to 24.75, "lng" to 90.4, "gps" to false)))!!.exact)
        // the ambulance rule: exact only with gps: true
        assertFalse(ListingFacts.origin(body(mapOf("lat" to 24.75, "lng" to 90.4)), gpsFlag = true)!!.exact)
        assertTrue(ListingFacts.origin(body(mapOf("lat" to 24.75, "lng" to 90.4, "gps" to true)), gpsFlag = true)!!.exact)
        val c = Places.UPAZILA_CENTER.getValue(466)
        val up = ListingFacts.origin(body(mapOf("upazilaId" to 466)))!!
        assertEquals(c.first, up.lat)
        assertFalse(up.exact)
        assertNull(ListingFacts.origin(body(mapOf("district" to "x"))))
        // outside Bangladesh → the upazila (or nothing)
        assertNull(ListingFacts.origin(body(mapOf("lat" to 51.5, "lng" to 0.1))))

        val v = LinkedHashMap<String, Any?>()
        ListingFacts.putDistance(v, gps, 24.75, 90.41, true)
        assertEquals(1.0, v["distanceKm"])
        assertEquals(false, v["distanceApprox"])
        ListingFacts.putDistance(v, gps, 24.75, 90.41, false)
        assertEquals(true, v["distanceApprox"])
        ListingFacts.putDistance(v, null, 24.75, 90.41, true)
        assertNull(v["distanceKm"])
        ListingFacts.putDistance(v, gps, null, null, true)
        assertNull(v["distanceKm"])
    }

    @Test
    fun `ratingSummary, q and sort`() {
        assertNull(ListingFacts.ratingSummary(0, 0))
        assertEquals(mapOf("avg" to 4.6, "count" to 31), ListingFacts.ratingSummary(142, 31))
        assertNull(ListingFacts.query(body(mapOf("q" to "  "))))
        assertEquals("জীবন ফার্মেসি", ListingFacts.query(body(mapOf("q" to " জীবন   ফার্মেসি "))))
        assertTrue(ListingFacts.matches("life চর", listOf("Life Pharmacy", "চরপাড়া")))
        assertFalse(ListingFacts.matches("life x", listOf("Life Pharmacy", "চরপাড়া")))
        assertTrue(ListingFacts.matches(null, emptyList()))
        assertNull(ListingFacts.sort(body(mapOf("sort" to "price"))))

        val a = mapOf("id" to "a", "distanceKm" to 3.0, "openNow" to false, "ratingSummary" to mapOf("avg" to 4.0, "count" to 2))
        val b = mapOf("id" to "b", "distanceKm" to null, "openNow" to true, "ratingSummary" to null)
        val c = mapOf("id" to "c", "distanceKm" to 1.0, "openNow" to null, "ratingSummary" to mapOf("avg" to 4.0, "count" to 9))
        fun ids(sort: String?) = ListingFacts.sortedMaps(listOf(a, b, c), sort).map { it["id"] }
        assertEquals(listOf("a", "b", "c"), ids(null))
        assertEquals(listOf("c", "a", "b"), ids("near"))
        assertEquals(listOf("c", "a", "b"), ids("rating"))
        assertEquals(listOf("b", "c", "a"), ids("open"))
        // ambulances: available first
        val x = mapOf("id" to "x", "openNow" to null, "available" to false)
        val y = mapOf("id" to "y", "openNow" to null, "available" to true)
        assertEquals(listOf("y", "x"), ListingFacts.sortedMaps(listOf(x, y), "open").map { it["id"] })
    }

    private fun ch(days: List<Int>, start: String, end: String, patientMin: Int? = null, appointments: Boolean = true, name: String = "H") =
        Ch(hospitalName = name, district = "d", days = days, start = start, end = end, fee = "৫০০ টাকা", appointments = appointments, patientMin = patientMin)

    @Test
    fun `nextSlot - today while a session runs, else the next day it sits, capacity full, labels`() {
        val mon18 = dhaka("2026-10-19 18:00") // Monday
        val today = DhakaTime.todayUtcMidnight(mon18)
        val day = NextSlots.DAY_MS
        val all = { _: Ch -> true }
        val none = { _: String, _: Long -> 0 }
        fun doc(vararg c: Ch, appointments: Boolean = true) = ProviderDoc(uid = "d", appointments = appointments, chambers = c.toList())

        // Mon 17–21 → today
        assertEquals(NextSlots.Slot(today, 0, true), NextSlots.nextSlot(doc(ch(listOf(1), "17:00", "21:00")), mon18, all, none))
        // Mon 09–12 (over) + Tue → tomorrow, chamber 1
        assertEquals(
            NextSlots.Slot(today + day, 1, true),
            NextSlots.nextSlot(doc(ch(listOf(1), "09:00", "12:00", name = "A"), ch(listOf(2), "18:00", "20:00", name = "B")), mon18, all, none),
        )
        // the evening session past midnight is still today
        assertEquals(today, NextSlots.nextSlot(doc(ch(listOf(1), "16:00", "01:00")), mon18, all, none)!!.date)
        // Saturday only → 5 days later
        assertEquals(today + 5 * day, NextSlots.nextSlot(doc(ch(listOf(6), "10:00", "12:00")), mon18, all, none)!!.date)
        // no appointments, chamber not taking them, hidden (online off) → null
        assertNull(NextSlots.nextSlot(doc(ch(listOf(1), "17:00", "21:00"), appointments = false), mon18, all, none))
        assertNull(NextSlots.nextSlot(doc(ch(listOf(1), "17:00", "21:00", appointments = false)), mon18, all, none))
        assertNull(NextSlots.nextSlot(doc(ch(listOf(1), "17:00", "21:00")), mon18, { false }, none))

        // capacity: 17–21 = 240 min / 60 = 4 serials; today full → tomorrow (every day chamber)
        val every = ch(listOf(0, 1, 2, 3, 4, 5, 6), "17:00", "21:00", patientMin = 60)
        assertEquals(4, NextSlots.capacity(every, today))
        assertEquals(6, NextSlots.capacity(ch(listOf(1), "20:00", "02:00", patientMin = 60), today))
        assertNull(NextSlots.capacity(ch(listOf(1), "17:00", "21:00"), today))
        val fullToday = { _: String, d: Long -> if (d == today) 4 else 0 }
        assertEquals(NextSlots.Slot(today + day, 0, true), NextSlots.nextSlot(doc(every), mon18, all, fullToday))
        // every day full → the first day, not available
        assertEquals(NextSlots.Slot(today, 0, false), NextSlots.nextSlot(doc(every), mon18, all) { _, _ -> 9 })

        assertEquals("আজ", NextSlots.label(today, today, "bn"))
        assertEquals("কাল", NextSlots.label(today + day, today, "bn"))
        assertEquals("Tomorrow", NextSlots.label(today + day, today, "en"))
        assertEquals("২১ অক্টোবর", NextSlots.label(today + 2 * day, today, "bn"))
        assertEquals("21 Oct", NextSlots.label(today + 2 * day, today, "en"))
    }
}
