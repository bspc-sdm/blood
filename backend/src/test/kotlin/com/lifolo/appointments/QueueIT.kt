package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * server/test/queue.test.js: start / next / cancelled skipped / pick / pause, the average and its fallbacks, the
 * patient's estimate, assistants may and strangers may not, caregivers (asUid), "late" → ok / come later, one push
 * at 2 before, stale presses, past-midnight queues, account deletion.
 */
class QueueIT : P2TestBase() {
    @Autowired lateinit var queue: QueueActions

    @Autowired lateinit var deletion: P2AccountDeletion

    private val min = 60_000L
    private var t0 = 0L

    private fun at(m: Long) {
        clock.fixedMs = t0 + m * min
    }

    private val doc = "qDoctor"
    private val asst = "qAssistant"
    private val cg = "qCaregiver"
    private val x = "qStranger"
    private val p = listOf("qP1", "qP2", "qP3", "qP4", "qP5", "qP6")

    private fun ch(district: String) = Ch(
        hospitalId = "dghs_392", hospitalName = "Test Medical College Hospital", hospitalNameBn = "টেস্ট মেডিকেল কলেজ হাসপাতাল", district = district,
        room = "৩১২", days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, appointments = true, patientMin = 12,
    )

    private fun seed() {
        person(doc, "ডা. সিরিয়াল", "doc@q.test")
        person(asst, "সহকারী", "asst@q.test")
        person(cg, "কেয়ারগিভার", "cg@q.test")
        person(x, "অন্য কেউ", "x@q.test")
        p.forEachIndexed { i, u -> person(u, "রোগী ${i + 1}", "$u@q.test") }
        badge(doc, "doctor", "ডা. সিরিয়াল")
        provider(doc, listOf(ch(district("Mymensingh"))), name = "ডা. সিরিয়াল", assistants = listOf("asst@q.test"))
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active')", p[5], cg)
    }

    private fun stats(uid: String, chamberKey: String): Pair<Double, Int>? =
        jdbc.query(
            "SELECT avg_minutes, n FROM provider_queue_stats WHERE provider_uid = ? AND queue_key = ?",
            { rs, _ -> rs.getDouble(1) to rs.getInt(2) },
            uid, QueueRules.keyOf(chamberKey),
        ).firstOrNull()

    @Test
    fun `serial running - doctor, assistant, patient, caregiver, late`() {
        seed()
        val today = today(dhaka("2026-10-19 23:50"))
        t0 = today + 4 * 3600 * 1000 // 10 am Dhaka
        at(0)
        // six patients book today, the doctor confirms → serials 1–6; number 3 cancels later
        val ids = p.map { u ->
            val id = ok(u, "book", mapOf("providerUid" to doc, "date" to today, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()
            ok(doc, "appointment_update", mapOf("id" to id, "op" to "confirm"))
            id
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ids.map { jdbc.queryForObject("SELECT serial FROM appointments WHERE id = ?", Int::class.java, it) })
        ok(p[2], "appointment_update", mapOf("id" to ids[2], "op" to "cancel"))

        // ---------- before the start ----------
        var v = ok(p[4], "appointment_queue", mapOf("id" to ids[4]))
        assertEquals(true, v["live"].booleanValue())
        assertEquals(false, v["started"].booleanValue())
        assertEquals(3, v["ahead"].intValue(), "1, 2, 4 (3 cancelled)")
        assertEquals(12, v["avgMin"].intValue())
        assertEquals("setting", v["avgFrom"].textValue())
        // chamber from 9, now 10 → now + 3×12 = 10:36 → 10:40; come 24 minutes earlier → 10:15
        assertEquals("10:40", hhmm(v["eta"].longValue()))
        assertEquals("10:15", hhmm(v["arriveBy"].longValue()))
        // someone else's appointment is not visible
        assertEquals(404, call(x, "appointment_queue", mapOf("id" to ids[4])).status)

        // ---------- who may ----------
        assertEquals(403, call(x, "queue_today", mapOf("doctorUid" to doc)).status, "a stranger")
        assertEquals(403, call(p[0], "queue_today").status, "a patient is no doctor")
        assertEquals(403, call(p[0], "queue_control", mapOf("chamberKey" to "dghs_392", "op" to "start")).status)
        assertEquals(403, call(x, "queue_control", mapOf("doctorUid" to doc, "chamberKey" to "dghs_392", "op" to "start")).status)
        // a caregiver cannot run the queue for the patient
        assertEquals(403, call(cg, "queue_control", mapOf("asUid" to p[5], "doctorUid" to doc, "chamberKey" to "dghs_392", "op" to "start")).status)
        val tdA = ok(asst, "queue_today", mapOf("doctorUid" to doc))
        assertEquals("assistant", tdA["role"].textValue())
        assertEquals(1, tdA["chambers"].size())
        val chamberKey = tdA["chambers"][0]["chamberKey"].textValue()
        assertEquals("dghs_392", chamberKey)
        assertEquals(
            listOf("1 waiting", "2 waiting", "3 cancelled", "4 waiting", "5 waiting", "6 waiting"),
            tdA["chambers"][0]["rows"].map { "${it["serial"].intValue()} ${it["state"].textValue()}" },
        )

        // ---------- start (doctor) ----------
        var c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "start"))["chamber"]
        assertEquals(listOf(true, 1, 2), listOf(c["queue"]["started"].booleanValue(), c["queue"]["current"].intValue(), c["queue"]["next"].intValue()))
        // pressed twice: nothing changes
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "start"))["chamber"]
        assertEquals(1, c["queue"]["current"].intValue())
        // 1–2 ahead: number 2 (1 ahead) and number 4 (2 ahead) are told
        assertEquals(1, pushesTo(p[1], "আগে ১ জন").size)
        assertEquals(1, pushesTo(p[3], "আগে ২ জন — চেম্বারে চলে আসুন").size)
        assertEquals(0, pushesTo(p[4], "আগে").size)

        // ---------- late: number 4, 20 minutes ----------
        assertEquals(400, call(p[3], "appointment_late", mapOf("id" to ids[3], "minutes" to 15)).status, "only 10/20/30/60")
        assertEquals(404, call(x, "appointment_late", mapOf("id" to ids[3], "minutes" to 20)).status, "not someone else's")
        ok(p[3], "appointment_late", mapOf("id" to ids[3], "minutes" to 20, "note" to "জ্যামে আছি"))
        assertEquals(1, pushesTo(doc, "২০ মিনিট দেরিতে").size, "the doctor is told")
        val row4 = ok(asst, "queue_today", mapOf("doctorUid" to doc))["chambers"][0]["rows"].first { it["serial"].intValue() == 4 }
        assertEquals(listOf("20", "asked", "জ্যামে আছি"), listOf(row4["late"]["min"].asText(), row4["late"]["status"].textValue(), row4["late"]["note"].textValue()))
        // can be changed again (30 minutes)
        ok(p[3], "appointment_late", mapOf("id" to ids[3], "minutes" to 30))
        // nobody else may reply; the assistant says "come later"
        assertEquals(403, call(x, "appointment_late_reply", mapOf("id" to ids[3], "op" to "later")).status)
        val before5 = pushesTo(p[4], "আগে").size
        c = ok(asst, "appointment_late_reply", mapOf("id" to ids[3], "op" to "later"))["chamber"]
        assertEquals("later", row("SELECT late_status FROM appointments WHERE id = ?", ids[3])["late_status"])
        assertEquals(1, pushesTo(p[3], "পরে আসুন").size, "the patient hears the reply")
        assertEquals("later", c["rows"].first { it["serial"].intValue() == 4 }["state"].textValue())
        // order: 2, 5, 6, then 4 (the others keep their numbers)
        val l = queue.load(doc, today, chamberKey)
        assertEquals(listOf(2, 5, 6, 4), QueueRules.order(l.q, l.nums))
        // number 5 now has 2 ahead → told
        assertEquals(before5 + 1, pushesTo(p[4], "আগে ২ জন").size)
        // moved to the end: no more asking for time
        assertEquals(409, call(p[3], "appointment_late", mapOf("id" to ids[3], "minutes" to 10)).status)
        v = ok(p[3], "appointment_queue", mapOf("id" to ids[3]))
        assertEquals(listOf(true, false, 4), listOf(v["later"].booleanValue(), v["canLate"].booleanValue(), v["ahead"].intValue()))

        // ---------- caregiver: sees for number 6 and asks for time; the doctor says OK ----------
        v = ok(cg, "appointment_queue", mapOf("asUid" to p[5], "id" to ids[5]))
        assertEquals(listOf(6, 1, 3), listOf(v["serial"].intValue(), v["current"].intValue(), v["ahead"].intValue()))
        ok(cg, "appointment_late", mapOf("asUid" to p[5], "id" to ids[5], "minutes" to 10))
        assertEquals("asked", row("SELECT late_status FROM appointments WHERE id = ?", ids[5])["late_status"])
        ok(doc, "appointment_late_reply", mapOf("id" to ids[5], "op" to "ok"))
        assertEquals("ok", row("SELECT late_status FROM appointments WHERE id = ?", ids[5])["late_status"])
        assertEquals(1, pushesTo(p[5], "ঠিক আছে").size)
        assertTrue(pushesTo(cg, "ঠিক আছে").isNotEmpty(), "the caregiver too")

        // ---------- next patient (assistant), 8 minutes later ----------
        at(8)
        c = ok(asst, "queue_control", mapOf("doctorUid" to doc, "chamberKey" to chamberKey, "op" to "next"))["chamber"]
        assertEquals(2, c["queue"]["current"].intValue())
        assertEquals(8.0 to 1, stats(doc, chamberKey))
        // number 6 now has 2 ahead (2, 5) → told (patient and caregiver)
        assertEquals(1, pushesTo(p[5], "আগে ২ জন").size)
        // estimate: still the setting (12) — number 2 until 10:20, then 5; number 6 at 10:32 → 10:35
        v = ok(p[4], "appointment_queue", mapOf("id" to ids[4]))
        assertEquals(listOf(2, 1, 12), listOf(v["current"].intValue(), v["ahead"].intValue(), v["avgMin"].intValue()))
        assertEquals("10:20", hhmm(v["eta"].longValue()))
        assertEquals("09:55", hhmm(v["arriveBy"].longValue()))
        v = ok(p[5], "appointment_queue", mapOf("id" to ids[5]))
        assertEquals("10:35", hhmm(v["eta"].longValue()))
        assertEquals("10:10", hhmm(v["arriveBy"].longValue()))

        // ---------- pause ----------
        at(10)
        ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "pause"))
        v = ok(p[4], "appointment_queue", mapOf("id" to ids[4]))
        assertEquals(true, v["paused"].booleanValue())
        at(40)
        ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "resume"))
        // next at 50: gap 42 − 30 paused = 12 → average 10
        at(50)
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "next"))["chamber"]
        assertEquals(5, c["queue"]["current"].intValue(), "3 cancelled, 4 at the end")
        assertEquals(10.0 to 2, stats(doc, chamberKey))
        // one's own turn: no more asking for time
        assertEquals(409, call(p[4], "appointment_late", mapOf("id" to ids[4], "minutes" to 10)).status)
        assertEquals("now", ok(p[4], "appointment_queue", mapOf("id" to ids[4]))["state"].textValue())

        at(60)
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "next"))["chamber"]
        assertEquals(6, c["queue"]["current"].intValue())
        v = ok(p[3], "appointment_queue", mapOf("id" to ids[3]))
        assertEquals(listOf("1", "10", "history"), listOf(v["ahead"].asText(), v["avgMin"].asText(), v["avgFrom"].textValue()), "history after three gaps")
        // number 4 at the end is told again (1 ahead) — once
        assertEquals(2, pushesTo(p[3], "আগে").size)

        at(70)
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "next"))["chamber"]
        assertEquals(4, c["queue"]["current"].intValue(), "the patient moved to the end, last")
        at(75)
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "next"))["chamber"]
        assertEquals(0, c["queue"]["current"].intValue())
        assertEquals(true, c["queue"]["finished"].booleanValue())
        assertTrue(c["queue"]["next"].isNull)
        assertEquals("done", ok(p[3], "appointment_queue", mapOf("id" to ids[3]))["state"].textValue())
        // nobody got "2 before you" more than once (moved to the end: once again)
        for (u in listOf(p[1], p[4], p[5])) assertTrue(pushesTo(u, "আগে").size <= 1, u)

        // ---------- pick: number 2 again; gaps over 60 minutes do not count ----------
        at(200)
        c = ok(asst, "queue_control", mapOf("doctorUid" to doc, "chamberKey" to chamberKey, "op" to "set", "appointmentId" to ids[1]))["chamber"]
        assertEquals(2, c["queue"]["current"].intValue())
        assertEquals(4, stats(doc, chamberKey)!!.second, "only the four earlier gaps (8, 12, 10, 10)")
        assertEquals(409, call(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "set", "appointmentId" to ids[2])).status, "not the cancelled one")
        // a number booked by phone (not in the app) can be picked too
        c = ok(doc, "queue_control", mapOf("chamberKey" to chamberKey, "op" to "set", "serial" to 9))["chamber"]
        assertEquals(9, c["queue"]["current"].intValue())

        // ---------- account deletion: the doctor's queues go ----------
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM queues WHERE provider_uid = ?", Int::class.java, doc)!! > 0)
        deletion.deleteNow(doc, "doc@q.test")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM queues", Int::class.java))
    }

    /** a doctor with only a badge (profile = also a provider row without chambers) */
    private fun seedDoctor(uid: String, profile: Boolean = false) {
        person(uid)
        badge(uid, "doctor", uid)
        if (profile) provider(uid, emptyList())
    }

    private fun seedAppt(id: String, doctor: String, patient: String, date: Long, serial: Int, chamberKey: String) {
        person(patient)
        jdbc.update(
            """
            INSERT INTO appointments (id, provider_uid, patient_uid, visit_date, serial, status, chamber_key, chamber, chamber_bn, provider_name,
              patient_name, start_time, type, created_at)
            VALUES (?, ?, ?, ?, ?, 'confirmed', ?, 'Night Clinic', 'রাতের চেম্বার', 'ডা. রাত', ?, '20:00', 'doctor', ?)
            """.trimIndent(),
            id, doctor, patient, Sql.date(DhakaTime.fromUtcMidnightMs(date)), serial, chamberKey, patient, Sql.ts(serial.toLong()),
        )
    }

    @Test
    fun `two next presses at once - an old number changes nothing (stale), no provider row still records the average`() {
        val d = "qStaleDoc"
        seedDoctor(d)
        val key = "dghs_901"
        val today = today(dhaka("2026-10-19 23:50"))
        t0 = today + 4 * 3600 * 1000
        for (s in 1..4) seedAppt("qStale$s", d, "qStaleP$s", today, s, key)
        at(0)
        var r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "start"))
        assertEquals(1, r["chamber"]["queue"]["current"].intValue())
        at(6)
        // doctor and assistant both saw 1 and press "next" — the first gives 2, the second nothing (not 3)
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 1))
        assertEquals(2, r["chamber"]["queue"]["current"].intValue())
        assertFalse(r.has("stale"))
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 1))
        assertEquals(listOf("true", "2", "3"), listOf(r["stale"].asText(), r["chamber"]["queue"]["current"].asText(), r["chamber"]["queue"]["next"].asText()), "nobody skipped")
        val q = queue.readQ(d, today, QueueRules.keyOf(key))
        assertEquals(2, q.current)
        assertEquals(listOf(1), q.done)
        // "seeing now" the same
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "set", "serial" to 4, "expect" to 1))
        assertEquals(true, r["stale"].booleanValue())
        assertEquals(2, r["chamber"]["queue"]["current"].intValue())
        // no provider document: "next" was not blocked, the average was written
        assertEquals(1, stats(d, key)!!.second)
        at(12)
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 2))
        assertEquals(3, r["chamber"]["queue"]["current"].intValue())
        assertEquals(2, stats(d, key)!!.second, "the answer has the new average too")
        assertEquals(10, r["chamber"]["avgMin"].intValue(), "two gaps are not enough history: the default")
    }

    @Test
    fun `the 2-before push goes once even for two loads at the same time`() {
        val d = "qNearDoc"
        seedDoctor(d, profile = true)
        val key = "dghs_902"
        val today = today()
        for (s in 1..3) seedAppt("qNear$s", d, "qNearP$s", today, s, key)
        jdbc.update(
            "INSERT INTO queues (provider_uid, visit_date, queue_key, chamber_key, started, current, pos) VALUES (?, ?, ?, ?, TRUE, 1, 1)",
            d, Sql.date(DhakaTime.fromUtcMidnightMs(today)), QueueRules.keyOf(key), key,
        )
        val l1 = queue.load(d, today, key)
        val l2 = queue.load(d, today, key)
        assertEquals(2, queue.notifyNear(l1) + queue.notifyNear(l2), "two patients, each once")
        assertEquals(1, pushesTo("qNearP2", "আগে").size)
        assertEquals(1, pushesTo("qNearP3", "আগে").size)
    }

    @Test
    fun `the patient view follows cancellations and queue changes at once`() {
        val d = "qCacheDoc"
        seedDoctor(d, profile = true)
        val key = "dghs_903"
        val today = today(dhaka("2026-10-19 23:50"))
        t0 = today + 4 * 3600 * 1000
        for (s in 1..4) seedAppt("qCache$s", d, "qCacheP$s", today, s, key)
        at(0)
        assertEquals(3, ok("qCacheP4", "appointment_queue", mapOf("id" to "qCache4"))["ahead"].intValue())
        // cancelled in the app → at once
        ok("qCacheP2", "appointment_update", mapOf("id" to "qCache2", "op" to "cancel"))
        assertEquals(2, ok("qCacheP4", "appointment_queue", mapOf("id" to "qCache4"))["ahead"].intValue())
        // the queue runs → at once
        ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "start"))
        var v = ok("qCacheP4", "appointment_queue", mapOf("id" to "qCache4"))
        assertEquals(listOf("true", "1", "2"), listOf(v["started"].asText(), v["current"].asText(), v["ahead"].asText()))
        ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 1))
        v = ok("qCacheP4", "appointment_queue", mapOf("id" to "qCache4"))
        assertEquals(listOf(3, 1), listOf(v["current"].intValue(), v["ahead"].intValue()))
        ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 3))
        assertEquals("now", ok("qCacheP4", "appointment_queue", mapOf("id" to "qCache4"))["state"].textValue())
    }

    @Test
    fun `past midnight - until 6 am the running queue of yesterday for doctor, patient and late, once finished the one of today does not start by itself`() {
        val d = "qNightDoc"
        seedDoctor(d, profile = true)
        val key = "dghs_904"
        val today = today(dhaka("2026-10-19 23:50"))
        val yest = today - 24 * 3600 * 1000L
        for (s in 1..3) seedAppt("qNight$s", d, "qNightP$s", yest, s, key)
        seedAppt("qNightToday", d, "qNightP9", today, 1, key) // today's serial in the same chamber
        var now = yest + 16 * 3600 * 1000 // yesterday 10 pm Dhaka
        clock.fixedMs = now
        assertFalse(QueueRules.earlyHours(now))
        ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "start"))
        now += 10 * min
        clock.fixedMs = now
        ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 1))
        // 12:30 at night (today's date)
        now = today - 6 * 3600 * 1000 + 30 * min
        clock.fixedMs = now
        assertEquals(today, DhakaTime.todayUtcMidnight(now))
        assertTrue(QueueRules.earlyHours(now))
        var td = ok(d, "queue_today")
        assertEquals(listOf("$yest 2 $key"), td["chambers"].map { "${it["date"].asLong()} ${it["queue"]["current"].asInt()} ${it["chamberKey"].textValue()}" }, "yesterday's first, today's later")
        // the patient sees yesterday's appointment in the queue and can ask for time
        var v = ok("qNightP3", "appointment_queue", mapOf("id" to "qNight3"))
        assertEquals(listOf("true", "2", "1"), listOf(v["live"].asText(), v["current"].asText(), v["ahead"].asText()))
        ok("qNightP3", "appointment_late", mapOf("id" to "qNight3", "minutes" to 10))
        ok(d, "appointment_late_reply", mapOf("id" to "qNight3", "op" to "ok"))
        // old app (no date) and new app (with date) — both run yesterday's
        var r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 2))
        assertEquals(3, r["chamber"]["queue"]["current"].intValue())
        assertEquals(yest, r["chamber"]["date"].asLong())
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 3, "date" to yest))
        assertEquals(true, r["chamber"]["queue"]["finished"].booleanValue())
        // pressed again after the end: today's queue does not start
        r = ok(d, "queue_control", mapOf("chamberKey" to key, "op" to "next", "expect" to 0, "date" to yest))
        assertEquals(yest, r["chamber"]["date"].asLong())
        assertEquals(true, r["chamber"]["queue"]["finished"].booleanValue())
        assertFalse(queue.readQ(d, today, QueueRules.keyOf(key)).exists)
        td = ok(d, "queue_today")
        assertEquals(listOf("$today false"), td["chambers"].map { "${it["date"].asLong()} ${it["queue"]["started"].asBoolean()}" }, "yesterday's is over — now today's")
        v = ok("qNightP3", "appointment_queue", mapOf("id" to "qNight3"))
        assertEquals("done", v["state"].textValue())
        // 7 am: nothing of yesterday
        clock.fixedMs = today + 3600 * 1000
        assertEquals(false, ok("qNightP3", "appointment_queue", mapOf("id" to "qNight3"))["live"].booleanValue())
    }

    @Test
    fun `account deletion removes every queue of the doctor, even many days`() {
        val d = "qManyDoc"
        person(d)
        person("other")
        for (i in 0 until 30) {
            jdbc.update(
                "INSERT INTO queues (provider_uid, visit_date, queue_key, started) VALUES (?, ?, 'k', TRUE)",
                d, Sql.date(java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong())),
            )
        }
        jdbc.update("INSERT INTO queues (provider_uid, visit_date, queue_key) VALUES ('other', '2026-01-01', 'k')")
        assertEquals(30, queue.deleteFor(d))
        assertEquals(listOf("other"), jdbc.queryForList("SELECT provider_uid FROM queues", String::class.java))
    }
}
