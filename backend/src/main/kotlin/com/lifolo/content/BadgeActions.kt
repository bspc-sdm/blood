package com.lifolo.content

import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.appointments.ApptCancel
import com.lifolo.appointments.P2Cache
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.P2Js
import com.lifolo.appointments.P2Users
import com.lifolo.appointments.ProviderStore
import com.lifolo.auth.AdminService
import com.lifolo.common.Lang
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import com.lifolo.practice.Practice
import com.lifolo.practice.PracticeGate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * community.js professional badges: badge_apply (doctor / nurse / nutritionist / trainer / expert with registration
 * number, workplace and document photos; an approved badge sends a correction), admin_badges, admin_badge_resolve
 * (approve/reject with the verification ticks; a rejected badge takes the provider out of the lists and cancels the
 * future appointments; a re-approved one brings the listing back).
 */
@Component
class BadgeActions(
    private val jdbc: JdbcTemplate,
    private val users: P2Users,
    private val admins: AdminService,
    private val verify: VerifyDocs,
    private val quotas: QuotaService,
    private val cancel: ApptCancel,
    private val store: ProviderStore,
    private val cache: P2Cache,
    private val push: PushService,
    private val clock: P2Clock,
    private val gate: PracticeGate,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "badge_apply" to ActionHandler(::badgeApply),
        "admin_badges" to ActionHandler(::adminBadges),
        "admin_badge_resolve" to ActionHandler(::adminBadgeResolve),
    )

    companion object {
        private fun ms(v: Any?): Long? = (v as? Timestamp)?.time

        private fun texts(v: Any?): List<String> = ((v as? java.sql.Array)?.array as? Array<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        /** badges.verification {by, byEmail, at, checks} or null */
        fun verificationOf(row: Map<String, Any?>): Map<String, Any?>? =
            if (row["verified_at"] == null && row["verified_by"] == null) {
                null
            } else {
                linkedMapOf(
                    "by" to row["verified_by"],
                    "byEmail" to row["verified_by_email"],
                    "at" to ms(row["verified_at"]),
                    "checks" to texts(row["verification_checks"]),
                )
            }

        /** badges.pendingChange {type, regNo, workplace, name, at} or null */
        fun changeOf(row: Map<String, Any?>): Map<String, Any?>? =
            if (row["change_at"] == null) {
                null
            } else {
                linkedMapOf(
                    "type" to row["change_type"],
                    "regNo" to row["change_reg_no"],
                    "workplace" to row["change_workplace"],
                    "name" to row["change_name"],
                    "at" to ms(row["change_at"]),
                )
            }

        /** the Node-shaped badge for the verify.js helpers (type, system, docsHave, docsRequestedAt, verification) */
        fun listingOf(row: Map<String, Any?>, type: Any? = row["type"]): Map<String, Any?> = linkedMapOf(
            "type" to type,
            "system" to row["system"],
            "docsHave" to texts(row["docs_have"]),
            "docsRequestedAt" to ms(row["docs_requested_at"]),
            "verification" to verificationOf(row),
        )
    }

    private fun row(uid: String): Map<String, Any?>? = jdbc.queryForList("SELECT * FROM badges WHERE uid = ?", uid).firstOrNull()

    fun badgeApply(ctx: ActionContext): Any {
        val user = ctx.user
        val uid = user.id
        val b = ctx.body
        val type = b.string("type")
        if (type == null || type !in Rules.BADGES) throw ctx.err(400, "Choose a profession", "পেশা বেছে নিন")
        // PRACTICE.md: physio / psychologist each behind its switch; a doctor's medical system (registering body)
        gate.requireType(ctx, type)
        val sentSystem = b.string("system")
        if (sentSystem != null && !Practice.isSystem(sentSystem)) {
            throw ctx.err(400, "Choose the medical system", "চিকিৎসা পদ্ধতি বেছে নিন", "bad_system") // bn-ok
        }
        val regNo = Rules.cleanText(b.string("regNo"), 60)
        val workplace = Rules.cleanText(b.string("workplace"), 120)
        if (regNo == null) throw ctx.err(400, "Enter your registration / licence number", "রেজিস্ট্রেশন / লাইসেন্স নম্বর দিন")
        if (workplace == null) throw ctx.err(400, "Enter your workplace", "কর্মস্থল লিখুন")
        val cur = row(uid)
        if (cur != null && cur["status"] == "pending") throw ctx.err(409, "Your application is already under review", "আপনার আবেদন যাচাই চলছে")
        val now = clock.now()
        // an approved badge: a correction request — it stays approved and listed until an admin looks at it
        if (cur != null && cur["status"] == "approved") {
            val curType0 = cur["type"] as String
            val curSystem = Practice.systemOf(curType0, cur["system"] as String?)
            // PRACTICE.md: another medical system = another registering body — the badge goes back to verification
            if (curType0 == "doctor" && sentSystem != null && sentSystem != curSystem) {
                return reverify(ctx, cur, sentSystem, regNo, workplace)
            }
            // title_guard: the corrected name is the profile name — no "ডা." for anyone but an MBBS/BDS doctor
            gate.requireName(ctx, users.name(uid), curType0, cur["system"] as String?)
            quotas.use(ctx.lang, uid, "badge")
            // the profession cannot change (lists and services depend on it); only number, workplace and name
            val curType = cur["type"] as String
            val sent = verify.save(ctx.lang, "badge", uid, uid, mapOf("type" to curType, "system" to cur["system"]), b["docs"], false)
            val set = SqlSet()
                .value("change_type", curType)
                .value("change_reg_no", regNo)
                .value("change_workplace", workplace)
                .value("change_name", users.name(uid).take(80))
                .ms("change_at", now)
                .nul("docs_requested_at")
                .nul("docs_request_note")
            if (sent != null) set.ms("docs_sent_at", now)
            set.update(jdbc, "badges", "uid = ?", uid)
            gate.namesChanged()
            return linkedMapOf("ok" to true, "correction" to true)
        }
        // a doctor: allopathy unless another system is chosen (non-allopathic only while practice_systems is on)
        val system = if (type == "doctor") sentSystem ?: Practice.ALLOPATHY else null
        gate.requireSystem(ctx, system)
        val listing = mapOf("type" to type, "system" to system)
        // certificate photos (required for nurses and others; a doctor's number is checked on the BMDC site) — checked
        // before the quota is used
        verify.validate(ctx.lang, "badge", listing, b["docs"], true)
        // title_guard: the badge takes the profile name — no "ডা." for anyone but an MBBS/BDS doctor
        gate.requireName(ctx, users.name(uid), type, system)
        quotas.use(ctx.lang, uid, "badge")
        val docsHave = verify.save(ctx.lang, "badge", uid, uid, listing, b["docs"], true) ?: emptyList()
        // set() without merge: a new application replaces everything of the earlier one
        jdbc.update(
            """
            INSERT INTO badges (uid, docs_have, type, system, reg_no, workplace, name, email, status, created_at)
            VALUES (?, ?::text[], ?, ?, ?, ?, ?, ?, 'pending', ?)
            ON CONFLICT (uid) DO UPDATE SET docs_have = EXCLUDED.docs_have, type = EXCLUDED.type, system = EXCLUDED.system,
              reverify_from = NULL, reg_no = EXCLUDED.reg_no,
              workplace = EXCLUDED.workplace, name = EXCLUDED.name, email = EXCLUDED.email, status = 'pending',
              created_at = EXCLUDED.created_at, status_reason = NULL, reviewed_at = NULL, reviewed_by = NULL, verified_by = NULL,
              verified_by_email = NULL, verified_at = NULL, verification_checks = NULL, docs_sent_at = NULL, docs_requested_at = NULL,
              docs_request_note = NULL, change_type = NULL, change_reg_no = NULL, change_workplace = NULL, change_name = NULL,
              change_at = NULL
            """.trimIndent(),
            uid, P2Js.pgTexts(docsHave), type, system, regNo, workplace, users.name(uid).take(80), user.email, Sql.ts(now),
        )
        gate.namesChanged()
        return mapOf("ok" to true)
    }

    /**
     * PRACTICE.md: an approved doctor changes the medical system — new registering body, so the badge is verified
     * again: `pending` with the new system, number, workplace and certificate (required for non-allopathic), the old
     * verification gone, `reverify_from` = the earlier system (the admin review shows it). While pending the doctor is
     * out of the lists (they need an approved badge); open appointments stay. The listing's system changes on approval.
     */
    private fun reverify(ctx: ActionContext, cur: Map<String, Any?>, system: String, regNo: String, workplace: String): Any {
        val uid = ctx.user.id
        gate.requireSystem(ctx, system)
        val listing = mapOf("type" to "doctor", "system" to system)
        verify.validate(ctx.lang, "badge", listing, ctx.body["docs"], true)
        // title_guard: an MBBS doctor turning homeopath (…) cannot keep "ডা." in the name
        gate.requireName(ctx, users.name(uid), "doctor", system)
        quotas.use(ctx.lang, uid, "badge")
        val sent = verify.save(ctx.lang, "badge", uid, uid, listing, ctx.body["docs"], false)
        val now = clock.now()
        val set = SqlSet()
            .value("status", "pending")
            .value("system", system)
            .value("reverify_from", Practice.systemOf("doctor", cur["system"] as String?))
            .value("reg_no", regNo)
            .value("workplace", workplace)
            .value("name", users.name(uid).take(80))
            .ms("created_at", now)
            .nul("status_reason").nul("reviewed_at").nul("reviewed_by")
            .nul("verified_by").nul("verified_by_email").nul("verified_at").nul("verification_checks")
            .nul("change_type").nul("change_reg_no").nul("change_workplace").nul("change_name").nul("change_at")
            .nul("docs_requested_at").nul("docs_request_note")
        if (sent != null) set.ms("docs_sent_at", now)
        set.update(jdbc, "badges", "uid = ?", uid)
        cache.clearProviders()
        gate.namesChanged()
        return linkedMapOf("ok" to true, "reverify" to true)
    }

    fun adminBadges(ctx: ActionContext): Any {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val b = ctx.body
        // app: pending; web panel: approved or rejected too
        val status = b.string("status")?.takeIf { it in listOf("pending", "approved", "rejected") } ?: "pending"
        val panel = b.isTrue("panel")
        val rows = jdbc.queryForList("SELECT * FROM badges WHERE status = ? ORDER BY created_at, uid LIMIT 300", status)
        // corrections of approved badges in the pending list (web panel only — in the old app "reject" removed the badge)
        val changes = if (status == "pending" && panel) {
            jdbc.queryForList("SELECT * FROM badges WHERE change_at IS NOT NULL ORDER BY change_at, uid LIMIT 100")
        } else {
            emptyList()
        }
        return mapOf(
            "items" to (rows + changes).map { d ->
                val change = changeOf(d)
                val m = LinkedHashMap<String, Any?>()
                // documents and the checklist (web panel)
                if (panel && (d["status"] != "approved" || change != null)) {
                    m["verify"] = try {
                        verify.panelInfo("badge", d["uid"] as String, listingOf(d, change?.get("type") ?: d["type"]))
                    } catch (e: Exception) {
                        null
                    }
                }
                m["verification"] = verificationOf(d)
                m["change"] = change
                m["status"] = d["status"]
                m["reviewedAt"] = ms(d["reviewed_at"])
                m["uid"] = d["uid"]
                m["type"] = d["type"]
                // PRACTICE.md: the system and its registering body (+ "was …" when it is a system change)
                val sys = Practice.systemOf(d["type"] as String?, d["system"] as String?)
                m.putAll(Practice.labels(d["type"] as String?, d["system"] as String?))
                m["body"] = sys?.let { Practice.SYSTEM_INFO[it]?.bodyBn }
                m["degreeHint"] = sys?.let { Practice.SYSTEM_INFO[it]?.degrees }
                m["reverifyFrom"] = d["reverify_from"]
                m["regNo"] = d["reg_no"]
                m["workplace"] = d["workplace"]
                m["name"] = d["name"]
                m["email"] = d["email"]
                m["createdAt"] = ms(d["created_at"])
                m
            },
        )
    }

    private fun setVerification(set: SqlSet, v: Map<String, Any?>) {
        set.value("verified_by", v["by"] as? String)
            .value("verified_by_email", v["byEmail"] as? String)
            .ms("verified_at", (v["at"] as? Number)?.toLong())
        @Suppress("UNCHECKED_CAST")
        set.textArray("verification_checks", (v["checks"] as? List<Any?>)?.map { it.toString() } ?: emptyList())
    }

    fun adminBadgeResolve(ctx: ActionContext): Any {
        val admin = ctx.user
        if (!admins.isAdmin(admin)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val b = ctx.body
        val uid = b.string("uid")
        if (!Rules.isValidId(uid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        uid!!
        // not one's own badge — another admin looks at it
        if (uid == admin.uid) throw ctx.err(403, "Another admin must review your own badge", "নিজের ব্যাজ অন্য অ্যাডমিন দেখবেন")
        val bs = row(uid) ?: throw ctx.err(404, "Application not found", "আবেদনটা পাওয়া যায়নি")
        val approve = b.isTrue("approve")
        val now = clock.now()
        // a correction: approved → the new details (also the listed name); otherwise only the request goes — the badge stays
        if (b.isTrue("correction")) {
            val ch = changeOf(bs) ?: return linkedMapOf("ok" to true, "unchanged" to true)
            val chType = ch["type"] as String
            if (approve) {
                val verification = verify.assertChecks(
                    ctx.lang, admin, "badge", linkedMapOf("type" to chType, "system" to bs["system"], "docsHave" to texts(bs["docs_have"])), b["checks"],
                )
                val set = SqlSet()
                    .value("type", chType)
                    .value("reg_no", ch["regNo"])
                    .value("workplace", ch["workplace"])
                    .value("name", ch["name"] ?: "")
                    .nul("change_type").nul("change_reg_no").nul("change_workplace").nul("change_name").nul("change_at")
                    .ms("reviewed_at", now)
                    .value("reviewed_by", admin.uid)
                    .nul("docs_requested_at")
                    .nul("docs_request_note")
                setVerification(set, verification)
                set.update(jdbc, "badges", "uid = ?", uid)
                // profession changed (e.g. doctor → nurse): out of the old profession's list; they switch it on again
                val p = store.load(uid)
                if (p != null) {
                    val typeChanged = p.type.isNotEmpty() && p.type != chType
                    try {
                        jdbc.update(
                            "UPDATE providers SET name = ?" + (if (typeChanged) ", listed = FALSE, active = FALSE, appointments = FALSE" else "") + " WHERE uid = ?",
                            ch["name"] ?: "", uid,
                        )
                    } catch (e: Exception) {
                        // Node ignores a failed update here
                    }
                    gate.namesChanged()
                    if (typeChanged) cancel.cancelStale(uid, { false }, "The doctor/nurse is no longer listed", "ডাক্তার/নার্স এখন তালিকায় নেই")
                }
            } else {
                jdbc.update(
                    """
                    UPDATE badges SET change_type = NULL, change_reg_no = NULL, change_workplace = NULL, change_name = NULL, change_at = NULL,
                      docs_requested_at = NULL, docs_request_note = NULL WHERE uid = ?
                    """.trimIndent(),
                    uid,
                )
            }
            push.pushUsers(listOf(uid)) { _, lang ->
                PushText(
                    if (approve) {
                        Lang.pick(lang, "Your badge details were updated", "আপনার ব্যাজের তথ্য সংশোধন হয়েছে")
                    } else {
                        Lang.pick(lang, "Badge correction not accepted", "ব্যাজের তথ্য সংশোধনের অনুরোধ গ্রহণ হয়নি")
                    },
                    "",
                    mapOf("type" to "badge"),
                )
            }
            return mapOf("ok" to true)
        }
        cache.clearProviders()
        // from pending or rejected to approved: every check ticked in the panel
        val verification = if (approve && bs["status"] != "approved") {
            verify.assertChecks(ctx.lang, admin, "badge", listingOf(bs), b["checks"])
        } else {
            null
        }
        val p = store.load(uid)
        if (!approve) {
            // a rejected badge also leaves the doctor/nurse lists (new and old apps); future appointments are cancelled
            if (p != null) {
                // remember whether it was listed (it only comes back on approval then)
                val set = SqlSet().value("active", false).value("listed", false).value("badge_rejected", true)
                if (!p.badgeRejected) set.value("listed_before", p.listed).value("active_before", p.active)
                set.update(jdbc, "providers", "uid = ?", uid)
                cancel.cancelStale(uid, { false }, "The doctor/nurse is no longer listed", "ডাক্তার/নার্স এখন তালিকায় নেই")
            }
        } else if (p != null && p.badgeRejected) {
            // rejected and out of the list before: back on approval — same profession, listed before, not turned off by
            // an admin. A changed profession (doctor rejected → nurse approved) does not return to the old list.
            val same = p.type == bs["type"] && !p.adminSuspended
            val listed = same && p.listedBefore != false
            val set = SqlSet()
                .value("listed", listed)
                .value("active", listed && p.activeBefore != false && p.appointments)
                .value("badge_rejected", false)
                .nul("listed_before")
                .nul("active_before")
            if (!same) set.value("appointments", false)
            set.update(jdbc, "providers", "uid = ?", uid)
        }
        val set = SqlSet()
        // a rejected badge also drops a pending correction (otherwise no new application would be possible)
        if (!approve) set.nul("change_type").nul("change_reg_no").nul("change_workplace").nul("change_name").nul("change_at")
        set.value("status", if (approve) "approved" else "rejected")
            .ms("reviewed_at", now)
            .value("reviewed_by", admin.uid)
        if (verification != null) {
            setVerification(set, verification)
            set.nul("docs_requested_at").nul("docs_request_note")
        }
        // the reason of a rejection (the user sees it)
        val reason = Rules.cleanText(b.string("reason"), 200)
        if (!approve && reason != null) set.value("status_reason", reason)
        if (approve) set.nul("status_reason")
        set.nul("reverify_from")
        set.update(jdbc, "badges", "uid = ?", uid)
        if (approve && bs["type"] == "doctor" && p != null) syncSystem(uid, p, Practice.systemOf("doctor", bs["system"] as String?)!!)
        return mapOf("ok" to true)
    }

    /**
     * PRACTICE.md: an approved doctor's listing carries the badge's system. A changed system (re-verification) takes
     * the listing out of the lists — the specialties belong to another system's catalog — and the future
     * appointments booked under the old system are cancelled; the doctor saves the chambers again.
     */
    private fun syncSystem(uid: String, p: com.lifolo.appointments.ProviderDoc, system: String) {
        val before = Practice.systemOf(p.type, p.system)
        if (p.type == "doctor" && before == system && p.system != null) return
        val changed = p.type == "doctor" && before != system
        jdbc.update(
            "UPDATE providers SET system = ?" + (if (changed) ", specialties = '{}', listed = FALSE, active = FALSE, appointments = FALSE" else "") + " WHERE uid = ?",
            system, uid,
        )
        cache.clearProviders()
        if (changed) {
            cancel.cancelStale(uid, { false }, "The doctor changed the medical system", "ডাক্তার চিকিৎসা পদ্ধতি বদলেছেন") // bn-ok
        }
    }
}
