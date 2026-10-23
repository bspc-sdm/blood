package com.bspc.blood

import androidx.compose.runtime.Composable

// ------------------------------------------------------------
// "ডা." / "Dr." পদবির নিয়ম (backend/docs/PRACTICE.md §৬, ফিচার সুইচ title_guard — "‘ডা.’ পদবির নিয়ম")।
// হাইকোর্টের রায় (২০২০/২০২১, ২০২৫-এ আবার): নামের আগে "ডা."/"Dr." শুধু MBBS/BDS — অ্যাপে ধরন doctor + পদ্ধতি allopathy
// (দাঁতের ডাক্তারও এলোপ্যাথি)। হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ, নার্স, পুষ্টিবিদ, ফিজিও, সাইকোলজিস্ট … কেউ নয়।
// সার্ভারই আসল পাহারাদার (400 title_not_allowed; পুরোনো নাম দেখানোর সময় পদবি ছাড়া) — অ্যাপ শুধু একই নিয়ম আগে দেখায়।
// উকিল লেখা দেখছেন: এই নিয়মের প্রতিটা লেখা এই ফাইলেই (সার্ভারে practice/TitleRules.kt, ওয়েবে lib/titleGuard.ts)।
// ------------------------------------------------------------

object TitleGuard {
    const val KEY = Features.TITLE_GUARD
    const val CODE = "title_not_allowed"

    fun on(): Boolean = Practice.on(KEY)

    @Composable
    fun rememberOn(): Boolean = Practice.rememberOn(KEY)

    // ---------- লেখা (একটাই জায়গা) ----------
    fun error(): String = tr("Only MBBS/BDS doctors may write ‘Dr.’ before the name", "MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না")
    fun hint(): String = tr("Don't write ‘Dr.’ before your name — your profession is shown automatically", "নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে")
    fun fixInProfile(): String = tr("Change the name in Edit profile first", "আগে প্রোফাইল এডিটে নাম ঠিক করুন")
    /** খোঁজার "ডাক্তার" ট্যাব যখন সব পদ্ধতির চিকিৎসক এখানে (সুইচ আর চিকিৎসা পদ্ধতি চালু) */
    fun tabAll(): String = tr("Doctors & practitioners", "ডাক্তার ও চিকিৎসক")

    /** অ-এলোপ্যাথিক চিকিৎসকের পেশা ("ডাক্তার"-এর বদলে); অন্য পদ্ধতিতে null */
    fun practitionerName(system: String?): String? = when (system) {
        "homeopathy" -> tr("Homeopathic practitioner", "হোমিওপ্যাথিক চিকিৎসক")
        "unani" -> tr("Unani practitioner", "ইউনানি চিকিৎসক")
        "ayurveda" -> tr("Ayurvedic practitioner", "আয়ুর্বেদিক চিকিৎসক")
        else -> null
    }

    // ---------- নিয়ম (সার্ভারের TitleRules-এর মতো হুবহু) ----------
    /** শুরুতে পদবি: ডা. ডাঃ ডা ডা: ডাক্তার Dr. Dr Dr: DR. Doctor — যেকোনো হাতের অক্ষর, পরে ফাঁকা থাকুক বা না থাকুক */
    val TITLE = Regex("^\\s*(?:ডাক্তার|ডা|doctor|dr)(?:\\s*[.:ঃ]+\\s*|\\s+)", RegexOption.IGNORE_CASE)

    /** এই পেশা কি "ডা." লিখতে পারেন? শুধু এলোপ্যাথি ডাক্তার (পদ্ধতি খালি = এলোপ্যাথি) */
    fun eligible(type: String?, system: String?): Boolean =
        type == "doctor" && (system.isNullOrBlank() || system == Practice.ALLOPATHY)

    /** পদবি বাদে নাম ("ডা. ডা. করিম" → "করিম"); শুধু পদবি হলে যেমন ছিল */
    fun strip(name: String): String {
        var s = name
        repeat(4) { TITLE.find(s)?.let { m -> s = s.substring(m.range.last + 1) } }
        return s.trim().ifEmpty { name.trim() }
    }

    fun hasTitle(name: String?): Boolean = name != null && TITLE.containsMatchIn(name) && strip(name) != name.trim()

    /** নামের ঘরের ভুল: সুইচ চালু, পেশা "ডা." লিখতে পারেন না আর নামের শুরুতে পদবি; type null = পেশাজীবী নন */
    fun nameError(name: String?, type: String?, system: String?, on: Boolean = on()): String? =
        if (type.isNullOrBlank() || !on || eligible(type, system) || !hasTitle(name)) null else error()

    /** নামের ঘরের নিচের ছোট লেখা */
    fun nameHint(type: String?, system: String?, on: Boolean = on()): String? =
        if (type.isNullOrBlank() || !on || eligible(type, system)) null else hint()

    /**
     * অ-এলোপ্যাথিক চিকিৎসকের পেশা: সার্ভারের typeLabel (সবাই এক কথা বলে) — না থাকলে সুইচ চালু হলে নিজের হিসাব; নইলে null
     * (ডাকা জায়গা আগের নাম দেখায়, যেমন Badges.label → "ডাক্তার")।
     */
    fun practitionerLabel(type: String?, system: String?, typeLabel: String? = null, typeLabelEn: String? = null): String? {
        if (type != "doctor") return null
        if (!typeLabel.isNullOrBlank() && typeLabel != "ডাক্তার") return tr(typeLabelEn?.takeIf { it.isNotBlank() } ?: typeLabel, typeLabel)
        if (!on()) return null
        return practitionerName(system)
    }

    /** পেশার নাম: অ-এলোপ্যাথিক চিকিৎসক হলে তাঁর পেশা, নইলে ব্যাজের নাম */
    fun professionLabel(type: String, system: String?, typeLabel: String? = null, typeLabelEn: String? = null): String =
        practitionerLabel(type, system, typeLabel, typeLabelEn) ?: Badges.label(type)
}
