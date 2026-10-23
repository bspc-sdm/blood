package com.lifolo.practice

import com.lifolo.auth.AuthUser
import com.lifolo.common.action.ActionContext
import com.lifolo.features.Features
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Feature switches of this package (backend/docs/FEATURES.md): `practice_systems` (চিকিৎসা পদ্ধতি), `pro_physio`,
 * `pro_psychologist`.
 *  - practice_systems off for a viewer: no system field/label/filter, non-allopathic doctors are not listed, and nobody
 *    can register (badge_apply) as a non-allopathic doctor. Stored prescription rules stay enforced either way.
 *  - pro_physio / pro_psychologist off: that profession's actions answer 404 `feature_off` (badge_apply, provider_save,
 *    providers/home_providers of that type, book, rx writing) and the profession is never listed.
 */
@Component
class PracticeGate(private val features: Features) {
    fun systemsOn(uid: String?): Boolean = features.isOn(uid, Features.PRACTICE_SYSTEMS)

    /** the person holding the phone (also when a caregiver acts for a patient) — whose switches count */
    fun viewer(ctx: ActionContext): String? = ctx.caller.uid ?: ctx.user.uid

    fun systemsOn(ctx: ActionContext): Boolean = systemsOn(viewer(ctx))

    /** the feature key of a profession, null when the profession is not gated */
    fun keyOf(type: String?): String? = when (type) {
        Practice.PHYSIO -> Features.PRO_PHYSIO
        Practice.PSYCHOLOGIST -> Features.PRO_PSYCHOLOGIST
        else -> null
    }

    fun typeOn(uid: String?, type: String?): Boolean = keyOf(type)?.let { features.isOn(uid, it) } ?: true

    /** 404 feature_off when [type] is a gated profession that is off for the caller */
    fun requireType(ctx: ActionContext, type: String?) {
        keyOf(type)?.let { features.require(ctx, it) }
    }

    /** 404 feature_off when a non-allopathic [system] is chosen while practice_systems is off for the caller */
    fun requireSystem(ctx: ActionContext, system: String?) {
        if (system != null && system != Practice.ALLOPATHY) features.require(ctx, Features.PRACTICE_SYSTEMS)
    }
}

/**
 * Psychologist privacy (PRACTICE.md §2): a caregiver acting for the patient (`asUid`) never sees the patient's
 * psychologist appointments, session notes or their receipts — unless the patient switched sharing on for that
 * caregiver (`care_links.share_psych`, default off; `care_share_psych`).
 */
@Component
class PracticePrivacy(private val jdbc: JdbcTemplate) {
    fun shared(patientUid: String, caretakerUid: String): Boolean =
        jdbc.queryForList(
            "SELECT share_psych FROM care_links WHERE patient_uid = ? AND caretaker_uid = ? AND status = 'active'",
            Boolean::class.java, patientUid, caretakerUid,
        ).firstOrNull() == true

    /** true when [user] is a caregiver acting for the patient who did not share private appointments with them */
    fun hidesPrivate(user: AuthUser): Boolean {
        val by = user.actingBy ?: return false
        val patient = user.uid ?: return true
        return !shared(patient, by)
    }

    /** [user] may not see an item of profession [type] (a psychologist's) */
    fun hidden(user: AuthUser, type: String?): Boolean = Practice.isPrivate(type) && hidesPrivate(user)
}
