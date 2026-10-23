package com.lifolo.payments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.auth.AuthUser
import com.lifolo.features.Features
import com.lifolo.invoices.Customer
import com.lifolo.invoices.Invoice
import com.lifolo.invoices.InvoiceMath
import com.lifolo.invoices.InvoiceService
import com.lifolo.invoices.IssueCmd
import com.lifolo.invoices.LineIn
import com.lifolo.invoices.PaymentIn
import com.lifolo.pharmacy.P4Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Date
import java.time.LocalDate

/** helpers of the Lifolo Pay tests (backend/docs/PAYMENTS.md) */
abstract class PayTestBase : P4Test() {
    @Autowired lateinit var payClock: PayClock

    @Autowired lateinit var features: Features

    @Autowired lateinit var jobs: PayJobs

    @Autowired lateinit var invoices: InvoiceService

    @Autowired lateinit var hooks: PayHooks

    @AfterEach
    fun payBack() {
        payClock.fixed = null
        features.allOn = true
        features.clearCache()
    }

    val PAT = "pyPat"
    val PAT2 = "pyPat2"
    val CG = "pyCg"
    val PH = "pyPh"
    val PH2 = "pyPh2"
    val LAB = "pyLab"
    val DOC = "pyDoc"
    val ASST = "pyAsst"
    val X = "pyX"
    val ADMIN = "pyAdmin"

    fun fails(uid: String?, action: String, body: Map<String, Any?>, status: Int, code: String? = null): Res {
        val r = call(uid, action, body)
        assertEquals(status, r.status, "$action: $r")
        if (code != null) assertEquals(code, r.code, "$action: $r")
        return r
    }

    fun d(n: JsonNode?): Double = n!!.asDouble()

    // ------------------------------------------------------------------ businesses and people

    fun admin() = user(ADMIN, mapOf("name" to "অ্যাডমিন"), email = "owner@example.com")

    fun lab(uid: String = LAB) {
        person(uid, "ল্যাব মালিক")
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, 'নিরাময় ল্যাব', ?, 'চরপাড়া', 'DGHS-1', '01711000498', 'approved')",
            uid, MYMENSINGH,
        )
    }

    fun doctor(uid: String = DOC, assistantEmail: String? = null) {
        person(uid, "ডা. হাসান")
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, status) VALUES (?, 'doctor', 'A-1', 'X', 'ডা. হাসান', 'approved')", uid)
        jdbc.update("INSERT INTO providers (uid, type, name, degrees, specialties, listed) VALUES (?, 'doctor', 'ডা. হাসান', 'MBBS', '{medicine}', TRUE)", uid)
        jdbc.update(
            "INSERT INTO provider_chambers (provider_uid, idx, hospital_id, hospital_name, hospital_name_bn) VALUES (?, 0, 'h1', 'City Hospital', 'সিটি হাসপাতাল')",
            uid,
        )
        if (assistantEmail != null) {
            jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, 0)", uid, assistantEmail)
            user(ASST, mapOf("name" to "সহকারী", "lang" to "bn"), email = assistantEmail)
        }
    }

    fun careLink(patient: String = PAT, caregiver: String = CG, status: String = "active") {
        person(caregiver, "কেয়ারগিভার")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, ?)", patient, caregiver, status)
    }

    fun bkash(no: String, type: String = "personal", active: Boolean = true) = mapOf("method" to "bkash", "number" to no, "type" to type, "active" to active)

    fun nagad(no: String, type: String = "personal", active: Boolean = true) = mapOf("method" to "nagad", "number" to no, "type" to type, "active" to active)

    fun setMethods(kind: String, bizId: String, owner: String, methods: List<Map<String, Any?>>): JsonNode =
        ok(owner, "pay_methods_set", mapOf("kind" to kind, "bizId" to bizId, "methods" to methods))

    /** the numbers have been active for 2 days (patients only ever get matured numbers, P4) */
    fun mature(bizId: String) {
        jdbc.update("UPDATE pay_methods SET active_since = active_since - interval '2 days' WHERE biz_id = ? AND active_since IS NOT NULL", bizId)
    }

    fun ready(kind: String, bizId: String, owner: String, no: String = "01711000001") {
        setMethods(kind, bizId, owner, listOf(bkash(no)))
        mature(bizId)
    }

    // ------------------------------------------------------------------ sources

    /** an online prescription order, accepted with priced lines (10 × ৳2.5) + ৳50 delivery = ৳75 */
    fun acceptedOrder(id: String, patient: String = PAT, pharmacy: String = PH, rxId: String = "rx-$id") {
        rx(rxId, "C${id.uppercase().take(9).padEnd(9, '0')}", patient, listOf(Med("Napa")))
        order(
            id,
            mapOf("rx_id" to rxId, "patient_uid" to patient, "pharmacy_uid" to pharmacy, "pharmacy_name" to "দোকান", "patient_name" to "রোগী রহিম",
                "mode" to "online", "status" to "accepted", "delivery_fee" to "৫০ টাকা", "phone" to "01811000000", "address" to "বাসা"),
        )
        jdbc.update("UPDATE rx_orders SET invoice_lines = ?::jsonb WHERE id = ?", InvoiceMath.linesJson(listOf(LineIn("Napa", BigDecimal(10), BigDecimal("2.5")))), id)
    }

    /** a lab order with a confirmed home collection: ৳400 + ৳150, 10 % off, ৳200 home fee = ৳695 */
    fun labOrder(id: String, patient: String = PAT, lab: String = LAB) {
        jdbc.update(
            """
            INSERT INTO lab_orders (id, lab_uid, lab_name, patient_uid, patient_name, tests, status, price_discount_pct, home_status, home_fee)
            VALUES (?, ?, 'নিরাময় ল্যাব', ?, 'রোগী রহিম', '{cbc,rbs}', 'received', 10, 'confirmed', 200)
            """.trimIndent(),
            id, lab, patient,
        )
        jdbc.update("INSERT INTO lab_order_price_items (order_id, idx, test, name, price) VALUES (?, 0, 'cbc', 'CBC', 400), (?, 1, 'rbs', 'RBS', 150)", id, id)
    }

    /** an appointment at the doctor's chamber h1 (in person) or the online one */
    fun appt(id: String, patient: String = PAT, doctor: String = DOC, date: LocalDate = LocalDate.now().plusDays(3), start: String = "18:00", online: Boolean = false, status: String = "confirmed") {
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, chamber_bn, chamber_key, visit_date, start_time, status,
              online, pay_state, fee, bkash_number)
            VALUES (?, ?, ?, 'রোগী রহিম', 'ডা. হাসান', 'doctor', 'City Hospital', 'সিটি হাসপাতাল', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, patient, doctor, if (online) "online" else "h1", Date.valueOf(date), start, status, online,
            if (online) "unpaid" else null, if (online) "৫০০ টাকা" else null, if (online) "01711000001" else null,
        )
    }

    /** a receipt for [patient] with money due */
    fun dueInvoice(kind: String, biz: String, owner: String, total: Int, paid: Int, patient: String = PAT): Invoice =
        invoices.issue(
            "bn", AuthUser(owner, email = "$owner@example.com", emailVerified = true),
            IssueCmd(kind, biz, listOf(LineIn("সেবা", BigDecimal.ONE, BigDecimal(total))), payments = if (paid > 0) listOf(PaymentIn(BigDecimal(paid), "cash")) else emptyList(),
                customer = Customer("রোগী রহিম"), patientUid = patient),
        )

    fun req(id: String): Map<String, Any?> = row("SELECT * FROM pay_requests WHERE id = ?", id)

    fun create(owner: String, refType: String, refId: String, purpose: String, extra: Map<String, Any?> = emptyMap()): JsonNode =
        ok(owner, "pay_request_create", mapOf("refType" to refType, "refId" to refId, "purpose" to purpose) + extra)["request"]

    fun claim(uid: String, id: String, trx: String, last4: String = "1234", method: String = "bkash", extra: Map<String, Any?> = emptyMap()): Res =
        call(uid, "pay_claim", mapOf("id" to id, "method" to method, "trxId" to trx, "senderLast4" to last4) + extra)

    val CHECKS = mapOf("trxId" to true, "amount" to true, "last4" to true)

    fun confirm(uid: String, id: String): Res = call(uid, "pay_confirm", mapOf("id" to id, "checks" to CHECKS))

    /** pay_* push data of [uid] (from the bell list) */
    fun payPushes(uid: String, type: String): List<JsonNode> =
        inbox(uid).map { mapper.readTree(it["data"] as String) }.filter { it["type"]?.textValue() == type }

    // ------------------------------------------------------------------ switches

    /** real switches: only these keys on (mode on), everything else off */
    fun onlyOn(vararg keys: String, pilots: Map<String, List<String>> = emptyMap()) {
        features.allOn = false
        jdbc.update("DELETE FROM feature_flags")
        for (k in keys) {
            val p = pilots[k]
            jdbc.update(
                "INSERT INTO feature_flags (key, mode, pilot_uids) VALUES (?, ?, ?::text[])",
                k, if (p != null) "pilot" else "on", "{" + (p ?: emptyList()).joinToString(",") { "\"$it\"" } + "}",
            )
        }
        jdbc.update("INSERT INTO feature_changes (key, mode) VALUES ('test', 'on')")
        features.clearCache()
    }
}
