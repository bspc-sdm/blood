package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * server/test/doctor-filters.test.js (2.33.0): today / tomorrow / now in the chamber (Dhaka time, like booking and the
 * queue), specialty chips (facets), fee up to, hospital name, gender — all chamber conditions in one chamber; bad or
 * old values do not filter; the cached list never stores a filtered result; demo ↔ real lists are the same but
 * booking across worlds is refused.
 */
class DoctorFiltersIT : P2TestBase() {
    private val viewer = "realViewerF"
    private val a = "realDocA" // medicine, male — Mon/Wed 17–21, 500 taka, Tongi
    private val b = "realDocB" // cardiology, female — every day 09–12, ৳1,200
    private val c = "realDocC" // pediatrics, no gender — Sat 20:00–02:00 (past midnight), no fee
    private val d = "realDocD" // medicine + chest, male — Sun 10–13 (1000) at one hospital, Tue 18–20 (300) at another

    private fun ch(gzp: String, name: String, days: List<Int>, start: String, end: String, fee: String = "", noFee: Boolean = false, id: String? = null, bn: String = "", upazilaId: Int = 320) =
        Ch(hospitalId = id, hospitalName = name, hospitalNameBn = bn, district = gzp, upazilaId = upazilaId, days = days, start = start, end = end, fee = fee, noFee = noFee, appointments = true)

    private fun seedReal() {
        val gzp = district("Gazipur")
        person(viewer)
        val docs = listOf(
            Triple(a, "ডা. আসল আলম", listOf(ch(gzp, "Tongi General Hospital", listOf(1, 3), "17:00", "21:00", "৫০০ টাকা", id = "dghs_t1", bn = "টঙ্গী সাধারণ হাসপাতাল"))),
            Triple(b, "ডা. আসল বিথি", listOf(ch(gzp, "Heart Care Center", listOf(0, 1, 2, 3, 4, 5, 6), "09:00", "12:00", "৳1,200"))),
            Triple(c, "ডা. আসল চন্দন", listOf(ch(gzp, "Night Child Clinic", listOf(6), "20:00", "02:00", noFee = true))),
            Triple(
                d, "ডা. আসল দীপ",
                listOf(ch(gzp, "Sunday Hospital", listOf(0), "10:00", "13:00", "১০০০ টাকা", upazilaId = 318), ch(gzp, "Tuesday Clinic", listOf(2), "18:00", "20:00", "৩০০ টাকা")),
            ),
        )
        val meta = mapOf(a to (listOf("medicine") to "male"), b to (listOf("cardiology") to "female"), c to (listOf("pediatrics") to null), d to (listOf("medicine", "chest") to "male"))
        for ((uid, name, chambers) in docs) {
            person(uid, name)
            badge(uid, "doctor", name)
            provider(uid, chambers, name = name, specialties = meta[uid]!!.first, gender = meta[uid]!!.second)
        }
    }

    private fun list(extra: Map<String, Any?> = emptyMap(), uid: String = viewer): JsonNode =
        ok(uid, "providers", mapOf("type" to "doctor", "district" to district("Gazipur"), "homeVisit" to false) + extra)

    private fun ids(r: JsonNode): List<String> = r["items"].map { it["uid"].textValue() }.sorted()

    @Test
    fun `list - today, tomorrow, now in the chamber at several Dhaka times`() {
        clock.fixedMs = dhaka("2026-10-19 18:00") // Monday evening
        seedReal()
        assertEquals(listOf(a, b, c, d).sorted(), ids(list()))
        assertEquals(listOf(a), ids(list(mapOf("day" to "today"))), "B ends at 12, C on Saturday, D on Sun/Tue")
        assertEquals(listOf(a), ids(list(mapOf("openNow" to true))))
        assertEquals(listOf(b, d).sorted(), ids(list(mapOf("day" to "tomorrow"))))
        // which of D's chambers matched: the Tuesday one (index 1)
        assertEquals(listOf(1), list(mapOf("day" to "tomorrow"))["items"].first { it["uid"].textValue() == d }["matched"].map { it.intValue() })

        clock.fixedMs = dhaka("2026-10-19 10:00") // Monday morning
        assertEquals(listOf(a, b).sorted(), ids(list(mapOf("day" to "today"))))
        assertEquals(listOf(b), ids(list(mapOf("openNow" to true))))

        clock.fixedMs = dhaka("2026-10-18 01:00") // Sunday 1 am — Saturday night's chamber still running
        assertEquals(listOf(c), ids(list(mapOf("openNow" to true))))
        assertEquals(listOf(b, c, d).sorted(), ids(list(mapOf("day" to "today"))))

        clock.fixedMs = dhaka("2026-10-17 23:00") // Saturday night
        assertEquals(listOf(c), ids(list(mapOf("day" to "today"))))
        assertEquals(listOf(b, d).sorted(), ids(list(mapOf("day" to "tomorrow"))))
    }

    @Test
    fun `list - specialty, fee, hospital name, gender, all in one chamber, facets`() {
        clock.fixedMs = dhaka("2026-10-19 18:00")
        seedReal()
        val all = list()
        // only what the list has (most first), with counts; genders are written
        assertEquals(
            listOf("medicine 2", "cardiology 1", "chest 1", "pediatrics 1"),
            all["facets"]["specialties"].map { "${it["code"].textValue()} ${it["n"].intValue()}" },
        )
        assertEquals(true, all["facets"]["gender"].booleanValue())
        // filtering by specialty keeps the area's chips
        val med = list(mapOf("specialty" to "medicine"))
        assertEquals(listOf(a, d).sorted(), ids(med))
        assertEquals(4, med["facets"]["specialties"].size())

        assertEquals(listOf(c, d).sorted(), ids(list(mapOf("maxFee" to 300))), "no fee = 0; D's Tuesday 300")
        assertEquals(listOf(a, c, d).sorted(), ids(list(mapOf("maxFee" to 500))))
        assertEquals(listOf(a, c, d).sorted(), ids(list(mapOf("maxFee" to 1000))), "৳1,200 out")
        // one chamber: Monday evening "tomorrow" (Tue) + up to ৳1000 → D's Tuesday chamber; B (1200) out
        assertEquals(listOf(d), ids(list(mapOf("day" to "tomorrow", "maxFee" to 1000))))
        // Saturday noon "tomorrow" (Sun) + up to 300: D's Sunday chamber is 1000 — the other chamber's 300 does not count
        clock.fixedMs = dhaka("2026-10-17 12:00")
        assertEquals(emptyList<String>(), ids(list(mapOf("day" to "tomorrow", "maxFee" to 300))))
        val sun = list(mapOf("day" to "tomorrow", "maxFee" to 1000))
        assertEquals(listOf(d), ids(sun))
        assertEquals(listOf(0), sun["items"][0]["matched"].map { it.intValue() })

        assertEquals(listOf(a), ids(list(mapOf("hospitalQ" to "tongi"))))
        assertEquals(listOf(a), ids(list(mapOf("hospitalQ" to "টঙ্গী"))), "the Bangla name too")
        assertEquals(listOf(d), ids(list(mapOf("hospitalQ" to "  Tuesday   CLINIC "))))
        assertEquals(listOf(b), ids(list(mapOf("gender" to "female"))))
        assertEquals(listOf(a, d).sorted(), ids(list(mapOf("gender" to "male", "specialty" to "medicine"))))
        // area + the new filters: in Kaliakair (318) only D's Sunday chamber
        assertEquals(emptyList<String>(), ids(list(mapOf("upazilaId" to 318, "maxFee" to 300))))
        assertEquals(listOf(d), ids(list(mapOf("upazilaId" to 318, "maxFee" to 1000))))
    }

    @Test
    fun `bad or old values do not filter, the cache never keeps a filtered list, today follows the clock`() {
        clock.fixedMs = dhaka("2026-10-19 18:00")
        seedReal()
        val before = list()
        for (extra in listOf<Map<String, Any?>>(
            mapOf("day" to "yesterday"), mapOf("day" to true), mapOf("openNow" to "true"), mapOf("maxFee" to "500"), mapOf("maxFee" to -1),
            mapOf("maxFee" to 2.5), mapOf("gender" to "other"), mapOf("hospitalQ" to "x"), mapOf("hospitalQ" to 5),
        )) {
            assertEquals(4, list(extra)["items"].size(), extra.toString())
        }
        // old apps (no homeVisit field) — as before
        assertEquals(listOf(a, b, c, d).sorted(), ids(ok(viewer, "providers", mapOf("type" to "doctor", "district" to district("Gazipur")))))
        // after a filtered list the whole list again, the same
        list(mapOf("day" to "today", "maxFee" to 300, "specialty" to "medicine", "gender" to "male", "openNow" to true))
        assertEquals(before, list())
        // the clock moves on: "today" with the cached list at the new time
        clock.fixedMs = dhaka("2026-10-19 18:01")
        assertEquals(listOf(a), ids(list(mapOf("day" to "today"))))
        clock.fixedMs = dhaka("2026-10-19 21:00")
        assertEquals(emptyList<String>(), ids(list(mapOf("day" to "today"))))
        // guests see the list without names of their own
        assertEquals(4, ok(null, "providers", mapOf("type" to "doctor", "district" to district("Gazipur"), "homeVisit" to false))["items"].size())
        // nurses have no chamber list; unknown types are refused
        assertEquals(0, list(mapOf("type" to "nurse"))["items"].size())
        assertEquals("ডাক্তার বা নার্স বেছে নিন", call(viewer, "providers", mapOf("type" to "x")).error)
    }

    @Test
    fun `demo and real - the same list for everyone, but no booking across worlds`() {
        clock.fixedMs = dhaka("2026-10-19 11:00")
        seedReal()
        val gzp = district("Gazipur")
        val dd = "demo-doctor"
        person(dd, "ডা. ডেমো হাসান")
        badge(dd, "doctor", "ডা. ডেমো হাসান")
        provider(dd, listOf(ch(gzp, "Demo Hospital", listOf(0, 1, 2, 3, 4, 5, 6), "09:00", "23:00", "৩০০ টাকা", id = "demo-h1")), name = "ডা. ডেমো হাসান")
        val pt = "demo-patient"
        person(pt, "ডেমো রোগী রহিম")
        val real = "realPatientF"
        person(real, "আসল রোগী")
        val f = mapOf("day" to "today", "maxFee" to 500)
        val asDemo = list(f, pt)
        val asReal = list(f, real)
        val asGuest = ok(null, "providers", mapOf("type" to "doctor", "district" to gzp, "homeVisit" to false) + f)
        assertEquals(ids(asDemo), ids(asReal))
        assertEquals(ids(asDemo), ids(asGuest))
        assertTrue(ids(asReal).contains(dd) && ids(asReal).contains(a), "both worlds' doctors are in the list")
        val today = today()
        assertEquals(404, call(pt, "book", mapOf("providerUid" to a, "chamberIndex" to 0, "date" to today, "note" to "ডেমো থেকে আসলে")).status)
        assertEquals(404, call(real, "book", mapOf("providerUid" to dd, "chamberIndex" to 0, "date" to today, "note" to "আসল থেকে ডেমোতে")).status)
        assertEquals(200, call(pt, "book", mapOf("providerUid" to dd, "chamberIndex" to 0, "date" to today, "note" to "ডেমো থেকে ডেমোতে")).status)
    }
}
