package com.lifolo.invoices

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.ambulance.AmbClock
import com.lifolo.ambulance.AmbFare
import com.lifolo.ambulance.AmbRepo
import com.lifolo.ambulance.AmbReqService
import com.lifolo.auth.AuthUser
import com.lifolo.books.BooksService
import com.lifolo.common.Body
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.LangCtx
import com.lifolo.contracts.Prescriptions
import com.lifolo.pharmacy.RxPharmacyService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The kind-specific receipts (docs/INVOICES.md): a pharmacy sale (books), an online prescription order (out/delivered),
 * a lab bill, a doctor's chamber fee (also the online chamber's confirmed bKash fee), an ambulance trip. Each one builds
 * the lines from its source and calls [InvoiceService.issue] in the same transaction. The `data` of the desktop ops is the
 * action body, so the sync agent calls the same methods with a [DeviceStamp].
 */
@Service
class InvoiceCreators(
    private val jdbc: JdbcTemplate,
    private val invoices: InvoiceService,
    private val books: BooksService,
    private val prescriptions: Prescriptions,
    private val rxPharmacy: RxPharmacyService,
    private val ambReqs: AmbReqService,
    private val ambRepo: AmbRepo,
) {
    private fun err(lang: String?, status: Int, en: String, bn: String, code: String? = null) = InvoiceService.err(lang, status, en, bn, code)

    private fun notFound(lang: String?) = InvoiceService.notFound(lang)

    // ------------------------------------------------------------------ pharmacy: counter / prescription sale

    /**
     * books_sale_add + its receipt. Input as before plus `customer {name?, phone?}` (a plain name still works) and
     * `payments?` (default: fully paid in cash). An approved pharmacy gets `invoice`; a shop that may not issue receipts
     * (not approved / suspended) still records the sale (`invoice: null`, like before). A sale of an online order that
     * already has its receipt (rx_order_update) shows that receipt instead of a second one.
     */
    @Transactional
    fun pharmacySale(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?> {
        val ctx = LangCtx(lang)
        val customer = InvoiceMath.customer(lang, data["customer"])
        val payments = InvoiceMath.payments(lang, data["payments"])
        val body = if (stamp != null) Body((data.node.deepCopy() as ObjectNode).put("at", stamp.at)) else data
        val out = books.saleCore(ctx, actor, body)
        val sale = out.sale
        val res = linkedMapOf<String, Any?>("id" to sale.id, "sale" to sale.toMap())
        val shop = actor.id
        val b = invoices.biz("pharmacy", shop)
        var invoice: Invoice? = null
        if (b != null && invoices.canIssue(b)) {
            val rx = sale.rx?.let { prescriptions.get(it.rxId) }
            invoice = if (sale.source == "online" && rx != null) onlineOrderInvoice(rx.id, shop) else null
            if (invoice == null) {
                val lines = sale.lines.map { l ->
                    val price = BigDecimal.valueOf(l.unitPrice ?: 0.0)
                    val qty = BigDecimal.valueOf(l.qty)
                    val gross = InvoiceMath.m2(qty.multiply(price))
                    val disc = InvoiceMath.m2(BigDecimal.valueOf(l.unitDisc).multiply(qty)).min(gross)
                    LineIn(l.name.take(100).ifEmpty { "-" }, qty, price, disc)
                }
                invoice = invoices.issue(
                    lang, actor,
                    IssueCmd(
                        "pharmacy", shop, lines, BigDecimal.valueOf(sale.totals.billDiscount), payments = payments,
                        customer = Customer(customer.name ?: sale.customer.ifEmpty { null }, customer.phone),
                        patientUid = rx?.patientUid, refType = "book_sale", refId = sale.id,
                    ),
                    stamp,
                )
            }
        }
        res["invoice"] = invoice?.toMap()
        if (out.short.isNotEmpty()) {
            res["warn"] = "stock_short"
            res["stockShort"] = out.short
        }
        return res
    }

    /** the receipt an online order of this prescription at this shop already has */
    private fun onlineOrderInvoice(rxId: String, shop: String): Invoice? {
        val orderIds = jdbc.queryForList("SELECT id FROM rx_orders WHERE rx_id = ? AND pharmacy_uid = ?", String::class.java, rxId, shop)
        return orderIds.firstNotNullOfOrNull { invoices.liveFor("rx_order", it) }
    }

    /**
     * books_sale_delete: a sale that has a receipt can't be deleted — 409 `invoice_exists`; voiding the receipt (with a
     * reason) is the only way, and it puts the stock back (security review I2). Sales from before receipts still delete.
     */
    @Transactional
    fun saleDelete(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        val shop = ctx.user.uid
        if (id != null && shop != null && Rules.isValidId(shop)) {
            val inv = invoices.liveFor("book_sale", id)
            if (inv != null && inv.kind == "pharmacy" && inv.bizId == shop) {
                throw err(ctx.lang, 409, "A sale with a receipt can't be deleted — void the receipt", "রসিদ দেওয়া বিক্রি মোছা যায় না — বাতিল করুন", "invoice_exists") // bn-ok
            }
        }
        return books.saleDelete(ctx)
    }

    // ------------------------------------------------------------------ pharmacy: online prescription order

    /**
     * rx_order_update + the receipt: `accepted` may carry `lines` (kept on the order); `out` or `delivered` issues the
     * receipt from them (+ the delivery fee). At `out` nothing is paid yet (cash on delivery); `delivered` marks the due
     * as paid in cash (or issues the receipt fully paid when there was none).
     */
    @Transactional
    fun rxOrderUpdate(ctx: ActionContext): Any {
        val res = rxPharmacy.orderUpdate(ctx)
        val status = ctx.body.string("status")
        if (status != "out" && status != "delivered") return res
        val id = ctx.body.string("id")!!
        val o = jdbc.queryForList("SELECT * FROM rx_orders WHERE id = ?", id).firstOrNull() ?: return res
        val stored = o["invoice_lines"]?.toString()?.let { InvoiceMath.linesFromJson(it) }
        val given = InvoiceMath.lines(ctx.lang, ctx.body["lines"])
        val lines = given ?: stored ?: return res
        if (lines.isEmpty()) return res
        val shop = ctx.user.id
        val live = invoices.liveFor("rx_order", id)
        val inv = when {
            live == null -> {
                val fee = InvoiceMath.takaOf(o["delivery_fee"] as String?)
                invoices.issue(
                    ctx.lang, ctx.user,
                    IssueCmd(
                        "pharmacy", shop, lines,
                        fees = if (fee != null) listOf(FeeIn("ডেলিভারি চার্জ", fee)) else emptyList(), // bn-ok
                        payments = if (status == "out") emptyList() else null,
                        customer = Customer((o["patient_name"] as String?)?.ifEmpty { null }?.take(60), (o["phone"] as String?)?.take(20)),
                        patientUid = o["patient_uid"] as String?, refType = "rx_order", refId = id,
                    ),
                )
            }
            status == "delivered" && live.due.signum() > 0 -> invoices.pay(ctx.lang, ctx.user, live.id, PaymentIn(live.due, "cash"))
            else -> live
        }
        return linkedMapOf("ok" to true, "invoice" to inv.toMap())
    }

    // ------------------------------------------------------------------ lab

    /**
     * lab_bill_create {labOrderId?, lines?, customer?, fees?, disc?, payments?} — the approved lab owner. From an order the
     * lines come from its price snapshot (unless given), the order's discount and a confirmed home-collection fee too;
     * a walk-in needs lines (a line with a `code` and no price takes the lab's list price).
     */
    @Transactional
    fun labBill(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?> {
        val lab = invoices.requireIssuer(lang, actor, "lab", actor.uid)
        invoices.requireCanIssue(lang, lab)
        val listPrice = { code: String ->
            jdbc.queryForList(
                "SELECT price FROM lab_price_items WHERE lab_uid = ? AND code = ? AND enabled ORDER BY idx LIMIT 1", Int::class.java, lab.id, code,
            ).firstOrNull()?.let { BigDecimal(it) }
        }
        var lines = InvoiceMath.lines(lang, data["lines"], listPrice)
        var fees = InvoiceMath.fees(lang, data["fees"])
        var disc = InvoiceMath.money(lang, data["disc"], "discount", "ছাড়")
        var customer = InvoiceMath.customer(lang, data["customer"])
        var patient: String? = null
        var orderId: String? = null
        if (!data.isNullish("labOrderId")) {
            orderId = data.string("labOrderId")
            if (!Rules.isValidId(orderId)) throw notFound(lang)
            val o = jdbc.queryForList("SELECT * FROM lab_orders WHERE id = ? AND lab_uid = ?", orderId, lab.id).firstOrNull() ?: throw notFound(lang)
            if (o["status"] == "cancelled") throw err(lang, 409, "This order was cancelled", "এই অর্ডার বাতিল হয়েছে", "state") // bn-ok
            patient = o["patient_uid"] as String?
            if (customer.name == null) customer = customer.copy(name = (o["patient_name"] as String?)?.ifEmpty { null }?.take(60))
            if (lines == null) {
                val snap = jdbc.queryForList("SELECT test, name, price FROM lab_order_price_items WHERE order_id = ? ORDER BY idx", orderId)
                val tests = snap.ifEmpty { ((o["tests"] as java.sql.Array?)?.array as Array<*>?)?.map { mapOf("test" to it, "name" to it, "price" to null) } ?: emptyList() }
                lines = tests.map { t ->
                    val code = t["test"]?.toString()
                    val price = (t["price"] as Number?)?.let { BigDecimal(it.toString()) } ?: code?.let(listPrice)
                        ?: throw InvoiceMath.bad(lang, "Write the price of every test", "প্রতিটা পরীক্ষার দাম লিখুন", "price_missing") // bn-ok
                    LineIn((t["name"] ?: code ?: "-").toString().take(100), BigDecimal.ONE, price, code = code?.take(40))
                }
            }
            if (disc == null) {
                val pct = (o["price_discount_pct"] as Number?)?.toInt() ?: 0
                if (pct in 1..100) {
                    val sub = lines.fold(BigDecimal.ZERO) { a, l -> a + InvoiceMath.m2(l.qty.multiply(l.price)) - l.disc }
                    disc = InvoiceMath.m2(sub.multiply(BigDecimal(pct)).divide(BigDecimal(100)))
                }
            }
            if (fees == null) {
                val home = (o["home_fee"] as Number?)?.toInt() ?: 0
                fees = if (o["home_status"] == "confirmed" && home > 0) listOf(FeeIn("হোম কালেকশন", BigDecimal(home))) else emptyList() // bn-ok
            }
        }
        if (lines.isNullOrEmpty()) throw InvoiceMath.bad(lang, "Add at least one test", "অন্তত একটা পরীক্ষা দিন") // bn-ok
        val inv = invoices.issue(
            lang, actor,
            IssueCmd(
                "lab", lab.id, lines, disc ?: BigDecimal.ZERO, fees ?: emptyList(), InvoiceMath.payments(lang, data["payments"]), customer,
                patient, if (orderId != null) "lab_order" else null, orderId,
            ),
            stamp,
        )
        return linkedMapOf("invoice" to inv.toMap())
    }

    // ------------------------------------------------------------------ doctor's chamber

    private val visitLabel = mapOf("new" to "নতুন রোগী", "followup" to "ফলো-আপ", "report" to "রিপোর্ট দেখানো") // bn-ok

    /** a Node date: "YYYY-MM-DD" or the UTC-midnight ms of the Dhaka day */
    private fun dateOf(v: JsonNode?): LocalDate? {
        Js.string(v)?.let { s -> return runCatching { LocalDate.parse(s) }.getOrNull() }
        return Js.integer(v)?.takeIf { it in 0..4_102_444_800_000 }?.let { DhakaTime.fromUtcMidnightMs(it) }
    }

    /**
     * chamber_fee_collect {doctorUid?, appointmentId? | walkIn:{name, phone?, chamberKey, date}, visitType, amount, method,
     * ref?} — the doctor, or an assistant with `doctorUid`.
     */
    @Transactional
    fun chamberFee(lang: String?, actor: AuthUser, data: Body, stamp: DeviceStamp? = null): Map<String, Any?> {
        val doctor = data.string("doctorUid")?.takeIf { it.isNotEmpty() } ?: actor.uid
        val biz = invoices.requireIssuer(lang, actor, "chamber", doctor)
        invoices.requireCanIssue(lang, biz)
        val visit = data.string("visitType")?.takeIf { it in visitLabel } ?: throw InvoiceMath.bad(lang, "Choose the visit type", "ভিজিটের ধরন বেছে নিন") // bn-ok
        val amount = InvoiceMath.money(lang, data["amount"], "fee", "ফি") // bn-ok
        if (amount == null || amount.signum() == 0) throw InvoiceMath.bad(lang, "Write the fee", "ফি লিখুন") // bn-ok
        val method = InvoiceMath.method(lang, data["method"], "cash")
        val ref = InvoiceMath.text(data["ref"], 40)
        val apptId = data.string("appointmentId")?.takeIf { it.isNotEmpty() }
        val customer: Customer
        val patient: String?
        val chamber: String?
        val date: LocalDate
        if (apptId != null) {
            if (!Rules.isValidId(apptId)) throw notFound(lang)
            val a = jdbc.queryForList("SELECT * FROM appointments WHERE id = ? AND provider_uid = ?", apptId, biz.id).firstOrNull() ?: throw notFound(lang)
            if (a["status"] in listOf("cancelled", "declined")) throw err(lang, 409, "This appointment was cancelled", "এই অ্যাপয়েন্টমেন্ট বাতিল হয়েছে", "state") // bn-ok
            patient = a["patient_uid"] as String?
            customer = Customer((a["patient_name"] as String?)?.ifEmpty { null }?.take(60))
            chamber = ((a["chamber_bn"] as String?)?.ifEmpty { null } ?: (a["chamber"] as String?))?.ifEmpty { null }
            date = (a["visit_date"] as java.sql.Date).toLocalDate()
        } else {
            val w = data["walkIn"]
            if (w == null || !w.isObject) throw InvoiceMath.bad(lang, "Choose the appointment or write the patient's name", "অ্যাপয়েন্টমেন্ট বেছে নিন বা রোগীর নাম লিখুন") // bn-ok
            val name = InvoiceMath.text(w.get("name"), 60) ?: throw InvoiceMath.bad(lang, "Write the patient's name", "রোগীর নাম লিখুন") // bn-ok
            val key = Js.string(w.get("chamberKey"))?.takeIf { it.isNotEmpty() } ?: throw notFound(lang)
            val ch = jdbc.queryForList(
                "SELECT hospital_name, hospital_name_bn FROM provider_chambers WHERE provider_uid = ? AND (hospital_id = ? OR hospital_name = ?) ORDER BY idx LIMIT 1",
                biz.id, key, key,
            ).firstOrNull() ?: throw notFound(lang)
            chamber = ((ch["hospital_name_bn"] as String?)?.ifEmpty { null } ?: (ch["hospital_name"] as String?))?.ifEmpty { null }
            date = dateOf(w.get("date")) ?: DhakaTime.day()
            customer = InvoiceMath.customer(lang, w).copy(name = name)
            patient = null
        }
        val inv = invoices.issue(
            lang, actor,
            IssueCmd(
                "chamber", biz.id, listOf(LineIn("ভিজিট ফি — ${visitLabel[visit]} ($date)", BigDecimal.ONE, amount)), // bn-ok
                payments = listOf(PaymentIn(amount, method, ref)), customer = customer, patientUid = patient,
                refType = if (apptId != null) "appointment" else null, refId = apptId, bizAddress = chamber,
            ),
            stamp,
        )
        return linkedMapOf("invoice" to inv.toMap())
    }

    /**
     * Online chamber: the doctor/assistant confirmed the bKash fee (appt_pay_confirm) → the patient's receipt, fully paid
     * by bKash with the patient's TrxID. Skipped (null) when there is no fee in numbers or a receipt exists. Called after
     * the confirm transaction; the caller catches (a receipt problem never undoes the confirmation).
     */
    @Transactional
    fun onlineFeePaid(lang: String?, actor: AuthUser, appointmentId: String): Invoice? {
        val a = jdbc.queryForList("SELECT * FROM appointments WHERE id = ?", appointmentId).firstOrNull() ?: return null
        val doctor = a["provider_uid"] as String
        if (a["no_fee"] == true) return null
        val amount = InvoiceMath.takaOf(a["fee"] as String?) ?: return null
        if (invoices.liveFor("appointment", appointmentId) != null) return null
        val biz = invoices.biz("chamber", doctor) ?: return null
        if (!invoices.canIssue(biz) || invoices.roleOf(actor, "chamber", doctor, biz.ownerUid) == null) return null
        val date = (a["visit_date"] as java.sql.Date).toLocalDate()
        return invoices.issue(
            lang, actor,
            IssueCmd(
                "chamber", doctor, listOf(LineIn("অনলাইন ভিজিট ফি ($date)", BigDecimal.ONE, amount)), // bn-ok
                payments = listOf(PaymentIn(amount, "bkash", (a["pay_ref"] as String?)?.take(40))),
                customer = Customer((a["patient_name"] as String?)?.ifEmpty { null }?.take(60)), patientUid = a["patient_uid"] as String?,
                refType = "appointment", refId = appointmentId, bizAddress = "অনলাইন চেম্বার", // bn-ok
            ),
        )
    }

    // ------------------------------------------------------------------ ambulance

    /** ambreq_finish extras: the chart's oxygen / icu / night, plus one "other" of at most ৳500 */
    private val extraLabel = linkedMapOf("oxygen" to "অক্সিজেন", "icu" to "আইসিইউ", "night" to "রাতের ভাড়া", "other" to "অন্যান্য") // bn-ok

    /**
     * ambreq_finish {reqId, fare, extras?:[{label: oxygen|icu|night|other, amount}], method} — the driver (owner) who took
     * the trip: closes it (like ambreq_step done) and gives the patient the receipt. Compared with what the patient agreed
     * to (security review I8): the agreed fare, else the chart estimate frozen when the trip was accepted. An extra is
     * at most the chart's value at accept time (one not in the chart is refused), "other" at most ৳500 and shown
     * separately; `overFare` = fare + chart extras above that. A trip closed with ambreq_step in the last 24 h can still
     * get its receipt.
     */
    @Transactional
    fun ambreqFinish(ctx: ActionContext): Any {
        val lang = ctx.lang
        val actor = ctx.user
        if (actor.patientSideOnly) throw notFound(lang)
        val reqId = ctx.body.string("reqId")
        if (!Rules.isValidId(reqId)) throw notFound(lang)
        val fare = InvoiceMath.money(lang, ctx.body["fare"], "fare", "ভাড়া", BigDecimal(500000)) // bn-ok
        if (fare == null || fare.signum() == 0) throw InvoiceMath.bad(lang, "Write the fare", "ভাড়া লিখুন") // bn-ok
        val extrasIn = ctx.body["extras"]
        if (extrasIn != null && !extrasIn.isNull && (!extrasIn.isArray || extrasIn.size() > 4)) throw InvoiceMath.bad(lang, "Check the extras", "অতিরিক্ত খরচ দেখে নিন", "extras") // bn-ok
        val extras = LinkedHashMap<String, BigDecimal>()
        for (e in extrasIn?.takeIf { it.isArray } ?: emptyList()) {
            val code = Js.string(e?.get("label"))?.takeIf { it in extraLabel } ?: throw InvoiceMath.bad(lang, "Check the extras", "অতিরিক্ত খরচ দেখে নিন", "extras") // bn-ok
            val amount = InvoiceMath.money(lang, e.get("amount"), "extras", "অতিরিক্ত খরচ") ?: continue // bn-ok
            if (amount.signum() == 0) continue
            if (code in extras) throw InvoiceMath.bad(lang, "Check the extras", "অতিরিক্ত খরচ দেখে নিন", "extras") // bn-ok
            extras[code] = amount
        }
        val method = InvoiceMath.method(lang, ctx.body["method"], "cash")
        val x = ambReqs.load(reqId!!) ?: throw notFound(lang)
        if (x.acceptedBy != actor.uid || x.ambulanceId == null) throw notFound(lang)
        val biz = invoices.requireIssuer(lang, actor, "ambulance", x.ambulanceId)
        invoices.requireCanIssue(lang, biz)
        val now = AmbClock.now()
        val open = x.status == "accepted" || x.status == "arrived"
        val recent = x.status == "done" && now - (x.closedAt ?: 0L) <= 24 * 3600 * 1000L
        if (!open && !recent) throw err(lang, 409, "This request is already closed", "এই অনুরোধ আগেই বন্ধ হয়েছে")
        if (invoices.liveFor("ambreq", x.id) != null) throw err(lang, 409, "A receipt was already issued for this", "এর রসিদ আগেই দেওয়া হয়েছে", "already_issued") // bn-ok
        // the chart at accept time (trips accepted before the snapshot existed: the chart now)
        val snap = ambReqs.fareSnapOf(x.id) ?: ambRepo.get(x.ambulanceId)?.let { a ->
            val c = a.chart
            listOf(AmbFare.estimate(c, a.veh, x.dest, x.type, x.oxygen, x.night), if (x.oxygen) c?.oxygen else null, if (x.type == "icu") c?.icu else null, if (x.night) c?.night else null)
        } ?: listOf(null, null, null, null)
        val chartFare = snap[0]
        val cap = mapOf("oxygen" to snap[1], "icu" to snap[2], "night" to snap[3], "other" to 500)
        for ((code, amount) in extras) {
            val max = cap[code]
            if (max == null || amount > BigDecimal(max)) {
                throw InvoiceMath.bad(lang, "This extra is more than the fare chart allows", "এই অতিরিক্ত খরচ ভাড়ার চার্টের চেয়ে বেশি", "extras_cap") // bn-ok
            }
        }
        val chartExtras = extras.filterKeys { it != "other" }.values.fold(BigDecimal.ZERO) { a, v -> a + v }
        val limit = x.agreedFare ?: chartFare
        val over = limit != null && fare + chartExtras > BigDecimal(limit)
        if (open) ambReqs.finishTrip(lang, x.id, actor.id)
        val dest = AmbFare.destLabel(x.dest, "bn")
        val total = fare + extras.values.fold(BigDecimal.ZERO) { a, v -> a + v }
        val inv = invoices.issue(
            lang, actor,
            IssueCmd(
                "ambulance", x.ambulanceId, listOf(LineIn(if (dest.isNotEmpty()) "ভাড়া — $dest" else "ভাড়া", BigDecimal.ONE, fare)), // bn-ok
                fees = extras.map { (code, amount) -> FeeIn(extraLabel[code]!!, amount) }, payments = listOf(PaymentIn(InvoiceMath.m2(total), method)),
                customer = Customer(x.patientName.ifEmpty { null }?.take(60), x.phone?.take(20)), patientUid = x.uid,
                refType = "ambreq", refId = x.id, overFare = over, chartFare = limit,
            ),
        )
        return linkedMapOf("ok" to true, "invoice" to inv.toMap(), "overFare" to over, "chartFare" to limit, "agreedFare" to x.agreedFare)
    }
}
