package com.lifolo.lab

import com.lifolo.rx.RxLabDeletionStep
import com.lifolo.rx.RxLabTest
import com.lifolo.rx.RxRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** server/test/labprices.test.js: price lists, the estimate and sort, home sample collection, privacy, demo, deletion */
class LabPricesIT : RxLabTest() {
    @Autowired lateinit var deletion: RxLabDeletionStep

    @Autowired lateinit var catalog: LabCatalog

    private val sadar = 466
    private val trishal = 463
    private val home = "বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ"
    private val today get() = RxRules.dhakaToday()

    private fun clearGuest() {
        redis.keys("guest:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
    }

    private val d = "lpDoctor"
    private val p = "lpPatient"
    private val cg = "lpCaregiver"
    private val x = "lpOther"
    private val l1 = "lpLabCity"
    private val l2 = "lpLabPopular"
    private val l3 = "lpLabNoList"
    private val l4 = "lpLabPending"
    private val l5 = "lpLabTrishal"
    private val l6 = "lpLabTshOnly"

    private fun seed() {
        person(d, "ডা. দাম")
        doctor(d, "ডা. দাম", "A-777")
        person(p, "রোগী রহিম", "01711000401")
        person(cg, "ছেলে সাকিব")
        person(x, "অন্য কেউ")
        for ((uid, name) in listOf(l1 to "সিটি ল্যাব", l2 to "পপুলার ল্যাব", l3 to "তালিকাহীন ল্যাব", l4 to "অপেক্ষার ল্যাব", l5 to "ত্রিশাল ল্যাব", l6 to "শুধু টিএসএইচ ল্যাব")) person(uid, name)
        lab(l1, "সিটি ল্যাব", upazilaId = sadar, homeCollection = true)
        lab(l2, "পপুলার ল্যাব", upazilaId = sadar)
        lab(l3, "তালিকাহীন ল্যাব", upazilaId = sadar, homeCollection = true)
        lab(l4, "অপেক্ষার ল্যাব", status = "pending", upazilaId = sadar)
        lab(l5, "ত্রিশাল ল্যাব", upazilaId = trishal)
        lab(l6, "শুধু টিএসএইচ ল্যাব", upazilaId = sadar)
        careLink(p, cg)
    }

    @Test
    fun `tests and prices, home sample collection - the whole path`() {
        seed()
        // ---------- 1. the price list: approved labs only, validated ----------
        val good = listOf(
            mapOf("code" to "cbc", "price" to 400, "tat" to "একই দিন"),
            mapOf("code" to "hba1c", "price" to "১,০০০"),
            mapOf("name" to "Lipid profile", "price" to 1200, "tat" to "একই দিন"),
            mapOf("name" to "Serum Vitamin B12", "price" to 1800, "tat" to "৪৮ ঘণ্টা"),
            mapOf("code" to "ecg", "price" to 400, "on" to false),
        )
        fails(p, "lab_prices_set", mapOf("items" to good), 403)
        fails(l4, "lab_prices_set", mapOf("items" to good), 403)
        fails(p, "lab_prices_get", mapOf(), 403)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc"))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 0))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to "চারশো"))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 400), mapOf("name" to "CBC", "price" to 300))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "no_such", "price" to 400))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to listOf(mapOf("name" to "", "price" to 400))), 400)
        fails(l1, "lab_prices_set", mapOf("items" to good, "discountPct" to 95), 400)
        fails(l1, "lab_prices_set", mapOf("items" to good, "homeFee" to -5), 400)
        fails(l1, "lab_prices_set", mapOf("items" to (0 until 201).map { mapOf("name" to "T$it", "price" to 10) }), 400)
        assertEquals("সর্বোচ্চ ২০০টা পরীক্ষা", fails(l1, "lab_prices_set", mapOf("items" to (0 until 201).map { mapOf("name" to "T$it", "price" to 10) }), 400)["error"].asText())
        fails(l1, "lab_prices_set", mapOf(), 400)

        val saved = ok(l1, "lab_prices_set", mapOf("items" to good, "discountPct" to 10, "homeFee" to 150))
        assertEquals(5, saved["items"].size())
        val lip = saved["items"].first { it["name"].asText() == "Lipid Profile" }
        assertEquals("lipid", lip["code"].asText())
        assertEquals(1000, saved["items"].first { it["code"].asText() == "hba1c" }["price"].intValue())
        assertTrue(saved["items"].first { it["name"].asText() == "Serum Vitamin B12" }["code"].isNull)
        assertEquals(false, saved["items"].first { it["code"].asText() == "ecg" }["on"].booleanValue())
        assertEquals(listOf("10", "150", "true"), listOf(saved["discountPct"].asText(), saved["homeFee"].asText(), saved["homeCollection"].asText()))
        assertEquals(saved["items"], ok(l1, "lab_prices_get")["items"])
        // no home collection → no home fee
        val s2 = ok(l2, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 350, "tat" to "একই দিন"), mapOf("code" to "hba1c", "price" to 900, "tat" to "২৪ ঘণ্টা")), "homeFee" to 100))
        assertTrue(s2["homeFee"].isNull)
        ok(l5, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 350), mapOf("code" to "hba1c", "price" to 900))))
        ok(l6, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "tsh", "price" to 800))))
        // all empty → the list goes
        ok(l3, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 100))))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lab_prices WHERE lab_uid = ?", Int::class.java, l3))
        ok(l3, "lab_prices_set", mapOf("items" to emptyList<Any>()))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_prices WHERE lab_uid = ?", Int::class.java, l3))

        // ---------- 2. the public list (guests too): a summary in the area list, the full list in the details ----------
        val pub = ok(null, "labs", mapOf("district" to dist))["items"]
        val s1 = pub.first { it["id"].asText() == l1 }["prices"]
        assertEquals(listOf(4, 400, 10, 150), listOf(s1["count"].intValue(), s1["from"].intValue(), s1["discountPct"].intValue(), s1["homeFee"].intValue()))
        val p1 = ok(null, "lab_prices_public", mapOf("labId" to l1))["prices"]
        assertEquals(
            listOf("CBC (Complete Blood Count)" to 400, "HbA1c" to 1000, "Lipid Profile" to 1200, "Serum Vitamin B12" to 1800),
            p1["items"].map { it["name"].asText() to it["price"].intValue() },
        )
        assertEquals(listOf(10, 150), listOf(p1["discountPct"].intValue(), p1["homeFee"].intValue()))
        assertEquals(p1, ok(x, "lab_prices_public", mapOf("labId" to l1))["prices"])
        assertTrue(pub.first { it["id"].asText() == l2 }["prices"]["homeFee"].isNull)
        assertTrue(pub.first { it["id"].asText() == l3 }["prices"].isNull)
        assertTrue(ok(null, "lab_prices_public", mapOf("labId" to l3))["prices"].isNull)
        fails(null, "lab_prices_public", mapOf("labId" to l4), 404)
        fails(null, "lab_prices_public", mapOf("labId" to "../x"), 404)
        assertFalse(pub.any { it["id"].asText() == l4 })

        // ---------- 3. a prescription: where how many, for how much — sorted ----------
        val pt = ok(d, "rx_patient_add", mapOf("name" to "রোগী রহিম", "age" to 52, "gender" to "male", "phone" to "01711000401"))["patient"]["id"].asText()
        val rx = ok(d, "rx_create", mapOf("rxPatientId" to pt, "tests" to listOf("CBC", "HbA1c", "Lipid profile", "ECG"), "advice" to "পরীক্ষা করে আসবেন"))
        val rxId = rx["id"].asText()
        ok(p, "rx_claim", mapOf("code" to rx["code"].asText(), "phone" to "01711000401"))

        var opt = ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist, "upazilaId" to sadar))
        assertTrue(opt["lastHome"].isNull)
        val o1 = opt["items"].first { it["id"].asText() == l1 }
        assertEquals("true 3 4 [\"ECG\"]", "${o1["priced"]} ${o1["have"]} ${o1["total"]} ${o1["missing"]}")
        assertEquals(listOf(2600, 10, 2340, 150), listOf(o1["subtotal"].intValue(), o1["discountPct"].intValue(), o1["estimate"].intValue(), o1["homeFee"].intValue()))
        assertEquals(true, o1["homeCollection"].booleanValue())
        assertEquals("একই দিন", o1["tat"].asText())
        assertEquals(listOf("CBC" to 400, "HbA1c" to 1000, "Lipid profile" to 1200), o1["items"].map { it["test"].asText() to it["price"].intValue() })
        val o2 = opt["items"].first { it["id"].asText() == l2 }
        assertEquals(listOf("2", "1250", "একই দিন / ২৪ ঘণ্টা"), listOf(o2["have"].asText(), o2["estimate"].asText(), o2["tat"].asText()))
        val o3 = opt["items"].first { it["id"].asText() == l3 }
        assertEquals("false null null", "${o3["priced"]} ${o3["have"]} ${o3["estimate"]}")
        assertFalse(opt["items"].any { it["id"].asText() == l4 })
        assertEquals(listOf(l1, l2, l5, l3, l6), ids(opt["items"]))
        opt = ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist, "upazilaId" to sadar, "tests" to listOf("CBC", "HbA1c")))
        assertEquals(listOf(l2 to 1250, l5 to 1250, l1 to 1260), opt["items"].take(3).map { it["id"].asText() to it["estimate"].intValue() })
        opt = ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist, "upazilaId" to trishal, "tests" to listOf("CBC", "HbA1c")))
        assertEquals(listOf(l5, l2), ids(opt["items"]).take(2))
        fails(x, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist), 404)
        fails(cg, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist), 404)
        fails(d, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist), 404)
        assertEquals(listOf(l1, l2, l5, l3, l6), ids(ok(cg, "rx_lab_options", mapOf("asUid" to p, "rxId" to rxId, "district" to dist, "upazilaId" to sadar))["items"]))
        fails(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to "কোথাও না"), 400)

        // ---------- 4. home collection: validation ----------
        val h = mapOf("address" to home, "phone" to "01711000401", "day" to 1, "slot" to "morning", "note" to "খালি পেটে থাকব")
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l2, "home" to h), 409)
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1, "home" to h + ("address" to "")), 400)
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1, "home" to h + ("phone" to "123")), 400)
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1, "home" to h + ("day" to 5)), 400)
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1, "home" to h + ("slot" to "night")), 400)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_orders", Int::class.java))

        // ---------- 5. sending: the prices are kept, the home part ----------
        val sent = ok(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1, "tests" to listOf("CBC", "HbA1c", "Lipid profile", "ECG"), "home" to h))["id"].asText()
        assertTrue(lastPush(l1)!!["title"]!!.contains("বাসা থেকে নমুনা"))
        var lo = ok(l1, "lab_orders")["items"].first { it["id"].asText() == sent }
        assertEquals("2340 2600 10 [\"ECG\"]", "${lo["prices"]["estimate"]} ${lo["prices"]["subtotal"]} ${lo["prices"]["discountPct"]} ${lo["prices"]["missing"]}")
        val hv = lo["home"]
        assertEquals(
            listOf("requested", "morning", "${today + DAY}", "150", home, "01711000401", "খালি পেটে থাকব"),
            listOf(hv["status"].asText(), hv["slot"].asText(), hv["date"].asText(), hv["fee"].asText(), hv["address"].asText(), hv["phone"].asText(), hv["note"].asText()),
        )
        fails(p, "rx_send_lab", mapOf("rxId" to rxId, "labId" to l1), 409)
        // a later price change keeps this order's estimate; a new estimate uses the new price
        ok(l1, "lab_prices_set", mapOf("items" to listOf(good[0] + ("price" to 500)) + good.drop(1), "discountPct" to 10, "homeFee" to 150))
        lo = ok(l1, "lab_orders")["items"].first { it["id"].asText() == sent }
        assertEquals(2340, lo["prices"]["estimate"].intValue())
        assertEquals(2430, ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist))["items"].first { it["id"].asText() == l1 }["estimate"].intValue())
        var act = ok(p, "rx_activity", mapOf("rxId" to rxId))
        assertEquals(2340, act["labOrders"][0]["prices"]["estimate"].intValue())
        assertEquals(home, act["labOrders"][0]["home"]["address"].asText())
        assertEquals("01711000401", ok(p, "lab_order_get", mapOf("id" to sent))["item"]["home"]["phone"].asText())
        val last = ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist))["lastHome"]
        assertEquals(home to "01711000401", last["address"].asText() to last["phone"].asText())

        // ---------- 6. privacy: no address/phone in lists or for the doctor ----------
        clearGuest()
        assertFalse(leaks(ok(null, "labs", mapOf("district" to dist)), home) || leaks(ok(x, "labs", mapOf("district" to dist)), "01711000401"))
        assertFalse(leaks(ok(l2, "lab_orders"), home))
        fails(l2, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "08:00"), 404)
        fails(x, "lab_order_get", mapOf("id" to sent), 404)
        val hist = ok(d, "rx_patient_history", mapOf("rxPatientId" to pt))
        assertFalse(leaks(hist, home) || leaks(hist, "01711000401"))
        assertFalse(leaks(ok(d, "rx_activity", mapOf("rxId" to rxId)), home))

        // ---------- 7. the lab confirms the time → the patient and the caregiver know ----------
        fails(p, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "08:00"), 403)
        fails(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "8 am"), 400)
        fails(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + 9 * DAY, "time" to "08:00"), 400)
        fails(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today - DAY, "time" to "08:00"), 400)
        fails(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "23:30"), 400)
        ok(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "08:00", "note" to "রাতে ১০টার পর কিছু খাবেন না"))
        assertEquals("সিটি ল্যাব কাল সকাল ৮টায় আসবে", lastPush(p)!!["title"])
        assertEquals("রাতে ১০টার পর কিছু খাবেন না", lastPush(p)!!["body"])
        assertEquals(rxId, lastPush(p)!!["id"])
        assertEquals("রোগী রহিম: সিটি ল্যাব কাল সকাল ৮টায় আসবে", lastPush(cg)!!["title"])
        act = ok(p, "rx_activity", mapOf("rxId" to rxId))
        val ch = act["labOrders"][0]["home"]
        assertEquals(listOf("confirmed", "08:00", "${today + DAY}", "রাতে ১০টার পর কিছু খাবেন না"), listOf(ch["status"].asText(), ch["time"].asText(), ch["visitDate"].asText(), ch["confirmNote"].asText()))
        ok(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "08:30"))
        assertEquals("সিটি ল্যাব কাল সকাল ৮টা ৩০ মিনিটে আসবে", lastPush(p)!!["title"])
        ok(l1, "lab_order_status", mapOf("id" to sent, "status" to "received"))
        fails(l1, "lab_home_confirm", mapOf("id" to sent, "date" to today + DAY, "time" to "09:00"), 409)
        fails(l1, "lab_home_decline", mapOf("id" to sent), 409)
        assertEquals(home, ok(l1, "lab_orders")["items"].first { it["id"].asText() == sent }["home"]["address"].asText())
        ok(l1, "lab_report", mapOf("id" to sent, "items" to listOf(mapOf("test" to "Haemoglobin", "result" to "12.5", "unit" to "g/dL", "range" to "13 – 17", "flag" to "low"))))
        val done = ok(l1, "lab_orders")["items"].first { it["id"].asText() == sent }
        assertNull(done["home"]["address"])
        assertEquals("confirmed", done["home"]["status"].asText())
        assertEquals("low", done["report"]["items"][0]["flag"].asText())
        assertEquals(home, ok(p, "lab_order_get", mapOf("id" to sent))["item"]["home"]["address"].asText())

        // ---------- 8. the caregiver asks for a home visit for the patient; the lab can't come ----------
        val rx2 = ok(d, "rx_create", mapOf("rxPatientId" to pt, "tests" to listOf("CBC")))
        val s3 = ok(cg, "rx_send_lab", mapOf("asUid" to p, "rxId" to rx2["id"].asText(), "labId" to l1, "home" to h + mapOf("slot" to "afternoon", "day" to 2)))["id"].asText()
        val o3doc = row("SELECT patient_uid, by_caretaker, home_date, price_estimate FROM lab_orders WHERE id = ?", s3)
        assertEquals(listOf(p, cg, "450"), listOf(o3doc["patient_uid"], o3doc["by_caretaker"], o3doc["price_estimate"].toString()))
        assertEquals(java.time.LocalDate.ofEpochDay((today + 2 * DAY) / DAY).toString(), o3doc["home_date"].toString())
        assertEquals(home, ok(cg, "lab_order_get", mapOf("asUid" to p, "id" to s3))["item"]["home"]["address"].asText())
        fails(cg, "lab_order_get", mapOf("id" to s3), 404)
        ok(l1, "lab_home_decline", mapOf("id" to s3, "note" to "ওই দিন লোক নেই"))
        assertEquals("সিটি ল্যাব বাসায় আসতে পারবে না — প্যাথলজিতে গিয়ে নমুনা দিন", lastPush(p)!!["title"])
        assertEquals("ওই দিন লোক নেই", lastPush(p)!!["body"])
        val dec = ok(l1, "lab_orders")["items"].first { it["id"].asText() == s3 }
        assertEquals(listOf("sent", "declined", "ওই দিন লোক নেই"), listOf(dec["status"].asText(), dec["home"]["status"].asText(), dec["home"]["declineNote"].asText()))
        assertNull(dec["home"]["address"])
        fails(l1, "lab_home_confirm", mapOf("id" to s3, "date" to today + DAY, "time" to "08:00"), 409)
        fails(l1, "lab_home_decline", mapOf("id" to s3), 409)
        ok(l1, "lab_order_status", mapOf("id" to s3, "status" to "received"))
        val rx3 = ok(d, "rx_create", mapOf("rxPatientId" to pt, "tests" to listOf("Thyroid profile")))
        val s4 = ok(p, "rx_send_lab", mapOf("rxId" to rx3["id"].asText(), "labId" to l3))["id"].asText()
        val o4 = ok(l3, "lab_orders")["items"].first { it["id"].asText() == s4 }
        assertTrue(o4["home"].isNull && o4["prices"].isNull)
        fails(l3, "lab_home_confirm", mapOf("id" to s4, "date" to today + DAY, "time" to "08:00"), 404)

        // ---------- 9. a paper prescription opened by code: the lab's own price list ----------
        val rx4 = ok(d, "rx_create", mapOf("rxPatientId" to pt, "tests" to listOf("HbA1c", "Serum Vitamin B12")))
        val walk = ok(l1, "lab_open_code", mapOf("code" to rx4["code"].asText()))["item"]
        assertEquals("2520 []", "${walk["prices"]["estimate"]} ${walk["prices"]["missing"]}")
        assertEquals("received", walk["status"].asText())
        assertEquals(walk["id"], ok(l1, "lab_open_code", mapOf("code" to rx4["code"].asText()))["item"]["id"])
        fails(l1, "lab_open_code", mapOf("code" to "ABC"), 400)
        fails(l1, "lab_open_code", mapOf("code" to "ZZZZZZZZZZ"), 404)

        // ---------- 10. demo and real stay apart ----------
        val dl = "demo-lp-lab"
        val dp = "demo-lp-patient"
        person(dp, "ডেমো রোগী", "01700000999")
        user(dl)
        lab(dl, "ডেমো ল্যাব", upazilaId = sadar, homeCollection = true)
        jdbc.update("INSERT INTO lab_prices (lab_uid, discount_pct, home_fee, updated_at) VALUES (?, NULL, 0, to_timestamp(0))", dl)
        jdbc.update("INSERT INTO lab_price_items (lab_uid, idx, code, name, price, tat, enabled) VALUES (?, 0, 'cbc', 'CBC (Complete Blood Count)', 10, '', TRUE)", dl)
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status, tests, patient_name) VALUES ('demo-lp-rx', 'DEMXLPRX22', 'demo-doctor', ?, 'active', '{CBC}', 'ডেমো রোগী')",
            dp,
        )
        assertFalse(ok(p, "rx_lab_options", mapOf("rxId" to rxId, "district" to dist))["items"].any { it["id"].asText() == dl })
        val dem = ok(dp, "rx_lab_options", mapOf("rxId" to "demo-lp-rx", "district" to dist))["items"]
        assertEquals(listOf("$dl 10 0"), dem.map { "${it["id"].asText()} ${it["estimate"]} ${it["homeFee"]}" })
        fails(p, "rx_send_lab", mapOf("rxId" to rx3["id"].asText(), "labId" to dl, "home" to h), 404)
        fails(dp, "rx_send_lab", mapOf("rxId" to "demo-lp-rx", "labId" to l1), 404)

        // ---------- 11. account deletion: the lab's prices and the patients' addresses; the patient's orders ----------
        deletion.deleteNow(l1, "l1@lp.test")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_prices WHERE lab_uid = ?", Int::class.java, l1))
        val left = row("SELECT home_address, home_phone, home_status FROM lab_orders WHERE id = ?", sent)
        assertEquals(listOf(null, null, "confirmed"), listOf(left["home_address"], left["home_phone"], left["home_status"]))
        deletion.deleteNow(p, "pat@lp.test")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_orders WHERE patient_uid = ?", Int::class.java, p))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_orders WHERE home_address = ? OR home_address LIKE '%চরপাড়া%'", Int::class.java, home))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_state WHERE lab_home_address IS NOT NULL", Int::class.java))
        assertTrue(jdbc.queryForList("SELECT patient_uid FROM prescriptions WHERE id = ?", String::class.java, rxId).single() == null, "the doctor's record stays without the patient")
    }

    @Test
    fun `after review - Bangla names, home requests, status rules, hidden templates, deletion`() {
        val dr = "lqDoctor"
        val pt = "lqPatient"
        val cgq = "lqCaregiver"
        val xo = "lqOther"
        val a = "lqLabA"
        val b = "lqLabB"
        val c = "lqLabC"
        val phone = "01711000501"
        person(dr, "ডা. পর্যালোচনা")
        doctor(dr, "ডা. পর্যালোচনা", "A-778")
        person(pt, "রোগী করিম", phone)
        person(cgq, "মেয়ে রিমা")
        person(xo, "অন্য")
        for ((u, n) in listOf(a to "আলফা ল্যাব", b to "বিটা ল্যাব", c to "ডাক্তারের বলা ল্যাব")) person(u, n)
        lab(a, "আলফা ল্যাব", upazilaId = sadar, homeCollection = true)
        lab(b, "বিটা ল্যাব", upazilaId = sadar)
        lab(c, "ডাক্তারের বলা ল্যাব", upazilaId = sadar, facilityId = "fac-lq-1")
        careLink(pt, cgq)

        // ---------- 1. own Bangla names: two different Bangla tests are not "twice"; punctuation does not matter ----------
        ok(a, "lab_prices_set", mapOf("items" to listOf(mapOf("name" to "রক্তের গ্রুপ", "price" to 150), mapOf("name" to "প্রস্রাবের পরীক্ষা", "price" to 200), mapOf("code" to "ecg", "price" to 300)), "homeFee" to 100))
        fails(a, "lab_prices_set", mapOf("items" to listOf(mapOf("name" to "রক্তের গ্রুপ", "price" to 150), mapOf("name" to "রক্তের-গ্রুপ!", "price" to 100))), 400)
        fails(a, "lab_prices_set", mapOf("items" to listOf(mapOf("name" to "— ?", "price" to 10))), 400)
        ok(b, "lab_prices_set", mapOf("items" to listOf(mapOf("code" to "cbc", "price" to 350), mapOf("code" to "tsh", "price" to 800))))
        val ptd = ok(dr, "rx_patient_add", mapOf("name" to "রোগী করিম", "age" to 40, "gender" to "male", "phone" to phone))["patient"]["id"].asText()
        fun mk(tests: List<String>, extra: Map<String, Any?> = emptyMap()): String {
            val r = ok(dr, "rx_create", mapOf("rxPatientId" to ptd, "tests" to tests) + extra)
            ok(pt, "rx_claim", mapOf("code" to r["code"].asText(), "phone" to phone))
            return r["id"].asText()
        }
        val rxBn = mk(listOf("রক্তের গ্রুপ", "ডেঙ্গু পরীক্ষা"))
        var opt = ok(pt, "rx_lab_options", mapOf("rxId" to rxBn, "district" to dist))
        val oa = opt["items"].first { it["id"].asText() == a }
        assertEquals("1 2 150 [\"ডেঙ্গু পরীক্ষা\"]", "${oa["have"]} ${oa["total"]} ${oa["estimate"]} ${oa["missing"]}")
        assertEquals(listOf("রক্তের গ্রুপ"), oa["items"].map { it["name"].asText() })

        // ---------- 3. none matched → no price (not ৳0), nothing kept on the order ----------
        val ob = opt["items"].first { it["id"].asText() == b }
        assertEquals("true 0 null null", "${ob["priced"]} ${ob["have"]} ${ob["estimate"]} ${ob["subtotal"]}")
        assertEquals(listOf("রক্তের গ্রুপ", "ডেঙ্গু পরীক্ষা"), ob["missing"].map { it.asText() })
        val toB = ok(pt, "rx_send_lab", mapOf("rxId" to rxBn, "labId" to b))["id"].asText()
        assertNull(row("SELECT price_estimate FROM lab_orders WHERE id = ?", toB)["price_estimate"])
        val toA = ok(pt, "rx_send_lab", mapOf("rxId" to rxBn, "labId" to a))["id"].asText()
        assertEquals(150, row("SELECT price_estimate FROM lab_orders WHERE id = ?", toA)["price_estimate"])
        assertEquals("ডেঙ্গু পরীক্ষা", ok(pt, "lab_order_get", mapOf("id" to toA))["item"]["prices"]["missing"][0].asText())

        // ---------- 8. one template counted once; the doctor's suggested lab first ----------
        val rxDup = mk(listOf("CBC", "CBC with ESR", "TSH"), mapOf("testPlaceId" to "fac-lq-1"))
        opt = ok(pt, "rx_lab_options", mapOf("rxId" to rxDup, "district" to dist, "upazilaId" to sadar))
        val od = opt["items"].first { it["id"].asText() == b }
        assertEquals("2 2 1150 []", "${od["have"]} ${od["total"]} ${od["estimate"]} ${od["missing"]}")
        assertEquals(c, opt["items"][0]["id"].asText())
        assertEquals(true, opt["items"][0]["suggested"].booleanValue())
        assertEquals(2, opt["items"][0]["total"].intValue())

        // ---------- 4. status rules: a cancelled order never reopens ----------
        fails(b, "lab_order_status", mapOf("id" to toB, "status" to "sent"), 404)
        ok(b, "lab_order_status", mapOf("id" to toB, "status" to "cancelled"))
        fails(b, "lab_order_status", mapOf("id" to toB, "status" to "received"), 409)
        fails(b, "lab_order_status", mapOf("id" to toB, "status" to "cancelled"), 409)
        ok(a, "lab_order_status", mapOf("id" to toA, "status" to "received"))
        fails(a, "lab_order_status", mapOf("id" to toA, "status" to "received"), 409)
        ok(a, "lab_order_status", mapOf("id" to toA, "status" to "cancelled"))
        fails(a, "lab_order_status", mapOf("id" to toA, "status" to "received"), 409)

        // ---------- 2. home: the useful words in the push (no phone), the last address kept ----------
        val addr = "বাসা ৭, রোড ২, চরপাড়া, ময়মনসিংহ"
        val h = mapOf("address" to addr, "phone" to phone, "day" to 1, "slot" to "morning", "note" to "")
        val rxH = mk(listOf("রক্তের গ্রুপ"))
        val h1 = ok(pt, "rx_send_lab", mapOf("rxId" to rxH, "labId" to a, "home" to h))["id"].asText()
        val pb = lastPush(a)!!
        assertEquals("বাসা থেকে নমুনা · কাল সকাল ৯–১২ · চরপাড়া… · রোগী করিম · রক্তের গ্রুপ", pb["body"])
        assertFalse(pb["body"]!!.contains(phone) || pb["body"]!!.contains("রোড ২"))
        assertEquals(addr, row("SELECT lab_home_address FROM user_state WHERE uid = ?", pt)["lab_home_address"])
        jdbc.update("UPDATE user_state SET lab_home_address = 'নতুন ঠিকানা', lab_home_phone = '01711000502' WHERE uid = ?", pt)
        val lh = ok(pt, "rx_lab_options", mapOf("rxId" to rxH, "district" to dist))["lastHome"]
        assertEquals("নতুন ঠিকানা" to "01711000502", lh["address"].asText() to lh["phone"].asText())

        // ---------- 2. the asked time is over → "expired" on both sides; the patient changes / cancels ----------
        jdbc.update("UPDATE lab_orders SET home_date = home_date - 2 WHERE id = ?", h1)
        assertEquals("expired", ok(a, "lab_orders")["items"].first { it["id"].asText() == h1 }["home"]["status"].asText())
        assertEquals("expired", ok(pt, "rx_activity", mapOf("rxId" to rxH))["labOrders"][0]["home"]["status"].asText())
        assertEquals("expired", ok(pt, "lab_order_get", mapOf("id" to h1))["item"]["home"]["status"].asText())
        fails(xo, "lab_home_change", mapOf("id" to h1, "home" to h), 404)
        fails(a, "lab_home_change", mapOf("id" to h1, "home" to h), 404)
        fails(pt, "lab_home_change", mapOf("id" to h1, "home" to h + ("slot" to "night")), 400)
        ok(pt, "lab_home_change", mapOf("id" to h1, "home" to h + mapOf("day" to 2, "slot" to "afternoon", "address" to "বাসা ৯, কাঁচিঝুলি")))
        val cur = ok(a, "lab_orders")["items"].first { it["id"].asText() == h1 }["home"]
        assertEquals(
            listOf("requested", "${today + 2 * DAY}", "afternoon", "বাসা ৯, কাঁচিঝুলি", "100"),
            listOf(cur["status"].asText(), cur["date"].asText(), cur["slot"].asText(), cur["address"].asText(), cur["fee"].asText()),
        )
        assertEquals("রোগী বাসা থেকে নমুনার সময় বদলেছেন", lastPush(a)!!["title"])
        assertEquals("রোগী করিম · বাসা থেকে নমুনা · পরশু বিকেল ৩–৬ · কাঁচিঝুলি…", lastPush(a)!!["body"])
        fails(cgq, "lab_home_cancel", mapOf("id" to h1), 404)
        ok(cgq, "lab_home_cancel", mapOf("asUid" to pt, "id" to h1))
        val after = row("SELECT status, home_status, home_address FROM lab_orders WHERE id = ?", h1)
        assertEquals(listOf("sent", null, null), listOf(after["status"], after["home_status"], after["home_address"]))
        assertEquals("বাসায় যেতে হবে না — রোগী প্যাথলজিতে এসে নমুনা দেবেন", lastPush(a)!!["title"])
        fails(pt, "lab_home_cancel", mapOf("id" to h1), 404)
        // once the lab told the time the patient can't change/cancel (they call)
        val rxH2 = mk(listOf("প্রস্রাবের পরীক্ষা"))
        val h2 = ok(pt, "rx_send_lab", mapOf("rxId" to rxH2, "labId" to a, "home" to h))["id"].asText()
        ok(a, "lab_home_confirm", mapOf("id" to h2, "date" to today + DAY, "time" to "09:30"))
        fails(pt, "lab_home_change", mapOf("id" to h2, "home" to h), 409)
        fails(pt, "lab_home_cancel", mapOf("id" to h2), 409)

        // ---------- 4. rx_activity: the address by order (its patient), not by the prescription owner ----------
        user("lqSomeoneElse")
        jdbc.update(
            """
            INSERT INTO lab_orders (id, rx_id, code, lab_uid, lab_name, patient_uid, patient_name, doctor_uid, tests, status, home_status, home_date,
              home_slot, home_address, home_phone)
            SELECT 'lq-foreign', rx_id, code, lab_uid, lab_name, 'lqSomeoneElse', patient_name, doctor_uid, tests, status, home_status, home_date,
              home_slot, 'অন্যের বাসা', '01711000599' FROM lab_orders WHERE id = ?
            """.trimIndent(),
            h2,
        )
        val act = ok(pt, "rx_activity", mapOf("rxId" to rxH2))
        assertEquals(addr, act["labOrders"].first { it["id"].asText() == h2 }["home"]["address"].asText())
        val foreign = act["labOrders"].first { it["id"].asText() == "lq-foreign" }
        assertTrue(foreign["home"]["address"] == null && !leaks(act, "অন্যের বাসা"))
        jdbc.update("DELETE FROM lab_orders WHERE id = 'lq-foreign'")

        // ---------- 5. a hidden template: not in public lists, the save still works (kept as an own name) ----------
        assertTrue(ok(null, "lab_prices_public", mapOf("labId" to a))["prices"]["items"].any { it["code"].asText() == "ecg" })
        catalog.save(listOf(LabTests.DEFAULTS.first { LabTests.code(it) == "ecg" }.deepCopy().put("hidden", true)))
        clearGuest()
        val hidPub = ok(null, "lab_prices_public", mapOf("labId" to a))["prices"]
        assertFalse(hidPub["items"].any { it["code"].asText() == "ecg" })
        assertEquals(2, ok(null, "labs", mapOf("district" to dist))["items"].first { it["id"].asText() == a }["prices"]["count"].intValue())
        val ownerItems = ok(a, "lab_prices_get")["items"]
        assertEquals(true, ownerItems.first { it["code"].asText() == "ecg" }["hidden"].booleanValue())
        val kept = ok(a, "lab_prices_set", mapOf("items" to mapper.convertValue(ownerItems, List::class.java), "homeFee" to 100))
        assertEquals(listOf("ECG"), kept["kept"].map { it.asText() })
        assertEquals("""{"code":null,"name":"ECG","price":300,"tat":"","on":true}""", kept["items"].first { it["name"].asText() == "ECG" }.toString())
        assertTrue(ok(null, "lab_prices_public", mapOf("labId" to a))["prices"]["items"].any { it["name"].asText() == "ECG" && it["code"].isNull })
        jdbc.update("DELETE FROM lab_test_templates")
        clearGuest()

        // ---------- 6. the daily limit ends rx_lab_options ----------
        jdbc.update(
            "INSERT INTO user_quotas (uid, kind, day, count) VALUES (?, 'rxPharm', ?, 300) ON CONFLICT (uid, kind) DO UPDATE SET day = EXCLUDED.day, count = 300",
            pt, java.sql.Date.valueOf(com.lifolo.common.DhakaTime.day()),
        )
        fails(pt, "rx_lab_options", mapOf("rxId" to rxH, "district" to dist), 429)

        // ---------- 7. the lab removed: its price list and the open orders' addresses go ----------
        assertEquals(addr, row("SELECT home_address FROM lab_orders WHERE id = ?", h2)["home_address"])
        ok(a, "lab_delete")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_prices WHERE lab_uid = ?", Int::class.java, a))
        val h2doc = row("SELECT home_address, home_phone, home_status FROM lab_orders WHERE id = ?", h2)
        assertEquals(listOf(null, null, "confirmed"), listOf(h2doc["home_address"], h2doc["home_phone"], h2doc["home_status"]))
        fails(null, "lab_prices_public", mapOf("labId" to a), 404)

        // ---------- 7. account deletion: many orders — every address goes ----------
        jdbc.update(
            """
            INSERT INTO lab_orders (id, rx_id, code, lab_uid, patient_uid, tests, status, created_at, home_status, home_date, home_slot, home_address, home_phone)
            SELECT 'lq-bulk-' || lpad(i::text, 4, '0'), ?, 'X', ?, ?, '{CBC}', CASE WHEN i % 50 = 0 THEN 'sent' ELSE 'reported' END, to_timestamp(i),
              'requested', CURRENT_DATE, 'early', 'বাসা ' || i || ', চরপাড়া', '01711000501'
            FROM generate_series(0, 699) AS i
            """.trimIndent(),
            rxDup, b, pt,
        )
        deletion.deleteNow(b, "b@lq.test")
        assertEquals(700, jdbc.queryForObject("SELECT count(*) FROM lab_orders WHERE id LIKE 'lq-bulk-%'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_orders WHERE id LIKE 'lq-bulk-%' AND (home_address IS NOT NULL OR home_phone IS NOT NULL)", Int::class.java))
    }
}
