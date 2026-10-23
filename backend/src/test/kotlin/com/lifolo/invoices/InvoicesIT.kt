package com.lifolo.invoices

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.pharmacy.P4Test
import com.lifolo.users.AccountDeletionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Receipts (docs/INVOICES.md): every action and rule, the security-review items (docs/test-plan/INVOICES-SECURITY-REVIEW.md) */
class InvoicesIT : P4Test() {
    @Autowired lateinit var invoices: InvoiceService

    @Autowired lateinit var summary: InvoiceSummary

    @Autowired lateinit var deletion: AccountDeletionService

    @Autowired lateinit var features: InvoiceFeatures

    @Autowired lateinit var creators: InvoiceCreators

    @org.junit.jupiter.api.AfterEach
    fun featuresBack() {
        features.forced.clear()
    }

    private val PH = "invPh1"
    private val PAT = "invPat1"
    private val CG = "invCg1"
    private val LAB = "invLab1"
    private val DOC = "invDoc1"
    private val ASST = "invAsst1"

    // ------------------------------------------------------------------ helpers

    private fun fails(uid: String?, action: String, body: Map<String, Any?>, status: Int, code: String? = null): Res {
        val r = call(uid, action, body)
        assertEquals(status, r.status, "$action: $r")
        if (code != null) assertEquals(code, r.code, "$action: $r")
        return r
    }

    private fun num(n: JsonNode): Double = n.doubleValue()

    private fun lab(uid: String, status: String = "approved", license: String = "DGHS-1") {
        person(uid, "ল্যাব মালিক")
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, 'নিরাময় ল্যাব', ?, 'চরপাড়া', ?, '01711000498', ?)",
            uid, MYMENSINGH, license, status,
        )
        jdbc.update("INSERT INTO lab_prices (lab_uid, discount_pct) VALUES (?, NULL)", uid)
        jdbc.update("INSERT INTO lab_price_items (lab_uid, idx, code, name, price) VALUES (?, 0, 'cbc', 'CBC', 400), (?, 1, 'rbs', 'RBS', 150)", uid, uid)
    }

    private fun doctor(uid: String, assistantEmail: String? = null) {
        person(uid, "ডা. হাসান")
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, status) VALUES (?, 'doctor', 'A-1', 'X', 'ডা. হাসান', 'approved')", uid)
        jdbc.update("INSERT INTO providers (uid, type, name, degrees, specialties, listed) VALUES (?, 'doctor', 'ডা. হাসান', 'MBBS', '{medicine}', TRUE)", uid)
        jdbc.update(
            "INSERT INTO provider_chambers (provider_uid, idx, hospital_id, hospital_name, hospital_name_bn) VALUES (?, 0, 'h1', 'City Hospital', 'সিটি হাসপাতাল')",
            uid,
        )
        if (assistantEmail != null) jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, 0)", uid, assistantEmail)
    }

    private fun appt(id: String, patient: String, doctor: String, status: String = "confirmed") {
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, chamber_bn, chamber_key, visit_date, status)
            VALUES (?, ?, ?, 'রোগী রহিম', 'ডা. হাসান', 'doctor', 'City Hospital', 'সিটি হাসপাতাল', 'h1', CURRENT_DATE, ?)
            """.trimIndent(),
            id, patient, doctor, status,
        )
    }

    private fun labOrder(id: String, lab: String, patient: String?) {
        jdbc.update(
            """
            INSERT INTO lab_orders (id, lab_uid, lab_name, patient_uid, patient_name, tests, status, price_discount_pct, home_status, home_fee)
            VALUES (?, ?, 'নিরাময় ল্যাব', ?, 'রোগী রহিম', '{cbc,rbs}', 'received', 10, 'confirmed', 200)
            """.trimIndent(),
            id, lab, patient,
        )
        jdbc.update("INSERT INTO lab_order_price_items (order_id, idx, test, name, price) VALUES (?, 0, 'cbc', 'CBC', 400), (?, 1, 'rbs', 'RBS', 150)", id, id)
    }

    private fun issue(biz: String, total: Int, patient: String? = null, kind: String = "pharmacy", stamp: DeviceStamp? = null, actor: AuthUser = AuthUser(biz), customer: Customer = Customer("ক্রেতা", "01799000000")): Invoice =
        invoices.issue("bn", actor, IssueCmd(kind, biz, listOf(LineIn("ওষুধ", BigDecimal.ONE, BigDecimal(total))), customer = customer, patientUid = patient), stamp)

    private fun inboxData(uid: String): List<JsonNode> = inbox(uid).map { mapper.readTree(it["data"] as String) }

    // ------------------------------------------------------------------ pharmacy sale

    @Test
    fun `pharmacy sale - the receipt's totals are the server's, numbered W-000001…, customer and payments kept`() {
        shop(PH)
        val r = ok(
            PH, "books_sale_add",
            mapOf(
                "lines" to listOf(
                    mapOf("name" to "Napa", "qty" to 10, "unitPrice" to 2.5, "unitDisc" to 0.5, "amount" to 1),
                    mapOf("name" to "Seclo", "qty" to 3, "unitPrice" to 7),
                ),
                "billDisc" to mapOf("type" to "amt", "v" to 1),
                "customer" to mapOf("name" to "রহিম", "phone" to "01711-000000"),
                "payments" to listOf(mapOf("amount" to 30, "method" to "bkash", "ref" to "TRX1")),
                "total" to 1, "subtotal" to 99999, "paid" to 9999,
            ),
        )
        val inv = r["invoice"]
        assertEquals("W-000001", inv["no"].textValue())
        assertEquals("pharmacy", inv["kind"].textValue())
        assertEquals(PH, inv["bizId"].textValue())
        assertEquals("দোকান $PH", inv["bizName"].textValue())
        assertEquals(listOf(20.0, 21.0), inv["lines"].map { num(it["amount"]) })
        assertEquals(5.0, num(inv["lines"][0]["disc"]))
        assertEquals(41.0, num(inv["subtotal"]))
        assertEquals(1.0, num(inv["disc"]))
        assertEquals(40.0, num(inv["total"]))
        assertEquals(num(r["sale"]["totals"]["net"]), num(inv["total"]), "the books and the receipt agree")
        assertEquals(30.0, num(inv["paid"]))
        assertEquals(10.0, num(inv["due"]))
        assertEquals("bkash", inv["payments"][0]["method"].textValue())
        assertEquals("TRX1", inv["payments"][0]["ref"].textValue())
        assertEquals("রহিম", inv["customer"]["name"].textValue())
        assertEquals("01711000000", inv["customer"]["phone"].textValue())
        assertEquals("issued", inv["status"].textValue())
        assertEquals(false, inv["offline"].booleanValue())
        assertEquals(PH, inv["issuedBy"].textValue())
        assertEquals("book_sale", inv["refType"].textValue())
        assertEquals(r["id"].textValue(), inv["refId"].textValue())
        assertTrue(Regex("^[A-Za-z0-9]{10}$").matches(inv["verifyCode"].textValue()))
        assertEquals("https://lifolo.com/v/" + inv["verifyCode"].textValue(), inv["verifyUrl"].textValue())
        assertEquals(InvoiceMath.FOOTER, inv["footer"].textValue())
        assertFalse(inv.toString().contains("VAT"), "never a VAT invoice")
        assertNull(inv["patientUid"])
        // the next one: W-000002, fully paid in cash by default; the books' plain customer name still works
        val r2 = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 2, "unitPrice" to 2.5)), "customer" to "করিম"))["invoice"]
        assertEquals("W-000002", r2["no"].textValue())
        assertEquals(5.0, num(r2["paid"]))
        assertEquals(0.0, num(r2["due"]))
        assertEquals("cash", r2["payments"][0]["method"].textValue())
        assertEquals("করিম", r2["customer"]["name"].textValue())
        // audit: one issue event each
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM invoice_events WHERE action = 'issue'", Int::class.java))
        // overpaying, a bad method, a bad phone → 400 and no sale recorded
        val before = jdbc.queryForObject("SELECT count(*) FROM shop_sales", Int::class.java)
        fails(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "payments" to listOf(mapOf("amount" to 6))), 400, "overpaid")
        fails(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "payments" to listOf(mapOf("amount" to 5, "method" to "gold"))), 400, "method")
        fails(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "customer" to mapOf("phone" to "abc")), 400)
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM shop_sales", Int::class.java))
        // a shop that is not approved still keeps its books, without a receipt
        shop("invPh2", mapOf("status" to "pending"))
        val r3 = ok("invPh2", "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5))))
        assertTrue(r3["invoice"].isNull)
        assertTrue(r3["sale"].isObject)
    }

    @Test
    fun `I3 - a typed phone of a registered user never links the receipt to them, the answer looks the same`() {
        shop(PH)
        user(PAT, mapOf("name" to "রোগী", "phone" to "01711000000", "lang" to "bn"))
        val a = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "customer" to mapOf("phone" to "01711000000")))["invoice"]
        val b = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "customer" to mapOf("phone" to "01722000000")))["invoice"]
        assertNull(a["patientUid"])
        assertEquals(a.fieldNames().asSequence().toList(), b.fieldNames().asSequence().toList())
        assertEquals(emptyList<Any>(), inbox(PAT))
        assertEquals(0, ok(PAT, "my_invoices")["items"].size())
    }

    @Test
    fun `void - a fixed reason, the pharmacy stock goes back, the sale is gone, deleting an invoiced sale is refused`() {
        shop(PH)
        val item = ok(PH, "books_item_save", mapOf("name" to "Napa", "qty" to 10, "price" to 2, "cost" to 1))["item"]["id"].textValue()
        val sale = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to item, "qty" to 3))))
        val inv = sale["invoice"]
        val id = inv["id"].textValue()
        fun qty() = (row("SELECT qty FROM shop_items WHERE shop_uid = ? AND id = ?", PH, item)["qty"] as Number).toDouble()
        assertEquals(7.0, qty())
        // the old delete button: refused while the receipt lives
        fails(PH, "books_sale_delete", mapOf("id" to sale["id"].textValue()), 409, "invoice_exists")
        assertEquals(7.0, qty())
        fails(PH, "invoice_void", mapOf("id" to id), 400, "reason")
        fails(PH, "invoice_void", mapOf("id" to id, "reason" to "because"), 400, "reason")
        fails(PH, "invoice_void", mapOf("id" to id, "reason" to "other"), 400, "reason")
        shop("invPh9")
        fails("invPh9", "invoice_void", mapOf("id" to id, "reason" to "returned"), 404)
        val v = ok(PH, "invoice_void", mapOf("id" to id, "reason" to "returned", "note" to "ফেরত"))["invoice"]
        assertEquals("void", v["status"].textValue())
        assertEquals("returned", v["voidReason"].textValue())
        assertEquals("ফেরত", v["voidNote"].textValue())
        assertTrue(v["voidedAt"].isNumber)
        assertEquals(10.0, qty(), "stock back like books_sale_delete")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shop_sales WHERE shop_uid = ?", Int::class.java, PH))
        fails(PH, "invoice_void", mapOf("id" to id, "reason" to "returned"), 409, "already_void")
        fails(PH, "invoice_pay", mapOf("id" to id, "amount" to 1, "method" to "cash"), 409, "void")
        assertEquals(listOf("issue", "void"), jdbc.queryForList("SELECT action FROM invoice_events WHERE invoice_id = ? ORDER BY id", String::class.java, id))
        // a sale from before receipts (none issued) still deletes
        val old = ok("invPh2".also { shop(it, mapOf("status" to "pending")) }, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5))))
        ok("invPh2", "books_sale_delete", mapOf("id" to old["id"].textValue()))
    }

    @Test
    fun `immutability - the database refuses any change of what was issued`() {
        shop(PH)
        val inv = issue(PH, 100)
        val id = inv.id
        for (sql in listOf(
            "UPDATE invoices SET total = 1 WHERE id = ?",
            "UPDATE invoices SET lines = '[]' WHERE id = ?",
            "UPDATE invoices SET no = 'W-000099' WHERE id = ?",
            "UPDATE invoices SET issued_at = now() - interval '1 day' WHERE id = ?",
            "UPDATE invoices SET biz_name = 'অন্য' WHERE id = ?",
            "UPDATE invoices SET verify_code = 'AAAAAAAAAA' WHERE id = ?",
            "UPDATE invoices SET customer_name = 'অন্য' WHERE id = ?",
            "UPDATE invoices SET paid = 0, due = 100 WHERE id = ?",
            "UPDATE invoice_payments SET amount = 1 WHERE invoice_id = ?",
            "UPDATE invoice_payments SET method = 'card' WHERE invoice_id = ?",
        )) {
            assertThrows(Exception::class.java, { jdbc.update(sql, id) }, sql)
        }
        invoices.void("bn", AuthUser(PH), id, "duplicate")
        assertThrows(Exception::class.java) { jdbc.update("UPDATE invoices SET status = 'issued' WHERE id = ?", id) }
        assertThrows(Exception::class.java) { jdbc.update("UPDATE invoices SET void_reason = 'other' WHERE id = ?", id) }
        val now = invoices.load(id)!!
        assertEquals(BigDecimal("100.00"), now.total)
        assertEquals("void", now.status)
        // there is no delete or edit action
        assertTrue(listOf("invoice_delete", "invoice_update", "invoice_edit").all { call(PH, it).status == 400 })
    }

    @Test
    fun `pay - only the issuer, never more than due, then nothing due`() {
        shop(PH)
        val inv = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 10, "unitPrice" to 10)), "payments" to emptyList<Any>()))["invoice"]
        val id = inv["id"].textValue()
        assertEquals(100.0, num(inv["due"]))
        assertEquals(0, inv["payments"].size())
        fails(PH, "invoice_pay", mapOf("id" to id, "amount" to 101, "method" to "cash"), 400, "overpaid")
        fails(PH, "invoice_pay", mapOf("id" to id, "amount" to 10, "method" to "gold"), 400, "method")
        fails(PH, "invoice_pay", mapOf("id" to id, "amount" to 0, "method" to "cash"), 400)
        fails(null, "invoice_pay", mapOf("id" to id, "amount" to 10, "method" to "cash"), 401)
        person("invOther")
        fails("invOther", "invoice_pay", mapOf("id" to id, "amount" to 10, "method" to "cash"), 404)
        val p1 = ok(PH, "invoice_pay", mapOf("id" to id, "amount" to 40, "method" to "nagad", "ref" to "N1"))["invoice"]
        assertEquals(40.0, num(p1["paid"]))
        assertEquals(60.0, num(p1["due"]))
        val p2 = ok(PH, "invoice_pay", mapOf("id" to id, "amount" to "60", "method" to "cash"))["invoice"]
        assertEquals(0.0, num(p2["due"]))
        assertEquals(listOf("nagad", "cash"), p2["payments"].map { it["method"].textValue() })
        fails(PH, "invoice_pay", mapOf("id" to id, "amount" to 1, "method" to "cash"), 409, "nothing_due")
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM invoice_events WHERE invoice_id = ? AND action = 'pay'", Int::class.java, id))
    }

    // ------------------------------------------------------------------ verify

    @Test
    fun `verify - public, only no, date, totals, line count and status, void, deleted and purged codes still answer`() {
        lab(LAB)
        person(PAT, "রোগী রহিম")
        labOrder("lo1", LAB, PAT)
        val inv = ok(LAB, "lab_bill_create", mapOf("labOrderId" to "lo1", "customer" to mapOf("phone" to "01755000000")))["invoice"]
        val code = inv["verifyCode"].textValue()
        val v = ok(null, "invoice_verify", mapOf("code" to code))["invoice"]
        assertEquals(setOf("bizName", "kind", "no", "date", "total", "paid", "due", "lines", "status", "demo", "footer"), v.fieldNames().asSequence().toSet())
        assertEquals("valid", v["status"].textValue())
        assertEquals(2, v["lines"].intValue())
        assertEquals(695.0, num(v["total"]))
        assertEquals(false, v["demo"].booleanValue())
        val text = v.toString()
        for (secret in listOf("রোগী রহিম", "01755000000", PAT, "CBC", LAB, "patient", "customer", "issuedBy")) assertFalse(text.contains(secret), secret)
        fails(null, "invoice_verify", mapOf("code" to "ZZZZZZZZZZ"), 404)
        fails(null, "invoice_verify", mapOf("code" to "../x"), 404)
        // void → still answers, with the time
        ok(LAB, "invoice_void", mapOf("id" to inv["id"].textValue(), "reason" to "wrong_price"))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        val vv = ok(null, "invoice_verify", mapOf("code" to code))["invoice"]
        assertEquals("void", vv["status"].textValue())
        assertTrue(vv["voidedAt"].isNumber)
        // the lab owner's account is deleted → still answers; 6 years later (purged) → still answers, archived
        deletion.deleteNow(LAB, "$LAB@example.com")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertEquals("void", ok(null, "invoice_verify", mapOf("code" to code))["invoice"]["status"].textValue())
        assertEquals(1, invoices.purge(System.currentTimeMillis() + 7L * 366 * 24 * 3600 * 1000))
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        val gone = ok(null, "invoice_verify", mapOf("code" to code))["invoice"]
        assertEquals("void", gone["status"].textValue())
        assertEquals(true, gone["archived"].booleanValue())
        assertEquals(inv["no"].textValue(), gone["no"].textValue())
    }

    @Test
    fun `verify - rate limited per IP (30 a minute)`() {
        val codes = (1..31).map { "ABCDEFG" + it.toString().padStart(3, '0') }
        val statuses = codes.map { call(null, "invoice_verify", mapOf("code" to it), ip = "7.7.7.7").status }
        assertEquals(List(30) { 404 }, statuses.take(30))
        assertEquals(429, statuses[30])
        assertEquals(404, call(null, "invoice_verify", mapOf("code" to codes[0] + "x"), ip = "7.7.7.8").status, "another IP")
    }

    // ------------------------------------------------------------------ patient side, caregiver

    @Test
    fun `caregiver - reads the patient's receipts with asUid, the push goes to both, nothing on the business side`() {
        lab(LAB)
        person(PAT, "রোগী রহিম")
        person(CG, "কেয়ারগিভার")
        person("invCg2")
        person("invStranger")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active'), (?, 'invCg2', 'invited')", PAT, CG, PAT)
        labOrder("lo1", LAB, PAT)
        val inv = ok(LAB, "lab_bill_create", mapOf("labOrderId" to "lo1"))["invoice"]
        val id = inv["id"].textValue()
        assertEquals(PAT, inv["patientUid"].textValue())
        val pd = inboxData(PAT).last()
        assertEquals("invoice_new", pd["type"].textValue())
        assertEquals("my_request", pd["cat"].textValue())
        assertEquals(id, pd["invoiceId"].textValue())
        val cd = inboxData(CG).last()
        assertEquals("care", cd["type"].textValue())
        assertEquals(PAT, cd["asUid"].textValue())
        assertEquals("my_request", cd["cat"].textValue())
        // reads
        assertEquals(id, ok(PAT, "invoice_get", mapOf("id" to id))["invoice"]["id"].textValue())
        assertEquals(id, ok(CG, "invoice_get", mapOf("id" to id, "asUid" to PAT))["invoice"]["id"].textValue())
        assertEquals(listOf(id), ok(CG, "my_invoices", mapOf("asUid" to PAT))["items"].map { it["id"].textValue() })
        assertEquals(695.0, num(ok(CG, "my_expenses", mapOf("asUid" to PAT))["total"]))
        fails(CG, "invoice_get", mapOf("id" to id), 404)
        assertEquals(0, ok(CG, "my_invoices")["items"].size())
        fails("invStranger", "invoice_get", mapOf("id" to id), 404)
        fails("invCg2", "invoice_get", mapOf("id" to id, "asUid" to PAT), 403, "care_revoked")
        // the patient side only: no pay/void/list as the patient's caregiver, nor as the patient
        fails(CG, "invoice_void", mapOf("id" to id, "reason" to "other", "note" to "x", "asUid" to PAT), 403)
        fails(PAT, "invoice_void", mapOf("id" to id, "reason" to "other", "note" to "x"), 404)
        fails(PAT, "invoice_pay", mapOf("id" to id, "amount" to 1, "method" to "cash"), 404)
        fails(PAT, "invoice_list_biz", mapOf("kind" to "lab", "bizId" to LAB), 403, "not_issuer")
    }

    @Test
    fun `my_invoices pages, my_expenses per kind without void receipts`() {
        shop(PH)
        lab(LAB)
        doctor(DOC)
        person(PAT)
        val a = issue(PH, 100, PAT)
        issue(LAB, 400, PAT, kind = "lab")
        issue(DOC, 800, PAT, kind = "chamber")
        val voided = issue(PH, 50, PAT)
        invoices.void("bn", AuthUser(PH), voided.id, "wrong_item")
        val e = ok(PAT, "my_expenses")
        assertEquals(1300.0, num(e["total"]))
        assertEquals(3, e["count"].intValue())
        assertEquals(mapOf("pharmacy" to 100, "lab" to 400, "chamber" to 800, "ambulance" to 0), mapper.convertValue(e["byKind"], Map::class.java))
        assertEquals(0, ok(PAT, "my_expenses", mapOf("month" to "2020-01"))["count"].intValue())
        fails(PAT, "my_expenses", mapOf("month" to "2026-13"), 400)
        val all = ok(PAT, "my_invoices")["items"]
        assertEquals(4, all.size(), "a void receipt is still listed (with its status)")
        assertTrue(ok(PAT, "my_invoices")["cursor"].isNull)
        // 35 more → two pages
        repeat(35) { issue(PH, 1, PAT, stamp = DeviceStamp("dev1", "D1-" + (it + 1).toString().padStart(6, '0'), System.currentTimeMillis() - 1000L * (it + 1))) }
        val p1 = ok(PAT, "my_invoices")
        assertEquals(30, p1["items"].size())
        val p2 = ok(PAT, "my_invoices", mapOf("cursor" to p1["cursor"].textValue()))
        assertEquals(9, p2["items"].size())
        assertTrue((p1["items"].map { it["id"].textValue() } + p2["items"].map { it["id"].textValue() }).contains(a.id))
    }

    // ------------------------------------------------------------------ lab

    @Test
    fun `lab bill - lines from the order's price snapshot, its discount and the home fee, once, walk-in uses list prices`() {
        lab(LAB)
        person(PAT)
        labOrder("lo1", LAB, PAT)
        val inv = ok(LAB, "lab_bill_create", mapOf("labOrderId" to "lo1"))["invoice"]
        assertEquals(listOf("CBC", "RBS"), inv["lines"].map { it["name"].textValue() })
        assertEquals(listOf("cbc", "rbs"), inv["lines"].map { it["code"].textValue() })
        assertEquals(550.0, num(inv["subtotal"]))
        assertEquals(55.0, num(inv["disc"]))
        assertEquals("হোম কালেকশন", inv["fees"][0]["label"].textValue())
        assertEquals(695.0, num(inv["total"]))
        assertEquals("lab_order", inv["refType"].textValue())
        assertEquals("রোগী রহিম", inv["customer"]["name"].textValue())
        fails(LAB, "lab_bill_create", mapOf("labOrderId" to "lo1"), 409, "already_issued")
        assertEquals(inv["id"].textValue(), ok(PAT, "lab_order_get", mapOf("id" to "lo1"))["item"]["invoiceId"].textValue())
        fails(LAB, "lab_bill_create", mapOf("labOrderId" to "nope"), 404)
        // someone else's order
        lab("invLab2", license = "DGHS-2")
        fails("invLab2", "lab_bill_create", mapOf("labOrderId" to "lo1"), 404)
        // walk-in: a code without a price takes the list price; bill discount and fees as given; partly paid
        val w = ok(
            LAB, "lab_bill_create",
            mapOf(
                "lines" to listOf(mapOf("name" to "CBC", "code" to "cbc"), mapOf("name" to "Urine R/E", "price" to 200)), "disc" to 100,
                "fees" to listOf(mapOf("label" to "রিপোর্ট ডেলিভারি", "amount" to 50)), "customer" to mapOf("name" to "ওয়াক-ইন"),
                "payments" to listOf(mapOf("amount" to 300, "method" to "cash")),
            ),
        )["invoice"]
        assertEquals(listOf(400.0, 200.0), w["lines"].map { num(it["price"]) })
        assertEquals(550.0, num(w["total"]))
        assertEquals(250.0, num(w["due"]))
        assertNull(w["patientUid"])
        fails(LAB, "lab_bill_create", emptyMap(), 400)
        fails(LAB, "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "code" to "zzz"))), 400)
        fails(LAB, "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "price" to 10)), "disc" to 11), 400)
    }

    @Test
    fun `sanctions - a pending, suspended or banned business issues nothing`() {
        lab("invLabP", status = "pending", license = "DGHS-7")
        fails("invLabP", "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "price" to 10))), 403, "biz_not_approved")
        lab("invLabS", status = "suspended", license = "DGHS-8")
        fails("invLabS", "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "price" to 10))), 403, "biz_sanctioned")
        lab("invLabB", license = "DGHS-9")
        jdbc.update("INSERT INTO sanctions (key, listing, kind) VALUES (?, TRUE, 'lab')", com.lifolo.admin.kernel.Sanctions.key("lic", com.lifolo.common.Phones.normId("DGHS-9")))
        fails("invLabB", "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "price" to 10))), 403, "biz_sanctioned")
        doctor(DOC)
        jdbc.update("UPDATE providers SET admin_suspended = TRUE WHERE uid = ?", DOC)
        fails(DOC, "chamber_fee_collect", mapOf("walkIn" to mapOf("name" to "ক", "chamberKey" to "h1"), "visitType" to "new", "amount" to 500), 403, "biz_sanctioned")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invoices", Int::class.java))
    }

    // ------------------------------------------------------------------ chamber, assistants (I7)

    @Test
    fun `chamber fee - doctor and assistant, an assistant voids only their own within 2 h and the doctor is told, owner-only export`() {
        doctor(DOC, assistantEmail = "asst@example.com")
        user(ASST, mapOf("name" to "সহকারী", "lang" to "bn"), email = "asst@example.com")
        person(PAT)
        appt("ap1", PAT, DOC)
        val inv = ok(DOC, "chamber_fee_collect", mapOf("appointmentId" to "ap1", "visitType" to "new", "amount" to 800, "method" to "cash"))["invoice"]
        assertEquals(PAT, inv["patientUid"].textValue())
        assertEquals("appointment", inv["refType"].textValue())
        assertEquals("সিটি হাসপাতাল", inv["bizAddress"].textValue())
        assertEquals(800.0, num(inv["paid"]))
        assertTrue(inv["lines"][0]["name"].textValue().contains("নতুন রোগী"))
        fails(DOC, "chamber_fee_collect", mapOf("appointmentId" to "ap1", "visitType" to "new", "amount" to 800), 409, "already_issued")
        assertEquals(inv["id"].textValue(), ok(PAT, "my_appointments")["items"].first { it["id"].textValue() == "ap1" }["invoiceId"].textValue())
        val chs = ok(ASST, "assistant_chambers", mapOf("doctorUid" to DOC))
        assertEquals(listOf("h1"), chs["chambers"].map { it["chamberKey"].textValue() })
        assertEquals("সিটি হাসপাতাল", chs["chambers"][0]["nameBn"].textValue())
        fails("invPat1", "assistant_chambers", mapOf("doctorUid" to DOC), 403, "not_issuer")
        fails(DOC, "chamber_fee_collect", mapOf("appointmentId" to "ap1", "visitType" to "x", "amount" to 800), 400)
        fails(DOC, "chamber_fee_collect", mapOf("walkIn" to mapOf("name" to "ক", "chamberKey" to "nope"), "visitType" to "new", "amount" to 500), 404)
        fails(DOC, "chamber_fee_collect", mapOf("walkIn" to mapOf("chamberKey" to "h1"), "visitType" to "new", "amount" to 500), 400)
        // the assistant, for the doctor (walk-in, no patient link)
        val w = ok(
            ASST, "chamber_fee_collect",
            mapOf("doctorUid" to DOC, "walkIn" to mapOf("name" to "হাসিনা", "phone" to "01811000000", "chamberKey" to "h1", "date" to "2026-10-23"), "visitType" to "followup", "amount" to 400, "method" to "bkash", "ref" to "B1"),
        )["invoice"]
        assertEquals(ASST, w["issuedBy"].textValue())
        assertEquals(DOC, w["bizId"].textValue())
        assertEquals("W-000002", w["no"].textValue())
        assertNull(w["patientUid"])
        assertEquals("হাসিনা", w["customer"]["name"].textValue())
        // not an assistant
        person("invNobody")
        fails("invNobody", "chamber_fee_collect", mapOf("doctorUid" to DOC, "walkIn" to mapOf("name" to "ক", "chamberKey" to "h1"), "visitType" to "new", "amount" to 500), 403, "not_issuer")
        // the assistant may not void the doctor's receipt, an old one of their own neither
        fails(ASST, "invoice_void", mapOf("id" to inv["id"].textValue(), "reason" to "wrong_price"), 403, "assistant_void")
        val asst = AuthUser(ASST, email = "asst@example.com", emailVerified = true)
        val old = invoices.issue(
            "bn", asst, IssueCmd("chamber", DOC, listOf(LineIn("ফি", BigDecimal.ONE, BigDecimal(300)))),
            DeviceStamp("dev9", "D9-000001", System.currentTimeMillis() - 3 * 3600 * 1000L),
        )
        fails(ASST, "invoice_void", mapOf("id" to old.id, "reason" to "wrong_price"), 403, "assistant_void")
        // their own within 2 h: ok, and the doctor gets a push with the reason
        jdbc.update("DELETE FROM inbox_items")
        ok(ASST, "invoice_void", mapOf("id" to w["id"].textValue(), "reason" to "wrong_price", "note" to "৫০০ হবে"))
        val doc = inbox(DOC).single()
        assertTrue((doc["title"] as String).contains(w["no"].textValue()))
        assertTrue((doc["body"] as String).contains("ভুল দাম"))
        assertEquals("invoice_void", mapper.readTree(doc["data"] as String)["type"].textValue())
        // the doctor voids anything
        ok(DOC, "invoice_void", mapOf("id" to old.id, "reason" to "duplicate"))
        // lists: doctor and assistant; export: the doctor only
        assertEquals(3, ok(ASST, "invoice_list_biz", mapOf("kind" to "chamber", "bizId" to DOC, "period" to "7d"))["items"].size())
        fails(ASST, "invoice_export_csv", mapOf("kind" to "chamber", "bizId" to DOC), 403, "owner_only")
        val csv = ok(DOC, "invoice_export_csv", mapOf("kind" to "chamber", "bizId" to DOC))
        assertEquals("text/csv", csv["mime"].textValue())
        assertEquals(3, csv["count"].intValue())
        val text = csv["csv"].textValue()
        assertTrue(text.startsWith("﻿no,date,status,customer,lines,subtotal,discount,fees,total,paid,due,methods,void_reason,device"))
        assertTrue(text.contains("W-000001") && text.contains("wrong_price"))
        assertFalse(text.contains("01811000000"), "no customer phone in the export")
        for (w2 in listOf("VAT", "মূসক", "Mushak")) assertFalse(text.contains(w2))
        // requireOwner for the desktop agent
        assertThrows(ApiException::class.java) { invoices.requireOwner("bn", asst, "chamber", DOC) }
        assertEquals(DOC, invoices.requireOwner("bn", AuthUser(DOC), "chamber", DOC).id)
    }

    @Test
    fun `online chamber fee - confirming the bKash fee gives the patient a receipt once, none without a fee in numbers`() {
        doctor(DOC)
        person(PAT)
        appt("ap1", PAT, DOC)
        jdbc.update("UPDATE appointments SET online = TRUE, pay_state = 'claimed', pay_ref = 'TRX55', fee = '৫০০ টাকা' WHERE id = 'ap1'")
        val inv = creators.onlineFeePaid("bn", AuthUser(DOC), "ap1")!!
        assertEquals(BigDecimal("500.00"), inv.total)
        assertEquals("bkash", inv.payments.single().method)
        assertEquals("TRX55", inv.payments.single().ref)
        assertEquals(PAT, inv.patientUid)
        assertEquals("appointment", inv.refType)
        assertNull(creators.onlineFeePaid("bn", AuthUser(DOC), "ap1"), "once")
        appt("ap2", PAT, DOC)
        jdbc.update("UPDATE appointments SET online = TRUE, pay_state = 'claimed', fee = 'ফোনে জানুন' WHERE id = 'ap2'")
        assertNull(creators.onlineFeePaid("bn", AuthUser(DOC), "ap2"))
        person("invNobody")
        appt("ap3", PAT, DOC)
        jdbc.update("UPDATE appointments SET online = TRUE, pay_state = 'claimed', fee = '300' WHERE id = 'ap3'")
        assertNull(creators.onlineFeePaid("bn", AuthUser("invNobody"), "ap3"), "only the doctor's side")
    }

    // ------------------------------------------------------------------ online prescription order

    @Test
    fun `rx order - accepted with lines, out issues the receipt (due, delivery fee), delivered collects the due`() {
        shop(PH)
        person(PAT, "রোগী রহিম")
        rx("rx1", "RXCODE0001", PAT, listOf(Med("Napa")))
        order("o1", mapOf("rx_id" to "rx1", "patient_uid" to PAT, "pharmacy_uid" to PH, "pharmacy_name" to "দোকান", "patient_name" to "রোগী রহিম", "mode" to "online", "status" to "sent", "delivery_fee" to "৫০ টাকা", "phone" to "01811000000", "address" to "বাসা"))
        fails(PH, "rx_order_update", mapOf("id" to "o1", "status" to "accepted", "lines" to listOf(mapOf("name" to "Napa", "qty" to -1, "price" to 2))), 400)
        ok(PH, "rx_order_update", mapOf("id" to "o1", "status" to "accepted", "price" to "২০ টাকা", "lines" to listOf(mapOf("name" to "Napa", "qty" to 10, "price" to 2))))
        assertNotNull(row("SELECT invoice_lines FROM rx_orders WHERE id = 'o1'")["invoice_lines"])
        val out = ok(PH, "rx_order_update", mapOf("id" to "o1", "status" to "out"))["invoice"]
        assertEquals(70.0, num(out["total"]))
        assertEquals(0.0, num(out["paid"]))
        assertEquals(70.0, num(out["due"]))
        assertEquals("ডেলিভারি চার্জ", out["fees"][0]["label"].textValue())
        assertEquals(PAT, out["patientUid"].textValue())
        assertEquals("rx_order", out["refType"].textValue())
        assertEquals("invoice_new", inboxData(PAT).last()["type"].textValue())
        val del = ok(PH, "rx_order_update", mapOf("id" to "o1", "status" to "delivered"))["invoice"]
        assertEquals(out["id"].textValue(), del["id"].textValue())
        assertEquals(0.0, num(del["due"]))
        assertEquals("delivered", row("SELECT status FROM rx_orders WHERE id = 'o1'")["status"])
        assertEquals(out["id"].textValue(), ok(PAT, "rx_activity", mapOf("rxId" to "rx1"))["orders"][0]["invoiceId"].textValue())
        assertEquals(out["id"].textValue(), ok(PH, "rx_orders_pharmacy")["items"].first { it["id"].textValue() == "o1" }["invoiceId"].textValue())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invoices", Int::class.java))
        // an order without lines (an old app): status changes, no receipt
        order("o2", mapOf("rx_id" to "rx1", "patient_uid" to PAT, "pharmacy_uid" to PH, "mode" to "online", "status" to "sent"))
        val r = ok(PH, "rx_order_update", mapOf("id" to "o2", "status" to "out"))
        assertNull(r["invoice"])
        fails(PH, "rx_order_update", mapOf("id" to "o2", "status" to "delivered", "lines" to "x"), 400)
    }

    // ------------------------------------------------------------------ numbering

    @Test
    fun `series - W numbers are unique and gapless under concurrency, device numbers accepted once`() {
        shop(PH)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val jobs = (1..20).map {
            pool.submit(Callable { start.await(); issue(PH, it).no })
        }
        start.countDown()
        val nos = jobs.map { it.get() }
        pool.shutdown()
        assertEquals((1..20).map { "W-" + it.toString().padStart(6, '0') }.toSet(), nos.toSet())
        assertEquals(20, nos.distinct().size)
        // a desktop's own series
        val now = System.currentTimeMillis()
        val d = issue(PH, 5, stamp = DeviceStamp("dev1", "D1-000005", now - 60_000, verifyCode = "ABCDEFGHJK"))
        assertEquals("D1-000005", d.no)
        assertTrue(d.offline)
        assertEquals("dev1", d.deviceId)
        assertEquals("ABCDEFGHJK", d.verifyCode)
        assertEquals(now - 60_000, d.issuedAt)
        fun code(block: () -> Unit): String? = try {
            block()
            null
        } catch (e: ApiException) {
            e.code
        }
        assertEquals("no_taken", code { issue(PH, 5, stamp = DeviceStamp("dev1", "D1-000005", now)) })
        assertEquals("bad_no", code { issue(PH, 5, stamp = DeviceStamp("dev1", "W-000099", now)) })
        assertEquals("bad_no", code { issue(PH, 5, stamp = DeviceStamp("dev1", "D1-5", now)) })
        assertEquals("verify_collision", code { issue(PH, 5, stamp = DeviceStamp("dev1", "D1-000006", now, verifyCode = "ABCDEFGHJK")) })
        assertEquals("bad_verify", code { issue(PH, 5, stamp = DeviceStamp("dev1", "D1-000006", now, verifyCode = "abc")) })
        // renumbered by the sync: the printed number is kept and found
        val r = issue(PH, 5, stamp = DeviceStamp("dev1", "D1-000007", now, deviceNo = "D1-000004"))
        assertEquals("D1-000004", r.toMap()["deviceNo"])
        assertEquals(r.id, invoices.findByNo("pharmacy", PH, "D1-000004")!!.id)
        assertEquals(d.id, invoices.findByNo("pharmacy", PH, "D1-000005")!!.id)
        assertEquals(7, jdbc.queryForObject("SELECT last_seq FROM invoice_series WHERE kind = 'pharmacy' AND biz_id = ? AND series = 'D1'", Int::class.java, PH))
        assertEquals("W-000021", issue(PH, 5).no, "the desktop numbers don't move W")
        // another business starts at 1 again
        shop("invPh2")
        assertEquals("W-000001", issue("invPh2", 5).no)
    }

    // ------------------------------------------------------------------ rules

    @Test
    fun `rules - 423 for a deactivated account, issue rate limit, demo and real apart, demo can't rename`() {
        shop(PH)
        val inv = issue(PH, 10)
        jdbc.update("UPDATE accounts SET deactivated_at = now() WHERE uid = ?", PH)
        accounts.forgetAll()
        for (a in listOf("books_sale_add", "invoice_list_biz", "invoice_pay", "invoice_void", "invoice_get", "lab_bill_create", "chamber_fee_collect", "ambreq_finish", "my_invoices", "invoice_export_csv")) {
            assertEquals(423, call(PH, a, mapOf("id" to inv.id)).status, a)
        }
        jdbc.update("UPDATE accounts SET deactivated_at = NULL WHERE uid = ?", PH)
        accounts.forgetAll()
        // 60 receipts a minute per business
        val codes = (1..60).map {
            try {
                issue(PH, 1)
                null
            } catch (e: ApiException) {
                e.status
            }
        }
        assertEquals(59, codes.count { it == null })
        assertEquals(429, codes.last())
        // demo: a real patient is never linked, the demo flag shows everywhere; the demo shop keeps its name
        person(PAT)
        shop("demo-invph")
        val d = issue("demo-invph", 10, PAT)
        assertNull(d.patientUid)
        assertEquals(true, d.toMap()["demo"])
        assertEquals(true, ok(null, "invoice_verify", mapOf("code" to d.verifyCode))["invoice"]["demo"].booleanValue())
        fails(PAT, "invoice_get", mapOf("id" to d.id), 404)
        val save = mapOf(
            "name" to "নতুন নাম", "licenseNo" to "DL-1", "phone" to "01711111111", "district" to MYMENSINGH, "upazilaId" to 466,
            "address" to "চরপাড়া", "open24" to true, "termsAccepted" to true,
        )
        fails("demo-invph", "pharmacy_save", save, 403, "demo_locked")
        assertEquals("দোকান demo-invph", row("SELECT name FROM pharmacies WHERE uid = 'demo-invph'")["name"])
    }

    // ------------------------------------------------------------------ lists

    @Test
    fun `business list - period, search, sums, pages, phone masked and not searchable after 30 days`() {
        shop(PH)
        val now = System.currentTimeMillis()
        val old = issue(PH, 100, stamp = DeviceStamp("dev1", "D1-000001", now - 40L * 24 * 3600 * 1000), customer = Customer("পুরোনো", "01799111111"))
        val fresh = issue(PH, 50, customer = Customer("নতুন", "01799222222"))
        val voided = issue(PH, 30)
        invoices.void("bn", AuthUser(PH), voided.id, "wrong_item")
        val today = ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "today"))
        assertEquals(setOf(voided.id, fresh.id), today["items"].map { it["id"].textValue() }.toSet())
        assertEquals(1, today["sum"]["count"].intValue(), "void receipts are not in the sums")
        assertEquals(50.0, num(today["sum"]["total"]))
        val year = ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "365d"))["items"]
        val o = year.first { it["id"].textValue() == old.id }
        assertEquals("017••••••11", o["customer"]["phone"].textValue())
        assertEquals("01799222222", year.first { it["id"].textValue() == fresh.id }["customer"]["phone"].textValue())
        fun q(text: String) = ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "365d", "q" to text))["items"].map { it["id"].textValue() }
        assertEquals(emptyList<String>(), q("01799111111"))
        assertEquals(listOf(fresh.id), q("01799222222"))
        assertEquals(listOf(old.id), q("পুরোনো"))
        assertEquals(listOf(old.id), q("D1-000001"))
        assertEquals(emptyList<String>(), q("%"), "LIKE wildcards are plain text")
        // pages of 50
        repeat(55) { issue(PH, 1, stamp = DeviceStamp("dev2", "D2-" + (it + 1).toString().padStart(6, '0'), now - 1000L * (it + 1))) }
        val p1 = ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "7d"))
        assertEquals(50, p1["items"].size())
        assertEquals(56, p1["sum"]["count"].intValue(), "55 + the fresh one; the void one is not counted")
        val p2 = ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "7d", "cursor" to p1["cursor"].textValue()))
        assertEquals(7, p2["items"].size())
        assertTrue(p2["cursor"].isNull)
        // not the owner
        shop("invPh2")
        fails("invPh2", "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH), 403, "not_issuer")
        fails(PH, "invoice_list_biz", mapOf("kind" to "shop", "bizId" to PH), 404)
    }

    // ------------------------------------------------------------------ retention, summary

    @Test
    fun `retention - account deletion keeps receipts 6 years without personal fields, demo receipts go, purge after`() {
        shop(PH)
        person(PAT)
        val inv = invoices.issue("bn", AuthUser(PH), IssueCmd("pharmacy", PH, listOf(LineIn("ওষুধ", BigDecimal.ONE, BigDecimal(100))), payments = listOf(PaymentIn(BigDecimal(100), "bkash", "TRX9")), customer = Customer("রহিম", "01799000000"), patientUid = PAT))
        invoices.void("bn", AuthUser(PH), inv.id, "other", "রহিম ফেরত দিয়েছে")
        deletion.deleteNow(PAT, "$PAT@example.com")
        val a = row("SELECT * FROM invoices WHERE id = ?", inv.id)
        assertNull(a["patient_uid"])
        assertNull(a["customer_name"])
        assertNull(a["customer_phone"])
        assertNull(a["void_note"])
        assertNotNull(a["anonymised_at"])
        val keepYears = ((a["purge_after"] as Timestamp).time - System.currentTimeMillis()) / (365.0 * 24 * 3600 * 1000)
        assertTrue(keepYears in 5.99..6.1, "$keepYears")
        assertNull(row("SELECT ref FROM invoice_payments WHERE invoice_id = ?", inv.id)["ref"])
        assertEquals(PH, a["owner_uid"], "the business still has its receipt")
        assertEquals(1, ok(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH, "period" to "today"))["items"].size())
        // the business owner goes too
        deletion.deleteNow(PH, "$PH@example.com")
        val b = row("SELECT * FROM invoices WHERE id = ?", inv.id)
        assertNull(b["owner_uid"])
        assertNull(b["issued_by"])
        assertEquals("দোকান $PH", b["biz_name"], "the business name and the amounts stay")
        assertEquals(0, invoices.purge(System.currentTimeMillis()))
        assertEquals(1, invoices.purge(System.currentTimeMillis() + 7L * 366 * 24 * 3600 * 1000))
        assertTrue(row("SELECT 1 AS x FROM invoices WHERE id = ?", inv.id).isEmpty())
        // demo: deleted at once (the demo wipe)
        shop("demo-invph")
        val d = issue("demo-invph", 10)
        deletion.deleteNow("demo-invph", "demo-invph@example.com")
        assertTrue(row("SELECT 1 AS x FROM invoices WHERE id = ?", d.id).isEmpty())
    }

    @Test
    fun `end of day - the owner gets one summary with receipts, voids, large discounts and write-offs`() {
        shop(PH)
        val item = ok(PH, "books_item_save", mapOf("name" to "Napa", "qty" to 10, "price" to 10, "cost" to 5))["item"]["id"].textValue()
        ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to item, "qty" to 1))))
        ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to item, "qty" to 2, "unitDisc" to 5))))
        val v = issue(PH, 30)
        invoices.void("bn", AuthUser(PH), v.id, "duplicate")
        ok(PH, "books_adjust", mapOf("kind" to "writeoff", "itemId" to item, "qty" to 2, "reason" to "ভাঙা"))
        jdbc.update("DELETE FROM inbox_items")
        val now = System.currentTimeMillis()
        val day = summary.collect(now)[InvoiceSummary.Key(PH, "pharmacy", PH)]!!
        assertEquals(2, day.receipts)
        assertEquals(1, day.voids)
        assertEquals(1, day.bigDisc)
        assertEquals(1, day.writeOffs)
        assertEquals(0, BigDecimal(10).compareTo(day.writeOffLoss))
        assertEquals(1, summary.run(now))
        val push = inbox(PH).single()
        assertTrue((push["title"] as String).startsWith("দিনের শেষ"), push.toString())
        assertTrue((push["body"] as String).contains("বাতিল"))
        assertEquals("invoice_summary", mapper.readTree(push["data"] as String)["type"].textValue())
        val data = mapper.readTree(push["data"] as String)
        assertEquals("business", data["cat"].textValue())
        assertEquals("pharmacy", data["kind"].textValue())
        assertEquals(PH, data["bizId"].textValue())
        assertEquals(0, summary.run(now), "once a day")
        assertEquals(1, inbox(PH).size)
    }

    // ------------------------------------------------------------------ feature switches (OFF = the old behaviour)

    @Test
    fun `switches off - pharmacy sale, rx order and the other kinds behave as before, verify stays public`() {
        shop(PH)
        person(PAT, "রোগী রহিম")
        val before = issue(PH, 10, PAT)
        for (k in listOf("inv_pharmacy", "inv_lab", "inv_chamber", "inv_ambulance")) features.forced[k] = false
        // books_sale_add exactly as before: no invoice key, nothing issued
        val r = ok(PH, "books_sale_add", mapOf("lines" to listOf(mapOf("name" to "Napa", "qty" to 1, "unitPrice" to 5)), "payments" to "junk"))
        assertEquals(listOf("id", "sale"), r.fieldNames().asSequence().toList())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invoices", Int::class.java))
        // rx_order_update: no `delivered`, lines ignored, no receipt at out
        rx("rx1", "RXCODE0001", PAT, listOf(Med("Napa")))
        order("o1", mapOf("rx_id" to "rx1", "patient_uid" to PAT, "pharmacy_uid" to PH, "mode" to "online", "status" to "sent"))
        ok(PH, "rx_order_update", mapOf("id" to "o1", "status" to "accepted", "lines" to listOf(mapOf("name" to "Napa", "qty" to 10, "price" to 2))))
        assertNull(row("SELECT invoice_lines FROM rx_orders WHERE id = 'o1'")["invoice_lines"])
        fails(PH, "rx_order_update", mapOf("id" to "o1", "status" to "delivered"), 404)
        val out = ok(PH, "rx_order_update", mapOf("id" to "o1", "status" to "out"))
        assertEquals(listOf("ok"), out.fieldNames().asSequence().toList())
        // the business actions: feature_off
        fails(PH, "invoice_list_biz", mapOf("kind" to "pharmacy", "bizId" to PH), 404, "feature_off")
        fails(PH, "invoice_pay", mapOf("id" to before.id, "amount" to 1, "method" to "cash"), 404, "feature_off")
        fails(PH, "invoice_void", mapOf("id" to before.id, "reason" to "duplicate"), 404, "feature_off")
        lab(LAB)
        fails(LAB, "lab_bill_create", mapOf("lines" to listOf(mapOf("name" to "X", "price" to 10))), 404, "feature_off")
        doctor(DOC)
        fails(DOC, "chamber_fee_collect", mapOf("walkIn" to mapOf("name" to "ক", "chamberKey" to "h1"), "visitType" to "new", "amount" to 500), 404, "feature_off")
        fails(DOC, "assistant_chambers", emptyMap(), 404, "feature_off")
        appt("ap1", PAT, DOC)
        jdbc.update("UPDATE appointments SET fee = '৫০০ টাকা' WHERE id = 'ap1'")
        assertNull(creators.onlineFeePaid("bn", AuthUser(DOC), "ap1"), "appt_pay_confirm issues no receipt")
        person("invDriver")
        fails("invDriver", "ambreq_finish", mapOf("reqId" to "x1", "fare" to 500), 404, "feature_off")
        // the public check still answers for an existing receipt
        assertEquals("valid", ok(null, "invoice_verify", mapOf("code" to before.verifyCode))["invoice"]["status"].textValue())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invoices", Int::class.java))
    }

    @Test
    fun `switches off - my_expenses hides the patient's side and its push, only kinds that are on are shown`() {
        shop(PH)
        lab(LAB)
        person(PAT)
        features.forced["my_expenses"] = false
        val a = issue(PH, 100, PAT)
        assertEquals(emptyList<Any>(), inbox(PAT), "no invoice_new while my_expenses is off")
        fails(PAT, "my_invoices", emptyMap(), 404, "feature_off")
        fails(PAT, "my_expenses", emptyMap(), 404, "feature_off")
        fails(PAT, "invoice_get", mapOf("id" to a.id), 404, "feature_off")
        fails(PAT, "invoice_report", mapOf("id" to a.id), 404, "feature_off")
        // on again, but lab receipts off: lab ones are neither listed nor counted
        features.forced["my_expenses"] = true
        issue(LAB, 400, PAT, kind = "lab").also { features.forced["inv_lab"] = false }
        assertEquals(listOf(a.id), ok(PAT, "my_invoices")["items"].map { it["id"].textValue() })
        val e = ok(PAT, "my_expenses")
        assertEquals(100.0, num(e["total"]))
        assertEquals(listOf("pharmacy", "chamber", "ambulance"), e["byKind"].fieldNames().asSequence().toList())
    }
}
