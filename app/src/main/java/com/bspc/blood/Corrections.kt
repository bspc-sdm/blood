@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

object CorrectionRepository {
    private val col get() = FirebaseFirestore.getInstance().collection("correctionRequests")

    /** নিজের সর্বশেষ অনুরোধ */
    suspend fun latest(uid: String): CorrectionRequest? =
        col.whereEqualTo("uid", uid).get().await().documents
            .map {
                CorrectionRequest(
                    id = it.id,
                    uid = uid,
                    requestedMillis = it.getLong("requestedMillis"),
                    reason = it.getString("reason") ?: "",
                    status = it.getString("status") ?: "pending",
                    createdAtMillis = it.getLong("createdAtMillis") ?: 0L,
                )
            }
            .maxByOrNull { it.createdAtMillis }

    suspend fun create(uid: String, profile: Profile, requestedMillis: Long?, reason: String) {
        col.add(
            hashMapOf<String, Any?>(
                "uid" to uid,
                "name" to profile.name.take(80),
                "currentMillis" to profile.lastDonationMillis,
                "requestedMillis" to requestedMillis,
                "reason" to reason.take(300),
                "status" to "pending",
                "createdAtMillis" to System.currentTimeMillis(),
            )
        ).await()
    }
}

/** প্রোফাইল পাতায়: অনুরোধের অবস্থা আর নতুন অনুরোধের বাটন */
@Composable
fun CorrectionCard(profile: Profile) {
    if (profile.lastDonationMillis == null) return
    val scope = rememberCoroutineScope()
    val uid = AuthRepository.currentUid ?: return
    var reload by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<CorrectionRequest?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        latest = runCatching { CorrectionRepository.latest(uid) }.getOrNull()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("শেষ রক্তদানের তারিখ ভুল?", style = MaterialTheme.typography.titleSmall)
            val l = latest
            when (l?.status) {
                "pending" -> Text(
                    "আপনার সংশোধনের অনুরোধ অ্যাডমিনের কাছে আছে: " +
                        (l.requestedMillis?.let { Rules.formatDate(it) } ?: "কখনো রক্ত দিইনি"),
                    style = MaterialTheme.typography.bodySmall,
                )
                "approved" -> Text("আপনার শেষ অনুরোধটি অনুমোদিত হয়েছে ✅", style = MaterialTheme.typography.bodySmall)
                "rejected" -> Text("আপনার শেষ অনুরোধটি বাতিল হয়েছে", style = MaterialTheme.typography.bodySmall)
                else -> Text(
                    "আগের তারিখে নেওয়া বা মুছে ফেলা অ্যাপ থেকে সরাসরি করা যায় না। অ্যাডমিনের কাছে অনুরোধ পাঠান।",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            InfoText(infoMsg)
            if (l?.status != "pending") {
                TextButton(onClick = { showDialog = true }) { Text("সংশোধনের অনুরোধ পাঠান") }
            }
        }
    }

    if (showDialog) {
        CorrectionDialog(
            onDismiss = { showDialog = false },
            onSubmit = { requested, reason ->
                showDialog = false
                scope.launch {
                    runCatching { CorrectionRepository.create(uid, profile, requested, reason) }
                        .onSuccess { infoMsg = "অনুরোধ পাঠানো হয়েছে। অ্যাডমিন দেখে ঠিক করে দেবেন।" }
                        .onFailure { infoMsg = "পাঠানো যায়নি: ${it.message}" }
                    reload++
                }
            },
        )
    }
}

@Composable
private fun CorrectionDialog(onDismiss: () -> Unit, onSubmit: (Long?, String) -> Unit) {
    var neverDonated by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("তারিখ সংশোধনের অনুরোধ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverDonated, onCheckedChange = { neverDonated = it; if (it) date = null })
                    Text("আসলে কখনো রক্ত দিইনি")
                }
                if (!neverDonated) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(date?.let { "সঠিক তারিখ: ${Rules.formatDate(it)}" } ?: "সঠিক তারিখ বেছে নিন")
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = { Text("কী ভুল হয়েছিল?") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(formError)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                formError = when {
                    !neverDonated && date == null -> "সঠিক তারিখ বেছে নিন"
                    (date ?: 0L) > Rules.todayUtcMidnight() -> "ভবিষ্যতের তারিখ দেওয়া যাবে না"
                    reason.isBlank() -> "কী ভুল হয়েছিল লিখুন"
                    else -> null
                }
                if (formError == null) onSubmit(if (neverDonated) null else date, reason.trim())
            }) { Text("পাঠান") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )

    if (showPicker) {
        DatePickerPopup(initial = date, onPicked = { date = it }, onDismiss = { showPicker = false })
    }
}

/** অ্যাডমিনের পাতা: অপেক্ষায় থাকা সংশোধনের অনুরোধ অনুমোদন বা বাতিল */
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<CorrectionRequest>>(emptyList()) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching { ServerApi.adminCorrections() }
            .onSuccess { items = it }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← ফিরে যান") }
        Text("অ্যাডমিন: তারিখ সংশোধনের অনুরোধ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = { reload++ }) { Text("🔄 রিফ্রেশ") }
        ErrorText(errorMsg)
        when {
            loading -> LoadingBox()
            items.isEmpty() && errorMsg == null -> Text("এখন কোনো অনুরোধ অপেক্ষায় নেই।")
            else -> items.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name.ifBlank { "নাম নেই" }, fontWeight = FontWeight.SemiBold)
                        Text("এখনকার তারিখ: " + (c.currentMillis?.let { Rules.formatDate(it) } ?: "নেই"))
                        Text("চাওয়া তারিখ: " + (c.requestedMillis?.let { Rules.formatDate(it) } ?: "কখনো রক্ত দেননি"))
                        Text("কারণ: ${c.reason}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(
                                onClick = {
                                    scope.launch {
                                        busyId = c.id
                                        runCatching { ServerApi.resolveCorrection(c.id, true) }
                                            .onFailure { errorMsg = it.message }
                                        busyId = null
                                        reload++
                                    }
                                },
                                enabled = busyId == null,
                            ) { ButtonContent(busyId == c.id, "অনুমোদন") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        busyId = c.id
                                        runCatching { ServerApi.resolveCorrection(c.id, false) }
                                            .onFailure { errorMsg = it.message }
                                        busyId = null
                                        reload++
                                    }
                                },
                                enabled = busyId == null,
                            ) { Text("বাতিল") }
                        }
                    }
                }
            }
        }
    }
}
