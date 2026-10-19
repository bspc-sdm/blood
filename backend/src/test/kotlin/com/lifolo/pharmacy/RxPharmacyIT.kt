package com.lifolo.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Pharmacy parts of server/test/rxflow.test.js, pharmacyestimate.test.js (rx_pharmacy_options), worksegs.test.js
 * (order segments) and privacy-gaps.test.js (contact purge).
 */
class RxPharmacyIT : P4Test() {
    @Autowired
    lateinit var privacyTask: RxOrderPrivacyTask

    @Autowired
    lateinit var contacts: RxOrderContacts

    private val p = "demoPatient"
    private val cg = "demoCaregiver"
    private val ph1 = "demoShop1"
    private val ph2 = "demoShop2"
    private val code = "H4QW7ZK2MP"
    private val rxId = "rxFlow01"

    private fun seedFlow() {
        person(p, "রহিমা বেগম")
        person(cg, "সাকিব (ছেলে)")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active')", p, cg)
        shop(ph1, mapOf("name" to "মা ফার্মেসি", "lat" to 24.751, "lng" to 90.408, "open24" to true, "upazila_id" to null, "phone" to "01711000099", "address" to "চরপাড়া মোড়"))
        shop(ph2, mapOf("name" to "সেবা ফার্মেসি (অনলাইন)", "lat" to 24.760, "lng" to 90.400, "open24" to true, "upazila_id" to null))
        rx(
            rxId, code, p,
            listOf(Med("Comet 500", dose = "1+0+1", days = 30, timing = "after"), Med("Rosuva 10", dose = "0+0+1", days = 30), Med("Napa 500", dose = "1+1+1", days = 3)),
            patientName = "রহিমা বেগম",
        )
    }

    @Test
    fun `shop visit, dispensing, missed list, withdraw, online order - the pharmacy path of rxflow`() {
        seedFlow()
        // shop code + caregiver sends from the counter
        val home1 = ok(ph1, "rx_pharmacy_home")
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{6}$").matches(home1["shopCode"].textValue()))
        assertEquals(home1["shopCode"], ok(ph1, "rx_pharmacy_home")["shopCode"], "generated once")
        val found = ok(cg, "rx_shop_find", mapOf("asUid" to p, "code" to home1["shopCode"].textValue().lowercase()))
        assertEquals(ph1, found["item"]["id"].textValue())
        assertEquals(400, call(cg, "rx_shop_find", mapOf("asUid" to p, "code" to "AB")).status)
        ok(cg, "rx_send_pharmacy", mapOf("asUid" to p, "rxId" to rxId, "pharmacyId" to ph1, "mode" to "shop"))
        assertTrue(lastPush(ph1)!!["title"].toString().contains("প্রেসক্রিপশন"))
        assertEquals(cg, row("SELECT by_caretaker FROM rx_orders WHERE pharmacy_uid = ?", ph1)["by_caretaker"])
        val inbox1 = ok(ph1, "rx_orders_pharmacy")["items"]
        assertEquals("shop", inbox1[0]["mode"].textValue())
        assertFalse(inbox1[0].has("address"))
        assertEquals("new", inbox1[0]["seg"].textValue())
        assertEquals(409, call(cg, "rx_send_pharmacy", mapOf("asUid" to p, "rxId" to rxId, "pharmacyId" to ph1, "mode" to "shop")).status)

        // dispense: Comet 60, Rosuva "not available"
        val opened = ok(ph1, "rx_pharmacy_open", mapOf("code" to code.lowercase()))["item"]
        assertEquals(60, opened["medicines"][0]["need"].intValue())
        assertFalse(opened.toString().contains("01711000001"))
        val disp = ok(ph1, "rx_dispense", mapOf("code" to inbox1[0]["code"].textValue(), "items" to listOf(mapOf("i" to 0, "qty" to 60)), "missing" to listOf(1)))
        assertTrue(disp["item"]["medicines"][0]["done"].booleanValue())
        assertEquals(1, disp["item"]["dispensed"].size())
        assertEquals(409, call(ph1, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 0, "qty" to 1)))).status)
        assertEquals("কয়টা দিলেন লিখুন", call(ph1, "rx_dispense", mapOf("code" to code, "items" to emptyList<Any>())).error)
        assertEquals(400, call(ph1, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 1, "qty" to 1001)))).status)
        assertTrue(lastPush(p)!!["title"].toString().contains("ওষুধ দেওয়া হয়েছে"))
        assertTrue(lastPush(cg)!!["title"].toString().startsWith("রহিমা বেগম:"))
        assertTrue(lastPush(cg)!!["data"].toString().contains(rxId))
        val order1 = row("SELECT * FROM rx_orders WHERE pharmacy_uid = ?", ph1)
        assertEquals("done", order1["status"])
        assertNotNull(order1["closed_at"])
        assertNull(order1["purge_contact_at"], "a shop order has no contact to purge")
        assertEquals(listOf(listOf("Rosuva 10", "1")), ok(ph1, "rx_pharmacy_home")["missed"].map { listOf(it["name"].textValue(), it["count"].asText()) })
        assertNotNull(row("SELECT refill_at FROM prescriptions WHERE id = ?", rxId)["refill_at"], "refill reminder date set")

        // send again and take back: still-to-buy medicines go to the missed list
        ok(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph1, "mode" to "shop"))
        val o2 = row("SELECT id FROM rx_orders WHERE status = 'sent'")["id"] as String
        ok(p, "rx_order_withdraw", mapOf("id" to o2, "reason" to "not_available"))
        val miss2 = ok(ph1, "rx_pharmacy_home")["missed"].map { listOf(it["name"].textValue(), it["count"].intValue()) }
        assertEquals(listOf(listOf("Napa 500", 1), listOf("Rosuva 10", 2)), miss2.sortedBy { it[0] as String })
        assertTrue(lastPush(ph1)!!["title"].toString().contains("ফিরিয়ে"))
        assertEquals(409, call(p, "rx_order_withdraw", mapOf("id" to o2)).status)
        assertEquals(404, call(cg, "rx_order_withdraw", mapOf("id" to o2)).status, "the caregiver's own account has no such order")

        // online: nobody sells online yet
        var opts = ok(p, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH, "lat" to 24.752, "lng" to 90.407, "mode" to "online"))
        assertEquals(0, opts["items"].size())
        assertEquals(409, call(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph2, "mode" to "online", "address" to "বাসা", "phone" to "01711000001")).status)
        val online = ok(ph2, "rx_pharmacy_online_set", mapOf("enabled" to true, "radiusKm" to 3, "fee" to "৩০ টাকা", "note" to "সকাল ১০টা–রাত ৯টা"))
        assertEquals(json(mapOf("enabled" to true, "radiusKm" to 3, "fee" to "৩০ টাকা", "note" to "সকাল ১০টা–রাত ৯টা")), online["online"])
        ok(ph2, "stock_set", mapOf("add" to listOf("Rosuva 10", "Napa 500")))
        opts = ok(p, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH, "lat" to 24.752, "lng" to 90.407, "mode" to "online"))
        assertEquals(1, opts["items"].size())
        val o = opts["items"][0]
        assertEquals(listOf(ph2, "2", "2", "৩০ টাকা"), listOf(o["id"].textValue(), o["have"].asText(), o["total"].asText(), o["deliveryFee"].textValue()))
        assertEquals(ph1, ok(p, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH, "lat" to 24.752, "lng" to 90.407, "mode" to "shop"))["items"][0]["id"].textValue())
        val byName = ok(p, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH, "lat" to 24.9, "lng" to 90.6, "mode" to "shop", "q" to "মা ফার"))
        assertEquals(listOf(ph1), ids(byName))
        assertEquals(0, ok(p, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH, "lat" to 24.9, "lng" to 90.6, "mode" to "online"))["items"].size())
        assertEquals(404, call("demoStranger".also { person(it) }, "rx_pharmacy_options", mapOf("rxId" to rxId, "district" to MYMENSINGH)).status)

        assertEquals(400, call(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph2, "mode" to "online", "address" to "", "phone" to "01711000001")).status)
        ok(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph2, "mode" to "online", "address" to "বাসা ১২, রোড ৪, চরপাড়া", "phone" to "01711000001"))
        assertTrue(lastPush(ph2)!!["title"].toString().contains("অনলাইন"))
        val on = ok(ph2, "rx_orders_pharmacy")["items"][0]
        assertEquals(listOf("online", "বাসা ১২, রোড ৪, চরপাড়া", "01711000001"), listOf(on["mode"].textValue(), on["address"].textValue(), on["phone"].textValue()))
        val oid = on["id"].textValue()
        assertEquals(409, call(ph2, "rx_order_update", mapOf("id" to oid, "status" to "ready")).status)
        ok(ph2, "rx_order_update", mapOf("id" to oid, "status" to "accepted", "price" to "৪২০ টাকা", "eta" to "আজ সন্ধ্যা ৭টার মধ্যে"))
        assertTrue(lastPush(p)!!["body"].toString().contains("৪২০ টাকা"))
        ok(ph2, "rx_order_update", mapOf("id" to oid, "status" to "out"))
        assertEquals(409, call(p, "rx_order_withdraw", mapOf("id" to oid)).status)
        ok(ph2, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 1, "qty" to 30), mapOf("i" to 2, "qty" to 9))))
        val after = row("SELECT * FROM rx_orders WHERE id = ?", oid)
        assertEquals("done", after["status"])
        assertNotNull(after["purge_contact_at"])
        assertFalse(ok(ph2, "rx_orders_pharmacy")["items"][0].has("address"), "a closed order shows no address")
        assertEquals("closed", ok(ph2, "rx_orders_pharmacy")["items"][0]["seg"].textValue())
        assertEquals(409, call(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph1, "mode" to "shop")).status)

        // every rx push carries rxId
        val rxPushes = jdbc.queryForList("SELECT data::text AS d FROM inbox_items WHERE data->>'type' IN ('rx', 'rx_pharmacy')", String::class.java)
        assertTrue(rxPushes.isNotEmpty() && rxPushes.all { it.contains("\"rxId\"") })
    }

    @Test
    fun `only approved pharmacies, cancelled prescription, other world`() {
        seedFlow()
        val pend = "phPending01"
        shop(pend, mapOf("status" to "pending"))
        for (a in listOf("rx_pharmacy_open", "rx_dispense", "rx_orders_pharmacy", "rx_pharmacy_home", "stock_get", "stock_set", "rx_missed_clear")) {
            assertEquals("শুধু অনুমোদিত ফার্মেসি এটা করতে পারে", call(pend, a, mapOf("code" to code)).error, a)
        }
        assertEquals(400, call(ph1, "rx_pharmacy_open", mapOf("code" to "BAD")).status)
        assertEquals(404, call(ph1, "rx_pharmacy_open", mapOf("code" to "ZZZZZZZZZZ")).status)
        shop("demo-shopx")
        assertEquals(404, call("demo-shopx", "rx_pharmacy_open", mapOf("code" to code)).status, "demo shop cannot open a real prescription")
        jdbc.update("UPDATE prescriptions SET status = 'cancelled' WHERE id = ?", rxId)
        assertEquals(409, call(ph1, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 0, "qty" to 1)))).status)
        assertEquals(409, call(p, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to ph1)).status)
    }

    @Test
    fun `missed list clear, stock list get and set`() {
        seedFlow()
        ok(ph1, "rx_dispense", mapOf("code" to code, "missing" to listOf(1, 2, 99)))
        assertTrue(lastPush(p)!!["title"].toString().contains("কিছু ওষুধ নেই"))
        val missed = ok(ph1, "rx_pharmacy_home")["missed"]
        assertEquals(2, missed.size())
        ok(ph1, "rx_missed_clear", mapOf("k" to missed[0]["k"].textValue()))
        assertEquals(1, ok(ph1, "rx_pharmacy_home")["missed"].size())
        ok(ph1, "rx_missed_clear")
        assertEquals(0, ok(ph1, "rx_pharmacy_home")["missed"].size())

        assertEquals(json(mapOf("items" to emptyList<String>(), "updatedAt" to 0)), ok(ph1, "stock_get"))
        assertEquals(json(listOf("Ace", "Napa 500")), ok(ph1, "stock_set", mapOf("add" to listOf("Napa 500", " Ace ", "napa  500", 5)))["items"])
        assertEquals(json(listOf("Ace")), ok(ph1, "stock_set", mapOf("remove" to listOf("NAPA 500")))["items"])
        val st = ok(ph1, "stock_get")
        assertEquals(json(listOf("Ace")), st["items"])
        assertTrue(st["updatedAt"].longValue() > 0)
    }

    @Test
    fun `segments - new, active, closed; counts from all, open orders keep their place`() {
        assertEquals(
            listOf("new", "active", "active", "active", "closed", "closed", "closed"),
            listOf("sent", "ready", "accepted", "out", "done", "cancelled", "withdrawn").map { RxMath.pharmSeg(it) },
        )
        assertEquals(mapOf("new" to 1, "active" to 0, "closed" to 2), RxMath.segCounts(listOf("new", "active", "closed"), listOf("new", "closed", "closed")))
        data class X(val id: String, val createdAt: Long, val open: Boolean)
        val list = listOf(X("old-open", 1, true)) + (0 until 30).map { X("c$it", 100L + it, false) }
        val out = RxMath.recentFirstOpen(list, { it.createdAt }, { it.open }, 10)
        assertEquals(10, out.size)
        assertEquals("c29", out[0].id)
        assertEquals("old-open", out.last().id)

        seedFlow()
        val now = System.currentTimeMillis()
        order("open-old", mapOf("rx_id" to rxId, "pharmacy_uid" to ph1, "patient_uid" to p, "status" to "sent", "created_at" to ts(now - 10 * DAY)))
        order("open-act", mapOf("rx_id" to rxId, "pharmacy_uid" to ph1, "patient_uid" to p, "status" to "ready", "created_at" to ts(now - 9 * DAY)))
        for (i in 0 until 120) order("bulk-$i", mapOf("rx_id" to rxId, "pharmacy_uid" to ph1, "patient_uid" to p, "status" to "done", "created_at" to ts(now - i * 1000L)))
        val many = ok(ph1, "rx_orders_pharmacy")
        assertEquals(100, many["items"].size())
        assertEquals(json(mapOf("new" to 1, "active" to 1, "closed" to 120)), many["counts"])
        assertTrue(ids(many).containsAll(listOf("open-old", "open-act")))
        val times = many["items"].map { it["createdAt"].longValue() }
        assertEquals(times.sortedDescending(), times)
        ok(ph1, "rx_order_update", mapOf("id" to "open-old", "status" to "ready"))
        assertEquals(json(mapOf("new" to 0, "active" to 2, "closed" to 120)), ok(ph1, "rx_orders_pharmacy")["counts"])
    }

    @Test
    fun `online order contacts - closed orders keep them 7 days, the daily job removes them`() {
        val pp = "pgPatient02"
        val s = "pgShop00002"
        person(pp)
        shop(s, mapOf("name" to "দোকান"))
        rx("pgRx2", "PGRXQQ2222", pp, listOf(Med("Seclo", dose = "1+0+1", days = 30)), patientName = "রোগী দুই")
        rx("pgRx2b", "PGRXQQ2223", pp, listOf(Med("Seclo", dose = "1+0+1", days = 30)), patientName = "রোগী দুই")
        fun online(id: String, extra: Map<String, Any?> = emptyMap()) = order(
            id,
            mapOf(
                "rx_id" to "pgRx2", "code" to "PGRXQQ2222", "pharmacy_uid" to s, "pharmacy_name" to "দোকান", "patient_uid" to pp,
                "patient_name" to "রোগী দুই", "mode" to "online", "address" to "বাসা ১২, চরপাড়া", "phone" to "01711000222", "status" to "sent",
                "created_at" to ts(System.currentTimeMillis() - DAY),
            ) + extra,
        )
        online("pgOrdCancel")
        online("pgOrdWithdraw")
        online("pgOrdDone", mapOf("status" to "out"))
        online("pgOrdOpen", mapOf("rx_id" to "pgRx2b"))
        ok(s, "rx_order_update", mapOf("id" to "pgOrdCancel", "status" to "cancelled", "reason" to "স্টক নেই"))
        ok(pp, "rx_order_withdraw", mapOf("id" to "pgOrdWithdraw"))
        ok(s, "rx_dispense", mapOf("code" to "PGRXQQ2222", "items" to listOf(mapOf("i" to 0, "qty" to 10))))
        for ((id, st) in listOf("pgOrdCancel" to "cancelled", "pgOrdWithdraw" to "withdrawn", "pgOrdDone" to "done")) {
            val x = row("SELECT * FROM rx_orders WHERE id = ?", id)
            assertEquals(st, x["status"])
            val closed = (x["closed_at"] as java.sql.Timestamp).time
            assertTrue(Math.abs((x["purge_contact_at"] as java.sql.Timestamp).time - (closed + 7 * DAY)) < 5, id)
            assertEquals("বাসা ১২, চরপাড়া", x["address"])
        }
        assertEquals(0, contacts.purgeClosedContacts(System.currentTimeMillis() + 6 * DAY))
        @Suppress("UNCHECKED_CAST")
        val out = privacyTask.run(System.currentTimeMillis() + 7 * DAY + 60000) as Map<String, Any?>
        assertTrue((out["rxOrderContactsPurged"] as Int) >= 3, out.toString())
        for (id in listOf("pgOrdCancel", "pgOrdWithdraw", "pgOrdDone")) {
            val x = row("SELECT * FROM rx_orders WHERE id = ?", id)
            assertEquals(listOf(null, null, null), listOf(x["address"], x["phone"], x["purge_contact_at"]), id)
            assertNotNull(x["closed_at"])
        }
        val open = row("SELECT address, phone FROM rx_orders WHERE id = 'pgOrdOpen'")
        assertEquals(listOf("বাসা ১২, চরপাড়া", "01711000222"), listOf(open["address"], open["phone"]))
        assertEquals(0, contacts.purgeClosedContacts(System.currentTimeMillis() + 30 * DAY))
        // the daily job through the runner too
        val cron = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, cron.status)
        assertTrue(cron.body.has("privacy") && cron.body.has("medFindsDeleted"))
    }

    // ------------------------------------------------------------------ pharmacyestimate.test.js (rx_pharmacy_options)

    @Test
    fun `patient - estimate per shop, placeId gives only that shop, demo and real apart`() {
        val s1 = "estShopP1"
        val s2 = "estShopP2"
        val far = "estShopFar"
        val other = "estShopElse"
        val dsh = "demo-estshop"
        val otherDistrict = com.lifolo.common.Places.DISTRICTS.first { it.en != "Mymensingh" }.bn
        shop(s1, mapOf("upazila_id" to 1, "open24" to true))
        shop(s2, mapOf("upazila_id" to 1, "lat" to 24.751, "lng" to 90.401, "open24" to true))
        shop(far, mapOf("upazila_id" to 9, "lat" to 25.2, "lng" to 90.9, "open24" to true))
        shop(other, mapOf("upazila_id" to 1, "district" to otherDistrict, "open24" to true))
        shop(dsh, mapOf("upazila_id" to 1, "open24" to true))
        val pp = "estPatient1"
        val dp = "demo-estpatient"
        person(pp)
        person(dp)
        val meds = listOf(Med("Comet 500", dose = "1+0+1", days = 5), Med("Napa"), Med("Tusca", form = "Syrup"), Med("Zimax", dose = "1+0+0", days = 3))
        rx("estRx1", "CESTRX1AAA", pp, meds)
        rx("demo-estRx1", "CDEMOESTAA", dp, meds, doctorUid = "demo-docX")
        for (id in listOf(s1, far, dsh)) {
            ok(id, "books_item_save", mapOf("name" to "Comet 500", "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "unit" to "piece"))
            ok(id, "books_item_save", mapOf("name" to "Napa", "price" to 1.2, "unit" to "piece"))
            ok(id, "books_item_save", mapOf("name" to "Tusca", "price" to 85.5, "unit" to "bottle"))
        }
        ok(s2, "stock_set", mapOf("add" to listOf("Comet 500", "Zimax")))
        ok(other, "books_item_save", mapOf("name" to "Comet 500", "price" to 8, "unit" to "piece"))
        val est = json(mapOf("total" to 158, "priced" to 2, "of" to 4, "mrp" to 166, "discount" to 8))

        val body = mapOf("rxId" to "estRx1", "district" to MYMENSINGH, "upazilaId" to 1, "lat" to 24.75, "lng" to 90.40, "mode" to "shop")
        val items = ok(pp, "rx_pharmacy_options", body)
        assertEquals(est, by(items, s1)["estimate"])
        assertEquals(listOf("3", "4"), listOf(by(items, s1)["have"].asText(), by(items, s1)["total"].asText()))
        assertEquals(json(listOf("Zimax")), by(items, s1)["missing"])
        assertTrue(by(items, s2)["estimate"].isNull)
        assertEquals(2, by(items, s2)["have"].intValue())
        assertFalse(ids(items).contains(far) || ids(items).contains(other) || ids(items).contains(dsh))
        assertFalse(items.toString().contains("85.5"))

        var one = ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to far))
        assertEquals(listOf(far), ids(one))
        assertEquals(est, one["items"][0]["estimate"])
        one = ok(pp, "rx_pharmacy_options", body + mapOf("mode" to "online", "placeId" to s2))
        assertEquals(listOf(s2), ids(one))
        assertTrue(one["items"][0]["estimate"].isNull && one["items"][0]["keepsStock"].booleanValue())
        assertEquals(0, ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to other))["items"].size())
        assertEquals(0, ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to dsh))["items"].size())
        assertEquals(0, ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to "nope000"))["items"].size())

        val dem = ok(dp, "rx_pharmacy_options", body + mapOf("rxId" to "demo-estRx1"))
        assertTrue(dem["items"].size() > 0 && ids(dem).all { it.startsWith("demo-") })
        assertEquals(est, by(dem, dsh)["estimate"])
        assertEquals(0, ok(dp, "rx_pharmacy_options", body + mapOf("rxId" to "demo-estRx1", "placeId" to s1))["items"].size())

        // part bought: Comet 6 given → 4 × 7.2 + 85.5 = 114.3 → 114
        dispensed("estRx1", s2, 1, listOf(0 to 6))
        one = ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to s1))
        assertEquals(json(mapOf("total" to 114, "priced" to 2, "of" to 4, "mrp" to 118, "discount" to 4)), one["items"][0]["estimate"])
        ok(s1, "books_item_save", mapOf("id" to com.lifolo.books.BooksMath.idOf("Tusca"), "name" to "Tusca", "price" to 100, "unit" to "bottle"))
        one = ok(pp, "rx_pharmacy_options", body + mapOf("placeId" to s1))
        assertEquals(json(mapOf("total" to 129, "priced" to 2, "of" to 4, "mrp" to 132, "discount" to 3)), one["items"][0]["estimate"])
    }
}
