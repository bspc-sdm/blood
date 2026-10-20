package com.lifolo.demo

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.common.DhakaTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of server/test/demo-rx.test.js and demo-lab.test.js (doctors, serials, prescriptions, assistant, pharmacy, lab) */
class DemoRxIT : DemoTestBase() {
    private val codes = DemoRx.CODES

    @Test
    fun `rx demo - doctors, serials, prescriptions, assistant, pharmacy, lab - read, work, wipe`() {
        owner()
        assertTrue(seedAll()["docs"].intValue() > 50)
        val doc = U("doctor")
        val asst = U("assistant")
        val pt = U("patient")
        val ph = U("pharmacy")
        val lab = U("lab")
        val today = DhakaTime.todayUtcMidnight()

        // ---------- the doctor list (guests too) ----------
        val list = ok(null, "providers", mapOf("type" to "doctor", "district" to GZP, "homeVisit" to false))["items"]
        val names = list.map { it["name"].textValue() }
        for (n in listOf("ডা. ডেমো হাসান", "ডা. ডেমো নাসরিন আক্তার", "ডা. ডেমো কামরুল ইসলাম", "ডা. ডেমো মাহবুব আলম", "ডা. ডেমো ফারজানা হক")) assertTrue(names.contains(n), n)
        assertFalse(names.contains("ডা. ডেমো তানভীর রহমান"), "a doctor waiting for verification is not listed")
        val hasan = list.first { it["uid"].textValue() == doc }
        assertEquals(2, hasan["chambers"].size())
        assertTrue(hasan["appointments"].booleanValue() && hasan["chambers"].all { it["appointments"].booleanValue() })
        assertFalse(list.first { it["name"].textValue() == "ডা. ডেমো ফারজানা হক" }["appointments"].booleanValue())
        val cardio = ok(null, "providers", mapOf("type" to "doctor", "district" to GZP, "specialty" to "cardiology", "homeVisit" to false))["items"]
        assertEquals(listOf("ডা. ডেমো মাহবুব আলম"), cardio.map { it["name"].textValue() })

        // ---------- the doctor ----------
        val me = ok(doc, "me")
        assertEquals("doctor", me["badge"]["type"].textValue())
        assertEquals("approved", me["badge"]["status"].textValue())
        assertTrue(me["badge"]["regNo"].textValue().startsWith("DEMO-"))
        assertEquals(2, ok(doc, "rx_me")["chambers"].size())
        val docAppts = ok(doc, "my_appointments")["items"].filter { it["role"].textValue() == "provider" }
        val todays = docAppts.filter { it["date"].longValue() == today }
        assertEquals(listOf(1, 2, 3, 4), todays.filter { it["status"].textValue() == "confirmed" }.map { it["serial"].intValue() }.sorted())
        assertEquals(1, todays.count { it["status"].textValue() == "pending" })
        assertEquals(1, todays.count { it["status"].textValue() == "cancelled" })
        assertTrue(todays.first { it["serial"]?.intValue() == 3 }["otherPhone"]?.textValue()?.isNotEmpty() == true, "a confirmed patient's phone")
        assertTrue(docAppts.any { it["date"].longValue() > today && it["status"].textValue() == "pending" })
        assertTrue(docAppts.any { it["status"].textValue() == "completed" })
        assertTrue(docAppts.any { it["status"].textValue() == "declined" })

        // the patient: every state
        val patAppts = ok(pt, "my_appointments")["items"].filter { it["role"].textValue() == "patient" }
        val st = patAppts.map { it["status"].textValue() }.toSet()
        for (x in listOf("confirmed", "pending", "completed", "expired", "declined", "cancelled")) assertTrue(st.contains(x), x)
        assertTrue(patAppts.none { it["date"].longValue() == today }, "nothing today — the tester books")
        assertTrue(patAppts.any { it["status"].textValue() == "confirmed" && it["serial"]?.intValue() == 2 && it["otherPhone"]?.textValue() == "01200000213" })

        // ---------- the assistant ----------
        assertEquals(listOf(doc), ok(asst, "rx_assisting")["items"].map { it["doctorUid"].textValue() })
        val rp = ok(asst, "rx_patients", mapOf("doctorUid" to doc))
        assertEquals("assistant", rp["role"].textValue())
        assertTrue(rp["appointments"].count { it["date"].longValue() == today && it["serial"]?.isNull == false } >= 4)
        assertTrue(rp["patients"].any { it["name"].textValue() == "ডেমো রোগী জমিলা খাতুন" })
        assertTrue(rp["appPatients"].any { it["name"].textValue() == "ডেমো রোগী রহিম" })
        assertEquals(403, req(asst, "rx_create", mapOf("doctorUid" to doc, "appointmentId" to "demo-rx-appt-03", "advice" to "x")).status)
        assertEquals(403, req(X("rx-pat-salma"), "rx_patients", mapOf("doctorUid" to doc)).status)

        // ---------- prescriptions ----------
        val docList = ok(doc, "rx_doctor_list")["items"]
        assertTrue(docList.size() >= 9)
        assertTrue(docList.any { it["code"].textValue() == codes["C"] && it["status"].textValue() == "cancelled" })
        val mineRx = ok(pt, "rx_mine")["items"]
        val rxCodes = mineRx.map { it["code"].textValue() }
        for (c in listOf("A", "B", "C")) assertTrue(rxCodes.contains(codes[c]), c)
        assertFalse(rxCodes.contains(codes["PAPER"]), "the paper one only after adding it by code")
        val rxB = mineRx.first { it["code"].textValue() == codes["B"] }
        assertEquals("DEMO-A-10001", rxB["doctor"]["regNo"].textValue())
        assertTrue(rxB["followUpDate"].longValue() > today)
        val act = ok(pt, "rx_activity", mapOf("rxId" to rxB["id"].textValue()))
        assertEquals(
            listOf(listOf("Comet", 30, 60), listOf("Rosuva", 30, 30), listOf("Bislol", 0, 30), listOf("Napa", 0, 9)),
            act["medicines"].map { listOf(it["name"].textValue(), it["got"].intValue(), it["need"].intValue()) },
        )
        assertEquals(listOf("done"), act["orders"].map { it["status"].textValue() })
        assertEquals(listOf("received", "reported"), act["labOrders"].map { it["status"].textValue() }.sorted())
        assertEquals("৩০০ টাকা", act["reviews"].first { it["status"].textValue() == "claimed" }["fee"].textValue())
        assertTrue(act["nextOpen"].longValue() >= today)
        val rep = ok(pt, "lab_order_get", mapOf("id" to "demo-rx-lab-me-lipid"))
        assertTrue(rep["item"]["report"]["items"].any { it["code"].textValue() == "lipid" && it["key"].textValue() == "ldl" && it["flag"].textValue() == "high" })
        assertEquals("152", rep["history"]["lipid.ldl"][0]["result"].textValue(), "the earlier result beside it")

        // ---------- pharmacy ----------
        val orders = ok(ph, "rx_orders_pharmacy")["items"]
        val shopIn = orders.first { it["id"].textValue() == "demo-rx-order-nasima" }
        assertEquals("sent", shopIn["status"].textValue())
        assertEquals("shop", shopIn["mode"].textValue())
        val online = orders.first { it["id"].textValue() == "demo-rx-order-karim-online" }
        assertEquals("online", online["mode"].textValue())
        assertTrue(!online["address"].isNull && !online["phone"].isNull)
        val home = ok(ph, "rx_pharmacy_home")
        assertTrue(home["online"]["enabled"].booleanValue())
        assertTrue(home["missed"].any { it["name"].textValue().startsWith("Thyrox") })
        assertEquals("cancelled", ok(ph, "rx_pharmacy_open", mapOf("code" to codes["C"]))["item"]["status"].textValue())
        assertTrue(ok(pt, "rx_pharmacy_options", mapOf("rxId" to rxB["id"].textValue(), "district" to GZP, "mode" to "shop"))["items"].any { it["id"].textValue() == ph })

        // ---------- lab ----------
        val lst = ok(lab, "lab_orders")["items"].map { it["status"].textValue() }.toSet()
        for (x in listOf("sent", "received", "reported", "cancelled")) assertTrue(lst.contains(x), x)
        assertTrue(ok(null, "labs", mapOf("district" to GZP))["items"].any { it["id"].textValue() == lab })

        // ---------- report reviews (the doctor) ----------
        val rst = ok(doc, "reviews_doctor")["items"].map { it["status"].textValue() }.toSet()
        for (x in listOf("done", "claimed", "paid", "unpaid")) assertTrue(rst.contains(x), x)

        // ---------- the owner: waiting for verification ----------
        val pend = ok(OWNER, "admin_badges", mapOf("status" to "pending", "panel" to true))["items"]
        val tanvir = pend.first { it["name"].textValue() == "ডা. ডেমো তানভীর রহমান" }
        assertEquals("doctor", tanvir["type"].textValue())
        assertTrue(pend.any { it["name"].textValue() == "ডা. ডেমো কামরুল ইসলাম" && it["change"]?.get("regNo")?.textValue() == "DEMO-A-55555" })

        // =================== the patient's whole journey ===================
        val ch1 = hasan["chambers"][0]
        var b = req(pt, "book", mapOf("providerUid" to doc, "chamberIndex" to 0, "date" to today, "note" to "সুগার বেশি, মাথা ঘোরে", "chamberKey" to ch1["hospitalId"].textValue()))
        if (b.status != 200) {
            assertTrue(b.error!!.contains("সময় শেষ"), b.toString())
            b = req(pt, "book", mapOf("providerUid" to doc, "chamberIndex" to 0, "date" to today + DAY, "note" to "সুগার বেশি, মাথা ঘোরে", "chamberKey" to ch1["hospitalId"].textValue()))
        }
        assertEquals(200, b.status, b.toString())
        val apptId = b.body["id"].textValue()
        val apDate = DhakaTime.toUtcMidnightMs((row("SELECT visit_date FROM appointments WHERE id = ?", apptId)["visit_date"] as java.sql.Date).toLocalDate())
        assertEquals(409, req(pt, "book", mapOf("providerUid" to doc, "chamberIndex" to 0, "date" to apDate, "note" to "আবার")).status, "not twice a day")
        assertEquals(400, req(asst, "appointment_update", mapOf("id" to apptId, "op" to "confirm")).status, "the assistant cannot confirm")
        ok(doc, "appointment_update", mapOf("id" to apptId, "op" to "confirm", "providerNote" to "সিরিয়াল এলে ফোন করা হবে"))
        val myA = ok(pt, "my_appointments")["items"].first { it["id"].textValue() == apptId }
        assertEquals("confirmed", myA["status"].textValue())
        assertTrue(myA["serial"].intValue() >= 1 && !myA["otherPhone"].isNull)
        assertTrue(ok(asst, "rx_patients", mapOf("doctorUid" to doc))["appointments"].any { it["id"].textValue() == apptId && it["serial"].intValue() == myA["serial"].intValue() })
        val made = ok(
            doc, "rx_create",
            mapOf(
                "appointmentId" to apptId, "chamberIndex" to 0, "complaints" to "সুগার বেশি, মাথা ঘোরে", "findings" to mapOf("bp" to "140/90"), "diagnosis" to "Type 2 DM",
                "medicines" to listOf(
                    mapOf("name" to "Comet", "form" to "Tablet", "strength" to "500 mg", "dose" to "1+0+1", "days" to 30, "timing" to "after"),
                    mapOf("name" to "Napa", "form" to "Tablet", "strength" to "500 mg", "dose" to "1+1+1", "days" to 3, "timing" to "after"),
                ),
                "tests" to listOf("CBC", "HbA1c"), "advice" to "মিষ্টি বাদ", "followUpDays" to 15,
            ),
        )
        val madeId = made["id"].textValue()
        assertEquals(listOf("CBC", "HbA1c"), ok(pt, "rx_mine")["items"].first { it["id"].textValue() == madeId }["tests"].map { it.textValue() })
        val onl = ok(pt, "rx_pharmacy_options", mapOf("rxId" to madeId, "district" to GZP, "upazilaId" to 320, "mode" to "online"))["items"].first { it["id"].textValue() == ph }
        assertTrue(onl["online"].booleanValue() && onl["have"].intValue() == 2, onl.toString())
        val ord = ok(pt, "rx_send_pharmacy", mapOf("rxId" to madeId, "pharmacyId" to ph, "mode" to "online", "address" to "ডেমো বাসা ৫, শিববাড়ি", "phone" to "01200000101"))
        ok(ph, "rx_order_update", mapOf("id" to ord["id"].textValue(), "status" to "accepted", "price" to "৪২০ টাকা", "eta" to "এক ঘণ্টায়"))
        ok(ph, "rx_order_update", mapOf("id" to ord["id"].textValue(), "status" to "out"))
        ok(ph, "rx_dispense", mapOf("code" to made["code"].textValue(), "items" to listOf(mapOf("i" to 0, "qty" to 60), mapOf("i" to 1, "qty" to 9))))
        val a1 = ok(pt, "rx_activity", mapOf("rxId" to madeId))
        assertEquals("done", a1["orders"][0]["status"].textValue())
        assertTrue(a1["medicines"].all { it["done"].booleanValue() })
        ok(pt, "rx_send_lab", mapOf("rxId" to madeId, "labId" to lab, "tests" to listOf("CBC", "HbA1c")))
        assertEquals(409, req(pt, "rx_send_lab", mapOf("rxId" to madeId, "labId" to lab)).status)
        val lo = ok(lab, "lab_orders")["items"].first { it["rxId"].textValue() == madeId }
        val loId = lo["id"].textValue()
        ok(lab, "lab_order_status", mapOf("id" to loId, "status" to "received"))
        val forms = ok(lab, "lab_form", mapOf("id" to loId))["forms"]
        assertEquals(listOf("cbc", "hba1c"), forms.map { it["code"].textValue() })
        val vals = mapOf("hb" to "13.2", "wbc" to "7.5", "plt" to "250", "hba1c" to "7.9")
        val items = forms.flatMap { f: JsonNode ->
            f["params"].filter { vals[it["key"].textValue()] != null }.map { p ->
                mapOf(
                    "test" to p["name"].textValue(), "result" to vals[p["key"].textValue()], "unit" to p["unit"].textValue(), "group" to f["name"].textValue(),
                    "code" to f["code"].textValue(), "key" to p["key"].textValue(), "low" to p["low"]?.takeIf { !it.isNull }?.doubleValue(),
                    "high" to p["high"]?.takeIf { !it.isNull }?.doubleValue(),
                )
            }
        }
        ok(lab, "lab_report", mapOf("id" to loId, "items" to items, "note" to "ডেমো রিপোর্ট"))
        val pr = ok(pt, "lab_order_get", mapOf("id" to loId))
        assertEquals("high", pr["item"]["report"]["items"].first { it["key"].textValue() == "hba1c" }["flag"].textValue())
        assertEquals("8.4", pr["history"]["hba1c.hba1c"][0]["result"].textValue())
        assertEquals(402, req(doc, "lab_order_get", mapOf("id" to loId)).status, "the doctor reads after the fee")
        ok(pt, "review_pay", mapOf("id" to loId, "trxId" to "DEMO9X8Y7Z", "method" to "bkash"))
        assertTrue(ok(asst, "reviews_doctor", mapOf("doctorUid" to doc))["items"].any { it["id"].textValue() == loId && it["status"].textValue() == "claimed" && it["trxId"].textValue() == "DEMO9X8Y7Z" })
        ok(asst, "review_confirm", mapOf("doctorUid" to doc, "id" to loId, "ok" to true))
        val dr = ok(doc, "lab_order_get", mapOf("id" to loId))
        assertTrue(dr["item"]["report"]["items"].size() >= 4)
        assertEquals("8.4", dr["history"]["hba1c.hba1c"][0]["result"].textValue())
        ok(doc, "review_done", mapOf("id" to loId, "comment" to "HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।"))
        assertEquals(
            "HbA1c একটু বেশি, ওষুধ চলবে। ১৫ দিন পর আসবেন।",
            ok(pt, "rx_activity", mapOf("rxId" to madeId))["reviews"].first { it["labOrderId"].textValue() == loId }["comment"].textValue(),
        )
        val h = ok(doc, "rx_patient_history", mapOf("appointmentId" to apptId))
        assertTrue(h["items"].any { it["id"].textValue() == madeId } && h["items"].any { it["code"].textValue() == codes["B"] })
        val hl = h["labOrders"].associateBy { it["id"].textValue() }
        assertTrue(hl[loId]!!["open"].booleanValue() && hl[loId]!!["review"].textValue() == "done" && hl[loId]!!["report"]["items"].size() >= 4)
        assertTrue(hl["demo-rx-lab-me-old"]!!["open"].booleanValue() && !hl["demo-rx-lab-me-old"]!!["comment"].isNull)
        assertTrue(!hl["demo-rx-lab-me-lipid"]!!["open"].booleanValue() && hl["demo-rx-lab-me-lipid"]!!["review"].textValue() == "claimed" && hl["demo-rx-lab-me-lipid"]!!["report"].isNull)
        assertEquals("received", hl["demo-rx-lab-me-cbc"]!!["status"].textValue())
        val karimAppt = todays.first { it["serial"]?.intValue() == 2 }["id"].textValue()
        assertTrue(ok(doc, "rx_patient_history", mapOf("appointmentId" to karimAppt))["labOrders"].any { it["id"].textValue() == "demo-rx-lab-karim" && it["review"].textValue() == "unpaid" && it["report"].isNull })

        // =================== a few more steps ===================
        val habib = todays.first { it["status"].textValue() == "pending" }
        ok(doc, "appointment_update", mapOf("id" to habib["id"].textValue(), "op" to "confirm", "providerNote" to "সন্ধ্যা ৭টায় আসবেন"))
        assertTrue((row("SELECT serial FROM appointments WHERE id = ?", habib["id"].textValue())["serial"] as Int) >= 5, "after today's earlier serials")
        ok(pt, "rx_send_pharmacy", mapOf("rxId" to rxB["id"].textValue(), "pharmacyId" to ph, "mode" to "shop"))
        val disp = ok(ph, "rx_dispense", mapOf("code" to codes["B"], "items" to listOf(mapOf("i" to 0, "qty" to 30), mapOf("i" to 2, "qty" to 30))))
        assertTrue(disp["item"]["medicines"][0]["done"].booleanValue() && disp["item"]["medicines"][2]["done"].booleanValue())
        assertEquals(409, req(ph, "rx_dispense", mapOf("code" to codes["C"], "items" to listOf(mapOf("i" to 0, "qty" to 1)))).status, "not on a cancelled prescription")
        ok(ph, "rx_order_update", mapOf("id" to "demo-rx-order-karim-online", "status" to "accepted", "price" to "৮৫০ টাকা", "eta" to "আজ রাত ৮টা"))
        ok(asst, "review_confirm", mapOf("doctorUid" to doc, "id" to "demo-rx-lab-me-lipid", "ok" to true))
        ok(doc, "review_done", mapOf("id" to "demo-rx-lab-me-lipid", "comment" to "কোলেস্টেরল কমছে, ওষুধ চলবে।"))
        ok(doc, "review_done", mapOf("id" to "demo-rx-lab-salma-tsh", "comment" to "TSH বেশি — Thyrox 75 চলবে।"))
        ok(lab, "lab_order_status", mapOf("id" to "demo-rx-lab-salma-new", "status" to "received"))
        val phone = row("SELECT phone FROM user_profiles WHERE uid = ?", pt)["phone"] as String
        assertEquals(400, req(pt, "rx_claim", mapOf("code" to codes["PAPER"], "phone" to "01799999999")).status)
        assertEquals(1, ok(pt, "rx_claim", mapOf("code" to codes["PAPER"], "phone" to phone))["added"].intValue())
        assertTrue(ok(pt, "rx_mine")["items"].any { it["code"].textValue() == codes["PAPER"] })
        ok(asst, "rx_patient_add", mapOf("doctorUid" to doc, "name" to "ডেমো নতুন রোগী", "age" to 30, "gender" to "male", "phone" to "01200000399"))
        ok(doc, "rx_team_set", mapOf("emails" to emptyList<String>()))
        assertEquals(0, ok(asst, "rx_assisting")["items"].size())
        assertEquals(403, req(asst, "rx_patients", mapOf("doctorUid" to doc)).status)
        ok(doc, "rx_team_set", mapOf("emails" to listOf("assistant@lifolo-demo.test")))
        assertEquals(1, ok(asst, "rx_assisting")["items"].size())
        assertEquals(400, req(OWNER, "admin_badge_resolve", mapOf("uid" to tanvir["uid"].textValue(), "approve" to true, "checks" to emptyList<String>())).status)
        ok(OWNER, "admin_badge_resolve", mapOf("uid" to tanvir["uid"].textValue(), "approve" to true, "checks" to tanvir["verify"]["checks"].map { it["id"].textValue() }))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertTrue(ok(pt, "providers", mapOf("type" to "doctor", "district" to GZP, "homeVisit" to false))["items"].any { it["name"].textValue() == "ডা. ডেমো তানভীর রহমান" })

        // =================== wipe ===================
        val w = ok(OWNER, "admin_demo_wipe")
        assertTrue(w["users"].intValue() >= 10)
        assertEquals(emptyList<String>(), demoLeft())
        for (t in listOf("appointments", "rx_orders", "lab_orders", "rx_reviews", "prescriptions", "rx_patients", "serial_counters", "queues")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $t", Int::class.java), t)
        }
    }

    @Test
    fun `lab demo - prices, home collection, walk-in code, approval`() {
        owner()
        seedSteps()
        val lab = U("lab")
        val p = U("patient")
        val doc = U("doctor")
        val pub = ok(null, "labs", mapOf("district" to GZP))["items"]
        assertFalse(pub.any { it["name"].textValue().contains("নিউ লাইফ") }, "a waiting lab is not listed")
        val city = pub.first { it["name"].textValue() == "ডেমো সিটি ডায়াগনস্টিক সেন্টার" }["prices"]
        assertEquals(listOf(17, 10, 150), listOf(city["count"].intValue(), city["discountPct"].intValue(), city["homeFee"].intValue()), "16 form tests + 1 typed (USG off)")
        assertTrue(city["items"] == null, "no full price list in the list")
        val full = ok(null, "lab_prices_public", mapOf("labId" to lab))["prices"]
        assertEquals(17, full["items"].size())
        assertEquals(full["items"].minOf { it["price"].intValue() }, city["from"].intValue())
        assertTrue(full["items"].any { it["name"].textValue() == "Serum Vitamin B12" && it["code"].isNull })
        assertTrue(pub.first { it["name"].textValue() == "ডেমো পপুলার প্যাথলজি" }["prices"]["count"].intValue() < 17)
        assertTrue(pub.first { it["name"].textValue() == "ডেমো রয়েল ল্যাব" }["prices"].isNull, "no list — price not known")
        assertEquals(18, ok(lab, "lab_prices_get")["items"].size(), "the owner sees the switched-off one too")

        val pts = ok(doc, "rx_patients")
        val apId = pts["appPatients"].map { it["appointmentId"].textValue() }
            .first { row("SELECT patient_uid FROM appointments WHERE id = ?", it)["patient_uid"] == p }
        val rx3 = ok(doc, "rx_create", mapOf("appointmentId" to apId, "tests" to listOf("CBC", "HbA1c", "TSH")))
        val opt = ok(p, "rx_lab_options", mapOf("rxId" to rx3["id"].textValue(), "district" to GZP))["items"]
        assertEquals(3, opt[0]["have"].intValue())
        assertTrue(opt.all { it["id"].textValue().startsWith("demo-") }, "only demo labs")
        val ho = ok(lab, "lab_orders")["items"].first { it["id"].textValue() == "demo-rx-lab-fatema-home" }
        assertEquals(listOf("sent", "requested", "early"), listOf(ho["status"].textValue(), ho["home"]["status"].textValue(), ho["home"]["slot"].textValue()))
        assertEquals(150, ho["home"]["fee"].intValue())
        assertEquals(Math.round(2300 * 0.9).toInt(), ho["prices"]["estimate"].intValue())
        val tomorrow = DhakaTime.todayUtcMidnight() + DAY
        ok(lab, "lab_home_confirm", mapOf("id" to ho["id"].textValue(), "date" to tomorrow, "time" to "07:30", "note" to "রাত ১০টার পর কিছু খাবেন না"))
        // a walk-in patient: the lab opens the code → "sample taken" at once
        val walkPt = pts["patients"].firstOrNull { it["linkedUid"] == null || it["linkedUid"].isNull } ?: pts["patients"][0]
        val paper = ok(doc, "rx_create", mapOf("rxPatientId" to walkPt["id"].textValue(), "tests" to listOf("RBS")))
        assertEquals("received", ok(lab, "lab_open_code", mapOf("code" to paper["code"].textValue()))["item"]["status"].textValue())
        // approve the waiting lab
        val nl = ok(OWNER, "admin_labs", mapOf("status" to "pending"))["items"].first { it["name"].textValue().contains("নিউ লাইফ") }
        assertEquals(400, req(OWNER, "admin_lab_resolve", mapOf("id" to nl["id"].textValue(), "approve" to true)).status)
        ok(OWNER, "admin_lab_resolve", mapOf("id" to nl["id"].textValue(), "status" to "approved", "checks" to VerifySpec.checks("lab", null).map { it.id }))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertTrue(ok(p, "labs", mapOf("district" to GZP))["items"].any { it["id"].textValue() == nl["id"].textValue() })
        // a demo lab never opens a real prescription
        user("real-doc-1")
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, status, tests, patient_name) VALUES ('real-rx-1', 'RE2LRX3456', 'real-doc-1', 'active', '{CBC}', 'আসল রোগী')")
        assertEquals(404, req(lab, "lab_open_code", mapOf("code" to "RE2LRX3456")).status)
        wipe()
        assertEquals(emptyList<String>(), demoLeft())
        assertEquals(listOf("real-rx-1"), jdbc.queryForList("SELECT id FROM prescriptions", String::class.java))
    }
}
