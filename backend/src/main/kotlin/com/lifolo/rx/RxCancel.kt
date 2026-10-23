package com.lifolo.rx

import com.lifolo.common.Js
import com.lifolo.common.action.ActionContext

/**
 * Why the doctor cancelled a prescription (`rx_cancel {id, reason, note?}`, backend/docs/RX-STATE.md "Cancellation
 * reason"). Stored as a key in `prescriptions.cancel_reason` (+ `cancel_note`, V684); the patient (and a caregiver acting
 * for the patient) sees it in `state.cancelReason` / `state.cancelNote` and on the timeline's `cancelled` step. Never on
 * the public /rx/{code} page, the pharmacy/lab code views or any push — the note may hold medical details.
 * The reason is required (owner 2026-10: no old app versions in use — a client without one gets a plain 400).
 */
object RxCancel {
    const val WRONG_MEDICINE = "wrong_medicine"
    const val WRONG_PATIENT = "wrong_patient"
    const val REPLACED = "replaced"
    const val PATIENT_REPORTED = "patient_reported"
    const val OTHER = "other"

    /** what the doctor picks from, in this order (the apps show ভুল ওষুধ/মাত্রা, ভুল রোগী, নতুন প্রেসক্রিপশন দিয়েছি, রোগী জানিয়েছেন সমস্যা, অন্য কারণ) */
    val REASONS = listOf(WRONG_MEDICINE, WRONG_PATIENT, REPLACED, PATIENT_REPORTED, OTHER)

    const val NOTE_MIN = 3
    const val NOTE_MAX = 200

    data class Reason(val reason: String, val note: String?)

    /**
     * a stored value → the key the apps translate; NULL (a cancellation made by the previous release during a
     * blue/green switch, or before V684) → null: the apps show "cancelled" without a reason
     */
    fun keyOf(stored: String?): String? = stored?.takeIf { it in REASONS }

    /**
     * The request's reason (one of [REASONS], required) and note: "other" needs [NOTE_MIN]–[NOTE_MAX] characters, the
     * others take an optional note of up to [NOTE_MAX].
     */
    fun parse(ctx: ActionContext): Reason {
        val b = ctx.body
        val reason = Js.string(b["reason"])?.let { RxRules.jsTrim(it) }?.takeIf { it in REASONS }
            ?: throw ctx.err(400, "Choose why you are cancelling", "বাতিলের কারণ বেছে নিন", "cancel_reason") // bn-ok
        val noteNode = b["note"]
        if (noteNode != null && !noteNode.isNull && !noteNode.isTextual) throw noteErr(ctx)
        val note = RxRules.jsTrim((Js.string(noteNode) ?: "").replace("\r\n", "\n")).takeIf { it.isNotEmpty() }
        if (note != null && note.length > NOTE_MAX) throw noteErr(ctx)
        if (reason == OTHER && (note == null || note.length < NOTE_MIN)) throw noteErr(ctx)
        return Reason(reason, note)
    }

    private fun noteErr(ctx: ActionContext) =
        ctx.err(400, "Write the reason in $NOTE_MIN–$NOTE_MAX characters", "কারণটা ৩–২০০ অক্ষরে লিখুন", "cancel_note") // bn-ok
}
