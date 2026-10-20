package com.lifolo.common

/** Constants and tiny pure rules shared by many modules (server/lib/rules.js, community.js). */
object Rules {
    val BLOOD_GROUPS = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")
    val RADIUS_CHOICES = listOf(5, 10, 25, 50)
    const val DEFAULT_RADIUS_KM = 10
    const val MAX_PUSH_PER_DAY = 3
    const val MAX_PUSH_PER_DAY_LIMIT = 10
    const val DAY_MS = 24L * 60 * 60 * 1000

    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidId(id: String?): Boolean = id != null && ID_RE.matches(id)

    /** community.js BADGES */
    val BADGES = listOf("doctor", "nurse", "nutritionist", "trainer", "expert")

    /**
     * community.js LIMITS — per user per Dhaka day. rxClaim 10 → 20 (2026-10): each paper prescription is now added by
     * its own code (no automatic linking), so a patient adding a pile of old papers needs more than 10 a day.
     */
    val LIMITS: Map<String, Int> = mapOf(
        "ask" to 5, "answer" to 40, "tip" to 5, "badge" to 3, "book" to 5, "report" to 20, "hospital" to 10,
        "phoneCode" to 5, "ambulance" to 10, "pharmacy" to 5, "medTry" to 30, "ticket" to 5, "medFind" to 5,
        "bloodbank" to 5, "dateChange" to 3, "docs" to 10, "rx" to 300, "rxPatient" to 200, "rxClaim" to 20,
        "rxPharm" to 300, "rxSend" to 20, "lab" to 3, "care" to 10, "correction" to 3,
    )

    /** community.js PAGE */
    const val PAGE = 30

    /** community.js cleanText: trims, collapses 3+ newlines, null when empty or longer than max */
    fun cleanText(v: Any?, max: Int): String? {
        if (v !is String) return null
        val t = v.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (t.isEmpty() || t.length > max) return null
        return t
    }
}
