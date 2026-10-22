package com.bspc.blood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * নোটিফিকেশনের ধরন (cat) → চ্যানেল: সার্ভারের `cat` থাকলে সেটাই; না থাকলে (পুরোনো সার্ভার) পুশের type থেকে —
 * backend-এর প্রতিটা পুশের data (NotifyRouteTest-এর মতো একই তালিকা)।
 */
class NotifyCatsTest {
    private fun c(vararg kv: Pair<String, String>) = NotifyCats.catOf(kv.toMap())

    @Test
    fun serverCatWins() {
        NotifyCats.ALL.forEach { assertEquals(it, c("cat" to it, "type" to "request", "requestId" to "r1")) }
        // সার্ভার অন্য ধরন বললে (যেমন ডোনারকে রিকোয়েস্ট বন্ধের খবর my_request নয় blood_nearby) — সার্ভারের কথাই
        assertEquals(NotifyCats.MY_REQUEST, c("cat" to "my_request", "type" to "request"))
        assertEquals(NotifyCats.BUSINESS, c("cat" to " business ", "type" to "services", "kind" to "ambulance", "sub" to "amb_req"))
    }

    @Test
    fun unknownOrBlankCatFallsBackToType() {
        assertEquals(NotifyCats.BLOOD_EMERGENCY, c("cat" to "", "type" to "emergency"))
        assertEquals(NotifyCats.BLOOD_EMERGENCY, c("cat" to "something_new", "type" to "emergency"))
        // অচেনা ধরন আর অচেনা type: অ্যাকাউন্ট (সবসময় চালু চ্যানেল) — খবর হারায় না
        assertEquals(NotifyCats.ACCOUNT, c("cat" to "something_new", "type" to "something_new"))
        assertEquals(NotifyCats.ACCOUNT, c())
    }

    @Test
    fun everyOldServerPushTypeHasACategory() {
        // রক্ত
        assertEquals(NotifyCats.BLOOD_NEARBY, c("type" to "request", "requestId" to "r1"))
        assertEquals(NotifyCats.BLOOD_NEARBY, c("type" to "eligible"))
        assertEquals(NotifyCats.BLOOD_EMERGENCY, c("type" to "emergency", "emergencyId" to "e1"))
        assertEquals(NotifyCats.MY_REQUEST, c("type" to "response", "requestId" to "r1"))
        assertEquals(NotifyCats.MY_REQUEST, c("type" to "request_removed"))
        // অ্যাপয়েন্টমেন্ট, বাসায় সেবা
        assertEquals(NotifyCats.APPOINTMENTS, c("type" to "appointment", "role" to "patient"))
        assertEquals(NotifyCats.APPOINTMENTS, c("type" to "appointment", "role" to "provider"))
        assertEquals(NotifyCats.APPOINTMENTS, c("type" to "homecare"))
        assertEquals(NotifyCats.APPOINTMENTS, c("type" to "homevisit"))
        // প্রেসক্রিপশন, ওষুধ, ল্যাব, কেয়ারগিভার
        assertEquals(NotifyCats.RX, c("type" to "rx", "rxId" to "x1"))
        assertEquals(NotifyCats.RX, c("type" to "med_find_mine"))
        // অ্যাম্বুলেন্স: রোগীর ডাকা, আর চালককে ডাক
        assertEquals(NotifyCats.AMBULANCE, c("type" to "amb_req_mine", "reqId" to "a1"))
        assertEquals(NotifyCats.AMBULANCE, c("type" to "amb_call"))
        assertEquals(NotifyCats.AMBULANCE, c("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to "a1"))
        // প্রশ্নোত্তর
        assertEquals(NotifyCats.COMMUNITY, c("type" to "answer", "questionId" to "q1"))
        // ব্যবসা
        assertEquals(NotifyCats.BUSINESS, c("type" to "services", "kind" to "ambulance"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "services", "kind" to "bloodbank"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "services"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "rx_pharmacy"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "rx_lab"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "med_find"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "pharmacy_order"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "pharmacy_mine"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "rx_review"))
        assertEquals(NotifyCats.BUSINESS, c("type" to "badge"))
        // অ্যাকাউন্ট (সবসময় চালু); কেয়ারগিভারের কাছে রোগীর খবরে নতুন সার্ভার নিজেই cat দেয়
        assertEquals(NotifyCats.RX, c("type" to "care", "asUid" to "p1", "rxId" to "x1", "cat" to "rx"))
        listOf("care", "correction", "support", "hospital").forEach { assertEquals(it, NotifyCats.ACCOUNT, c("type" to it)) }
        // অ্যাপে কল: আসা কল/মিসড কল/কলের অবস্থা — calls; "ডাক্তার ভিডিও কলে আছেন" — অ্যাপয়েন্টমেন্ট
        listOf("call_incoming", "call_missed", "call_state").forEach { assertEquals(it, NotifyCats.CALLS, c("type" to it, "callId" to "c1")) }
        assertEquals(NotifyCats.APPOINTMENTS, c("type" to "appt_call_ready", "appointmentId" to "a1"))
    }

    @Test
    fun channelPerCategory() {
        assertEquals(10, NotifyCats.ALL.size)
        assertEquals(NotifyCats.ALL.size, NotifyCats.ALL.map(NotifyCats::channelId).toSet().size)
        assertEquals(
            listOf(
                "cat_blood_nearby", "cat_blood_emergency", "cat_my_request", "cat_appointments", "cat_rx",
                "cat_ambulance", "cat_community", "cat_business", "cat_calls", "cat_account",
            ),
            NotifyCats.ALL.map(NotifyCats::channelId),
        )
        // আগের একমাত্র চ্যানেলের নাম নতুন কোনোটার সাথে মেলে না (মুছে ফেলা হয়)
        assertFalse(NotifyCats.OLD_CHANNEL_ID in NotifyCats.ALL.map(NotifyCats::channelId))
        // জরুরি, অ্যাম্বুলেন্স, অ্যাপয়েন্টমেন্ট জোরালো; প্রশ্নোত্তর/ব্যবসা/অ্যাকাউন্ট সাধারণ
        listOf(NotifyCats.BLOOD_EMERGENCY, NotifyCats.AMBULANCE, NotifyCats.APPOINTMENTS, NotifyCats.CALLS).forEach { assertTrue(it, it in NotifyCats.HIGH) }
        listOf(NotifyCats.COMMUNITY, NotifyCats.BUSINESS, NotifyCats.ACCOUNT, NotifyCats.RX).forEach { assertFalse(it, it in NotifyCats.HIGH) }
        // account সেটিংসে বদলানো যায় না; জরুরি দুটো বন্ধের আগে নিশ্চিত করা
        assertFalse(NotifyCats.ACCOUNT in NotifyCats.SETTABLE)
        assertEquals(setOf(NotifyCats.BLOOD_EMERGENCY, NotifyCats.AMBULANCE), NotifyCats.CONFIRM_OFF)
        // প্রতিটা ধরনের নাম আর বিবরণ আছে
        NotifyCats.ALL.forEach { assertTrue(it, NotifyCats.label(it).isNotBlank() && NotifyCats.description(it).isNotBlank()) }
    }

    @Test
    fun prefs() {
        val p = NotifyPrefs.of(true, mapOf(NotifyCats.COMMUNITY to false))
        // উত্তরে না থাকা ধরন চালু
        assertEquals(NotifyCats.SETTABLE.toSet(), p.cats.keys)
        assertTrue(p.isOn(NotifyCats.RX))
        assertFalse(p.isOn(NotifyCats.COMMUNITY))
        assertFalse(p.delivers(NotifyCats.COMMUNITY))
        assertTrue(p.delivers(NotifyCats.RX))
        // সব বন্ধ: শুধু অ্যাকাউন্ট
        val off = p.copy(all = false)
        NotifyCats.SETTABLE.forEach { assertFalse(it, off.delivers(it)) }
        assertTrue(off.delivers(NotifyCats.ACCOUNT))
        assertTrue(off.isOn(NotifyCats.RX))
        // একটা বদল বাকিগুলো ছোঁয় না
        val q = p.with(NotifyCats.COMMUNITY, true).with(NotifyCats.AMBULANCE, false)
        assertTrue(q.isOn(NotifyCats.COMMUNITY))
        assertFalse(q.isOn(NotifyCats.AMBULANCE))
        assertTrue(q.isOn(NotifyCats.BLOOD_NEARBY))
    }
}
