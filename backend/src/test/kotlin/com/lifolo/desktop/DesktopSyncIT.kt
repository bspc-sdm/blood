package com.lifolo.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.appointments.Ch
import com.lifolo.appointments.ProviderStore
import com.lifolo.common.DhakaTime
import com.lifolo.common.Places
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.util.UUID

/**
 * Offline (desktop) sync: devices, signed bootstrap/sync, ops (idempotent, ordered, warned, renumbered, rejected),
 * references scoped to the business, roles, demo/real.
 */
class DesktopSyncIT : IntegrationTest() {
    @Autowired lateinit var providerStore: ProviderStore

    @Autowired lateinit var deskClock: DesktopClock

    @Autowired lateinit var feature: DesktopFeature

    @Autowired lateinit var invoiceFeatures: com.lifolo.invoices.InvoiceFeatures

    @BeforeEach
    fun switchOn() {
        feature.mode = DesktopFeature.ON
        feature.pilotUids = emptySet()
        feature.demo = false
    }

    @AfterEach
    fun reset() {
        deskClock.fixed = null
        feature.mode = DesktopFeature.ON
        invoiceFeatures.forced.clear()
    }

    private val mym: String get() = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn
    private val DAY = 24 * 3600 * 1000L
    private val MIN = 60_000L

    /** deviceId → secret (desktop_register shows it once) */
    private val secrets = HashMap<String, String>()

    private fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action: $r")
        return r.body
    }

    // ------------------------------------------------------------------ signed calls (what the desk sends)

    /** POST /api/community with `X-Lifolo-Device-Sig: t=…,v1=HMAC(secret, "t.deviceId.body")` over the exact bytes */
    private fun signed(
        uid: String,
        action: String,
        deviceId: String,
        body: Map<String, Any?>,
        secret: String? = secrets[deviceId],
        t: Long = System.currentTimeMillis() / 1000,
        sig: String? = null,
    ): Res {
        val payload = linkedMapOf<String, Any?>("lang" to "bn", "action" to action, "deviceId" to deviceId)
        payload.putAll(body)
        val raw = mapper.writeValueAsString(payload) + "\n"
        val req = MockMvcRequestBuilders.post("/api/community").contentType(MediaType.APPLICATION_JSON).content(raw.toByteArray(Charsets.UTF_8))
            .header("Authorization", "Bearer $uid")
        if (secret != null || sig != null) {
            val mac = sig ?: DeviceSig.hmacHex(secret!!.toByteArray(Charsets.UTF_8), "$t.$deviceId.".toByteArray(Charsets.UTF_8) + raw.toByteArray(Charsets.UTF_8))
            req.header(DeviceSig.HEADER, "t=$t,v1=$mac")
        }
        val r = mvc.perform(req).andReturn().response
        val text = r.getContentAsString(Charsets.UTF_8)
        return Res(r.status, if (text.isBlank()) mapper.nullNode() else mapper.readTree(text), r)
    }

    private fun sync(uid: String, deviceId: String, vararg ops: Map<String, Any?>): JsonNode {
        val r = signed(uid, "offline_sync", deviceId, mapOf("ops" to ops.toList()))
        assertEquals(200, r.status, r.toString())
        return r.body
    }

    private fun boot(uid: String, deviceId: String): JsonNode {
        val r = signed(uid, "offline_bootstrap", deviceId, emptyMap())
        assertEquals(200, r.status, r.toString())
        return r.body
    }

    // ------------------------------------------------------------------ businesses

    private fun pharmacy(uid: String, status: String = "approved") {
        user(uid, mapOf("name" to "মালিক $uid", "lang" to "bn"))
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, upazila_id, address, lat, lng, open_at, close_at, status)
            VALUES (?, ?, 'DL-1', '01711111111', ?, 466, 'চরপাড়া', 24.75, 90.4, '08:00', '22:00', ?)
            """.trimIndent(),
            uid, "দোকান $uid", mym, status,
        )
    }

    private fun lab(uid: String, status: String = "approved") {
        user(uid, mapOf("name" to "ল্যাব $uid", "lang" to "bn"))
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, ?, ?, 'চরপাড়া', 'DGHS-1', '01711000498', ?)",
            uid, "ল্যাব $uid", mym, status,
        )
    }

    private val chamberA = Ch(
        hospitalId = "hospA1", hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল", district = "Mymensingh",
        days = listOf(0, 1, 2, 3, 4, 5, 6), start = "17:00", end = "21:00", fee = "500", appointments = true,
    )

    private fun doctor(uid: String, assistants: List<String> = emptyList()) {
        user(uid, mapOf("name" to "ডা. $uid", "lang" to "bn"))
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, email, status) VALUES (?, 'doctor', 'A-1', 'X', ?, '', 'approved')", uid, "ডা. $uid")
        jdbc.update(
            "INSERT INTO providers (uid, type, name, listed, appointments, active, specialties, degrees, legacy_chamber) VALUES (?, 'doctor', ?, TRUE, TRUE, TRUE, '{medicine}', 'MBBS', FALSE)",
            uid, "ডা. $uid",
        )
        providerStore.saveChambers(uid, listOf(chamberA))
        assistants.forEachIndexed { i, e -> jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, ?)", uid, e, i) }
    }

    private fun appointment(id: String, doctor: String, patient: String, status: String = "pending", date: Long = DhakaTime.todayUtcMidnight()) {
        user(patient, mapOf("name" to "রোগী $patient", "lang" to "bn"))
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, chamber_key, visit_date, status)
            VALUES (?, ?, ?, ?, ?, 'doctor', 'City Hospital', 'hospA1', ?, ?)
            """.trimIndent(),
            id, patient, doctor, "রোগী $patient", "ডা. $doctor", java.sql.Date.valueOf(DhakaTime.fromUtcMidnightMs(date)), status,
        )
    }

    private fun register(uid: String, kind: String, bizId: String = uid, name: String = "Counter PC"): JsonNode {
        val r = ok(uid, "desktop_register", mapOf("kind" to kind, "bizId" to bizId, "name" to name))
        secrets[r["deviceId"].textValue()] = r["secret"].textValue()
        return r
    }

    private fun deviceOf(uid: String, kind: String): String = register(uid, kind)["deviceId"].textValue()

    private fun item(owner: String, name: String, qty: Int, price: Int = 10): String =
        ok(owner, "books_item_save", mapOf("name" to name, "qty" to qty, "price" to price, "cost" to price / 2))["item"]["id"].textValue()

    private fun qty(owner: String, itemId: String): Double? =
        row("SELECT qty FROM shop_items WHERE shop_uid = ? AND id = ?", owner, itemId)["qty"]?.let { (it as Number).toDouble() }

    private fun op(type: String, data: Map<String, Any?>, at: Long = System.currentTimeMillis() - MIN, opId: String = UUID.randomUUID().toString()) =
        mapOf("opId" to opId, "type" to type, "at" to at, "data" to data)

    /** the desk's sale: lines {itemId, name, qty, price}; `no` in its own series */
    private fun sale(no: String, itemId: String, qty: Int, price: Int = 10, extra: Map<String, Any?> = emptyMap()) =
        mapOf("localId" to "L$no".replace("-", "_"), "no" to no, "lines" to listOf(mapOf("itemId" to itemId, "name" to "x", "qty" to qty, "price" to price))) + extra

    private fun invoices(biz: String): Int = jdbc.queryForObject("SELECT count(*) FROM invoices WHERE biz_id = ?", Int::class.java, biz)!!

    private fun paidOf(biz: String, no: String): Double = (row("SELECT paid FROM invoices WHERE biz_id = ? AND no = ?", biz, no)["paid"] as Number).toDouble()

    // ------------------------------------------------------------------ devices

    @Test
    fun `register - series D1 D2, a secret once, at most 5 at a time, revoked series never reused, only approved owners`() {
        pharmacy("ph1")
        val d1 = register("ph1", "pharmacy")
        assertEquals("D1", d1["series"].textValue())
        assertTrue(Regex("^[0-9a-f]{64}$").matches(d1["secret"].textValue()))
        // the secret is not stored (only a salt) and never shown again
        assertFalse(row("SELECT * FROM desktop_devices WHERE id = ?", d1["deviceId"].textValue()).values.contains(d1["secret"].textValue()))
        assertEquals("D2", register("ph1", "pharmacy")["series"].textValue())
        repeat(3) { register("ph1", "pharmacy") }
        val sixth = call("ph1", "desktop_register", mapOf("kind" to "pharmacy", "bizId" to "ph1", "name" to "PC6"))
        assertEquals(409, sixth.status, sixth.toString())
        assertEquals("device_limit", sixth.code)
        ok("ph1", "desktop_revoke", mapOf("deviceId" to d1["deviceId"].textValue()))
        assertEquals("D6", register("ph1", "pharmacy")["series"].textValue())
        val list = ok("ph1", "desktop_devices", mapOf("kind" to "pharmacy", "bizId" to "ph1"))
        assertEquals(6, list["items"].size())
        assertEquals(5, list["active"].intValue())
        assertTrue(list["items"].any { it["series"].textValue() == "D1" && it["revoked"].booleanValue() })
        assertFalse(list.toString().contains("secret"))
        // not approved / not mine / no name / wrong kind
        pharmacy("ph2", status = "pending")
        assertEquals(403, call("ph2", "desktop_register", mapOf("kind" to "pharmacy", "bizId" to "ph2", "name" to "PC")).status)
        assertEquals(403, call("ph2", "desktop_register", mapOf("kind" to "pharmacy", "bizId" to "ph1", "name" to "PC")).status)
        assertEquals(403, call("ph2", "desktop_devices", mapOf("kind" to "pharmacy", "bizId" to "ph1")).status)
        assertEquals(400, call("ph1", "desktop_register", mapOf("kind" to "pharmacy", "bizId" to "ph1", "name" to "  ")).status)
        assertEquals(400, call("ph1", "desktop_register", mapOf("kind" to "shop", "bizId" to "ph1", "name" to "PC")).status)
        assertEquals(403, call("ph1", "desktop_register", mapOf("kind" to "lab", "bizId" to "ph1", "name" to "PC")).status)
        // someone else's device: as if it did not exist
        assertEquals(404, call("ph2", "desktop_revoke", mapOf("deviceId" to d1["deviceId"].textValue())).status)
    }

    @Test
    fun `revoke - sync and bootstrap answer 403 device_revoked at once, and the registering account is signed out everywhere`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 10)
        assertTrue(sync("ph1", id, op("sale", sale("D1-000001", napa, 1)))["results"][0]["ok"].booleanValue())
        identity.revokedCalls.clear()
        val rv = ok("ph1", "desktop_revoke", mapOf("deviceId" to id))
        assertTrue(rv["revoked"].booleanValue())
        assertTrue(rv["signedOut"].booleanValue())
        assertTrue(rv["note"].textValue().contains("আবার লগইন"))
        assertEquals(listOf("ph1"), identity.revokedCalls.toList())
        val r = signed("ph1", "offline_sync", id, mapOf("ops" to listOf(op("sale", sale("D1-000002", napa, 1)))))
        assertEquals(403, r.status)
        assertEquals("device_revoked", r.code)
        assertEquals("device_revoked", signed("ph1", "offline_bootstrap", id, emptyMap()).code)
        assertEquals(9.0, qty("ph1", napa))
        assertEquals(1, invoices("ph1"))
        assertEquals(null, row("SELECT secret_salt FROM desktop_devices WHERE id = ?", id)["secret_salt"])
    }

    @Test
    fun `signature - only the desk itself can sync - no header, a wrong secret, a stale time or a changed body are 401`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 10)
        val body = mapOf("ops" to listOf(op("sale", sale("D1-000001", napa, 1))))
        // the owner's phone session alone (no device signature)
        val plain = call("ph1", "offline_sync", mapOf("deviceId" to id) + body)
        assertEquals(401, plain.status)
        assertEquals("bad_device_sig", plain.code)
        assertEquals("bad_device_sig", signed("ph1", "offline_sync", id, body, secret = "0".repeat(64)).code)
        assertEquals("bad_device_sig", signed("ph1", "offline_sync", id, body, t = System.currentTimeMillis() / 1000 - 601).code)
        assertEquals("bad_device_sig", signed("ph1", "offline_sync", id, body, sig = "a".repeat(64)).code)
        assertEquals("bad_device_sig", signed("ph1", "offline_bootstrap", id, emptyMap(), secret = null).code)
        assertEquals(10.0, qty("ph1", napa))
        assertEquals(0, invoices("ph1"))
        // another device's secret does not sign for this one
        val other = deviceOf("ph1", "pharmacy")
        assertEquals("bad_device_sig", signed("ph1", "offline_sync", id, body, secret = secrets[other]).code)
        assertEquals(200, signed("ph1", "offline_sync", id, body).status)
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    fun `bootstrap - pharmacy items with stock and price, lab prices, next number, delta only what changed`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 7, price = 12)
        val b = boot("ph1", id)
        assertEquals("D1", b["series"].textValue())
        assertEquals(1, b["nextSeq"].intValue())
        assertTrue(b["asOf"].longValue() > 0)
        val it = b["items"].first { x -> x["id"].textValue() == napa }
        assertEquals(7.0, it["stock"].doubleValue())
        assertEquals(12.0, it["price"].doubleValue())
        sync("ph1", id, op("sale", sale("D1-000005", napa, 1)))
        assertEquals(6, boot("ph1", id)["nextSeq"].intValue())

        lab("lab1")
        val lid = deviceOf("lab1", "lab")
        ok("lab1", "lab_prices_set", mapOf("items" to listOf(mapOf("name" to "CBC", "price" to 400))))
        val lb = boot("lab1", lid)
        assertEquals(1, lb["prices"]["items"].size(), lb.toString())
        assertEquals(400, lb["prices"]["items"][0]["price"].intValue())
        // first sync: everything; then unchanged → no price list
        assertFalse(sync("lab1", lid)["delta"]["prices"].isNull)
        assertTrue(sync("lab1", lid)["delta"]["prices"].isNull)
    }

    // ------------------------------------------------------------------ ops

    @Test
    fun `idempotent replay - the same opId gives the same result once, in the same batch and later`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 10)
        val s = op("sale", sale("D1-000001", napa, 2))
        val first = sync("ph1", id, s, s)["results"]
        assertTrue(first[0]["ok"].booleanValue(), first.toString())
        assertEquals("D1-000001", first[0]["no"].textValue())
        assertEquals("LD1_000001", first[0]["localId"].textValue())
        assertEquals(first[0], first[1])
        val again = sync("ph1", id, s)["results"][0]
        assertEquals(first[0], again)
        assertEquals(8.0, qty("ph1", napa))
        assertEquals(1, invoices("ph1"))
        // a failed op is answered the same way again too
        val bad = op("sale", sale("D7-000001", napa, 1))
        val e1 = sync("ph1", id, bad)["results"][0]
        assertEquals("series_mismatch", e1["error"].textValue())
        assertEquals(e1, sync("ph1", id, bad)["results"][0])
        // purchase / adjust (the desk's shapes) go through the books, once
        val p = op("purchase", mapOf("localId" to "p1", "lines" to listOf(mapOf("itemId" to napa, "name" to "Napa", "qty" to 5, "cost" to 4))))
        assertTrue(sync("ph1", id, p, p)["results"][0]["ok"].booleanValue())
        sync("ph1", id, p)
        assertEquals(13.0, qty("ph1", napa))
        val a = op("adjust", mapOf("itemId" to napa, "delta" to -3, "reason" to "broken"))
        sync("ph1", id, a)
        sync("ph1", id, a)
        assertEquals(10.0, qty("ph1", napa))
        sync("ph1", id, op("adjust", mapOf("itemId" to napa, "delta" to 2, "reason" to "found")))
        assertEquals(12.0, qty("ph1", napa))
        // the opId belongs to the device: another device's same opId is its own op
        val other = deviceOf("ph1", "pharmacy")
        val r2 = sync("ph1", other, mapOf("opId" to s["opId"], "type" to "sale", "at" to System.currentTimeMillis() - MIN, "data" to sale("D2-000001", napa, 1)))["results"][0]
        assertEquals("D2-000001", r2["no"].textValue())
    }

    @Test
    fun `out-of-order ops - a payment listed before its sale, one by localId before the sale is synced, void by number`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 10, price = 100)
        val now = System.currentTimeMillis()
        val saleOp = op("sale", sale("D1-000001", napa, 1, 100, mapOf("payments" to listOf(mapOf("amount" to 40, "method" to "cash")))), at = now - 5 * MIN)
        val payOp = op("pay", mapOf("invoice" to mapOf("localId" to "LD1_000001"), "amount" to 20, "method" to "bkash"), at = now - 2 * MIN)
        val r = sync("ph1", id, payOp, saleOp)["results"]
        assertTrue(r[0]["ok"].booleanValue(), r.toString())
        assertTrue(r[1]["ok"].booleanValue(), r.toString())
        assertEquals(60.0, paidOf("ph1", "D1-000001"))

        // a payment of a sale not synced yet: retry later (not stored), then applied
        val pay2 = op("pay", mapOf("invoice" to mapOf("localId" to "LD1_000002", "no" to "D1-000002"), "amount" to 10, "method" to "cash"), at = now - MIN)
        val early = sync("ph1", id, pay2)["results"][0]
        assertFalse(early["ok"].booleanValue())
        assertEquals("invoice_not_found", early["error"].textValue())
        assertTrue(early["retry"].booleanValue())
        sync("ph1", id, op("sale", sale("D1-000002", napa, 1, 100, mapOf("payments" to emptyList<Any>())), at = now - 3 * MIN))
        val late = sync("ph1", id, pay2)["results"][0]
        assertTrue(late["ok"].booleanValue(), late.toString())
        assertEquals(10.0, paidOf("ph1", "D1-000002"))
        // void by number (the books sale goes back: stock returns)
        val before = qty("ph1", napa)!!
        val v = sync("ph1", id, op("void", mapOf("invoice" to mapOf("no" to "D1-000002"), "reason" to "ভুল বিল")))["results"][0]
        assertTrue(v["ok"].booleanValue(), v.toString())
        assertEquals("void", row("SELECT status FROM invoices WHERE no = 'D1-000002' AND biz_id = 'ph1'")["status"])
        assertEquals(before + 1, qty("ph1", napa))
        // a receipt whose sale was refused cannot be paid
        val refused = op("sale", sale("D9-000001", napa, 1))
        sync("ph1", id, refused)
        val p3 = sync("ph1", id, op("pay", mapOf("invoice" to mapOf("opId" to refused["opId"]), "amount" to 5, "method" to "cash")))["results"][0]
        assertEquals("ref_rejected", p3["error"].textValue())
    }

    @Test
    fun `two devices sell the same item offline - stock short is accepted with a warning and flagged for review`() {
        pharmacy("ph1")
        val a = deviceOf("ph1", "pharmacy")
        val b = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 5)
        val r1 = sync("ph1", a, op("sale", sale("D1-000001", napa, 4)))["results"][0]
        assertTrue(r1["ok"].booleanValue())
        assertNull(r1["warn"])
        val r2 = sync("ph1", b, op("sale", sale("D2-000001", napa, 4)))["results"][0]
        assertTrue(r2["ok"].booleanValue(), r2.toString())
        assertEquals("stock_short", r2["warn"].textValue())
        assertEquals(0.0, qty("ph1", napa))
        assertEquals(2, invoices("ph1"))
        val boot = boot("ph1", a)
        val flag = boot["review"].single()
        assertEquals(napa, flag["itemId"].textValue())
        assertEquals(1.0, flag["had"].doubleValue())
        assertEquals(3.0, flag["short"].doubleValue())
        assertTrue(boot["items"].first { it["id"].textValue() == napa }["review"].booleanValue())
        // the other device's delta shows it too
        assertEquals(1, sync("ph1", b)["delta"]["review"].size())
        // a stock count clears it
        ok("ph1", "books_adjust", mapOf("kind" to "count", "itemId" to napa, "qty" to 2))
        assertEquals(0, boot("ph1", a)["review"].size())
    }

    @Test
    fun `old and future ops are refused as suspicious - and stay refused`() {
        pharmacy("ph1")
        val id = deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 10)
        val now = System.currentTimeMillis()
        val old = op("sale", sale("D1-000001", napa, 1), at = now - 9 * DAY)
        val future = op("sale", sale("D1-000002", napa, 1), at = now + 11 * MIN)
        val ok7 = op("sale", sale("D1-000003", napa, 1), at = now - 7 * DAY)
        val r = sync("ph1", id, old, future, ok7)["results"]
        assertEquals("too_old", r[0]["error"].textValue())
        assertTrue(r[0]["suspicious"].booleanValue())
        assertEquals("bad_at", r[1]["error"].textValue())
        assertTrue(r[1]["suspicious"].booleanValue())
        assertTrue(r[2]["ok"].booleanValue(), r.toString())
        assertEquals(9.0, qty("ph1", napa))
        assertEquals("too_old", sync("ph1", id, old)["results"][0]["error"].textValue())
        // the owner sees them on the device list
        assertEquals(2, ok("ph1", "desktop_devices", mapOf("kind" to "pharmacy"))["items"][0]["suspicious"].intValue())
        // a damaged op (no uuid), an unknown type, a chamber op from a pharmacy desk
        val r2 = sync("ph1", id, mapOf("opId" to "x", "type" to "sale", "at" to 1, "data" to emptyMap<String, Any>()), op("teleport", emptyMap()))["results"]
        assertEquals("bad_op", r2[0]["error"].textValue())
        assertEquals("bad_type", r2[1]["error"].textValue())
        assertEquals("wrong_kind", sync("ph1", id, op("walkin", mapOf("name" to "X")))["results"][0]["error"].textValue())
        // too many ops at once
        val many = (1..101).map { op("adjust", mapOf("itemId" to napa, "delta" to 1)) }
        assertEquals(413, signed("ph1", "offline_sync", id, mapOf("ops" to many)).status)
    }

    @Test
    fun `numbers - the server owns the series, a used number is renumbered (old number still found), the printed verify code is kept`() {
        pharmacy("ph1")
        val a = deviceOf("ph1", "pharmacy")
        deviceOf("ph1", "pharmacy")
        val napa = item("ph1", "Napa", 20)
        val r = sync(
            "ph1", a,
            op("sale", sale("D2-000001", napa, 1)),
            op("sale", sale("W-000001", napa, 1)),
            op("sale", sale("D1-1", napa, 1)),
            op("sale", sale("D1-000000", napa, 1)),
            op("sale", sale("D1-000001", napa, 1, extra = mapOf("verifyCode" to "AbCdEf2345", "localId" to "s1"))),
            op("sale", sale("D1-000001", napa, 1, extra = mapOf("localId" to "s2"))),
        )["results"]
        assertEquals("series_mismatch", r[0]["error"].textValue(), r.toString())
        assertEquals("series_mismatch", r[1]["error"].textValue())
        assertEquals("bad_no", r[2]["error"].textValue())
        assertEquals("bad_no", r[3]["error"].textValue())
        assertTrue(r[4]["ok"].booleanValue(), r.toString())
        assertEquals("AbCdEf2345", row("SELECT verify_code FROM invoices WHERE biz_id = 'ph1' AND no = 'D1-000001'")["verify_code"])
        // the same number again (a restored backup): accepted under the next free number, the desk's kept as deviceNo
        assertTrue(r[5]["ok"].booleanValue(), r.toString())
        assertEquals("D1-000002", r[5]["no"].textValue())
        assertEquals("D1-000001", r[5]["deviceNo"].textValue())
        assertEquals("renumbered", r[5]["warn"].textValue())
        assertEquals(2, invoices("ph1"))
        assertEquals(18.0, qty("ph1", napa))
        // a used verify code: refused so the desk prints a new one
        val c = sync("ph1", a, op("sale", sale("D1-000003", napa, 1, extra = mapOf("verifyCode" to "AbCdEf2345"))))["results"][0]
        assertEquals("verify_collision", c["error"].textValue())
        assertEquals(18.0, qty("ph1", napa))
    }

    @Test
    fun `references - an invoice, item, appointment or order of another business is refused`() {
        pharmacy("ph1")
        pharmacy("ph2")
        val id = deviceOf("ph1", "pharmacy")
        val other = deviceOf("ph2", "pharmacy")
        val mine = item("ph1", "Napa", 10)
        val theirs = item("ph2", "Seclo", 10)
        val inv = sync("ph2", other, op("sale", sale("D1-000001", theirs, 1, 10, mapOf("payments" to emptyList<Any>()))))["results"][0]["id"].textValue()
        val r = sync(
            "ph1", id,
            op("pay", mapOf("invoice" to mapOf("id" to inv), "amount" to 5, "method" to "cash")),
            op("void", mapOf("invoice" to mapOf("id" to inv), "reason" to "x")),
            op("sale", sale("D1-000005", theirs, 1)),
            op("adjust", mapOf("itemId" to theirs, "delta" to -1)),
            op("purchase", mapOf("lines" to listOf(mapOf("itemId" to theirs, "name" to "Other", "qty" to 1, "cost" to 1)))),
            op("sale", sale("D1-000002", mine, 1, extra = mapOf("rxOrderId" to "noSuchOrder1"))),
        )["results"]
        for (i in 0..5) assertEquals("bad_ref", r[i]["error"].textValue(), "$i: $r")
        assertEquals(0.0, paidOf("ph2", "D1-000001"))
        assertEquals("issued", row("SELECT status FROM invoices WHERE id = ?", inv)["status"])
        assertEquals(9.0, qty("ph2", theirs))
        // the other device's localId is not visible from here
        val p = sync("ph1", id, op("pay", mapOf("invoice" to mapOf("localId" to "LD1_000001"), "amount" to 5, "method" to "cash")))["results"][0]
        assertEquals("invoice_not_found", p["error"].textValue())
    }

    // ------------------------------------------------------------------ chamber: roles

    @Test
    fun `chamber - the doctor registers and revokes, the assistant syncs walk-ins and check-ins, strangers see nothing`() {
        doctor("doc1", assistants = listOf("asst1@example.com"))
        user("asst1", mapOf("name" to "সহকারী", "lang" to "bn"), email = "asst1@example.com")
        user("stranger1", mapOf("name" to "অচেনা", "lang" to "bn"))
        // the assistant may not register or revoke
        assertEquals(403, call("asst1", "desktop_register", mapOf("kind" to "chamber", "bizId" to "doc1", "name" to "Desk")).status)
        val id = register("doc1", "chamber", name = "Chamber desk")["deviceId"].textValue()
        assertEquals("assistant", ok("asst1", "desktop_devices", mapOf("kind" to "chamber", "bizId" to "doc1"))["role"].textValue())
        assertEquals(403, call("asst1", "desktop_revoke", mapOf("deviceId" to id)).status)
        assertEquals(404, signed("stranger1", "offline_bootstrap", id, emptyMap()).status)
        assertEquals(404, signed("stranger1", "offline_sync", id, mapOf("ops" to emptyList<Any>())).status)
        // an unverified email on the list is not the assistant
        identity.add("asst2", "asst2@example.com", emailVerified = false)
        jdbc.update("INSERT INTO accounts (uid, email) VALUES ('asst2', 'asst2@example.com')")
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES ('doc1', 'asst2@example.com', 1)")
        assertEquals(404, signed("asst2", "offline_bootstrap", id, emptyMap()).status)

        appointment("ap1", "doc1", "pat1")
        appointment("ap2", "doc1", "pat2")
        val b = boot("asst1", id)
        assertEquals("assistant", b["role"].textValue())
        assertEquals(2, b["appointments"].size())
        assertEquals("hospA1", b["chambers"][0]["chamberKey"].textValue())
        assertEquals(500, b["chambers"][0]["fees"]["new"].intValue())
        // walk-in and check-ins share the chamber's serial numbers
        val now = System.currentTimeMillis()
        val r = sync(
            "asst1", id,
            op("checkin", mapOf("appointmentId" to "ap1"), at = now - 3 * MIN),
            op("walkin", mapOf("name" to "হেঁটে আসা রোগী", "phone" to "01711000123", "chamberKey" to "hospA1", "date" to DhakaTime.day().toString()), at = now - 2 * MIN),
            op("checkin", mapOf("appointmentId" to "ap2"), at = now - MIN),
        )["results"]
        assertTrue(r.all { it["ok"].booleanValue() }, r.toString())
        assertEquals(listOf(1, 2, 3), r.map { it["serial"].intValue() })
        assertEquals("confirmed", row("SELECT status FROM appointments WHERE id = 'ap1'")["status"])
        // check-in = chamber_checkin: the patient has arrived
        assertEquals("arrived", r[0]["status"].textValue())
        assertTrue(r[0]["arrivedAt"].longValue() > 0)
        assertTrue(row("SELECT arrived_at FROM appointments WHERE id = 'ap2'")["arrived_at"] != null)
        val delta = sync("asst1", id)["delta"]
        assertEquals("হেঁটে আসা রোগী", delta["walkins"][0]["name"].textValue())
        assertNull(delta["walkins"][0]["phone"], "no phones on the desk")
        // someone else's appointment
        doctor("doc2")
        appointment("ap9", "doc2", "pat9")
        assertEquals("bad_ref", sync("asst1", id, op("checkin", mapOf("appointmentId" to "ap9")))["results"][0]["error"].textValue())
        assertEquals("pending", row("SELECT status FROM appointments WHERE id = 'ap9'")["status"])
        // revoked by the doctor: the assistant's desk stops at once
        ok("doc1", "desktop_revoke", mapOf("deviceId" to id))
        assertEquals("device_revoked", signed("asst1", "offline_sync", id, mapOf("ops" to emptyList<Any>())).code)
    }

    @Test
    fun `chamber fee - doctor and assistant issue receipts in the device series, then a payment by opId`() {
        doctor("doc1", assistants = listOf("asst1@example.com"))
        user("asst1", mapOf("name" to "সহকারী", "lang" to "bn"), email = "asst1@example.com")
        val id = deviceOf("doc1", "chamber")
        val today = DhakaTime.day().toString()
        val fee = mapOf(
            "no" to "D1-000001", "walkIn" to mapOf("name" to "রহিম", "chamberKey" to "hospA1", "date" to today), "visitType" to "new",
            "amount" to 500, "method" to "cash",
        )
        // chamber receipts switched off: not stored, the same op goes through once they are on
        val feeOp = op("fee", fee)
        invoiceFeatures.forced["inv_chamber"] = false
        val off = sync("asst1", id, feeOp)["results"][0]
        assertEquals("feature_off", off["error"].textValue(), off.toString())
        assertTrue(off["retry"].booleanValue())
        invoiceFeatures.forced.clear()
        val r = sync("asst1", id, feeOp)["results"][0]
        assertTrue(r["ok"].booleanValue(), r.toString())
        assertEquals("D1-000001", r["no"].textValue())
        val r2 = sync("doc1", id, op("fee", fee + mapOf("no" to "D1-000002")))["results"][0]
        assertTrue(r2["ok"].booleanValue(), r2.toString())
        assertEquals(2, invoices("doc1"))
        // a fee for another doctor's appointment
        doctor("doc2")
        appointment("ap9", "doc2", "pat9")
        val r3 = sync("doc1", id, op("fee", mapOf("no" to "D1-000003", "appointmentId" to "ap9", "visitType" to "new", "amount" to 500, "method" to "cash")))["results"][0]
        assertEquals("bad_ref", r3["error"].textValue())
    }

    // ------------------------------------------------------------------ demo / real

    @Test
    fun `demo and real never mix`() {
        pharmacy("ph1")
        pharmacy("demo-ph1")
        doctor("doc1", assistants = listOf("demo-asst@example.com"))
        user("demo-asst", mapOf("name" to "ডেমো", "lang" to "bn"), email = "demo-asst@example.com")
        val real = deviceOf("ph1", "pharmacy")
        val demo = deviceOf("demo-ph1", "pharmacy")
        assertEquals(true, row("SELECT is_demo FROM desktop_devices WHERE id = ?", demo)["is_demo"])
        assertEquals(false, row("SELECT is_demo FROM desktop_devices WHERE id = ?", real)["is_demo"])
        // a demo account never reaches a real business or device (and the other way round)
        assertEquals(403, call("demo-ph1", "desktop_register", mapOf("kind" to "pharmacy", "bizId" to "ph1", "name" to "PC")).status)
        assertEquals(404, signed("demo-ph1", "offline_sync", real, mapOf("ops" to emptyList<Any>())).status)
        assertEquals(404, signed("ph1", "offline_bootstrap", demo, emptyMap()).status)
        // a demo assistant on a real doctor's list is not let in
        val docDev = deviceOf("doc1", "chamber")
        assertEquals(404, signed("demo-asst", "offline_bootstrap", docDev, emptyMap()).status)
        assertEquals(403, call("demo-asst", "desktop_devices", mapOf("kind" to "chamber", "bizId" to "doc1")).status)
        // the demo shop works on its own
        val napa = item("demo-ph1", "Napa", 3)
        assertTrue(sync("demo-ph1", demo, op("sale", sale("D1-000001", napa, 1)))["results"][0]["ok"].booleanValue())
        assertEquals(true, row("SELECT is_demo FROM invoices WHERE biz_id = 'demo-ph1'")["is_demo"])
        assertEquals(1, ok("ph1", "desktop_devices", mapOf("kind" to "pharmacy"))["items"].size())
    }

    // ------------------------------------------------------------------ feature switch `desktop`

    @Test
    fun `switch desktop - off refuses everything new, pilot lets only pilot businesses in, a registered desk keeps syncing after off`() {
        pharmacy("ph1")
        pharmacy("ph2")
        pharmacy("demo-ph9")
        feature.mode = DesktopFeature.OFF
        val off = call("ph1", "desktop_register", mapOf("kind" to "pharmacy", "name" to "PC"))
        assertEquals(404, off.status)
        assertEquals("feature_off", off.code)
        assertEquals("এই সুবিধা এখনো চালু হয়নি", off.error)
        assertEquals("feature_off", call("ph1", "desktop_devices", mapOf("kind" to "pharmacy")).code)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM desktop_devices", Int::class.java))

        // pilot: only the pilot owners (and demo accounts when demo is on)
        feature.mode = DesktopFeature.PILOT
        feature.pilotUids = setOf("ph1")
        val id = deviceOf("ph1", "pharmacy")
        assertEquals("feature_off", call("ph2", "desktop_register", mapOf("kind" to "pharmacy", "name" to "PC")).code)
        assertEquals("feature_off", call("demo-ph9", "desktop_register", mapOf("kind" to "pharmacy", "name" to "PC")).code)
        feature.demo = true
        deviceOf("demo-ph9", "pharmacy")
        val napa = item("ph1", "Napa", 10)
        assertEquals(200, signed("ph1", "offline_bootstrap", id, emptyMap()).status)

        // back to off: no new desks, no new snapshots — but the registered desk's sales still come in, and it can be removed
        feature.mode = DesktopFeature.OFF
        assertEquals("feature_off", call("ph1", "desktop_register", mapOf("kind" to "pharmacy", "name" to "PC2")).code)
        assertEquals("feature_off", signed("ph1", "offline_bootstrap", id, emptyMap()).code)
        val r = sync("ph1", id, op("sale", sale("D1-000001", napa, 2)))["results"][0]
        assertTrue(r["ok"].booleanValue(), r.toString())
        assertEquals(8.0, qty("ph1", napa))
        assertEquals(1, ok("ph1", "desktop_devices", mapOf("kind" to "pharmacy"))["active"].intValue())
        assertTrue(ok("ph1", "desktop_revoke", mapOf("deviceId" to id))["revoked"].booleanValue())
        assertEquals("feature_off", call("ph1", "desktop_devices", mapOf("kind" to "pharmacy")).code)
    }
}
