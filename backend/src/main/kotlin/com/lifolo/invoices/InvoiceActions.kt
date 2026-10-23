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
class InvoiceActions(private val invoices: InvoiceService, private val creators: InvoiceCreators) : ActionModule {
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
        "my_invoices" to ActionHandler { ctx -> invoices.mine(ctx.user, ctx.body.string("cursor")) },
        "my_expenses" to ActionHandler { ctx -> invoices.expenses(ctx.lang, ctx.user, ctx.body.string("month")) },
        "lab_bill_create" to ActionHandler { ctx -> creators.labBill(ctx.lang, ctx.user, ctx.body) },
        "chamber_fee_collect" to ActionHandler { ctx -> creators.chamberFee(ctx.lang, ctx.user, ctx.body) },
        "ambreq_finish" to ActionHandler { ctx -> creators.ambreqFinish(ctx) },
    )

    private fun id(ctx: ActionContext): String {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw InvoiceService.notFound(ctx.lang)
        return id!!
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
        return mapOf("invoice" to invoices.void(ctx.lang, ctx.user, id, ctx.body.string("reason") ?: "").toMap())
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
    override val order = 590

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
