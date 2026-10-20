package com.lifolo.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.ApiException
import com.lifolo.contracts.Prescriptions
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import com.lifolo.lab.LabHome
import com.lifolo.lab.LabPrices
import com.lifolo.lab.LabTests
import com.lifolo.lab.LabViews
import com.lifolo.lab.PriceItem
import com.lifolo.lab.PriceList
import com.lifolo.rx.RxRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** server/test/prescriptions.test.js, labtests.test.js, worksegs.test.js (pure parts) and the labprices.js helpers */
class RxLabRulesTest {
    private val mapper = ObjectMapper()
    private fun json(s: String) = mapper.readTree(s)
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(y, m, d, h, min)).toEpochMilli()

    // ------------------------------------------------------------------ prescriptions.test.js

    @Test
    fun `phone key - same for every form of a number, the number itself not inside`() {
        val a = RxRules.phoneKey("01712345678", "s")!!
        assertTrue(a.startsWith("p_"))
        assertEquals(a, RxRules.phoneKey("+8801712345678", "s"))
        assertFalse(a.contains("1712345678"))
        assertNull(RxRules.phoneKey("123", "s"))
    }

    @Test
    fun `phone key - byte-identical to Node (HMAC-SHA256 of rx-phone, 40 hex)`() {
        // node -e "require('crypto').createHmac('sha256','rx').update('rx-phone:8801711000001').digest('hex').slice(0,40)"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("rx".toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal("rx-phone:8801711000001".toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
        assertEquals("p_$hex", RxRules.phoneKey("01711-000001", "rx"))
    }

    @Test
    fun `Dhaka today as UTC midnight`() {
        // 1 am on 17 October in Dhaka = 7 pm on 16 October UTC
        assertEquals(utc(2026, 10, 17), RxRules.dhakaToday(utc(2026, 10, 16, 19, 0)))
        assertEquals(utc(2026, 10, 16), RxRules.dhakaToday(utc(2026, 10, 16, 17, 59)))
    }

    @Test
    fun `medicines are validated`() {
        val m = RxRules.parseMedicines("en", json("""[{"name":" Napa ","strength":"500 mg","form":"Tablet","dose":"1 + 0 + 1","timing":"after","days":5}]"""))
        assertEquals(RxMedicine(0, "Napa", "Tablet", "500 mg", "1+0+1", "after", 5, ""), m[0])
        assertEquals(0, RxRules.parseMedicines("en", json("""[{"name":"X","days":0}]"""))[0].days)
        assertThrows(ApiException::class.java) { RxRules.parseMedicines("en", json("""[{"name":""}]""")) }
        assertThrows(ApiException::class.java) { RxRules.parseMedicines("en", json("""[{"name":"X","days":400}]""")) }
        assertThrows(ApiException::class.java) { RxRules.parseMedicines("en", json("""[{"name":"X","days":2.5}]""")) }
        assertThrows(ApiException::class.java) { RxRules.parseMedicines("en", json("null")) }
        assertEquals("", RxRules.parseMedicines("en", json("""[{"name":"X","timing":"weird"}]"""))[0].timing)
        assertTrue(listOf("1+0+1", "0+0+1", "½+0+½", "1+1+1+1").all { RxRules.DOSE_RE.matches(it) })
        val many = mapper.createArrayNode().apply { repeat(26) { add(mapper.createObjectNode().put("name", "M$it")) } }
        assertEquals("Up to 25 medicines", assertThrows(ApiException::class.java) { RxRules.parseMedicines("en", many) }.message)
        assertEquals(25, RxRules.parseMedicines("en", mapper.createArrayNode().apply { repeat(25) { add(mapper.createObjectNode().put("name", "M$it")) } }).size)
    }

    @Test
    fun `patient and tests`() {
        assertEquals(RxRules.Patient("Karim", 40, "male"), RxRules.parsePatient("en", json("""{"name":"Karim","age":40,"gender":"male"}""")))
        assertNull(RxRules.parsePatient("en", json("""{"name":"K","age":""}""")).age)
        assertThrows(ApiException::class.java) { RxRules.parsePatient("en", json("""{"name":""}""")) }
        assertThrows(ApiException::class.java) { RxRules.parsePatient("en", json("""{"name":"K","age":200}""")) }
        assertEquals(listOf("CBC", "RBS"), RxRules.parseTests("en", json("""["CBC", " CBC ", "", "RBS"]""")))
        assertEquals(listOf<String>(), RxRules.parseTests("en", null))
        assertThrows(ApiException::class.java) { RxRules.parseTests("en", json("\"CBC\"")) }
        // a patient override on top of the appointment/register data
        val merged = RxRules.mergePatient(linkedMapOf("name" to "Rahim", "age" to 50, "gender" to "male"), json("""{"age":51}"""))
        assertEquals(RxRules.Patient("Rahim", 51, "male"), RxRules.parsePatient("en", merged))
    }

    @Test
    fun `verification code`() {
        assertTrue(Prescriptions.CODE_RE.matches("ABCDEFGH23"))
        assertFalse(Prescriptions.CODE_RE.matches("ABCDEFGH01")) // no 0 and 1 (mixed up with O/I)
        assertFalse(Prescriptions.CODE_RE.matches("abc"))
        repeat(50) { assertTrue(Prescriptions.CODE_RE.matches(RxRules.newCode())) }
    }

    @Test
    fun `medicine arithmetic - per day, needed, left, refill date`() {
        assertEquals(2.0, RxRules.perDay("1+0+1"))
        assertEquals(1.0, RxRules.perDay("½+0+½"))
        assertEquals(0.0, RxRules.perDay("প্রয়োজনে"))
        assertEquals(15, RxRules.needQty(RxMedicine(0, "x", "Tablet", "", "1+1+1", "", 5, "")))
        assertNull(RxRules.needQty(RxMedicine(0, "x", "Tablet", "", "1+0+1", "", 0, ""))) // continue
        assertNull(RxRules.needQty(RxMedicine(0, "x", "Syrup", "", "1+0+1", "", 5, ""))) // not countable
        val day = utc(2026, 10, 10, 6) // noon on 10 October in Dhaka
        val meds = listOf(RxMedicine(0, "Napa", "Tablet", "", "1+1+1", "", 5, ""), RxMedicine(1, "Losectil", "Capsule", "", "1+0+0", "", 0, ""))
        val disp = mutableListOf(RxDispensing("ph", "দোকান", day, listOf(0 to 9, 1 to 30)))
        val st = RxRules.medStatus(meds, disp)
        assertEquals(listOf(15, 9, 6, false), listOf(st[0].need, st[0].got, st[0].left, st[0].done))
        assertEquals(listOf(null, 30, null), listOf(st[1].need, st[1].got, st[1].left))
        // Napa 9 = 3 days → runs out on 13 October, reminder on the 11th; Losectil 30 days later
        assertEquals(utc(2026, 10, 11), RxRules.refillDate(meds, disp, day))
        // the whole course bought: not for Napa any more
        disp.add(RxDispensing("ph", "দোকান", day, listOf(0 to 6)))
        assertEquals(utc(2026, 11, 7), RxRules.refillDate(meds, disp, day))
    }

    // ------------------------------------------------------------------ labtests.test.js

    private val tests get() = LabTests.merge(null)

    @Test
    fun `the doctor's test name finds the template`() {
        val l = tests
        assertEquals("cbc", LabTests.match("CBC", l)?.let { LabTests.code(it) })
        assertEquals("cbc", LabTests.match("CBC with ESR", l)?.let { LabTests.code(it) })
        assertEquals("creat", LabTests.match("S. Creatinine", l)?.let { LabTests.code(it) })
        assertEquals("creat", LabTests.match("Serum Creatinine", l)?.let { LabTests.code(it) })
        assertEquals("urine_re", LabTests.match("Urine R/E", l)?.let { LabTests.code(it) })
        assertEquals("urine_re", LabTests.match("urine r/e", l)?.let { LabTests.code(it) })
        assertEquals("hba1c", LabTests.match("HbA1c", l)?.let { LabTests.code(it) })
        assertEquals("cxr", LabTests.match("X-ray chest P/A view", l)?.let { LabTests.code(it) })
        assertNull(LabTests.match("Something unknown", l))
        for (t in listOf("CBC", "RBS", "FBS", "HbA1c", "Lipid profile", "S. Creatinine", "SGPT", "Urine R/E", "X-ray chest P/A", "ECG", "USG of whole abdomen", "TSH")) {
            assertNotNull(LabTests.match(t, l), t)
        }
    }

    @Test
    fun `no name is shared by two templates`() {
        val seen = HashMap<String, String>()
        for (t in LabTests.DEFAULTS) {
            val c = LabTests.code(t)
            for (k in (listOf(c, LabTests.name(t)) + LabTests.aliases(t)).map { LabTests.norm(it) }) {
                assertTrue(seen[k] == null || seen[k] == c, "$k: ${seen[k]} / $c")
                seen[k] = c
            }
            val keys = t.get("params").map { it.get("key").asText() }
            assertEquals(keys.size, keys.toSet().size, c)
        }
    }

    @Test
    fun `ranges by gender and high-low flags`() {
        val hb = LabTests.DEFAULTS.first { LabTests.code(it) == "cbc" }.get("params").first { it.get("key").asText() == "hb" }
        assertEquals(LabTests.Range(12.0, 15.0, "12 – 15"), LabTests.rangeFor(hb, "female"))
        assertEquals(LabTests.Range(13.0, 17.0, "13 – 17"), LabTests.rangeFor(hb, "male"))
        assertEquals("M: 13 – 17, F: 12 – 15", LabTests.rangeFor(hb, "").range)
        assertEquals("low", LabTests.flagOf("11.2", 12.0, 15.0))
        assertEquals("high", LabTests.flagOf("16", 12.0, 15.0))
        assertEquals("", LabTests.flagOf("13", 12.0, 15.0))
        assertEquals("high", LabTests.flagOf("210", null, 200.0))
        assertEquals("", LabTests.flagOf("Negative", null, 200.0))
        assertEquals("high", LabTests.flagOf("1,250", 150.0, 410.0))
    }

    @Test
    fun `the lab's form uses its own range`() {
        val f = LabTests.formFor("FBS", "male", mapOf("fbs.glu_f" to (3.5 to 6.0)), tests)
        assertEquals("fbs", f["code"])
        @Suppress("UNCHECKED_CAST")
        val p0 = (f["params"] as List<Map<String, Any?>>)[0]
        assertEquals(3.5, p0["low"])
        assertEquals("3.5 – 6", p0["range"])
        val none = LabTests.formFor("Bone scan", "male", emptyMap(), tests)
        assertNull(none["code"])
        assertEquals(emptyList<Any>(), none["params"])
    }

    @Test
    fun `the admin's template is validated`() {
        val t = LabTests.cleanTest("en", json("""{"name":"Serum Magnesium","aliases":"s magnesium, magnesium","params":[{"name":"Magnesium","unit":"mg/dL","r":["1.7","2.2"]}]}"""), emptySet())
        assertEquals("serum_magnesium", t.get("code").asText())
        assertEquals("[1.7,2.2]", t.get("params")[0].get("r").toString())
        assertEquals("""["s magnesium","magnesium"]""", t.get("aliases").toString())
        assertTrue(assertThrows(ApiException::class.java) { LabTests.cleanTest("en", json("""{"name":"X","params":[{"name":"A","r":[5,2]}]}"""), emptySet()) }.message!!.contains("normal range"))
        assertTrue(assertThrows(ApiException::class.java) { LabTests.cleanTest("en", json("""{"name":"X","params":[]}"""), emptySet()) }.message!!.contains("rows"))
        assertEquals("serum_magnesium_2", LabTests.cleanTest("en", json("""{"name":"Serum Magnesium","params":[{"name":"A"}]}"""), setOf("serum_magnesium")).get("code").asText())
        assertEquals("[2,null]", LabTests.cleanTest("en", json("""{"name":"Y","params":[{"name":"A","r":[2,""]}]}"""), emptySet()).get("params")[0].get("r").toString())
    }

    // ------------------------------------------------------------------ worksegs.test.js (lab parts)

    @Test
    fun `lab segments new - active - reported - closed`() {
        assertEquals("new", LabViews.labSeg("sent", null))
        assertEquals("new", LabViews.labSeg("sent", "requested"))
        assertEquals("new", LabViews.labSeg("sent", "declined"))
        assertEquals("active", LabViews.labSeg("sent", "confirmed"))
        assertEquals("active", LabViews.labSeg("received", null))
        assertEquals("reported", LabViews.labSeg("reported", null))
        assertEquals("closed", LabViews.labSeg("cancelled", null))
        assertEquals(mapOf("new" to 1, "active" to 0, "closed" to 2), LabViews.segCounts(listOf("new", "active", "closed"), listOf("new", "closed", "closed")))
    }

    @Test
    fun `the list limit keeps an old open order among many closed ones`() {
        data class O(val id: String, val createdAt: Long, val open: Boolean)
        val list = listOf(O("old-open", 1, true)) + (0 until 30).map { O("c$it", 100L + it, false) }
        val out = LabViews.recentFirstOpen(list, { it.createdAt }, { it.open }, 10)
        assertEquals(10, out.size)
        assertTrue(out.any { it.id == "old-open" })
        assertEquals("c29", out[0].id)
        assertEquals("old-open", out.last().id)
    }

    // ------------------------------------------------------------------ labprices.js helpers

    @Test
    fun `prices as numbers or text with Bangla digits`() {
        assertEquals(1000.0, LabPrices.toInt(json("\"১,০০০\"")))
        assertEquals(400.0, LabPrices.toInt(json("400")))
        assertTrue(LabPrices.toInt(json("\"চারশো\""))!!.isNaN())
        assertTrue(LabPrices.toInt(json("2.5"))!!.isNaN())
        assertNull(LabPrices.toInt(json("\"\"")))
        assertNull(LabPrices.toInt(null))
        assertEquals(150.0, LabPrices.toInt(json("\"৳ 150\"")))
    }

    @Test
    fun `own test names keep Bangla - punctuation does not matter, empty never matches`() {
        assertEquals(LabPrices.nameKey("রক্তের গ্রুপ"), LabPrices.nameKey("রক্তের-গ্রুপ!"))
        assertFalse(LabPrices.nameKey("রক্তের গ্রুপ") == LabPrices.nameKey("ডেঙ্গু পরীক্ষা"))
        assertEquals("", LabPrices.nameKey("— ?"))
        assertEquals("b12 test", LabPrices.nameKey("B১২ Test"))
    }

    @Test
    fun `estimate - matched tests, missing ones, discount, one template counted once`() {
        val l = tests
        val x = PriceList(listOf(PriceItem("cbc", "CBC", 400, "একই দিন", true), PriceItem("ecg", "ECG", 300, "", false), PriceItem(null, "রক্তের গ্রুপ", 150, "", true)), 10, 100, 1)
        val e = LabPrices.estimate(listOf("CBC", "CBC with ESR", "ECG", "রক্তের গ্রুপ"), x, true, l)!!
        assertEquals(listOf("CBC", "রক্তের গ্রুপ"), e.items.map { it.test })
        assertEquals(listOf("ECG"), e.missing)
        assertEquals(listOf(2, 3, 550, 495, 100), listOf(e.have, e.total, e.subtotal, e.estimate, e.homeFee))
        assertNull(LabPrices.estimate(listOf("CBC"), x, false, l)!!.homeFee)
        val none = LabPrices.estimate(listOf("TSH"), x, true, l)!!
        assertEquals(listOf(0, null, null), listOf(none.have, none.estimate, none.subtotal))
        assertNull(LabPrices.snapshot(none))
        assertNull(LabPrices.estimate(listOf("CBC"), PriceList(emptyList(), null, null, 1), true, l))
        assertEquals(2, LabPrices.countTests(listOf("CBC", "CBC with ESR", "TSH"), l))
    }

    @Test
    fun `home collection words and the short address in pushes`() {
        val now = utc(2026, 10, 19, 3) // 9 am in Dhaka
        val today = RxRules.dhakaToday(now)
        val day = 24 * 3600 * 1000L
        assertEquals("tomorrow at 8:00 AM" to "কাল সকাল ৮টায়", LabPrices.visitWords(today + day, "08:00", now))
        assertEquals("কাল সকাল ৮টা ৩০ মিনিটে", LabPrices.visitWords(today + day, "08:30", now).second)
        assertEquals("চরপাড়া…", LabPrices.shortAddress("বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ"))
        val h = LabHome("requested", today + day, "morning", "", null, "বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ", "01711000501", now)
        assertEquals("বাসা থেকে নমুনা · কাল সকাল ৯–১২ · চরপাড়া…", LabPrices.homePushLine(h, now)!!.second)
        assertEquals("Sample from home · tomorrow 9 AM–12 PM · চরপাড়া…", LabPrices.homePushLine(h, now)!!.first)
        // the asked slot is over and the lab did not answer → expired (shown, not stored)
        assertFalse(LabPrices.homeExpired(h, now))
        assertTrue(LabPrices.homeExpired(h.copy(date = today - day), now))
        assertEquals("expired", LabPrices.homeView(h.copy(date = today - day), false, now)!!["status"])
        assertFalse(LabPrices.homeView(h, false, now)!!.containsKey("address"))
        assertEquals("01711000501", LabPrices.homeView(h, true, now)!!["phone"])
    }

    @Test
    fun `the lab's visit time - within 3 days, 5 am to 10 pm, not in the past`() {
        val now = utc(2026, 10, 19, 3)
        val today = RxRules.dhakaToday(now)
        val day = 24 * 3600 * 1000L
        val v = LabPrices.cleanVisit("en", json("${today + day}"), json("\"08:00\""), now)
        assertEquals(today + day + 8 * 3600_000L - 6 * 3600_000L, v.at)
        for ((d, t) in listOf((today + day) to "8 am", (today + 9 * day) to "08:00", (today - day) to "08:00", (today + day) to "23:30", today to "07:00")) {
            assertThrows(ApiException::class.java, { LabPrices.cleanVisit("en", json("$d"), json("\"$t\""), now) }, "$d $t")
        }
        // the patient's request: day 0–2, a slot that is not over today
        assertEquals("morning", LabPrices.cleanHome("en", json("""{"address":"বাসা","phone":"01711000401","day":0,"slot":"morning"}"""), true, now)!!.slot)
        assertThrows(ApiException::class.java) { LabPrices.cleanHome("en", json("""{"address":"বাসা","phone":"01711000401","day":0,"slot":"early"}"""), true, now) }
        assertEquals(409, assertThrows(ApiException::class.java) { LabPrices.cleanHome("en", json("""{"address":"বাসা"}"""), false, now) }.status)
        assertNull(LabPrices.cleanHome("en", null, false, now))
    }
}
