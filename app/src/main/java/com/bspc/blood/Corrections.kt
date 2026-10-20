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
import androidx.compose.runtime.LaunchedEffect
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
 * "তথ্য সংশোধনের অনুরোধ" (প্রোফাইল → ওয়েবের মতোই): শেষ রক্তদানের তারিখ অ্যাডমিন দেখে ঠিক করেন — নিজে "তারিখ বদলান"-এ
 * না পারলে (দিনে ৩ বারের সীমা) বা অ্যাডমিনকে জানাতে চাইলে। সার্ভার: correction_create {requestedMillis | null, reason ১–৩০০},
 * একবারে একটাই অপেক্ষায় (409), দিনে ৩টা; correction_mine → শেষ অনুরোধ আর তার অবস্থা। অ্যাডমিনের "তারিখ সংশোধন" তালিকা থেকে
 * অনুমোদন/বাতিল হলে "correction" নোটিফিকেশন (চাপলে প্রোফাইল)।
 */
object CorrectionRequests {
    suspend fun create(requestedMillis: Long?, reason: String) {
        ServerApi.post(
            "/api/community",
            JSONObject().put("action", "correction_create").put("requestedMillis", requestedMillis ?: JSONObject.NULL).put("reason", reason),
        )
    }

    /** নিজের শেষ অনুরোধ (না থাকলে null) */
    suspend fun mine(): CorrectionRequest? {
        val o = ServerApi.post("/api/community", JSONObject().put("action", "correction_mine")).optJSONObject("item") ?: return null
        fun ms(k: String): Long? = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)
        return CorrectionRequest(
            id = o.optString("id"),
            currentMillis = ms("currentMillis"),
            requestedMillis = ms("requestedMillis"),
            reason = o.optString("reason"),
            status = o.optString("status", "pending"),
            createdAtMillis = o.optLong("createdAtMillis"),
        )
    }

    /** শেষ অনুরোধের অবস্থা — ওয়েবের CorrectionDialog-এর একই লেখা */
    fun statusLine(c: CorrectionRequest): String {
        val asked = c.requestedMillis?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো রক্ত দিইনি")
        return when (c.status) {
            "pending" -> tr("Your request is with the admin: ", "আপনার অনুরোধ অ্যাডমিনের কাছে আছে: ") + asked
            "approved" -> tr("Your last request was approved.", "আপনার শেষ অনুরোধটি অনুমোদিত হয়েছে।")
            else -> tr("Your last request was not approved.", "আপনার শেষ অনুরোধটি বাতিল হয়েছে।")
        }
    }
}

/** তথ্য সংশোধনের অনুরোধ: শেষ অনুরোধের অবস্থা; অপেক্ষায় না থাকলে সঠিক তারিখ (বা কখনো দিইনি) আর কী ভুল হয়েছিল */
@Composable
fun CorrectionRequestDialog(profile: Profile, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = profile.lastDonationMillis
    var latest by remember { mutableStateOf<CorrectionRequest?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var neverDonated by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { CorrectionRequests.mine() }.onSuccess { latest = it }
        loaded = true
    }
    val waiting = latest?.status == "pending"

    FormDialog(
        title = tr("Ask to correct the date", "তথ্য সংশোধনের অনুরোধ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("correction_dialog")) {
                Text(
                    tr("Last donation date now: ", "এখন শেষ রক্তদানের তারিখ: ") + (current?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                latest?.let { Text(CorrectionRequests.statusLine(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                if (!waiting) {
                    Text(
                        tr("An admin checks the request and fixes the date. You will get a notification.", "অ্যাডমিন অনুরোধ দেখে তারিখ ঠিক করে দেবেন। নোটিফিকেশন পাবেন।"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = neverDonated, onCheckedChange = { neverDonated = it })
                        Text(tr("I have actually never donated", "আসলে কখনো রক্ত দিইনি"))
                    }
                    if (!neverDonated) {
                        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(date?.let { tr("The correct date: ", "সঠিক তারিখ: ") + Rules.formatDate(it) } ?: tr("The correct date", "সঠিক তারিখ"))
                        }
                    }
                    OutlinedTextField(
                        reason,
                        { reason = it.take(300) },
                        label = { Text(tr("What was wrong?", "কী ভুল হয়েছিল?")) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("correction_reason"),
                    )
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            if (!waiting) {
                TextButton(
                    enabled = loaded && !busy && (neverDonated || date != null) && reason.isNotBlank(),
                    onClick = {
                        val requested = if (neverDonated) null else date
                        if ((requested ?: 0L) > Rules.todayUtcMidnight()) {
                            errorMsg = tr("Date can't be in the future", "ভবিষ্যতের তারিখ দেওয়া যাবে না")
                            return@TextButton
                        }
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { CorrectionRequests.create(requested, reason.trim()) }
                                .onSuccess { onSent() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                ) { ButtonContent(busy, tr("Send", "পাঠান")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (waiting) tr("Close", "বন্ধ করুন") else tr("Cancel", "বাতিল")) } },
    )

    if (showPicker) {
        DatePickerPopup(initial = date, onPicked = { date = it }, onDismiss = { showPicker = false }, maxDate = Rules.todayUtcMidnight())
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
}
