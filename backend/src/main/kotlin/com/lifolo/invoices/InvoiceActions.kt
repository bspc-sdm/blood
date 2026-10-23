package com.lifolo.invoices

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.books.BooksService
import com.lifolo.common.ClientIp
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.springframework.stereotype.Component

/**
 * The receipt actions (docs/INVOICES.md). books_sale_add / books_sale_delete / rx_order_update stay registered by their
 * modules and call [InvoiceCreators] from there.
 */
@Component
class InvoiceActions(
    private val summary: InvoiceSummary,
    private val invoices: InvoiceService,
    private val creators: InvoiceCreators,
    private val features: InvoiceFeatures,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val payHooks: org.springframework.beans.factory.ObjectProvider<AmbFinishPayHook>,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "invoice_get" to ActionHandler { ctx -> mapOf("invoice" to invoices.get(ctx.lang, ctx.user, ctx.body.string("id")).toMap()) },
        "invoice_verify" to ActionHandler { ctx ->
            val ip = ClientIp.key(ClientIp.of(ctx.request))
            mapOf("invoice" to invoices.verify(ctx.lang, ctx.body.string("code"), ip))
        },
        "invoice_list_biz" to ActionHandler { ctx ->
            val b = ctx.body
            invoices.listBiz(ctx.lang, ctx.user, b.string("kind"), b.string("bizId"), b.string("period"), b.string("q"), b.string("cursor"))
        },
        "invoice_pay" to ActionHandler(::pay),
        "invoice_void" to ActionHandler(::void),
        "invoice_report" to ActionHandler { ctx -> invoices.report(ctx.lang, ctx.user, ctx.body.string("id"), ctx.body["paid"], ctx.body.string("note")) },
        "invoice_export_csv" to ActionHandler { ctx ->
            val b = ctx.body
            invoices.exportCsv(ctx.lang, ctx.user, b.string("kind"), b.string("bizId"), b.string("month"))
        },
        "my_invoices" to ActionHandler { ctx -> invoices.mine(ctx.lang, ctx.user, ctx.body.string("cursor")) },
        "my_expenses" to ActionHandler { ctx -> invoices.expenses(ctx.lang, ctx.user, ctx.body.string("month")) },
        "lab_bill_create" to ActionHandler { ctx -> creators.labBill(ctx.lang, ctx.user, ctx.body) },
        "chamber_fee_collect" to ActionHandler { ctx -> creators.chamberFee(ctx.lang, ctx.user, ctx.body) },
        "ambreq_finish" to ActionHandler(::ambreqFinish),
        "assistant_chambers" to ActionHandler(::chambers),
        "chamber_checkin" to ActionHandler(::checkin),
        "invoice_day_summary" to ActionHandler(::daySummary),
    )

    /**
     * ambreq_finish: the trip's receipt (InvoiceCreators); with `payLater` (pay_ambulance) the fare request for the
     * patient is made right after (Lifolo Pay — after the trip only, never required). Answer + `payRequest`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ambreqFinish(ctx: ActionContext): Any {
        val res = creators.ambreqFinish(ctx) as Map<String, Any?>
        if (res["payLater"] != true) return res
        val reqId = ctx.body.string("reqId") ?: return res
        val pr = try {
            payHooks.ifAvailable?.afterFinish(ctx.lang, ctx.user, reqId)
        } catch (e: Exception) {
            null
        }
        return LinkedHashMap(res).apply { put("payRequest", pr) }
    }

    private fun id(ctx: ActionContext): String {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw InvoiceService.notFound(ctx.lang)
        return id!!
    }

    /**
     * assistant_chambers {doctorUid?}: the doctor's in-person chambers with their keys — for chamber_fee_collect walk-ins
     * on a day without serials. The doctor or an assistant (verified email).
     */
    private fun chambers(ctx: ActionContext): Any {
        val doctor = ctx.body.string("doctorUid")?.takeIf { it.isNotEmpty() } ?: ctx.user.uid
        val b = invoices.requireIssuer(ctx.lang, ctx.user, "chamber", doctor)
        features.requireKind(ctx.lang, b.ownerUid, "chamber")
        val rows = jdbc.queryForList(
            "SELECT hospital_id, hospital_name, hospital_name_bn, fee, no_fee FROM provider_chambers WHERE provider_uid = ? AND mode <> 'online' ORDER BY idx",
            b.id,
        )
        return mapOf(
            "doctorUid" to b.id, "doctorName" to b.name,
            "chambers" to rows.map { r ->
                val name = r["hospital_name"] as String? ?: ""
                linkedMapOf(
                    "chamberKey" to ((r["hospital_id"] as String?)?.ifEmpty { null } ?: name), "name" to name,
                    "nameBn" to (r["hospital_name_bn"] as String? ?: ""), "fee" to (r["fee"] as String? ?: ""), "noFee" to (r["no_fee"] == true),
                )
            }.filter { (it["chamberKey"] as String).isNotEmpty() },
        )
    }

    /**
     * chamber_checkin {appointmentId, doctorUid?}: the chamber desk marks the patient arrived (doctor or assistant). Today's
     * (or an earlier day's still open) appointment of that doctor; pressed twice = the first time stays.
     */
    private fun checkin(ctx: ActionContext): Any {
        val doctor = ctx.body.string("doctorUid")?.takeIf { it.isNotEmpty() } ?: ctx.user.uid
        val b = invoices.requireIssuer(ctx.lang, ctx.user, "chamber", doctor)
        features.requireKind(ctx.lang, b.ownerUid, "chamber")
        val id = ctx.body.string("appointmentId")
        if (!Rules.isValidId(id)) throw InvoiceService.notFound(ctx.lang)
        val a = jdbc.queryForList("SELECT status, visit_date, arrived_at FROM appointments WHERE id = ? AND provider_uid = ?", id, b.id).firstOrNull()
            ?: throw InvoiceService.notFound(ctx.lang)
        if (a["status"] !in listOf("pending", "confirmed")) {
            throw InvoiceService.err(ctx.lang, 409, "This appointment is not open", "এই অ্যাপয়েন্টমেন্ট এখন খোলা নেই", "state") // bn-ok
        }
        if ((a["visit_date"] as java.sql.Date).toLocalDate() > com.lifolo.common.DhakaTime.day()) {
            throw InvoiceService.err(ctx.lang, 409, "This appointment is on a later day", "এই অ্যাপয়েন্টমেন্ট পরের কোনো দিনের", "not_today") // bn-ok
        }
        jdbc.update("UPDATE appointments SET arrived_at = coalesce(arrived_at, now()), arrived_by = coalesce(arrived_by, ?) WHERE id = ?", ctx.user.id, id)
        val at = jdbc.queryForObject("SELECT arrived_at FROM appointments WHERE id = ?", java.sql.Timestamp::class.java, id)!!
        return linkedMapOf("ok" to true, "appointmentId" to id, "arrivedAt" to at.time)
    }

    /** invoice_day_summary {kind, bizId, day?: "YYYY-MM-DD"} — the owner (not an assistant): [InvoiceSummary.detail] */
    private fun daySummary(ctx: ActionContext): Any {
        val b = invoices.requireOwner(ctx.lang, ctx.user, ctx.body.string("kind"), ctx.body.string("bizId"))
        features.requireKind(ctx.lang, b.ownerUid, b.kind)
        val d = ctx.body.string("day")
        val day = if (d.isNullOrEmpty()) {
            com.lifolo.common.DhakaTime.day()
        } else {
            runCatching { java.time.LocalDate.parse(d) }.getOrNull()?.takeIf { it.year in 2020..2100 }
                ?: throw InvoiceMath.bad(ctx.lang, "Check the date", "তারিখ দেখে নিন")
        }
        return summary.detail(b, day)
    }

    private fun pay(ctx: ActionContext): Any {
        val id = id(ctx)
        val amount = InvoiceMath.money(ctx.lang, ctx.body["amount"])
        if (amount == null || amount.signum() == 0) throw InvoiceMath.bad(ctx.lang, "Check the amount", "টাকার পরিমাণ দেখে নিন") // bn-ok
        val p = PaymentIn(amount, InvoiceMath.method(ctx.lang, ctx.body["method"]), InvoiceMath.text(ctx.body["ref"], 40))
        return mapOf("invoice" to invoices.pay(ctx.lang, ctx.user, id, p).toMap())
    }

    private fun void(ctx: ActionContext): Any {
        val id = id(ctx)
        return mapOf("invoice" to invoices.void(ctx.lang, ctx.user, id, ctx.body.string("reason") ?: "", ctx.body.string("note")).toMap())
    }
}

/** voiding a pharmacy sale's receipt reverses the sale like books_sale_delete (stock back, sale and day totals gone) */
@Component
class BooksInvoiceHook(private val books: BooksService) : InvoiceVoidHook {
    override fun onVoid(lang: String?, inv: Invoice) {
        if (inv.kind != "pharmacy" || inv.refType != "book_sale" || inv.refId == null) return
        if (!books.lockShop(inv.bizId)) return
        books.reverseSale(inv.bizId, inv.refId)
    }
}

/**
 * Account deletion: the receipts stay 6 years for the accounts (personal fields removed — [InvoiceService.forget]); demo
 * accounts' receipts go at once (the demo wipe deletes through here).
 */
@Component
class InvoiceDeletionStep(private val invoices: InvoiceService) : AccountDeletionStep {
    // before the ambulances (500) go: a demo owner's car numbering is found through them
    override val order = 450

    override fun deleteNow(uid: String, email: String?) {
        invoices.forget(uid)
    }
}

/** daily: receipts whose 6 years after an account deletion are over */
@Component
class InvoicePurgeTask(private val invoices: InvoiceService) : DailyTask {
    override val name = "invoicesPurged"
    override val order = 20

    override fun run(nowMs: Long): Any = invoices.purge(nowMs)
}

/** Lifolo Pay (payments package): the fare request after ambreq_finish with payLater; returns the request JSON or null */
fun interface AmbFinishPayHook {
    fun afterFinish(lang: String?, driver: com.lifolo.auth.AuthUser, reqId: String): Map<String, Any?>?
}
