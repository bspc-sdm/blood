package com.lifolo.payments

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A mocked bKash Tokenized Checkout server (sandbox paths, v1.2.0-beta) for the provider and gateway tests: each path
 * answers what [answers] says (JSON text, or a status code with `!500`), and every request is recorded.
 */
class BkashMock : AutoCloseable {
    data class Hit(val path: String, val headers: Map<String, String>, val body: JsonNode)

    private val mapper = ObjectMapper()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val hits = CopyOnWriteArrayList<Hit>()
    val answers = ConcurrentHashMap<String, String>()
    private val seq = java.util.concurrent.atomic.AtomicInteger()

    init {
        answers["/tokenized/checkout/token/grant"] = """{"statusCode":"0000","statusMessage":"Successful","id_token":"tok-1","token_type":"Bearer","expires_in":3600,"refresh_token":"r1"}"""
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/v1.2.0-beta")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            hits.add(Hit(path, headers, if (body.isBlank()) mapper.createObjectNode() else mapper.readTree(body)))
            // "{n}" in an answer = a new number per request (e.g. a new paymentID per checkout)
            val raw = answers[path] ?: """{"statusCode":"2001","statusMessage":"Invalid App Key"}"""
            val answer = if (raw.contains("{n}")) raw.replace("{n}", seq.incrementAndGet().toString()) else raw
            if (answer.startsWith("!")) {
                ex.sendResponseHeaders(answer.drop(1).toInt(), -1)
                ex.close()
                return@createContext
            }
            val bytes = answer.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1.2.0-beta"

    fun provider() = BkashTokenizedProvider(baseUrl, "app-key", "app-secret", "user", "pass")

    fun count(path: String) = hits.count { it.path == path }

    /** execute / query answer for a payment */
    fun payment(paymentId: String, invoice: String, amount: String, status: String = "Completed", trx: String? = "TRX$paymentId", code: String = "0000"): String =
        mapper.writeValueAsString(
            linkedMapOf(
                "statusCode" to code, "statusMessage" to "Successful", "paymentID" to paymentId, "trxID" to trx, "transactionStatus" to status,
                "amount" to amount, "currency" to "BDT", "intent" to "sale", "merchantInvoiceNumber" to invoice,
            ),
        )

    override fun close() {
        server.stop(0)
    }
}
