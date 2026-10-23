package com.lifolo.care

import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionPolicy
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * careaccess.js actAs: with `body.asUid` a caregiver acts for a patient — only for [ActionPolicy.CARE_ACTIONS]
 * and only while the care link is active. Returns the patient as the actor (actingBy = caregiver).
 */
@Service
class CareAccess(private val jdbc: JdbcTemplate) {
    fun actAs(body: Body, user: AuthUser, lang: String?): AuthUser {
        val asNode = body["asUid"]
        if (asNode == null || asNode.isNull) return user
        val asUid = if (asNode.isTextual) asNode.textValue() else null
        if (asUid == "" || (asUid != null && asUid == user.uid)) return user
        // consent (backend/docs/CONSENT.md): only the patient gives or withdraws it
        if (body.action.startsWith("consent_")) {
            throw ApiException(403, Lang.tr(lang, "The patient has to give consent themselves once", "রোগীকে নিজে একবার সম্মতি দিতে হবে"), "consent_self_only") // bn-ok
        }
        if (!Rules.isValidId(asUid) || body.action !in ActionPolicy.CARE_ACTIONS) {
            throw ApiException(403, Lang.tr(lang, "A caregiver can't do this for the patient", "কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না"))
        }
        val active = jdbc.queryForList(
            "SELECT 1 FROM care_links WHERE patient_uid = ? AND caretaker_uid = ? AND status = 'active'",
            asUid, user.uid,
        ).isNotEmpty()
        // a link across the demo/real worlds never counts (demo accounts are public)
        if (!active || !DemoWorld.sameWorld(asUid, user.uid)) {
            throw ApiException(403, Lang.tr(lang, "You are no longer this person's caregiver", "আপনি আর এই মানুষের কেয়ারগিভার নন"), "care_revoked")
        }
        // the patient switched the account off (account_deactivate): the link is paused until they turn it on again
        val paused = jdbc.queryForList("SELECT 1 FROM accounts WHERE uid = ? AND deactivated_at IS NOT NULL", asUid).isNotEmpty()
        if (paused) {
            throw ApiException(
                403,
                Lang.pick(lang, "This person's account is temporarily deactivated", "এই মানুষের অ্যাকাউন্ট এখন সাময়িক বন্ধ আছে"), // bn-ok
                "care_paused",
            )
        }
        return AuthUser(uid = asUid, actingBy = user.uid, email = "", emailVerified = false)
    }
}
