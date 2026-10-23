package com.lifolo.work

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.appointments.Ch
import com.lifolo.appointments.P2TestBase
import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.features.Features
import com.lifolo.pharmacy.P4Clock
import com.lifolo.support.setFeature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/** MYWORK.md: work_today (one call for the "আমার কাজ" home) and the breaks with `until` that end by themselves */
class WorkTodayIT : P2TestBase() {
    @Autowired lateinit var p4: P4Clock

    @Autowired lateinit var features: Features

    private val min = 60_000L
    private val day = 24 * 3600 * 1000L

    @AfterEach
    fun back() {
        p4.fixed = null
        features.allOn = true
    }

    /** both clocks (appointments + pharmacy) at the same Dhaka time */
    private fun at(s: String): Long {
        val t = dhaka(s)
        clock.fixedMs = t
        p4.fixed = t
        return t
    }

    private fun work(uid: String): JsonNode = ok(uid, "work_today")

    private fun item(w: JsonNode, kind: String): JsonNode = w["items"].first { it["kind"].textValue() == kind }

    private fun types(n: JsonNode): List<String> = n["tasks"].map { it["type"].textValue() }

    // ------------------------------------------------------------------ doctor + assistant

    private val doc = "wtDoc"
    private val asst = "wtAsst"

    private fun seedDoctor() {
        val gzp = district("Gazipur")
        person(doc, "ডা. কাজ")
        badge(doc, "doctor", "ডা. কাজ")
        person(asst, "সহকারী", "wtasst@q.test")
        for (p in listOf("wtP1", "wtP2", "wtP3", "wtP4")) person(p, "রোগী $p")
        provider(
            doc,
            listOf(
                Ch(hospitalName = "Every Day Clinic", district = gzp, upazilaId = 320, days = listOf(0, 1, 2, 3, 4, 5, 6), start = "17:00", end = "21:00", fee = "৫০০ টাকা", appointments = true),
                Ch(hospitalName = "Monday Clinic", district = gzp, upazilaId = 320, days = listOf(1), start = "09:00", end = "22:00", fee = "৩০০ টাকা", appointments = true),
            ),
            name = "ডা. কাজ",
            assistants = listOf("wtasst@q.test"),
        )
    }

    private fun book(p: String, date: Long, chamber: Int = 0): String =
        ok(p, "book", mapOf("providerUid" to doc, "date" to date, "chamberIndex" to chamber, "note" to "জ্বর"))["id"].textValue()

    private fun card(): JsonNode =
        ok("wtP1", "providers", mapOf("type" to "doctor", "district" to district("Gazipur"), "homeVisit" to false))["items"].first { it["uid"].textValue() == doc }

    @Test
    fun `doctor - today's numbers, tasks, a chamber break that ends by itself, assistant view`() {
        val now = at("2026-10-19 18:00") // Monday, inside "Every Day Clinic" 17–21
        seedDoctor()
        val today = today()
        val a1 = book("wtP1", today)
        val a2 = book("wtP2", today)
        book("wtP3", today + day)
        ok(doc, "appointment_update", mapOf("id" to a1, "op" to "confirm"))
        ok(doc, "appointment_update", mapOf("id" to a2, "op" to "confirm"))

        val w = work(doc)
        assertEquals(listOf("doctor"), w["roles"].map { it.textValue() })
        val d = item(w, "doctor")
        assertEquals("owner", d["role"].textValue())
        assertEquals("open", d["status"].textValue())
        assertEquals(2, d["counts"]["today"].intValue())
        assertEquals(2, d["counts"]["waiting"].intValue())
        assertEquals(1, d["counts"]["requests"].intValue(), "tomorrow's request waits for গ্রহণ")
        // ডাকুন first (most urgent), then the new request
        assertEquals(listOf("queue_next", "appt_request"), types(d))
        assertEquals("next", d["tasks"][0]["action"].textValue())
        assertEquals("accept", d["tasks"][1]["action"].textValue())
        assertEquals("রোগী wtP3", d["tasks"][1]["title"].textValue())
        assertEquals(w["tasks"].size(), 2)
        assertEquals(2, w["taskCount"].intValue())
        val chs = d["chambers"]
        assertEquals(listOf("open", "open"), chs.map { it["status"].textValue() })
        assertTrue(chs[0]["sessionNow"].booleanValue())
        // setup: everything there except the photo
        assertEquals(mapOf("photo" to false, "chamber" to true, "time" to true, "address" to true, "fee" to true), d["setup"].associate { it["key"].textValue() to it["done"].booleanValue() })
        // the queue tab counts (queue_today)
        val q = ok(doc, "queue_today")["chambers"][0]["counts"]
        assertEquals(2, q["waiting"].intValue())
        assertEquals(0, q["now"].intValue())
        assertEquals(0, q["done"].intValue())

        // বিরতি ৩০ মিনিট on the evening chamber
        val until = now + 30 * min
        val r = ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to true, "until" to until))
        assertEquals(until, r["pausedUntil"].longValue())
        assertFalse(r["closed"].booleanValue(), "a break is not a closed day")
        val pausedCh = item(work(doc), "doctor")["chambers"][0]
        assertEquals("paused", pausedCh["status"].textValue())
        assertEquals(until, pausedCh["pausedUntil"].longValue())
        assertEquals(until, ok(doc, "chamber_days")["items"][0]["pausedUntil"].longValue())
        // bookings for today stay open during a break
        book("wtP4", today)
        // both chambers on a break → the card is not open now; nextSlot still today
        ok(asst, "chamber_day_set", mapOf("doctorUid" to doc, "chamberIndex" to 1, "closed" to true, "until" to now + 60 * min))
        val c = card()
        assertFalse(c["openNow"].booleanValue())
        assertEquals(today, c["nextSlot"]["date"].longValue())
        val both = item(work(doc), "doctor")
        assertEquals("paused", both["status"].textValue())
        assertEquals(until, both["pausedUntil"].longValue(), "the first chamber to open again")

        // 31 minutes later the first break is over by itself
        clock.fixedMs = now + 31 * min
        p4.fixed = now + 31 * min
        val later = item(work(doc), "doctor")
        assertEquals("open", later["chambers"][0]["status"].textValue())
        assertTrue(later["chambers"][0]["pausedUntil"].isNull)
        assertEquals("paused", later["chambers"][1]["status"].textValue())
        assertTrue(ok(doc, "chamber_days")["items"][0]["pausedUntil"].isNull)
        assertTrue(card()["openNow"].booleanValue())
        // "চলছে" again ends the other break at once
        ok(doc, "chamber_day_set", mapOf("chamberIndex" to 1, "closed" to false))
        assertEquals("open", item(work(doc), "doctor")["chambers"][1]["status"].textValue())

        // a break must end later today; not on another day
        val t = now + 31 * min
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to true, "until" to t - min)).status)
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to true, "until" to today + day + 1)).status)
        assertEquals(400, call(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "date" to today + day, "closed" to true, "until" to t + min)).status)
        // আজ বন্ধ (no until) → closed; the queue task goes
        ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to true))
        val closed = item(work(doc), "doctor")
        assertEquals("closed", closed["chambers"][0]["status"].textValue())
        assertFalse(types(closed).contains("queue_next"))
        ok(doc, "chamber_day_set", mapOf("chamberIndex" to 0, "closed" to false))

        // the assistant: same chambers and queue, no requests (only the doctor confirms them), no setup
        val aw = work(asst)
        val ad = item(aw, "doctor")
        assertEquals("assistant", ad["role"].textValue())
        assertEquals(doc, ad["id"].textValue())
        assertFalse(ad["counts"].has("requests"))
        assertEquals(2, ad["counts"]["today"].intValue(), "wtP4's request is not confirmed yet")
        assertEquals(listOf("queue_next"), types(ad))
        assertEquals("/work/rx/patients?doctor=$doc", ad["tasks"][0]["link"].textValue())
        assertEquals(0, ad["setup"].size())

        // the log: the breaks too
        assertEquals(2L, row("SELECT count(*) AS n FROM listing_switch_log WHERE kind = 'chamber' AND field = 'pause'")["n"])
    }

    // ------------------------------------------------------------------ businesses

    private val own = "wtOwner"

    private fun shop() {
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, upazila_id, address, lat, lng, open_at, close_at, status)
            VALUES (?, 'কাজের দোকান', 'DL-1', '01711111111', ?, 466, 'চরপাড়া', 24.75, 90.4, '08:00', '22:00', 'approved')
            """.trimIndent(),
            own, district("Mymensingh"),
        )
    }

    @Test
    fun `pharmacy, lab, blood bank, ambulance - state, numbers, tasks, setup, breaks with until`() {
        val now = at("2026-10-20 10:00")
        person(own, "মালিক")
        person("wtPat", "রোগী করিম")
        shop()
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status) VALUES ('wtRx1', 'WT23CD45EF', ?, 'wtPat', 'active')", own)
        jdbc.update(
            "INSERT INTO rx_orders (id, rx_id, code, patient_uid, patient_name, pharmacy_uid, mode, status, created_at) VALUES ('wtO1', 'wtRx1', 'WT23CD45EF', 'wtPat', 'রোগী করিম', ?, 'shop', 'sent', ?)",
            own, Timestamp(now - 5 * min),
        )
        jdbc.update("INSERT INTO shop_days (shop_uid, day, sales, net) VALUES (?, ?, 3, 450)", own, Sql.date(DhakaTime.day(now)))
        // the lab (same owner), open 24 hours, one new request and one with the sample taken
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, hours, status) VALUES (?, 'কাজের ল্যাব', 'ঢাকা', 'ঠিকানা', 'D-1', '01711000498', '২৪ ঘণ্টা', 'approved')",
            own,
        )
        val real = System.currentTimeMillis()
        jdbc.update(
            "INSERT INTO lab_orders (id, lab_uid, patient_uid, patient_name, tests, status, created_at) VALUES ('wtL1', ?, 'wtPat', 'রোগী করিম', '{CBC,RBS}', 'sent', ?)",
            own, Timestamp(real - 2 * min),
        )
        jdbc.update(
            "INSERT INTO lab_orders (id, lab_uid, patient_uid, patient_name, tests, status, created_at) VALUES ('wtL2', ?, 'wtPat', 'রোগী করিম', '{Lipid}', 'received', ?)",
            own, Timestamp(real - 60 * min),
        )
        // a blood bank without stock news, an ambulance not available
        jdbc.update(
            """
            INSERT INTO blood_banks (id, owner_uid, name, kind, phone, district, address, open24, services, status)
            VALUES ('wtBb', ?, 'কাজের ব্লাড ব্যাংক', 'org', '01712345678', 'ঢাকা', 'চরপাড়া', TRUE, '{whole_blood}', 'approved')
            """.trimIndent(),
            own,
        )
        jdbc.update(
            "INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, status) VALUES ('wtAmb', ?, 'রহিম অ্যাম্বুলেন্স', '01711000000', 'GA-1', '{ac}', 'ঢাকা', 'approved')",
            own,
        )

        val w = work(own)
        assertEquals(listOf("pharmacy", "lab", "bloodbank", "ambulance"), w["roles"].map { it.textValue() })
        // the merged list: most urgent first (new order / new lab request, then the report, then stock), max 5
        assertEquals(listOf("lab_new", "order_new", "lab_report", "stock"), w["tasks"].map { it["type"].textValue() })
        assertEquals(4, w["taskCount"].intValue())

        val ph = item(w, "pharmacy")
        assertEquals("open", ph["status"].textValue())
        assertEquals("approved", ph["listingStatus"].textValue())
        assertEquals(1, ph["counts"]["new"].intValue())
        assertEquals(0, ph["counts"]["active"].intValue())
        assertEquals(3, ph["counts"]["salesToday"].intValue())
        assertEquals(450, ph["counts"]["salesTodayTk"].intValue())
        assertEquals(0, ph["counts"]["finds"].intValue())
        val o = ph["tasks"][0]
        assertEquals("ready", o["action"].textValue())
        assertEquals("/work/pharmacy/orders/rx/WT23CD45EF", o["link"].textValue())
        assertEquals("দোকানে এসে নেবেন · WT23CD45EF", o["sub"].textValue())
        assertTrue(ph["setup"].all { it["done"].booleanValue() })

        // বিরতি ৩০ মিনিট → paused, opens again by itself
        val until = now + 30 * min
        val r = ok(own, "pharmacy_open_override", mapOf("open" to false, "until" to until))
        assertEquals(until, r["openOverride"]["until"].longValue())
        assertFalse(r["openNow"].booleanValue())
        val p1 = item(work(own), "pharmacy")
        assertEquals("paused", p1["status"].textValue())
        assertEquals(until, p1["pausedUntil"].longValue())
        p4.fixed = now + 31 * min
        clock.fixedMs = now + 31 * min
        assertEquals("open", item(work(own), "pharmacy")["status"].textValue())
        assertTrue(ok(own, "pharmacy_me")["pharmacy"]["openNow"].booleanValue())
        // বন্ধ (to the next scheduled change = closing time 22:00) → closed, not "opens again at 22:00"
        ok(own, "pharmacy_open_override", mapOf("open" to false))
        val p2 = item(work(own), "pharmacy")
        assertEquals("closed", p2["status"].textValue())
        assertTrue(p2["pausedUntil"].isNull)
        ok(own, "pharmacy_open_override", mapOf("open" to null))
        // until: within 24 h, never in the past, only with open true/false
        val t = now + 31 * min
        assertEquals(400, call(own, "pharmacy_open_override", mapOf("open" to false, "until" to t - 1)).status)
        assertEquals(400, call(own, "pharmacy_open_override", mapOf("open" to false, "until" to t + 25 * 60 * min)).status)
        assertEquals(400, call(own, "pharmacy_open_override", mapOf("open" to null, "until" to t + 10 * min)).status)
        // a long break (pharmacy_pause) is "paused" until its end
        val back = t + 2 * day
        ok(own, "pharmacy_pause", mapOf("until" to back))
        val p3 = item(work(own), "pharmacy")
        assertEquals("paused", p3["status"].textValue())
        assertEquals(back, p3["pausedUntil"].longValue())
        ok(own, "pharmacy_pause", mapOf("until" to null))

        val lab = item(w, "lab")
        assertEquals("open", lab["status"].textValue())
        assertEquals(1, lab["counts"]["new"].intValue())
        assertEquals(1, lab["counts"]["active"].intValue())
        assertEquals(0, lab["counts"]["reported"].intValue())
        assertEquals(listOf("lab_new", "lab_report"), types(lab))
        assertEquals(listOf("receive", "report"), lab["tasks"].map { it["action"].textValue() })
        assertEquals("CBC, RBS", lab["tasks"][0]["sub"].textValue())
        assertEquals("/work/lab/orders/wtL1", lab["tasks"][0]["link"].textValue())
        assertEquals(mapOf("approved" to true, "hours" to true, "address" to true, "prices" to false), lab["setup"].associate { it["key"].textValue() to it["done"].booleanValue() })
        // the lab's break (the lab runs on the real clock)
        val labUntil = System.currentTimeMillis() + 60 * min
        ok(own, "lab_open_override", mapOf("open" to false, "until" to labUntil))
        val l2 = item(work(own), "lab")
        assertEquals("paused", l2["status"].textValue())
        assertEquals(labUntil, l2["pausedUntil"].longValue())
        assertFalse(ok(own, "lab_me")["lab"]["openNow"].booleanValue())
        jdbc.update("UPDATE labs SET open_override_until = ? WHERE uid = ?", Timestamp(System.currentTimeMillis() - 1), own)
        assertEquals("open", item(work(own), "lab")["status"].textValue(), "ended by itself")
        assertEquals(400, call(own, "lab_open_override", mapOf("open" to false, "until" to System.currentTimeMillis() + 2 * day)).status)

        val bb = item(w, "bloodbank")
        assertTrue(bb["status"].isNull)
        assertTrue(bb["stockUpdatedAt"].isNull)
        assertEquals("stock", bb["tasks"][0]["action"].textValue())
        assertEquals("স্টকের খবর এখনো দেওয়া হয়নি", bb["tasks"][0]["sub"].textValue())
        assertFalse(bb["setup"].first { it["key"].textValue() == "stock" }["done"].booleanValue())
        // the stock given: no task, fresh
        ok(own, "bloodbank_stock_set", mapOf("id" to "wtBb", "groups" to mapOf("A+" to "have")))
        val bb2 = item(work(own), "bloodbank")
        assertEquals(0, bb2["tasks"].size())
        assertFalse(bb2["stockStale"].booleanValue())

        val amb = item(w, "ambulance")
        assertEquals("closed", amb["status"].textValue())
        assertEquals(0, amb["counts"]["calls"].intValue())
        assertEquals("wtAmb", amb["vehicles"][0]["id"].textValue())
        assertFalse(amb["vehicles"][0]["available"].booleanValue())
        assertEquals(mapOf("approved" to true, "base" to false), amb["setup"].associate { it["key"].textValue() to it["done"].booleanValue() })
        jdbc.update("UPDATE ambulances SET available = TRUE, available_at = now() WHERE id = 'wtAmb'")
        val amb2 = item(work(own), "ambulance")
        assertEquals("open", amb2["status"].textValue())
        assertTrue(amb2["vehicles"][0]["available"].booleanValue())
        assertTrue(ok(own, "ambreq_driver")["counts"]["new"].isInt)

        // English
        assertEquals("Pick up at the shop · WT23CD45EF", item(ok(own, "work_today", lang = "en"), "pharmacy")["tasks"][0]["sub"].textValue())
    }

    @Test
    fun `no role - nothing, a pending pharmacy - setup only, no queue numbers`() {
        at("2026-10-20 10:00")
        person("wtNobody")
        val w = work("wtNobody")
        assertEquals(0, w["items"].size())
        assertEquals(0, w["roles"].size())
        assertEquals(0, w["taskCount"].intValue())
        assertEquals(401, call(null, "work_today").status)

        person(own)
        shop()
        jdbc.update("UPDATE pharmacies SET status = 'pending', lat = NULL, lng = NULL WHERE uid = ?", own)
        val ph = item(work(own), "pharmacy")
        assertEquals("pending", ph["listingStatus"].textValue())
        assertFalse(ph["counts"].has("new"))
        assertFalse(ph["counts"].has("finds"))
        assertEquals(mapOf("approved" to false, "hours" to true, "address" to true, "location" to false), ph["setup"].associate { it["key"].textValue() to it["done"].booleanValue() })
    }

    // ------------------------------------------------------------------ feature switches

    @Test
    fun `switched-off features contribute nothing - online chambers, physio`() {
        at("2026-10-19 18:00")
        person("wtOnl", "ডা. অনলাইন")
        badge("wtOnl", "doctor", "ডা. অনলাইন")
        provider(
            "wtOnl",
            listOf(
                Ch(hospitalName = "Clinic", district = district("Gazipur"), upazilaId = 320, days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "13:00", fee = "৫০০ টাকা", appointments = true),
                Ch(mode = "online", days = listOf(0, 1, 2, 3, 4, 5, 6), start = "18:00", end = "21:00", fee = "৪০০ টাকা", appointments = true),
            ),
            name = "ডা. অনলাইন",
        )
        person("wtPhy", "ফিজিও")
        badge("wtPhy", "physio", "ফিজিও")
        provider("wtPhy", emptyList(), type = "physio", name = "ফিজিও")
        // every switch on (the test profile): both chambers, the physio's chamber business
        assertEquals(2, item(work("wtOnl"), "doctor")["chambers"].size())
        assertEquals("physio", item(work("wtPhy"), "doctor")["proType"].textValue())

        features.allOn = false
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, "off")
        setFeature(jdbc, features, Features.PRO_PHYSIO, "off")
        val chs = item(work("wtOnl"), "doctor")["chambers"]
        assertEquals(1, chs.size())
        assertEquals("Clinic", chs[0]["name"].textValue())
        assertEquals(0, work("wtPhy")["items"].size())
        assertNull(work("wtPhy")["roles"].firstOrNull())
    }
}
