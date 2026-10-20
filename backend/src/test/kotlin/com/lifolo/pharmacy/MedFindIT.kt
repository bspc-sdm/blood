package com.lifolo.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** medfind.js: server/test/medfind.test.js (search) + the med-find parts of demo-pharmacy, demo-night, guest */
class MedFindIT : P4Test() {
    @Autowired
    lateinit var cleanup: MedFindCleanupTask

    @Test
    fun `search - brand prefix first, generic too, nothing for one letter, no duplicates`() {
        val r = DrugList.search("napa")
        assertTrue(r.isNotEmpty() && r.size <= 15)
        assertTrue(r[0].name.lowercase().startsWith("napa"))
        assertTrue(DrugList.search("esomeprazole").any { it.generic.lowercase().contains("esomeprazole") })
        assertEquals(emptyList<Drug>(), DrugList.search("n"))
        val keys = DrugList.search("para").map { "${it.name}|${it.generic}|${it.form}|${it.company}" }
        assertEquals(keys.size, keys.toSet().size)
        // guests may search
        val g = ok(null, "drug_search", mapOf("q" to "Napa"))
        assertEquals(listOf("i", "name", "generic", "form", "company"), g["items"][0].fieldNames().asSequence().toList())
    }

    private val ph = "demo-pharmacy"
    private val ph2 = "demo-pharmacy2"
    private val pt = "demo-patient"

    private fun seed() {
        shop(ph, mapOf("name" to "ডেমো লাইফ ফার্মেসি", "district" to GAZIPUR, "upazila_id" to 320, "lat" to 24.0, "lng" to 90.42, "open24" to true))
        shop(ph2, mapOf("name" to "ডেমো দুই", "district" to GAZIPUR, "upazila_id" to 320, "lat" to 24.01, "lng" to 90.43, "open_at" to "09:00", "close_at" to "21:00"))
        person(pt, "ডেমো রোগী রহিম")
    }

    private fun montene(uid: String) = ok(uid, "drug_search", mapOf("q" to "Montene"))["items"][0]["i"].intValue()

    @Test
    fun `find, shop answers, buyer sees who has it, close, checks`() {
        setDhaka("2026-10-20 11:00")
        seed()
        assertEquals("তালিকা থেকে ওষুধ বেছে নিন", call(pt, "med_find", mapOf("i" to -1, "district" to GAZIPUR)).error)
        assertEquals("জেলা বেছে নিন", call(pt, "med_find", mapOf("i" to 0, "district" to "x")).error)
        val i = montene(pt)
        val mf = ok(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320, "lat" to 24.001, "lng" to 90.421))
        assertTrue(mf["ok"].booleanValue(), mf.toString())
        assertEquals(2, mf["notified"].intValue())
        val f = row("SELECT * FROM med_finds WHERE id = ?", mf["id"].textValue())
        assertEquals(24.0, f["lat"])
        assertEquals("Montene", (f["drug_name"] as String).substring(0, 7))
        assertTrue(lastPush(ph)!!["title"].toString().contains("ওষুধ খুঁজছেন"))
        assertEquals(1, (row("SELECT find_count FROM pharmacies WHERE uid = ?", ph)["find_count"] as Number).toInt())

        val reqs = ok(ph, "med_find_requests")
        assertFalse(reqs["findOff"].booleanValue())
        val req = reqs["items"].first { it["id"].textValue() == mf["id"].textValue() }
        assertTrue(req["answer"].isNull)
        assertEquals("গাজীপুর সদর, $GAZIPUR", req["area"].textValue())
        assertTrue(req["km"].isNumber)

        ok(ph, "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to true))
        assertTrue(lastPush(pt)!!["title"].toString().contains("ডেমো লাইফ ফার্মেসি"))
        val before = inbox(pt).size
        ok(ph, "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to true))
        assertEquals(before, inbox(pt).size, "only the first 'have' pushes")
        ok(ph2, "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to false))
        assertEquals("yes", ok(ph, "med_find_requests")["items"][0]["answer"].textValue())

        val mine = ok(pt, "med_finds_mine")["items"][0]
        assertEquals(mf["id"], mine["id"])
        assertEquals(2, mine["notified"].intValue())
        assertEquals(2, mine["answered"].intValue())
        assertEquals(listOf(ph), mine["have"].map { it["id"].textValue() })
        assertTrue(mine["open"].booleanValue())
        assertEquals("Montene", mine["drug"]["name"].textValue().substring(0, 7))

        // not notified / not approved / bad id
        person("notmine")
        shop("demo-pharmacy3", mapOf("district" to GAZIPUR, "status" to "pending"))
        assertEquals(403, call("demo-pharmacy3", "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to true)).status)
        assertEquals(400, call(ph, "med_find_answer", mapOf("id" to "../x")).status)
        assertEquals(404, call(ph, "med_find_answer", mapOf("id" to "nope0000")).status)

        // close: only the finder
        assertEquals(404, call("notmine", "med_find_close", mapOf("id" to mf["id"].textValue())).status)
        ok(pt, "med_find_close", mapOf("id" to mf["id"].textValue()))
        assertFalse(ok(pt, "med_finds_mine")["items"][0]["open"].booleanValue())
        assertEquals(0, ok(ph, "med_find_requests")["items"].size())
        assertEquals(409, call(ph, "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to true)).status)

        // a shop that switched off finds, a real user (no real shop), nothing open
        ok(ph, "pharmacy_find_setting", mapOf("off" to true))
        assertTrue(ok(ph, "med_find_requests")["findOff"].booleanValue())
        person("real-user-0001")
        assertEquals(json(mapOf("ok" to false, "noPharmacy" to true)), ok("real-user-0001", "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320)))
        setDhaka("2026-10-20 23:30")
        assertEquals(json(mapOf("ok" to false, "noneOpen" to true)), ok(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320)), "ph is off, ph2 is closed at night")
    }

    @Test
    fun `at night the 24-hour demo shop still gets the find (demo-night)`() {
        for (t in listOf("2026-10-19 22:45", "2026-10-19 23:59", "2026-10-20 00:00", "2026-10-20 03:30", "2026-10-20 05:55", "2026-10-20 11:00")) {
            cleanState()
            setDhaka(t)
            seed()
            assertTrue(ok(ph, "pharmacy_me")["pharmacy"]["openNow"].booleanValue(), t)
            val mf = ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "upazilaId" to 320))
            assertTrue(mf["ok"].booleanValue(), "$t: $mf")
            assertTrue(ok(ph, "med_find_requests")["items"].any { it["id"] == mf["id"] }, t)
        }
    }

    @Test
    fun `per shop at most 30 a day, at most 15 shops, the daily count restarts`() {
        setDhaka("2026-10-20 11:00")
        for (k in 0 until 17) {
            shop("demo-many$k", mapOf("district" to GAZIPUR, "upazila_id" to 320, "lat" to 24.0 + k * 0.001, "lng" to 90.42, "open24" to true))
        }
        person(pt)
        jdbc.update("UPDATE pharmacies SET find_day = '2026-10-20', find_count = 30 WHERE uid = 'demo-many0'")
        jdbc.update("UPDATE pharmacies SET find_day = '2026-10-19', find_count = 30 WHERE uid = 'demo-many1'")
        val mf = ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "upazilaId" to 320, "lat" to 24.0, "lng" to 90.42))
        assertEquals(15, mf["notified"].intValue())
        val targets = jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, mf["id"].textValue())
        assertFalse("demo-many0" in targets, "30 today already")
        assertTrue("demo-many1" in targets, "yesterday's count does not count")
        assertEquals(1, (row("SELECT find_count FROM pharmacies WHERE uid = 'demo-many1'")["find_count"] as Number).toInt())
        assertEquals(java.sql.Date.valueOf("2026-10-20"), row("SELECT find_day FROM pharmacies WHERE uid = 'demo-many1'")["find_day"])
        // the daily quota: 5 finds a day
        repeat(4) { ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR)) }
        assertEquals(429, call(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR)).status)
    }

    @Test
    fun `daily cleanup - finds expired more than 2 days ago are deleted`() {
        seed()
        val now = System.currentTimeMillis()
        for ((id, exp) in listOf("old1" to now - 3 * DAY, "new1" to now - DAY, "open1" to now + HOUR)) {
            jdbc.update(
                "INSERT INTO med_finds (id, uid, district, drug_name, created_at, expires_at) VALUES (?, ?, ?, 'X', ?, ?)",
                id, pt, GAZIPUR, ts(exp - 6 * HOUR), ts(exp),
            )
        }
        assertEquals(1, cleanup.run(now))
        assertEquals(listOf("new1", "open1"), jdbc.queryForList("SELECT id FROM med_finds ORDER BY id", String::class.java))
        assertNotNull(cleanup.name)
    }
}
