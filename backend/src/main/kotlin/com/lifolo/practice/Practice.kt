package com.lifolo.practice

/**
 * Medical systems + new professionals (backend/docs/PRACTICE.md) — the pure rules, no Spring.
 *
 *  - a doctor's `system`: allopathy | homeopathy | unani | ayurveda (null on old rows = allopathy). Only doctors have one.
 *  - two more professions: `physio` (ফিজিওথেরাপিস্ট) and `psychologist` (ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর).
 *  - prescriptions: non-allopathic = free-text medicine lines, never sent to a pharmacy; physio/psychologist write notes
 *    only (no medicines, no tests).
 *  - a psychologist's appointments and notes are private: caregivers see them only when the patient shares them, and
 *    every push about them reads "অ্যাপয়েন্টমেন্ট আপডেট" (PushService masks items tagged [PRIVATE_KEY]).
 */
object Practice {
    const val ALLOPATHY = "allopathy"
    const val HOMEOPATHY = "homeopathy"
    const val UNANI = "unani"
    const val AYURVEDA = "ayurveda"

    val SYSTEMS: List<String> = listOf(ALLOPATHY, HOMEOPATHY, UNANI, AYURVEDA)

    /** a system: labels, the registering body and the degree hint (shown in badge_apply and the admin review) */
    data class SystemInfo(val code: String, val labelBn: String, val labelEn: String, val bodyBn: String, val bodyEn: String, val degrees: String) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
            "code" to code, "label" to labelBn, "labelEn" to labelEn, "body" to bodyBn, "bodyEn" to bodyEn, "degrees" to degrees,
        )
    }

    val SYSTEM_INFO: Map<String, SystemInfo> = linkedMapOf(
        ALLOPATHY to SystemInfo(ALLOPATHY, "এলোপ্যাথি", "Allopathy", "BMDC", "BMDC", "MBBS / BDS"), // bn-ok
        HOMEOPATHY to SystemInfo(HOMEOPATHY, "হোমিওপ্যাথি", "Homeopathy", "বাংলাদেশ হোমিওপ্যাথিক বোর্ড", "Bangladesh Homeopathic Board", "DHMS / BHMS"), // bn-ok
        UNANI to SystemInfo(UNANI, "ইউনানি", "Unani", "বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড", "Bangladesh Unani & Ayurvedic Board", "DUMS / BUMS"), // bn-ok
        AYURVEDA to SystemInfo(AYURVEDA, "আয়ুর্বেদ", "Ayurveda", "বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড", "Bangladesh Unani & Ayurvedic Board", "DAMS / BAMS"), // bn-ok
    )

    const val DOCTOR = "doctor"
    const val PHYSIO = "physio"
    const val PSYCHOLOGIST = "psychologist"

    /** the professions of this package (each behind its own feature key) */
    val NEW_TYPES: List<String> = listOf(PHYSIO, PSYCHOLOGIST)

    /** profession labels (lists, appointment and prescription headers) */
    val TYPE_LABELS: Map<String, String> = linkedMapOf(
        DOCTOR to "ডাক্তার", // bn-ok
        "nurse" to "নার্স", // bn-ok
        "nutritionist" to "পুষ্টিবিদ", // bn-ok
        "trainer" to "ফিটনেস ট্রেইনার", // bn-ok
        PHYSIO to "ফিজিওথেরাপিস্ট", // bn-ok
        PSYCHOLOGIST to "ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর", // bn-ok
    )

    val TYPE_LABELS_EN: Map<String, String> = linkedMapOf(
        DOCTOR to "Doctor", "nurse" to "Nurse", "nutritionist" to "Nutritionist", "trainer" to "Fitness trainer",
        PHYSIO to "Physiotherapist", PSYCHOLOGIST to "Clinical psychologist / counsellor",
    )

    /** who writes prescriptions / notes (rx_create …) and may have assistants and an online (video) chamber */
    val RX_WRITERS: List<String> = listOf(DOCTOR, PHYSIO, PSYCHOLOGIST)
    val ONLINE_TYPES: List<String> = RX_WRITERS
    val ASSISTED_TYPES: List<String> = RX_WRITERS

    /** notes only: no medicines section (and no tests) */
    val NOTES_ONLY: Set<String> = setOf(PHYSIO, PSYCHOLOGIST)

    /** private professions: hidden from caregivers unless shared, generic push text */
    val PRIVATE_TYPES: Set<String> = setOf(PSYCHOLOGIST)

    /** push data key: PushService shows the generic text and leaves out caregivers who were not shared with */
    const val PRIVATE_KEY = "private"
    const val GENERIC_EN = "Appointment update"
    const val GENERIC_BN = "অ্যাপয়েন্টমেন্ট আপডেট" // bn-ok

    const val PHYSIO_SECTION = "পরামর্শ ও ব্যায়াম" // bn-ok
    const val PSY_SECTION = "সেশন নোট ও পরামর্শ" // bn-ok
    const val PSY_NOTICE = "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান" // bn-ok

    fun isSystem(s: String?): Boolean = s != null && s in SYSTEMS

    /** a doctor's system (null on old rows = allopathy); null for everyone else */
    fun systemOf(type: String?, stored: String?): String? =
        if (type == DOCTOR) stored?.takeIf { it in SYSTEMS } ?: ALLOPATHY else null

    fun isPrivate(type: String?): Boolean = type != null && type in PRIVATE_TYPES

    /** `+ privateTag(type)` on a push's data map */
    fun privateTag(type: String?): Map<String, Any?> = if (isPrivate(type)) mapOf(PRIVATE_KEY to true) else emptyMap()

    /** a non-allopathic doctor's prescription: free-text medicine lines (no drug catalog, no interaction check, no stock match) */
    fun freeText(type: String?, system: String?): Boolean = type == DOCTOR && systemOf(type, system) != ALLOPATHY

    /** "send to pharmacy" (and every pharmacy step): only an allopathic doctor's prescription */
    fun pharmacyOk(type: String?, system: String?): Boolean = type == DOCTOR && systemOf(type, system) == ALLOPATHY

    /**
     * The labels every provider / appointment / prescription response carries: `system` (doctors only), `systemLabel`
     * ("এলোপ্যাথি" …, doctors only) and `typeLabel`. [systemsOn] false (practice_systems off for the viewer): no system
     * shown — everyone is an (allopathic) doctor as before.
     */
    fun labels(type: String?, system: String?, systemsOn: Boolean = true): LinkedHashMap<String, Any?> {
        val sys = if (systemsOn) systemOf(type, system) else null
        return linkedMapOf(
            "system" to sys,
            "systemLabel" to sys?.let { SYSTEM_INFO[it]?.labelBn },
            "typeLabel" to TYPE_LABELS[type ?: ""],
        )
    }

    /**
     * What a prescription of this writer may hold — `rxRules` in rx_me and every prescription view, so the clients
     * show the right form: medicines (false = no medicines section), freeText (free lines, no catalog/interactions/
     * stock), pharmacy ("send to pharmacy"), tests, section (the notes heading), notice (shown on the prescription).
     */
    fun rxRules(type: String?, system: String?): LinkedHashMap<String, Any?> {
        val notes = type in NOTES_ONLY
        val free = freeText(type, system)
        return linkedMapOf(
            "medicines" to !notes,
            "freeText" to free,
            "drugCatalog" to (!notes && !free),
            "interactions" to (!notes && !free),
            "pharmacy" to pharmacyOk(type, system),
            "tests" to !notes,
            "section" to when (type) {
                PHYSIO -> PHYSIO_SECTION
                PSYCHOLOGIST -> PSY_SECTION
                else -> null
            },
            "notice" to if (type == PSYCHOLOGIST) PSY_NOTICE else null,
        )
    }

    /** the specialty catalog list of a profession/system (`catalog_get` lists.specialty[...]) */
    fun specialtyList(type: String?, system: String?): String = when {
        type == DOCTOR && systemOf(type, system) != ALLOPATHY -> "doctor_${systemOf(type, system)}"
        type in listOf("nurse", "nutritionist", "trainer", PHYSIO, PSYCHOLOGIST) -> type!!
        else -> DOCTOR
    }
}
