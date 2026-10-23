package com.bspc.blood

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

// ------------------------------------------------------------
// চিকিৎসা পদ্ধতি (এলোপ্যাথি / হোমিওপ্যাথি / ইউনানি / আয়ুর্বেদ) আর নতুন পেশাজীবী (ফিজিওথেরাপিস্ট, সাইকোলজিস্ট / কাউন্সেলর)।
// চুক্তি: scratchpad/practice/CONTRACT.md, সার্ভার backend/docs/PRACTICE.md। সব ফিচার সুইচের পেছনে — বন্ধ থাকলে অ্যাপ আগের মতোই:
// সবাই এলোপ্যাথি, পদ্ধতি বাছাই নেই, নতুন পেশা নেই।
// ------------------------------------------------------------

object Practice {
    /** চিকিৎসা পদ্ধতি: পদ্ধতির ঘর, নামের পাশে লেবেল, খোঁজার ছাঁকনি, প্রেসক্রিপশনের নিয়ম */
    const val SYSTEMS = Features.PRACTICE_SYSTEMS
    const val PHYSIO = Features.PRO_PHYSIO
    const val PSYCH = Features.PRO_PSYCHOLOGIST

    /** ফিচার সুইচের বাংলা নাম (backend/docs/FEATURES.md-এর একই) */
    val NAMES = linkedMapOf(
        SYSTEMS to "চিকিৎসা পদ্ধতি",
        PHYSIO to "ফিজিওথেরাপিস্ট",
        PSYCH to "সাইকোলজিস্ট / কাউন্সেলর",
    )

    /** টেস্টে: null = আসল Features */
    var testKeys: Map<String, Boolean>? = null

    fun on(key: String): Boolean = testKeys?.get(key) ?: Features.on(key)

    /** কম্পোজে: সুইচ বদলালে পাতা নিজে বদলায় */
    @Composable
    fun rememberOn(key: String): Boolean {
        val keys by Features.keys.collectAsState()
        return testKeys?.get(key) ?: (keys[key] == true)
    }

    // ---------- চিকিৎসা পদ্ধতি ----------

    const val ALLOPATHY = "allopathy"
    val SYSTEM_LIST = listOf(ALLOPATHY, "homeopathy", "unani", "ayurveda")

    /** সুইচ বন্ধ বা অজানা হলে এলোপ্যাথি */
    fun system(raw: String?): String = if (!on(SYSTEMS)) ALLOPATHY else raw?.takeIf { it in SYSTEM_LIST } ?: ALLOPATHY

    fun nonAllopathic(raw: String?): Boolean = system(raw) != ALLOPATHY

    fun systemLabel(s: String): String = when (s) {
        "homeopathy" -> tr("Homeopathy", "হোমিওপ্যাথি")
        "unani" -> tr("Unani", "ইউনানি")
        "ayurveda" -> tr("Ayurveda", "আয়ুর্বেদ")
        else -> tr("Allopathy", "এলোপ্যাথি")
    }

    /** নিবন্ধনকারী প্রতিষ্ঠান */
    fun body(s: String): String = when (s) {
        "homeopathy" -> tr("Bangladesh Homeopathic Board", "বাংলাদেশ হোমিওপ্যাথিক বোর্ড")
        "unani", "ayurveda" -> tr("Bangladesh Unani & Ayurvedic Board", "বাংলাদেশ ইউনানি ও আয়ুর্বেদিক বোর্ড")
        else -> "BMDC"
    }

    fun degreeHint(s: String): String = when (s) {
        "homeopathy" -> "DHMS / BHMS"
        "unani" -> "DUMS / BUMS"
        "ayurveda" -> "DAMS / BAMS"
        else -> "MBBS / BDS"
    }

    /**
     * নামের পাশের লেবেল: সুইচ চালু থাকলে প্রতিটা ডাক্তারের পাশে পদ্ধতি (এলোপ্যাথিও), নইলে কিছু নয়।
     * নামের আগে "ডা." নিজে থেকে কখনো বসানো হয় না — নাম যেমন লেখা, তেমনই (CONTRACT §1)।
     */
    fun nameTag(type: String, system: String?): String? =
        if (type == "doctor" && on(SYSTEMS)) systemLabel(system(system)) else null

    /** অনুমোদিত ডাক্তার পদ্ধতি বদলালে ({ok, reverify: true}) — কী হবে */
    fun reverifyText(): String = tr(
        "An admin will check your badge again with the new registering body. Until then you are not in the lists (booked appointments stay). After approval your specialties are cleared — choose them again and save your chambers.",
        "নতুন নিবন্ধনকারী প্রতিষ্ঠান দিয়ে অ্যাডমিন আবার ব্যাজ যাচাই করবেন। ততদিন তালিকায় থাকবেন না (নেওয়া অ্যাপয়েন্টমেন্ট থাকবে)। অনুমোদনের পর বিশেষজ্ঞতা মুছে যাবে — আবার বেছে চেম্বার সেভ করবেন।",
    )

    // ---------- পেশা ----------

    const val PHYSIO_TYPE = "physio"
    const val PSYCH_TYPE = "psychologist"
    val NEW_TYPES = listOf(PHYSIO_TYPE, PSYCH_TYPE)

    /** চালু থাকা নতুন পেশা */
    fun newTypes(): List<String> = listOfNotNull(if (on(PHYSIO)) PHYSIO_TYPE else null, if (on(PSYCH)) PSYCH_TYPE else null)

    /** "আমার চেম্বার/বাসায় সেবা" পান */
    fun providerTypes(): List<String> = PROVIDER_TYPES + newTypes()
    /** চেম্বার আর অ্যাপয়েন্টমেন্ট */
    fun chamberTypes(): List<String> = CHAMBER_TYPES + newTypes()
    /** বাসায় সেবা: ফিজিওথেরাপিস্ট যান, সাইকোলজিস্ট নন */
    fun homeTypes(): List<String> = HOME_PROVIDER_TYPES + listOfNotNull(if (on(PHYSIO)) PHYSIO_TYPE else null)
    /** ব্যাজের আবেদনে পেশা (বিশেষজ্ঞ শেষে) */
    fun badgeTypes(): List<String> = Badges.ALL.filter { it != "expert" } + newTypes() + "expert"

    /** অনলাইন (ভিডিও) চেম্বার রাখতে পারেন */
    fun onlineCapable(type: String): Boolean = type == "doctor" || type in NEW_TYPES

    /** অ্যাপে প্রেসক্রিপশন/নোট লেখেন */
    fun writesRx(type: String): Boolean = type == "doctor" || type in NEW_TYPES

    fun proLabel(type: String): String? = when (type) {
        PHYSIO_TYPE -> tr("Physiotherapist", "ফিজিওথেরাপিস্ট")
        PSYCH_TYPE -> tr("Psychologist / Counsellor", "সাইকোলজিস্ট / কাউন্সেলর")
        else -> null
    }

    /** ছোট নাম (ট্যাব) */
    fun proTab(type: String): String = when (type) {
        PHYSIO_TYPE -> tr("Physiotherapist", "ফিজিওথেরাপিস্ট")
        PSYCH_TYPE -> tr("Psychologist", "সাইকোলজিস্ট")
        else -> Badges.label(type)
    }

    /** নিবন্ধনের ঘরে কোন প্রতিষ্ঠান/কাগজ আর ডিগ্রি (আবেদনে ছোট লেখা) */
    fun regHint(type: String, system: String): String? = when (type) {
        "doctor" -> if (on(SYSTEMS)) tr("Registration: ", "নিবন্ধন: ") + body(system) + " · " + tr("Degree: ", "ডিগ্রি: ") + degreeHint(system) else null
        PHYSIO_TYPE -> tr(
            "Degree: BPT / MPT. Professional body membership number, if you have one.",
            "ডিগ্রি: BPT / MPT। পেশাজীবী সংগঠনের সদস্য নম্বর থাকলে দিন।",
        )
        PSYCH_TYPE -> tr(
            "Degree: MS / MPhil in clinical or counselling psychology. Bangladesh Clinical Psychology Society membership, if you have one.",
            "ডিগ্রি: ক্লিনিক্যাল বা কাউন্সেলিং সাইকোলজিতে MS / MPhil। বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্য হলে নম্বর দিন।",
        )
        else -> null
    }

    /** রেজিস্ট্রেশন নম্বরের ঘরের উদাহরণ */
    fun regPlaceholder(type: String, system: String): String = when (type) {
        "doctor" -> when (system(system)) {
            "homeopathy" -> tr("Board reg. no.", "বোর্ডের নিবন্ধন নম্বর")
            "unani", "ayurveda" -> tr("Board reg. no.", "বোর্ডের নিবন্ধন নম্বর")
            else -> "BMDC A-12345"
        }
        PHYSIO_TYPE, PSYCH_TYPE -> tr("Membership no. (if any)", "সদস্য নম্বর (থাকলে)")
        else -> ""
    }

    /** ডিগ্রির ঘরের উদাহরণ (প্রোফাইল) */
    fun degreePlaceholder(type: String, system: String?): String = when (type) {
        "doctor" -> if (nonAllopathic(system)) tr("e.g. ", "যেমন: ") + degreeHint(system(system)) else tr("e.g. MBBS, FCPS (Medicine)", "যেমন: MBBS, FCPS (Medicine)")
        PHYSIO_TYPE -> tr("e.g. BPT, MPT", "যেমন: BPT, MPT")
        PSYCH_TYPE -> tr("e.g. MS, MPhil (Clinical Psychology)", "যেমন: MS, MPhil (Clinical Psychology)")
        else -> tr("e.g. MBBS, FCPS (Medicine)", "যেমন: MBBS, FCPS (Medicine)")
    }

    /** প্রেসক্রিপশনের মাথায় নিবন্ধনের লাইন: "BMDC A-1" / "বাংলাদেশ হোমিওপ্যাথিক বোর্ড 123" / "নিবন্ধন: 77" */
    fun regLine(type: String, system: String?, regNo: String): String {
        if (regNo.isBlank()) return ""
        return when {
            type == "doctor" && nonAllopathic(system) -> body(system(system)) + " " + regNo
            type in NEW_TYPES -> tr("Reg.: ", "নিবন্ধন: ") + regNo
            else -> "BMDC $regNo"
        }
    }

    // ---------- বিশেষজ্ঞতা ----------

    // সার্ভারের catalog-defaults.json-এর একই কোড (specialty: doctor_homeopathy / doctor_unani / doctor_ayurveda, physio, psychologist)
    val ALT_SPECIALTIES = listOf("alt_general", "alt_skin_hair", "alt_women", "alt_child", "alt_joint_pain", "alt_digestive", "alt_allergy")
    val PHYSIO_SPECIALTIES = listOf("physio_back_neck", "physio_sports", "physio_stroke", "physio_pediatric", "physio_post_surgery")
    val PSYCH_SPECIALTIES = listOf("psy_depression_anxiety", "psy_child", "psy_couple_family", "psy_addiction", "psy_trauma")

    /**
     * পদ্ধতি/পেশার তালিকা (প্যানেলের তালিকা থাকলে সেটা) — null হলে পুরনো নিয়ম (Specialties.of)।
     * হোমিওপ্যাথি, ইউনানি, আয়ুর্বেদের ছোট সাধারণ তালিকা আর "সাধারণ"।
     */
    fun specialties(type: String, system: String?): List<String>? = when {
        type == PHYSIO_TYPE -> Catalog.visible("specialty", PHYSIO_TYPE) ?: PHYSIO_SPECIALTIES
        type == PSYCH_TYPE -> Catalog.visible("specialty", PSYCH_TYPE) ?: PSYCH_SPECIALTIES
        type == "doctor" && nonAllopathic(system) -> Catalog.visible("specialty", "doctor_" + system(system)) ?: ALT_SPECIALTIES
        else -> null
    }

    fun specialtyLabel(code: String): String? = when (code) {
        "alt_general" -> tr("General", "সাধারণ")
        "alt_skin_hair" -> tr("Skin & hair", "চর্ম ও চুল")
        "alt_women" -> tr("Women's health", "নারী স্বাস্থ্য")
        "alt_child" -> tr("Child health", "শিশু স্বাস্থ্য")
        "alt_joint_pain" -> tr("Joint & back pain", "জয়েন্ট ও কোমর ব্যথা")
        "alt_digestive" -> tr("Digestive problems", "হজমের সমস্যা")
        "alt_allergy" -> tr("Allergy & breathing", "অ্যালার্জি ও শ্বাসকষ্ট")
        "physio_back_neck" -> tr("Back & neck pain", "কোমর ও ঘাড় ব্যথা")
        "physio_sports" -> tr("Sports injury", "খেলাধুলার আঘাত")
        "physio_stroke" -> tr("Stroke rehab", "স্ট্রোক পরবর্তী পুনর্বাসন")
        "physio_pediatric" -> tr("Child physiotherapy", "শিশুদের ফিজিওথেরাপি")
        "physio_post_surgery" -> tr("After surgery", "অপারেশন পরবর্তী")
        "psy_depression_anxiety" -> tr("Depression & anxiety", "বিষণ্ণতা ও উদ্বেগ")
        "psy_child" -> tr("Child & adolescent", "শিশু ও কিশোর")
        "psy_couple_family" -> tr("Couple & family", "দাম্পত্য ও পারিবারিক")
        "psy_addiction" -> tr("Addiction counselling", "মাদকাসক্তি কাউন্সেলিং")
        "psy_trauma" -> tr("Trauma", "ট্রমা")
        else -> null
    }

    /** ফিজিওথেরাপিস্টের বাসায় সেবা (homeService: physio) */
    val PHYSIO_HOME = listOf("physio_home_exercise", "physio_home_stroke", "physio_home_post_op", "physio_home_elderly", "physio_home_pain")

    fun homeServiceLabel(code: String): String? = when (code) {
        "physio_home_exercise" -> tr("Exercise therapy at home", "বাসায় ব্যায়াম থেরাপি")
        "physio_home_stroke" -> tr("Stroke or paralysis", "স্ট্রোক বা প্যারালাইসিস")
        "physio_home_post_op" -> tr("After surgery", "অপারেশনের পরে")
        "physio_home_elderly" -> tr("Elderly mobility", "বয়স্কদের চলাফেরা")
        "physio_home_pain" -> tr("Pain relief", "ব্যথা কমানো")
        else -> null
    }

    // ---------- প্রেসক্রিপশন ----------

    enum class RxMode {
        /** এলোপ্যাথি: আগের মতো সব — ওষুধের তালিকা থেকে খোঁজা, ফার্মেসিতে পাঠানো */
        FULL,
        /** হোমিওপ্যাথি/ইউনানি/আয়ুর্বেদ: ওষুধ হাতে লেখা লাইন, তালিকা নয়, ফার্মেসিতে পাঠানো নেই; পরীক্ষা চলে */
        FREE,
        /** ফিজিওথেরাপিস্ট: শুধু "পরামর্শ ও ব্যায়াম" */
        PHYSIO,
        /** সাইকোলজিস্ট: সেশনের নোট আর পরামর্শ — ওষুধ নেই */
        PSYCH,
    }

    /**
     * সার্ভারের rxRules (rx_me আর প্রতিটা প্রেসক্রিপশন): কী লেখা যায় — ওষুধ, হাতে লেখা লাইন, ওষুধের তালিকা, ফার্মেসি, পরীক্ষা,
     * নোটের শিরোনাম (section), কাগজের সতর্কবার্তা (notice)। লেখার পাতা এটা দিয়েই চলে (PRACTICE.md)।
     */
    data class RxRules(
        val medicines: Boolean = true,
        val freeText: Boolean = false,
        val drugCatalog: Boolean = true,
        val pharmacy: Boolean = true,
        val tests: Boolean = true,
        val section: String? = null,
        val notice: String? = null,
    ) {
        val mode: RxMode
            get() = when {
                !medicines && notice != null -> RxMode.PSYCH
                !medicines -> RxMode.PHYSIO
                freeText || !drugCatalog -> RxMode.FREE
                else -> RxMode.FULL
            }

        companion object {
            fun parse(o: org.json.JSONObject?): RxRules? = o?.let {
                RxRules(
                    it.optBoolean("medicines", true), it.optBoolean("freeText"), it.optBoolean("drugCatalog", true), it.optBoolean("pharmacy", true),
                    it.optBoolean("tests", true), it.optString("section").takeIf { s -> s.isNotBlank() && !it.isNull("section") },
                    it.optString("notice").takeIf { s -> s.isNotBlank() && !it.isNull("notice") },
                )
            }

            /** সার্ভার না জানালে (পুরনো সার্ভার) পেশা আর পদ্ধতি থেকে */
            fun of(mode: RxMode): RxRules = when (mode) {
                RxMode.FULL -> RxRules()
                RxMode.FREE -> RxRules(freeText = true, drugCatalog = false, pharmacy = false)
                RxMode.PHYSIO -> RxRules(false, false, false, false, false, "পরামর্শ ও ব্যায়াম")
                RxMode.PSYCH -> RxRules(false, false, false, false, false, "সেশন নোট ও পরামর্শ", "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান")
            }
        }
    }

    fun rxMode(type: String, system: String?): RxMode = when {
        type == PHYSIO_TYPE -> RxMode.PHYSIO
        type == PSYCH_TYPE -> RxMode.PSYCH
        nonAllopathic(system) -> RxMode.FREE
        else -> RxMode.FULL
    }

    /** সার্ভারের freeText (rx view) থাকলে সেটাও — সুইচ পরে বন্ধ হলেও হাতে লেখা লাইন ঠিকভাবে দেখায় */
    fun rxMode(rx: Rx): RxMode {
        rx.rules?.let { return it.mode }
        val m = rxMode(rx.doctorType.ifBlank { "doctor" }, rx.system)
        return if (m == RxMode.FULL && rx.freeText) RxMode.FREE else m
    }

    /** ফার্মেসিতে পাঠানো (আর দোকানের ওষুধ মেলানো) শুধু এলোপ্যাথির প্রেসক্রিপশনে — সার্ভারের "pharmacy" থাকলে সেটাই */
    fun pharmacyOk(rx: Rx): Boolean = (rx.pharmacy ?: rx.rules?.pharmacy ?: true) && rxMode(rx) == RxMode.FULL

    /** কাগজের শিরোনাম */
    fun rxTitle(mode: RxMode): String = when (mode) {
        RxMode.PHYSIO -> tr("Advice & exercises", "পরামর্শ ও ব্যায়াম")
        RxMode.PSYCH -> tr("Session notes & advice", "সেশন নোট ও পরামর্শ")
        else -> tr("Prescription", "প্রেসক্রিপশন")
    }

    /** সাইকোলজিস্টের কাগজে আর লেখার পাতায় সবসময় */
    fun psychiatristNote(): String = tr(
        "If medicine is needed, see a psychiatrist (allopathic doctor)",
        "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান",
    )

    /** কাগজ/PDF-এর মাথায় পদ্ধতি বা পেশা: "হোমিওপ্যাথি", "ফিজিওথেরাপিস্ট" (সুইচ বন্ধ থাকলে এলোপ্যাথির লেবেল নেই) */
    fun rxHeaderTag(rx: Rx): String? = proLabel(rx.doctorType) ?: nameTag("doctor", rx.system)

    // ---------- সাইকোলজিস্টের গোপনীয়তা ----------
    // সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট আর নোট কেয়ারগিভার দেখেন না, যদি না রোগী সেই কেয়ারগিভারকে শেয়ার করেন (শুরুতে বন্ধ)।
    // সার্ভারই ছাঁকে (কেয়ারগিভার মোডে শেয়ার না থাকলে আসেই না); অ্যাপ নিজে আর কিছু লুকায় না, শুধু রোগীর সুইচ দেখায়।
}

/** সার্ভারের ঘরের নাম (backend/docs/PRACTICE.md) — এক জায়গায় */
object PracticeWire {
    const val SYSTEM = "system"
    /** প্রেসক্রিপশনের doctor-এর ভেতরে পেশা ("doctor" / "physio" / "psychologist") */
    const val DOCTOR_TYPE = "type"
    /** প্রেসক্রিপশন: লেখকের পেশা, হাতে লেখা লাইন, ফার্মেসিতে পাঠানো যায় কি না (rx view-এর উপরের ঘর) */
    const val RX_TYPE = "doctorType"
    const val RX_FREE = "freeText"
    const val RX_PHARMACY = "pharmacy"
    /** সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট (my_appointments) */
    const val PRIVATE = "private"
    /** রোগী একজন কেয়ারগিভারকে সাইকোলজিস্টের অ্যাপয়েন্টমেন্ট/নোট দেখাতে দেন (care_links.share_psych; শুরুতে বন্ধ) */
    const val SHARE_ACTION = "care_share_psych"
    const val SHARE_FIELD = "sharePsych"
}
