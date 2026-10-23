package com.lifolo.payments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.auth.AdminService
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.invoices.Biz
import com.lifolo.invoices.InvoiceMath
import com.lifolo.invoices.InvoiceService
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Lifolo Pay actions (backend/docs/PAYMENTS.md): business settings, the pay request lifecycle, the business's
 * lists / day summary / CSV, the patient's payments, and the admins' reports and flags.
 */
@Component
class PayActions(
    private val pay: PayService,
    private val store: PayMethodStore,
    private val gate: PayGate,
    private val invoices: InvoiceService,
    private val jdbc: JdbcTemplate,
    private val push: PushService,
    private val quotas: QuotaService,
    private val admins: AdminService,
    private val guard: PayMethodGuard,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "pay_methods_get" to ActionHandler(::methodsGet),
        "pay_methods_set" to ActionHandler(::methodsSet),
        "pay_request_create" to ActionHandler(::requestCreate),
        "pay_due_start" to ActionHandler(::dueStart),
        "pay_request_get" to ActionHandler(::requestGet),
        "pay_claim" to ActionHandler { ctx ->
            val b = ctx.body
            val r = pay.claim(ctx.lang, ctx.user, ctx.caller.id, b.string("id"), b.string("method"), b.string("trxId"), b.string("senderLast4") ?: Js.integer(b["senderLast4"])?.toString())
            mapOf("ok" to true, "request" to r.toMap())
        },
        "pay_confirm" to ActionHandler { ctx ->
            val (r, inv) = pay.confirm(ctx.lang, ctx.user, ctx.body.string("id"), ctx.body["checks"])
            linkedMapOf("ok" to true, "request" to r.toMap(), "invoice" to inv?.toMap())
        },
        "pay_reject" to ActionHandler { ctx ->
            val r = pay.reject(ctx.lang, ctx.user, ctx.body.string("id"), ctx.body.string("reason"), ctx.body.string("note"))
            mapOf("ok" to true, "request" to r.toMap())
        },
        "pay_refund" to ActionHandler { ctx ->
            val r = pay.refund(ctx.lang, ctx.user, ctx.body.string("id"), ctx.body.string("trxId"), ctx.body["amount"])
            mapOf("ok" to true, "request" to r.toMap())
        },
        "pay_methods_freeze" to ActionHandler(::freeze),
        "admin_pay_unfreeze" to ActionHandler(::adminUnfreeze),
        "pay_refund_ack" to ActionHandler { ctx -> mapOf("ok" to true, "request" to pay.refundAck(ctx.lang, ctx.user, ctx.body.string("id")).toMap()) },
        "pay_report" to ActionHandler(::report),
        "my_payments" to ActionHandler(::mine),
        "pay_list_biz" to ActionHandler(::listBiz),
        "pay_summary" to ActionHandler(::summary),
        "pay_export_csv" to ActionHandler(::exportCsv),
        "admin_pay_reports" to ActionHandler(::adminReports),
        "admin_pay_report_set" to ActionHandler(::adminReportSet),
        "admin_pay_flags" to ActionHandler(::adminFlags),
    )

    // ------------------------------------------------------------------ business settings

    /** the business of {kind, bizId} (bizId defaults to the caller, except an ambulance) for its owner or a doctor's assistant */
    private fun biz(ctx: ActionContext, ownerOnly: Boolean): Biz {
        val kind = ctx.body.string("kind")?.takeIf { it in Pay.KINDS } ?: throw Pay.notFound(ctx.lang)
        val bizId = ctx.body.string("bizId")?.takeIf { it.isNotEmpty() } ?: if (kind == "ambulance") null else ctx.user.uid
        if (ctx.user.patientSideOnly) throw Pay.notFound(ctx.lang)
        return if (ownerOnly) invoices.requireOwner(ctx.lang, ctx.user, kind, bizId) else invoices.requireIssuer(ctx.lang, ctx.user, kind, bizId)
    }

    /** the switch for a business's settings (P13: the business's own switch): pay_manual, or a doctor's online chamber fee */
    private fun requireBizGate(ctx: ActionContext, b: Biz) {
        if (b.kind == "chamber") gate.requireAny(ctx.lang, b.ownerUid) else gate.require(ctx.lang, b.ownerUid, PayGate.MANUAL)
    }

    private fun inPersonChambers(doctor: String): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT hospital_id, hospital_name, hospital_name_bn FROM provider_chambers WHERE provider_uid = ? AND mode <> 'online' ORDER BY idx", doctor,
    ).mapNotNull { r ->
        val name = r["hospital_name"] as String? ?: ""
        val key = (r["hospital_id"] as String?)?.ifEmpty { null } ?: name
        if (key.isEmpty()) null else linkedMapOf("chamberKey" to key, "name" to name, "nameBn" to (r["hospital_name_bn"] as String? ?: ""))
    }

    private fun methodsView(ctx: ActionContext, b: Biz, owner: Boolean): Map<String, Any?> {
        val now = pay.clock.now()
        val out = linkedMapOf<String, Any?>(
            "kind" to b.kind, "bizId" to b.id, "bizName" to b.name,
            "methods" to store.list(b.kind, b.id).map { it.toMap(now) },
        )
        val since = store.frozenSince(b.kind, b.id)
        out["frozen"] = since != null
        out["frozenSince"] = since
        if (b.kind == "chamber") {
            out["advance"] = store.advances(b.id)
            out["chambers"] = inPersonChambers(b.id)
        }
        if (owner) out["history"] = store.history(b.kind, b.id)
        return out
    }

    /** pay_methods_get {kind, bizId} → {kind, bizId, bizName, methods:[{method, number, type, active, activeNumber, pendingUntil}], advance?, chambers?, history?} */
    private fun methodsGet(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = false)
        requireBizGate(ctx, b)
        return methodsView(ctx, b, invoices.roleOf(ctx.user, b.kind, b.id, b.ownerUid) == "owner")
    }

    private fun parseMethods(ctx: ActionContext, v: JsonNode, owner: String?): List<MethodIn> {
        if (!v.isArray || v.size() > Pay.MAX_METHODS) throw Pay.bad(ctx.lang, "Check the payment numbers", "পেমেন্ট নম্বরগুলো দেখে নিন", "methods") // bn-ok
        val seen = HashSet<String>()
        return v.map { m ->
            if (m == null || !m.isObject) throw Pay.bad(ctx.lang, "Check the payment numbers", "পেমেন্ট নম্বরগুলো দেখে নিন", "methods") // bn-ok
            val method = Js.string(m.get("method"))?.takeIf { it in Pay.METHODS && seen.add(it) }
                ?: throw Pay.bad(ctx.lang, "Choose bKash, Nagad, Rocket or Upay (each once)", "বিকাশ, নগদ, রকেট বা উপায় বেছে নিন (প্রতিটা একবার)", "method") // bn-ok
            val number = Phones.normalizeBd(Js.string(m.get("number")))?.let { Phones.localBd(it) }
                ?: throw Pay.bad(ctx.lang, "Write a valid mobile number (01XXXXXXXXX)", "সঠিক মোবাইল নম্বর লিখুন (01XXXXXXXXX)", "number") // bn-ok
            guard.requireWorldNumber(ctx.lang, owner, number)
            val type = Js.string(m.get("type"))?.takeIf { it in Pay.TYPES }
                ?: throw Pay.bad(ctx.lang, "Choose personal or merchant", "পার্সোনাল না মার্চেন্ট বেছে নিন", "type") // bn-ok
            val active = m.get("active")?.takeIf { it.isBoolean }?.booleanValue() ?: true
            MethodIn(method, number, type, active)
        }
    }

    private data class AdvanceIn(val chamberKey: String, val amount: Int, val hours: Int)

    private fun parseAdvance(ctx: ActionContext, v: JsonNode, doctor: String): List<AdvanceIn> {
        val bad = { Pay.bad(ctx.lang, "Check the advance fee", "অগ্রিম ফি দেখে নিন", "advance") } // bn-ok
        if (!v.isArray || v.size() > 10) throw bad()
        val keys = inPersonChambers(doctor).map { it["chamberKey"] as String }.toSet()
        val seen = HashSet<String>()
        return v.map { a ->
            if (a == null || !a.isObject) throw bad()
            val key = Js.string(a.get("chamberKey"))?.takeIf { it in keys && seen.add(it) } ?: throw bad()
            val amount = Js.integer(a.get("amount"))?.takeIf { it in 1..100_000 }?.toInt() ?: throw bad()
            val hours = (if (a.get("refundableUntilHoursBefore") == null || a.get("refundableUntilHoursBefore").isNull) 0L else Js.integer(a.get("refundableUntilHoursBefore")))
                ?.takeIf { it in 0..720 }?.toInt() ?: throw bad()
            AdvanceIn(key, amount, hours)
        }
    }

    /**
     * pay_methods_set {kind, bizId, methods:[{method, number, type, active}], advance?:[{chamberKey, amount,
     * refundableUntilHoursBefore}]} — the owner only (the doctor for a chamber), with a login of the last 10 minutes
     * (401 `reauth_required`). `methods` replaces the list (P4: any number not active for 24 h waits); every change → an
     * audit row + a push and an email to the owner with "আমি করিনি". `advance` (doctors, pay_chamber_advance) replaces the advance fees.
     */
    private fun methodsSet(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = true)
        requireBizGate(ctx, b)
        val body = ctx.body
        val methodsIn = body["methods"]?.takeIf { !it.isNull }?.let { parseMethods(ctx, it, b.ownerUid) }
        if (methodsIn != null) {
            // P4: where the money goes changes only with a recent login, never while frozen
            guard.requireNotFrozen(ctx.lang, b.kind, b.id)
            guard.requireRecentLogin(ctx.lang, ctx.caller)
        }
        val advanceIn = body["advance"]?.takeIf { !it.isNull }?.let {
            if (b.kind != "chamber") throw Pay.bad(ctx.lang, "Only doctors take an advance", "শুধু ডাক্তাররা অগ্রিম ফি নিতে পারেন", "advance") // bn-ok
            gate.require(ctx.lang, b.ownerUid, PayGate.CHAMBER_ADVANCE)
            parseAdvance(ctx, it, b.id)
        }
        val now = pay.clock.now()
        var changes: List<MethodChange> = emptyList()
        tx.executeWithoutResult {
            if (methodsIn != null) {
                changes = store.replace(b.kind, b.id, b.ownerUid, methodsIn, ctx.caller.id, now)
                // the online chamber reads its bKash number from here: keep the chamber's copy (and its R6 log) in step
                val bk = changes.firstOrNull { it.method == "bkash" && (it.change == "number" || it.change == "added") }
                if (b.kind == "chamber" && bk?.newNo != null) {
                    val n = jdbc.update("UPDATE provider_chambers SET bkash_number = ? WHERE provider_uid = ? AND mode = 'online'", bk.newNo, b.id)
                    if (n > 0) {
                        jdbc.update(
                            "INSERT INTO bkash_number_log (provider_uid, old_last3, new_last3, by_uid, at) VALUES (?, ?, ?, ?, ?)",
                            b.id, bk.oldNo?.takeLast(3), bk.newNo.takeLast(3), ctx.caller.id, Sql.ts(now),
                        )
                    }
                }
            }
            if (advanceIn != null) {
                jdbc.update("DELETE FROM pay_chamber_advance WHERE doctor_uid = ?", b.id)
                for (a in advanceIn) {
                    jdbc.update(
                        "INSERT INTO pay_chamber_advance (doctor_uid, chamber_key, amount, refundable_hours, updated_at) VALUES (?, ?, ?, ?, ?)",
                        b.id, a.chamberKey, a.amount, a.hours, Sql.ts(now),
                    )
                }
            }
        }
        // P4: push AND email to the owner, each with "আমি করিনি"
        guard.notifyChanged(b.kind, b.id, b.ownerUid, changes, now)
        return linkedMapOf("ok" to true, "changes" to changes.size) + methodsView(ctx, b, owner = true)
    }

    // ------------------------------------------------------------------ requests

    /**
     * pay_methods_freeze {kind, bizId} — "আমি করিনি" (the owner, no recent login needed): every number of the business
     * stops at once; only an admin unfreezes (admin_pay_unfreeze). The admins see it in admin_pay_flags.
     */
    private fun freeze(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = true)
        val frozen = guard.freeze(b.kind, b.id, ctx.caller.id, "app", pay.clock.now())
        return linkedMapOf("ok" to true, "frozen" to true, "changed" to frozen)
    }

    /** admin_pay_unfreeze {kind, bizId} */
    private fun adminUnfreeze(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val kind = ctx.body.string("kind")?.takeIf { it in Pay.KINDS } ?: throw Pay.notFound(ctx.lang)
        val bizId = ctx.body.string("bizId")?.takeIf { Rules.isValidId(it) } ?: throw Pay.notFound(ctx.lang)
        if (!guard.unfreeze(kind, bizId, ctx.user.id, pay.clock.now())) throw Pay.notFound(ctx.lang)
        return linkedMapOf("ok" to true)
    }

    /** pay_request_create {refType, refId, purpose} — the business → {request, created}; the amount comes from the source (P6) */
    private fun requestCreate(ctx: ActionContext): Any {
        val b = ctx.body
        if (ctx.user.patientSideOnly) throw Pay.notFound(ctx.lang)
        val (r, created) = pay.create(ctx.lang, ctx.user, ctx.caller.id, b.string("refType"), b.string("refId"), b.string("purpose"), pay.clock.now())
        return linkedMapOf("ok" to true, "request" to r.toMap(), "created" to created)
    }

    /** pay_due_start {invoiceId} — the patient (or a caregiver) pays a receipt's due amount → {request, created} */
    private fun dueStart(ctx: ActionContext): Any {
        val id = ctx.body.string("invoiceId")
        val (r, created) = pay.create(ctx.lang, ctx.user, ctx.caller.id, "invoice", id, "invoice_due", pay.clock.now(), payerStart = true)
        return linkedMapOf("ok" to true, "request" to r.toMap(), "created" to created)
    }

    /**
     * pay_request_get {id} | {refType, refId, purpose?} — the payer (caregiver via asUid) or the business →
     * {request, side, events (business), invoiceId}
     */
    private fun requestGet(ctx: ActionContext): Any {
        val b = ctx.body
        val id = b.string("id")
        val r = if (!id.isNullOrEmpty()) {
            pay.load(ctx.lang, id)
        } else {
            val refType = b.string("refType")
            val refId = b.string("refId")
            if (Pay.isBlood(refType, b.string("purpose"))) throw Pay.blood(ctx.lang)
            if (refType == null || refType !in Pay.REF_TYPES || !Rules.isValidId(refId)) throw Pay.notFound(ctx.lang)
            val purpose = b.string("purpose")?.takeIf { it in Pay.PURPOSES }
            (if (refType == "appointment" && (purpose == null || purpose == "chamber_fee")) pay.ensureChamberFee(refId!!, pay.clock.now()) else null)
                ?.takeIf { purpose != null || pay.latestFor(refType, refId!!) == null || it.purpose == "chamber_fee" }
                ?: pay.latestFor(refType, refId!!, purpose) ?: throw Pay.notFound(ctx.lang)
        }
        val side = pay.sideOf(ctx.user, r) ?: throw Pay.notFound(ctx.lang)
        // P11: the refund policy and the safety line before paying; P8: the demo banner
        val out = linkedMapOf<String, Any?>("request" to r.toMap(), "side" to side, "policy" to PayPolicy.text(r, ctx.lang))
        if (r.isDemo) out["demoNote"] = Lang.pick(ctx.lang, "Demo — do not send real money", "ডেমো — আসল টাকা পাঠাবেন না") // bn-ok
        if (side != "payer") out["events"] = pay.events(r.id)
        return out
    }

    /** pay_report {id, reason, note?} — the payer → the admins (one open report per person per request) */
    private fun report(ctx: ActionContext): Any {
        val r = pay.load(ctx.lang, ctx.body.string("id"))
        pay.requirePayer(ctx.lang, ctx.user, r)
        val reason = ctx.body.string("reason")?.takeIf { it in Pay.REPORT_REASONS } ?: throw Pay.bad(ctx.lang, "Choose a reason", "কারণ বেছে নিন", "reason") // bn-ok
        val note = Rules.cleanText(ctx.body.string("note"), 300)
        if (reason == "other" && note == null) throw Pay.bad(ctx.lang, "Write what happened", "কী হয়েছে লিখুন", "reason") // bn-ok
        quotas.use(ctx.lang, ctx.user.id, "report")
        val id = "prp_" + Ids.sha256Hex("${r.id}|${ctx.user.id}").take(20)
        jdbc.update(
            """
            INSERT INTO pay_reports (id, request_id, reporter_uid, payer_uid, reason, note, disputes_reject, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?)
            ON CONFLICT (id) DO UPDATE SET reason = EXCLUDED.reason, note = EXCLUDED.note, disputes_reject = EXCLUDED.disputes_reject OR pay_reports.disputes_reject,
              status = 'open', outcome = NULL, created_at = EXCLUDED.created_at, resolved_at = NULL, resolved_by = NULL
            """.trimIndent(),
            // P3: a report on a claim the business rejected disputes that rejection (it stops counting against the payer)
            id, r.id, ctx.caller.id, r.payerUid, reason, note, r.rejectedAt != null, Sql.ts(pay.clock.now()),
        )
        pay.event(r.id, "report", ctx.caller.id, r.payerUid, r.status, r.status, null, reason, pay.clock.now())
        return linkedMapOf("ok" to true, "id" to id)
    }

    private fun cursorArgs(cursor: String?): Pair<Long, String>? {
        val m = Regex("^([0-9]{1,15})_([A-Za-z0-9_-]{1,64})$").matchEntire(cursor ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2]
    }

    /** my_payments {asUid?, cursor?} — the payer's requests, newest first, 30 a page (P13: existing ones whatever the switches) */
    private fun mine(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val c = cursorArgs(ctx.body.string("cursor"))
        val rows = if (c == null) {
            pay.query("payer_uid = ? ORDER BY created_at DESC, id DESC LIMIT ${Pay.PAGE + 1}", uid)
        } else {
            pay.query("payer_uid = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ${Pay.PAGE + 1}", uid, Sql.ts(c.first), c.second)
        }
        val page = rows.take(Pay.PAGE)
        val last = rows.take(Pay.PAGE).lastOrNull()
        return linkedMapOf(
            "items" to page.map { it.toMap() },
            "cursor" to if (rows.size > Pay.PAGE && last != null) "${last.createdAt}_${last.id}" else null,
        )
    }

    private fun periodStart(period: String, now: Long): Long {
        val today = DhakaTime.day(now)
        val start: LocalDate = when (period) {
            "today" -> today
            "7d" -> today.minusDays(6)
            "month" -> today.withDayOfMonth(1)
            "365d" -> today.minusDays(364)
            else -> today.minusDays(29)
        }
        return start.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
    }

    /** pay_list_biz {kind, bizId, status?, period?} → {items, sum:{count, amount, confirmed, pendingClaims}} (200 at most) */
    private fun listBiz(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = false)
        val now = pay.clock.now()
        val period = ctx.body.string("period")?.takeIf { it in listOf("today", "7d", "30d", "month", "365d") } ?: "30d"
        val status = ctx.body.string("status")?.takeIf { it in Pay.STATUSES }
        val where = StringBuilder("kind = ? AND biz_id = ? AND created_at >= ?")
        val args = arrayListOf<Any?>(b.kind, b.id, Sql.ts(periodStart(period, now)))
        if (status != null) {
            where.append(" AND status = ?")
            args.add(status)
        }
        val rows = pay.query("$where ORDER BY created_at DESC, id DESC LIMIT 200", *args.toTypedArray())
        val confirmed = rows.filter { it.status == "confirmed" }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
        return linkedMapOf(
            "items" to rows.map { it.toMap() },
            "sum" to linkedMapOf(
                "count" to rows.size, "amount" to InvoiceMath.num(rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }),
                "confirmed" to InvoiceMath.num(confirmed), "pendingClaims" to rows.count { it.status == "claimed" },
            ),
        )
    }

    /** pay_summary {kind, bizId, day?} → {day, collected, confirmedCount, refunded, openCount, pendingClaims} */
    private fun summary(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = false)
        val d = ctx.body.string("day")
        val day = if (d.isNullOrEmpty()) {
            DhakaTime.day(pay.clock.now())
        } else {
            runCatching { LocalDate.parse(d) }.getOrNull()?.takeIf { it.year in 2020..2100 } ?: throw Pay.bad(ctx.lang, "Check the date", "তারিখ দেখে নিন") // bn-ok
        }
        val from = Sql.ts(day.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val to = Sql.ts(day.plusDays(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli())
        val m = jdbc.queryForMap(
            """
            SELECT
              coalesce(sum(amount) FILTER (WHERE confirmed_at >= ? AND confirmed_at < ? AND status IN ('confirmed', 'refunded')), 0) AS collected,
              count(*) FILTER (WHERE confirmed_at >= ? AND confirmed_at < ? AND status IN ('confirmed', 'refunded')) AS confirmed_n,
              coalesce(sum(refund_amount) FILTER (WHERE refunded_at >= ? AND refunded_at < ?), 0) AS refunded,
              count(*) FILTER (WHERE status IN ('open', 'rejected')) AS open_n,
              count(*) FILTER (WHERE status = 'claimed') AS claimed_n
            FROM pay_requests WHERE kind = ? AND biz_id = ?
            """.trimIndent(),
            from, to, from, to, from, to, b.kind, b.id,
        )
        return linkedMapOf(
            "day" to day.toString(), "kind" to b.kind, "bizId" to b.id,
            "collected" to InvoiceMath.num(m["collected"] as BigDecimal), "confirmedCount" to (m["confirmed_n"] as Number).toInt(),
            "refunded" to InvoiceMath.num(m["refunded"] as BigDecimal), "openCount" to (m["open_n"] as Number).toInt(),
            "pendingClaims" to (m["claimed_n"] as Number).toInt(),
        )
    }

    /** pay_export_csv {kind, bizId, month} — the owner only → {filename, mime, count, csv} (UTF-8 BOM, no phone numbers) */
    private fun exportCsv(ctx: ActionContext): Any {
        val b = biz(ctx, ownerOnly = true)
        val month = ctx.body.string("month")
        val ym = if (month.isNullOrEmpty()) {
            YearMonth.from(DhakaTime.day(pay.clock.now()))
        } else {
            if (!Regex("^20[0-9]{2}-(0[1-9]|1[0-2])$").matches(month)) throw Pay.bad(ctx.lang, "Check the month", "মাস দেখে নিন") // bn-ok
            YearMonth.parse(month)
        }
        val from = ym.atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val rows = pay.query("kind = ? AND biz_id = ? AND created_at >= ? AND created_at < ? ORDER BY created_at, id LIMIT 20000", b.kind, b.id, Sql.ts(from), Sql.ts(to))
        val invNo = HashMap<String, String>()
        rows.mapNotNull { it.invoiceId }.distinct().chunked(500).forEach { ids ->
            jdbc.query("SELECT id, no FROM invoices WHERE id IN (${ids.joinToString(",") { "?" }})", { rs, _ -> invNo[rs.getString(1)] = rs.getString(2) }, *ids.toTypedArray())
        }
        fun cell(v: Any?): String {
            val t = v?.toString() ?: ""
            val safe = if (t.isNotEmpty() && t[0] in "=+-@\t") "'$t" else t
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
        fun time(ms: Long?) = ms?.let { java.time.Instant.ofEpochMilli(it).atOffset(DhakaTime.OFFSET).toLocalDateTime().toString().take(16).replace('T', ' ') }
        val sb = StringBuilder("﻿")
        sb.append("id,created,purpose,amount,status,method,trx_id,sender_last4,confirmed,refund_amount,refund_trx,receipt_no\r\n")
        for (r in rows) {
            sb.append(
                listOf(
                    r.id, time(r.createdAt), r.purpose, InvoiceMath.num(r.amount), r.status, r.claimMethod, r.claimTrx, r.claimLast4,
                    time(r.confirmedAt), r.refundAmount?.let { InvoiceMath.num(it) }, r.refundTrx, r.invoiceId?.let { invNo[it] },
                ).joinToString(",") { cell(it) },
            )
            sb.append("\r\n")
        }
        return linkedMapOf("filename" to "lifolo-pay-${b.kind}-$ym.csv", "mime" to "text/csv", "count" to rows.size, "csv" to sb.toString())
    }

    // ------------------------------------------------------------------ admins

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw Pay.err(ctx.lang, 403, "Admins only", "শুধু অ্যাডমিন")
    }

    /** admin_pay_reports {status?} → {items:[{id, reporterUid, reason, note, status, createdAt, resolvedAt, request, events}]} */
    private fun adminReports(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = ctx.body.string("status")?.takeIf { it in setOf("open", "resolved", "dismissed") } ?: "open"
        val rows = jdbc.queryForList("SELECT * FROM pay_reports WHERE status = ? ORDER BY created_at DESC, id LIMIT 200", status)
        return linkedMapOf(
            "items" to rows.map { r ->
                val reqId = r["request_id"] as String
                linkedMapOf<String, Any?>(
                    "id" to r["id"], "reporterUid" to r["reporter_uid"], "reason" to r["reason"], "note" to r["note"], "status" to r["status"],
                    "outcome" to r["outcome"], "disputesReject" to r["disputes_reject"],
                    // P11: an answer within 72 hours
                    "slaBreached" to (r["status"] == "open" && ((r["created_at"] as? java.sql.Timestamp)?.time ?: 0L) < pay.clock.now() - Pay.REPORT_SLA_MS),
                    "createdAt" to (r["created_at"] as? java.sql.Timestamp)?.time, "resolvedAt" to (r["resolved_at"] as? java.sql.Timestamp)?.time,
                    "request" to pay.get(reqId)?.toMap(), "events" to pay.events(reqId),
                )
            },
        )
    }

    /** admin_pay_report_set {id, status: open|resolved|dismissed} */
    private fun adminReportSet(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        val status = ctx.body.string("status")
        if (id.isNullOrEmpty() || status !in setOf("open", "resolved", "dismissed")) throw Pay.bad(ctx.lang, "Not found", "পাওয়া যায়নি")
        // P3: who was at fault — `payer_fault` makes a disputed rejection count again
        val outcome = ctx.body.string("outcome")?.takeIf { it in setOf("business_fault", "payer_fault", "no_fault") }
        val now = pay.clock.now()
        val n = jdbc.update(
            "UPDATE pay_reports SET status = ?, outcome = ?, resolved_at = ?, resolved_by = ? WHERE id = ?",
            status, if (status == "open") null else outcome, if (status == "open") null else Sql.ts(now), if (status == "open") null else ctx.user.id, id,
        )
        if (n == 0) throw Pay.notFound(ctx.lang)
        return linkedMapOf("ok" to true)
    }

    /**
     * admin_pay_flags → {users: people with 3+ rejected claims in 30 days, businesses: claims unconfirmed for 72 h+,
     * claims: those requests}
     */
    private fun adminFlags(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val now = pay.clock.now()
        val users = jdbc.queryForList(
            """
            SELECT e.payer_uid AS uid, count(*) AS n, max(e.at) AS last_at, p.name
            FROM pay_events e LEFT JOIN user_profiles p ON p.uid = e.payer_uid
            WHERE e.action = 'reject' AND e.at > ? AND e.payer_uid IS NOT NULL
            GROUP BY e.payer_uid, p.name HAVING count(*) >= ? ORDER BY count(*) DESC, max(e.at) DESC LIMIT 200
            """.trimIndent(),
            Sql.ts(now - Pay.REJECT_WINDOW_MS), Pay.REJECTS_FOR_LIMIT,
        ).map { r ->
            linkedMapOf<String, Any?>(
                "uid" to r["uid"], "name" to r["name"], "rejected30d" to (r["n"] as Number).toInt(), "lastRejectAt" to (r["last_at"] as? java.sql.Timestamp)?.time,
                "openClaims" to (jdbc.queryForObject("SELECT count(*) FROM pay_requests WHERE payer_uid = ? AND status = 'claimed'", Int::class.java, r["uid"]) ?: 0),
            )
        }
        val old = Sql.ts(now - Pay.FLAG_AFTER_MS)
        val businesses = jdbc.queryForList(
            """
            SELECT kind, biz_id, max(biz_name) AS biz_name, count(*) AS n, min(claimed_at) AS oldest
            FROM pay_requests WHERE status = 'claimed' AND claimed_at <= ?
            GROUP BY kind, biz_id ORDER BY count(*) DESC, min(claimed_at) LIMIT 200
            """.trimIndent(),
            old,
        ).map { r ->
            linkedMapOf<String, Any?>(
                "kind" to r["kind"], "bizId" to r["biz_id"], "bizName" to r["biz_name"], "pendingClaims" to (r["n"] as Number).toInt(),
                "oldestClaimAt" to (r["oldest"] as? java.sql.Timestamp)?.time,
            )
        }
        val claims = pay.query("status = 'claimed' AND claimed_at <= ? ORDER BY claimed_at LIMIT 200", old).map { it.toMap() }
        // P4: frozen businesses ("আমি করিনি") and numbers changed in the last 7 days
        val frozen = jdbc.queryForList("SELECT kind, biz_id, frozen_at, frozen_by, via FROM pay_freezes ORDER BY frozen_at DESC LIMIT 200").map { r ->
            linkedMapOf<String, Any?>("kind" to r["kind"], "bizId" to r["biz_id"], "at" to (r["frozen_at"] as? java.sql.Timestamp)?.time, "by" to r["frozen_by"], "via" to r["via"])
        }
        val changed = jdbc.queryForList(
            """
            SELECT kind, biz_id, count(*) AS n, max(at) AS last_at FROM pay_method_log
            WHERE change IN ('added', 'number', 'on') AND at > ? GROUP BY kind, biz_id ORDER BY max(at) DESC LIMIT 200
            """.trimIndent(),
            Sql.ts(now - 7 * Pay.DAY),
        ).map { r -> linkedMapOf<String, Any?>("kind" to r["kind"], "bizId" to r["biz_id"], "changes" to (r["n"] as Number).toInt(), "lastAt" to (r["last_at"] as? java.sql.Timestamp)?.time) }
        // P11: refunds owed over 7 days (those businesses cannot ask for new payments), reports past the 72 h answer time
        val refundsOverdue = pay.query("status = 'confirmed' AND refund_due_at IS NOT NULL AND refund_due_at < ? ORDER BY refund_due_at LIMIT 200", Sql.ts(now - Pay.REFUND_DUE_PAUSE_MS)).map { it.toMap() }
        val reportsLate = jdbc.queryForObject("SELECT count(*) FROM pay_reports WHERE status = 'open' AND created_at < ?", Int::class.java, Sql.ts(now - Pay.REPORT_SLA_MS)) ?: 0
        // P3: businesses with 2+ disputed rejections in 30 days
        val disputed = jdbc.queryForList(
            """
            SELECT q.kind, q.biz_id, max(q.biz_name) AS biz_name, count(DISTINCT q.id) AS n FROM pay_reports r JOIN pay_requests q ON q.id = r.request_id
            WHERE r.disputes_reject AND r.created_at > ? GROUP BY q.kind, q.biz_id HAVING count(DISTINCT q.id) >= 2 LIMIT 200
            """.trimIndent(),
            Sql.ts(now - Pay.REJECT_WINDOW_MS),
        ).map { r -> linkedMapOf<String, Any?>("kind" to r["kind"], "bizId" to r["biz_id"], "bizName" to r["biz_name"], "disputedRejects" to (r["n"] as Number).toInt()) }
        return linkedMapOf(
            "users" to users, "businesses" to businesses, "claims" to claims, "frozen" to frozen, "numbersChanged" to changed,
            "refundsOverdue" to refundsOverdue, "reportsLate" to reportsLate, "disputedRejects" to disputed,
        )
    }
}
