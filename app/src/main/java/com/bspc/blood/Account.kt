package com.bspc.blood

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ------------------------------------------------------------
// অ্যাকাউন্টের অবস্থা: চালু, সাময়িক বন্ধ (account_deactivate), মোছার অপেক্ষায় (delete_account, ৩০ দিন)
// সার্ভারের `account` (profile_get আর লগইনের উত্তরে) — backend/docs/API-INVENTORY.md "Account state…"।
// সাময়িক বন্ধ থাকলে সার্ভার বাকি সব কাজে 423 `account_deactivated` দেয় — তখন পুরো অ্যাপ আটকে "আবার চালু করুন"।
// লেখা ওয়েবের সাথে মেলানো (web-vue: DeleteAccountPage, DeactivateAccountPage, AccountPaused)।
// ------------------------------------------------------------

/** সার্ভারের `account` অবজেক্ট */
data class AccountInfo(
    val state: String,
    val deactivatedAt: Long? = null,
    val deletionRequestedAt: Long? = null,
    val deletesAt: Long? = null,
) {
    val deactivated: Boolean get() = state == DEACTIVATED

    companion object {
        const val ACTIVE = "active"
        const val DEACTIVATED = "deactivated"
        const val DELETING = "deleting"

        /** মিলিসেকেন্ড: সংখ্যা বা সংখ্যার লেখা; না থাকলে/অচেনা হলে null */
        internal fun millis(v: Any?): Long? = when (v) {
            is Number -> v.toLong().takeIf { it > 0 }
            is String -> v.trim().toLongOrNull()?.takeIf { it > 0 }
            else -> null
        }

        /** অবস্থা অচেনা হলে null (মানে কিছু জানা নেই — চালু ধরে নেওয়া) */
        fun of(state: Any?, deactivatedAt: Any? = null, deletionRequestedAt: Any? = null, deletesAt: Any? = null): AccountInfo? {
            val s = (state as? String)?.trim() ?: return null
            if (s != ACTIVE && s != DEACTIVATED && s != DELETING) return null
            return AccountInfo(s, millis(deactivatedAt), millis(deletionRequestedAt), millis(deletesAt))
        }

        fun parse(o: JSONObject?): AccountInfo? {
            if (o == null) return null
            fun g(k: String): Any? = if (o.has(k) && !o.isNull(k)) o.opt(k) else null
            return of(g("state"), g("deactivatedAt"), g("deletionRequestedAt"), g("deletesAt"))
        }
    }
}

object AccountState {
    /** মোছার পর যতদিন ফেরানো যায় (সার্ভারের GRACE) */
    const val DELETE_GRACE_DAYS = 30
    private const val DAY_MS = 24L * 3600 * 1000

    /** এখনকার অ্যাকাউন্টের অবস্থা; null = জানা নেই (চালু ধরে নেওয়া) */
    val info = mutableStateOf<AccountInfo?>(null)

    /** শেষ কবে আবার চালু হলো — এর আগে পাঠানো অনুরোধের পুরোনো 423 উত্তর আর আটকায় না */
    @Volatile var reactivatedAt = 0L

    /** আজ মুছলে কবে চিরতরে মুছবে */
    fun deleteDateFrom(now: Long): Long = now + DELETE_GRACE_DAYS * DAY_MS

    /** তারিখ (ঢাকার দিন): "২২ নভেম্বর ২০২৬" / "22 Nov 2026" */
    fun formatDay(millis: Long, bangla: Boolean = isBangla): String {
        val f = if (bangla) SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("bn-BD")) else SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        f.timeZone = TimeZone.getTimeZone("Asia/Dhaka")
        return f.format(Date(millis))
    }

    /** লগইন করা আর অ্যাকাউন্ট সাময়িক বন্ধ — পুরো অ্যাপ আটকে "আবার চালু করুন" */
    fun blocked(loggedIn: Boolean, a: AccountInfo?): Boolean = loggedIn && a?.deactivated == true

    /** প্রোফাইল/লগইনের উত্তরের `account` (না থাকলে — পুরোনো সার্ভার — যা ছিল তাই) */
    fun fromServer(o: JSONObject?) {
        AccountInfo.parse(o)?.let { info.value = it }
    }

    /** কোনো কাজে 423 `account_deactivated` এল (startedAt: অনুরোধ কখন গিয়েছিল); লগআউটের পর আসা উত্তর বাদ */
    fun onDeactivatedError(startedAt: Long, loggedIn: Boolean = AuthRepository.currentUid != null) {
        if (!shouldBlockOn423(startedAt, reactivatedAt, loggedIn)) return
        info.value = (info.value ?: AccountInfo(AccountInfo.DEACTIVATED)).copy(state = AccountInfo.DEACTIVATED)
    }

    internal fun shouldBlockOn423(startedAt: Long, reactivatedAt: Long, loggedIn: Boolean): Boolean =
        loggedIn && startedAt >= reactivatedAt

    /** account_reactivate-এর পর: সার্ভারের অবস্থা (এখনো "বন্ধ" বললেও চালু ধরা — সার্ভার ok দিয়েছে) */
    fun afterReactivate(server: AccountInfo?, now: Long = System.currentTimeMillis()) {
        reactivatedAt = now
        info.value = server?.takeIf { !it.deactivated } ?: AccountInfo(AccountInfo.ACTIVE)
    }

    /** লগআউট: আগের মানুষের অবস্থা থাকে না */
    fun clear() {
        info.value = null
    }
}

/** অ্যাকাউন্ট মোছা / সাময়িক বন্ধ / আবার চালু — সার্ভারের কাজ */
object AccountApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    /** delete_account {ack:true} ("আমি বুঝেছি" টিক) → কবে মুছবে (সার্ভার না বললে null) */
    suspend fun deleteAccount(): Long? = AccountInfo.millis(call("delete_account", JSONObject().put("ack", true)).opt("deleteAt"))

    /** account_deactivate — সার্ভার এই ফোনসহ সব লগইন বাতিল করে; এরপর লগআউট */
    suspend fun deactivate() {
        call("account_deactivate")
    }

    /** account_reactivate → নতুন অবস্থা */
    suspend fun reactivate(): AccountInfo? = AccountInfo.parse(call("account_reactivate").optJSONObject("account"))
}

/** প্রোফাইলের "অ্যাকাউন্ট মুছুন" / "সাময়িক বন্ধ রাখুন" — MainActivity পাতাটা খোলে */
object AccountNav {
    const val DELETE = "delete"
    const val DEACTIVATE = "deactivate"
    val open = mutableStateOf<String?>(null)
}

// ------------------------------------------------------------
// পাতা
// ------------------------------------------------------------

@Composable
private fun Bullets(items: List<String>, tag: String? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        items.forEach { t ->
            Row {
                Text("•", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(color: Color = MaterialTheme.colorScheme.surfaceContainerLow, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun DoneHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(R.drawable.ic_check_circle, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * "অ্যাকাউন্ট মুছুন" — পুরো পাতা (আগে ছোট ডায়ালগ): ওপরে বিকল্প "মুছে না ফেলে সাময়িক বন্ধ রাখুন", কী মুছবে আর কী থাকে
 * (PRIVACY.md-এর "অ্যাকাউন্ট মোছা"), ৩০ দিনে ফেরানো আর আসল তারিখ, "আমি বুঝেছি" টিক না দিলে লাল বোতাম চাপা যায় না।
 * মোছা হলে (delete_account {ack:true}) এই ফোন থেকে লগআউট, আর এরপর কী হবে (ইমেইল গেছে)। onFinished: হোমে (অতিথি হিসেবে)।
 * paused: অ্যাকাউন্ট এখন সাময়িক বন্ধ (আটকানো পর্দা থেকে আসা) — তখন "সাময়িক বন্ধ রাখুন" বিকল্প দেখায় না।
 */
@Composable
fun DeleteAccountScreen(
    email: String?,
    onBack: () -> Unit,
    onPauseInstead: () -> Unit,
    onFinished: () -> Unit,
    paused: Boolean = false,
    /** পরীক্ষায় সার্ভারের বদলে */
    doDelete: suspend () -> Long? = { AccountApi.deleteAccount() },
    afterDelete: suspend () -> Unit = { AuthRepository.signOutLocal() },
) {
    val scope = rememberCoroutineScope()
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** মোছার অনুরোধ হয়ে গেছে: কবে মুছবে */
    var doneAt by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val lastDay = remember { AccountState.formatDay(AccountState.deleteDateFrom(System.currentTimeMillis())) }

    // মোছার পর আর পেছনে ফেরা নয় (লগআউট হয়ে গেছে) — হোমে
    BackHandler(enabled = doneAt != null) { onFinished() }

    Scaffold(topBar = { BackTopBar(tr("Delete account", "অ্যাকাউন্ট মুছুন"), { if (doneAt != null) onFinished() else onBack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val done = doneAt
            if (done != null) {
                val day = AccountState.formatDay(done)
                SectionCard(tag = "delete_done") {
                    DoneHeader(tr("Deletion requested", "মোছার অনুরোধ নেওয়া হয়েছে"))
                    Text(tr("Your account will be deleted on $day.", "আপনার অ্যাকাউন্ট $day তারিখে মুছে যাবে।"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("What happens next", "এরপর কী হবে"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Bullets(
                        listOf(
                            tr("Now: your listings are hidden, notifications stop and you are logged out everywhere.", "এখন: আপনার তালিকা লুকানো, নোটিফিকেশন বন্ধ, সব জায়গা থেকে লগআউট।"),
                            tr("Until $day: log in again (app or web) and the deletion is cancelled — everything comes back.", "$day পর্যন্ত: আবার লগইন করলেই (অ্যাপে বা ওয়েবে) মোছা বাতিল, সব আগের মতো ফিরবে।"),
                            tr("After $day: deleted for good — it cannot be brought back.", "$day-এর পর: চিরতরে মুছে যাবে — আর ফেরানো যাবে না।"),
                        ),
                    )
                    Text(
                        if (!email.isNullOrBlank()) tr("We sent an email to $email: the date, what will be deleted and how to cancel.", "$email ঠিকানায় একটা ইমেইল পাঠানো হয়েছে: কোন তারিখে মুছবে, কী কী মুছবে আর কীভাবে বাতিল করবেন।")
                        else tr("We sent an email to your account email: the date, what will be deleted and how to cancel.", "আপনার অ্যাকাউন্টের ইমেইলে একটা ইমেইল পাঠানো হয়েছে: কোন তারিখে মুছবে, কী কী মুছবে আর কীভাবে বাতিল করবেন।"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        tr("You have been logged out on every device. Log in again before then if you change your mind — that cancels the deletion.", "সব ফোন আর ব্রাউজার থেকে লগআউট হয়েছে। মত বদলালে এর আগে আবার লগইন করুন — তাতে মোছা বাতিল হবে।"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onFinished, modifier = Modifier.testTag("delete_home")) { Text(tr("Go to home", "হোমে যান")) }
                }
                return@Column
            }

            // বিকল্প: না মুছে সাময়িক বন্ধ
            if (!paused) {
                SectionCard(color = MaterialTheme.colorScheme.secondaryContainer, tag = "pause_instead") {
                    SectionTitle(tr("Just need a break?", "শুধু কিছুদিনের বিরতি চান?"))
                    Text(
                        tr(
                            "Pause your account instead: your profile, donor details and listings are hidden and notifications stop — but nothing is deleted, and there is no time limit. Log in and turn it back on whenever you like.",
                            "মুছে না ফেলে সাময়িক বন্ধ রাখুন: প্রোফাইল, রক্তদাতার তথ্য আর আপনার তালিকা লুকানো থাকবে, নোটিফিকেশন আসবে না — কিন্তু কিছুই মুছবে না, সময়ের সীমাও নেই। যখন খুশি লগইন করে আবার চালু করুন।",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onPauseInstead, enabled = !busy, modifier = Modifier.testTag("pause_instead_button")) {
                        AppIcon(R.drawable.ic_schedule, null, Modifier.size(18.dp))
                        Text(tr("Pause instead of deleting", "মুছে না ফেলে সাময়িক বন্ধ রাখুন"), Modifier.padding(start = 8.dp))
                    }
                }
            }

            // কী মুছবে, কী থাকে
            SectionCard {
                SectionTitle(tr("What will be deleted", "যা মুছে যাবে"))
                Bullets(
                    listOf(
                        tr("Profile and photo", "প্রোফাইল ও ছবি"),
                        tr("Phone number", "ফোন নম্বর"),
                        tr("Blood requests", "রক্তের অনুরোধ"),
                        tr("Prescriptions and lab reports", "প্রেসক্রিপশন ও ল্যাব রিপোর্ট"),
                        tr("Appointments", "অ্যাপয়েন্টমেন্ট"),
                        tr("Questions, answers and tips", "প্রশ্নোত্তর ও টিপস"),
                        tr("Your shop, lab, ambulance and chamber listings, and your shop books", "আপনার দোকান/ল্যাব/অ্যাম্বুলেন্স/চেম্বারের তালিকা আর দোকানের হিসাব"),
                    ),
                    tag = "delete_what",
                )
                Text(
                    tr("Also: family members, badge, caregiver links, verification document photos, notifications and help messages.", "এছাড়া: পরিবারের সদস্য, ব্যাজ, কেয়ারগিভার সংযোগ, যাচাইয়ের কাগজের ছবি, নোটিফিকেশন আর সাহায্যের বার্তা।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tr("What is kept", "যা থাকে"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Bullets(
                    listOf(
                        tr(
                            "A prescription a doctor wrote stays in the doctor's records, no longer linked to your account. If you are a doctor, the prescriptions you wrote stay with your patients.",
                            "ডাক্তারের লেখা প্রেসক্রিপশনের রেকর্ড ডাক্তারের কাছে থাকে, কিন্তু আপনার অ্যাকাউন্টের সাথে আর যুক্ত থাকে না। আপনি ডাক্তার হলে আপনার লেখা প্রেসক্রিপশন রোগীদের কাছে থাকে।",
                        ),
                        tr("Sales a pharmacy recorded in its own books (without your name) stay with that shop.", "কোনো ফার্মেসি নিজের হিসাব-খাতায় যে বিক্রি তুলেছে (আপনার নাম ছাড়া), তা সেই দোকানের কাছে থাকে।"),
                        tr(
                            "If the Lifolo team suspended a listing, a one-way hash of its email, phone, licence or vehicle number stays so it cannot be listed again.",
                            "Lifolo টিম কোনো তালিকা বন্ধ করে থাকলে, আবার তালিকায় আসা ঠেকাতে তার ইমেইল, ফোন, লাইসেন্স বা গাড়ির নম্বরের একমুখী hash থাকে।",
                        ),
                        tr(
                            "Deleted data can remain in server backups for 14 days and in off-site encrypted copies for up to 90 days, and is then removed automatically.",
                            "মোছা তথ্য সার্ভারের ব্যাকআপে ১৪ দিন আর বাইরের এনক্রিপ্ট করা কপিতে সর্বোচ্চ ৯০ দিন থাকতে পারে, তারপর নিজে থেকে মুছে যায়।",
                        ),
                    ),
                    tag = "delete_kept",
                )
            }

            // ৩০ দিনে ফেরানো, আসল তারিখ
            SectionCard(tag = "delete_recovery") {
                SectionTitle(tr("If you change your mind", "মত বদলালে"))
                Text(
                    tr(
                        "Log in again within 30 days to cancel the deletion — everything comes back as it was. After 30 days it is deleted for good — it cannot be brought back.",
                        "৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে। ৩০ দিন পর চিরতরে মুছে যাবে — আর ফেরানো যাবে না।",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr("Delete today and you can undo it until $lastDay; after that it is gone for good.", "আজ মুছলে $lastDay পর্যন্ত ফেরানো যাবে, তারপর চিরতরে মুছে যাবে।"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("delete_date"),
                )
                Text(
                    tr("Right away: your listings are hidden, notifications stop and you are logged out on every phone and browser.", "সাথে সাথে: আপনার তালিকা লুকানো হয়, নোটিফিকেশন বন্ধ হয়, সব ফোন আর ব্রাউজার থেকে লগআউট হয়।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // নিশ্চিত করা
            SectionCard(tag = "delete_confirm_box") {
                if (!email.isNullOrBlank()) Text(tr("Logged in as", "লগইন করা আছে:") + " " + email, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = understood, enabled = !busy, role = Role.Checkbox, onValueChange = { understood = it })
                        .testTag("delete_ack_" + if (understood) "on" else "off"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null, enabled = !busy)
                    Text(
                        tr("I understand that after 30 days all my data is deleted for good.", "আমি বুঝেছি, ৩০ দিন পর সব তথ্য চিরতরে মুছে যাবে।"),
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ErrorText(error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (!understood || busy) return@Button
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { doDelete() }
                                    .onSuccess { at ->
                                        // আগে লগআউট (সার্ভার সব লগইন বাতিল করেছে), তারপর এরপর কী হবে
                                        runCatching { afterDelete() }
                                        doneAt = at ?: AccountState.deleteDateFrom(System.currentTimeMillis())
                                    }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = understood && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.testTag("delete_confirm"),
                    ) {
                        AppIcon(R.drawable.ic_delete, null, Modifier.size(18.dp))
                        Text(tr("Delete account", "অ্যাকাউন্ট মুছুন"), Modifier.padding(start = 8.dp))
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp))
                }
            }

            TextButton(onClick = { openUrl(context, PRIVACY_URL) }) { Text(tr("Full details: Privacy policy", "বিস্তারিত: প্রাইভেসি পলিসি")) }
        }
    }
}

/**
 * "সাময়িক বন্ধ রাখুন" (account_deactivate): কী হয় (প্রোফাইল/ডোনার/তালিকা লুকানো, নোটিফিকেশন নেই, কিছু মোছে না, সময়ের সীমা
 * নেই, লগইন করে আবার চালু), টিক ছাড়াই এক চাপে "সাময়িক বন্ধ করুন", তারপর এই ফোন থেকে লগআউট।
 * 409 `deletion_pending` (মোছার অপেক্ষায়) — সার্ভারের বার্তা দেখায়।
 */
@Composable
fun DeactivateAccountScreen(
    email: String?,
    onBack: () -> Unit,
    onDeleteInstead: () -> Unit,
    onFinished: () -> Unit,
    doDeactivate: suspend () -> Unit = { AccountApi.deactivate() },
    afterDeactivate: suspend () -> Unit = { AuthRepository.signOutLocal() },
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    BackHandler(enabled = done) { onFinished() }

    Scaffold(topBar = { BackTopBar(tr("Pause account", "অ্যাকাউন্ট সাময়িক বন্ধ"), { if (done) onFinished() else onBack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Pause your account", "অ্যাকাউন্ট সাময়িক বন্ধ রাখুন"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                tr("Need a break? Pause your account instead of deleting it.", "কিছুদিনের বিরতি চাইলে অ্যাকাউন্ট না মুছে সাময়িক বন্ধ রাখুন।"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (done) {
                SectionCard(tag = "pause_done") {
                    DoneHeader(tr("Your account is paused", "অ্যাকাউন্ট সাময়িক বন্ধ হয়েছে"))
                    Text(
                        tr("You have been logged out. Nothing was deleted. Log in any time to turn it back on.", "লগআউট হয়েছে। কিছুই মোছা হয়নি। আবার চালু করতে যেকোনো সময় লগইন করুন।"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onFinished, modifier = Modifier.testTag("pause_home")) { Text(tr("Go to home", "হোমে যান")) }
                }
                return@Column
            }
            SectionCard {
                SectionTitle(tr("While it is paused", "বন্ধ থাকলে"))
                Bullets(
                    listOf(
                        tr("Nobody sees your profile, and you are not picked as a donor.", "আপনার প্রোফাইল কেউ দেখবে না, রক্তদাতার তালিকাতেও থাকবেন না।"),
                        tr(
                            "Your shop, lab, ambulance, blood bank, chamber and home care listings are hidden (no new appointments). Appointments already booked stay.",
                            "আপনার দোকান, ল্যাব, অ্যাম্বুলেন্স, ব্লাড ব্যাংক, চেম্বার আর বাসায় সেবার তালিকা লুকানো থাকবে (নতুন অ্যাপয়েন্টমেন্ট নয়)। আগে নেওয়া অ্যাপয়েন্টমেন্ট থাকবে।",
                        ),
                        tr("Your questions, answers and tips stay, shown as \"Deactivated user\".", "আপনার প্রশ্ন, উত্তর আর টিপস থাকবে, নামের জায়গায় দেখাবে \"নিষ্ক্রিয় ব্যবহারকারী\"।"),
                        tr("No notifications reach your phone except account notices. Caregiver links are paused.", "অ্যাকাউন্টের খবর ছাড়া কোনো নোটিফিকেশন আসবে না। কেয়ারগিভার সংযোগ থেমে থাকবে।"),
                        tr("Nothing is deleted — all your data stays as it is.", "কিছুই মুছবে না — সব তথ্য যেমন আছে তেমনই থাকবে।"),
                        tr("There is no time limit — keep it paused as long as you like.", "সময়ের কোনো সীমা নেই — যতদিন খুশি বন্ধ রাখুন।"),
                        tr(
                            "To turn it back on, just log in again and tap \"Turn on again\". We email you both times.",
                            "আবার চালু করতে শুধু লগইন করে \"আবার চালু করুন\" চাপুন। বন্ধ আর চালু — দুবারই ইমেইলে জানানো হবে।",
                        ),
                    ),
                    tag = "pause_what",
                )
            }
            SectionCard {
                if (!email.isNullOrBlank()) Text(tr("Logged in as", "লগইন করা আছে:") + " " + email, style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("You will be logged out here and on every other phone and browser.", "বন্ধ করলে এখানে আর সব ফোন ও ব্রাউজার থেকে লগআউট হয়ে যাবে।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorText(error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (busy) return@Button
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { doDeactivate() }
                                    .onSuccess {
                                        // সার্ভার এই ফোনের লগইনও বাতিল করেছে — এখানে লগআউট
                                        runCatching { afterDeactivate() }
                                        done = true
                                    }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("pause_confirm"),
                    ) {
                        AppIcon(R.drawable.ic_schedule, null, Modifier.size(18.dp))
                        Text(tr("Pause account", "সাময়িক বন্ধ করুন"), Modifier.padding(start = 8.dp))
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Want it gone for good instead?", "চিরতরে মুছতে চান?"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDeleteInstead, enabled = !busy) { Text(tr("Delete account", "অ্যাকাউন্ট মুছুন")) }
            }
        }
    }
}

/**
 * অ্যাকাউন্ট সাময়িক বন্ধ (profile/লগইনের account.state = "deactivated", বা কোনো কাজে 423 `account_deactivated`):
 * পুরো অ্যাপের বদলে এই পর্দা — "আবার চালু করুন" (account_reactivate, তারপর হোম নতুন করে) আর "লগআউট"।
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AccountPausedScreen(
    email: String?,
    account: AccountInfo?,
    onReactivated: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
    doReactivate: suspend () -> AccountInfo? = { AccountApi.reactivate() },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val since = account?.deactivatedAt?.let { AccountState.formatDay(it) }

    // আলাদা জানালায় (FullScreenDialog) — মূল পাতার testTagsAsResourceId এখানে পৌঁছায় না, তাই Maestro-র জন্য আবার
    Surface(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("account_paused"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { AppIcon(R.drawable.ic_schedule, null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSecondaryContainer) }
                Text(
                    tr("Your account is paused", "আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (since != null) {
                    Text(tr("Paused since $since.", "$since থেকে বন্ধ।"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    tr(
                        "Your profile and listings are hidden and notifications are off. Nothing was deleted — turn it back on and everything is as before.",
                        "আপনার প্রোফাইল আর তালিকা লুকানো, নোটিফিকেশন বন্ধ। কিছুই মোছা হয়নি — চালু করলেই সব আগের মতো।",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (!email.isNullOrBlank()) Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(error)
                Button(
                    onClick = {
                        if (busy) return@Button
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { doReactivate() }
                                .onSuccess {
                                    AccountState.afterReactivate(it)
                                    android.widget.Toast.makeText(context, tr("Your account is back on.", "অ্যাকাউন্ট আবার চালু হয়েছে।"), android.widget.Toast.LENGTH_LONG).show()
                                    onReactivated()
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("reactivate"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else AppIcon(R.drawable.ic_refresh, null, Modifier.size(18.dp))
                    Text(tr("Turn on again", "আবার চালু করুন"), Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = onLogout, enabled = !busy, modifier = Modifier.testTag("paused_logout")) {
                    AppIcon(R.drawable.ic_logout, null, Modifier.size(18.dp))
                    Text(tr("Log out", "লগআউট"), Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Want it gone for good instead?", "চিরতরে মুছতে চান?"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDelete, enabled = !busy) { Text(tr("Delete account", "অ্যাকাউন্ট মুছুন")) }
                }
            }
        }
    }
}

/** মোছার অপেক্ষায় থাকা অ্যাকাউন্টে আবার লগইন (সার্ভারের `restored`): একবারের ব্যানার */
@Composable
fun DeletionCancelledBanner(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().testTag("deletion_cancelled"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_check_circle, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(tr("Account deletion cancelled", "অ্যাকাউন্ট মোছা বাতিল হয়েছে"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(tr("Everything is back as before.", "সব আগের মতো আছে।"), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { AppIcon(R.drawable.ic_close, tr("Close", "বন্ধ করুন")) }
        }
    }
}
