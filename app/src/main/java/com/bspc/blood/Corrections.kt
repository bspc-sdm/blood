@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import org.json.JSONObject
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** শেষ রক্তদানের তারিখ সংশোধনের অনুরোধ (অ্যাপ থেকে তারিখ পেছানো যায় না, অ্যাডমিন অনুমোদন দেন) */
data class CorrectionRequest(
    val id: String = "",
    val uid: String = "",
    val name: String = "",
    val currentMillis: Long? = null,
    /** null মানে "কখনো রক্ত দিইনি" */
    val requestedMillis: Long? = null,
    val reason: String = "",
    /** "pending", "approved", "rejected" */
    val status: String = "pending",
    val createdAtMillis: Long = 0,
)

object DonationDate {
    /** সার্ভারে বদল (অনুমোদন লাগে না; ঝুঁকি থাকলে confirmed লাগে, বদল লেখা থাকে) */
    suspend fun set(millis: Long?, confirmed: Boolean) {
        ServerApi.post(
            "/api/community",
            JSONObject().put("action", "donation_date_set").put("millis", millis ?: JSONObject.NULL).put("confirmed", confirmed),
        )
    }
}

/**
 * "তথ্য সংশোধনের অনুরোধ": শেষ রক্তদানের তারিখ নিজে ঠিক করা না গেলে (দিনে ৩ বারের সীমা, বা তারিখটা নিয়ে আপত্তি)
 * অ্যাডমিনকে চাওয়া তারিখ আর কারণ পাঠানো — অ্যাডমিনের "তারিখ সংশোধন" তালিকায় যায় (correction_requests), অনুমোদন/বাতিল হলে
 * "correction" নোটিফিকেশন আসে (চাপলে প্রোফাইল)।
 *
 * সার্ভারে নতুন অনুরোধ তৈরির action এখনো নেই (শুধু ডেমোর জমা থাকা অনুরোধ; অ্যাডমিনের দিক আছে) — তাই ENABLED = false,
 * বাটন দেখায় না। সার্ভারে action এলে নাম/ফিল্ড মিলিয়ে ENABLED = true।
 */
object CorrectionRequests {
    const val ENABLED = false

    /** সার্ভারের action (backend-এ যোগ হলে এই নামে): millis null = "কখনো রক্ত দিইনি" */
    const val ACTION = "correction_request"

    suspend fun send(millis: Long?, reason: String) {
        ServerApi.post(
            "/api/community",
            JSONObject().put("action", ACTION).put("millis", millis ?: JSONObject.NULL).put("reason", reason),
        )
    }
}

/** অ্যাডমিনকে শেষ রক্তদানের তারিখ সংশোধনের অনুরোধ: চাওয়া তারিখ (বা কখনো দিইনি) আর কারণ */
@Composable
fun CorrectionRequestDialog(profile: Profile, initial: Long?, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val before = profile.lastDonationMillis
    var neverDonated by remember { mutableStateOf(initial == null && before == null) }
    var date by remember { mutableStateOf(initial ?: before) }
    var reason by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val requested = if (neverDonated) null else date

    FormDialog(
        title = tr("Ask for a correction", "তথ্য সংশোধনের অনুরোধ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Lifolo's team will check and fix your last donation date. You'll get a notification.", "Lifolo টিম দেখে আপনার শেষ রক্তদানের তারিখ ঠিক করে দেবে। ফল নোটিফিকেশনে জানানো হবে।"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr("Now: ", "এখন: ") + (before?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি")),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverDonated, onCheckedChange = { neverDonated = it })
                    Text(tr("I have never donated", "কখনো রক্ত দিইনি"))
                }
                if (!neverDonated) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(date?.let { tr("Correct date: ", "সঠিক তারিখ: ") + Rules.formatDate(it) } ?: tr("Pick the correct date", "সঠিক তারিখ বেছে নিন"))
                    }
                }
                OutlinedTextField(
                    reason,
                    { reason = it.take(500) },
                    label = { Text(tr("What's wrong?", "কী ভুল হয়েছে?")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("correction_reason"),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (neverDonated || date != null) && reason.isNotBlank(),
                onClick = {
                    if ((requested ?: 0L) > Rules.todayUtcMidnight()) {
                        errorMsg = tr("Date can't be in the future", "ভবিষ্যতের তারিখ দেওয়া যাবে না")
                        return@TextButton
                    }
                    if (requested == before) {
                        errorMsg = tr("That's already your date", "এটাই এখন আপনার তারিখ")
                        return@TextButton
                    }
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { CorrectionRequests.send(requested, reason.trim()) }
                            .onSuccess { onSent() }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
            ) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )

    if (showPicker) {
        DatePickerPopup(initial = date, onPicked = { date = it }, onDismiss = { showPicker = false })
    }
}

/**
 * শেষ রক্তদানের তারিখ বদলানো (অ্যাডমিনের অনুমোদন ছাড়া)। পেছালে বা দুই তারিখের ফাঁক ১২০ দিনের কম হলে
 * লাল সতর্কতা আর "আমি জেনেই সিদ্ধান্ত নিয়েছি" টিক দিয়ে তবেই সেভ।
 */
@Composable
fun DonationDateDialog(profile: Profile, onDismiss: () -> Unit, onSaved: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val before = profile.lastDonationMillis
    var neverDonated by remember { mutableStateOf(before == null) }
    var date by remember { mutableStateOf(before) }
    var agreed by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var askCorrection by remember { mutableStateOf(false) }
    var sentNote by remember { mutableStateOf(false) }
    val after = if (neverDonated) null else date
    val risk = Rules.donationRisk(before, after)
    val changed = after != before

    FormDialog(
        title = tr("Last donation date", "শেষ রক্তদানের তারিখ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Now: ", "এখন: ") + (before?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverDonated, onCheckedChange = { neverDonated = it; agreed = false })
                    Text(tr("I have never donated", "কখনো রক্ত দিইনি"))
                }
                if (!neverDonated) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(date?.let { Rules.formatDate(it) } ?: tr("Pick the date", "তারিখ বেছে নিন"))
                    }
                }
                if (changed && risk != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(R.drawable.ic_warning, null, Modifier.size(18.dp), MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Health risk", "স্বাস্থ্যঝুঁকি"), fontWeight = FontWeight.Bold)
                            }
                            Text(
                                if (risk == "backward") tr(
                                    "You're moving the date back. You'll show as able to donate earlier. Donating less than 120 days after your last donation is risky for you and the patient.",
                                    "আপনি তারিখ পেছাচ্ছেন — আগের চেয়ে আগেই \"রক্ত দিতে পারবেন\" দেখাবে। আগের রক্তদানের ১২০ দিনের আগে আবার রক্ত দেওয়া আপনার ও রোগীর জন্য ঝুঁকিপূর্ণ।",
                                ) else {
                                    val gap = ((after ?: 0L) - (before ?: 0L)) / DAY_MS
                                    tr(
                                        "Only $gap days between the two donations. Less than 120 days between donations is a health risk.",
                                        "দুই রক্তদানের মাঝে মাত্র ${num(gap)} দিন। ১২০ দিনের কম ফাঁকে রক্ত দেওয়া স্বাস্থ্যঝুঁকি।",
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                                Text(tr("I understand and have decided", "আমি জেনেই সিদ্ধান্ত নিয়েছি"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                ErrorText(errorMsg)
                // নিজে ঠিক করা না গেলে (দিনে ৩ বারের সীমা, বা তারিখটা নিয়ে আপত্তি): অ্যাডমিনকে সংশোধনের অনুরোধ
                if (CorrectionRequests.ENABLED) {
                    TextButton(onClick = { askCorrection = true }, enabled = !busy, modifier = Modifier.testTag("correction_open")) {
                        Text(tr("Can't fix it here? Ask for a correction", "এখানে ঠিক করা যাচ্ছে না? সংশোধনের অনুরোধ পাঠান"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && changed && (neverDonated || date != null) && (risk == null || agreed),
                onClick = {
                    if ((after ?: 0L) > Rules.todayUtcMidnight()) {
                        errorMsg = tr("Date can't be in the future", "ভবিষ্যতের তারিখ দেওয়া যাবে না")
                        return@TextButton
                    }
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { DonationDate.set(after, risk != null && agreed) }
                            .onSuccess { onSaved(after) }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
            ) { ButtonContent(busy, tr("Confirm", "নিশ্চিত করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )

    if (showPicker) {
        DatePickerPopup(initial = date, onPicked = { date = it; agreed = false }, onDismiss = { showPicker = false })
    }
    if (askCorrection) {
        CorrectionRequestDialog(profile, after, onDismiss = { askCorrection = false }) {
            askCorrection = false
            sentNote = true
        }
    }
    if (sentNote) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sentNote = false; onDismiss() },
            text = { Text(tr("Request sent. Lifolo's team will check it and let you know.", "অনুরোধ পাঠানো হয়েছে। Lifolo টিম দেখে জানাবে।")) },
            confirmButton = { TextButton(onClick = { sentNote = false; onDismiss() }) { Text(tr("OK", "ঠিক আছে")) } },
        )
    }
}
