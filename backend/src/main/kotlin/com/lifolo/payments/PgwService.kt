package com.lifolo.payments

import com.lifolo.auth.AdminService
import com.lifolo.auth.AuthUser
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.demo.DemoWorld
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI

/**
 * bKash Tokenized Checkout for Lifolo's OWN income only (purpose `lifolo_subscription`; PAYMENTS.md, security review P7
 * and P10). Never for a business's fees.
 *  - the price comes from the server's table, never from the client;
 *  - success is decided only by our server calling execute (and query when execute fails or times out) for a paymentID
 *    WE created, and matching statusCode 0000, transactionStatus Completed, amount, currency BDT, intent sale and
 *    merchantInvoiceNumber = our row id — the redirect's `status` only picks the branch;
 *  - the callback is idempotent (a second one returns the stored result; one grant per row, to the row's own account);
 *  - a job queries checkouts still pending after 2 minutes (up to 24 h, then failed);
 *  - the result redirect is fixed (`lifolo://pay/result?id=…` or `https://lifolo.com/pay/result/<id>`), nothing from
 *    the request goes into it.
 */
@Service
class PgwService(
    private val jdbc: JdbcTemplate,
    bkash: BkashTokenizedProvider,
    private val gate: PayGate,
    @Value("\${lifolo.pay.public-api:https://lifolo.com}") private val publicApi: String,
    @Value("\${lifolo.pay.subscription-price:299}") subscriptionPrice: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the gateway (tests put a mocked one in) */
    @Volatile
    var provider: PaymentProvider = bkash

    /** the server's price table (P7: never an amount from the client) */
    val prices: Map<String, BigDecimal> = mapOf("lifolo_subscription" to (subscriptionPrice.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal(299)))

    companion object {
        const val APP_RESULT = "lifolo://pay/result?id="
        const val WEB_RESULT = "https://lifolo.com/pay/result/"
        const val PENDING_AFTER_MS = 2 * 60 * 1000L
        const val GIVE_UP_MS = 24 * 3600 * 1000L
    }

    fun resultUrl(id: String, client: String): String = if (client == "web") WEB_RESULT + id else APP_RESULT + id

    fun callbackUrl(): String = "${publicApi.trimEnd('/')}/api/pay/bkash/callback"

    private fun unavailable(lang: String?) =
        Pay.err(lang, 503, "Online payment is not available right now", "অনলাইন পেমেন্ট এখন চালু নেই", "pgw_unavailable") // bn-ok

    /** pgw_checkout {purpose, client?} → {id, bkashURL, paymentID, amount} */
    fun checkout(lang: String?, user: AuthUser, purpose: String?, client: String?): Map<String, Any?> {
        val uid = user.uid ?: throw Pay.notFound(lang)
        gate.require(lang, uid, PayGate.BKASH_PGW)
        val price = purpose?.let { prices[it] } ?: throw Pay.bad(lang, "Only Lifolo's own subscription can be paid here", "এখানে শুধু লাইফলোর নিজের সাবস্ক্রিপশন দেওয়া যায়", "pgw_purpose") // bn-ok
        if (user.patientSideOnly) throw Pay.notFound(lang)
        // P7: demo accounts never reach the real gateway
        if (DemoWorld.isDemoUid(uid)) throw Pay.err(lang, 403, "Not in the demo", "ডেমোতে এটা চলে না", "demo_locked") // bn-ok
        val p = provider
        if (!p.configured) throw unavailable(lang)
        val cl = if (client == "web") "web" else "app"
        val id = Ids.newId()
        jdbc.update("INSERT INTO pgw_payments (id, uid, purpose, amount, client) VALUES (?, ?, ?, ?, ?)", id, uid, purpose, price, cl)
        val co = try {
            p.createCheckout(id, price, uid, callbackUrl())
        } catch (e: PgwException) {
            log.warn("pgw create failed: {}", e.message)
            jdbc.update("UPDATE pgw_payments SET status = 'failed', provider_status = 'create_failed' WHERE id = ?", id)
            throw unavailable(lang)
        }
        jdbc.update("UPDATE pgw_payments SET payment_id = ? WHERE id = ?", co.paymentId, id)
        return linkedMapOf("id" to id, "bkashURL" to co.redirectUrl, "paymentID" to co.paymentId, "amount" to Pay.num(price))
    }

    private fun row(where: String, vararg args: Any?): Map<String, Any?>? = jdbc.queryForList("SELECT * FROM pgw_payments WHERE $where", *args).firstOrNull()

    /** does the gateway's answer prove THIS row was paid? */
    private fun matches(r: PgwResult, row: Map<String, Any?>): Boolean =
        r.ok && r.transactionStatus == "Completed" && r.paymentId == row["payment_id"] && r.trxId != null &&
            r.amount != null && r.amount.compareTo(row["amount"] as BigDecimal) == 0 &&
            (r.currency == null || r.currency == "BDT") && (r.intent == null || r.intent == "sale") &&
            r.merchantInvoiceNumber == row["id"]

    /** record a proven payment once (status created → completed; a trxID used before is never granted twice) */
    private fun complete(row: Map<String, Any?>, r: PgwResult, now: Long): Boolean = try {
        jdbc.update(
            "UPDATE pgw_payments SET status = 'completed', trx_id = ?, provider_status = ?, completed_at = ?, checked_at = ? WHERE id = ? AND status = 'created'",
            r.trxId, r.transactionStatus, Sql.ts(now), Sql.ts(now), row["id"],
        ) > 0
    } catch (e: DuplicateKeyException) {
        jdbc.update("UPDATE pgw_payments SET status = 'failed', provider_status = 'trx_reused', checked_at = ? WHERE id = ? AND status = 'created'", Sql.ts(now), row["id"])
        false
    }

    /**
     * GET /api/pay/bkash/callback?paymentID=&status= — executes / queries server-side, records, and answers with the
     * fixed redirect. Unknown paymentID → null (404).
     */
    fun callback(paymentId: String?, status: String?, now: Long): String? {
        if (paymentId.isNullOrEmpty() || paymentId.length > 100) return null
        val row = row("payment_id = ?", paymentId) ?: return null
        val id = row["id"] as String
        val client = row["client"] as String
        // idempotent: anything but a pending checkout is answered from what is stored
        if (row["status"] != "created") return resultUrl(id, client)
        val p = provider
        var result: PgwResult? = null
        if (status == "success") {
            result = try {
                p.execute(paymentId)
            } catch (e: PgwException) {
                log.info("pgw execute failed ({}), querying", e.message)
                null
            }
        }
        // execute failed or timed out (it may still have completed), or failure/cancel said by the browser: ask bKash
        if (result == null || !matches(result, row)) {
            result = try {
                p.query(paymentId)
            } catch (e: PgwException) {
                log.info("pgw query failed: {}", e.message)
                null
            }
        }
        when {
            result != null && matches(result, row) -> complete(row, result, now)
            result != null && result.transactionStatus in setOf("Cancelled", "Failed", "Expired") || status == "cancel" || status == "failure" -> {
                val st = if (status == "cancel" || result?.transactionStatus == "Cancelled") "cancelled" else "failed"
                jdbc.update("UPDATE pgw_payments SET status = ?, provider_status = ?, checked_at = ? WHERE id = ? AND status = 'created'", st, result?.transactionStatus, Sql.ts(now), id)
            }
            else -> jdbc.update("UPDATE pgw_payments SET checked_at = ?, provider_status = ? WHERE id = ?", Sql.ts(now), result?.transactionStatus, id)
        }
        return resultUrl(id, client)
    }

    /** pgw_status {id} — the row's own account only (the app and web read the result from here, never from the URL) */
    fun status(lang: String?, user: AuthUser, id: String?): Map<String, Any?> {
        val row = id?.takeIf { Rules.isValidId(it) }?.let { row("id = ?", it) } ?: throw Pay.notFound(lang)
        if (row["uid"] != user.uid || user.patientSideOnly) throw Pay.notFound(lang)
        return linkedMapOf(
            "id" to row["id"], "purpose" to row["purpose"], "amount" to Pay.num(row["amount"] as BigDecimal), "status" to row["status"],
            "trxId" to row["trx_id"], "createdAt" to (row["created_at"] as java.sql.Timestamp).time,
            "completedAt" to (row["completed_at"] as? java.sql.Timestamp)?.time,
        )
    }

    /** the job: pending checkouts older than 2 minutes are queried; after 24 h they fail */
    fun reconcile(now: Long): Int {
        val p = provider
        var done = 0
        jdbc.update(
            "UPDATE pgw_payments SET status = 'failed', provider_status = coalesce(provider_status, 'timeout') WHERE status = 'created' AND created_at < ?",
            Sql.ts(now - GIVE_UP_MS),
        )
        if (!p.configured) return 0
        val rows = jdbc.queryForList(
            "SELECT * FROM pgw_payments WHERE status = 'created' AND payment_id IS NOT NULL AND created_at < ? AND (checked_at IS NULL OR checked_at < ?) ORDER BY created_at LIMIT 100",
            Sql.ts(now - PENDING_AFTER_MS), Sql.ts(now - PENDING_AFTER_MS),
        )
        for (row in rows) {
            val r = try {
                p.query(row["payment_id"] as String)
            } catch (e: PgwException) {
                null
            }
            if (r != null && matches(r, row)) {
                if (complete(row, r, now)) done++
            } else if (r != null && r.transactionStatus in setOf("Cancelled", "Failed", "Expired")) {
                jdbc.update("UPDATE pgw_payments SET status = ?, provider_status = ?, checked_at = ? WHERE id = ? AND status = 'created'",
                    if (r.transactionStatus == "Cancelled") "cancelled" else "failed", r.transactionStatus, Sql.ts(now), row["id"])
            } else {
                jdbc.update("UPDATE pgw_payments SET checked_at = ? WHERE id = ?", Sql.ts(now), row["id"])
            }
        }
        return done
    }

    /** admin_pgw_refund {id} — a completed checkout back in full through bKash */
    fun refund(lang: String?, id: String?): Map<String, Any?> {
        val row = id?.takeIf { Rules.isValidId(it) }?.let { row("id = ?", it) } ?: throw Pay.notFound(lang)
        if (row["status"] != "completed") throw Pay.state(lang)
        val p = provider
        if (!p.configured) throw unavailable(lang)
        val r = try {
            p.refund(row["payment_id"] as String, row["trx_id"] as String, row["amount"] as BigDecimal, "Lifolo refund")
        } catch (e: PgwException) {
            throw unavailable(lang)
        }
        if (r.refundTrxId == null || r.transactionStatus != "Completed") throw Pay.err(lang, 502, "bKash refused the refund", "বিকাশ ফেরত নেয়নি", "pgw_refund") // bn-ok
        jdbc.update("UPDATE pgw_payments SET status = 'refunded', refund_trx = ? WHERE id = ? AND status = 'completed'", r.refundTrxId, row["id"])
        return linkedMapOf("ok" to true, "refundTrxId" to r.refundTrxId)
    }
}

/** pgw_checkout, pgw_status, admin_pgw_refund */
@Component
class PgwActions(private val pgw: PgwService, private val admins: AdminService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "pgw_checkout" to ActionHandler { ctx -> pgw.checkout(ctx.lang, ctx.user, ctx.body.string("purpose"), ctx.body.string("client")) },
        "pgw_status" to ActionHandler { ctx -> pgw.status(ctx.lang, ctx.user, ctx.body.string("id")) },
        "admin_pgw_refund" to ActionHandler { ctx ->
            if (!admins.isAdmin(ctx.user)) throw Pay.err(ctx.lang, 403, "Admins only", "শুধু অ্যাডমিন")
            pgw.refund(ctx.lang, ctx.body.string("id"))
        },
    )
}

/**
 * The browser endpoints: the bKash callback (always a fixed redirect) and the email's "আমি করিনি" link (a page with a
 * button, so a mail scanner opening the link freezes nothing).
 */
@RestController
class PayHttpController(private val pgw: PgwService, private val guard: PayMethodGuard, private val pay: PayService) {
    @GetMapping("/api/pay/bkash/callback")
    fun bkashCallback(
        @RequestParam(name = "paymentID", required = false) paymentId: String?,
        @RequestParam(name = "status", required = false) status: String?,
    ): ResponseEntity<Any> {
        val to = pgw.callback(paymentId, status, System.currentTimeMillis())
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("not found")
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(to)).build()
    }

    private fun page(title: String, body: String): ResponseEntity<Any> = ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(
        "<!doctype html><html lang=\"bn\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title><style>${com.lifolo.common.WebFont.FACES}</style></head>" +
            "<body style=\"font-family:${com.lifolo.common.WebFont.STACK};max-width:32rem;margin:2rem auto;padding:0 1rem\">$body</body></html>",
    )

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    @GetMapping("/api/pay/freeze")
    fun freezePage(@RequestParam(name = "t", required = false) t: String?, req: HttpServletRequest): ResponseEntity<Any> {
        if (guard.parseToken(t, System.currentTimeMillis()) == null) return page("Lifolo", "<p>লিংকটা আর কাজ করছে না। অ্যাপ থেকে \"আমি করিনি\" চাপুন বা লাইফলো টিমকে জানান।</p>") // bn-ok
        return page(
            "Lifolo",
            "<h2>আমি করিনি</h2><p>নিচের বোতাম চাপলে আপনার প্রতিষ্ঠানের সব পেমেন্ট নম্বর সাথে সাথে বন্ধ হবে। লাইফলো টিম যোগাযোগ করবে।</p>" + // bn-ok
                "<form method=\"post\" action=\"/api/pay/freeze\"><input type=\"hidden\" name=\"t\" value=\"${esc(t!!)}\">" +
                "<button type=\"submit\" style=\"font-size:1.1rem;padding:.6rem 1.2rem\">সব পেমেন্ট নম্বর বন্ধ করুন</button></form>", // bn-ok
        )
    }

    @PostMapping("/api/pay/freeze")
    fun freeze(@RequestParam(name = "t", required = false) t: String?): ResponseEntity<Any> {
        val now = System.currentTimeMillis()
        val (kind, bizId) = guard.parseToken(t, now) ?: return page("Lifolo", "<p>লিংকটা আর কাজ করছে না।</p>") // bn-ok
        guard.freeze(kind, bizId, null, "email", now)
        return page("Lifolo", "<p>সব পেমেন্ট নম্বর বন্ধ করা হয়েছে। লাইফলো টিম যোগাযোগ করবে।</p><p>Payment numbers are frozen. The Lifolo team will contact you.</p>") // bn-ok
    }
}
