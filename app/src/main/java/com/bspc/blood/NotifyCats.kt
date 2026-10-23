package com.bspc.blood

import org.json.JSONObject

/**
 * নোটিফিকেশনের ধরন (category) — সার্ভারের সাথে চুক্তি: প্রতিটা পুশের data-তে `cat` = এখানের একটা id;
 * সেটিংসে প্রতিটা ধরন আলাদা চালু/বন্ধ (notify_prefs_get / notify_prefs_set), আর ফোনে প্রতিটার আলাদা চ্যানেল
 * (ফোনের সেটিংস থেকেও আলাদা করে বন্ধ করা যায়)। `account` সবসময় চালু — সেটিংসে বদলানো যায় না।
 * blood_nearby = আগের profile.notify ("আশেপাশে কারো রক্ত লাগলে"), তার দিনে সর্বোচ্চ/গ্রুপ/দূরত্ব আগের মতোই।
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

    /** সেটিংসে চালু/বন্ধ করা যায় এমন ধরন — সেটিংসের ক্রমে */
    val SETTABLE = listOf(BLOOD_NEARBY, BLOOD_EMERGENCY, MY_REQUEST, APPOINTMENTS, RX, AMBULANCE, COMMUNITY, BUSINESS)

    /** সব ধরন (চ্যানেল প্রতিটার একটা) */
    val ALL = SETTABLE + ACCOUNT

    /** বন্ধ করার আগে নিশ্চিত করা হয় (জরুরি খবর) */
    val CONFIRM_OFF = setOf(BLOOD_EMERGENCY, AMBULANCE)

    /**
     * জোরালো চ্যানেল (শব্দ + উপরে ভেসে ওঠা): জরুরি আহ্বান, অ্যাম্বুলেন্স, অ্যাপয়েন্টমেন্ট/সিরিয়াল; আর আশেপাশের রক্তের
     * রিকোয়েস্ট ও নিজের রিকোয়েস্টে সাড়া — এ দুটো আগের একমাত্র চ্যানেলেও জোরালো ছিল (রক্ত লাগা সময়ের ব্যাপার)।
     */
    val HIGH = setOf(BLOOD_EMERGENCY, AMBULANCE, APPOINTMENTS, BLOOD_NEARBY, MY_REQUEST)

    /** আগের একমাত্র চ্যানেল (২.৩৫ পর্যন্ত) — নতুন চ্যানেল বানানোর সময় মুছে ফেলা হয় */
    const val OLD_CHANNEL_ID = "blood_requests"

    fun channelId(cat: String): String = "cat_$cat"

    /**
     * পুশের data থেকে ধরন: `cat` থাকলে (আর চেনা হলে) সেটাই; না থাকলে (পুরোনো সার্ভার) পুশের type থেকে আন্দাজ।
     * অচেনা কিছু হলে account — সবসময় চালু চ্যানেল, যাতে খবর হারায় না।
     */
    fun catOf(data: Map<String, String?>): String {
        val cat = data["cat"]?.trim()
        if (!cat.isNullOrEmpty() && cat in ALL) return cat
        return fromType(data["type"], data["sub"])
    }

    /** পুরোনো সার্ভারের পুশ (cat ছাড়া): type (আর sub) থেকে ধরন — সার্ভারের NotifyCats (backend/…/notify) মতোই */
    fun fromType(type: String?, sub: String? = null): String =
        when (type) {
            // পুরোনো সার্ভারে "request" বেশিরভাগই কাছের ডোনারকে নতুন রিকোয়েস্ট; আবার রক্ত দিতে পারবেন
            // (নতুন সার্ভার নিজেই cat দেয়: রাজি হওয়া রিকোয়েস্টের পরের খবর my_request)
            "request", "eligible" -> BLOOD_NEARBY
            "emergency" -> BLOOD_EMERGENCY
            // রক্তপ্রার্থীকে: কেউ রাজি হলেন/সরে গেলেন; ডোনারকে: রাজি হওয়া রিকোয়েস্ট মুছে ফেলা হয়েছে
            "response", "request_removed" -> MY_REQUEST
            // নতুন রসিদ (সার্ভার নিজেই cat my_request দেয়)
            "invoice_new" -> MY_REQUEST
            "invoice_summary", "invoice_void", "invoice_day_end" -> BUSINESS
            "appointment", "homecare", "homevisit" -> APPOINTMENTS
            "rx", "med_find_mine" -> RX
            "amb_req_mine", "amb_call" -> AMBULANCE
            "answer" -> COMMUNITY
            "rx_pharmacy", "rx_lab", "rx_review", "med_find", "pharmacy_order", "pharmacy_mine", "badge" -> BUSINESS
            // চালককে অ্যাম্বুলেন্স ডাকার খবর জরুরি; বাকি ব্যবসার খবর (অনুমোদন, কাগজ চাওয়া)
            "services" -> if (sub == "amb_req") AMBULANCE else BUSINESS
            // care (কেয়ারগিভারের আমন্ত্রণ), correction, support, hospital আর অচেনা — অ্যাকাউন্ট (সবসময় চালু)
            else -> ACCOUNT
        }

    fun label(cat: String): String = when (cat) {
        BLOOD_NEARBY -> tr("When someone nearby needs blood", "আশেপাশে রক্ত লাগলে")
        BLOOD_EMERGENCY -> tr("Emergency blood calls", "জরুরি রক্তের আহ্বান")
        MY_REQUEST -> tr("My blood requests", "আমার রক্তের অনুরোধ")
        APPOINTMENTS -> tr("Appointments and queue", "অ্যাপয়েন্টমেন্ট আর সিরিয়াল")
        RX -> tr("Prescriptions, medicines and lab", "প্রেসক্রিপশন, ওষুধ আর ল্যাব")
        AMBULANCE -> tr("Ambulance", "অ্যাম্বুলেন্স")
        COMMUNITY -> tr("Questions and tips", "প্রশ্নোত্তর আর টিপস")
        BUSINESS -> tr("My business", "আমার ব্যবসা")
        else -> tr("Account and security (always on)", "অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)")
    }

    fun description(cat: String): String = when (cat) {
        BLOOD_NEARBY -> tr("Requests near you that match your blood group", "কাছের রিকোয়েস্ট, যাদের আপনি রক্ত দিতে পারেন")
        BLOOD_EMERGENCY -> tr("Urgent calls for blood in your area", "এলাকায় জরুরি রক্তের ডাক")
        MY_REQUEST -> tr("When donors respond to your request", "আপনার রিকোয়েস্টে কেউ রাজি হলে বা সরে গেলে")
        APPOINTMENTS -> tr("Bookings, reminders and your turn in the queue", "বুকিং, রিমাইন্ডার আর সিরিয়াল এগোলে")
        RX -> tr("New prescriptions, medicine orders and lab reports", "নতুন প্রেসক্রিপশন, ওষুধের অর্ডার আর ল্যাব রিপোর্ট")
        AMBULANCE -> tr("Updates on the ambulance you called", "আপনার ডাকা অ্যাম্বুলেন্সের খবর")
        COMMUNITY -> tr("Answers to your questions and health tips", "আপনার প্রশ্নের উত্তর আর স্বাস্থ্য টিপস")
        BUSINESS -> tr("Orders, bookings and approvals for your business or chamber", "আপনার ব্যবসা বা চেম্বারের অর্ডার, বুকিং আর অনুমোদন")
        else -> tr(
            "Login, caregivers, corrections and support replies. These can't be turned off.",
            "লগইন, কেয়ারগিভার, তথ্য সংশোধন আর সাপোর্টের উত্তর — এগুলো বন্ধ করা যায় না।",
        )
    }
}

/** নোটিফিকেশনের পছন্দ (সার্ভারের notify_prefs_get/set-এর উত্তর) — all বন্ধ হলে শুধু account আসে */
data class NotifyPrefs(val all: Boolean, val cats: Map<String, Boolean>) {
    /** অজানা ধরন চালু ধরা হয় (সার্ভারের পুরোনো উত্তরে নতুন ধরন না থাকলে) */
    fun isOn(cat: String): Boolean = cat == NotifyCats.ACCOUNT || (cats[cat] ?: true)

    /** এই ধরনের খবর আসবে কি না (সব বন্ধ হলে শুধু account) */
    fun delivers(cat: String): Boolean = cat == NotifyCats.ACCOUNT || (all && isOn(cat))

    fun with(cat: String, on: Boolean): NotifyPrefs = copy(cats = cats + (cat to on))

    companion object {
        fun of(all: Boolean, cats: Map<String, Boolean>) =
            NotifyPrefs(all, NotifyCats.SETTABLE.associateWith { cats[it] ?: true })
    }
}

/** সার্ভারের notify_prefs_get / notify_prefs_set (/api/community) */
object NotifyPrefsApi {
    suspend fun get(): NotifyPrefs =
        parse(ServerApi.post("/api/community", JSONObject().put("action", "notify_prefs_get")))

    /** all = null: বদলায় না; cats: শুধু যেগুলো বদলাবে */
    suspend fun set(all: Boolean? = null, cats: Map<String, Boolean> = emptyMap()): NotifyPrefs {
        val body = JSONObject().put("action", "notify_prefs_set")
        if (all != null) body.put("all", all)
        if (cats.isNotEmpty()) body.put("cats", JSONObject().apply { cats.forEach { (k, v) -> put(k, v) } })
        return parse(ServerApi.post("/api/community", body))
    }

    private fun parse(j: JSONObject): NotifyPrefs {
        val c = j.optJSONObject("cats") ?: JSONObject()
        val cats = NotifyCats.SETTABLE.filter { c.has(it) }.associateWith { c.optBoolean(it, true) }
        return NotifyPrefs.of(j.optBoolean("all", true), cats)
    }
}
