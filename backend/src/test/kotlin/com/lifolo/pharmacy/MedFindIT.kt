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

    private fun idx(name: String, form: String) = DrugList.rows.indexOfFirst { it.name == name && it.form == form }.also { assertTrue(it >= 0, "$name $form") }

    @Test
    fun `find with an amount, shops answer how many they have, buyer sees who has it, close, checks`() {
        setDhaka("2026-10-20 11:00")
        seed()
        assertEquals("তালিকা থেকে ওষুধ বেছে নিন", call(pt, "med_find", mapOf("i" to -1, "district" to GAZIPUR, "qty" to 1)).error)
        assertEquals("জেলা বেছে নিন", call(pt, "med_find", mapOf("i" to 0, "district" to "x", "qty" to 1)).error)
        val i = montene(pt)
        // the amount is required: 1–999, an integer (an old app without it gets a clean 400)
        for (q in listOf(null, 0, 1000, -2, 2.5, "3", true)) {
            val r = call(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR, "qty" to q, "unit" to "strip"))
            assertEquals(400, r.status, "qty $q")
            assertEquals("পরিমাণ ১ থেকে ৯৯৯-এর মধ্যে দিন", r.error)
        }
        assertEquals(400, call(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR)).status, "no qty at all")
        assertEquals(0, (row("SELECT count(*) AS n FROM med_finds")["n"] as Number).toInt(), "a refused find is not stored")

        val mf = ok(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320, "lat" to 24.001, "lng" to 90.421, "qty" to 3, "unit" to "strip"))
        assertTrue(mf["ok"].booleanValue(), mf.toString())
        assertEquals(2, mf["notified"].intValue())
        val id = mf["id"].textValue()
        val f = row("SELECT * FROM med_finds WHERE id = ?", id)
        assertEquals(24.0, f["lat"])
        assertEquals("Montene", (f["drug_name"] as String).substring(0, 7))
        assertEquals(3, (f["qty"] as Number).toInt())
        assertEquals("strip", f["unit"])
        // the shop's push and bell entry: the medicine and the amount, never the buyer
        val push = lastPush(ph)!!
        assertEquals("কাছে একজন ওষুধ খুঁজছেন", push["title"])
        assertEquals("${f["drug_name"]} — ৩ পাতা লাগবে। আপনার কাছে আছে?", push["body"])
        assertFalse(push.toString().contains("রহিম"), "no buyer name in the push")
        assertEquals(1, (row("SELECT find_count FROM pharmacies WHERE uid = ?", ph)["find_count"] as Number).toInt())

        val reqs = ok(ph, "med_find_requests")
        assertFalse(reqs["findOff"].booleanValue())
        val req = by(reqs, id)
        assertTrue(req["answer"].isNull && req["haveQty"].isNull)
        assertEquals(3, req["qty"].intValue())
        assertEquals("strip", req["unit"].textValue())
        assertEquals("গাজীপুর সদর, $GAZIPUR", req["area"].textValue())
        assertTrue(req["km"].isNumber)
        assertFalse(req.toString().contains("রহিম"))

        // answers: how many it has (1–9999) — no "নেই"; an old app's have true/false gets a clean 400
        for (bad in listOf(mapOf("have" to true), mapOf("have" to false), mapOf("haveQty" to 0), mapOf("haveQty" to 10000), mapOf("haveQty" to "4"), emptyMap<String, Any?>())) {
            val r = call(ph, "med_find_answer", mapOf("id" to id) + bad)
            assertEquals(400, r.status, bad.toString())
            assertEquals("কতটা আছে লিখুন (১ থেকে ৯৯৯৯)", r.error)
        }
        assertTrue(by(ok(ph, "med_find_requests"), id)["answer"].isNull, "nothing stored")

        // ph2 has only 1 strip; ph has 5 (all of it)
        ok(ph2, "med_find_answer", mapOf("id" to id, "haveQty" to 1))
        val p1 = lastPush(pt)!!
        assertEquals("ডেমো দুই-এ আছে", p1["title"])
        assertEquals("${f["drug_name"]} — ফোন করে নিশ্চিত হয়ে নিন", p1["body"], "no stock number on the lock screen")
        ok(ph, "med_find_answer", mapOf("id" to id, "haveQty" to 5, "unit" to "bottle"))
        assertTrue(lastPush(pt)!!["title"].toString().contains("ডেমো লাইফ ফার্মেসি"))
        var mine = by(ok(pt, "med_finds_mine"), id)
        assertEquals(3, mine["qty"].intValue())
        assertEquals("strip", mine["unit"].textValue())
        assertEquals(2, mine["notified"].intValue())
        assertEquals(2, mine["answered"].intValue())
        // full first, then nearest
        assertEquals(listOf(ph, ph2), mine["have"].map { it["id"].textValue() })
        assertEquals(listOf(5, 1), mine["have"].map { it["haveQty"].intValue() })
        assertEquals(listOf("strip", "strip"), mine["have"].map { it["haveUnit"].textValue() }, "always the buyer's unit")
        assertEquals(listOf(true, false), mine["have"].map { it["full"].booleanValue() })
        assertTrue(mine["have"][0]["km"].isNumber && mine["have"][0]["phone"].isTextual)
        assertTrue(mine["open"].booleanValue())
        assertEquals("Montene", mine["drug"]["name"].textValue().substring(0, 7))

        // answering again changes the amount; no second push
        val before = inbox(pt).size
        ok(ph2, "med_find_answer", mapOf("id" to id, "haveQty" to 4))
        assertEquals(before, inbox(pt).size, "only the first answer pushes")
        val r2 = by(ok(ph2, "med_find_requests"), id)
        assertEquals("yes", r2["answer"].textValue())
        assertEquals(4, r2["haveQty"].intValue())
        assertEquals("strip", r2["haveUnit"].textValue())
        mine = by(ok(pt, "med_finds_mine"), id)
        assertEquals(listOf(true, true), mine["have"].map { it["full"].booleanValue() })
        assertTrue(mine["have"][0]["km"].doubleValue() <= mine["have"][1]["km"].doubleValue(), "both full → nearest first")

        // not notified / not approved / bad id
        person("notmine")
        shop("demo-pharmacy3", mapOf("district" to GAZIPUR, "status" to "pending"))
        assertEquals(403, call("demo-pharmacy3", "med_find_answer", mapOf("id" to id, "haveQty" to 1)).status)
        assertEquals(400, call(ph, "med_find_answer", mapOf("id" to "../x", "haveQty" to 1)).status)
        assertEquals(404, call(ph, "med_find_answer", mapOf("id" to "nope0000", "haveQty" to 1)).status)

        // close: only the finder
        assertEquals(404, call("notmine", "med_find_close", mapOf("id" to id)).status)
        ok(pt, "med_find_close", mapOf("id" to id))
        assertFalse(ok(pt, "med_finds_mine")["items"][0]["open"].booleanValue())
        assertEquals(0, ok(ph, "med_find_requests")["items"].size())
        assertEquals(409, call(ph, "med_find_answer", mapOf("id" to id, "haveQty" to 2)).status)

        // a shop that switched off finds, a real user (no real shop), nothing open
        ok(ph, "pharmacy_find_setting", mapOf("off" to true))
        assertTrue(ok(ph, "med_find_requests")["findOff"].booleanValue())
        person("real-user-0001")
        assertEquals(json(mapOf("ok" to false, "noPharmacy" to true)), ok("real-user-0001", "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320, "qty" to 1)))
        setDhaka("2026-10-20 23:30")
        assertEquals(json(mapOf("ok" to false, "noneOpen" to true)), ok(pt, "med_find", mapOf("i" to i, "district" to GAZIPUR, "upazilaId" to 320, "qty" to 1)), "ph is off, ph2 is closed at night")
    }

    @Test
    fun `units fit the drug's form`() {
        assertEquals(listOf("strip", "pcs"), MedQty.unitsFor("Tablet"))
        assertEquals(listOf("strip", "pcs"), MedQty.unitsFor("Soft Gelatin Capsule"))
        assertEquals(listOf("bottle"), MedQty.unitsFor("Syrup"))
        assertEquals(listOf("bottle"), MedQty.unitsFor("Powder For Suspension"))
        assertEquals(listOf("vial"), MedQty.unitsFor("IV/IM Injection"))
        assertEquals(listOf("unit"), MedQty.unitsFor("Cream"))
        assertEquals(listOf("unit"), MedQty.unitsFor(""))
        assertEquals("strip", MedQty.unitFor("Tablet", "bottle"), "a unit that does not fit → the form's default")
        assertEquals("pcs", MedQty.unitFor("Capsule", "pcs"))
        assertEquals("৩ পাতা", MedQty.bn(3, "strip"))
        assertEquals("১০ পিস", MedQty.bn(10, "pcs"))
        assertEquals("২টি/ভায়াল", MedQty.bn(2, "vial"))
        assertEquals("১টি", MedQty.bn(1, "unit"))
        assertEquals("1 bottle", MedQty.en(1, "bottle"))
        assertEquals("3 strips", MedQty.en(3, "strip"))
    }

    @Test
    fun `units by form - a misfit unit becomes the default, syrups in bottles, capsules in pieces`() {
        setDhaka("2026-10-20 11:00")
        seed()
        val m2 = ok(pt, "med_find", mapOf("i" to idx("Montene", "Tablet"), "district" to GAZIPUR, "qty" to 2, "unit" to "bottle"))
        assertEquals("strip", row("SELECT unit FROM med_finds WHERE id = ?", m2["id"].textValue())["unit"])
        val m3 = ok(pt, "med_find", mapOf("i" to idx("Napa", "Syrup"), "district" to GAZIPUR, "qty" to 1))
        assertEquals("bottle", row("SELECT unit FROM med_finds WHERE id = ?", m3["id"].textValue())["unit"])
        assertEquals("Napa — ১ বোতল লাগবে। আপনার কাছে আছে?", lastPush(ph)!!["body"])
        val m4 = ok(pt, "med_find", mapOf("i" to idx("Sergel", "Capsule"), "district" to GAZIPUR, "qty" to 14, "unit" to "pcs"))
        assertEquals("pcs", row("SELECT unit FROM med_finds WHERE id = ?", m4["id"].textValue())["unit"])
        assertEquals("Sergel — ১৪ পিস লাগবে। আপনার কাছে আছে?", lastPush(ph)!!["body"])
        val m5 = ok(pt, "med_find", mapOf("i" to idx("Sergel", "Injection"), "district" to GAZIPUR, "qty" to 2))
        assertEquals("vial", row("SELECT unit FROM med_finds WHERE id = ?", m5["id"].textValue())["unit"])
        // a shop answering a pieces find answers in pieces
        ok(ph, "med_find_answer", mapOf("id" to m4["id"].textValue(), "haveQty" to 20, "unit" to "strip"))
        assertEquals("pcs", by(ok(ph, "med_find_requests"), m4["id"].textValue())["haveUnit"].textValue())
    }

    @Test
    fun `dismiss - hides a request from that shop only, the buyer sees nothing`() {
        setDhaka("2026-10-20 11:00")
        seed()
        val id = ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "upazilaId" to 320, "qty" to 2))["id"].textValue()
        val before = inbox(pt).size
        ok(ph, "med_find_answer", mapOf("id" to id, "dismiss" to true))
        assertFalse(ids(ok(ph, "med_find_requests")).contains(id), "gone from the shop's own list")
        assertTrue(ids(ok(ph2, "med_find_requests")).contains(id), "the other shop still has it")
        assertEquals(before, inbox(pt).size, "no push to the buyer")
        val mine = by(ok(pt, "med_finds_mine"), id)
        assertEquals(0, mine["have"].size())
        assertEquals(0, mine["answered"].intValue())
        // a stored "no" (an older server's answer) is not shown to the buyer either
        jdbc.update("UPDATE med_find_targets SET have = FALSE, answered_at = now() WHERE find_id = ? AND pharmacy_uid = ?", id, ph2)
        assertEquals(0, by(ok(pt, "med_finds_mine"), id)["have"].size())
        // dismiss on someone else's request / a bad id
        person("demo-pharmacy9")
        assertEquals(403, call("demo-pharmacy9", "med_find_answer", mapOf("id" to id, "dismiss" to true)).status, "not a pharmacy")
        shop("demo-pharmacy4", mapOf("district" to GAZIPUR))
        assertEquals(404, call("demo-pharmacy4", "med_find_answer", mapOf("id" to id, "dismiss" to true)).status, "not sent to this shop")
        assertEquals(400, call(ph, "med_find_answer", mapOf("id" to "../x", "dismiss" to true)).status)
    }

    @Test
    fun `a find stored before V692 (no amount) still lists and takes an answer`() {
        setDhaka("2026-10-20 11:00")
        seed()
        val now = clock.now()
        jdbc.update(
            "INSERT INTO med_finds (id, uid, district, upazila_id, drug_name, drug_form, created_at, expires_at) VALUES ('oldfind1', ?, ?, 320, 'Napa', 'Tablet', ?, ?)",
            pt, GAZIPUR, ts(now), ts(now + HOUR),
        )
        jdbc.update("INSERT INTO med_find_targets (find_id, pharmacy_uid) VALUES ('oldfind1', ?)", ph)
        val req = by(ok(ph, "med_find_requests"), "oldfind1")
        assertTrue(req["qty"].isNull && req["unit"].isNull)
        ok(ph, "med_find_answer", mapOf("id" to "oldfind1", "haveQty" to 3))
        val mine = by(ok(pt, "med_finds_mine"), "oldfind1")
        assertTrue(mine["qty"].isNull)
        assertEquals(3, mine["have"][0]["haveQty"].intValue())
        assertEquals("strip", mine["have"][0]["haveUnit"].textValue())
        assertFalse(mine["have"][0]["full"].booleanValue())
    }

    @Test
    fun `at night the 24-hour demo shop still gets the find (demo-night)`() {
        for (t in listOf("2026-10-19 22:45", "2026-10-19 23:59", "2026-10-20 00:00", "2026-10-20 03:30", "2026-10-20 05:55", "2026-10-20 11:00")) {
            cleanState()
            setDhaka(t)
            seed()
            assertTrue(ok(ph, "pharmacy_me")["pharmacy"]["openNow"].booleanValue(), t)
            val mf = ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "upazilaId" to 320, "qty" to 1))
            assertTrue(mf["ok"].booleanValue(), "$t: $mf")
            assertTrue(ok(ph, "med_find_requests")["items"].any { it["id"] == mf["id"] }, t)
        }
    }

    @Test
    fun `quotas unchanged - per shop at most 30 a day, at most 15 shops, the daily count restarts, 5 finds a day`() {
        setDhaka("2026-10-20 11:00")
        for (k in 0 until 17) {
            shop("demo-many$k", mapOf("district" to GAZIPUR, "upazila_id" to 320, "lat" to 24.0 + k * 0.001, "lng" to 90.42, "open24" to true))
        }
        person(pt)
        jdbc.update("UPDATE pharmacies SET find_day = '2026-10-20', find_count = 30 WHERE uid = 'demo-many0'")
        jdbc.update("UPDATE pharmacies SET find_day = '2026-10-19', find_count = 30 WHERE uid = 'demo-many1'")
        val mf = ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "upazilaId" to 320, "lat" to 24.0, "lng" to 90.42, "qty" to 2, "unit" to "strip"))
        assertEquals(15, mf["notified"].intValue())
        val targets = jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, mf["id"].textValue())
        assertFalse("demo-many0" in targets, "30 today already")
        assertTrue("demo-many1" in targets, "yesterday's count does not count")
        assertEquals(1, (row("SELECT find_count FROM pharmacies WHERE uid = 'demo-many1'")["find_count"] as Number).toInt())
        assertEquals(java.sql.Date.valueOf("2026-10-20"), row("SELECT find_day FROM pharmacies WHERE uid = 'demo-many1'")["find_day"])
        // a refused amount does not use the quota; then 5 finds a day
        repeat(3) { assertEquals(400, call(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "qty" to 0)).status) }
        repeat(4) { k -> ok(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "qty" to k + 1)) }
        assertEquals(429, call(pt, "med_find", mapOf("i" to montene(pt), "district" to GAZIPUR, "qty" to 1)).status)
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
