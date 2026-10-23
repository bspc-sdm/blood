@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

// ------------------------------------------------------------
// অনলাইন (ভিডিও) চেম্বার — পারভেজ, ২৩ অক্টোবর ২০২৬ (backend/docs/CALLS.md "Online chambers")
// ডাক্তার চেম্বারের ধরন "অনলাইন (ভিডিও)" দিলে হাসপাতাল/ঠিকানা নেই, থাকে ফি পাঠানোর bKash নম্বর আর নোট।
// রোগী বুকিং নিশ্চিত হলে নিজের bKash থেকে ডাক্তারের নম্বরে টাকা পাঠিয়ে "টাকা পাঠিয়েছি" (TrxID ঐচ্ছিক) চাপেন;
// ডাক্তার/সহকারী মিলিয়ে "ফি পেয়েছি" বা "পাইনি"। Lifolo টাকা রাখে না। সময় হলে "ভিডিও কলে যুক্ত হোন" —
// কলের পর্দা কল-অ্যাপের (CallsNav.kt: openAppointmentCall, পথ appt_call/{appointmentId}); সময়ের হিসাব CallWindows (Calls.kt)।
// ------------------------------------------------------------

object OnlineChambers {
    /** সার্ভার অনলাইন চেম্বারের এই নামই দেয় (hospitalName / hospitalNameBn) */
    const val NAME_EN = "Online video consultation"
    const val NAME_BN = "অনলাইন ভিডিও পরামর্শ"
    const val NOTE_MAX = 200

    /** বাংলা অঙ্ক → ইংরেজি, ফাঁকা/ড্যাশ বাদ ("০১৭১১-২২৩৩৪৪" → "01711223344") */
    fun cleanNumber(raw: String): String =
        raw.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.filter { it.isDigit() || it == '+' }.joinToString("").take(14)

    /** bKash নম্বর: বাংলাদেশি মোবাইল (01[3-9]XXXXXXXX, +88 সহ চলে); ডেমো অ্যাকাউন্টে ডেমোর নম্বরও */
    fun bkashOk(raw: String, demo: Boolean = DemoLogin.isDemo(AuthRepository.currentUid)): Boolean = Rules.isMobile(cleanNumber(raw), demo)

    /** সেভের আগে একটা অনলাইন চেম্বারের ভুল; ঠিক থাকলে (বা অনলাইন না হলে) null */
    fun problem(c: Chamber, demo: Boolean = DemoLogin.isDemo(AuthRepository.currentUid)): String? = when {
        !c.online -> null
        !c.noFee && c.bkashNumber.isBlank() -> tr("Enter the bKash number patients send the fee to", "রোগীরা যে bKash নম্বরে ফি পাঠাবেন, সেটা দিন")
        c.bkashNumber.isNotBlank() && !bkashOk(c.bkashNumber, demo) -> tr("Enter a valid bKash number (01XXXXXXXXX)", "সঠিক bKash নম্বর দিন (01XXXXXXXXX)")
        !c.noFee && !hasAmount(c.fee) -> tr("Write the online chamber's fee (taka)", "অনলাইন চেম্বারের ফি লিখুন (টাকা)")
        c.note.length > NOTE_MAX -> tr("Keep the note within $NOTE_MAX letters", "নোট ${num(NOTE_MAX)} অক্ষরের মধ্যে রাখুন")
        hasPhone(c.note) -> NOTE_PHONE
        else -> null
    }

    val NOTE_PHONE: String get() = tr("The note can't have a phone or bKash number", "নোটে ফোন বা বিকাশ নম্বর লেখা যাবে না")

    /** ফিতে টাকার অঙ্ক আছে কি না ("৪০০ টাকা", "500") */
    fun hasAmount(fee: String): Boolean = cleanDigits(fee).any { it.isDigit() && it != '0' }

    /** নোটে ফোন নম্বরের মতো কিছু (ফাঁকা/ড্যাশ/বিন্দু বাদ দিয়ে টানা ১০+ অঙ্ক) — সার্ভার এমন নোট নেয় না */
    fun hasPhone(text: String): Boolean = Regex("\\d{10,}").containsMatchIn(cleanDigits(text).replace(Regex("(?<=\\d)[\\s.\\-()]+(?=\\d)"), ""))

    private fun cleanDigits(s: String): String = s.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")

    /** bKash নম্বর বদলেছে (সেভ করা নম্বর থেকে) — বদল ২৪ ঘণ্টা পর চালু */
    fun bkashChanged(c: Chamber): Boolean = c.online && c.bkashSaved.isNotBlank() && cleanNumber(c.bkashNumber) != cleanNumber(c.bkashSaved)

    /** চেম্বারে ↔ অনলাইন: অনলাইনে হাসপাতাল, ঠিকানা, রুম, সিরিয়ালের ফোন থাকে না; বুকিং অ্যাপেই */
    fun toOnline(c: Chamber): Chamber = c.copy(
        mode = "online", hospitalId = null, hospitalName = NAME_EN, hospitalNameBn = NAME_BN, district = "", upazilaId = null, unionId = null,
        room = "", serialPhone = "", custom = false, address = "", appointments = true,
    )

    fun toInPerson(c: Chamber): Chamber = c.copy(mode = "in_person", bkashNumber = "", note = "")
}

/** অনলাইন অ্যাপয়েন্টমেন্টের ফি (my_appointments / rx_patients-এর ঘর) */
data class OnlinePay(
    /** "unpaid" | "claimed" | "paid" (সার্ভার "rejected" দিলে সেটাও) */
    val state: String = "unpaid",
    /** রোগীর দেওয়া bKash TrxID */
    val ref: String = "",
    /** ডাক্তারের bKash নম্বর (শুধু রোগীর দিকে) */
    val bkashNumber: String = "",
    val fee: String = "",
    val noFee: Boolean = false,
    /** ডাক্তারের অনলাইন চেম্বারের নোট */
    val note: String = "",
    /** ডাক্তার "পাইনি" বললে কারণ */
    val rejectReason: String = "",
    val rejected: Boolean = false,
) {
    val view: PayView
        get() = when {
            noFee || state == "none" -> PayView.NO_FEE
            state == "paid" -> PayView.PAID
            state == "claimed" -> PayView.CLAIMED
            rejected || state == "rejected" -> PayView.REJECTED
            else -> PayView.UNPAID
        }

    /** "৳৫০০" (লেখা বোঝা না গেলে লেখাটাই) */
    val feeShort: String get() = ChamberClock.feeShort(Chamber(null, "", "", "", null, null, "", "", fee, false, "", noFee = noFee))

    companion object {
        fun parse(o: JSONObject): OnlinePay {
            // "পাইনি"-র কারণ: payNote (CHAMBERS-ONLINE.md); নতুন দাবি বা পরিশোধে মুছে যায়
            val reason = listOf("payNote", "payRejectReason", "payReject").firstNotNullOfOrNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotBlank() } }.orEmpty()
            return OnlinePay(
                // payState null = দেওয়ার কিছু নেই (ফি নেই)
                if (o.isNull("payState") || !o.has("payState")) "none" else o.str("payState").ifBlank { "none" }, o.str("payRef"), o.str("bkashNumber"), o.str("fee"), o.optBoolean("noFee"),
                o.str("onlineNote"), reason, o.optBoolean("payRejected") || reason.isNotBlank(),
            )
        }
    }
}

enum class PayView { NO_FEE, UNPAID, CLAIMED, PAID, REJECTED }

object OnlineText {
    /** staff: ডাক্তার/সহকারীর দিক থেকে লেখা */
    fun payLabel(v: PayView, staff: Boolean = false): String = when (v) {
        PayView.NO_FEE -> tr("No fee", "ফি নেই")
        PayView.UNPAID -> if (staff) tr("Fee not sent", "ফি পাঠানো হয়নি") else tr("Not sent", "পাঠানো হয়নি")
        PayView.CLAIMED -> if (staff) tr("Check the fee", "ফি যাচাই করুন") else tr("Being checked", "যাচাই হচ্ছে")
        PayView.PAID -> tr("Fee paid", "ফি পরিশোধিত")
        PayView.REJECTED -> if (staff) tr("Not received", "পাননি") else tr("Doctor didn't get it", "ডাক্তার পাননি")
    }

    const val JOIN = "ভিডিও কলে যুক্ত হোন"
    const val BKASH_24H = "নতুন নম্বর ২৪ ঘণ্টা পর চালু হবে"

    /** bKash TrxID: ঠিক ১০টা ইংরেজি অক্ষর/সংখ্যা */
    fun trxOk(t: String): Boolean = Regex("^[A-Za-z0-9]{10}$").matches(t)
    val TRX_HINT: String get() = tr("A bKash TrxID has exactly 10 English letters/digits", "bKash-এর TrxID ঠিক ১০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)")

    /** ফির কাজের ভুল বাংলায়: একই TrxID আবার (409), ভুল অবস্থা ইত্যাদি; বাকিগুলো সার্ভারের বার্তা */
    fun error(e: Throwable): String {
        val code = (e as? ServerError)?.code.orEmpty()
        return when {
            code.contains("trx", true) || code.contains("pay_ref", true) || code.contains("payref", true) || code == "duplicate" ->
                tr("This TrxID was already used. Check the bKash message and enter the right one.", "এই TrxID আগেই ব্যবহার হয়েছে। bKash-এর মেসেজ দেখে সঠিক TrxID দিন।")
            code == "pay_claim_rate" -> tr("Too many tries — try again in an hour.", "অনেকবার চেষ্টা হয়েছে — এক ঘণ্টা পরে আবার চেষ্টা করুন।")
            code == "not_online" -> tr("This is not an online appointment.", "এটা অনলাইন অ্যাপয়েন্টমেন্ট নয়।")
            code == "no_fee" -> tr("There is no fee for this appointment.", "এই অ্যাপয়েন্টমেন্টে ফি নেই।")
            code == "state" -> tr("Can't be done in this state — refresh the list.", "এই অবস্থায় করা যায় না — তালিকা রিফ্রেশ করে দেখুন।")
            else -> e.message ?: tr("Something went wrong", "সমস্যা হয়েছে")
        }
    }
    const val WAIT = "সময় হলে এখানে বোতাম আসবে"
}

object OnlineApi {
    private suspend fun call(action: String, body: JSONObject): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    /** রোগী: "টাকা পাঠিয়েছি" (TrxID ঐচ্ছিক) → claimed */
    suspend fun claim(appointmentId: String, payRef: String) {
        call("appt_pay_claim", JSONObject().put("appointmentId", appointmentId).apply { if (payRef.isNotBlank()) put("payRef", payRef.trim().take(10)) })
    }

    /** ডাক্তার/সহকারী: "ফি পেয়েছি" → paid */
    suspend fun confirm(appointmentId: String) {
        call("appt_pay_confirm", JSONObject().put("appointmentId", appointmentId))
    }

    /** রোগী: ফি দিয়েছেন কিন্তু ডাক্তার কলে আসেননি — অ্যাডমিন দেখবেন */
    suspend fun report(appointmentId: String, reason: String) {
        call("appt_report", JSONObject().put("appointmentId", appointmentId).put("reason", reason.trim().take(300)))
    }

    /** ডাক্তার/সহকারী: "পাইনি" (কারণ ঐচ্ছিক) — রোগী আবার পাঠাতে পারেন */
    suspend fun reject(appointmentId: String, reason: String) {
        call("appt_pay_reject", JSONObject().put("appointmentId", appointmentId).apply { if (reason.isNotBlank()) put("reason", reason.trim().take(200)) })
    }
}

// ------------------------------------------------------------
// ছোট অংশ: ব্যাজ
// ------------------------------------------------------------

@Composable
fun OnlineTag() = Tag(tr("Online", "অনলাইন"), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)

@Composable
fun PayStateTag(pay: OnlinePay, staff: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (pay.view) {
        PayView.PAID, PayView.NO_FEE -> cs.tertiaryContainer to cs.onTertiaryContainer
        PayView.CLAIMED -> cs.primaryContainer to cs.onPrimaryContainer
        PayView.REJECTED -> cs.errorContainer to cs.onErrorContainer
        PayView.UNPAID -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Tag(OnlineText.payLabel(pay.view, staff), bg, fg)
}

// ------------------------------------------------------------
// ডাক্তারের সেটআপ: চেম্বারের ধরন আর অনলাইন চেম্বারের ঘর
// ------------------------------------------------------------

/** "চেম্বারে" / "অনলাইন (ভিডিও)" — onlineAllowed: আরেকটা অনলাইন চেম্বার আগে থেকে থাকলে false (একটাই রাখা যায়) */
@Composable
fun ChamberModeChips(online: Boolean, onlineAllowed: Boolean, onSelect: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("Chamber type", "চেম্বারের ধরন"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !online,
                onClick = { if (online) onSelect(false) },
                label = { Text(tr("In chamber", "চেম্বারে")) },
                leadingIcon = { AppIcon(R.drawable.ic_local_hospital, null, Modifier.size(16.dp)) },
            )
            FilterChip(
                selected = online,
                onClick = { if (!online) onSelect(true) },
                enabled = online || onlineAllowed,
                label = { Text(tr("Online (video)", "অনলাইন (ভিডিও)")) },
                leadingIcon = { AppIcon(R.drawable.ic_online_video, null, Modifier.size(16.dp)) },
            )
        }
        if (!online && !onlineAllowed) SmallNote(tr("You already have an online chamber.", "অনলাইন চেম্বার একটাই রাখা যায় — আগেরটা আছে।"))
    }
}

/** নতুন চেম্বারের ধরন বাছাই (ডাক্তার "চেম্বার যোগ করুন" চাপলে) */
@Composable
fun ChamberTypeDialog(onlineAllowed: Boolean, inPersonAllowed: Boolean = true, onInPerson: () -> Unit, onOnline: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    @Composable
    fun Option(icon: Int, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = cs.primaryContainer, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { AppIcon(icon, null, Modifier.size(20.dp), cs.onPrimaryContainer) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Chamber type", "চেম্বারের ধরন")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Option(R.drawable.ic_local_hospital, tr("In chamber", "চেম্বারে"), if (inPersonAllowed) tr("At a hospital or clinic — choose it from the list", "হাসপাতাল/ক্লিনিকে — তালিকা থেকে বেছে নেবেন") else tr("Up to 3 chambers at a place", "জায়গায় সর্বোচ্চ ৩টা চেম্বার"), inPersonAllowed, onInPerson)
                Option(
                    R.drawable.ic_online_video, tr("Online (video)", "অনলাইন (ভিডিও)"),
                    if (onlineAllowed) tr("Video call in the app; the fee comes to your bKash", "অ্যাপে ভিডিও কলে; ফি আসবে আপনার bKash-এ")
                    else tr("You already have an online chamber", "অনলাইন চেম্বার একটাই রাখা যায় — আগেরটা আছে"),
                    onlineAllowed, onOnline,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** অনলাইন চেম্বারের মাথা: ভিডিওর চিহ্ন, নাম, সরানোর বোতাম */
@Composable
fun OnlineChamberHeader(onRemove: () -> Unit) {
    Row(Modifier.testTag("online_head"), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(R.drawable.ic_online_video, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(tr("Online (video) chamber", "অনলাইন (ভিডিও) চেম্বার"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান")) }
    }
}

/**
 * অনলাইন চেম্বারের ঘর (ChamberEditor-এর ভেতরে, হাসপাতাল/ঠিকানা/রুম/সিরিয়ালের ফোনের জায়গায়):
 * ফি (বা "ফি চাই না"), bKash নম্বর (যাচাই), নোট। বার আর সময় ChamberEditor-এর সাধারণ ঘরে।
 */
@Composable
fun OnlineChamberFields(c: Chamber, onChange: (Chamber) -> Unit) {
    OutlinedTextField(
        value = c.fee,
        onValueChange = { onChange(c.copy(fee = it.take(40))) },
        label = { Text(tr("Fee", "ফি")) },
        placeholder = { Text(tr("e.g. 500 Tk", "যেমন: ৫০০ টাকা")) },
        enabled = !c.noFee,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("online_fee"),
    )
    NoFeeCheck(c.noFee) { onChange(c.copy(noFee = it, fee = if (it) "" else c.fee)) }
    val bad = c.bkashNumber.isNotBlank() && !OnlineChambers.bkashOk(c.bkashNumber)
    OutlinedTextField(
        value = c.bkashNumber,
        onValueChange = { onChange(c.copy(bkashNumber = OnlineChambers.cleanNumber(it))) },
        label = { Text(if (c.noFee) tr("bKash number (optional)", "bKash নম্বর (ঐচ্ছিক)") else tr("bKash number for the fee", "ফি নেওয়ার bKash নম্বর")) },
        placeholder = { Text("01XXXXXXXXX") },
        supportingText = {
            Text(
                if (bad) tr("Enter a valid number: 01XXXXXXXXX", "সঠিক নম্বর দিন: 01XXXXXXXXX")
                else tr("Patients see it only after booking. The money comes straight to you.", "রোগীরা বুকিংয়ের পরেই দেখবেন। টাকা সরাসরি আপনার কাছে আসবে।"),
            )
        },
        isError = bad,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        leadingIcon = { AppIcon(R.drawable.ic_payments, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("online_bkash"),
    )
    // নম্বর বদলালে সাথে সাথে নয় — ২৪ ঘণ্টা পর চালু (ভুল/চুরি করা নম্বরে টাকা না যায়; ততক্ষণ নতুন বুকিংয়ে আগের নম্বর)
    val pendingUntil = c.bkashPendingUntil?.takeIf { it > System.currentTimeMillis() }
    if (OnlineChambers.bkashChanged(c) || pendingUntil != null) {
        IconLine(
            R.drawable.ic_schedule,
            if (pendingUntil != null && !OnlineChambers.bkashChanged(c)) tr("The new number starts ", "নতুন নম্বর চালু হবে ") + joinOpensText(pendingUntil, System.currentTimeMillis()) + tr("", " থেকে")
            else tr("The new number starts after 24 hours", OnlineText.BKASH_24H),
            MaterialTheme.colorScheme.tertiary,
        )
    }
    val phoneInNote = OnlineChambers.hasPhone(c.note)
    OutlinedTextField(
        value = c.note,
        onValueChange = { onChange(c.copy(note = it.take(OnlineChambers.NOTE_MAX))) },
        label = { Text(tr("Note for patients (optional)", "রোগীদের জন্য নোট (ঐচ্ছিক)")) },
        placeholder = { Text(tr("e.g. Keep your old reports ready", "যেমন: আগের রিপোর্টগুলো হাতের কাছে রাখবেন")) },
        supportingText = {
            Text(
                if (phoneInNote) OnlineChambers.NOTE_PHONE
                else tr("Only booked patients see it. No phone numbers.", "শুধু বুকিং করা রোগীরা দেখবেন। ফোন নম্বর দেবেন না।") + " " + num(c.note.length) + "/" + num(OnlineChambers.NOTE_MAX),
            )
        },
        isError = phoneInNote,
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("online_note"),
    )
}

// ------------------------------------------------------------
// অ্যাপয়েন্টমেন্ট: রোগীর দিক (ফি পাঠানো) আর ডাক্তার/সহকারীর দিক (ফি মেলানো), দুই দিকেই ভিডিও কলে যুক্ত হওয়া
// ------------------------------------------------------------

/** রোগী: ফি, ডাক্তারের bKash নম্বর (কপি), ধাপগুলো, "টাকা পাঠিয়েছি", অবস্থা; তারপর ভিডিও কলের বোতাম */
@Composable
fun PatientOnlinePanel(appointmentId: String, status: String, date: Long, start: String, pay: OnlinePay, onChanged: () -> Unit, now: Long? = null) {
    if (status !in listOf("pending", "confirmed", "completed")) return
    val context = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    var claiming by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    val v = pay.view
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("online_patient_panel")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_online_video, null, Modifier.size(18.dp), cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(tr("Online (video) visit", "অনলাইনে (ভিডিও) দেখাবেন"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PayStateTag(pay)
            }
            if (pay.note.isNotBlank()) IconLine(R.drawable.ic_info, pay.note)
            if (v != PayView.NO_FEE) {
                if (pay.fee.isNotBlank()) IconLine(R.drawable.ic_payments, tr("Fee: ", "ফি: ") + pay.feeShort)
                if (pay.bkashNumber.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Doctor's bKash (Send Money)", "ডাক্তারের bKash (সেন্ড মানি)"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Text(pay.bkashNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = {
                                clip.setText(androidx.compose.ui.text.AnnotatedString(pay.bkashNumber))
                                android.widget.Toast.makeText(context, tr("Number copied", "নম্বর কপি হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_bkash"),
                        ) { AppIcon(R.drawable.ic_content_copy, tr("Copy number", "নম্বর কপি করুন"), Modifier.size(20.dp)) }
                    }
                }
                when (v) {
                    PayView.UNPAID, PayView.REJECTED -> {
                        if (v == PayView.REJECTED) {
                            Text(
                                tr("The doctor didn't get the money", "ডাক্তার টাকা পাননি") + if (pay.rejectReason.isNotBlank()) ": " + pay.rejectReason else "",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.error,
                            )
                        }
                        PaySteps(pay)
                        Button(onClick = { claiming = true }, modifier = Modifier.fillMaxWidth()) {
                            ButtonContent(false, if (v == PayView.REJECTED) tr("I've sent it again", "আবার পাঠিয়েছি") else tr("I've sent the money", "টাকা পাঠিয়েছি"), R.drawable.ic_send)
                        }
                    }
                    PayView.CLAIMED -> {
                        if (pay.ref.isNotBlank()) Text("TrxID: " + pay.ref, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        SmallNote(tr("The doctor will check it in bKash and confirm.", "ডাক্তার bKash-এ মিলিয়ে নিশ্চিত করবেন।"))
                        TextButton(onClick = { claiming = true }) { Text(if (pay.ref.isBlank()) tr("Add TrxID", "TrxID দিন") else tr("Change TrxID", "TrxID বদলান")) }
                    }
                    PayView.PAID -> {
                        IconLine(R.drawable.ic_check_circle, tr("The doctor confirmed your fee", "ডাক্তার ফি পাওয়া নিশ্চিত করেছেন"), cs.tertiary)
                        // ফি দিয়েছেন, সময় হয়েছে, কিন্তু ডাক্তার কলে আসেননি — রিপোর্ট (appt_report)
                        if (OnlineAppts.canReport(status, date, start, now ?: System.currentTimeMillis())) {
                            if (reported) SmallNote(tr("Reported. Lifolo will look into it.", "রিপোর্ট পাঠানো হয়েছে। Lifolo দেখবে।"))
                            else TextButton(onClick = { reporting = true }, modifier = Modifier.testTag("appt_report")) {
                                Text(tr("Doctor didn't join? Report", "ডাক্তার কলে আসেননি? রিপোর্ট করুন"), color = cs.error)
                            }
                        }
                    }
                    PayView.NO_FEE -> {}
                }
                SmallNote(tr("The money goes straight to the doctor. Lifolo doesn't hold or take any of it.", "টাকা সরাসরি ডাক্তারের কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না।"))
            }
            VideoJoinBox(appointmentId, status, date, start, now)
        }
    }
    if (claiming) {
        PayClaimDialog(appointmentId, pay.ref, onDismiss = { claiming = false }, onDone = { claiming = false; onChanged() })
    }
    if (reporting) {
        ApptReportDialog(appointmentId, onDismiss = { reporting = false }, onDone = { reporting = false; reported = true })
    }
}

object OnlineAppts {
    /** "রিপোর্ট করুন": নিশ্চিত/সম্পন্ন অ্যাপয়েন্টমেন্ট, সময় শুরু হয়ে গেছে (শুরু না থাকলে সেই দিন থেকে), ৭ দিনের মধ্যে */
    fun canReport(status: String, date: Long, start: String, now: Long): Boolean {
        if (status != "confirmed" && status != "completed") return false
        val from = CallWindows.slotStart(date, start) ?: (date - Rules.DHAKA_OFFSET_MS)
        return now >= from && now < from + 7L * 24 * 60 * 60 * 1000
    }
}

/** রোগী: ফি দিয়েও ডাক্তার কলে আসেননি — কারণ বেছে বা লিখে রিপোর্ট */
@Composable
fun ApptReportDialog(appointmentId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reasons = listOf(
        tr("The doctor didn't join the call", "ডাক্তার কলে আসেননি"),
        tr("The doctor left without seeing me", "না দেখেই চলে গেছেন"),
        tr("Took the fee, no reply", "ফি নিয়েছেন, সাড়া নেই"),
    )
    var pick by remember { mutableStateOf(reasons[0]) }
    var more by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Report", "রিপোর্ট করুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("You paid the fee but the doctor didn't see you. Lifolo will check it.", "ফি দিয়েছেন কিন্তু ডাক্তার দেখেননি — Lifolo বিষয়টা দেখবে।"), style = MaterialTheme.typography.bodyMedium)
                ChipRows(reasons, setOf(pick), perRow = 1, label = { it }) { pick = it }
                OutlinedTextField(
                    more, { more = it.take(200) },
                    label = { Text(tr("More (optional)", "আরও কিছু (ঐচ্ছিক)")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { OnlineApi.report(appointmentId, listOf(pick, more.trim()).filter { it.isNotBlank() }.joinToString(" — ")) }
                        .onSuccess { onDone() }.onFailure { errorMsg = OnlineText.error(it) }
                    busy = false
                }
            }, enabled = !busy) { ButtonContent(busy, tr("Send report", "রিপোর্ট পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** টাকা পাঠানোর ধাপ (পাঠানো না হলে) */
@Composable
private fun PaySteps(pay: OnlinePay) {
    val steps = listOf(
        tr("Open bKash and Send Money", "bKash অ্যাপে \"সেন্ড মানি\" করুন") + (if (pay.fee.isNotBlank()) " — " + pay.feeShort else "") + tr(" to the number above", " উপরের নম্বরে"),
        tr("Keep the TrxID from the bKash message", "bKash-এর মেসেজের TrxID রেখে দিন"),
        tr("Tap \"I've sent the money\" — the doctor confirms it", "\"টাকা পাঠিয়েছি\" চাপুন — ডাক্তার মিলিয়ে নিশ্চিত করবেন"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, s ->
            Row {
                Text(num(i + 1) + ".", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "টাকা পাঠিয়েছি": TrxID ঐচ্ছিক */
@Composable
fun PayClaimDialog(appointmentId: String, initial: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var trx by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("I've sent the money", "টাকা পাঠিয়েছি"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Giving the TrxID helps the doctor find your payment.", "TrxID দিলে ডাক্তার সহজে আপনার টাকা খুঁজে পাবেন।"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    trx, { trx = it.filter { c -> c.isLetterOrDigit() && c.code < 128 }.uppercase().take(10) },
                    label = { Text(tr("TrxID (optional)", "TrxID (ঐচ্ছিক)")) },
                    supportingText = { Text(tr("10 English letters/digits, from the bKash message", "bKash মেসেজের ১০ অক্ষরের TrxID (ইংরেজি অক্ষর ও সংখ্যা)") + " · " + num(trx.length) + "/" + num(10)) },
                    isError = trx.isNotEmpty() && trx.length != 10,
                    placeholder = { Text("8N7A6B5C4D") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pay_trxid"),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    if (trx.isNotEmpty() && !OnlineText.trxOk(trx)) {
                        errorMsg = OnlineText.TRX_HINT
                    } else {
                        runCatching { OnlineApi.claim(appointmentId, trx) }.onSuccess { onDone() }.onFailure { errorMsg = OnlineText.error(it) }
                    }
                    busy = false
                }
            }, enabled = !busy) { ButtonContent(busy, tr("Let the doctor know", "ডাক্তারকে জানান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ডাক্তার/সহকারী: ফির অবস্থা, TrxID, "ফি পেয়েছি" / "পাইনি" (নিশ্চিত করার ধাপসহ); তারপর ভিডিও কলের বোতাম */
@Composable
fun StaffOnlinePanel(appointmentId: String, status: String, date: Long, start: String, pay: OnlinePay, onChanged: () -> Unit, now: Long? = null) {
    if (status !in listOf("pending", "confirmed", "completed")) return
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var asking by remember { mutableStateOf<Boolean?>(null) } // true = পেয়েছি, false = পাইনি
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val v = pay.view
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("online_staff_panel")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_online_video, null, Modifier.size(18.dp), cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(tr("Online (video)", "অনলাইন (ভিডিও)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PayStateTag(pay, staff = true)
            }
            if (v != PayView.NO_FEE) {
                if (pay.fee.isNotBlank()) IconLine(R.drawable.ic_payments, tr("Fee: ", "ফি: ") + pay.feeShort)
                if (pay.ref.isNotBlank()) Text("TrxID: " + pay.ref, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                when (v) {
                    PayView.CLAIMED -> SmallNote(
                        if (pay.ref.isNotBlank()) tr("The patient says they sent it. Check this TrxID in your bKash.", "রোগী টাকা পাঠিয়েছেন বলছেন। আপনার bKash-এ এই TrxID মিলিয়ে দেখুন।")
                        else tr("The patient says they sent it (no TrxID). Check your bKash.", "রোগী টাকা পাঠিয়েছেন বলছেন (TrxID দেননি)। আপনার bKash দেখে নিন।"),
                    )
                    PayView.UNPAID -> SmallNote(tr("Not sent yet. You can still take the call — your choice.", "এখনো পাঠাননি। চাইলে তবুও কলে যুক্ত হতে পারেন — আপনার ইচ্ছা।"))
                    PayView.REJECTED -> SmallNote(tr("You said you didn't get it", "আপনি জানিয়েছেন টাকা পাননি") + if (pay.rejectReason.isNotBlank()) ": " + pay.rejectReason else "")
                    else -> {}
                }
                if (v != PayView.PAID) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction(tr("Fee received", "ফি পেয়েছি"), R.drawable.ic_check_circle, { asking = true }, primary = true, busy = busy && asking == true, enabled = !busy)
                        if (v == PayView.CLAIMED) SmallAction(tr("Not received", "পাইনি"), null, { asking = false }, busy = busy && asking == false, enabled = !busy)
                    }
                }
                ErrorText(errorMsg)
            }
            VideoJoinBox(appointmentId, status, date, start, now)
        }
    }
    when (asking) {
        true -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!busy) asking = null },
            title = { Text(tr("Got the fee?", "ফি পেয়েছেন?")) },
            text = {
                Text(
                    (if (pay.ref.isNotBlank()) "TrxID: ${pay.ref}\n" else "") +
                        tr("The patient will see \"Fee paid\". Confirm only after checking bKash.", "রোগী দেখবেন \"ফি পরিশোধিত\"। bKash মিলিয়ে তবেই নিশ্চিত করুন।"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { OnlineApi.confirm(appointmentId) }.onSuccess { asking = null; onChanged() }.onFailure { errorMsg = OnlineText.error(it); asking = null }
                        busy = false
                    }
                }, enabled = !busy) { ButtonContent(busy, tr("Yes, received", "হ্যাঁ, পেয়েছি")) }
            },
            dismissButton = { TextButton(onClick = { asking = null }, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
        )
        false -> {
            var reason by remember { mutableStateOf("") }
            FormDialog(
                title = tr("Didn't get the fee?", "ফি পাননি?"),
                onDismiss = { if (!busy) asking = null },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("The patient will be told and can send it again.", "রোগীকে জানানো হবে, উনি আবার পাঠাতে পারবেন।"), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            reason, { reason = it.take(200) },
                            label = { Text(tr("Reason (optional)", "কারণ (ঐচ্ছিক)")) },
                            placeholder = { Text(tr("e.g. This TrxID didn't match", "যেমন: এই TrxID মেলেনি")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { OnlineApi.reject(appointmentId, reason) }.onSuccess { asking = null; onChanged() }.onFailure { errorMsg = OnlineText.error(it); asking = null }
                            busy = false
                        }
                    }, enabled = !busy) { ButtonContent(busy, tr("Tell the patient", "রোগীকে জানান")) }
                },
                dismissButton = { TextButton(onClick = { asking = null }, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
            )
        }
        null -> {}
    }
}

/**
 * "ভিডিও কলে যুক্ত হোন" — শুধু সময়ের মধ্যে (শুরুর ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর, CallWindows.canJoin);
 * আগে "সময় হলে এখানে বোতাম আসবে"। চাপলে কলের পর্দা (openAppointmentCall → appt_call/{appointmentId})।
 * now: টেস্টে নির্দিষ্ট সময়; null হলে প্রতি ৩০ সেকেন্ডে নিজে মিলিয়ে নেয়।
 */
@Composable
fun VideoJoinBox(appointmentId: String, status: String, date: Long, start: String, now: Long? = null) {
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (now == null) {
        LaunchedEffect(appointmentId) {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                tick = System.currentTimeMillis()
            }
        }
    }
    val t = now ?: tick
    when {
        status == "pending" -> SmallNote(tr("The video call button comes once the doctor confirms.", "ডাক্তার নিশ্চিত করলে ভিডিও কলের বোতাম আসবে।"))
        status != "confirmed" -> {}
        CallWindows.canJoin(status, date, start, t) -> Button(
            onClick = { openAppointmentCall(context, appointmentId) },
            modifier = Modifier.fillMaxWidth().testTag("appt_join"),
        ) { ButtonContent(false, tr("Join the video call", OnlineText.JOIN), R.drawable.ic_online_video) }
        else -> {
            val opens = CallWindows.joinOpensAt(status, date, start, t)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_online_video, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (opens != null) tr("The button comes here when it's time", OnlineText.WAIT) else tr("The video call time is over", "ভিডিও কলের সময় পেরিয়ে গেছে"),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (opens != null) SmallNote(tr("From ", "") + joinOpensText(opens, t) + tr("", " থেকে"))
                }
            }
        }
    }
}

/** "আজ, বিকাল ৪:৪৫" / "২৫ অক্টোবর ২০২৬, বিকাল ৪:৪৫" (ঢাকার সময়) */
internal fun joinOpensText(at: Long, now: Long): String {
    val day = ChamberClock.dhakaToday(at)
    val today = ChamberClock.dhakaToday(now)
    val d = when (day) {
        today -> tr("today", "আজ")
        today + 24L * 60 * 60 * 1000 -> tr("tomorrow", "আগামীকাল")
        else -> Rules.formatDate(day)
    }
    return d + ", " + Rules.formatTime(at)
}

/** ডাক্তার: ফি পাঠানো/দেওয়া অনলাইন অ্যাপয়েন্টমেন্ট বাতিল বা না করতে কারণ (রোগী দেখবেন; টাকা ফেরতের কথা ডাক্তারের সাথে) */
@Composable
fun OnlineCancelReasonDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    FormDialog(
        title = tr("Reason needed", "কারণ লিখুন"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("The patient has sent or paid the fee. Tell them why — they'll contact you about the refund.", "রোগী ফি পাঠিয়েছেন/দিয়েছেন। কেন বাতিল করছেন লিখুন — টাকা ফেরতের জন্য উনি আপনার সাথে যোগাযোগ করবেন।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    reason, { reason = it.take(200) },
                    label = { Text(tr("Reason for the patient", "রোগীর জন্য কারণ")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(reason.trim()) }, enabled = reason.trim().length >= 3) { Text(tr("Send", "পাঠান")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}
