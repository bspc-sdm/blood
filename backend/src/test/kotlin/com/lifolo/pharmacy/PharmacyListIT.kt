package com.lifolo.pharmacy

import com.lifolo.admin.kernel.ListingRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** server/test/pharmacy24.test.js, pharmacylist-rx.test.js and the listing actions (save, me, pause, delete, admin) */
class PharmacyListIT : P4Test() {
    // ------------------------------------------------------------------ pharmacy24.test.js

    private val h24 = "ph24real0001"
    private val dayShop = "phdayreal001"
    private val old = "pholdreal001"
    private val paused = "ph24paused01"
    private val demo24 = "demo-test-ph24"

    private fun seed24(now: Long) {
        shop(h24, mapOf("open24" to true, "open_at" to "", "close_at" to ""))
        shop(dayShop, mapOf("open24" to false, "open_at" to "09:00", "close_at" to "22:00"))
        shop(old, mapOf("open_at" to "08:00", "close_at" to "23:00"))
        shop(paused, mapOf("open24" to true, "open_at" to "", "close_at" to "", "closed_until" to ts(now + 3 * DAY)))
        shop(demo24, mapOf("open24" to true, "open_at" to "", "close_at" to ""))
    }

    @Test
    fun `24-hour shop is open at 3 a_m_ Dhaka, a timed shop is not`() {
        val t = setDhaka("2026-10-20 03:00")
        assertTrue(ListingRules.openNow(mapOf("open24" to true, "openAt" to "", "closeAt" to ""), t))
        assertTrue(ListingRules.openNow(mapOf("open24" to true, "openAt" to "09:00", "closeAt" to "22:00"), t))
        assertFalse(ListingRules.openNow(mapOf("open24" to false, "openAt" to "09:00", "closeAt" to "22:00"), t))
        assertFalse(ListingRules.openNow(mapOf("openAt" to "08:00", "closeAt" to "23:00"), t))
        assertFalse(ListingRules.openNow(mapOf("open24" to true, "closedUntil" to t + HOUR), t))
    }

    @Test
    fun `list - open24 filter, openNow, other filters and order as before`() {
        val now = setDhaka("2026-10-20 03:00")
        seed24(now)
        val all = ok(null, "pharmacies", mapOf("district" to MYMENSINGH))
        assertEquals(listOf(demo24, h24, paused, dayShop, old).sorted(), ids(all).sorted())
        assertTrue(by(all, h24)["open24"].booleanValue())
        assertTrue(by(all, h24)["openNow"].booleanValue())
        assertFalse(by(all, dayShop)["open24"].booleanValue())
        assertFalse(by(all, dayShop)["openNow"].booleanValue())
        assertFalse(by(all, old)["open24"].booleanValue())
        assertFalse(by(all, paused)["openNow"].booleanValue())
        val list = all["items"].toList()
        val firstClosed = list.indexOfFirst { !it["openNow"].booleanValue() }
        assertTrue(list.drop(firstClosed).all { !it["openNow"].booleanValue() })

        val only24 = ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "open24" to true))
        assertEquals(listOf(demo24, h24, paused).sorted(), ids(only24).sorted())
        assertFalse(by(only24, paused)["openNow"].booleanValue())
        assertEquals(listOf(demo24, h24).sorted(), ids(ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "open24" to true, "openNow" to true))).sorted())
        assertEquals(5, ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "x" to 1))["items"].size())
        assertEquals(listOf(demo24, h24).sorted(), ids(ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "openNow" to true))).sorted())
    }

    @Test
    fun `old app or wrong values - no filtering`() {
        val now = setDhaka("2026-10-20 03:00")
        seed24(now)
        for (v in listOf(false, "true", 1, null)) {
            assertEquals(5, ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "open24" to v))["items"].size(), "open24=$v")
        }
        assertEquals(
            listOf(demo24, h24, paused).sorted(),
            ids(ok(null, "pharmacies", mapOf("district" to MYMENSINGH, "upazilaId" to 466, "open24" to true))).sorted(),
        )
    }

    @Test
    fun `demo and real apart - medicine find at 3 a_m_ reaches the own world's 24-hour shop`() {
        val now = setDhaka("2026-10-20 03:00")
        seed24(now)
        val real = "patientreal24"
        val demo = "demo-test-patient24"
        person(real)
        person(demo)
        val drug = ok(real, "drug_search", mapOf("q" to "Napa"))["items"][0]
        val r = ok(real, "med_find", mapOf("i" to drug["i"].intValue(), "district" to MYMENSINGH, "upazilaId" to 466))
        assertTrue(r["ok"].booleanValue(), r.toString())
        assertEquals(listOf(h24), jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, r["id"].textValue()))
        val d = ok(demo, "med_find", mapOf("i" to drug["i"].intValue(), "district" to MYMENSINGH, "upazilaId" to 466))
        assertTrue(d["ok"].booleanValue(), d.toString())
        assertEquals(listOf(demo24), jdbc.queryForList("SELECT pharmacy_uid FROM med_find_targets WHERE find_id = ?", String::class.java, d["id"].textValue()))
        val seen = by(ok(demo, "pharmacies", mapOf("district" to MYMENSINGH, "open24" to true)), h24)
        assertFalse(seen["canRate"].booleanValue())
    }

    // ------------------------------------------------------------------ pharmacylist-rx.test.js

    private val a = "rxlA0001"
    private val b = "rxlB0001"
    private val c2 = "rxlC0001"
    private val d = "rxlD0001"
    private val far = "rxlFar001"
    private val ds = "demo-rxlshop"
    private val p = "rxlPatient1"
    private val q = "rxlPatient2"
    private val dp = "demo-rxlpatient"
    private val meds = listOf(Med("Comet 500", dose = "1+0+1", days = 5), Med("Napa"), Med("Tusca", form = "Syrup"), Med("Zimax", dose = "1+0+0", days = 3))
    private val here = mapOf("district" to MYMENSINGH, "upazilaId" to 466, "lat" to 24.75, "lng" to 90.40)

    private fun on(radius: Int, fee: String) = mapOf("online_enabled" to true, "online_radius_km" to radius, "online_fee" to fee)

    private fun seedRx() {
        shop(
            a,
            mapOf(
                "lat" to 24.751, "lng" to 90.401, "open24" to true, "discount" to 10, "free_delivery_over" to 1000, "discount_note" to "শুধু নগদে",
                "rating_sum" to 9, "rating_count" to 2, "aspect_behaviour_sum" to 9, "aspect_behaviour_count" to 2,
            ) + on(3, "৩০ টাকা"),
        )
        shop(b, mapOf("lat" to 24.76, "lng" to 90.40, "open24" to true, "discount" to 5) + on(1, "২০ টাকা"))
        shop(c2, mapOf("open24" to true, "discount" to 12, "discount_min" to 500))
        shop(d, mapOf("lat" to 24.77, "lng" to 90.40, "open24" to true) + on(5, ""))
        shop(far, mapOf("upazila_id" to 9, "lat" to 25.2, "lng" to 90.9, "open24" to true) + on(30, ""))
        shop(ds, mapOf("open24" to true) + on(5, ""))
        for (id in listOf(a, ds)) {
            ok(id, "books_item_save", mapOf("name" to "Comet 500", "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "unit" to "piece"))
            ok(id, "books_item_save", mapOf("name" to "Napa", "price" to 1.2, "unit" to "piece"))
            ok(id, "books_item_save", mapOf("name" to "Tusca", "price" to 85.5, "unit" to "bottle"))
        }
        ok(b, "stock_set", mapOf("add" to listOf("Comet 500", "Zimax")))
        ok(c2, "books_item_save", mapOf("name" to "Comet 500", "price" to 8, "disc" to mapOf("type" to "pct", "v" to 12), "unit" to "piece"))
        person(p)
        person(q)
        person(dp)
        rx("rxlRx1", "CRXLRX1AAA", p, meds)
        rx("rxlRxQ", "CRXLRXQAAA", q, listOf(Med("Napa")))
        rx("demo-rxlRx1", "CDEMORXLAA", dp, meds, doctorUid = "demo-docX")
    }

    private fun rxCard(keeps: Boolean, have: Int, total: Int, missing: List<String>, priced: Int, payable: Int?, mrp: Int?, discount: Int?, belowMin: Boolean) = json(
        linkedMapOf(
            "keepsStock" to keeps, "have" to have, "total" to total, "missing" to missing, "priced" to priced, "payable" to payable,
            "mrp" to mrp, "discount" to discount, "belowMin" to belowMin,
        ),
    )

    @Test
    fun `with a prescription - medicines in stock, estimate, discount, terms, and the order`() {
        seedRx()
        val r = ok(p, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRx1"))
        assertEquals(listOf(a, b, c2, d), ids(r))
        assertEquals(rxCard(true, 3, 4, listOf("Zimax"), 2, 158, 166, 8, false), by(r, a)["rx"])
        assertEquals(listOf(10, 0, 1000), listOf("discount", "discountMin", "freeDeliveryOver").map { by(r, a)[it].intValue() })
        assertEquals("শুধু নগদে", by(r, a)["discountNote"].textValue())
        assertEquals(4.5, by(r, a)["rating"].doubleValue())
        assertEquals(2, by(r, a)["ratingCount"].intValue())
        assertEquals(json(mapOf("rating" to 4.5, "count" to 2)), by(r, a)["aspects"]["behaviour"])
        assertEquals(true, by(r, a)["online"].booleanValue())
        assertEquals("৩০ টাকা", by(r, a)["deliveryFee"].textValue())
        assertEquals(rxCard(true, 2, 4, listOf("Napa", "Tusca"), 0, null, null, null, false), by(r, b)["rx"])
        assertEquals(rxCard(true, 1, 4, listOf("Napa", "Tusca", "Zimax"), 1, 80, 80, 0, true), by(r, c2)["rx"])
        assertFalse(by(r, d)["rx"]["keepsStock"].booleanValue())
        assertEquals("rxlRx1", r["rxId"].textValue())
        assertTrue(r["lastHome"].isNull)
        assertFalse(r.toString().contains("85.5") || r.toString().contains("7.2"))
        val plain = ok(p, "pharmacies", here + mapOf("mode" to "shop"))
        assertTrue(plain["items"].all { !it.has("rx") } && !plain.has("lastHome"))
        assertEquals(listOf(c2, a, b, d), ids(plain))
    }

    @Test
    fun `online orders - only shops that deliver here; without a location the same upazila`() {
        seedRx()
        val online = ok(p, "pharmacies", here + mapOf("mode" to "online"))
        assertEquals(listOf(a, d), ids(online))
        assertTrue(online["items"].all { it["online"].booleanValue() && !it.has("radiusKm") })
        assertEquals(listOf(a, d), ids(ok(p, "pharmacies", here + mapOf("mode" to "online", "rxId" to "rxlRx1"))))
        assertEquals(listOf(a, b, d).sorted(), ids(ok(p, "pharmacies", mapOf("district" to MYMENSINGH, "upazilaId" to 466, "mode" to "online"))).sorted())
        assertEquals(listOf(a, b, d, far).sorted(), ids(ok(p, "pharmacies", mapOf("district" to MYMENSINGH, "mode" to "online"))).sorted())
        assertEquals(listOf(a, b, c2, d).sorted(), ids(ok(p, "pharmacies", here + mapOf("mode" to "shop"))).sorted())
    }

    @Test
    fun `someone else's prescription, guest or bad id - 403; a caregiver may for the patient`() {
        seedRx()
        for ((uid, rxId) in listOf(q to "rxlRx1", null to "rxlRx1", p to "nope0000", p to "rxlRxQ", p to "../x")) {
            val r = call(uid, "pharmacies", here + mapOf("mode" to "shop", "rxId" to rxId))
            assertEquals(403, r.status, "$uid $rxId")
            assertFalse(r.body.has("items"))
        }
        val cg = "rxlCarer01"
        person(cg)
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active')", p, cg)
        val viaCare = ok(cg, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRx1", "asUid" to p))
        assertEquals(158, by(viaCare, a)["rx"]["payable"].intValue())
        assertEquals(403, call(q, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRx1", "asUid" to p)).status)
        assertEquals(403, call(cg, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRx1")).status)
    }

    @Test
    fun `prescription parts stay in the own list`() {
        seedRx()
        assertEquals(3, by(ok(p, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRx1")), a)["rx"]["have"].intValue())
        val other = ok(q, "pharmacies", here + mapOf("mode" to "shop"))
        assertTrue(other["items"].all { !it.has("rx") } && !other.has("lastHome") && !other.has("rxId"))
        assertTrue(ok(null, "pharmacies", here)["items"].all { !it.has("rx") })
        assertEquals(rxCard(true, 1, 1, emptyList(), 0, null, null, null, false), by(ok(q, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "rxlRxQ")), a)["rx"])
    }

    @Test
    fun `demo and real - with a mode or prescription only the own world; guests and old apps as before`() {
        seedRx()
        val dem = ok(dp, "pharmacies", here + mapOf("mode" to "shop", "rxId" to "demo-rxlRx1"))
        assertEquals(listOf(ds), ids(dem))
        assertEquals(158, dem["items"][0]["rx"]["payable"].intValue())
        assertEquals(listOf(ds), ids(ok(dp, "pharmacies", here + mapOf("mode" to "online"))))
        assertEquals(403, call(dp, "pharmacies", here + mapOf("rxId" to "rxlRx1")).status)
        assertEquals(403, call(p, "pharmacies", here + mapOf("rxId" to "demo-rxlRx1")).status)
        assertTrue(ids(ok(null, "pharmacies", here)).contains(ds))
        assertTrue(ids(ok(p, "pharmacies", here)).contains(ds))
    }

    @Test
    fun `after an online order the next list pre-fills address and phone (only the own)`() {
        seedRx()
        ok(p, "rx_send_pharmacy", mapOf("rxId" to "rxlRx1", "pharmacyId" to a, "mode" to "online", "address" to "বাসা ৫, চরপাড়া", "phone" to "01712345678"))
        val r = ok(p, "pharmacies", here + mapOf("mode" to "online", "rxId" to "rxlRx1"))
        assertEquals(json(mapOf("address" to "বাসা ৫, চরপাড়া", "phone" to "01712345678")), r["lastHome"])
        assertFalse(ok(q, "pharmacies", here + mapOf("mode" to "online")).has("lastHome"))
        assertTrue(ok(q, "pharmacies", here + mapOf("mode" to "online", "rxId" to "rxlRxQ"))["lastHome"].isNull)
    }

    @Test
    fun `discount terms - saved, kept for old apps, limits; no new review`() {
        seedRx()
        val base = mapOf(
            "name" to "দোকান $a", "licenseNo" to "DL-1", "phone" to "01711111111", "district" to MYMENSINGH, "upazilaId" to 466,
            "address" to "চরপাড়া", "open24" to true, "termsAccepted" to true,
        )
        ok(a, "pharmacy_save", base + mapOf("discount" to 10, "discountMin" to 500, "freeDeliveryOver" to 1000, "discountNote" to "  শুধু\nনগদে  " + "ক".repeat(100)))
        var me = ok(a, "pharmacy_me")["pharmacy"]
        assertEquals(listOf(500, 1000), listOf(me["discountMin"].intValue(), me["freeDeliveryOver"].intValue()))
        assertTrue(me["discountNote"].textValue().startsWith("শুধু নগদে") && me["discountNote"].textValue().length == 80, me["discountNote"].textValue())
        ok(a, "pharmacy_save", base + mapOf("discount" to 10))
        me = ok(a, "pharmacy_me")["pharmacy"]
        assertEquals(listOf(500, 1000), listOf(me["discountMin"].intValue(), me["freeDeliveryOver"].intValue()))
        ok(a, "pharmacy_save", base + mapOf("discount" to 10, "discountMin" to -5, "freeDeliveryOver" to "x", "discountNote" to ""))
        me = ok(a, "pharmacy_me")["pharmacy"]
        assertEquals(listOf("0", "0", ""), listOf(me["discountMin"].asText(), me["freeDeliveryOver"].asText(), me["discountNote"].textValue()))
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = ?", a)["status"])
        ok(a, "pharmacy_save", base + mapOf("discount" to 10, "discountMin" to 300, "freeDeliveryOver" to 0, "discountNote" to "বিকাশে নয়"))
        val x = by(ok(p, "pharmacies", here + mapOf("mode" to "shop")), a)
        assertEquals(listOf("300", "0", "বিকাশে নয়"), listOf(x["discountMin"].asText(), x["freeDeliveryOver"].asText(), x["discountNote"].textValue()))
    }

    // ------------------------------------------------------------------ save, me, pause, delete, admin

    private fun newShopBody(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "name" to "নতুন ফার্মেসি", "licenseNo" to "DL-99", "phone" to "+8801711000123", "district" to MYMENSINGH, "upazilaId" to 466,
        "address" to "গাঙ্গিনাপাড়", "lat" to 24.7531, "lng" to 90.4072, "openAt" to "09:00", "closeAt" to "21:00", "closedDays" to listOf(5, 5, 9),
        "termsAccepted" to true, "docs" to mapOf("license" to JPEG, "front" to JPEG),
    ) + extra

    @Test
    fun `save - checks, new pharmacy pending with documents, quota, edits go to review, suspended cannot edit`() {
        val o = "phNewOwner1"
        person(o)
        assertEquals("ফার্মেসির নাম লিখুন", call(o, "pharmacy_save", newShopBody(mapOf("name" to " "))).error)
        assertEquals("ড্রাগ লাইসেন্স নম্বর দিন", call(o, "pharmacy_save", newShopBody(mapOf("licenseNo" to ""))).error)
        assertEquals("সঠিক মোবাইল নম্বর দিন", call(o, "pharmacy_save", newShopBody(mapOf("phone" to "123"))).error)
        assertEquals("জেলা বেছে নিন", call(o, "pharmacy_save", newShopBody(mapOf("district" to "x"))).error)
        assertEquals("দোকানের ঠিকানা লিখুন", call(o, "pharmacy_save", newShopBody(mapOf("address" to ""))).error)
        assertEquals(400, call(o, "pharmacy_save", newShopBody(mapOf("lat" to 10.0))).status)
        assertEquals("খোলা আর বন্ধের সময় দিন", call(o, "pharmacy_save", newShopBody(mapOf("closeAt" to "09:00"))).error)
        assertEquals("Please accept the terms", call(o, "pharmacy_save", newShopBody(mapOf("termsAccepted" to false)), lang = "en").error)
        val noDocs = call(o, "pharmacy_save", newShopBody(mapOf("docs" to null)))
        assertEquals(400, noDocs.status)
        assertEquals("docs_required", noDocs.code)
        assertTrue(row("SELECT 1 AS x FROM pharmacies WHERE uid = ?", o).isEmpty())

        val r = ok(o, "pharmacy_save", newShopBody())
        assertEquals(json(mapOf("ok" to true, "pending" to true)), r)
        val saved = row("SELECT * FROM pharmacies WHERE uid = ?", o)
        assertEquals("pending", saved["status"])
        assertEquals("01711000123", saved["phone"])
        assertEquals(24.753, saved["lat"])
        assertEquals(90.407, saved["lng"])
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM verify_docs WHERE kind = 'pharmacy' AND listing_id = ?", Int::class.java, o))
        val me = ok(o, "pharmacy_me")["pharmacy"]
        assertEquals("pending", me["status"].textValue())
        assertEquals(json(listOf(5)), me["closedDays"])
        assertTrue(me["hasLocation"].booleanValue())
        assertNotNull(me["docs"], "pending: the document list for the owner")
        assertEquals(false, me["docsMissing"].booleanValue())

        // approve, then a name typo keeps it listed; a new licence goes back to review
        jdbc.update("UPDATE pharmacies SET status = 'approved', approved_name = name WHERE uid = ?", o)
        val edit = ok(o, "pharmacy_save", newShopBody(mapOf("name" to "নতুন ফার্মেসী", "docs" to null)))
        assertFalse(edit["pending"].booleanValue())
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = ?", o)["status"])
        assertTrue(ok(o, "pharmacy_save", newShopBody(mapOf("licenseNo" to "DL-100", "docs" to null)))["pending"].booleanValue())
        assertEquals("pending", row("SELECT status FROM pharmacies WHERE uid = ?", o)["status"])
        assertNotNull(row("SELECT last_approved FROM pharmacies WHERE uid = ?", o)["last_approved"])

        jdbc.update("UPDATE pharmacies SET status = 'suspended' WHERE uid = ?", o)
        val s = call(o, "pharmacy_save", newShopBody(mapOf("docs" to null)))
        assertEquals(403, s.status)
        assertTrue(s.error!!.contains("সাহায্য ও আপিল"))
        assertEquals(403, call(o, "pharmacy_delete").status)
    }

    @Test
    fun `pause, find setting, delete with an open report`() {
        val o = "phPause0001"
        shop(o)
        val now = System.currentTimeMillis()
        assertEquals("৬০ দিনের মধ্যে তারিখ দিন", call(o, "pharmacy_pause", mapOf("until" to now + 61 * DAY)).error)
        assertEquals(400, call(o, "pharmacy_pause", mapOf("until" to now - 1000)).status)
        ok(o, "pharmacy_pause", mapOf("until" to now + 2 * DAY))
        val me = ok(o, "pharmacy_me")["pharmacy"]
        assertFalse(me["openNow"].booleanValue())
        assertTrue(me["closedUntil"].longValue() > now)
        ok(o, "pharmacy_pause", mapOf("until" to null))
        assertNull(row("SELECT closed_until FROM pharmacies WHERE uid = ?", o)["closed_until"])
        person("nobody01")
        assertEquals(404, call("nobody01", "pharmacy_pause").status)

        ok(o, "pharmacy_find_setting", mapOf("off" to true))
        assertEquals(true, row("SELECT find_off FROM pharmacies WHERE uid = ?", o)["find_off"])
        person("nobody02")
        assertEquals("আগে নিজের ফার্মেসি যোগ করুন", call("nobody02", "pharmacy_find_setting", mapOf("off" to true)).error)

        person("rep1")
        jdbc.update(
            "INSERT INTO mod_reports (id, target_path, reporter_uid, status, kind, reason) VALUES ('r1', ?, 'rep1', 'open', 'pharmacy', 'x')",
            "pharmacies/$o",
        )
        assertEquals(409, call(o, "pharmacy_delete").status)
        jdbc.update("UPDATE mod_reports SET status = 'dismissed'")
        ok(o, "pharmacy_delete")
        assertTrue(row("SELECT 1 AS x FROM pharmacies WHERE uid = ?", o).isEmpty())
        ok(o, "pharmacy_delete")
        assertTrue(ok(o, "pharmacy_me")["pharmacy"].isNull)
    }

    @Test
    fun `pharmacy_delete strips delivery contacts of every order of the shop, orders stay`() {
        val s = "pgShopDel01"
        shop(s)
        person("pgSomeone")
        rx("rDel", "RDELAAAAAA", "pgSomeone", listOf(Med("Napa")))
        for (i in 0 until 5) {
            order("${s}_o$i", mapOf("rx_id" to "rDel", "pharmacy_uid" to s, "patient_uid" to "pgSomeone", "mode" to "online", "address" to "ঠিকানা $i", "phone" to "01711000444", "status" to "sent"))
        }
        shop("pgOtherShop1")
        order("pgOtherShop", mapOf("rx_id" to "rDel", "pharmacy_uid" to "pgOtherShop1", "patient_uid" to "pgSomeone", "mode" to "online", "address" to "অন্য দোকানের", "phone" to "01711000555", "status" to "done"))
        ok(s, "pharmacy_delete")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rx_orders WHERE pharmacy_uid = ? AND (address IS NOT NULL OR phone IS NOT NULL)", Int::class.java, s))
        assertEquals("sent", row("SELECT status FROM rx_orders WHERE id = ?", "${s}_o0")["status"])
        assertEquals("অন্য দোকানের", row("SELECT address FROM rx_orders WHERE id = 'pgOtherShop'")["address"])
    }

    @Test
    fun `admin list and resolve`() {
        val adm = "admin1"
        user(adm, null, "owner@example.com")
        val o = "phPend00001"
        person(o)
        ok(o, "pharmacy_save", newShopBody())
        assertEquals(403, call(o, "admin_pharmacies").status)
        val list = ok(adm, "admin_pharmacies")
        assertEquals(listOf(o), ids(list))
        val item = list["items"][0]
        assertEquals("pending", item["status"].textValue())
        assertTrue(item.has("duplicates") && item.has("verify") && item.has("changes"))
        assertTrue(item["createdAt"].isNumber)
        val rej = call(adm, "admin_pharmacy_resolve", mapOf("id" to o, "approve" to true))
        assertEquals(400, rej.status, "the checks must be ticked")
        val checks = item["verify"]["checks"].map { it["id"].textValue() }
        ok(adm, "admin_pharmacy_resolve", mapOf("id" to o, "status" to "approved", "checks" to checks))
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = ?", o)["status"])
        assertEquals(json(mapOf("ok" to true, "unchanged" to true)), ok(adm, "admin_pharmacy_resolve", mapOf("id" to o, "status" to "approved")))
        assertEquals(listOf(o), ids(ok(adm, "admin_pharmacies", mapOf("status" to "approved"))))
        assertTrue(lastPush(o)!!["title"].toString().contains("তালিকায় এসেছে"))
    }

    companion object {
        /** a tiny JPEG (base64 of the JPEG magic bytes + padding) for verify docs */
        val JPEG: String = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200))
    }
}
