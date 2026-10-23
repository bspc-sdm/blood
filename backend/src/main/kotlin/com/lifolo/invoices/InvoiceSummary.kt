package com.lifolo.invoices

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
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
class InvoiceSummary(private val jdbc: JdbcTemplate, private val push: PushService) {
    private val log = LoggerFactory.getLogger(javaClass)

    class Day(var receipts: Int = 0, var total: BigDecimal = BigDecimal.ZERO, var voids: Int = 0, var bigDisc: Int = 0, var writeOffs: Int = 0, var writeOffLoss: BigDecimal = BigDecimal.ZERO)

    /** owner uid → the day's numbers (Dhaka day of [nowMs]) */
    fun collect(nowMs: Long): Map<String, Day> {
        val day = DhakaTime.day(nowMs)
        val from = Sql.ts(day.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val to = Sql.ts(day.plusDays(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val out = LinkedHashMap<String, Day>()
        jdbc.query(
            """
            SELECT owner_uid, count(*) AS n, coalesce(sum(total), 0) AS t,
              count(*) FILTER (WHERE (disc + (SELECT coalesce(sum((l->>'disc')::numeric), 0) FROM jsonb_array_elements(lines) l))
                > 0.1 * (SELECT coalesce(sum((l->>'qty')::numeric * (l->>'price')::numeric), 0) FROM jsonb_array_elements(lines) l)) AS big
            FROM invoices WHERE owner_uid IS NOT NULL AND issued_at >= ? AND issued_at < ? AND status = 'issued' GROUP BY owner_uid
            """.trimIndent(),
            RowCallbackHandler { rs ->
                val d = out.getOrPut(rs.getString(1)) { Day() }
                d.receipts = rs.getInt("n")
                d.total = rs.getBigDecimal("t")
                d.bigDisc = rs.getInt("big")
            },
            from, to,
        )
        jdbc.query(
            "SELECT owner_uid, count(*) FROM invoices WHERE owner_uid IS NOT NULL AND voided_at >= ? AND voided_at < ? GROUP BY owner_uid",
            RowCallbackHandler { rs -> out.getOrPut(rs.getString(1)) { Day() }.voids = rs.getInt(2) },
            from, to,
        )
        jdbc.query(
            "SELECT shop_uid, count(*), coalesce(sum(loss), 0) FROM shop_adjustments WHERE kind = 'writeoff' AND day = ? GROUP BY shop_uid",
            RowCallbackHandler { rs ->
                val d = out.getOrPut(rs.getString(1)) { Day() }
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
        val days = collect(nowMs).filterValues { it.receipts > 0 || it.voids > 0 || it.writeOffs > 0 }
        if (days.isEmpty()) return 0
        push.pushUsers(days.keys) { uid, lang ->
            val d = days[uid]!!
            val total = InvoiceMath.num(InvoiceMath.m2(d.total))
            val parts = ArrayList<String>()
            if (d.voids > 0) parts += Lang.pick(lang, "voided ${d.voids}", "বাতিল ${d.voids}") // bn-ok
            if (d.bigDisc > 0) parts += Lang.pick(lang, "large discounts ${d.bigDisc}", "বড় ছাড় ${d.bigDisc}") // bn-ok
            if (d.writeOffs > 0) parts += Lang.pick(lang, "write-offs ৳${InvoiceMath.num(InvoiceMath.m2(d.writeOffLoss))}", "নষ্ট/বাদ ৳${InvoiceMath.num(InvoiceMath.m2(d.writeOffLoss))}") // bn-ok
            val title = Lang.pick(lang, "End of day: ${d.receipts} receipts, ৳$total", "দিনের শেষ: ${d.receipts}টা রসিদ, ৳$total") // bn-ok
            val body = parts.joinToString(" · ")
            PushText(if (lang == "en") title else Lang.bnNumbers(title), if (lang == "en") body else Lang.bnNumbers(body), mapOf("type" to "invoice_summary", "day" to day.toString()))
        }
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
