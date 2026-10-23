package com.lifolo.practice

/**
 * The "ডা." / "Dr." title rule (backend/docs/PRACTICE.md §6, feature switch `title_guard`) — the pure rules, no Spring.
 *
 * Bangladesh High Court (2020/2021, reaffirmed 2025): only MBBS/BDS holders may use "Dr." / "ডা."; BMDC Act 2010
 * s.29(2). On Lifolo that is a provider of type `doctor` with system `allopathy` (dentists are allopathy). Everyone else
 * (homeopathy, unani, ayurveda, nurse, nutritionist, trainer, physio, psychologist, expert …) must not carry the title.
 *
 * EVERY user-facing string of the rule lives here, so the wording can change in one place after the lawyer's review
 * (the apps have the same strings in one place each: web-vue/src/lib/titleGuard.ts, app TitleGuard.kt).
 */
object TitleRules {
    const val CODE = "title_not_allowed"
    const val ERROR_BN = "MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না" // bn-ok
    const val ERROR_EN = "Only MBBS/BDS doctors may write ‘Dr.’ before the name"
    const val HINT_BN = "নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে" // bn-ok
    const val HINT_EN = "Don't write ‘Dr.’ before your name — your profession is shown automatically"

    /** the admin page / list name */
    const val ADMIN_TITLE_BN = "‘ডা.’ লেখা নাম" // bn-ok

    /** the profession of a non-allopathic practitioner (instead of "ডাক্তার") */
    val PRACTITIONER_BN: Map<String, String> = linkedMapOf(
        Practice.HOMEOPATHY to "হোমিওপ্যাথিক চিকিৎসক", // bn-ok
        Practice.UNANI to "ইউনানি চিকিৎসক", // bn-ok
        Practice.AYURVEDA to "আয়ুর্বেদিক চিকিৎসক", // bn-ok
    )
    val PRACTITIONER_EN: Map<String, String> = linkedMapOf(
        Practice.HOMEOPATHY to "Homeopathic practitioner",
        Practice.UNANI to "Unani practitioner",
        Practice.AYURVEDA to "Ayurvedic practitioner",
    )

    /** the public prescription check (/rx/{code}) for a writer who is not an MBBS/BDS doctor */
    const val VERIFY_ISSUED_BN = "Lifolo-তে যাচাই করা পেশাজীবীর দেওয়া। বাতিল দেখালে ওষুধ দেবেন না।" // bn-ok
    const val VERIFY_ISSUED_EN = "Issued on Lifolo by a verified professional. Don't dispense if it shows cancelled."

    /**
     * A leading title: "ডা.", "ডাঃ", "ডা ", "ডা:", "ডাক্তার ", "Dr.", "Dr ", "Dr:", "DR.", "Doctor " — any case, an
     * optional dot / colon / visarga, with or without a space after it. "ডালিয়া", "Drabir" are names, not titles.
     */
    val TITLE: Regex = Regex("^\\s*(?:ডাক্তার|ডা|doctor|dr)(?:\\s*[.:ঃ]+\\s*|\\s+)", RegexOption.IGNORE_CASE) // bn-ok

    /** may this profession carry the title? Only an allopathic doctor (MBBS/BDS) */
    fun eligible(type: String?, system: String?): Boolean =
        type == Practice.DOCTOR && Practice.systemOf(type, system) == Practice.ALLOPATHY

    /** does the name start with a title (and is there a name after it)? */
    fun hasTitle(name: String?): Boolean = name != null && TITLE.containsMatchIn(name) && strip(name) != name.trim()

    /** the name without its leading title(s) ("ডা. ডা. করিম" → "করিম"); a name that is only a title stays as it is */
    fun strip(name: String): String {
        var s = name
        repeat(4) {
            val m = TITLE.find(s) ?: return@repeat
            s = s.substring(m.range.last + 1)
        }
        val out = s.trim()
        return if (out.isEmpty()) name.trim() else out
    }

    /** the profession label: a non-allopathic doctor is "হোমিওপ্যাথিক চিকিৎসক" … when [guard] is on, else as before */
    fun typeLabel(type: String?, system: String?, guard: Boolean): String? {
        if (guard && type == Practice.DOCTOR) {
            Practice.systemOf(type, system)?.let { PRACTITIONER_BN[it] }?.let { return it }
        }
        return Practice.TYPE_LABELS[type ?: ""]
    }

    fun typeLabelEn(type: String?, system: String?, guard: Boolean): String? {
        if (guard && type == Practice.DOCTOR) {
            Practice.systemOf(type, system)?.let { PRACTITIONER_EN[it] }?.let { return it }
        }
        return Practice.TYPE_LABELS_EN[type ?: ""]
    }
}
