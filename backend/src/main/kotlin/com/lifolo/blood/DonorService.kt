package com.lifolo.blood

import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * donors.js (response_update, confirm_donor) and donationdate.js (donation_date_set).
 * A response stays "accepted" when confirmed (only `confirmed = true` is added).
 */
@Service
class DonorService(
    private val store: BloodStore,
    private val ends: RequestEndService,
    private val push: PushService,
    private val quotas: QuotaService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc

    private data class UpdateResult(
        val r: BloodRequest,
        val restored: Boolean = false,
        val lost: Boolean = false,
        val donated: Boolean = false,
        val need: Int = 0,
        val reopened: Boolean = false,
        val standby: List<String> = emptyList(),
    )

    /** donor: cancel / donated; requester: remove (and restore within 30 min) */
    fun responseUpdate(ctx: ActionContext): Any {
        val b = ctx.body
        val requestId = b.string("requestId")
        val op = Js.string(b["op"])
        val uid = ctx.user.id
        val donorUid = if (Js.truthy(b["donorUid"])) b.string("donorUid") else uid
        if (!Rules.isValidId(requestId) || !Rules.isValidId(donorUid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        requestId!!
        donorUid!!
        val result = store.inTx {
            val r = store.request(requestId, forUpdate = true)
            val ds = store.response(requestId, donorUid, forUpdate = true)
            if (r == null || ds == null) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
            val now = System.currentTimeMillis()
            val isDonor = donorUid == uid
            val isRequester = r.requesterUid == uid
            var next: String? = null
            if (ds.status == "accepted") {
                if (isDonor && op == "cancel") {
                    next = "cancelled"
                } else if (isDonor && op == "donated") {
                    next = "donated"
                } else if (isRequester && !isDonor && op == "remove") {
                    next = "declined"
                }
            }
            // removed by mistake: the requester can bring the donor back within 30 minutes (open request)
            if (op == "restore") {
                val ok = isRequester && !isDonor && ds.status == "declined" && ds.removedBy == "requester" &&
                    now - (ds.removedAt ?: 0L) <= RESTORE_MS && r.isActive(now)
                if (!ok) throw ctx.err(400, "This donor can no longer be brought back", "এই ডোনারকে আর ফিরিয়ে আনা যায় না")
                jdbc.update(
                    "UPDATE blood_responses SET status = 'accepted', confirmed = FALSE, removed_by = NULL, removed_at = NULL, updated_at = ? WHERE request_id = ? AND donor_uid = ?",
                    Sql.ts(now), requestId, donorUid,
                )
                jdbc.update("UPDATE blood_requests SET accepted_count = accepted_count + 1 WHERE id = ?", requestId)
                return@inTx UpdateResult(r, restored = true)
            }
            if (next == null) throw ctx.err(400, "This can't be changed now", "এখন এটা বদলানো যায় না")

            val wasConfirmed = ds.confirmed
            val all = store.activeResponses(requestId)
            if (next == "declined") {
                jdbc.update(
                    "UPDATE blood_responses SET status = ?, updated_at = ?, removed_by = 'requester', removed_at = ? WHERE request_id = ? AND donor_uid = ?",
                    next, Sql.ts(now), Sql.ts(now), requestId, donorUid,
                )
            } else {
                jdbc.update("UPDATE blood_responses SET status = ?, updated_at = ? WHERE request_id = ? AND donor_uid = ?", next, Sql.ts(now), requestId, donorUid)
            }
            // cancelled/removed → one fewer accepted ("donated" stays counted among the accepted)
            if (next != "donated") jdbc.update("UPDATE blood_requests SET accepted_count = accepted_count - 1 WHERE id = ?", requestId)
            if (next == "donated") {
                // the server sets the donor's last donation date (only forward)
                val (exists, last) = store.lastDonation(donorUid, forUpdate = true)
                val today = BloodRules.todayDhakaUtcMidnight()
                if (exists && (last == null || last < today)) store.setLastDonation(donorUid, today)
            }
            // count confirmed/donated donors again in every case
            val count = all.count { it.donorUid != donorUid && (it.status == "donated" || it.confirmed) } + (if (next == "donated") 1 else 0)
            val bags = if (r.bags == 0) 1 else r.bags
            val fulfilled = count >= bags
            jdbc.update("UPDATE blood_requests SET confirmed_count = ?, fulfilled = ? WHERE id = ?", count, fulfilled, requestId)
            val standby = all.filter { it.donorUid != donorUid && it.status == "accepted" && !it.confirmed }.map { it.donorUid }
            UpdateResult(
                r,
                lost = wasConfirmed && next != "donated",
                donated = next == "donated",
                need = maxOf(bags - count, 0),
                reopened = r.fulfilled && !fulfilled,
                standby = standby,
            )
        }
        val r = result.r
        // gave blood here: their "accepted" on other requests is released (those requesters are told)
        if (result.donated) ends.releaseOthers(donorUid, requestId)
        val data = mapOf("requestId" to requestId, "type" to "request")
        if (result.restored) {
            push.pushUsers(listOf(donorUid)) { _, lang ->
                PushText(
                    Lang.pick(lang, "You are back on the request", "আপনাকে আবার রিকোয়েস্টে রাখা হয়েছে"),
                    Lang.pick(
                        lang,
                        "${r.hospital}: the requester removed you by mistake. Please wait for confirmation.",
                        "${r.hospital}: রক্তপ্রার্থী ভুল করে বাদ দিয়েছিলেন। নিশ্চিত করার অপেক্ষায় থাকুন।",
                    ),
                    data,
                )
            }
            return linkedMapOf("ok" to true, "restored" to true)
        }
        // removed by the requester: tell the donor, or they may go to the hospital
        if (op == "remove") {
            push.pushUsers(listOf(donorUid)) { _, lang ->
                PushText(
                    Lang.pick(lang, "You are no longer needed for this request", "এই রিকোয়েস্টে আপনাকে আর লাগবে না"),
                    Lang.pick(lang, "${r.hospital}: you don't need to go. Thank you for offering!", "${r.hospital}: আপনাকে যেতে হবে না। রাজি হওয়ার জন্য ধন্যবাদ!"),
                    data,
                )
            }
        }
        if (result.lost && r.isActive()) {
            if (op == "cancel") {
                push.pushUsers(listOf(r.requesterUid)) { _, lang ->
                    PushText(
                        Lang.pick(lang, "A confirmed donor cancelled", "একজন নিশ্চিত ডোনার বাতিল করেছেন"),
                        Lang.pick(
                            lang,
                            "${result.need} more donor(s) needed. Confirm another from My Request.",
                            "আরও ${result.need} জন লাগবে। \"আমার রিকোয়েস্ট\" থেকে আরেকজনকে নিশ্চিত করুন।",
                        ),
                        mapOf("requestId" to requestId, "type" to "response"),
                    )
                }
            }
            // donors who heard "enough for now" may be needed again
            if (result.reopened) {
                push.pushUsers(result.standby) { _, lang ->
                    PushText(
                        Lang.pick(lang, "A donor may be needed again", "আবার ডোনার লাগতে পারে"),
                        Lang.pick(lang, "The requester will confirm soon. Please stay available.", "রক্তপ্রার্থী শিগগিরই নিশ্চিত করবেন। অনুগ্রহ করে প্রস্তুত থাকুন।"),
                        data,
                    )
                }
            }
        }
        return linkedMapOf("ok" to true)
    }

    fun confirmDonor(ctx: ActionContext): Any {
        val b = ctx.body
        val requestId = b.string("requestId")
        val donorUid = b.string("donorUid")
        if (!Rules.isValidId(requestId) || !Rules.isValidId(donorUid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        requestId!!
        donorUid!!
        val uid = ctx.user.id
        val result = store.inTx<Pair<BloodRequest, List<String>>?> {
            val r = store.request(requestId, forUpdate = true) ?: throw ctx.err(404, "Request not found", "রিকোয়েস্টটা পাওয়া যায়নি")
            val ds = store.response(requestId, donorUid, forUpdate = true)
            if (r.requesterUid != uid) throw ctx.err(403, "This is not your request", "এটা আপনার রিকোয়েস্ট না")
            if (!r.isActive()) throw ctx.err(400, "This request is closed", "রিকোয়েস্টটা বন্ধ হয়ে গেছে")
            if (ds == null || ds.status != "accepted") throw ctx.err(400, "This donor is no longer available", "এই ডোনার এখন আর রাজি অবস্থায় নেই")
            if (ds.confirmed) return@inTx null
            val all = store.activeResponses(requestId)
            val confirmedCount = all.count { it.donorUid == donorUid || it.confirmed || it.status == "donated" }
            val bags = if (r.bags == 0) 1 else r.bags
            // no more "please come" than bags needed (raise the bags first)
            if (confirmedCount > bags) {
                throw ctx.err(
                    400,
                    "All bags are already covered. To confirm another donor, increase the bags first.",
                    "সব ব্যাগের ডোনার নিশ্চিত হয়ে গেছে। আরেকজন নিশ্চিত করতে আগে ব্যাগের সংখ্যা বাড়ান।",
                )
            }
            val fulfilled = confirmedCount >= bags
            jdbc.update(
                "UPDATE blood_responses SET confirmed = TRUE, confirmed_at = ? WHERE request_id = ? AND donor_uid = ?",
                Sql.ts(System.currentTimeMillis()), requestId, donorUid,
            )
            jdbc.update("UPDATE blood_requests SET confirmed_count = ?, fulfilled = ? WHERE id = ?", confirmedCount, fulfilled, requestId)
            // just fulfilled: the other accepted (not confirmed) donors are told
            val others = if (fulfilled && !r.fulfilled) {
                all.filter { it.donorUid != donorUid && it.status == "accepted" && !it.confirmed }.map { it.donorUid }
            } else {
                emptyList()
            }
            r to others
        } ?: return linkedMapOf("ok" to true, "already" to true)

        val (r, others) = result
        try {
            val whenMs = r.neededAtMillis?.takeIf { it != 0L }
            push.pushUsers(listOf(donorUid) + others) { u, lang ->
                val time = if (whenMs != null) " · ${if (lang == "en") BloodRules.formatTimeEn(whenMs) else BloodRules.formatTimeBn(whenMs)}" else ""
                if (u == donorUid) {
                    PushText(
                        Lang.pick(lang, "You are confirmed — please come", "আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন"),
                        "${r.hospital}$time",
                        mapOf("requestId" to requestId, "type" to "request"),
                    )
                } else {
                    PushText(
                        Lang.pick(lang, "Enough donors for now — thank you!", "আপাতত যথেষ্ট ডোনার পাওয়া গেছে, ধন্যবাদ!"),
                        Lang.pick(lang, "Please stay available in case it changes.", "দরকার হলে আবার জানানো হবে।"),
                        mapOf("requestId" to requestId, "type" to "request"),
                    )
                }
            }
        } catch (e: Exception) {
            log.error("confirm push failed", e)
        }
        return linkedMapOf("ok" to true)
    }

    /**
     * Changing the last donation date without admin approval: moving it back (or "never") and a gap under 120 days
     * need `confirmed: true` (409 confirm_needed); every change is logged (last 20); 3 per day.
     */
    fun donationDateSet(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val explicitNull = b.isNull("millis")
        val after: Long? = if (explicitNull) null else b.int("millis")
        if ((!explicitNull && after == null) || (after != null && (after < MIN_MILLIS || after > BloodRules.todayDhakaUtcMidnight() + BloodRules.DAY_MS))) {
            throw ctx.err(400, "Choose a valid date (not in the future)", "সঠিক তারিখ দিন (ভবিষ্যতের নয়)")
        }
        val (exists, before) = store.lastDonation(uid)
        if (!exists) throw ctx.err(404, "Profile not found", "প্রোফাইল পাওয়া যায়নি")
        if (before == after) return linkedMapOf("ok" to true, "unchanged" to true)
        val risk = BloodRules.donationRisk(before, after)
        if (risk != null && !b.isTrue("confirmed")) {
            throw ctx.err(409, "Please confirm that you understand the risk", "ঝুঁকি জেনে নিশ্চিত করুন", "confirm_needed")
        }
        quotas.use(ctx.lang, uid, "dateChange")
        store.inTx {
            store.lastDonation(uid, forUpdate = true)
            store.setLastDonation(uid, after)
            jdbc.update(
                "INSERT INTO donation_date_log (uid, from_ms, to_ms, risk, at) VALUES (?, ?, ?, ?, now())",
                uid, before, after, risk ?: "",
            )
            // keep the newest LOG_MAX entries
            jdbc.update(
                "DELETE FROM donation_date_log WHERE uid = ? AND id NOT IN (SELECT id FROM donation_date_log WHERE uid = ? ORDER BY at DESC, id DESC LIMIT ?)",
                uid, uid, LOG_MAX,
            )
        }
        return linkedMapOf("ok" to true, "risk" to risk)
    }

    companion object {
        const val RESTORE_MS = 30L * 60 * 1000
        val MIN_MILLIS: Long = LocalDate.of(1990, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        const val LOG_MAX = 20
    }
}
