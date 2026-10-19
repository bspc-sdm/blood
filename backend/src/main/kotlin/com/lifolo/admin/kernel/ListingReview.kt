package com.lifolo.admin.kernel

import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * listingreview.js — the admin decision on ambulances, pharmacies, blood banks and labs in one place: a reason the
 * owner sees (push + own card), the approved state kept as `last_approved`, and a rejected **edit** of an approved
 * listing restores the last approved data instead of unlisting it.
 *
 * Pure helpers (snapshot, changedFields, reviewNeeded, keepApproved) are in [ListingRules]; the Node-shaped listing
 * map comes from [ListingRows.load].
 */
interface ListingReview {
    /**
     * resolveListing — the body of `admin_ambulance_resolve`, `admin_pharmacy_resolve`, `admin_bloodbank_resolve`,
     * `admin_lab_resolve` (the caller checks admin rights first). Body: `id` is passed as [id]; `status`
     * (approved | rejected | suspended, else `approve === true` → approved, otherwise rejected), `reason` (≤ 200),
     * `checks` (verification tick ids).
     *  - invalid id → 400 "Not found"; no row → 404; same status → `{ok: true, unchanged: true}` (not admin-logged)
     *  - rejected while pending and `last_approved` exists → the approved data comes back, status approved,
     *    `edit_rejected_reason/_at` set, owner told "changes were not accepted" → `{ok: true, restored: true}`
     *  - pending/rejected → approved needs every check ([VerifyDocs.assertChecks]) → verification stored
     *  - approved → `approved_name`, `last_approved = snapshot`; otherwise `status_reason = reason`;
     *    suspended → `suspended_at`; ambulances: `remind_at` recomputed (see [AMBULANCE_REMIND_AT_MS])
     *  - [Sanctions.onStatusChange], then a push to the owner (data `{type: "services", kind}`) → `{ok: true}`
     * [table] is the listing table (or Node collection) and must match [kind].
     */
    fun resolve(ctx: ActionContext, kind: String, table: String, id: String?): Any

    /** tellSuspendedByReport: push to the owner after a report was upheld (moderation removeTarget) */
    fun tellSuspendedByReport(kind: String, listing: Map<String, Any?>, reason: String)

    /**
     * possibleDuplicates: for the panel's pending list — other listings of the same table with the same phone,
     * vehicle number or licence number: up to 5 `{id, name, status, field}`.
     */
    fun possibleDuplicates(kind: String, id: String, listing: Map<String, Any?>): List<Map<String, Any?>>

    fun snapshot(listing: Map<String, Any?>?): Map<String, Any?> = ListingRules.snapshot(listing)

    fun changedFields(listing: Map<String, Any?>?): List<ListingRules.Change> = ListingRules.changedFields(listing)

    companion object {
        /**
         * ambulance.js REMIND_AT_MS: "still available?" reminder 11 h after the driver went available
         * (AVAILABLE_MS 12 h − REMIND_BEFORE_MS 1 h). P5 should use this constant.
         */
        const val AMBULANCE_REMIND_AT_MS = 11 * 3600 * 1000L
    }
}

@Service
class ListingReviewService(
    private val jdbc: JdbcTemplate,
    private val rows: ListingRows,
    private val verify: VerifyDocs,
    private val sanctions: Sanctions,
    private val push: PushService,
) : ListingReview {
    override fun resolve(ctx: ActionContext, kind: String, table: String, id: String?): Any {
        val k = ListingKind.of(kind)?.takeIf { it.isListing } ?: throw IllegalArgumentException("not a listing kind: $kind")
        require(ListingKind.ofTable(table) == k) { "table $table does not match kind $kind" }
        val b = ctx.body
        val asked = b.string("status")
        val status: String = when {
            asked == "approved" || asked == "rejected" || asked == "suspended" -> asked!!
            b.isTrue("approve") -> "approved"
            else -> "rejected"
        }
        if (id == null || !Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val reason = Rules.cleanText(b.string("reason"), 200) ?: ""
        val x = rows.load(k, id) ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val prev = x["status"] as String?
        if (prev == status) return linkedMapOf("ok" to true, "unchanged" to true)
        val en = k.labelEn
        val bn = k.labelBn
        val name = x["name"] as? String
        val withReason = { lang: String? ->
            listOf(name ?: "", if (reason.isNotEmpty()) Lang.pick(lang, "Reason: $reason", "কারণ: $reason") else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val adminUid = ctx.user.uid

        // a rejected edit of an approved listing: the last approved data comes back and it stays listed
        @Suppress("UNCHECKED_CAST")
        val last = x["lastApproved"] as? Map<String, Any?>
        if (status == "rejected" && prev == "pending" && last != null) {
            val set = SqlSet()
            rows.restoreData(k, last, set)
            val approvedName = listOf(last["name"], x["approvedName"], x["name"]).firstOrNull { ListingRules.jsTruthy(it) } ?: x["name"]
            set.value("approved_name", approvedName?.toString())
                .value("status", "approved")
                .nul("status_reason")
                .value("edit_rejected_reason", reason)
                .now("edit_rejected_at")
                .now("reviewed_at")
                .value("reviewed_by", adminUid)
                .nul("docs_requested_at")
                .nul("docs_request_note")
            remind(k, x, true, set)
            set.update(jdbc, k.table, "${k.idColumn} = ?", id)
            tellOwner(
                x["ownerUid"] as? String,
                "Your $en changes were not accepted — the earlier details stay listed",
                "আপনার $bn-এর বদল গ্রহণ হয়নি — আগের তথ্যই তালিকায় আছে",
                withReason,
                kind,
            )
            return linkedMapOf("ok" to true, "restored" to true)
        }

        // pending/rejected → approved: every check ticked in the panel (not when un-suspending)
        val verification = if (status == "approved" && (prev == "pending" || prev == "rejected")) {
            verify.assertChecks(ctx.lang, ctx.user, kind, x, b["checks"])
        } else {
            null
        }
        val set = SqlSet().value("status", status).now("reviewed_at").value("reviewed_by", adminUid)
        if (verification != null) {
            @Suppress("UNCHECKED_CAST")
            set.value("verified_by", verification["by"])
                .value("verified_by_email", verification["byEmail"])
                .ms("verified_at", verification["at"] as Long)
                .textArray("verification_checks", verification["checks"] as List<String>)
                .nul("docs_requested_at")
                .nul("docs_request_note")
        }
        if (status == "suspended") set.now("suspended_at")
        remind(k, x, status == "approved", set)
        if (status == "approved") {
            set.value("approved_name", name)
                .json("last_approved", rows.toJson(ListingRules.snapshot(x)))
                .nul("status_reason")
                .nul("edit_rejected_reason")
        } else {
            set.value("status_reason", reason)
        }
        set.update(jdbc, k.table, "${k.idColumn} = ?", id)
        sanctions.onStatusChange(kind, x, prev, status)
        val (titleEn, titleBn) = when (status) {
            "approved" -> "Your $en is now listed" to "আপনার $bn তালিকায় এসেছে"
            "suspended" -> "Your $en was suspended. You can appeal from Profile → Help & appeals." to
                "আপনার $bn বন্ধ করা হয়েছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।"
            else -> "${en.substring(0, 1).uppercase()}${en.substring(1)} not approved" to "$bn অনুমোদন হয়নি"
        }
        tellOwner(x["ownerUid"] as? String, titleEn, titleBn, withReason, kind)
        return linkedMapOf("ok" to true)
    }

    /** ambulances: "still available?" reminder only while listed, available, and not yet sent for this shift */
    private fun remind(k: ListingKind, x: Map<String, Any?>, approved: Boolean, set: SqlSet) {
        if (k != ListingKind.AMBULANCE) return
        val availableAt = (x["availableAt"] as? Number)?.toLong()
        val sent = (x["reminderSentAt"] as? Number)?.toLong() ?: 0L
        val on = approved && x["available"] == true && availableAt != null && sent < availableAt
        if (on) set.ms("remind_at", availableAt!! + ListingReview.AMBULANCE_REMIND_AT_MS) else set.nul("remind_at")
    }

    private fun tellOwner(ownerUid: String?, en: String, bn: String, body: (String?) -> String, kind: String) {
        if (ownerUid == null) return
        push.pushUsers(listOf(ownerUid)) { _, lang ->
            PushText(Lang.pick(lang, en, bn), body(lang), linkedMapOf("type" to "services", "kind" to kind))
        }
    }

    override fun tellSuspendedByReport(kind: String, listing: Map<String, Any?>, reason: String) {
        val k = ListingKind.of(kind)?.takeIf { it.isListing } ?: ListingKind.AMBULANCE
        val name = listing["name"] as? String
        tellOwner(
            listing["ownerUid"] as? String,
            "Your ${k.labelEn} was suspended after a report. You can appeal from Profile → Help & appeals.",
            "রিপোর্টের পর আপনার ${k.labelBn} বন্ধ করা হয়েছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            { lang ->
                listOf(name ?: "", if (reason.isNotEmpty()) Lang.pick(lang, "Reason: $reason", "কারণ: $reason") else "")
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            },
            kind,
        )
    }

    override fun possibleDuplicates(kind: String, id: String, listing: Map<String, Any?>): List<Map<String, Any?>> {
        val k = ListingKind.of(kind)?.takeIf { it.isListing } ?: return emptyList()
        val columns = listOf("phone" to "phone", "vehicleNo" to "vehicle_no", "licenseNo" to "license_no")
            .filter { (_, c) -> k != ListingKind.AMBULANCE || c != "license_no" }
            .filter { (_, c) -> k == ListingKind.AMBULANCE || c != "vehicle_no" }
        val found = LinkedHashMap<String, Map<String, Any?>>()
        for ((field, column) in columns) {
            val value = listing[field] as? String ?: continue
            if (value.isBlank()) continue
            jdbc.query(
                "SELECT ${k.idColumn} AS id, name, status FROM ${k.table} WHERE $column = ? LIMIT 5",
                { rs, _ -> Triple(rs.getString("id"), rs.getString("name") ?: "", rs.getString("status") ?: "") },
                value,
            ).filter { it.first != id }.forEach { (oid, n, s) ->
                found[oid] = linkedMapOf("id" to oid, "name" to n, "status" to s, "field" to field)
            }
        }
        return found.values.take(5)
    }
}
