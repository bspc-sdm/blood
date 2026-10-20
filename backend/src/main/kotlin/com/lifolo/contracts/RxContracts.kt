package com.lifolo.contracts

/*
 * Contracts owned by P3 (prescriptions & labs). Implementations: [Prescriptions] = com.lifolo.rx.PrescriptionStore,
 * [PatientHome] = com.lifolo.lab.LabService. Consumers: P4 (pharmacy dispensing, shop books), P1 (/api/share?rx=, /rx/{code}).
 */

/** prescriptions.medicines[i] — [i] is the index used by dispensing and sales ("i" in Node) */
data class RxMedicine(
    val i: Int,
    val name: String,
    val form: String,
    val strength: String,
    val dose: String,
    val timing: String,
    val days: Int,
    val note: String,
)

/** prescriptions.dispensed[] {by, name, at, items[{i, qty}]} (oldest first) */
data class RxDispensing(val by: String, val name: String, val at: Long, val items: List<Pair<Int, Int>>) {
    fun itemsJson(): List<Map<String, Any?>> = items.map { (i, qty) -> linkedMapOf("i" to i, "qty" to qty) }
}

/**
 * One prescription as the Node document (`prescriptions/{id}`). Times are epoch-ms; [followUpDate],
 * [followUpBookDate], [refillAt] are UTC-midnight ms of a Dhaka date (prescriptions.js dhakaToday) or null.
 * [doctor] = {name, degrees, specialties, regNo, chamber: {name, address, phone} | null}; [patient] = {name, age, gender};
 * [findings] = {bp, pulse, temp, weight, spo2, other}.
 */
data class Prescription(
    val id: String,
    val code: String,
    val status: String,
    val createdAt: Long,
    val doctorUid: String,
    val patientUid: String?,
    val rxPatientId: String?,
    val appointmentId: String?,
    val chamberIndex: Int?,
    val doctor: Map<String, Any?>,
    val patient: Map<String, Any?>,
    val complaints: String,
    val findings: Map<String, Any?>,
    val diagnosis: String,
    val medicines: List<RxMedicine>,
    val tests: List<String>,
    val testPlace: String,
    val testPlaceId: String?,
    val advice: String,
    val followUpDate: Long?,
    val followUpBookDate: Long?,
    val followUpNote: String,
    val refillAt: Long?,
    val dispensed: List<RxDispensing>,
)

/**
 * prescriptions.js (rxView, verify, CODE_RE) + rxnet.js rxByCode. Consumers: P4 (rx_pharmacy_open, rx_dispense,
 * shop books "sale from prescription", orders), P1 (`/api/share?rx=`, `/rx/{code}` page).
 * Writes to prescriptions belong to P3, except what dispensing changes: P4 inserts `prescription_dispensings`
 * (+ items) itself and calls [setRefillAt].
 */
interface Prescriptions {
    /**
     * rxnet.js rxByCode: [code] trimmed, upper-cased, spaces/hyphens removed; not [CODE_RE] → 400
     * "Check the code on the prescription"; no such code, or [callerUid] in the other world (demo vs real,
     * compared with the doctor) → 404 "No prescription with this code".
     */
    fun byCode(lang: String?, code: String?, callerUid: String?): Prescription

    /** by id (null when missing) */
    fun get(id: String): Prescription?

    /** prescriptions.js rxView: the full prescription for the patient / writing doctor (JSON map, Node keys) */
    fun view(rx: Prescription): Map<String, Any?>

    /**
     * prescriptions.js verify (the /rx/{code} check for pharmacies): {status, createdAt, doctor {name, degrees,
     * regNo, chamber}, patient {name, age}, medicines [{name, strength, form, dose, days}], tests} — no phone, no
     * diagnosis. null for a bad/unknown code.
     */
    fun verify(code: String?): Map<String, Any?>?

    /**
     * after a dispensing (P4): rxnet.js refillDate result → `refill_at` (null clears; the refill reminder is P3's cron).
     * Also clears `refill_notified_at` (Node `refillNotifiedAt: FieldValue.delete()`).
     */
    fun setRefillAt(rxId: String, refillAtUtcMidnightMs: Long?)

    companion object {
        /** prescriptions.js CODE_RE (no I, O, 0, 1) */
        val CODE_RE = Regex("^[A-HJ-NP-Z2-9]{10}$")

        /** rxByCode's normalisation */
        fun normalizeCode(code: String?): String = (code ?: "").trim().uppercase().replace(Regex("[\\s-]"), "")
    }
}

/**
 * labprices.js lastHome: the patient's last home address/phone for lab home collection (`user_state.lab_home_*`).
 * Consumer: P4 (`pharmacies` with an rx: pre-fills the online order form).
 */
fun interface PatientHome {
    /** {address, phone} or null when none */
    fun lastHome(uid: String): Map<String, String>?
}
