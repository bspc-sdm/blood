package com.bspc.blood

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

/**
 * অনলাইন চেম্বারের অ্যাপয়েন্টমেন্টে "ভিডিও কলে যুক্ত হোন" — যুক্ত হওয়ার পর্দা (অপেক্ষার ঘর, তারপর ভিডিও কল)।
 * পথ (route) "appt_call/{appointmentId}": অন্য পাতা (অ্যাপয়েন্টমেন্টের তালিকা, সহকারীর পাতা) এটাই ডাকে;
 * পুশ `appt_call_ready` চাপলেও এখানে আসে (NotifyRoute)। পর্দা আলাদা activity (CallActivity), লক স্ক্রিনেও খোলে।
 */
fun openAppointmentCall(context: Context, appointmentId: String) {
    if (appointmentId.isBlank()) return
    val i = Intent(context, CallActivity::class.java)
        .setAction(CallIntents.ACTION_APPT_JOIN)
        .putExtra(CallIntents.EXTRA_APPOINTMENT_ID, appointmentId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }
}

/** "appt_call/{appointmentId}" → appointmentId (অন্য রূপে null) */
fun apptCallRouteId(route: String): String? =
    route.removePrefix("/").takeIf { it.startsWith("appt_call/") }?.removePrefix("appt_call/")?.takeIf { Regex("^[A-Za-z0-9_-]{1,64}$").matches(it) }

fun apptCallRoute(appointmentId: String) = "appt_call/$appointmentId"

/** CallActivity / CallService / CallActionReceiver-এর intent-এর নাম */
object CallIntents {
    const val ACTION_APPT_JOIN = "com.bspc.blood.call.APPT_JOIN"
    const val ACTION_INCOMING = "com.bspc.blood.call.INCOMING"
    const val ACTION_ACCEPT = "com.bspc.blood.call.ACCEPT"
    const val ACTION_DECLINE = "com.bspc.blood.call.DECLINE"
    const val ACTION_HANGUP = "com.bspc.blood.call.HANGUP"
    const val ACTION_SHOW = "com.bspc.blood.call.SHOW"
    const val EXTRA_APPOINTMENT_ID = "appointmentId"
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_VIDEO = "video"
    const val EXTRA_PEER_NAME = "peerName"
    const val EXTRA_PEER_PHOTO = "peerPhoto"
    const val EXTRA_CTX = "ctx"
    const val EXTRA_REF_ID = "refId"
}

/** প্রোফাইলের "কল" → কলের তালিকা (App খোলে) */
object CallNav {
    val history = androidx.compose.runtime.mutableStateOf(false)
}

/** ফিচার সুইচের নাম (scratchpad/flags/CONTRACT.md) — বন্ধ থাকলে কলের সব ঢোকার পথ লুকানো */
object CallFeatures {
    /** অ্যাপে অডিও কল: কলের বাটন, কলের তালিকা, সেটিংসের "অ্যাপে কল", যোগাযোগ বন্ধের তালিকা */
    const val AUDIO = Features.CALLS_AUDIO
    /** ভিডিও কল, "ভিডিওতে ধরুন", ভিডিও চালু করতে অনুরোধ */
    const val VIDEO = Features.CALLS_VIDEO
    /** অনলাইন অ্যাপয়েন্টমেন্টের ভিডিও কলে যুক্ত হওয়া (appt_call/{id}) */
    const val APPT = Features.APPT_VIDEO

    fun comingSoon() = Features.soonText()
}

/** ফিচার চালু কি না — বদলালে পাতা নতুন করে আঁকে */
@androidx.compose.runtime.Composable
fun rememberFeatureOn(key: String): Boolean {
    return featureOn(key)
}

/** বন্ধ সুবিধার পাতায় (লিংক/নোটিফিকেশন থেকে) */
@androidx.compose.runtime.Composable
fun ComingSoonScreen(onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(topBar = { BackTopBar("", onBack) }) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            EmptyState(R.drawable.ic_schedule, CallFeatures.comingSoon(), null)
        }
    }
}
