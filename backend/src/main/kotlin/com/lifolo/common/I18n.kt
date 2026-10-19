package com.lifolo.common

/**
 * Port of server/lib/i18n.js: legacy Bangla error messages → English when the app runs in English.
 * Keep EXACT/PATTERNS in sync with the Node file until the old server is switched off.
 */
object I18n {
    private val EXACT: Map<String, String> = mapOf(
        "requestId ঠিক নেই" to "Invalid request",
        "রিকোয়েস্ট পাওয়া যায়নি" to "Request not found",
        "এটা আপনার রিকোয়েস্ট না" to "This is not your request",
        "ব্যাগের সংখ্যা ঠিক নেই" to "Invalid number of bags",
        "হাসপাতালের নাম ঠিক নেই" to "Invalid hospital name",
        "তারিখ ঠিক নেই" to "Invalid date",
        "আজ বা পরের কোনো তারিখ দিন" to "Pick today or a later date",
        "সময় ঠিক নেই" to "Invalid time",
        "জরুরি ঠিক নেই" to "Invalid urgent value",
        "তথ্য ঠিক নেই" to "Invalid data",
        "অতিরিক্ত তথ্য ৩০০ অক্ষরের মধ্যে লিখুন" to "Keep the note under 300 characters",
        "কিছু বদলানো হয়নি" to "Nothing changed",
        "বন্ধ বা মেয়াদ শেষ হওয়া রিকোয়েস্ট এডিট করা যায় না" to "Closed or expired requests can't be edited",
        "রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ" to "This request is closed or expired",
        "নোটিফিকেশন পাঠানো চলছে, একটু পরে দেখুন" to "Notifications are being sent, check again shortly",
        "রিকোয়েস্টটা বন্ধ হয়ে গেছে" to "This request is closed",
        "নিজের রিকোয়েস্টে সাড়া দেওয়া যায় না" to "You can't respond to your own request",
        "আগে প্রোফাইল পূরণ করুন" to "Complete your profile first",
        "সঠিক মোবাইল নম্বর দিন" to "Enter a valid mobile number",
        "আগে একবার ডোনারদের জানান" to "Notify donors once first",
        "সব ব্যাগের ডোনার পাওয়া গেছে" to "All bags already have donors",
        "রক্ত দিতে আগে প্রোফাইলে আপনার রক্তের গ্রুপ দিন" to "Add your blood group in your profile to donate",
        "এলাকা ঠিক নেই" to "Invalid area",
        "লোকেশন ঠিক নেই" to "Invalid location",
        "রক্তের গ্রুপ ঠিক নেই" to "Invalid blood group",
        "আপনি এখন রক্ত দেওয়ার যোগ্য নন (বয়স ১৮–৬০, ওজন কমপক্ষে ৫০ কেজি, আর শেষ রক্তদানের পর ১২০ দিন লাগে)" to
            "You are not eligible right now (age 18–60, weight 50 kg+, and 120 days since your last donation)",
        "রক্তপ্রার্থী অন্য ডোনার বেছে নিয়েছেন" to "The requester chose another donor",
        "id ঠিক নেই" to "Invalid id",
        "অনুরোধ পাওয়া যায়নি" to "Request not found",
        "অনুরোধে সমস্যা আছে" to "Something is wrong with this request",
        "চাওয়া তারিখটা ঠিক নেই" to "The requested date is invalid",
        "সঠিক ইমেইল দিন" to "Enter a valid email",
        "আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।" to "Too many codes requested from here today. Try again tomorrow.",
        "আজকের মতো কোড পাঠানোর সীমা শেষ। কাল আবার চেষ্টা করুন।" to "Today's code limit is reached. Try again tomorrow.",
        "একটু আগেই কোড পাঠানো হয়েছে। ১ মিনিট পর আবার চেষ্টা করুন।" to "A code was just sent. Try again in 1 minute.",
        "আজ এই ইমেইলে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।" to "Too many codes for this email today. Try again tomorrow.",
        "ইমেইল পাঠানো যায়নি। একটু পর আবার চেষ্টা করুন।" to "Couldn't send the email. Try again shortly.",
        "৬ সংখ্যার কোড দিন" to "Enter the 6-digit code",
        "কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।" to "The code expired or wasn't requested. Ask for a new one.",
        "আজ অনেকবার ভুল কোড দেওয়া হয়েছে। কাল আবার চেষ্টা করুন।" to "Too many wrong codes today. Try again tomorrow.",
        "কোড মেলেনি। আবার দেখে লিখুন।" to "Wrong code. Please check and try again.",
        "অনুমতি নেই" to "Not allowed",
        "লগইন দরকার" to "Please log in",
        "লগইনের মেয়াদ শেষ, আবার লগইন করুন" to "Session expired, please log in again",
        "শুধু অ্যাডমিন এটা করতে পারবেন" to "Admins only",
    )

    private val PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^একটা রিকোয়েস্টে সর্বোচ্চ (\\d+) বার জানানো যায়।") to
            { m -> "Donors can be notified up to ${m.groupValues[1]} times per request. Tell people you know directly." },
        Regex("^(\\d+) ঘণ্টা পর আবার জানাতে পারবেন") to { m -> "You can notify again in ${m.groupValues[1]} hour(s)" },
        Regex("^একটা রিকোয়েস্ট সর্বোচ্চ (\\d+) বার এডিট করা যায়।") to
            { m -> "A request can be edited at most ${m.groupValues[1]} times. Close it and create a new one if needed." },
        Regex("^দিনে সর্বোচ্চ (\\d+)টা রিকোয়েস্ট দেওয়া যায়।") to
            { m -> "You can create up to ${m.groupValues[1]} requests a day. If urgent, contact people directly." },
        Regex("^একসাথে সর্বোচ্চ (\\d+)টা খোলা রিকোয়েস্ট রাখা যায়") to { m -> "You can keep up to ${m.groupValues[1]} open requests at a time" },
        Regex("^আপনার গ্রুপ \\(([^)]+)\\) এই রোগীকে দেওয়া যায় না") to { m -> "Your group (${m.groupValues[1]}) can't donate to this patient" },
        Regex("^দিনে সর্বোচ্চ (\\d+)টা রিকোয়েস্টে সাড়া দেওয়া যায়।") to
            { m -> "You can respond to up to ${m.groupValues[1]} requests a day. Try again tomorrow." },
    )

    fun toEnglish(msg: String): String {
        EXACT[msg]?.let { return it }
        for ((re, fn) in PATTERNS) {
            val m = re.find(msg)
            if (m != null) return fn(m)
        }
        return msg
    }
}
