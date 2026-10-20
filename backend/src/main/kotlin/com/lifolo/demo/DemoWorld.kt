package com.lifolo.demo

/**
 * Demo vs real world (server/lib/demo/index.js). Demo accounts have uids starting with "demo-";
 * demo and real users never see/notify/answer each other (sameWorld). Tables carry a generated `is_demo`.
 */
object DemoWorld {
    const val DOMAIN = "lifolo-demo.test"

    fun uidOf(key: String) = "demo-$key"

    fun isDemoUid(uid: String?): Boolean = uid != null && uid.startsWith("demo-")

    fun sameWorld(a: String?, b: String?): Boolean = isDemoUid(a) == isDemoUid(b)

    fun demoEmail(key: String) = "$key@$DOMAIN"

    fun isDemoEmail(email: String?): Boolean = email != null && email.endsWith("@$DOMAIN")

    data class Role(val key: String, val name: String, val bn: String)

    /** login-able demo roles (key = email local part, uid fixed) */
    val ROLES = listOf(
        Role("patient", "ডেমো রোগী রহিম", "সাধারণ ব্যবহারকারী / রোগী (রক্ত চায়, অ্যাপয়েন্টমেন্ট নেয়, প্রেসক্রিপশন পায়)"),
        Role("donor", "ডেমো ডোনার করিম", "রক্তদাতা"),
        Role("doctor", "ডা. ডেমো হাসান", "ডাক্তার"),
        Role("assistant", "ডেমো সহকারী সুমন", "ডাক্তারের সহকারী"),
        Role("pharmacy", "ডেমো ফার্মেসির মালিক", "ফার্মেসি"),
        Role("lab", "ডেমো প্যাথলজির মালিক", "প্যাথলজি / ল্যাব"),
        Role("ambulance", "ডেমো অ্যাম্বুলেন্স চালক", "অ্যাম্বুলেন্স"),
        Role("bloodbank", "ডেমো ব্লাড ব্যাংক", "ব্লাড ব্যাংক"),
        Role("homecare", "ডেমো নার্স নাসরিন", "বাসায় সেবা (নার্স)"),
        Role("caregiver", "ডেমো কেয়ারগিভার রুমা", "কেয়ারগিভার (রোগীর হয়ে কাজ করেন)"),
    )
}
