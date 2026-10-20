package com.lifolo.unit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.Catalog
import com.lifolo.appointments.Appt
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.Ch
import com.lifolo.appointments.HomeLoc
import com.lifolo.appointments.HomePlace
import com.lifolo.appointments.P2Js
import com.lifolo.appointments.Place
import com.lifolo.appointments.ProviderDoc
import com.lifolo.appointments.QueueRules
import com.lifolo.appointments.Slot
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.content.HospitalMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * P2 pure rules, ported from server/test/rules.test.js (chambers, home care, hospital names, phones),
 * doctor-filters.test.js (fee, now open, today/tomorrow) and queue.test.js (average, order, ETA).
 */
class P2RulesTest {
    private val mapper = ObjectMapper()

    private fun j(v: Any?): JsonNode = mapper.valueToTree(v)

    /** no catalog saved → the built-in lists (care.js fallbacks) */
    private val catalog = object : Catalog {
        override fun lists(): Map<String, Map<String, List<Map<String, Any?>>>> = emptyMap()
        override fun updatedAt(): Long = 0
        override fun save(kind: String, type: String, items: List<Map<String, Any?>>) {}
    }

    private val h1 = mapOf("h1" to Place("h1", "Trishal UHC", "ত্রিশাল", "ময়মনসিংহ", 10, null))

    private fun chambers(list: List<Map<String, Any?>>, hospitals: Map<String, Place> = h1, custom: Boolean = false) =
        CareRules.parseChambers(j(list), hospitals, custom)

    /** setDhaka("2026-10-19 12:00") */
    private fun dhaka(s: String): Long = LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()

    private fun utc(y: Int, m: Int, d: Int): Long = LocalDateTime.of(y, m, d, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `chambers - checks, legacy documents, filters (rules)`() {
        assertNotNull(chambers(emptyList()).error)
        assertNotNull(chambers(listOf(mapOf("hospitalId" to "x", "days" to listOf(1), "start" to "09:00", "end" to "10:00"))).error)
        val t = mapOf("days" to listOf(6, 0), "start" to "17:00", "end" to "21:00")
        assertNotNull(chambers(listOf(mapOf("hospitalId" to "h1", "days" to emptyList<Int>(), "start" to "17:00", "end" to "21:00"))).error)
        assertNotNull(chambers(listOf(mapOf("hospitalId" to "h1", "days" to listOf(1), "start" to "17:00", "end" to "17:00"))).error)
        // until midnight
        assertNull(chambers(listOf(mapOf("hospitalId" to "h1", "days" to listOf(1), "start" to "20:00", "end" to "00:00"))).error)
        assertEquals("Sat, Sun · 17:00–21:00 · x", CareRules.scheduleText(Ch(days = listOf(0, 6), start = "17:00", end = "21:00", schedule = "x")))
        // different times per weekday in one chamber
        val multi = chambers(
            listOf(mapOf("hospitalId" to "h1", "times" to listOf(mapOf("days" to listOf(6, 1), "start" to "17:00", "end" to "21:00"), mapOf("days" to listOf(5), "start" to "09:00", "end" to "12:00")))),
        )
        assertNull(multi.error)
        val m0 = multi.value!![0]
        assertEquals(listOf(1, 5, 6), m0.days)
        assertEquals("17:00", m0.start)
        assertEquals(2, m0.times!!.size)
        assertEquals("Sat, Mon · 17:00–21:00; Fri · 09:00–12:00", CareRules.scheduleText(m0))
        val fri = utc(2026, 10, 16)
        assertTrue(CareRules.chamberOpenOn(m0, fri))
        assertEquals("09:00", CareRules.chamberTimesOn(m0, fri)[0].start)
        assertFalse(CareRules.chamberOpenOn(m0, utc(2026, 10, 18)))
        fun times(vararg s: Map<String, Any?>) = chambers(listOf(mapOf("hospitalId" to "h1", "times" to s.toList())))
        fun slot(days: List<Int>, start: String, end: String) = mapOf("days" to days, "start" to start, "end" to end)
        // morning and evening on one day are fine, the same time twice is not
        assertNull(times(slot(listOf(1), "09:00", "12:00"), slot(listOf(1), "17:00", "21:00")).error)
        assertNotNull(times(slot(listOf(1), "09:00", "12:00"), slot(listOf(1, 2), "09:00", "12:00")).error)
        assertNotNull(times(slot(listOf(1), "09:00", "12:00"), slot(listOf(1), "11:00", "13:00")).error)
        assertNull(times(slot(listOf(1), "09:00", "12:00"), slot(listOf(1), "12:00", "13:00")).error)
        // two chambers at the same time on the same day
        fun two(b: Map<String, Any?>) = chambers(listOf(mapOf("hospitalId" to "h1", "days" to listOf(1), "start" to "17:00", "end" to "21:00"), mapOf("hospitalId" to "h1") + b))
        assertNotNull(two(slot(listOf(1), "20:00", "22:00")).error)
        assertNull(two(slot(listOf(2), "17:00", "21:00")).error)
        assertNull(two(slot(listOf(1), "09:00", "12:00")).error)
        // past midnight: Mon 22:00–02:00 and Tue 01:00–03:00 overlap
        assertNotNull(
            chambers(listOf(mapOf("hospitalId" to "h1") + slot(listOf(1), "22:00", "02:00"), mapOf("hospitalId" to "h1") + slot(listOf(2), "01:00", "03:00"))).error,
        )
        assertNull(
            chambers(listOf(mapOf("hospitalId" to "h1") + slot(listOf(6), "22:00", "02:00"), mapOf("hospitalId" to "h1") + slot(listOf(0), "03:00", "05:00"))).error,
        )
        // the same chamber's overlap message names it once
        assertEquals(
            "\"ত্রিশাল\" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে",
            times(slot(listOf(1), "09:00", "12:00"), slot(listOf(1), "11:00", "13:00")).error!!.bn,
        )
        // home-visit days and times
        val hvBase = mapOf("enabled" to true, "services" to listOf("checkup"), "phone" to "01712345678")
        assertEquals(
            listOf(Slot(listOf(5), "09:00", "13:00")),
            CareRules.parseHomeVisit(j(hvBase + mapOf("times" to listOf(slot(listOf(5), "09:00", "13:00")))), "doctor", catalog).value!!.times,
        )
        assertNotNull(CareRules.parseHomeVisit(j(hvBase + mapOf("times" to emptyList<Any>())), "doctor", catalog).error)
        assertNull(CareRules.parseHomeVisit(j(hvBase), "doctor", catalog).error)
        assertNotNull(
            CareRules.parseHomeVisit(j(hvBase + mapOf("times" to listOf(slot(listOf(5), "09:00", "13:00"), slot(listOf(5), "12:00", "14:00")))), "doctor", catalog).error,
        )
        assertNotNull(times(slot(listOf(1), "09:00", "12:00"), slot(emptyList(), "17:00", "21:00")).error)
        assertNotNull(chambers(List(4) { mapOf("hospitalId" to "h1") + t }).error)
        val ok = chambers(listOf(mapOf("hospitalId" to "h1", "schedule" to "Fridays closed", "appointments" to true, "serialPhone" to "017-1234 5678") + t))
        val c0 = ok.value!![0]
        assertEquals(listOf(0, 6), c0.days)
        assertTrue(CareRules.chamberOpenOn(c0, utc(2026, 10, 17)))
        assertFalse(CareRules.chamberOpenOn(c0, utc(2026, 10, 19)))
        assertTrue(CareRules.chamberOpenOn(Ch(schedule = "old"), utc(2026, 10, 19)))
        assertEquals("ময়মনসিংহ", c0.district)
        assertEquals("01712345678", c0.serialPhone)

        val doc = ProviderDoc(specialties = listOf("medicine"), chambers = ok.value)
        val f = com.lifolo.appointments.ListFilter()
        assertTrue(CareRules.matchesFilter(doc, f.copy(district = "ময়মনসিংহ", upazilaId = 10), 0))
        assertTrue(CareRules.matchesFilter(doc, f.copy(hospitalId = "h1", specialty = "medicine"), 0))
        assertFalse(CareRules.matchesFilter(doc, f.copy(specialty = "eye"), 0))
        assertFalse(CareRules.matchesFilter(doc, f.copy(district = "ঢাকা"), 0))
        assertFalse(CareRules.matchesFilter(doc, f.copy(upazilaId = 11), 0))
        assertTrue(CareRules.matchesFilter(doc, f, 0))
    }

    @Test
    fun `own place of a nutritionist or trainer - name, district, address (rules)`() {
        val t = mapOf("days" to listOf(1), "start" to "09:00", "end" to "11:00")
        val place = mapOf("placeName" to "Power Gym", "district" to "ঢাকা", "address" to "House 1, Road 2, Dhanmondi") + t
        val ok = chambers(listOf(place), emptyMap(), true)
        // a doctor cannot type a place — from the hospital list
        assertNotNull(chambers(listOf(place), emptyMap()).error)
        assertNull(ok.error)
        assertEquals(true, ok.value!![0].custom)
        assertNull(ok.value[0].hospitalId)
        assertNotNull(chambers(listOf(place + mapOf("placeName" to "Gym", "district" to "নেই")), emptyMap(), true).error)
        assertNotNull(chambers(listOf(place + mapOf("address" to "")), emptyMap(), true).error)
    }

    @Test
    fun `home care - settings, phone, area matching (rules)`() {
        assertNotNull(CareRules.parseHomeVisit(j(mapOf("enabled" to true, "services" to emptyList<String>(), "phone" to "01712345678")), "nurse", catalog).error)
        // patients call this number: needed when on
        assertNotNull(CareRules.parseHomeVisit(j(mapOf("enabled" to true, "services" to listOf("dressing"))), "nurse", catalog).error)
        assertNotNull(CareRules.parseHomeVisit(j(mapOf("enabled" to true, "services" to listOf("dressing"), "phone" to "12345")), "nurse", catalog).error)
        val h = CareRules.parseHomeVisit(
            j(mapOf("enabled" to true, "services" to listOf("injection", "bogus", "dressing"), "radiusKm" to 7, "upazilaIds" to listOf(1, 1, "x"), "phone" to "+8801712-345678")),
            "nurse", catalog,
        ).value!!
        assertEquals(listOf("injection", "dressing"), h.services)
        assertEquals(5, h.radiusKm)
        assertEquals(listOf(1), h.upazilaIds)
        assertEquals("01712345678", h.phone)
        // doctors have their own services
        val d = CareRules.parseHomeVisit(j(mapOf("enabled" to true, "services" to listOf("checkup", "injection", "bedridden"), "radiusKm" to 20, "phone" to "01812345678")), "doctor", catalog).value!!
        assertEquals(listOf("checkup", "bedridden"), d.services)
        assertEquals(20, d.radiusKm)
        val off = CareRules.parseHomeVisit(j(mapOf("enabled" to false, "services" to listOf("dressing"), "radiusKm" to 10)), "nurse", catalog).value!!
        assertFalse(off.enabled)
        assertEquals(listOf("dressing"), off.services)

        val p = ProviderDoc(homeVisit = h)
        val loc = HomeLoc(23.81, 90.41, "gps", "ঢাকা")
        // from the searcher's GPS: within the radius, with the distance
        val near = CareRules.homeMatch(p, loc, HomePlace(23.83, 90.42))
        assertTrue(near != null && near.km!! > 2 && near.km < 3)
        assertNull(CareRules.homeMatch(p, loc, HomePlace(24.5, 90.4)))
        assertNotNull(CareRules.homeMatch(p, loc, HomePlace(24.5, 90.4, upazilaId = 1)))
        // a district-only location gives no distance
        assertNull(CareRules.homeMatch(p, loc.copy(locationSource = "district"), HomePlace(23.83, 90.42)))
        // searching by area: the upazila matches, or GPS in the same district
        assertNotNull(CareRules.homeMatch(p, loc, HomePlace(district = "কুমিল্লা", upazilaId = 1)))
        assertNotNull(CareRules.homeMatch(p, loc, HomePlace(district = "কুমিল্লা")))
        assertNotNull(CareRules.homeMatch(p, loc, HomePlace(district = "ঢাকা")))
        assertNull(CareRules.homeMatch(p.copy(homeVisit = h.copy(upazilaIds = emptyList())), loc, HomePlace(district = "কুমিল্লা")))
        assertNull(CareRules.homeMatch(p.copy(homeVisit = h.copy(enabled = false)), loc, HomePlace()))
        assertNull(CareRules.homeMatch(p.copy(homeVisitBlocked = true), loc, HomePlace()))
    }

    @Test
    fun `hospital names and duplicates (rules)`() {
        assertEquals("mymensingh medical college", HospitalMatch.normalizeName("Mymensingh Medical College Hospital"))
        assertEquals("popular diagnostic", HospitalMatch.normalizeName("Popular Diagnostic Centre Ltd."))
        fun dup(name: String, district: String, up: Int?) = HospitalMatch.isDuplicate("Popular Diagnostic Center Ltd", "ময়মনসিংহ", 10, name, district, up)
        assertTrue(dup("Popular Diagnostic Centre", "ময়মনসিংহ", 10))
        assertTrue(dup("Populer Diagnostic", "ময়মনসিংহ", null))
        assertFalse(dup("Popular Diagnostic", "ঢাকা", 10))
        assertFalse(dup("Popular Diagnostic", "ময়মনসিংহ", 11))
        assertFalse(dup("Labaid Hospital", "ময়মনসিংহ", 10))
    }

    @Test
    fun `phone numbers (rules)`() {
        assertEquals("8801712345678", Phones.normalizeBd("01712-345678"))
        assertEquals("8801912345678", Phones.normalizeBd("+8801912345678"))
        assertEquals("8801712345678", Phones.normalizeBd("1712345678"))
        assertNull(Phones.normalizeBd("01212345678"))
        assertNull(Phones.normalizeBd("123"))
    }

    @Test
    fun `bnNumbers - standalone numbers in Bangla digits (e2efixes)`() {
        assertEquals("ডা. হাসান · ১৯/১০/২০২৬ · #৫", Lang.bnNumbers("ডা. হাসান · 19/10/2026 · #5"))
        assertEquals("HbA1c ৭.৯ · B12 · Napa ৫০০ mg", Lang.bnNumbers("HbA1c 7.9 · B12 · Napa 500 mg"))
    }

    @Test
    fun `doctor filters - fee, now open past midnight, today ends, tomorrow (doctor-filters)`() {
        assertEquals(1, CareRules.weekday(com.lifolo.common.DhakaTime.todayUtcMidnight(dhaka("2026-10-19 12:00"))), "19 Oct 2026 is a Monday")
        assertEquals(500.0, CareRules.feeAmount(Ch(fee = "৫০০ টাকা")))
        assertEquals(1200.0, CareRules.feeAmount(Ch(fee = "৳1,200")))
        assertEquals(300.0, CareRules.feeAmount(Ch(fee = "৩০০–৫০০ টাকা (সেবা অনুযায়ী)")))
        assertEquals(0.0, CareRules.feeAmount(Ch(fee = "ফি পরে জানাব", noFee = true)))
        assertNull(CareRules.feeAmount(Ch(fee = "আলোচনা সাপেক্ষে")))

        val night = Ch(days = listOf(6), start = "20:00", end = "02:00")
        var t = dhaka("2026-10-17 23:00")
        assertTrue(CareRules.chamberNowOpen(night, t) && CareRules.chamberToday(night, t))
        t = dhaka("2026-10-18 01:30") // Sunday early — Saturday's session still running
        assertTrue(CareRules.chamberNowOpen(night, t))
        assertTrue(CareRules.chamberToday(night, t))
        assertFalse(CareRules.chamberTomorrow(night, t))
        t = dhaka("2026-10-18 02:00")
        assertFalse(CareRules.chamberNowOpen(night, t) || CareRules.chamberToday(night, t))

        val a = Ch(days = listOf(1, 3), start = "17:00", end = "21:00")
        t = dhaka("2026-10-19 10:00")
        assertTrue(CareRules.chamberToday(a, t) && !CareRules.chamberNowOpen(a, t))
        assertTrue(CareRules.chamberNowOpen(a, dhaka("2026-10-19 20:59")))
        t = dhaka("2026-10-19 21:00")
        assertFalse(CareRules.chamberToday(a, t))
        assertFalse(CareRules.chamberTomorrow(a, t))
        assertTrue(CareRules.chamberTomorrow(a, dhaka("2026-10-20 23:59")))

        // hospital name search: NFC, punctuation, spaces
        assertEquals("tuesday clinic", CareRules.nameNorm("  Tuesday   CLINIC "))
        assertEquals("a b", CareRules.nameNorm("A-(B)"))
    }

    @Test
    fun `queue - average, order, place, arrival estimate (queue)`() {
        val now = 1_000_000_000_000L
        var st = QueueRules.addGap(null, 8.0, now)
        assertEquals(8.0, st.avg)
        assertEquals(1, st.n)
        st = QueueRules.addGap(st, 12.0, now)
        assertEquals(10.0, st.avg)
        assertEquals(2, st.n)
        // 30 days old: starts again
        assertEquals(1, QueueRules.addGap(QueueRules.Stats(30.0, 50, now - 31L * 86400000), 6.0, now).n)
        // not enough history: the chamber's setting, else 10
        assertEquals(QueueRules.Avg(8, "history"), QueueRules.avgFor(mapOf("k" to QueueRules.Stats(7.6, 5, now)), "k", Ch(patientMin = 12), now))
        assertEquals(QueueRules.Avg(12, "setting"), QueueRules.avgFor(mapOf("k" to QueueRules.Stats(7.6, 2, now)), "k", Ch(patientMin = 12), now))
        assertEquals(QueueRules.Avg(10, "default"), QueueRules.avgFor(emptyMap(), "k", Ch(), now))
        // phone-booked numbers are not skipped; cancelled and "come later" are
        fun ap(serial: Int, status: String, late: String? = null) = Appt(id = "a$serial", patientUid = "p", providerUid = "d", serial = serial, status = status, lateStatus = late)
        val nums = QueueRules.numbers(listOf(ap(1, "confirmed"), ap(3, "cancelled"), ap(4, "confirmed", "later"), ap(6, "confirmed")), 6)
        assertEquals(listOf(1, 2, 5, 6, 4), QueueRules.order(QueueRules.Q(), nums))
        val q2 = QueueRules.Q(started = true, current = 2, pos = 2, done = listOf(1))
        assertEquals(QueueRules.QPlace("waiting", 2), QueueRules.placeOf(q2, nums, 6))
        assertEquals(QueueRules.QPlace("waiting", 3), QueueRules.placeOf(q2, nums, 4))
        // now 5, yours 9 → 4 ahead; average 8 → end of the current one + 3 × 8
        val q = QueueRules.Q(started = true, current = 5, lastAt = now)
        val e = QueueRules.etaFor(q, QueueRules.QPlace("waiting", 4), 8, null, now)
        val step = 5 * 60_000L
        assertEquals(Math.ceilDiv(now + 32 * 60_000L, step) * step, e.eta)
        assertEquals(Math.floorDiv(e.eta!! - 20 * 60_000L, step) * step, e.arriveBy)
        assertEquals("c4ca4238a0", QueueRules.keyOf("1"))
        assertEquals("d41d8cd98f", QueueRules.keyOf(""))
        assertEquals(20.0, P2Js.number(j("20")))
        assertTrue(P2Js.number(j(mapOf("a" to 1))).isNaN())
    }
}
