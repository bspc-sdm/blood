package com.lifolo.invoices

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler

/**
 * `invoiceId` on the source objects (rx orders, lab orders, appointments, ambulance requests): the latest receipt of
 * that source that is not void, so clients link to it directly (docs/INVOICES.md).
 */
object InvoiceLinks {
    /** a SELECT-list subquery: the live receipt id of [refType] whose ref_id is [idExpr] (a trusted column name) */
    fun sql(refType: String, idExpr: String): String {
        require(refType in InvoiceMath.REF_TYPES && Regex("^[a-z_.]+$").matches(idExpr))
        return "(SELECT i.id FROM invoices i WHERE i.ref_type = '$refType' AND i.ref_id = $idExpr AND i.status = 'issued' ORDER BY i.issued_at DESC LIMIT 1)"
    }

    /** source id → live receipt id */
    fun live(jdbc: JdbcTemplate, refType: String, ids: Collection<String>): Map<String, String> {
        val list = ids.distinct()
        if (list.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (chunk in list.chunked(500)) {
            jdbc.query(
                "SELECT ref_id, id FROM invoices WHERE ref_type = ? AND status = 'issued' AND ref_id IN (${chunk.joinToString(",") { "?" }}) ORDER BY issued_at",
                RowCallbackHandler { rs -> out[rs.getString(1)] = rs.getString(2) },
                refType, *chunk.toTypedArray(),
            )
        }
        return out
    }
}
