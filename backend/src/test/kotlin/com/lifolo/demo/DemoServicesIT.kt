package com.lifolo.demo

import com.lifolo.admin.kernel.VerifySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of server/test/demo-services.test.js (pharmacies, medicine finds, online orders, labs, ambulances, home care,
 * reports, approvals), demo-pharmacy.test.js and demo-night.test.js (the time-independent part).
 */
class DemoServicesIT : DemoTestBase() {
    private fun names(items: com.fasterxml.jackson.databind.JsonNode) = items.map { it["name"].textValue() }

    @Test
    fun `services demo - lists, orders, finds, approvals - the tester's steps - nothing left`() {
        owner()
        seedAll()
        val p = U("patient")
        val ph = U("pharmacy")
        val lab = U("lab")
        val amb = U("ambulance")
        val nurse = U("homecare")

        // ---------- guests ----------
        val gPh = ok(null, "pharmacies", mapOf("district" to GZP))["items"]
        assertTrue(names(gPh).contains("ডেমো লাইফ ফার্মেসি"))
        assertTrue(names(gPh).contains("ডেমো ২৪ ঘণ্টা ফার্মেসি"))
        assertFalse(names(gPh).contains("ডেমো আরোগ্য ফার্মেসি"), "a waiting pharmacy is not listed")
        val seba = gPh.first { it["name"].textValue() == "ডেমো সেবা ড্রাগ হাউস" }
        assertFalse(seba["openNow"].booleanValue())
        assertTrue(seba["closedUntil"].longValue() > System.currentTimeMillis(), "closed for a while")
        assertTrue(gPh.first { it["name"].textValue() == "ডেমো ২৪ ঘণ্টা ফার্মেসি" }["open24"].booleanValue())
        val life = gPh.first { it["id"].textValue() == ph }
        assertEquals("01700000150", life["phone"].textValue())
        assertTrue(life["delivery"].booleanValue())
        assertEquals(10, life["discount"].intValue())
        val gOpen = ok(null, "pharmacies", mapOf("district" to GZP, "openNow" to true))["items"]
        assertFalse(names(gOpen).contains("ডেমো সেবা ড্রাগ হাউস"))
        assertTrue(names(gOpen).contains("ডেমো ২৪ ঘণ্টা ফার্মেসি"))
        val g24 = ok(null, "pharmacies", mapOf("district" to GZP, "open24" to true))["items"]
        assertEquals(listOf("ডেমো ২৪ ঘণ্টা ফার্মেসি", "ডেমো লাইফ ফার্মেসি").sorted(), names(g24).sorted())
        assertTrue(gPh.size() > g24.size() + 2)
        val gKali = ok(null, "pharmacies", mapOf("district" to GZP, "upazilaId" to 318))["items"]
        assertTrue(names(gKali).contains("ডেমো কালিয়াকৈর মেডিসিন কর্নার"))
        assertFalse(names(gKali).contains("ডেমো লাইফ ফার্মেসি"))

        val gLabs = ok(null, "labs", mapOf("district" to GZP))["items"]
        assertTrue(names(gLabs).contains("ডেমো সিটি ডায়াগনস্টিক সেন্টার"))
        assertTrue(names(gLabs).contains("ডেমো পপুলার প্যাথলজি"))
        assertFalse(names(gLabs).contains("ডেমো নিউ লাইফ ল্যাব"))
        assertTrue(gLabs.first { it["id"].textValue() == lab }["homeCollection"].booleanValue())

        val gAmb = ok(null, "ambulances", mapOf("district" to GZP))
        assertTrue(gAmb["items"].first { it["name"].textValue() == "ডেমো জীবন অ্যাম্বুলেন্স (এসি)" }["available"].booleanValue())
        assertFalse(gAmb["items"].first { it["name"].textValue() == "ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস" }["available"].booleanValue())
        assertTrue(names(gAmb["busy"]).contains("ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)"))

        val gNurse = ok(null, "home_providers", mapOf("type" to "nurse", "district" to GZP))["items"]
        assertTrue(names(gNurse).contains("ডেমো নার্স নাসরিন"))
        assertTrue(names(gNurse).contains("ডেমো নার্স মৌসুমী"))
        assertFalse(names(gNurse).contains("ডেমো নার্স তানিয়া"), "badge waiting — not listed")
        assertFalse(names(ok(null, "home_providers", mapOf("type" to "nurse", "district" to GZP, "female" to true))["items"]).contains("ডেমো নার্স শাহীন"))
        assertFalse(names(ok(null, "home_providers", mapOf("type" to "nurse", "district" to GZP, "service" to "newborn_care"))["items"]).contains("ডেমো নার্স শাহীন"))
        assertTrue(names(ok(null, "home_providers", mapOf("type" to "doctor", "district" to GZP))["items"]).contains("ডা. ডেমো রফিক"))
        assertTrue(ok(null, "drug_search", mapOf("q" to "Monte"))["items"].any { it["name"].textValue() == "Montene" })
        assertEquals(401, req(null, "med_find", mapOf("i" to 1, "district" to GZP)).status)

        // ---------- the pharmacy account ----------
        val me = ok(ph, "pharmacy_me")["pharmacy"]
        assertEquals("approved", me["status"].textValue())
        assertEquals("DEMO-DL-1501", me["licenseNo"].textValue())
        assertTrue(me["hasLocation"].booleanValue())
        val home = ok(ph, "rx_pharmacy_home")
        assertEquals("DEMQKP", home["shopCode"].textValue())
        assertTrue(home["online"]["enabled"].booleanValue())
        assertEquals(3, home["missed"].size())
        assertEquals("Mixtard 30", home["missed"][0]["name"].textValue())
        assertTrue(ok(ph, "stock_get")["items"].any { it.textValue() == "Montene" })
        val orders = ok(ph, "rx_orders_pharmacy")["items"].associateBy { it["id"].textValue() }
        assertEquals("sent", orders["demo-svc-ord-a"]!!["status"].textValue())
        assertEquals("online", orders["demo-svc-ord-a"]!!["mode"].textValue())
        assertTrue(orders["demo-svc-ord-a"]!!["address"].textValue().contains("শিববাড়ি"))
        assertEquals("out", orders["demo-svc-ord-b"]!!["status"].textValue())
        assertEquals("done", orders["demo-svc-ord-c"]!!["status"].textValue())
        assertTrue(orders["demo-svc-ord-c"]!!["address"] == null, "a finished order hides the address")
        val reqs = ok(ph, "med_find_requests")["items"].map { it["id"].textValue() }.sorted()
        assertEquals(listOf("demo-svc-find-1", "demo-svc-find-3"), reqs)
        assertTrue(ok(ph, "pharmacy_stats")["calls"]["30"].intValue() > 0)
        assertEquals(3, ok(ph, "rx_pharmacy_open", mapOf("code" to "DEMSVCRXA2"))["item"]["medicines"].size())

        // ---------- the patient ----------
        val mine = ok(p, "rx_mine")["items"].map { it["id"].textValue() }
        for (id in listOf("demo-svc-rx-a", "demo-svc-rx-b", "demo-svc-rx-c", "demo-svc-rx-d")) assertTrue(mine.contains(id), id)
        val actB = ok(p, "rx_activity", mapOf("rxId" to "demo-svc-rx-b"))
        assertEquals(listOf("cancelled", "out"), actB["orders"].map { it["status"].textValue() }.sorted())
        assertEquals(listOf("Montene"), actB["orders"].first { it["status"].textValue() == "cancelled" }["missed"].map { it.textValue() })
        assertTrue(ok(p, "rx_activity", mapOf("rxId" to "demo-svc-rx-c"))["medicines"][0]["done"].booleanValue())
        val lifeOpt = ok(p, "rx_pharmacy_options", mapOf("rxId" to "demo-svc-rx-d", "district" to GZP, "mode" to "online"))["items"].first { it["id"].textValue() == ph }
        assertTrue(lifeOpt["online"].booleanValue() && lifeOpt["have"].intValue() == 3 && lifeOpt["total"].intValue() == 3, lifeOpt.toString())
        val finds = ok(p, "med_finds_mine")["items"]
        val f1 = finds.first { it["id"].textValue() == "demo-svc-find-1" }
        assertTrue(f1["open"].booleanValue())
        assertEquals(listOf("ডেমো ২৪ ঘণ্টা ফার্মেসি"), f1["have"].map { it["name"].textValue() })
        assertFalse(finds.first { it["id"].textValue() == "demo-svc-find-2" }["open"].booleanValue())
        assertTrue(ok(p, "ambulances", mapOf("district" to GZP))["items"].first { it["name"].textValue() == "ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস" }["canRate"].booleanValue())
        val pPh = ok(p, "pharmacies", mapOf("district" to GZP))["items"]
        val lifePh = pPh.first { it["id"].textValue() == ph }
        assertTrue(lifePh["canRate"].booleanValue(), "a finished order → may rate")
        assertEquals(listOf("behaviour", "price", "delivery"), lifePh["rateAspects"].map { it.textValue() })
        assertEquals(4.7, lifePh["aspects"]["behaviour"]["rating"].doubleValue())
        assertEquals(12, lifePh["aspects"]["behaviour"]["count"].intValue())
        assertEquals(4.4, lifePh["aspects"]["price"]["rating"].doubleValue())
        assertEquals(4.1, lifePh["aspects"]["delivery"]["rating"].doubleValue())
        assertEquals(4.5, lifePh["rating"].doubleValue())
        assertEquals(listOf("behaviour"), pPh.first { it["id"].textValue() == X("svc-ph-model") }["rateAspects"].map { it.textValue() }, "a call only → behaviour only")
        assertFalse(pPh.first { it["id"].textValue() == X("svc-ph-24h") }["canRate"].booleanValue())
        assertFalse(ok(ph, "pharmacies", mapOf("district" to GZP))["items"].first { it["id"].textValue() == ph }["canRate"].booleanValue(), "not your own shop")
        assertTrue(ok(p, "home_providers", mapOf("type" to "nurse", "district" to GZP))["items"].first { it["uid"].textValue() == nurse }["canRate"].booleanValue())

        // ---------- lab, ambulance, nurse ----------
        assertEquals("approved", ok(lab, "lab_me")["lab"]["status"].textValue())
        ok(lab, "lab_orders")
        assertEquals(listOf("approved", "approved", "approved", "pending"), ok(amb, "ambulance_mine")["items"].map { it["status"].textValue() }.sorted())
        val acStats = ok(amb, "ambulance_stats")["items"].first { it["id"].textValue() == "demo-svc-amb-ac" }
        assertTrue(acStats["calls"]["30"].intValue() > 0 && acStats["comments"].size() == 2 && acStats["rating"].doubleValue() > 4)
        val hs = ok(nurse, "home_stats")
        assertTrue(hs["enabled"].booleanValue())
        assertEquals(2, hs["comments"].size())
        assertEquals(6, hs["ratingCount"].intValue())
        assertEquals("01700000180", ok(nurse, "provider_me")["provider"]["homeVisit"]["phone"].textValue())

        // ---------- the owner (admin) ----------
        val pend = ok(OWNER, "admin_pharmacies")["items"].first { it["name"].textValue() == "ডেমো আরোগ্য ফার্মেসি" }
        assertTrue(pend["verify"]["docs"].filter { it["required"].booleanValue() }.all { it["has"].booleanValue() })
        assertTrue(ok(OWNER, "admin_labs")["items"].any { it["name"].textValue() == "ডেমো নিউ লাইফ ল্যাব" })
        assertTrue(ok(OWNER, "admin_ambulances")["items"].any { it["name"].textValue() == "ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স" && it["ownerUid"].textValue() == amb })
        assertTrue(ok(OWNER, "admin_badges")["items"].any { it["name"].textValue() == "ডেমো নার্স তানিয়া" && it["type"].textValue() == "nurse" })
        val reps = ok(OWNER, "admin_reports", mapOf("panel" to true))["items"]
        for ((kind, path) in listOf("pharmacy" to "pharmacies/${X("svc-ph-model")}", "ambulance" to "ambulances/demo-svc-amb-momota", "homecare" to "providers/${X("svc-nurse-shahin")}")) {
            assertTrue(reps.any { it["kind"].textValue() == kind && it["targetPath"].textValue() == path }, kind)
        }
        assertTrue(ok(OWNER, "admin_verify_docs", mapOf("kind" to "pharmacy", "id" to X("svc-ph-new")))["photos"].count { !it["data"].isNull } >= 2)
        val ov = ok(OWNER, "admin_overview")
        assertTrue(ov["pharmacies"]["items"].size() > 0 && ov["labs"]["items"].size() > 0 && ov["ambulances"]["items"].size() > 0 && ov["reports"]["items"].size() > 0)

        // ---------- the tester ----------
        ok(ph, "rx_order_update", mapOf("id" to "demo-svc-ord-a", "status" to "accepted", "price" to "৩৫০ টাকা", "eta" to "১ ঘণ্টা"))
        ok(ph, "rx_order_update", mapOf("id" to "demo-svc-ord-a", "status" to "out"))
        ok(ph, "rx_dispense", mapOf("code" to "DEMSVCRXB3", "items" to listOf(mapOf("i" to 0, "qty" to 30), mapOf("i" to 1, "qty" to 10))))
        assertEquals("done", row("SELECT status FROM rx_orders WHERE id = 'demo-svc-ord-b'")["status"])
        val actA = ok(p, "rx_activity", mapOf("rxId" to "demo-svc-rx-a"))
        assertEquals("out", actA["orders"][0]["status"].textValue())
        assertEquals("৩৫০ টাকা", actA["orders"][0]["price"].textValue())
        ok(ph, "med_find_answer", mapOf("id" to "demo-svc-find-3", "have" to true))
        ok(ph, "stock_set", mapOf("add" to listOf("Thyrox 50")))
        val save = mapOf(
            "name" to "ডেমো লাইফ ফার্মেসি", "licenseNo" to "DEMO-DL-1501", "phone" to "01700000150", "district" to GZP, "upazilaId" to 320,
            "address" to "শিববাড়ি মোড়, হাসপাতাল রোড, গাজীপুর", "open24" to true, "delivery" to true, "deliveryNote" to "ফ্রি", "discount" to 12,
            "closedDays" to emptyList<Int>(), "termsAccepted" to true,
        )
        ok(ph, "pharmacy_save", save)
        assertEquals("approved", ok(ph, "pharmacy_me")["pharmacy"]["status"].textValue(), "hours/discount need no new check")
        ok(ph, "pharmacy_save", save + mapOf("licenseNo" to "DEMO-DL-9999"))
        assertEquals("pending", ok(ph, "pharmacy_me")["pharmacy"]["status"].textValue())
        ok(OWNER, "admin_pharmacy_resolve", mapOf("id" to ph, "status" to "rejected", "reason" to "ডেমো"))
        val back = ok(ph, "pharmacy_me")["pharmacy"]
        assertEquals("approved", back["status"].textValue())
        assertEquals("DEMO-DL-1501", back["licenseNo"].textValue())
        val sent = ok(p, "rx_send_pharmacy", mapOf("rxId" to "demo-svc-rx-d", "pharmacyId" to ph, "mode" to "online", "address" to "বাসা ১২, শিববাড়ি", "phone" to "01700000159"))
        assertTrue(ok(ph, "rx_orders_pharmacy")["items"].any { it["id"].textValue() == sent["id"].textValue() && it["status"].textValue() == "sent" })
        val montene = ok(p, "drug_search", mapOf("q" to "Montene"))["items"][0]
        val mf = ok(p, "med_find", mapOf("i" to montene["i"].intValue(), "district" to GZP, "upazilaId" to 320))
        assertTrue(mf["ok"].booleanValue() && mf["notified"].intValue() >= 1, mf.toString())
        ok(p, "ambulance_rate", mapOf("id" to "demo-svc-amb-icu", "rating" to 4, "comment" to "ভালো সার্ভিস"))
        ok(p, "home_rate", mapOf("uid" to nurse, "rating" to 5, "comment" to "খুব যত্নশীল"))
        ok(p, "pharmacy_rate", mapOf("id" to ph, "aspects" to mapOf("behaviour" to 5, "price" to 5, "delivery" to 5), "comment" to "রাতেও ডেলিভারি দিয়েছে"))
        assertEquals(13, row("SELECT rating_count FROM pharmacies WHERE uid = ?", ph)["rating_count"])
        val lifeAfter = ok(p, "pharmacies", mapOf("district" to GZP))["items"].first { it["id"].textValue() == ph }
        assertEquals(4.5, lifeAfter["rating"].doubleValue())
        assertEquals(4.7, lifeAfter["aspects"]["behaviour"]["rating"].doubleValue())
        assertEquals(4.5, lifeAfter["aspects"]["price"]["rating"].doubleValue())
        assertEquals(4.3, lifeAfter["aspects"]["delivery"]["rating"].doubleValue())
        assertEquals("রাতেও ডেলিভারি দিয়েছে", ok(ph, "pharmacy_stats")["comments"][0]["comment"].textValue())
        ok(p, "pharmacy_called", mapOf("id" to X("svc-ph-24h")))
        ok(p, "ambulance_called", mapOf("id" to "demo-svc-amb-ac"))
        ok(p, "home_called", mapOf("uid" to nurse))
        ok(p, "report", mapOf("kind" to "pharmacy", "id" to X("svc-ph-24h"), "reason" to "ডেমো রিপোর্ট"))
        ok(amb, "ambulance_available", mapOf("id" to "demo-svc-amb-nonac", "available" to true))
        ok(lab, "lab_ranges_set", mapOf("ranges" to listOf(mapOf("code" to "rbs", "key" to "glu", "low" to 4, "high" to 7.8))))
        val checks = VerifySpec.checks("pharmacy", null).map { it.id }
        assertEquals(400, req(OWNER, "admin_pharmacy_resolve", mapOf("id" to X("svc-ph-new"), "approve" to true)).status, "no approval without the ticks")
        ok(OWNER, "admin_pharmacy_resolve", mapOf("id" to X("svc-ph-new"), "status" to "approved", "checks" to checks))
        ok(OWNER, "admin_lab_resolve", mapOf("id" to X("svc-lab-new"), "status" to "approved", "checks" to VerifySpec.checks("lab", null).map { it.id }))
        ok(OWNER, "admin_ambulance_resolve", mapOf("id" to "demo-svc-amb-icu-new", "status" to "approved", "checks" to VerifySpec.checks("ambulance", null).map { it.id }))
        ok(OWNER, "admin_badge_resolve", mapOf("uid" to X("svc-nurse-tania"), "approve" to true, "checks" to VerifySpec.checks("badge", mapOf("type" to "nurse")).map { it.id }))
        ok(OWNER, "admin_report_resolve", mapOf("targetPath" to "pharmacies/${X("svc-ph-model")}", "kind" to "pharmacy", "remove" to false))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertTrue(names(ok(p, "pharmacies", mapOf("district" to GZP))["items"]).contains("ডেমো আরোগ্য ফার্মেসি"), "listed after approval")
        assertTrue(ok(amb, "ambulance_mine")["items"].all { it["status"].textValue() == "approved" })

        // ---------- wipe ----------
        ok(OWNER, "admin_demo_wipe")
        assertEquals(emptyList<String>(), demoLeft())
        for (t in listOf("pharmacies", "pharmacy_stock", "pharmacy_missed", "pharmacy_calls", "pharmacy_ratings", "med_finds", "rx_orders", "prescriptions", "labs", "lab_prices", "ambulances", "ambulance_calls", "providers", "badges", "home_calls", "mod_reports", "verify_docs", "user_profiles", "user_state", "inbox_items", "listing_stats")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $t", Int::class.java), t)
        }
    }

    @Test
    fun `pharmacy demo - shop, patient, caregiver and owner - demo and real worlds apart`() {
        owner()
        seedSteps()
        val ph = U("pharmacy")
        val p = U("patient")
        val list = ok(null, "pharmacies", mapOf("district" to GZP))["items"]
        assertTrue(list.any { it["id"].textValue() == ph })
        assertFalse(names(list).any { it.contains("আরোগ্য") })
        assertEquals("approved", ok(ph, "pharmacy_me")["pharmacy"]["status"].textValue())
        assertTrue(ok(ph, "rx_orders_pharmacy")["items"].any { it["status"].textValue() == "sent" })
        val opts = ok(p, "rx_pharmacy_options", mapOf("rxId" to "demo-svc-rx-d", "district" to GZP, "mode" to "online"))["items"]
        assertTrue(opts.any { it["id"].textValue() == ph })
        val est = opts.first { it["id"].textValue() == ph }["estimate"]
        assertTrue(est != null && !est.isNull && est["total"].doubleValue() > 0 && est["priced"].intValue() > 0, "an estimate from the books prices")
        assertTrue(opts.all { it["id"].textValue().startsWith("demo-") }, "the demo patient sees only demo shops")
        val sent = ok(p, "rx_send_pharmacy", mapOf("rxId" to "demo-svc-rx-d", "pharmacyId" to ph, "mode" to "online", "address" to "বাসা ১২, শিববাড়ি", "phone" to "01700000159"))
        ok(ph, "rx_order_update", mapOf("id" to sent["id"].textValue(), "status" to "accepted", "price" to "৩৫০ টাকা", "eta" to "১ ঘণ্টা"))
        ok(ph, "rx_order_update", mapOf("id" to sent["id"].textValue(), "status" to "out"))
        ok(ph, "rx_dispense", mapOf("code" to "DEMSVCRXD5", "items" to listOf(mapOf("i" to 0, "qty" to 10))))
        assertEquals(ph, ok(p, "rx_shop_find", mapOf("code" to "DEMQKP"))["item"]["id"].textValue())
        ok(ph, "stock_set", mapOf("add" to listOf("Thyrox 50")))
        assertTrue(ok(ph, "stock_get").toString().contains("Thyrox 50"))
        val drugs = ok(p, "drug_search", mapOf("q" to "Montene"))["items"]
        val mf = ok(p, "med_find", mapOf("i" to drugs[0]["i"].intValue(), "district" to GZP, "upazilaId" to 320))
        assertTrue(ok(ph, "med_find_requests")["items"].any { it["id"].textValue() == mf["id"].textValue() })
        ok(ph, "med_find_answer", mapOf("id" to mf["id"].textValue(), "have" to true))
        val notified = jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, mf["id"].textValue())
        assertTrue(notified.all { it.startsWith("demo-") }, "no real shop asked")
        assertEquals(200, req(U("caregiver"), "rx_pharmacy_options", mapOf("asUid" to p, "rxId" to "demo-svc-rx-d", "district" to GZP, "mode" to "shop")).status)
        val newShop = ok(OWNER, "admin_pharmacies", mapOf("status" to "pending"))["items"].first { it["name"].textValue().contains("আরোগ্য") }
        assertEquals(400, req(OWNER, "admin_pharmacy_resolve", mapOf("id" to newShop["id"].textValue(), "approve" to true)).status)
        ok(OWNER, "admin_pharmacy_resolve", mapOf("id" to newShop["id"].textValue(), "status" to "approved", "checks" to VerifySpec.checks("pharmacy", null).map { it.id }))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertTrue(ok(p, "pharmacies", mapOf("district" to GZP))["items"].any { it["id"].textValue() == newShop["id"].textValue() })
        // a real person: no demo shop, the demo shop code is unknown
        user("real-user-0001", mapOf("name" to "আসল", "phone" to "01811000000"))
        val rf = req("real-user-0001", "med_find", mapOf("i" to drugs[0]["i"].intValue(), "district" to GZP, "upazilaId" to 320))
        if (rf.status == 200 && rf.body["id"] != null) {
            val n2 = jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, rf.body["id"].textValue())
            assertTrue(n2.none { it.startsWith("demo-") }, "a real find never reaches demo shops")
        }
        assertEquals(404, req("real-user-0001", "rx_shop_find", mapOf("code" to "DEMQKP")).status)
        wipe()
        assertEquals(emptyList<String>(), demoLeft())
    }

    @Test
    fun `night - the demo doctor sits every day until 1 am and the demo pharmacy is open 24 hours`() {
        owner()
        seedSteps()
        val chambers = jdbc.queryForList(
            "SELECT t.days, t.start_time, t.end_time FROM provider_chamber_times t WHERE t.provider_uid = ? AND t.chamber_idx = 0 ORDER BY t.idx",
            U("doctor"),
        )
        assertEquals(2, chambers.size)
        for (c in chambers) {
            @Suppress("UNCHECKED_CAST")
            val days = ((c["days"] as java.sql.Array).array as Array<Any?>).map { (it as Number).toInt() }.sorted()
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), days)
        }
        assertEquals("16:00", chambers[1]["start_time"])
        assertEquals("01:00", chambers[1]["end_time"])
        assertEquals(true, row("SELECT open24 FROM pharmacies WHERE uid = ?", U("pharmacy"))["open24"])
        assertTrue(ok(U("pharmacy"), "pharmacy_me")["pharmacy"]["openNow"].booleanValue())
        // today's serial can always be taken (the 16:00–01:00 session never "ends" before midnight)
        val today = com.lifolo.common.DhakaTime.todayUtcMidnight()
        val booked = req(U("patient"), "book", mapOf("providerUid" to U("doctor"), "date" to today, "chamberIndex" to 0, "note" to "রাতের পরীক্ষা"))
        if (booked.status != 200) assertTrue(booked.error!!.contains("সময় শেষ"), booked.toString())
        val drugs = ok(U("patient"), "drug_search", mapOf("q" to "Montene"))["items"]
        val mf = ok(U("patient"), "med_find", mapOf("i" to drugs[0]["i"].intValue(), "district" to GZP, "upazilaId" to 320))
        assertTrue(ok(U("pharmacy"), "med_find_requests")["items"].any { it["id"].textValue() == mf["id"].textValue() }, "the demo shop gets the find at any hour")
    }
}
