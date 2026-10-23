package com.lifolo.payments

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.common.Ids
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Demo Lifolo Pay (PAYMENTS.md): the demo pharmacy, lab and doctor with payment numbers — the demo world's fake numbers
 * only (0120000XXXX, security review P8), matured — the doctor's advance fee on the first in-person chamber, and a few
 * of the demo patient's requests in different states (paid, waiting for the check, rejected, open). Runs after the
 * demo receipts; the demo refresh builds it again every new Dhaka day.
 */
@Component
class PayDemo(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : DemoSeedPart {
    override val step = 5
    override val label = "স্বাস্থ্য ও কেয়ারগিভার"
    override val order = 110

    private fun method(kind: String, biz: String, method: String, number: String, type: String, pos: Int, since: Long) {
        jdbc.update(
            """
            INSERT INTO pay_methods (kind, biz_id, method, number, type, active, active_since, position, owner_uid, updated_at)
            VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """.trimIndent(),
            kind, biz, method, number, type, Sql.ts(since), pos, biz, Sql.ts(since),
        )
    }

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val matured = now - 2 * Pay.DAY
        val doctor = ctx.user("doctor").uid
        val pharmacy = ctx.user("pharmacy").uid
        val lab = ctx.user("lab").uid
        val patient = ctx.user("patient").uid
        // the doctor's bKash is the demo online chamber's number (CHAMBERS-ONLINE.md demo)
        method("chamber", doctor, "bkash", "01200000204", "personal", 0, matured)
        method("chamber", doctor, "nagad", "01200000205", "personal", 1, matured)
        method("pharmacy", pharmacy, "bkash", "01200000301", "merchant", 0, matured)
        method("pharmacy", pharmacy, "nagad", "01200000302", "personal", 1, matured)
        method("lab", lab, "bkash", "01200000401", "merchant", 0, matured)
        jdbc.queryForList(
            "SELECT coalesce(nullif(hospital_id, ''), hospital_name) FROM provider_chambers WHERE provider_uid = ? AND mode <> 'online' ORDER BY idx LIMIT 1",
            String::class.java, doctor,
        ).firstOrNull()?.takeIf { it.isNotEmpty() }?.let { key ->
            jdbc.update(
                "INSERT INTO pay_chamber_advance (doctor_uid, chamber_key, amount, refundable_hours) VALUES (?, ?, 200, 6) ON CONFLICT DO NOTHING",
                doctor, key,
            )
        }

        fun snap(kind: String, biz: String): String = mapper.writeValueAsString(
            jdbc.queryForList("SELECT method, number, type FROM pay_methods WHERE kind = ? AND biz_id = ? ORDER BY position", kind, biz)
                .map { linkedMapOf("method" to it["method"], "number" to it["number"], "type" to it["type"]) },
        )

        // the demo patient's receipts → requests in different states
        val invs = jdbc.queryForList(
            "SELECT id, kind, biz_id, owner_uid, biz_name, total FROM invoices WHERE patient_uid = ? AND status = 'issued' AND kind IN ('pharmacy', 'lab', 'chamber') ORDER BY issued_at",
            patient,
        )
        val states = mapOf("pharmacy" to "confirmed", "lab" to "claimed", "chamber" to "rejected")
        var n = 0
        for (inv in invs) {
            val kind = inv["kind"] as String
            val state = states[kind] ?: continue
            n++
            val claimed = state != "open"
            val at = now - (6 - n) * Pay.HOUR
            jdbc.update(
                """
                INSERT INTO pay_requests (id, kind, biz_id, owner_uid, biz_name, payer_uid, ref_type, ref_id, purpose, amount, methods, status,
                  claim_method, claim_trx, claim_last4, claimed_at, claimed_by, confirmed_at, confirmed_by, reject_reason, reject_note, rejected_at,
                  invoice_id, expires_at, created_at, updated_at, is_demo)
                VALUES (?, ?, ?, ?, ?, ?, 'invoice', ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """.trimIndent(),
                "demo-pay-$n-" + Ids.newId(8), kind, inv["biz_id"], inv["owner_uid"], inv["biz_name"], patient, inv["id"],
                if (kind == "lab") "lab" else "invoice_due", inv["total"], snap(kind, inv["biz_id"] as String), state,
                if (claimed) "bkash" else null, if (claimed) "DEMO${n}X7K2P" else null, if (claimed) "0101" else null,
                if (claimed) Sql.ts(at) else null, if (claimed) patient else null,
                if (state == "confirmed") Sql.ts(at + 10 * Pay.MIN) else null, if (state == "confirmed") inv["owner_uid"] else null,
                if (state == "rejected") "not_received" else null, if (state == "rejected") "এই TrxID-তে টাকা আসেনি" else null, // bn-ok
                if (state == "rejected") Sql.ts(at + 20 * Pay.MIN) else null,
                if (state == "confirmed") inv["id"] else null, Sql.ts(now + 7 * Pay.DAY), Sql.ts(at - Pay.HOUR), Sql.ts(at),
            )
        }
    }
}
