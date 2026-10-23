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

    /**
     * invoice_day_summary: one business's day in detail — what the end-of-day push counts, item by item: receipts and
     * totals per issuer (the doctor and each assistant), voids with reasons, receipts with discounts above 10 %, pharmacy
     * sales below the product's price, stock write-offs.
     */
    fun detail(b: Biz, day: LocalDate): Map<String, Any?> {
        val from = Sql.ts(day.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val to = Sql.ts(day.plusDays(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val byIssuer = jdbc.queryForList(
            """
            SELECT i.issued_by, coalesce(p.name, '') AS name, count(*) FILTER (WHERE i.status = 'issued') AS n,
              coalesce(sum(i.total) FILTER (WHERE i.status = 'issued'), 0) AS total, coalesce(sum(i.paid) FILTER (WHERE i.status = 'issued'), 0) AS paid,
              count(*) FILTER (WHERE i.status = 'void') AS voids
            FROM invoices i LEFT JOIN user_profiles p ON p.uid = i.issued_by
            WHERE i.kind = ? AND i.biz_id = ? AND i.issued_at >= ? AND i.issued_at < ? GROUP BY i.issued_by, p.name ORDER BY 3 DESC
            """.trimIndent(),
            b.kind, b.id, from, to,
        ).map {
            linkedMapOf(
                "uid" to it["issued_by"], "name" to it["name"], "receipts" to (it["n"] as Number).toInt(),
                "total" to InvoiceMath.num(it["total"] as BigDecimal), "paid" to InvoiceMath.num(it["paid"] as BigDecimal), "voids" to (it["voids"] as Number).toInt(),
            )
        }
        val voids = jdbc.queryForList(
            """
            SELECT i.id, i.no, i.total, i.void_reason, i.void_note, i.voided_at, i.issued_at, i.voided_by, coalesce(p.name, '') AS by_name
            FROM invoices i LEFT JOIN user_profiles p ON p.uid = i.voided_by
            WHERE i.kind = ? AND i.biz_id = ? AND i.voided_at >= ? AND i.voided_at < ? ORDER BY i.voided_at
            """.trimIndent(),
            b.kind, b.id, from, to,
        ).map {
            linkedMapOf(
                "id" to it["id"], "no" to it["no"], "total" to InvoiceMath.num(it["total"] as BigDecimal), "reason" to it["void_reason"],
                "note" to it["void_note"], "at" to (it["voided_at"] as java.sql.Timestamp).time, "issuedAt" to (it["issued_at"] as java.sql.Timestamp).time,
                "by" to it["voided_by"], "byName" to it["by_name"],
            )
        }
        val bigDisc = jdbc.queryForList(
            """
            SELECT id, no, total, gross, d FROM (
              SELECT id, no, total,
                (SELECT coalesce(sum((l->>'qty')::numeric * (l->>'price')::numeric), 0) FROM jsonb_array_elements(lines) l) AS gross,
                disc + (SELECT coalesce(sum((l->>'disc')::numeric), 0) FROM jsonb_array_elements(lines) l) AS d
              FROM invoices WHERE kind = ? AND biz_id = ? AND issued_at >= ? AND issued_at < ? AND status = 'issued') x
            WHERE d > 0.1 * gross ORDER BY no
            """.trimIndent(),
            b.kind, b.id, from, to,
        ).map {
            val gross = it["gross"] as BigDecimal
            val d = it["d"] as BigDecimal
            linkedMapOf(
                "id" to it["id"], "no" to it["no"], "total" to InvoiceMath.num(it["total"] as BigDecimal), "discount" to InvoiceMath.num(InvoiceMath.m2(d)),
                "pct" to if (gross.signum() > 0) d.multiply(BigDecimal(100)).divide(gross, 0, java.math.RoundingMode.HALF_UP).toInt() else 100,
            )
        }
        var belowPrice: List<Map<String, Any?>> = emptyList()
        var writeOffs: Map<String, Any?>? = null
        if (b.kind == "pharmacy") {
            belowPrice = jdbc.queryForList(
                """
                SELECT i.id, i.no, l.name, l.qty, l.unit_price, si.price AS item_price
                FROM invoices i JOIN shop_sale_lines l ON l.shop_uid = i.biz_id AND l.sale_id = i.ref_id
                JOIN shop_items si ON si.shop_uid = l.shop_uid AND si.id = l.item_id
                WHERE i.kind = 'pharmacy' AND i.biz_id = ? AND i.ref_type = 'book_sale' AND i.issued_at >= ? AND i.issued_at < ?
                  AND l.unit_price IS NOT NULL AND si.price IS NOT NULL AND l.unit_price < si.price ORDER BY i.no, l.idx
                """.trimIndent(),
                b.id, from, to,
            ).map {
                linkedMapOf(
                    "id" to it["id"], "no" to it["no"], "name" to it["name"], "qty" to it["qty"],
                    "price" to InvoiceMath.num(it["unit_price"] as BigDecimal), "itemPrice" to InvoiceMath.num(it["item_price"] as BigDecimal),
                )
            }
            val items = jdbc.queryForList(
                "SELECT name, -delta AS qty, loss, reason FROM shop_adjustments WHERE shop_uid = ? AND kind = 'writeoff' AND day = ? ORDER BY at",
                b.id, java.sql.Date.valueOf(day),
            ).map {
                linkedMapOf("name" to it["name"], "qty" to it["qty"], "loss" to InvoiceMath.num(InvoiceMath.m2(it["loss"] as BigDecimal)), "reason" to it["reason"])
            }
            writeOffs = linkedMapOf(
                "count" to items.size, "loss" to InvoiceMath.num(items.fold(BigDecimal.ZERO) { a, x -> a + BigDecimal(x["loss"].toString()) }), "items" to items,
            )
        }
        val issued = byIssuer.sumOf { it["receipts"] as Int }
        val total = byIssuer.fold(BigDecimal.ZERO) { a, x -> a + BigDecimal(x["total"].toString()) }
        return linkedMapOf(
            "day" to day.toString(), "kind" to b.kind, "bizId" to b.id, "receipts" to issued, "total" to InvoiceMath.num(total),
            "byIssuer" to byIssuer, "voids" to voids, "bigDiscounts" to bigDisc, "belowPrice" to belowPrice, "writeOffs" to writeOffs,
        )
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
