package com.lifolo.payments

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler

/**
 * `payRequestId` + `payStatus` on the source objects (appointments: chamber_fee / booking_advance; rx orders: delivery;
 * lab orders: lab): the latest pay request of that source, so clients open the PaySheet and the business's confirm
 * (with the three checks) directly, and list cards show "অগ্রিম বাকি" (PAYMENTS.md). Like InvoiceLinks.
 */
object PayLinks {
    data class Link(val id: String, val status: String, val purpose: String)

    val APPOINTMENT = listOf("chamber_fee", "booking_advance")
    val RX_ORDER = listOf("delivery")
    val LAB_ORDER = listOf("lab")

    private val SAFE = Regex("^[a-z_]+$")

    /** source id → its latest pay request of [purposes] */
    fun latest(jdbc: JdbcTemplate, refType: String, ids: Collection<String>, purposes: List<String>): Map<String, Link> {
        val list = ids.distinct()
        if (list.isEmpty()) return emptyMap()
        val out = HashMap<String, Link>()
        for (chunk in list.chunked(500)) {
            jdbc.query(
                """
                SELECT ref_id, id, status, purpose FROM pay_requests
                WHERE ref_type = ? AND purpose = ANY (?::text[]) AND ref_id IN (${chunk.joinToString(",") { "?" }})
                ORDER BY created_at, id
                """.trimIndent(),
                RowCallbackHandler { rs -> out[rs.getString(1)] = Link(rs.getString(2), rs.getString(3), rs.getString(4)) },
                refType, purposes.joinToString(",", "{", "}"), *chunk.toTypedArray(),
            )
        }
        return out
    }

    /** a SELECT-list subquery: [col] (id | status) of the latest request of [refType] whose ref_id is [idExpr] (trusted) */
    fun sql(refType: String, idExpr: String, purposes: List<String>, col: String): String {
        require(SAFE.matches(refType) && Regex("^[a-z_.]+$").matches(idExpr) && col in setOf("id", "status") && purposes.all { SAFE.matches(it) })
        val ps = purposes.joinToString(",") { "'$it'" }
        return "(SELECT p.$col FROM pay_requests p WHERE p.ref_type = '$refType' AND p.ref_id = $idExpr AND p.purpose IN ($ps) ORDER BY p.created_at DESC, p.id DESC LIMIT 1)"
    }

    /** the two fields for a view map */
    fun fields(link: Link?): Map<String, Any?> = linkedMapOf("payRequestId" to link?.id, "payStatus" to link?.status)
}
