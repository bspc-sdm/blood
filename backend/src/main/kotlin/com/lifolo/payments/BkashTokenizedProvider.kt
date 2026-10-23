package com.lifolo.payments

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** a checkout the gateway opened: where to send the payer */
data class PgwCheckout(val paymentId: String, val redirectUrl: String)

/** what the gateway says about one payment (execute / query) */
data class PgwResult(
    val statusCode: String?,
    val statusMessage: String?,
    val transactionStatus: String?,
    val paymentId: String?,
    val trxId: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val intent: String?,
    val merchantInvoiceNumber: String?,
) {
    val ok: Boolean get() = statusCode == "0000"
}

data class PgwRefund(val statusCode: String?, val refundTrxId: String?, val transactionStatus: String?, val amount: BigDecimal?)

/** the gateway could not be reached or answered nonsense (timeouts: query again later) */
class PgwException(message: String) : RuntimeException(message)

/**
 * A payment gateway for Lifolo's OWN income (PAYMENTS.md "Gateway-ready") — never for a business's fees (security
 * review P10). [configured] false = no credentials: the checkout answers 503 `pgw_unavailable`.
 */
interface PaymentProvider {
    val configured: Boolean

    fun createCheckout(merchantInvoiceNumber: String, amount: BigDecimal, payerReference: String, callbackUrl: String): PgwCheckout

    fun execute(paymentId: String): PgwResult

    fun query(paymentId: String): PgwResult

    fun refund(paymentId: String, trxId: String, amount: BigDecimal, reason: String): PgwRefund
}

/**
 * bKash Tokenized Checkout (official API, v1.2.0-beta paths): grant token → create → execute → query → refund. Configured
 * from the environment: BKASH_BASE_URL (sandbox `https://tokenized.sandbox.bka.sh/v1.2.0-beta`), BKASH_APP_KEY,
 * BKASH_APP_SECRET, BKASH_USERNAME, BKASH_PASSWORD. The grant token (about 1 h) is cached in memory until a minute before
 * it ends. Secrets and tokens are never logged or returned.
 */
@Component
class BkashTokenizedProvider(
    @Value("\${BKASH_BASE_URL:}") baseUrl: String,
    @Value("\${BKASH_APP_KEY:}") private val appKey: String,
    @Value("\${BKASH_APP_SECRET:}") private val appSecret: String,
    @Value("\${BKASH_USERNAME:}") private val username: String,
    @Value("\${BKASH_PASSWORD:}") private val password: String,
) : PaymentProvider {
    private val base = baseUrl.trim().trimEnd('/')
    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile
    private var token: Pair<String, Long>? = null

    override val configured: Boolean
        get() = (base.startsWith("https://") || base.startsWith("http://localhost") || base.startsWith("http://127.0.0.1")) &&
            appKey.isNotBlank() && appSecret.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    private fun post(path: String, body: Map<String, Any?>, headers: Map<String, String>): JsonNode {
        if (!configured) throw PgwException("bkash not configured")
        val req = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val res = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw PgwException("bkash $path: ${e.javaClass.simpleName}")
        }
        if (res.statusCode() !in 200..299) throw PgwException("bkash $path: HTTP ${res.statusCode()}")
        return try {
            mapper.readTree(res.body())
        } catch (e: Exception) {
            throw PgwException("bkash $path: not JSON")
        }
    }

    /** the id_token (cached) */
    fun idToken(): String {
        val now = System.currentTimeMillis()
        token?.let { (t, until) -> if (now < until) return t }
        val r = post(
            "/tokenized/checkout/token/grant",
            mapOf("app_key" to appKey, "app_secret" to appSecret),
            mapOf("username" to username, "password" to password),
        )
        val t = r.path("id_token").asText("")
        if (t.isEmpty()) throw PgwException("bkash grant: ${r.path("statusCode").asText("no token")}")
        val ttl = r.path("expires_in").asLong(3600).coerceIn(120, 24 * 3600)
        token = t to now + (ttl - 60) * 1000
        return t
    }

    private fun auth(): Map<String, String> = mapOf("Authorization" to idToken(), "X-App-Key" to appKey)

    private fun money(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun amountOf(n: JsonNode): BigDecimal? = n.path("amount").asText("").trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    private fun text(n: JsonNode, key: String): String? = n.path(key).asText("").takeIf { it.isNotEmpty() }

    private fun result(n: JsonNode) = PgwResult(
        text(n, "statusCode"), text(n, "statusMessage"), text(n, "transactionStatus"), text(n, "paymentID"), text(n, "trxID"), amountOf(n),
        text(n, "currency"), text(n, "intent"), text(n, "merchantInvoiceNumber") ?: text(n, "merchantInvoice"),
    )

    override fun createCheckout(merchantInvoiceNumber: String, amount: BigDecimal, payerReference: String, callbackUrl: String): PgwCheckout {
        val r = post(
            "/tokenized/checkout/create",
            mapOf(
                "mode" to "0011", "payerReference" to payerReference.take(50), "callbackURL" to callbackUrl, "amount" to money(amount),
                "currency" to "BDT", "intent" to "sale", "merchantInvoiceNumber" to merchantInvoiceNumber,
            ),
            auth(),
        )
        val id = text(r, "paymentID")
        val url = text(r, "bkashURL")
        if (text(r, "statusCode") != "0000" || id == null || url == null) throw PgwException("bkash create: ${text(r, "statusCode") ?: "no paymentID"}")
        return PgwCheckout(id, url)
    }

    override fun execute(paymentId: String): PgwResult = result(post("/tokenized/checkout/execute", mapOf("paymentID" to paymentId), auth()))

    override fun query(paymentId: String): PgwResult = result(post("/tokenized/checkout/payment/status", mapOf("paymentID" to paymentId), auth()))

    override fun refund(paymentId: String, trxId: String, amount: BigDecimal, reason: String): PgwRefund {
        val r = post(
            "/tokenized/checkout/payment/refund",
            mapOf("paymentID" to paymentId, "amount" to money(amount), "trxID" to trxId, "sku" to "lifolo", "reason" to reason.take(100)),
            auth(),
        )
        return PgwRefund(text(r, "statusCode"), text(r, "refundTrxID"), text(r, "transactionStatus"), amountOf(r))
    }
}
