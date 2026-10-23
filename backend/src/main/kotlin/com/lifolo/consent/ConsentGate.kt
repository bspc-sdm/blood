package com.lifolo.consent

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.ApiException
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionGuard
import com.lifolo.common.action.ActionPolicy
import com.lifolo.features.Features
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The consent gate (backend/docs/CONSENT.md), run by the dispatcher before every action. While `health_consent` is on
 * for the patient, a patient-initiated entry of health data ([GATED]) without a current consent for both scopes is
 * refused with **428** `consent_required` `{scopes, version, caregiver}` — the apps show the consent screen and retry.
 *
 *  - Only what the patient (or a caregiver for them) starts. Records a provider makes (a prescription, a lab report)
 *    are never gated — they have a record-keeping duty; the patient is asked when they next open a health section.
 *  - Emergency features ([NEVER_GATED]) are never behind consent: blood requests, ambulances, calls, listings.
 *  - A caregiver acting for the patient gets `caregiver: true` and "রোগীকে নিজে একবার সম্মতি দিতে হবে": only the
 *    patient can consent.
 */
@Component
class ConsentGate(
    private val consents: Consents,
    private val text: ConsentText,
    private val features: Features,
    private val jdbc: JdbcTemplate,
) : ActionGuard {
    init {
        // a programming slip must never put an emergency action behind consent
        check(GATED.intersect(NEVER_GATED).isEmpty()) { "emergency actions in the consent gate: ${GATED.intersect(NEVER_GATED)}" }
        check(GATED.none { it in ActionPolicy.PUBLIC_ACTIONS }) { "a public action in the consent gate" }
    }

    override fun check(ctx: ActionContext) {
        val uid = ctx.user.uid ?: return
        if (ctx.user.guest || ctx.action !in GATED) return
        if (!features.isOn(uid, Features.HEALTH_CONSENT)) return
        if (!entersHealthData(ctx, uid)) return
        val missing = consents.missing(uid, text.version)
        if (missing.isEmpty()) return
        val caregiver = ctx.user.actingBy != null
        throw ApiException(
            STATUS,
            text.line(ctx.lang, if (caregiver) "caregiver" else "required"),
            CODE,
            linkedMapOf("scopes" to missing, "version" to text.version, "caregiver" to caregiver),
        )
    }

    /** does this call of a [GATED] action enter (new or changed) health data? */
    fun entersHealthData(ctx: ActionContext, uid: String): Boolean {
        val b = ctx.body
        return when (ctx.action) {
            // adding a doctor's prescription to my account (and its reports with it)
            "rx_claim" -> true
            // blood group, weight, last donation date — only a real change to a value (the forms resend unchanged
            // fields with every save; clearing a value is not an entry)
            "profile_update" -> profileChange(b["set"], uid)
            "donation_date_set" -> b.int("millis")?.let { it != storedLastDonation(uid) } ?: false
            // an appointment with a psychologist (the request carries the reason for the visit)
            "book" -> providerType(b.string("providerUid")) == "psychologist"
            // sharing my health data with a caregiver
            "care_invite" -> true
            "care_share_psych" -> b.isTrue("share")
            // an online (home delivery) order that sends a prescription with my address and phone
            "rx_send_pharmacy" -> b.string("mode") == "online"
            // home sample collection (address, phone, time)
            "rx_send_lab" -> b["home"].let { it != null && (it.isObject || it.isArray) }
            "lab_home_change" -> true
            else -> false
        }
    }

    private fun profileChange(set: JsonNode?, uid: String): Boolean {
        if (set == null || !set.isObject) return false
        val asked = PROFILE_FIELDS.keys.filter { f -> set.get(f).let { it != null && !it.isNull && !(it.isTextual && it.textValue().isEmpty()) } }
        if (asked.isEmpty()) return false
        val row = jdbc.queryForList("SELECT blood_group, weight_kg, last_donation_ms FROM user_profiles WHERE uid = ?", uid).firstOrNull()
            ?: return true
        return asked.any { f ->
            val sent = set.get(f)
            when (val stored = row[PROFILE_FIELDS.getValue(f)]) {
                null -> true
                is Number -> !sent.isNumber || sent.doubleValue() != stored.toDouble()
                else -> !sent.isTextual || sent.textValue() != stored.toString()
            }
        }
    }

    private fun storedLastDonation(uid: String): Long? =
        jdbc.queryForList("SELECT last_donation_ms FROM user_profiles WHERE uid = ?", uid).firstOrNull()
            ?.get("last_donation_ms")?.let { (it as Number).toLong() }

    private fun providerType(uid: String?): String? = if (uid == null) {
        null
    } else {
        jdbc.queryForList("SELECT type FROM providers WHERE uid = ?", String::class.java, uid).firstOrNull()
    }

    companion object {
        const val STATUS = 428
        const val CODE = "consent_required"

        /** the actions that can enter health data (each call is then checked by [entersHealthData]) */
        val GATED: Set<String> = setOf(
            "rx_claim", "profile_update", "donation_date_set", "book", "care_invite", "care_share_psych",
            "rx_send_pharmacy", "rx_send_lab", "lab_home_change",
        )

        /**
         * Never behind consent, whatever the switch says (documented in CONSENT.md, checked at start-up and in
         * ConsentIT): blood requests and responses, emergency appeals, ambulance calls, in-app calls, listings and
         * every guest action.
         */
        val NEVER_GATED: Set<String> = setOf(
            // blood requests (also the HTTP endpoints /api/respond, /api/close-request, /api/edit-request, /api/notify-request)
            "request_create", "request_delete", "request_reopen", "response_update", "confirm_donor", "blood_request_get",
            "blood_requests_near", "blood_requests_mine", "blood_contact_get", "blood_responses_list",
            // emergency appeals
            "emergency_respond", "emergency_donated", "emergencies", "emergencies_active",
            // ambulances
            "ambreq_create", "ambreq_status", "ambreq_cancel", "ambreq_agree", "ambreq_budget", "ambreq_replace", "ambreq_step",
            "ambulance_called", "ambulances", "amb_req_mine",
            // calls (an emergency call must always go through)
            "call_start", "call_accept", "call_end", "call_decline", "call_cancel", "call_state", "call_config",
            // listings
            "providers", "home_providers", "hospitals", "pharmacies", "bloodbanks", "labs", "bloodbank_called",
            "pharmacy_called", "home_called", "lab_prices_public", "drug_search", "catalog_get", "guest_requests",
        )

        /** the profile fields that are health data → their column */
        private val PROFILE_FIELDS = linkedMapOf(
            "bloodGroup" to "blood_group",
            "weightKg" to "weight_kg",
            "lastDonationMillis" to "last_donation_ms",
        )

        /** `consent_*` with a caregiver's `asUid`: only the patient consents */
        fun selfOnly(ctx: ActionContext, text: ConsentText): ApiException =
            ApiException(403, text.line(ctx.lang, "caregiver"), "consent_self_only")
    }
}
