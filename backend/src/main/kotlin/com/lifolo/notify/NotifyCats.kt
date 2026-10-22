package com.lifolo.notify

/**
 * Notification categories (notify_prefs_get / notify_prefs_set; the apps' per-category Android channels). Every push
 * belongs to exactly one category; [of] decides which. A person who turned a category (or `all`) off gets no phone
 * push for it — the bell list (inbox) still gets everything. [ACCOUNT] is always on.
 *
 * The category comes from the push's `type` ([BY_TYPE]); a few call sites whose type is shared by two kinds of news set
 * `cat` in the data themselves (always one of [IDS]):
 *  - `request` to nearby donors (a new request / "notify again") → [BLOOD_NEARBY]; every other `request` push goes to a
 *    donor about a request they already answered → [MY_REQUEST]
 *  - `appointment` new booking request / "requests waiting" to the doctor and "chambers removed" → [BUSINESS]
 *  - `services`/ambulance new call to drivers (the wave) → [BUSINESS]; later updates on that call → [AMBULANCE]
 *  - a caregiver's copy of the patient's news (`care` + asUid) → the category of the patient's news
 * Unknown types fall back to [ACCOUNT] (always sent); NotifyCatsTest fails when a type in the code has no entry.
 */
object NotifyCats {
    const val BLOOD_NEARBY = "blood_nearby"
    const val BLOOD_EMERGENCY = "blood_emergency"
    const val MY_REQUEST = "my_request"
    const val APPOINTMENTS = "appointments"
    const val RX = "rx"
    const val AMBULANCE = "ambulance"
    const val COMMUNITY = "community"
    const val BUSINESS = "business"
    const val ACCOUNT = "account"

    /** the categories a person can turn off, in the settings order (wire ids of notify_prefs_*) */
    val SETTABLE: List<String> = listOf(BLOOD_NEARBY, BLOOD_EMERGENCY, MY_REQUEST, APPOINTMENTS, RX, AMBULANCE, COMMUNITY, BUSINESS)

    val IDS: Set<String> = (SETTABLE + ACCOUNT).toSet()

    /** push `type` → category (the default for that type; see the class comment for the per-site exceptions) */
    val BY_TYPE: Map<String, String> = linkedMapOf(
        // blood
        "request" to MY_REQUEST, // donor side of a request they answered; the nearby-donor push sets cat=blood_nearby
        "response" to MY_REQUEST, // requester: a donor accepted / cancelled / left
        "request_removed" to MY_REQUEST, // donor: the request they answered was removed
        "eligible" to BLOOD_NEARBY, // "you can donate again" (only with the nearby switch on, like before)
        "emergency" to BLOOD_EMERGENCY, // Lifolo team appeal + "appeal ended" to donors who said coming
        // appointments (patient side, and updates for the doctor); new bookings to the doctor set cat=business
        "appointment" to APPOINTMENTS,
        // prescriptions, medicines, pharmacy and lab news for the patient
        "rx" to RX,
        "med_find_mine" to RX, // a shop has the medicine I looked for
        // ambulance call (patient)
        "amb_req_mine" to AMBULANCE,
        // questions and tips
        "answer" to COMMUNITY,
        // my business: listings, badge, new orders and finds, report reviews for the doctor
        "services" to BUSINESS, // sub=amb_req (driver: updates on a call) → ambulance, see [of]
        "badge" to BUSINESS,
        "rx_pharmacy" to BUSINESS,
        "rx_lab" to BUSINESS,
        "rx_review" to BUSINESS,
        "med_find" to BUSINESS,
        // always on
        "care" to ACCOUNT, // caregiver invitation / accepted; a caregiver's copy of patient news carries its own cat
        "support" to ACCOUNT, // Lifolo team reply
        "correction" to ACCOUNT, // last-donation-date correction result
        "hospital" to ACCOUNT, // the hospital I asked for was added
    )

    /** the category of one push (its data as built by the caller) */
    fun of(data: Map<String, Any?>): String {
        val explicit = data["cat"]?.toString()
        if (explicit != null && explicit in IDS) return explicit
        val type = data["type"]?.toString() ?: return ACCOUNT
        if (type == "services" && data["sub"]?.toString() == "amb_req") return AMBULANCE
        return BY_TYPE[type] ?: ACCOUNT
    }
}
