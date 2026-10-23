package com.lifolo.payments

import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** one MFS number of a business (pay_methods row) */
data class PayMethod(
    val method: String,
    val number: String,
    val type: String,
    val active: Boolean,
    /** since when this number has been active without a break (null = off) */
    val activeSince: Long?,
    /** the matured number this one replaced, used until this one matures */
    val prevNumber: String?,
    val prevType: String?,
    val position: Int,
) {
    /** active for 24 h without a break: patients may see it (security review P4) */
    fun matured(now: Long): Boolean = active && activeSince != null && activeSince <= now - Pay.COOL_MS

    /** the number (and type) new requests get now; null = none (a new number still maturing, or switched off) */
    fun inUse(now: Long): Pair<String, String>? = when {
        !active -> null
        matured(now) -> number to type
        else -> prevNumber?.let { it to (prevType ?: type) }
    }

    /** when this number reaches patients (null = it already does, or it is off) */
    fun pendingUntil(now: Long): Long? = if (active && !matured(now)) activeSince?.plus(Pay.COOL_MS) else null

    /** the business's own view (pay_methods_get) */
    fun toMap(now: Long): Map<String, Any?> = linkedMapOf(
        "method" to method, "number" to number, "type" to type, "active" to active,
        "activeNumber" to inUse(now)?.first, "pendingUntil" to pendingUntil(now),
    )
}

/** what pay_methods_set gets for one method */
data class MethodIn(val method: String, val number: String, val type: String, val active: Boolean)

/** one change of a business's methods (audit + the owner's push and email) */
data class MethodChange(val method: String, val change: String, val oldNo: String?, val newNo: String?)

/**
 * The businesses' MFS numbers (pay_methods) — the one code path for every payment number, the online chamber's bKash
 * number included (security review P4). A number reaches patients only after it has been active for 24 h without a
 * break: a new method, a removed and re-added one, a re-activated one and an edited number all wait. While an edited
 * number matures, the matured number it replaced stays in use; a switched-off or removed method is gone at once. A
 * frozen business ("আমি করিনি") shows no number at all. Requests keep the numbers they were made with (snapshot).
 * Depends on nothing but the database (the appointments read it too).
 */
@Component
class PayMethodStore(private val jdbc: JdbcTemplate) {
    fun list(kind: String, bizId: String, lock: Boolean = false): List<PayMethod> = jdbc.query(
        "SELECT * FROM pay_methods WHERE kind = ? AND biz_id = ? ORDER BY position, method${if (lock) " FOR UPDATE" else ""}",
        { rs, _ ->
            PayMethod(
                rs.getString("method"), rs.getString("number"), rs.getString("type"), rs.getBoolean("active"),
                rs.getTimestamp("active_since")?.time, rs.getString("prev_number"), rs.getString("prev_type"), rs.getInt("position"),
            )
        },
        kind, bizId,
    )

    fun frozen(kind: String, bizId: String): Boolean =
        jdbc.queryForList("SELECT 1 FROM pay_freezes WHERE kind = ? AND biz_id = ?", kind, bizId).isNotEmpty()

    /** the methods a new request gets: active ones with a matured number; none while the business is frozen */
    fun snapshot(kind: String, bizId: String, now: Long): List<Map<String, String>> {
        if (frozen(kind, bizId)) return emptyList()
        return list(kind, bizId).mapNotNull { m -> m.inUse(now)?.let { (no, type) -> linkedMapOf("method" to m.method, "number" to no, "type" to type) } }
    }

    /**
     * The online chamber's bKash number for new bookings (read only from here): the matured number in use, or null (no
     * bKash method, still maturing, off, or frozen) — and when the doctor's newest number takes over.
     */
    fun chamberBkash(doctorUid: String, now: Long): Pair<String?, Long?> {
        val m = list("chamber", doctorUid).firstOrNull { it.method == "bkash" } ?: return null to null
        if (frozen("chamber", doctorUid)) return null to null
        return m.inUse(now)?.first to m.pendingUntil(now)
    }

    /**
     * pay_methods_set (and provider_save's online-chamber bKash number, through [setOne]): [methods] replaces the
     * business's list (omitted ones are removed). Returns the changes (empty = nothing changed). The caller's transaction.
     */
    fun replace(kind: String, bizId: String, ownerUid: String?, methods: List<MethodIn>, by: String?, now: Long): List<MethodChange> {
        val old = list(kind, bizId, lock = true).associateBy { it.method }
        val changes = ArrayList<MethodChange>()
        methods.forEachIndexed { i, m ->
            val o = old[m.method]
            if (o == null) {
                jdbc.update(
                    """
                    INSERT INTO pay_methods (kind, biz_id, method, number, type, active, active_since, prev_number, prev_type, position, owner_uid, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                    """.trimIndent(),
                    kind, bizId, m.method, m.number, m.type, m.active, if (m.active) Sql.ts(now) else null, i, ownerUid, Sql.ts(now),
                )
                changes += MethodChange(m.method, "added", null, m.number)
                return@forEachIndexed
            }
            var since: Long? = o.activeSince
            var prevNo: String? = o.prevNumber
            var prevType: String? = o.prevType
            if (o.matured(now)) {
                prevNo = null
                prevType = null
            }
            if (o.number != m.number) {
                val use = o.inUse(now)
                when {
                    !m.active -> {
                        since = null
                        prevNo = null
                        prevType = null
                    }
                    // back to the number patients get right now: it never stopped being in use
                    use != null && use.first == m.number -> {
                        since = now - Pay.COOL_MS
                        prevNo = null
                        prevType = null
                    }
                    else -> {
                        since = now
                        prevNo = use?.first
                        prevType = use?.second
                    }
                }
                changes += MethodChange(m.method, "number", o.number, m.number)
            } else if (o.active != m.active) {
                // switched off: gone at once; switched on again: matures again
                since = if (m.active) now else null
                prevNo = null
                prevType = null
            }
            if (o.type != m.type) changes += MethodChange(m.method, "type", o.number, m.number)
            if (o.active != m.active) changes += MethodChange(m.method, if (m.active) "on" else "off", o.number, m.number)
            jdbc.update(
                """
                UPDATE pay_methods SET number = ?, type = ?, active = ?, active_since = ?, prev_number = ?, prev_type = ?, position = ?,
                  owner_uid = ?, updated_at = ?
                WHERE kind = ? AND biz_id = ? AND method = ?
                """.trimIndent(),
                m.number, m.type, m.active, since?.let { Sql.ts(it) }, prevNo, prevType, i, ownerUid, Sql.ts(now), kind, bizId, m.method,
            )
        }
        val keep = methods.map { it.method }.toSet()
        for (o in old.values) {
            if (o.method in keep) continue
            jdbc.update("DELETE FROM pay_methods WHERE kind = ? AND biz_id = ? AND method = ?", kind, bizId, o.method)
            changes += MethodChange(o.method, "removed", o.number, null)
        }
        for (c in changes) log(kind, bizId, c.method, c.change, c.oldNo, c.newNo, by, now)
        return changes
    }

    /** one method set to [number] (active), the others unchanged — the online chamber's bKash number (provider_save) */
    fun setOne(kind: String, bizId: String, ownerUid: String?, method: String, number: String, type: String, by: String?, now: Long): List<MethodChange> {
        val cur = list(kind, bizId)
        val next = if (cur.any { it.method == method }) {
            cur.map { m -> if (m.method == method) MethodIn(method, number, if (m.number == number) m.type else type, true) else MethodIn(m.method, m.number, m.type, m.active) }
        } else {
            cur.map { MethodIn(it.method, it.number, it.type, it.active) } + MethodIn(method, number, type, true)
        }
        return replace(kind, bizId, ownerUid, next, by, now)
    }

    fun log(kind: String, bizId: String, method: String, change: String, oldNo: String?, newNo: String?, by: String?, now: Long) {
        jdbc.update(
            "INSERT INTO pay_method_log (kind, biz_id, method, change, old_last4, new_last4, by_uid, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            kind, bizId, method, change, oldNo?.takeLast(4), newNo?.takeLast(4), by, Sql.ts(now),
        )
    }

    /**
     * the audit rows of a business (newest first): `number` masked (last 4 only), `cooloffUntil` = when a number that
     * change made active reaches patients (added / edited / switched on)
     */
    fun history(kind: String, bizId: String, limit: Int = 30): List<Map<String, Any?>> = jdbc.query(
        "SELECT method, change, old_last4, new_last4, by_uid, at FROM pay_method_log WHERE kind = ? AND biz_id = ? ORDER BY at DESC, id DESC LIMIT ?",
        { rs, _ ->
            val change = rs.getString(2)
            val newLast4 = rs.getString(4)
            val at = rs.getTimestamp(6).time
            linkedMapOf<String, Any?>(
                "method" to rs.getString(1), "change" to change,
                "number" to (newLast4 ?: rs.getString(3))?.let { "•••••••$it" },
                "oldLast4" to rs.getString(3), "newLast4" to newLast4, "by" to rs.getString(5), "at" to at,
                "cooloffUntil" to if (change in setOf("added", "number", "on")) at + Pay.COOL_MS else null,
            )
        },
        kind, bizId, limit,
    )

    /** when the business's numbers were frozen ("আমি করিনি"), or null */
    fun frozenSince(kind: String, bizId: String): Long? =
        jdbc.query("SELECT frozen_at FROM pay_freezes WHERE kind = ? AND biz_id = ?", { rs, _ -> rs.getTimestamp(1).time }, kind, bizId).firstOrNull()

    /** the doctor's advance booking fee per in-person chamber */
    fun advances(doctorUid: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT chamber_key, amount, refundable_hours FROM pay_chamber_advance WHERE doctor_uid = ? ORDER BY chamber_key",
        { rs, _ -> linkedMapOf<String, Any?>("chamberKey" to rs.getString(1), "amount" to rs.getInt(2), "refundableUntilHoursBefore" to rs.getInt(3)) },
        doctorUid,
    )

    fun advanceOf(doctorUid: String, chamberKey: String): Pair<Int, Int>? = jdbc.query(
        "SELECT amount, refundable_hours FROM pay_chamber_advance WHERE doctor_uid = ? AND chamber_key = ?",
        { rs, _ -> rs.getInt(1) to rs.getInt(2) },
        doctorUid, chamberKey,
    ).firstOrNull()
}
