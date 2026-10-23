package com.lifolo.practice

import com.lifolo.auth.AdminService
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * backend/docs/PRACTICE.md actions:
 *  - `care_share_psych {caretakerUid, share}` — the patient (never a caregiver: not in CARE_ACTIONS) lets one active
 *    caregiver see their psychologist appointments, session notes and receipts, or stops it. Default off.
 *  - `admin_title_names` (admins) — "‘ডা.’ লেখা নাম": providers who may not carry "ডা."/"Dr." but whose name starts with
 *    it (§6, title_guard), whether the switch is on or not — so the owner can ask them to fix it. Shown as stored.
 */
@Component
class PracticeActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val titles: TitleGuard,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "care_share_psych" to ActionHandler(::careSharePsych),
        "admin_title_names" to ActionHandler(::adminTitleNames),
    )

    fun adminTitleNames(ctx: ActionContext): Any {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val items = titles.affected()
        return linkedMapOf(
            "title" to TitleRules.ADMIN_TITLE_BN,
            "items" to items,
            "count" to items.size,
            // real accounts (the demo world is reseeded)
            "real" to items.count { it["demo"] != true },
            "message" to TitleRules.ERROR_BN,
            "hint" to TitleRules.HINT_BN,
        )
    }

    fun careSharePsych(ctx: ActionContext): Any {
        // own setting only (a caregiver acting through asUid never gets here — careaccess refuses it — checked again)
        if (ctx.user.patientSideOnly) throw ctx.err(403, "A caregiver can't do this for the patient", "কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না")
        val uid = ctx.user.id
        val carer = ctx.body.string("caretakerUid")
        if (!Rules.isValidId(carer)) throw ctx.notFound()
        val share = ctx.body.isTrue("share")
        val n = jdbc.update(
            "UPDATE care_links SET share_psych = ? WHERE patient_uid = ? AND caretaker_uid = ? AND status = 'active'",
            share, uid, carer,
        )
        if (n == 0) throw ctx.notFound()
        return linkedMapOf("ok" to true, "sharePsych" to share)
    }
}
