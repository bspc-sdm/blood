package com.lifolo.blood

import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.contracts.RequestEnd
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * requestend.js — when a request ends (blood received, time over, deleted, requester's account deleted) the donors
 * still "accepted" are told not to go; when an accepted donor goes away the counts stay right.
 */
@Service
class RequestEndService(private val store: BloodStore, private val push: PushService) : RequestEnd {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc

    private fun text(reason: String, lang: String?, hospital: String, patientGroup: String): Pair<String, String> {
        fun L(en: String, bn: String) = Lang.pick(lang, en, bn)
        return when (reason) {
            "expired" -> L("Request time is over", "রিকোয়েস্টের সময় শেষ") to
                L(
                    "$hospital: the request has closed, you don't need to go. If you donated, tell us in the app.",
                    "$hospital: রিকোয়েস্টটা বন্ধ হয়েছে, আর যেতে হবে না। রক্ত দিয়ে থাকলে অ্যাপে জানান।",
                )
            "notNeeded" -> L("Blood no longer needed — no need to come", "আর রক্ত লাগবে না, আসতে হবে না") to
                L(
                    "$hospital: the requester closed the request. Thank you for offering!",
                    "$hospital: রক্তপ্রার্থী রিকোয়েস্ট বন্ধ করেছেন। রাজি হওয়ার জন্য ধন্যবাদ!",
                )
            "reopened" -> L("Request opened again", "রিকোয়েস্ট আবার খোলা হয়েছে") to
                L(
                    "$hospital: it was closed by mistake. You may still be needed — please stay available.",
                    "$hospital: ভুল করে বন্ধ হয়েছিল। আপনাকে এখনো দরকার হতে পারে, প্রস্তুত থাকুন।",
                )
            "removed" -> L("Blood request withdrawn", "রক্তের রিকোয়েস্ট তুলে নেওয়া হয়েছে") to
                L(
                    "The $patientGroup request at $hospital was removed. You don't need to go. Thank you!",
                    "$hospital-এর $patientGroup রিকোয়েস্টটা তুলে নেওয়া হয়েছে। আপনার যাওয়ার দরকার নেই। ধন্যবাদ!",
                )
            else -> L("Request closed — no need to come", "রিকোয়েস্ট বন্ধ হয়েছে, আর আসতে হবে না") to
                L(
                    "$hospital: the requester got the blood. If you already donated, tell us in the app. Thank you!",
                    "$hospital: রক্তপ্রার্থী রক্ত পেয়ে গেছেন। আগেই রক্ত দিয়ে থাকলে অ্যাপে জানান। ধন্যবাদ!",
                )
        }
    }

    override fun notifyEnded(requestId: String, hospital: String, patientGroup: String, reason: String, donorUids: List<String>?): Int {
        val ids = donorUids ?: store.acceptedDonors(requestId, 50)
        if (ids.isEmpty()) return 0
        return push.pushUsers(ids) { _, lang ->
            val (title, body) = text(reason, lang, hospital, patientGroup)
            PushText(title, body, if (reason == "removed") mapOf("type" to "request_removed") else mapOf("requestId" to requestId, "type" to "request"))
        }
    }

    fun notifyEnded(r: BloodRequest, reason: String, donorUids: List<String>? = null): Int =
        notifyEnded(r.id, r.hospital, r.patientGroup, reason, donorUids)

    private data class Withdrawn(val r: BloodRequest, val confirmed: Boolean)

    /** withdraw(requestId, uid, mode): "deleted" = account deleted (row deleted), "elsewhere" = gave blood elsewhere */
    private fun withdraw(requestId: String, uid: String, mode: String) {
        val now = System.currentTimeMillis()
        val out = store.inTx<Withdrawn?> {
            val r = store.request(requestId, forUpdate = true)
            val ds = store.response(requestId, uid, forUpdate = true) ?: return@inTx null
            val wasAccepted = ds.status == "accepted"
            if (mode == "elsewhere" && !wasAccepted) return@inTx null
            val wasConfirmed = wasAccepted && ds.confirmed
            val all = if (r != null && wasConfirmed) store.activeResponses(requestId) else null
            if (mode == "deleted") {
                jdbc.update("DELETE FROM blood_responses WHERE request_id = ? AND donor_uid = ?", requestId, uid)
            } else {
                jdbc.update(
                    "UPDATE blood_responses SET status = 'cancelled', cancelled_reason = 'donated_elsewhere', updated_at = ? WHERE request_id = ? AND donor_uid = ?",
                    com.lifolo.common.Sql.ts(now), requestId, uid,
                )
            }
            if (r == null || !wasAccepted) return@inTx null
            if (all != null) {
                val count = all.count { it.donorUid != uid && (it.status == "donated" || it.confirmed) }
                jdbc.update(
                    "UPDATE blood_requests SET accepted_count = accepted_count - 1, confirmed_count = ?, fulfilled = ? WHERE id = ?",
                    count, count >= (if (r.bags == 0) 1 else r.bags), requestId,
                )
            } else {
                jdbc.update("UPDATE blood_requests SET accepted_count = accepted_count - 1 WHERE id = ?", requestId)
            }
            Withdrawn(r, wasConfirmed)
        }
        if (out != null && out.r.isActive()) {
            val elsewhere = mode == "elsewhere"
            push.pushUsers(listOf(out.r.requesterUid)) { _, lang ->
                PushText(
                    Lang.pick(lang, "A donor is no longer available", "একজন ডোনার আর থাকছেন না"),
                    if (elsewhere) {
                        Lang.pick(
                            lang,
                            if (out.confirmed) "A confirmed donor gave blood for another patient. Please confirm another from My Request." else "A donor who agreed gave blood for another patient.",
                            if (out.confirmed) "একজন নিশ্চিত ডোনার অন্য রোগীকে রক্ত দিয়েছেন। \"আমার রিকোয়েস্ট\" থেকে আরেকজনকে নিশ্চিত করুন।" else "রাজি হওয়া একজন ডোনার অন্য রোগীকে রক্ত দিয়েছেন।",
                        )
                    } else {
                        Lang.pick(
                            lang,
                            if (out.confirmed) "A confirmed donor left the app. Please confirm another from My Request." else "A donor who agreed left the app.",
                            if (out.confirmed) "একজন নিশ্চিত ডোনার অ্যাপ ছেড়ে গেছেন। \"আমার রিকোয়েস্ট\" থেকে আরেকজনকে নিশ্চিত করুন।" else "রাজি হওয়া একজন ডোনার অ্যাপ ছেড়ে গেছেন।",
                        )
                    },
                    mapOf("requestId" to requestId, "type" to "response"),
                )
            }
        }
    }

    override fun donorLeft(requestId: String, uid: String) = withdraw(requestId, uid, "deleted")

    override fun releaseOthers(uid: String, exceptRequestId: String) {
        try {
            val ids = jdbc.queryForList("SELECT request_id FROM user_commitments WHERE uid = ? ORDER BY committed_at", String::class.java, uid)
                .filter { it != exceptRequestId && Rules.isValidId(it) }
            for (id in ids.take(20)) {
                try {
                    withdraw(id, uid, "elsewhere")
                } catch (e: Exception) {
                    log.error("release failed", e)
                }
            }
            if (ids.isNotEmpty()) {
                val all = listOf(exceptRequestId) + ids
                jdbc.update(
                    "DELETE FROM user_commitments WHERE uid = ? AND request_id IN (${all.joinToString(",") { "?" }})",
                    *(listOf<Any>(uid) + all).toTypedArray(),
                )
            }
        } catch (e: Exception) {
            log.error("releaseOthers failed", e)
        }
    }
}
