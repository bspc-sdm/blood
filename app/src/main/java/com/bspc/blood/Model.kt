package com.bspc.blood

data class Profile(
    val name: String = "",
    val phone: String = "",
    val bloodGroup: String = "",
    /** null মানে কখনো রক্ত দেননি */
    val lastDonationMillis: Long? = null,
    /** জন্ম তারিখ (UTC মধ্যরাত) — বয়স হিসাবের জন্য */
    val birthDateMillis: Long? = null,
    /** ওজন, কেজি */
    val weightKg: Int? = null,
    /** গোল করা স্থানাঙ্ক (≈১ কিমি) */
    val lat: Double? = null,
    val lng: Double? = null,
    /** দেখানোর জন্য এলাকার নাম (জেলা) */
    val placeName: String? = null,
    /** "gps" বা "district" */
    val locationSource: String? = null,
    val notify: Boolean = true,
    /** দিনে সর্বোচ্চ কয়টা রিকোয়েস্টের নোটিফিকেশন চান (সেটিংস) */
    val maxPushPerDay: Int = Rules.DEFAULT_PUSH_PER_DAY,
    /** কোন রোগীর গ্রুপের রিকোয়েস্টে নোটিফিকেশন চান; null = যাদের দিতে পারেন তাদের সবার */
    val notifyGroups: List<String>? = null,
    /** "রক্ত দরকার" তালিকায় কোন রোগীর গ্রুপের রিকোয়েস্ট দেখবেন; null = যাদের দিতে পারেন তাদের */
    val viewGroups: List<String>? = null,
) {
    /** এই ডোনার যেসব রোগীর গ্রুপকে রক্ত দিতে পারেন */
    val compatibleGroups: List<String> get() = BloodCompat.patientsFor(bloodGroup)

    /** আসলে যে গ্রুপগুলোর নোটিফিকেশন আসবে (গ্রুপ বদলালে বাতিল হয়ে যাওয়াগুলো বাদ) — সার্ভারের নিয়মের মতো */
    val effectiveNotifyGroups: List<String>
        get() {
            val compat = compatibleGroups
            return notifyGroups?.filter { it in compat }?.ifEmpty { null } ?: compat
        }

    /** তালিকায় আসলে যে গ্রুপগুলো দেখানো হবে */
    val effectiveViewGroups: List<String>
        get() = viewGroups?.filter { it in Rules.BLOOD_GROUPS }?.ifEmpty { null } ?: compatibleGroups

    val hasLocation: Boolean get() = lat != null && lng != null
    val isComplete: Boolean get() = bloodGroup.isNotEmpty() && phone.isNotEmpty() && birthDateMillis != null && weightKg != null
}

data class BloodRequest(
    val id: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val patientGroup: String = "",
    val bags: Int = 1,
    val hospital: String = "",
    val placeName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val neededByMillis: Long = 0,
    val urgent: Boolean = false,
    val note: String = "",
    /** "open" বা "closed" */
    val status: String = "open",
    val createdAtMillis: Long = 0,
    /** সার্ভারের সময় অনুযায়ী তৈরির মুহূর্ত (ফোনের ঘড়ি ভুল থাকলেও ঠিক থাকে) */
    val serverCreatedMillis: Long = 0,
    /** সার্ভার আশেপাশের ডোনারদের জানিয়েছে কি না, আর কতজনকে */
    val notified: Boolean = false,
    val notifiedCount: Int = 0,
) {
    /** খোলা আছে আর দরকারের তারিখ পার হয়নি */
    val isActive: Boolean
        get() = status == "open" && neededByMillis + DAY_MS > System.currentTimeMillis()
}

data class DonorResponse(
    val donorUid: String = "",
    val donorName: String = "",
    val donorPhone: String = "",
    val donorGroup: String = "",
    /** "accepted", "cancelled", "declined", "donated" */
    val status: String = "accepted",
    val createdAtMillis: Long = 0,
)
