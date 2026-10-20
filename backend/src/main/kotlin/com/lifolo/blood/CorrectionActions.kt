package com.lifolo.blood

import com.lifolo.common.Ids
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component

/**
 * Last-donation-date correction requests from the person ("তথ্য সংশোধনের অনুরোধ"). Node never had a server action for
 * this: the app wrote `correctionRequests/{id}` straight to Firestore (rules: own uid, status pending, reason 1–300
 * letters, requestedMillis null or a number); the admin lists them (/api/admin/corrections, admin_overview) and
 * decides (/api/admin/resolve, [CorrectionService]) — approving sets the date. Since Firestore writes are closed this
 * is the only way to create one.
 *  - correction_create {requestedMillis: number | null (never donated), reason} → {ok, id}
 *  - correction_mine → {item: the latest request or null}
 * Limits: one waiting request at a time (409), 3 a day.
 */
@Component
class CorrectionActions(
    private val store: BloodStore,
    private val quotas: QuotaService,
) : ActionModule {
    private val jdbc get() = store.jdbc

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "correction_create" to ActionHandler(::create),
        "correction_mine" to ActionHandler(::mine),
    )

    private fun pendingErr(ctx: ActionContext) =
        ctx.err(409, "Your earlier request is still waiting for the admin", "আপনার আগের অনুরোধটা এখনো অ্যাডমিনের কাছে আছে") // bn-ok

    fun create(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val b = ctx.body
        // the date: a number (a past day, from 1990) or null = "never donated"; must be sent either way
        val explicitNull = b.isNull("requestedMillis")
        val requested: Long? = if (explicitNull) null else b.int("requestedMillis")
        if (!explicitNull && requested == null) throw ctx.err(400, "Choose the correct date", "সঠিক তারিখ বেছে নিন") // bn-ok
        if (requested != null && (requested < DonorService.MIN_MILLIS || requested > BloodRules.todayDhakaUtcMidnight())) {
            throw ctx.err(400, "Choose a valid date (not in the future)", "সঠিক তারিখ দিন (ভবিষ্যতের নয়)")
        }
        val reason = Rules.cleanText(b.string("reason"), REASON_MAX)
            ?: throw ctx.err(400, "Write what was wrong (up to $REASON_MAX letters)", "কী ভুল হয়েছিল লিখুন (${REASON_MAX} অক্ষরের মধ্যে)") // bn-ok
        val (exists, current) = store.lastDonation(uid)
        if (!exists) throw ctx.err(404, "Profile not found", "প্রোফাইল পাওয়া যায়নি")
        if (current == requested) throw ctx.err(400, "This is already your date", "এটাই এখন আপনার তারিখ") // bn-ok
        if (jdbc.queryForList("SELECT 1 FROM correction_requests WHERE uid = ? AND status = 'pending'", uid).isNotEmpty()) throw pendingErr(ctx)
        quotas.use(ctx.lang, uid, "correction")
        val name = jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
        val id = Ids.newId()
        try {
            jdbc.update(
                """
                INSERT INTO correction_requests (id, uid, name, reason, current_ms, requested_ms, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
                """.trimIndent(),
                id, uid, name.take(80), reason, current, requested, Sql.ts(System.currentTimeMillis()),
            )
        } catch (e: DuplicateKeyException) {
            throw pendingErr(ctx)
        }
        return linkedMapOf("ok" to true, "id" to id)
    }

    fun mine(ctx: ActionContext): Any {
        val item = jdbc.query(
            "SELECT * FROM correction_requests WHERE uid = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            { rs, _ ->
                linkedMapOf<String, Any?>(
                    "id" to rs.getString("id"),
                    "status" to rs.getString("status"),
                    "currentMillis" to rs.getLong("current_ms").let { if (rs.wasNull()) null else it },
                    "requestedMillis" to rs.getLong("requested_ms").let { if (rs.wasNull()) null else it },
                    "reason" to (rs.getString("reason") ?: ""),
                    "createdAtMillis" to (rs.getTimestamp("created_at")?.time ?: 0L),
                    "resolvedAtMillis" to rs.getTimestamp("resolved_at")?.time,
                )
            },
            ctx.user.id,
        ).firstOrNull()
        return linkedMapOf("item" to item)
    }

    companion object {
        const val REASON_MAX = 300
    }
}
