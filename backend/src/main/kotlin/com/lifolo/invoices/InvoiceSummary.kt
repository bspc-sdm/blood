package com.lifolo.invoices

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.notify.PushService
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/**
 * "দিনের শেষ" — the owner's end-of-day summary (security review I2): the day's receipts and total, voids, large
 * discounts (more than 10 % of the lines' gross) and stock write-offs. One push (+ bell list) per business owner with
 * anything that day, once per Dhaka day (job marker). Sent at 21:30 Dhaka.
 */
@Service
class InvoiceSummary(private val jdbc: JdbcTemplate, private val push: PushService, private val features: InvoiceFeatures) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** one business of an owner (pharmacy/lab: bizId = the owner; chamber: the doctor; ambulance: the car) */
    data class Key(val owner: String, val kind: String, val bizId: String)

    class Day(var receipts: Int = 0, var total: BigDecimal = BigDecimal.ZERO, var voids: Int = 0, var bigDisc: Int = 0, var writeOffs: Int = 0, var writeOffLoss: BigDecimal = BigDecimal.ZERO)

    /** business → the day's numbers (Dhaka day of [nowMs]) */
    fun collect(nowMs: Long): Map<Key, Day> {
        val day = DhakaTime.day(nowMs)
        val from = Sql.ts(day.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val to = Sql.ts(day.plusDays(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val out = LinkedHashMap<Key, Day>()
        jdbc.query(
            """
            SELECT owner_uid, kind, biz_id, count(*) AS n, coalesce(sum(total), 0) AS t,
              count(*) FILTER (WHERE (disc + (SELECT coalesce(sum((l->>'disc')::numeric), 0) FROM jsonb_array_elements(lines) l))
                > 0.1 * (SELECT coalesce(sum((l->>'qty')::numeric * (l->>'price')::numeric), 0) FROM jsonb_array_elements(lines) l)) AS big
            FROM invoices WHERE owner_uid IS NOT NULL AND issued_at >= ? AND issued_at < ? AND status = 'issued' GROUP BY owner_uid, kind, biz_id
            """.trimIndent(),
            RowCallbackHandler { rs ->
                val d = out.getOrPut(Key(rs.getString(1), rs.getString(2), rs.getString(3))) { Day() }
                d.receipts = rs.getInt("n")
                d.total = rs.getBigDecimal("t")
                d.bigDisc = rs.getInt("big")
            },
            from, to,
        )
        jdbc.query(
            "SELECT owner_uid, kind, biz_id, count(*) FROM invoices WHERE owner_uid IS NOT NULL AND voided_at >= ? AND voided_at < ? GROUP BY owner_uid, kind, biz_id",
            RowCallbackHandler { rs -> out.getOrPut(Key(rs.getString(1), rs.getString(2), rs.getString(3))) { Day() }.voids = rs.getInt(4) },
            from, to,
        )
        jdbc.query(
            "SELECT shop_uid, count(*), coalesce(sum(loss), 0) FROM shop_adjustments WHERE kind = 'writeoff' AND day = ? GROUP BY shop_uid",
            RowCallbackHandler { rs ->
                val d = out.getOrPut(Key(rs.getString(1), "pharmacy", rs.getString(1))) { Day() }
                d.writeOffs = rs.getInt(2)
                d.writeOffLoss = rs.getBigDecimal(3)
            },
            java.sql.Date.valueOf(day),
        )
        return out
    }

    /** sends the day's summaries once (a second run the same day sends nothing); returns how many owners it was for */
    fun run(nowMs: Long): Int {
        val day: LocalDate = DhakaTime.day(nowMs)
        val first = jdbc.update("INSERT INTO job_markers (key, done, done_at) VALUES (?, TRUE, now()) ON CONFLICT (key) DO NOTHING", "inv-summary:$day")
        if (first == 0) return 0
        val days = collect(nowMs).filter { (k, d) -> features.kindOn(k.owner, k.kind) && (d.receipts > 0 || d.voids > 0 || d.writeOffs > 0) }
        if (days.isEmpty()) return 0
        // one push per business (an owner with a pharmacy and a lab gets two)
        val items = ArrayList<com.lifolo.notify.PushItem>()
        val langs = jdbc.queryForList(
            "SELECT uid, lang FROM user_profiles WHERE uid IN (${days.keys.map { it.owner }.distinct().joinToString(",") { "?" }})",
            *days.keys.map { it.owner }.distinct().toTypedArray(),
        ).associate { it["uid"] as String to it["lang"] as String? }
        for ((k, d) in days) {
            if (k.owner !in langs) continue
            val lang = langs[k.owner]
            val total = InvoiceMath.num(InvoiceMath.m2(d.total))
            val parts = ArrayList<String>()
            if (d.voids > 0) parts += Lang.pick(lang, "voided ${d.voids}", "বাতিল ${d.voids}") // bn-ok
            if (d.bigDisc > 0) parts += Lang.pick(lang, "large discounts ${d.bigDisc}", "বড় ছাড় ${d.bigDisc}") // bn-ok
            if (d.writeOffs > 0) parts += Lang.pick(lang, "write-offs ৳${InvoiceMath.num(InvoiceMath.m2(d.writeOffLoss))}", "নষ্ট/বাদ ৳${InvoiceMath.num(InvoiceMath.m2(d.writeOffLoss))}") // bn-ok
            val title = Lang.pick(lang, "End of day: ${d.receipts} receipts, ৳$total", "দিনের শেষ: ${d.receipts}টা রসিদ, ৳$total") // bn-ok
            val body = parts.joinToString(" · ")
            items += com.lifolo.notify.PushItem(
                k.owner, if (lang == "en") title else Lang.bnNumbers(title), if (lang == "en") body else Lang.bnNumbers(body),
                mapOf("type" to "invoice_summary", "day" to day.toString(), "kind" to k.kind, "bizId" to k.bizId),
            )
        }
        push.send(items)
        return days.size
    }

    /** 21:30 Dhaka (15:30 UTC) */
    @Recurring(id = "invoice-day-summary", cron = "30 15 * * *", zoneId = "UTC")
    @Job(name = "Receipts: end-of-day summary")
    fun job() {
        try {
            run(System.currentTimeMillis())
        } catch (e: Exception) {
            log.error("invoice summary failed", e)
        }
    }
}
