package com.lifolo.contracts

/*
 * Contracts owned by P2 (doctors, community content & caregivers: appointments, homecare, care, content).
 * Other packages code against these interfaces; the baseline implementations in DoctorsBaseline.kt work on the
 * phase-1 tables so the other packages can test now. P2 replaces them (delete DoctorsBaseline.kt and add its own
 * beans) — the interface is the contract; changing it needs the consumers' agreement (PHASE2-PLAN.md).
 */

/**
 * community-db.js approvedBadge. Consumers: P3 (doctor/assistant checks for prescriptions and lab reviews), P6.
 */
fun interface Badges {
    /** the badge type ("doctor", "nurse", "nutritionist", "trainer", "expert") when the badge is approved, else null */
    fun approvedBadge(uid: String): String?
}

/**
 * providers.assistantEmails (prescriptions.js rx_team / rx_team_set / rx_assisting / actingFor, queue.js).
 * Table `provider_assistants`; emails are stored lower case. Consumers: P3 (rx_team_set writes the list, rx_* actions
 * check "acting for a doctor"), P2 queue.
 */
interface ProviderAssistants {
    /** the doctor's assistant emails, in the order they were set */
    fun emails(doctorUid: String): List<String>

    /**
     * actingFor's check: [email] (already lower case; empty = not verified) is on the doctor's list. Node compares
     * the token email only when `email_verified` — callers pass "" for an unverified email.
     */
    fun isAssistant(doctorUid: String, email: String): Boolean

    /** rx_assisting: doctors that added this email (Node `array-contains`, limit 10) */
    fun doctorsFor(email: String, limit: Int = 10): List<String>

    /**
     * rx_team_set: replaces the whole list (≤ 5, validated by the caller). Node `set({assistantEmails}, {merge:true})`
     * also creates the provider document when missing — here the `providers` row must exist (FK); returns false
     * when there is no provider row.
     */
    fun set(doctorUid: String, emails: List<String>): Boolean
}

/** one session of a chamber: weekdays 0–6 (Sun–Sat; empty = every day), "HH:MM" */
data class ChamberTime(val days: List<Int>, val start: String, val end: String)

/**
 * One entry of `providers.chambers[]` (care.js chambersOf), index = position (`chamberIndex` in the API).
 * [times] are the sessions (`chamberTimes`: the chamber's `times[]`, or one session from days/start/end).
 */
data class Chamber(
    val index: Int,
    val hospitalId: String?,
    val hospitalName: String,
    val hospitalNameBn: String,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val room: String,
    val schedule: String,
    val noFee: Boolean,
    val fee: String,
    val appointments: Boolean,
    val serialPhone: String,
    val times: List<ChamberTime>,
)

/**
 * care.js chamber helpers. Consumers: P3 (rx_me chamber names, follow-up booking date in rx_create, review
 * flow next open day), P6 (admin views).
 */
interface Chambers {
    /** care.js chambersOf: the provider's chambers in index order (empty when no provider) */
    fun chambersOf(providerUid: String): List<Chamber>

    /** care.js takesAppointments: the provider's main switch (`appointments`; legacy documents: `active`) */
    fun takesAppointments(providerUid: String): Boolean

    /**
     * care.js chamberOpenOn: the chamber sits on this date ([dateUtcMidnightMs] = UTC midnight of the Dhaka date,
     * like `appointments.date`): any session whose days are empty or include that weekday.
     */
    fun chamberOpenOn(chamber: Chamber?, dateUtcMidnightMs: Long): Boolean {
        if (chamber == null) return false
        val day = Math.floorMod(Math.floorDiv(dateUtcMidnightMs, 86_400_000L) + 4, 7L).toInt()
        val times = chamber.times.ifEmpty { listOf(ChamberTime(emptyList(), "", "")) }
        return times.any { it.days.isEmpty() || day in it.days }
    }
}

/** hospitals.js hospital(d) — a DGHS directory entry (`dghs_{n}`) or a user-added hospital */
data class Hospital(
    val id: String,
    val nameEn: String,
    val nameBn: String,
    val type: String,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val address: String,
    val phone: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "nameEn" to nameEn, "nameBn" to nameBn, "type" to type, "district" to district,
        "upazilaId" to upazilaId, "unionId" to unionId, "address" to address, "phone" to phone,
    )
}

/**
 * hospitals.js + hospital-dir.js: the bundled DGHS directory (`data/hospital-dir.json`, 17k entries) plus hospitals
 * added from the app (table `hospitals`). Consumers: P1 (admin_emergency_create / admin_hospitals), P3 (lab_save
 * facilityId), P5 (ambulance destinations, or read the JSON directly).
 */
interface HospitalDirectory {
    /** findHospital: `dghs_…` from the bundled directory, else a user-added one that is not removed/hidden; null if none */
    fun findHospital(id: String?): Hospital?

    /** approvedIn: the district's DGHS entries followed by the user-added ones (unsorted — `hospitals` sorts by nameEn) */
    fun approvedIn(district: String): List<Hospital>
}

/**
 * apptcancel.js + homecare.js blockHomeCare. Consumers: P6 (admin_user_action, moderation removeTarget for
 * `provider`/`homecare` reports, account deletion).
 */
interface ProviderSuspension {
    /**
     * suspendProvider(uid, reason): reason "admin" | "report" | "deletion" | "account". Unlists chambers and home care
     * (`admin_suspended`, `suspend_reason`, `listed_before_suspend`, `listed = active = false`,
     * `home_visit_enabled = false`) and cancels future pending/confirmed appointments with a push to each patient
     * ("Appointment cancelled" · "The doctor/nurse is not available now"). Already suspended: only upgrades the
     * reason to "report" and returns false. Returns true when it suspended.
     */
    fun suspendProvider(uid: String, reason: String): Boolean

    /** liftProvider: lists again (if it was listed before the suspension); false when not suspended */
    fun liftProvider(uid: String): Boolean

    /**
     * cancelStale(providerUid, () => false, reason): cancels all future pending/confirmed appointments
     * (`cancelled_by = 'system'`, provider note = [reasonBn]), forgets the queue cache and pushes each patient
     * ("Appointment cancelled" · name · date · reason). Returns how many were cancelled. (P2 keeps its own variant with
     * a `keep` filter for chamber edits.)
     */
    fun cancelStale(providerUid: String, reasonEn: String, reasonBn: String): Int

    /** homecare.js blockHomeCare: home care off and blocked (the provider cannot switch it on again) */
    fun blockHomeCare(uid: String)
}

/**
 * Removing reported community content (moderation.js removeTarget for questions, answers, tips).
 * Consumer: P6 admin_report_resolve.
 */
interface ContentModeration {
    /** a question with all its answers */
    fun removeQuestion(id: String)

    /** one answer; the question's answer count goes down */
    fun removeAnswer(questionId: String, answerId: String)

    fun removeTip(id: String)
}
