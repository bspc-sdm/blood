package com.lifolo.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.appointments.AppointmentActions
import com.lifolo.auth.AuthUser
import com.lifolo.books.BooksMath
import com.lifolo.books.BooksService
import com.lifolo.books.BooksStore
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.RateLimiter
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.invoices.DeviceStamp
import com.lifolo.invoices.Invoice
import com.lifolo.invoices.InvoiceCreators
import com.lifolo.invoices.InvoiceMath
import com.lifolo.invoices.InvoiceService
import com.lifolo.lab.LabActions
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * offline_bootstrap / offline_sync (backend/docs/INVOICES.md "Offline sync"). The desktop records sales, bills, fees,
 * payments, voids, purchases, stock adjustments, check-ins and walk-ins while offline and sends them later as ops.
 * Each op runs in its own transaction through the same service code as the online action (InvoiceCreators /
 * InvoiceService for receipts, BooksService for purchases and adjustments, appointment_update for check-ins); its
 * result is stored under (device, opId) and returned again for the same opId — a sync can be repeated safely.
 */
@Service
class OfflineSync(
    private val jdbc: JdbcTemplate,
    private val devices: DesktopDevices,
    private val clock: DesktopClock,
    private val limiter: RateLimiter,
    private val mapper: ObjectMapper,
    private val books: BooksService,
    private val booksStore: BooksStore,
    private val labs: LabActions,
    private val appointments: AppointmentActions,
    private val chamber: ChamberDesk,
    private val creators: InvoiceCreators,
    private val invoices: InvoiceService,
    private val feature: DesktopFeature,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** one op = one transaction of its own (the batch is not all-or-nothing) */
    private val tx = TransactionTemplate(txm).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    companion object {
        /** what each kind of device may send */
        val TYPES: Map<String, Set<String>> = mapOf(
            "pharmacy" to setOf("sale", "purchase", "adjust", "pay", "void"),
            "lab" to setOf("lab_bill", "pay", "void"),
            "chamber" to setOf("fee", "pay", "void", "checkin", "walkin"),
        )
        val ALL_TYPES = setOf("sale", "purchase", "adjust", "lab_bill", "fee", "pay", "void", "checkin", "walkin")

        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** most important first: the one shown as `warn` */
        private val WARN_ORDER = listOf("stock_short", "renumbered", "clock")
    }

    // ------------------------------------------------------------------ bootstrap / delta

    /** the business's data for the desk: full ([since] null) or what changed after [since] */
    private fun snapshot(ctx: ActionContext, d: Device, since: Long?): Map<String, Any?> {
        val now = clock.now()
        val out = linkedMapOf<String, Any?>("kind" to d.kind, "asOf" to now, "full" to (since == null))
        when (d.kind) {
            "pharmacy" -> {
                @Suppress("UNCHECKED_CAST")
                val all = (books.items(subCtx(ctx, ctx.user, "books_items", mapper.createObjectNode())) as Map<String, Any?>)["items"] as List<Map<String, Any?>>
                val review = openFlags(d.bizId)
                val flagged = review.map { it["itemId"] }.toSet()
                val items = all.filter { since == null || ((it["updatedAt"] as? Number)?.toLong() ?: 0L) > since }
                    .map { x -> LinkedHashMap(x).apply { put("stock", x["qty"]); put("review", x["id"] in flagged) } }
                out["items"] = items
                out["ids"] = all.map { it["id"] }
                out["units"] = BooksMath.UNITS
                out["review"] = review
            }
            "lab" -> {
                @Suppress("UNCHECKED_CAST")
                val prices = labs.labPricesGet(subCtx(ctx, ctx.user, "lab_prices_get", mapper.createObjectNode())) as Map<String, Any?>
                val changed = since == null || ((prices["updatedAt"] as? Number)?.toLong() ?: 0L) > since
                out["prices"] = if (changed) prices else null
                // the bill's extra fees: home collection (when the lab collects from home)
                out["fees"] = listOfNotNull((prices["homeFee"] as? Number)?.let { linkedMapOf("label" to "home", "amount" to it) })
            }
            "chamber" -> {
                // small (8 days of one doctor) — always whole
                out.putAll(chamber.window(d.bizId, now))
                out["visitTypes"] = listOf("new", "followup", "report")
            }
        }
        out["nextSeq"] = nextSeq(d)
        return out
    }

    /** the device's next own invoice number (after the last one the server has) */
    private fun nextSeq(d: Device): Int =
        (jdbc.queryForList("SELECT last_seq FROM invoice_series WHERE kind = ? AND biz_id = ? AND series = ?", Int::class.java, d.kind, d.bizId, d.series)
            .firstOrNull() ?: 0) + 1

    /** stock review flags still open: no stock count of that item since the flag */
    private fun openFlags(shopUid: String): List<Map<String, Any?>> = jdbc.queryForList(
        """
        SELECT f.* FROM shop_stock_flags f WHERE f.shop_uid = ? AND f.resolved_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM shop_adjustments a WHERE a.shop_uid = f.shop_uid AND a.item_id = f.item_id AND a.kind = 'count'
                          AND a.created_at > f.created_at)
        ORDER BY f.created_at DESC, f.id LIMIT 500
        """.trimIndent(),
        shopUid,
    ).map {
        linkedMapOf(
            "id" to it["id"], "itemId" to it["item_id"], "name" to it["item_name"], "wanted" to num(it["wanted"]), "had" to num(it["had"]),
            "short" to num(it["short"]), "deviceId" to it["device_id"], "at" to (it["at"] as java.sql.Timestamp).time,
        )
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()

    /** offline_bootstrap {deviceId} (signed) → snapshot + asOf */
    fun bootstrap(ctx: ActionContext): Any {
        val (d, role) = devices.usable(ctx, ctx.body.string("deviceId"))
        // switch `desktop`: no new snapshots while off (sync of a registered desk goes on, so no sale is lost)
        feature.require(ctx, d.bizId)
        if (!limiter.tryAcquire("desk-boot:${d.id}", DesktopRules.BOOTSTRAP_PER_HOUR, Duration.ofHours(1))) throw devices.tooMany(ctx)
        return linkedMapOf<String, Any?>(
            "deviceId" to d.id, "series" to d.series, "kind" to d.kind, "bizId" to d.bizId, "name" to d.name, "role" to role,
        ).apply { putAll(snapshot(ctx, d, null)) }
    }

    /** offline_review_done {id}: the pharmacy owner has looked at a stock_short flag */
    fun reviewDone(ctx: ActionContext): Any {
        val uid = ctx.user.uid
        if (ctx.user.guest || uid == null || ctx.user.patientSideOnly) throw ctx.err(401, "Please log in", "লগইন করুন")
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.notFound()
        val n = jdbc.update("UPDATE shop_stock_flags SET resolved_at = ? WHERE id = ? AND shop_uid = ? AND resolved_at IS NULL", Sql.ts(clock.now()), id, uid)
        if (n == 0 && jdbc.queryForList("SELECT 1 FROM shop_stock_flags WHERE id = ? AND shop_uid = ?", id, uid).isEmpty()) throw ctx.notFound()
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ sync

    private class Op(val index: Int, val opId: String, val type: String, val at: Long?, val data: ObjectNode?, val localId: String?, val bad: String?)

    /** a permanent error is stored (the same opId gets it again); a retryable one is not (the next sync tries again) */
    private class OpFailure(
        val code: String,
        val en: String,
        val bn: String,
        val retry: Boolean = false,
        val suspicious: Boolean = false,
    ) : RuntimeException(code)

    private fun badRef() = OpFailure("bad_ref", "This refers to something that is not this business's", "এটা এই প্রতিষ্ঠানের কিছু নয়") // bn-ok

    /**
     * offline_sync {deviceId, ops, asOf?} (signed) → {results, delta, asOf}. Not behind the `desktop` switch: a desk
     * registered while it was on keeps sending what it recorded (real sales) after the switch goes off.
     */
    fun sync(ctx: ActionContext): Any {
        val (d, _) = devices.usable(ctx, ctx.body.string("deviceId"))
        if (!limiter.tryAcquire("desk-sync:${d.id}", DesktopRules.SYNC_PER_MINUTE, Duration.ofMinutes(1))) throw devices.tooMany(ctx)
        val raw = ctx.body["ops"]
        if (raw != null && !raw.isNull && !raw.isArray) throw ctx.err(400, "Invalid request", "অনুরোধ ঠিক নেই") // bn-ok
        val list = raw?.takeIf { it.isArray }?.toList() ?: emptyList()
        if (list.size > DesktopRules.MAX_OPS) {
            throw ctx.err(413, "Up to ${DesktopRules.MAX_OPS} changes at a time", "একবারে সর্বোচ্চ ${DesktopRules.MAX_OPS}টা পরিবর্তন", "too_many_ops") // bn-ok
        }
        val since = (ctx.body.int("asOf") ?: ctx.body.int("since"))?.takeIf { it in 1..Sql.MAX_MS } ?: d.lastSyncAt
        val ops = list.mapIndexed { i, n -> parse(i, n) }
        val results = arrayOfNulls<Map<String, Any?>>(ops.size)
        val byId = HashMap<String, Map<String, Any?>>()
        // the device's order is kept by time: a payment recorded after its sale is applied after it, whatever the list order
        val order = ops.sortedWith(compareBy<Op> { it.at ?: Long.MAX_VALUE }.thenBy { it.index })
        val retry = ArrayList<Op>()
        for (op in order) {
            val r = byId[op.opId] ?: run(ctx, d, op)
            byId[op.opId] = r
            if (r["retry"] == true && r["error"] == "invoice_not_found") retry.add(op)
        }
        // an op that refers to a receipt made by a later op of the same batch: once more at the end
        for (op in retry) byId[op.opId] = run(ctx, d, op)
        for (op in ops) results[op.index] = byId[op.opId]
        devices.touch(d, ctx.user.id, ops.size)
        if (ops.isNotEmpty()) cleanOld(d.id)
        return linkedMapOf("results" to results.toList(), "delta" to snapshot(ctx, d, since), "asOf" to clock.now())
    }

    private fun parse(i: Int, n: JsonNode): Op {
        val opId = Js.string(n.get("opId")) ?: ""
        val type = Js.string(n.get("type")) ?: ""
        val at = Js.integer(n.get("at"))
        val data = n.get("data")
        val localId = Js.string(data?.get("localId"))
        val bad = when {
            !DesktopRules.OP_ID.matches(opId) -> "bad_op"
            type !in ALL_TYPES -> "bad_type"
            at == null || at <= 0 || at > Sql.MAX_MS -> "bad_at"
            data != null && !data.isNull && !data.isObject -> "bad_op"
            data != null && data.toString().length > DesktopRules.MAX_OP_BYTES -> "too_big"
            localId != null && !LOCAL_ID.matches(localId) -> "bad_op"
            else -> null
        }
        return Op(
            i, if (DesktopRules.OP_ID.matches(opId)) opId.lowercase() else opId, type, at, data as? ObjectNode,
            localId?.takeIf { LOCAL_ID.matches(it) }, bad,
        )
    }

    private fun stored(deviceId: String, opId: String): Map<String, Any?>? =
        jdbc.queryForList("SELECT result FROM offline_ops WHERE device_id = ? AND op_id = ?", String::class.java, deviceId, opId)
            .firstOrNull()?.let { readMap(it) }

    @Suppress("UNCHECKED_CAST")
    private fun readMap(json: String): Map<String, Any?> = mapper.readValue(json, LinkedHashMap::class.java) as Map<String, Any?>

    private fun failure(ctx: ActionContext, op: Op, e: OpFailure): Map<String, Any?> =
        linkedMapOf<String, Any?>("opId" to op.opId, "ok" to false, "error" to e.code, "message" to ctx.tr(e.en, e.bn)).apply {
            if (op.localId != null) put("localId", op.localId)
            if (e.retry) put("retry", true)
            if (e.suspicious) put("suspicious", true)
        }

    private fun store(d: Device, op: Op, ok: Boolean, result: Map<String, Any?>, suspicious: Boolean = false): Int = jdbc.update(
        """
        INSERT INTO offline_ops (device_id, op_id, type, at, ok, result, local_id, suspicious, received_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        ON CONFLICT (device_id, op_id) DO NOTHING
        """.trimIndent(),
        d.id, op.opId, op.type.take(20), op.at?.takeIf { it in 1..Sql.MAX_MS }?.let { Sql.ts(it) }, ok, mapper.writeValueAsString(result),
        op.localId, suspicious, Sql.ts(clock.now()),
    )

    private fun run(ctx: ActionContext, d: Device, op: Op): Map<String, Any?> {
        // a broken op (no valid opId) cannot be stored: answered, never applied
        if (!DesktopRules.OP_ID.matches(op.opId)) {
            return failure(ctx, op, OpFailure("bad_op", "This change is damaged", "এই পরিবর্তনটি ঠিক নেই")) // bn-ok
        }
        stored(d.id, op.opId)?.let { return it }
        return try {
            tx.execute {
                // the claim: a second sync with the same opId waits here until this one ends, then reads its result
                val claimed = store(d, op, false, mapOf("opId" to op.opId, "ok" to false, "error" to "running"))
                if (claimed == 0) return@execute stored(d.id, op.opId)!!
                val res = linkedMapOf<String, Any?>("opId" to op.opId, "ok" to true)
                if (op.localId != null) res["localId"] = op.localId
                val warns = LinkedHashSet<String>()
                res.putAll(applyOp(ctx, d, op, warns))
                if (warns.isNotEmpty()) {
                    res["warn"] = WARN_ORDER.firstOrNull { it in warns } ?: warns.first()
                    res["warns"] = warns.toList()
                }
                jdbc.update(
                    "UPDATE offline_ops SET ok = TRUE, result = ?::jsonb, ref_id = ?, device_no = ? WHERE device_id = ? AND op_id = ?",
                    mapper.writeValueAsString(res), res["id"] as? String, res["deviceNo"] as? String, d.id, op.opId,
                )
                res
            }!!
        } catch (e: OpFailure) {
            val f = failure(ctx, op, e)
            if (e.retry) f else keep(d, op, f, e.suspicious)
        } catch (e: ApiException) {
            val retry = e.status == 429 || e.status >= 500
            val f = linkedMapOf<String, Any?>("opId" to op.opId, "ok" to false, "error" to (e.code ?: codeOf(e.status)), "message" to e.message, "status" to e.status)
            if (op.localId != null) f["localId"] = op.localId
            if (retry) f.apply { put("retry", true) } else keep(d, op, f)
        } catch (e: Exception) {
            log.error("offline op {} {} failed", op.type, op.opId, e)
            failure(ctx, op, OpFailure("server", "Server error, please try again", "সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন", retry = true))
        }
    }

    /** stores a permanent failure; when another sync stored a result first, that one is the answer */
    private fun keep(d: Device, op: Op, f: Map<String, Any?>, suspicious: Boolean = false): Map<String, Any?> {
        if (store(d, op, false, f, suspicious) == 1) return f
        return stored(d.id, op.opId) ?: f
    }

    private fun codeOf(status: Int) = when (status) {
        400, 413 -> "invalid"
        401, 403 -> "forbidden"
        404 -> "not_found"
        409 -> "conflict"
        423 -> "locked"
        else -> "failed"
    }

    private fun cleanOld(deviceId: String) {
        jdbc.update("DELETE FROM offline_ops WHERE device_id = ? AND received_at < ?", deviceId, Sql.ts(clock.now() - DesktopRules.KEEP_OPS_MS))
    }

    // ------------------------------------------------------------------ one op

    private fun subCtx(ctx: ActionContext, user: AuthUser, action: String, data: ObjectNode): ActionContext {
        val body = data.deepCopy()
        body.put("action", action)
        ctx.lang?.let { body.put("lang", it) }
        return ActionContext(ctx.request, ctx.response, Body(body), user, ctx.caller, action)
    }

    private fun applyOp(ctx: ActionContext, d: Device, op: Op, warns: MutableSet<String>): Map<String, Any?> {
        when (op.bad) {
            null -> {}
            "bad_type" -> throw OpFailure("bad_type", "Unknown change type. Update the app.", "অজানা পরিবর্তন। অ্যাপ আপডেট করুন।") // bn-ok
            "bad_at" -> throw OpFailure("bad_at", "Check the computer's date and time", "কম্পিউটারের তারিখ ও সময় ঠিক করুন", suspicious = true) // bn-ok
            "too_big" -> throw OpFailure("too_big", "This change is too large", "এই পরিবর্তনটি অনেক বড়") // bn-ok
            else -> throw OpFailure("bad_op", "This change is damaged", "এই পরিবর্তনটি ঠিক নেই") // bn-ok
        }
        if (op.type !in TYPES.getValue(d.kind)) {
            throw OpFailure("wrong_kind", "This computer can't record this", "এই কম্পিউটার থেকে এটা করা যায় না") // bn-ok
        }
        val now = clock.now()
        val at0 = op.at!!
        // I5: the desk works offline for at most 7 days; older or future-dated ops are refused and shown to the owner
        if (at0 < now - DesktopRules.MAX_AGE_MS) {
            throw OpFailure("too_old", "Older than 8 days — enter it on the website", "৮ দিনের বেশি পুরোনো — ওয়েবসাইটে লিখুন", suspicious = true) // bn-ok
        }
        if (at0 > now + DesktopRules.MAX_AHEAD_MS) {
            throw OpFailure("bad_at", "Check the computer's date and time", "কম্পিউটারের তারিখ ও সময় ঠিক করুন", suspicious = true) // bn-ok
        }
        val at = minOf(at0, now)
        // recorded before this desk's previous sync but sent only now: kept, but the owner sees it
        if (d.lastSyncAt != null && at < d.lastSyncAt - DesktopRules.MAX_AHEAD_MS) warns.add("clock")
        val data = op.data ?: mapper.createObjectNode()
        return when (op.type) {
            "sale" -> sale(ctx, d, op, data, at, warns)
            "lab_bill" -> {
                refOf(data, "labOrderId")?.let { if (jdbc.queryForList("SELECT 1 FROM lab_orders WHERE id = ? AND lab_uid = ?", it, d.bizId).isEmpty()) throw badRef() }
                invoiceOut(creators.labBill(ctx.lang, ctx.user, Body(data.deepCopy()), numbered(d, data, at, warns)))
            }
            "fee" -> {
                refOf(data, "appointmentId")?.let { if (jdbc.queryForList("SELECT 1 FROM appointments WHERE id = ? AND provider_uid = ?", it, d.bizId).isEmpty()) throw badRef() }
                val body = data.deepCopy().apply { put("doctorUid", d.bizId) }
                invoiceOut(creators.chamberFee(ctx.lang, ctx.user, Body(body), numbered(d, data, at, warns)))
            }
            "pay", "void" -> payOrVoid(ctx, d, op.type, data, at)
            "purchase" -> purchase(ctx, d, data, at, now)
            "adjust" -> adjust(ctx, d, data)
            "checkin" -> checkin(ctx, d, data)
            "walkin" -> chamber.walkIn(ctx, d.bizId, data, at, d.id, now)
            else -> throw OpFailure("bad_type", "Unknown change type. Update the app.", "অজানা পরিবর্তন। অ্যাপ আপডেট করুন।") // bn-ok
        }
    }

    /** a reference id in [data]: null when not given; a malformed one is a bad reference */
    private fun refOf(data: ObjectNode, key: String): String? {
        val v = data.get(key) ?: return null
        if (v.isNull || (v.isTextual && v.textValue().isEmpty())) return null
        val s = Js.string(v)
        if (!Rules.isValidId(s)) throw badRef()
        return s
    }

    /**
     * The receipt's number and time. `no` must be in this device's own series (`series_mismatch` otherwise — the server
     * owns the series); a number the server already has gets the next free one of the series instead (warn
     * "renumbered", the desk's number kept as `deviceNo` — findByNo still finds it). `verifyCode` (printed in the
     * offline QR) is kept when unique (else InvoiceService: 409 `verify_collision`).
     */
    private fun numbered(d: Device, data: ObjectNode, at: Long, warns: MutableSet<String>): DeviceStamp {
        val asked = Js.string(data.get("no")) ?: ""
        val m = Regex("^(W|D[0-9]{1,3})-([0-9]{1,9})$").matchEntire(asked)
            ?: throw OpFailure("bad_no", "Check the receipt number", "রসিদ নম্বর দেখে নিন") // bn-ok
        if (m.groupValues[1] != d.series) {
            throw OpFailure("series_mismatch", "The receipt number is not this computer's", "রসিদ নম্বর এই কম্পিউটারের নয়") // bn-ok
        }
        if (!DesktopRules.DESKTOP_NO.matches(asked) || m.groupValues[2].toInt() == 0) {
            throw OpFailure("bad_no", "Check the receipt number", "রসিদ নম্বর দেখে নিন") // bn-ok
        }
        // the receipt's own time (data.issuedAt) when it agrees with the op's; else the op's time
        val issued = Js.integer(data.get("issuedAt"))
        val agrees = issued != null && Math.abs(issued - at) <= DesktopRules.MAX_AHEAD_MS
        if (issued != null && !agrees) warns.add("clock")
        val time = if (agrees) minOf(issued!!, clock.now()) else at
        val verify = Js.string(data.get("verifyCode"))?.takeIf { it.isNotEmpty() }
        // one number at a time per series: the free-number check and the issue stay consistent
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext(?))) x", Int::class.java, "desk-no:${d.kind}:${d.bizId}:${d.series}",
        )
        if (!numberTaken(d, asked)) return DeviceStamp(d.id, asked, time, verify)
        val last = jdbc.queryForObject(
            """
            SELECT greatest(
              coalesce((SELECT last_seq FROM invoice_series WHERE kind = ? AND biz_id = ? AND series = ?), 0),
              coalesce((SELECT max(seq) FROM invoices WHERE kind = ? AND biz_id = ? AND series = ?), 0))
            """.trimIndent(),
            Int::class.java, d.kind, d.bizId, d.series, d.kind, d.bizId, d.series,
        ) ?: 0
        var seq = last + 1
        while (numberTaken(d, InvoiceMath.no(d.series, seq))) seq++
        warns.add("renumbered")
        return DeviceStamp(d.id, InvoiceMath.no(d.series, seq), time, verify, asked)
    }

    private fun numberTaken(d: Device, no: String): Boolean =
        jdbc.queryForList("SELECT 1 FROM invoices WHERE kind = ? AND biz_id = ? AND no = ? LIMIT 1", d.kind, d.bizId, no).isNotEmpty()

    private fun invoiceOut(r: Map<String, Any?>): LinkedHashMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val inv = r["invoice"] as? Map<String, Any?>
        return linkedMapOf<String, Any?>("id" to inv?.get("id"), "no" to inv?.get("no")).apply {
            inv?.get("deviceNo")?.let { put("deviceNo", it) }
            if (inv != null) put("invoice", inv)
        }
    }

    /** an item id of a sale/purchase line: an existing product of this shop, or the id of a new product's name */
    private fun itemIdOf(l: JsonNode, known: Map<String, Any>): String? {
        val name = BooksMath.str(Js.string(l.get("name")), 100)
        val given = l.get("itemId")?.takeIf { !it.isNull && !(it.isTextual && it.textValue().isEmpty()) }
        if (given == null) return if (name.isNotEmpty()) BooksMath.idOf(name) else null
        val id = Js.string(given)
        if (!Rules.isValidId(id)) throw badRef()
        if (id !in known && (name.isEmpty() || id != BooksMath.idOf(name))) throw badRef()
        return id
    }

    private fun shopItems(shop: String, lines: List<JsonNode>): Map<String, Any> {
        val ids = lines.mapNotNull { Js.string(it.get("itemId"))?.takeIf { s -> Rules.isValidId(s) } }.distinct()
        return booksStore.itemsById(shop, ids, forUpdate = false)
    }

    /**
     * a pharmacy sale: books_sale_add + receipt. Desk lines `{itemId?, name, qty, price, disc?}` (disc = the line's
     * discount in taka) become books lines `{itemId, name, qty, unitPrice, unitDisc}`; a bill `disc` (taka) becomes
     * `billDisc {type:"amt"}`. Less stock than sold → still accepted, warn "stock_short", the items flagged for review.
     */
    private fun sale(ctx: ActionContext, d: Device, op: Op, data: ObjectNode, at: Long, warns: MutableSet<String>): Map<String, Any?> {
        refOf(data, "rxOrderId")?.let { if (jdbc.queryForList("SELECT 1 FROM rx_orders WHERE id = ? AND pharmacy_uid = ?", it, d.bizId).isEmpty()) throw badRef() }
        val rawLines = data.get("lines")?.takeIf { it.isArray }?.take(BooksMath.MAX_LINES + 1) ?: emptyList()
        val known = shopItems(d.bizId, rawLines)
        val body = data.deepCopy()
        val lines = mapper.createArrayNode()
        val wanted = LinkedHashMap<String, Pair<String, Double>>()
        for (l in rawLines) {
            if (!l.isObject) continue
            val line = (l as ObjectNode).deepCopy()
            val id = itemIdOf(l, known)
            if (id != null) line.put("itemId", id)
            if (!line.has("unitPrice") && line.has("price")) line.set<JsonNode>("unitPrice", line.get("price"))
            val qty = line.get("qty")?.takeIf { it.isNumber }?.doubleValue()
            if (!line.has("unitDisc") && line.get("disc")?.isNumber == true && qty != null && qty > 0) {
                line.put("unitDisc", BooksMath.r2(line.get("disc").doubleValue() / qty))
            }
            lines.add(line)
            if (id != null && qty != null) {
                val prev = wanted[id]
                wanted[id] = (prev?.first ?: BooksMath.str(Js.string(l.get("name")), 100)) to ((prev?.second ?: 0.0) + qty)
            }
        }
        body.set<JsonNode>("lines", lines)
        if (!body.has("billDisc") && body.get("disc")?.isNumber == true && body.get("disc").doubleValue() > 0) {
            body.set<JsonNode>("billDisc", mapper.createObjectNode().put("type", "amt").put("v", body.get("disc").doubleValue()))
        }
        val stamp = numbered(d, data, at, warns)
        // what the server had before the sale (for the review flag)
        val before = booksStore.itemsById(d.bizId, wanted.keys, forUpdate = false)
        val r = creators.pharmacySale(ctx.lang, ctx.user, Body(body), stamp)
        val out = invoiceOut(r)
        // the books' sale id (a shop that may not issue receipts right now still records the sale, without a receipt)
        out["saleId"] = r["id"]
        @Suppress("UNCHECKED_CAST")
        val short = (r["stockShort"] as? List<Any?>)?.mapNotNull { it as? String }.orEmpty()
        if (short.isNotEmpty() || r["warn"] == "stock_short") {
            warns.add("stock_short")
            val ids = short.ifEmpty { wanted.keys.filter { k -> before[k]?.qty?.let { it < wanted[k]!!.second } == true } }
            for (itemId in ids) {
                val had = before[itemId]?.qty ?: 0.0
                val want = wanted[itemId]?.second ?: had
                jdbc.update(
                    """
                    INSERT INTO shop_stock_flags (id, shop_uid, item_id, item_name, wanted, had, short, device_id, op_id, at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Ids.newId(), d.bizId, itemId, before[itemId]?.name ?: wanted[itemId]?.first ?: "", want, had, BooksMath.r2(maxOf(0.0, want - had)),
                    d.id, op.opId, Sql.ts(stamp.at), Sql.ts(clock.now()),
                )
            }
            out["stockShort"] = ids
        }
        return out
    }

    /** books_purchase_add: desk lines `{itemId?, name, qty, cost, price?, expiry?}` (cost → unitCost); a purchase of a flagged item clears the flag */
    private fun purchase(ctx: ActionContext, d: Device, data: ObjectNode, at: Long, now: Long): Map<String, Any?> {
        val rawLines = data.get("lines")?.takeIf { it.isArray }?.take(BooksMath.MAX_LINES + 1) ?: emptyList()
        val known = shopItems(d.bizId, rawLines)
        val body = data.deepCopy().apply { put("at", at) }
        val lines = mapper.createArrayNode()
        val ids = ArrayList<String>()
        for (l in rawLines) {
            if (!l.isObject) continue
            val line = (l as ObjectNode).deepCopy()
            itemIdOf(l, known)?.let {
                line.put("itemId", it)
                ids.add(it)
            }
            if (!line.has("unitCost") && line.has("cost")) line.set<JsonNode>("unitCost", line.get("cost"))
            lines.add(line)
        }
        body.set<JsonNode>("lines", lines)
        @Suppress("UNCHECKED_CAST")
        val r = books.purchaseAdd(subCtx(ctx, ctx.user, "books_purchase_add", body)) as Map<String, Any?>
        if (ids.isNotEmpty()) {
            val list = ids.distinct()
            jdbc.update(
                "UPDATE shop_stock_flags SET resolved_at = ? WHERE shop_uid = ? AND resolved_at IS NULL AND item_id IN (${list.joinToString(",") { "?" }})",
                Sql.ts(now), d.bizId, *list.toTypedArray(),
            )
        }
        return linkedMapOf("id" to r["id"])
    }

    /**
     * books_adjust. Desk shape `{itemId, delta, reason?}`: a minus delta is a write-off of that many, a plus delta a stock
     * count of (server stock + delta). The books shape `{kind, itemId, qty, reason?}` works too.
     */
    private fun adjust(ctx: ActionContext, d: Device, data: ObjectNode): Map<String, Any?> {
        val itemId = refOf(data, "itemId") ?: throw badRef()
        val x = booksStore.item(d.bizId, itemId, forUpdate = true) ?: throw badRef()
        val body = data.deepCopy()
        if (!body.has("kind")) {
            val delta = data.get("delta")?.takeIf { it.isNumber }?.doubleValue()
            if (delta == null || delta == 0.0 || !delta.isFinite()) throw ctx.err(400, "Enter the quantity", "পরিমাণ লিখুন")
            if (delta < 0) {
                body.put("kind", "writeoff").put("qty", -delta)
            } else {
                body.put("kind", "count").put("qty", BooksMath.r2((x.qty ?: 0.0) + delta))
            }
        }
        @Suppress("UNCHECKED_CAST")
        val r = books.adjust(subCtx(ctx, ctx.user, "books_adjust", body)) as Map<String, Any?>
        return linkedMapOf("id" to r["id"])
    }

    /**
     * pay {invoice:{id?, localId?, opId?, no?}, amount, method, ref?} / void {invoice:{…}, reason}: a receipt of this
     * business — by server id; by the desk's localId / opId of the op that made it (this device only); or by number
     * (this business; the desk's own number too when the server renumbered it).
     */
    private fun payOrVoid(ctx: ActionContext, d: Device, type: String, data: ObjectNode, at: Long): Map<String, Any?> {
        val ref = data.get("invoice") as? ObjectNode ?: mapper.createObjectNode()
        fun pick(k: String) = (Js.string(ref.get(k)) ?: Js.string(data.get(k)))?.takeIf { it.isNotEmpty() }
        val id = pick("id")
        val localId = Js.string(ref.get("localId"))?.takeIf { it.isNotEmpty() }
        val opId = Js.string(ref.get("opId"))?.takeIf { it.isNotEmpty() }?.lowercase()
        val no = pick("no")
        if (id == null && localId == null && opId == null && no == null) throw OpFailure("bad_op", "Which receipt?", "কোন রসিদ?") // bn-ok
        var inv: Invoice? = null
        if (id != null) {
            if (!Rules.isValidId(id)) throw badRef()
            inv = invoices.load(id) ?: throw badRef()
            if (inv.kind != d.kind || inv.bizId != d.bizId) throw badRef()
        }
        if (inv == null && (localId != null || opId != null)) {
            val row = jdbc.queryForList(
                """
                SELECT ok, ref_id, result->>'error' AS error FROM offline_ops WHERE device_id = ? AND (op_id = ? OR local_id = ?)
                  AND type IN ('sale', 'lab_bill', 'fee') ORDER BY (op_id = ?) DESC, received_at DESC LIMIT 1
                """.trimIndent(),
                d.id, opId ?: "", localId ?: "", opId ?: "",
            ).firstOrNull()
            if (row != null && row["ok"] == true) {
                inv = (row["ref_id"] as String?)?.let { invoices.load(it) }
            } else if (row != null && row["error"] != "running") {
                throw OpFailure("ref_rejected", "That receipt was not accepted", "ওই রসিদটি নেওয়া হয়নি") // bn-ok
            }
        }
        if (inv == null && no != null) inv = invoices.findByNo(d.kind, d.bizId, no)
        val found = inv ?: throw OpFailure("invoice_not_found", "Receipt not found yet", "রসিদ এখনো পাওয়া যায়নি", retry = true) // bn-ok
        val stamp = DeviceStamp(d.id, "", at)
        val out = if (type == "pay") {
            invoices.pay(ctx.lang, ctx.user, found.id, InvoiceMath.payment(ctx.lang, data), stamp)
        } else {
            invoices.void(ctx.lang, ctx.user, found.id, Js.string(data.get("reason")) ?: "", stamp)
        }
        return linkedMapOf("id" to out.id, "no" to out.no, "invoice" to out.toMap())
    }

    /**
     * checkin {appointmentId, serial?}: the patient is at the chamber — a pending appointment is confirmed with the
     * next serial (or the given register number) through appointment_update; a confirmed one keeps its serial.
     */
    private fun checkin(ctx: ActionContext, d: Device, data: ObjectNode): Map<String, Any?> {
        val id = refOf(data, "appointmentId") ?: throw badRef()
        val a = try {
            chamber.appointmentOf(ctx, d.bizId, id)
        } catch (e: ApiException) {
            throw badRef()
        }
        if (a.status == "pending") {
            // appointment_update acts as the doctor (an assistant at the desk confirms for the doctor; access was checked)
            val doctor = AuthUser(uid = d.bizId)
            val body = mapper.createObjectNode().apply {
                put("id", a.id)
                put("op", "confirm")
                data.get("serial")?.takeIf { it.isIntegralNumber }?.let { set<JsonNode>("serial", it) }
            }
            appointments.appointmentUpdate(subCtx(ctx, doctor, "appointment_update", body))
        } else if (a.status != "confirmed" && a.status != "completed") {
            throw ctx.err(409, "This appointment was cancelled", "এই অ্যাপয়েন্টমেন্ট বাতিল হয়েছে") // bn-ok
        }
        val serial = jdbc.queryForList("SELECT serial FROM appointments WHERE id = ?", Int::class.java, a.id).firstOrNull()
        return linkedMapOf("id" to a.id, "serial" to serial, "status" to if (a.status == "completed") "completed" else "confirmed")
    }
}

/** the desktop actions (backend/docs/INVOICES.md "Offline sync") */
@Component
class DesktopModule(private val devices: DesktopDevices, private val sync: OfflineSync) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "desktop_register" to ActionHandler { devices.register(it) },
        "desktop_devices" to ActionHandler { devices.list(it) },
        "desktop_revoke" to ActionHandler { devices.revoke(it) },
        "offline_bootstrap" to ActionHandler { sync.bootstrap(it) },
        "offline_sync" to ActionHandler { sync.sync(it) },
        "offline_review_done" to ActionHandler { sync.reviewDone(it) },
    )
}
