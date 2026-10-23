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
     * A leading title: "ডা.", "ডাঃ", "ডা ", "ডা:", "ডাক্তার ", "ডক্টর ", "Dr.", "Dr ", "Dr:", "DR.", "Doctor " — any case, an
     * optional dot / colon / visarga / Bengali abbreviation sign (or any other punctuation), with or without a space after
     * it, optionally behind brackets or symbols ("(ডা.)", "[Dr]"). "ডালিয়া", "Drabir" are names, not titles.
     * Matched on the [fold]ed name, so invisible characters, Unicode spaces, full-width / styled letters, combining marks
     * and look-alike letters (Cyrillic "Dос") cannot hide a title (security review 2026-10-23).
     */
    val TITLE: Regex = Regex("^[\\s\\p{P}\\p{S}]*(?:ডাক্তার|ডক্টর|ডা|doctor|dr)(?:\\s*[\\p{P}ঃ৽]+\\s*|\\s+)", RegexOption.IGNORE_CASE) // bn-ok

    /** may this profession carry the title? Only an allopathic doctor (MBBS/BDS) */
    fun eligible(type: String?, system: String?): Boolean =
        type == Practice.DOCTOR && Practice.systemOf(type, system) == Practice.ALLOPATHY

    /**
     * May this person carry the title? The badge decides when there is one (its type/system as applied for): an MBBS/BDS
     * claim counts once it is **approved** — while `pending` only for saving the name (the admin checks the name with the
     * application), a `rejected` one never (security review 2026-10-23: a homeopath applying as an allopathic doctor must
     * not get the title shown while waiting or after the rejection). No badge: the listing.
     */
    fun eligibleProfile(
        badgeType: String?,
        badgeSystem: String?,
        badgeStatus: String?,
        listingType: String?,
        listingSystem: String?,
        saving: Boolean = false,
    ): Boolean = if (badgeType != null) {
        eligible(badgeType, badgeSystem) && (badgeStatus == "approved" || (saving && badgeStatus == "pending"))
    } else {
        eligible(listingType, listingSystem)
    }

    /** does the name start with a title (and is there a name after it)? */
    fun hasTitle(name: String?): Boolean = name != null && TITLE.containsMatchIn(fold(name).text) && strip(name) != name.trim()

    /** the name without its leading title(s) ("ডা. ডা. করিম" → "করিম"); a name that is only a title stays as it is */
    fun strip(name: String): String {
        var s = name
        repeat(4) {
            val f = fold(s)
            val m = TITLE.find(f.text) ?: return@repeat
            s = s.substring(f.ends[m.range.last])
        }
        val out = s.trim { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
        return if (out.isEmpty()) name.trim() else out
    }

    /** the folded text and, for every folded char, the index in the original just after the char it came from */
    class Folded(val text: String, val ends: IntArray)

    /**
     * The name as the title rule reads it: invisible characters (zero-width, BOM, word joiner, soft hyphen, variation
     * selectors, combining marks of the Latin/general blocks) dropped, every Unicode space a plain space, compatibility
     * forms (full-width "Ｄｒ", "𝐃𝐫", "ⅮⅬ", "․") as ASCII (NFKD) and look-alike letters (Cyrillic, Greek, Cherokee, small
     * capitals) as the Latin ones. Bengali letters and signs stay as they are (the precomposed "ড়" is not "ড").
     */
    fun fold(name: String): Folded {
        val sb = StringBuilder(name.length)
        val ends = IntArray(name.length * 4 + 4)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val next = i + Character.charCount(cp)
            for (c in foldOne(cp)) {
                if (sb.length >= ends.size) break
                ends[sb.length] = next
                sb.append(c)
            }
            i = next
        }
        return Folded(sb.toString(), ends.copyOf(sb.length))
    }

    private fun invisible(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() || cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF || cp in 0x20D0..0x20FF || cp in 0xFE20..0xFE2F || cp in 0xFE00..0xFE0F ||
            cp in 0xE0100..0xE01EF || cp in 0x180B..0x180F || cp == 0x034F || cp == 0x115F || cp == 0x1160 ||
            cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800 ||
            // the Bengali nukta: no word starts with a decomposed "ড়", and "ডা\u09BC." must not hide "ডা."
            cp == 0x09BC

    private fun foldOne(cp: Int): String {
        if (invisible(cp)) return ""
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return " "
        LOOKALIKE[cp]?.let { return it }
        if (cp < 0x80 || cp in 0x0980..0x09FF) return String(Character.toChars(cp))
        val n = java.text.Normalizer.normalize(String(Character.toChars(cp)), java.text.Normalizer.Form.NFKD)
        val sb = StringBuilder()
        n.codePoints().forEach { c ->
            when {
                invisible(c) -> {}
                Character.isWhitespace(c) || Character.isSpaceChar(c) -> sb.append(' ')
                else -> sb.append(LOOKALIKE[c] ?: String(Character.toChars(c)))
            }
        }
        return sb.toString()
    }

    /** letters that look like the Latin ones of "Dr" / "Doctor" */
    private val LOOKALIKE: Map<Int, String> = buildMap {
        for (c in "Ꭰꓓᗞᗪᴅ") put(c.code, "d")
        for (c in "гʀꭇꮁᴦᎡꓣƦᖇ") put(c.code, "r")
        for (c in "оОοΟօՕᴏ௦ഠ") put(c.code, "o")
        for (c in "сСϲϹᴄꮯ") put(c.code, "c")
        for (c in "тТτΤᴛꓔ") put(c.code, "t")
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
