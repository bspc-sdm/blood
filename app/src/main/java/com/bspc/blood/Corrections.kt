@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
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

/** প্রোফাইল পাতায়: শেষ রক্তদানের তারিখ ভুল হলে সংশোধনের অনুরোধ */
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

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Wrong last donation date?", "শেষ রক্তদানের তারিখ ভুল?"), style = MaterialTheme.typography.titleSmall)
            val l = latest
            val status = when (l?.status) {
                "pending" -> tr("Your correction request is under review.", "আপনার সংশোধনের অনুরোধ যাচাই চলছে।")
                "approved" -> tr("Your last request was approved.", "আপনার শেষ অনুরোধ অনুমোদিত হয়েছে।")
                "rejected" -> tr("Your last request was not approved.", "আপনার শেষ অনুরোধ অনুমোদন হয়নি।")
                else -> null
            }
            if (status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfoText(infoMsg)
            if (l?.status != "pending") {
                TextButton(onClick = { showDialog = true }) { Text(tr("Request a correction", "সংশোধনের অনুরোধ পাঠান")) }
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
                        .onSuccess { infoMsg = tr("Sent. An admin will review it.", "অনুরোধ পাঠানো হয়েছে। অ্যাডমিন দেখে ঠিক করবেন।") }
                        .onFailure { infoMsg = tr("Couldn't send", "পাঠানো যায়নি") }
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

    FormDialog(
        title = tr("Date correction", "তারিখ সংশোধন"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverDonated, onCheckedChange = { neverDonated = it; if (it) date = null })
                    Text(tr("I have actually never donated", "আসলে কখনো রক্ত দিইনি"))
                }
                if (!neverDonated) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(date?.let { Rules.formatDate(it) } ?: tr("Pick the correct date", "সঠিক তারিখ বেছে নিন"))
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = { Text(tr("What went wrong?", "কী ভুল হয়েছিল?")) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(formError)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                formError = when {
                    !neverDonated && date == null -> tr("Pick the correct date", "সঠিক তারিখ বেছে নিন")
                    (date ?: 0L) > Rules.todayUtcMidnight() -> tr("Date can't be in the future", "ভবিষ্যতের তারিখ দেওয়া যাবে না")
                    reason.isBlank() -> tr("Tell us what went wrong", "কী ভুল হয়েছিল লিখুন")
                    else -> null
                }
                if (formError == null) onSubmit(if (neverDonated) null else date, reason.trim())
            }) { Text(tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )

    if (showPicker) {
        DatePickerPopup(initial = date, onPicked = { date = it }, onDismiss = { showPicker = false })
    }
}

/** অ্যাডমিন: পেশাদার ব্যাজের আবেদন আর তারিখ সংশোধনের অনুরোধ */
@Composable
fun AdminScreen(isOwner: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<CorrectionRequest>>(emptyList()) }
    var badges by remember { mutableStateOf<List<BadgeRequest>>(emptyList()) }
    var reports by remember { mutableStateOf<List<ReportedItem>>(emptyList()) }
    var hospitalReqs by remember { mutableStateOf<List<HospitalRequestItem>>(emptyList()) }
    var ambulances by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var pharmacies by remember { mutableStateOf<List<Pharmacy>>(emptyList()) }
    var owners by remember { mutableStateOf<List<String>>(emptyList()) }
    var admins by remember { mutableStateOf<List<String>>(emptyList()) }
    var newAdmin by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching { ServerApi.adminCorrections() }
            .onSuccess { items = it }
            .onFailure { errorMsg = it.message }
        runCatching { Community.adminBadges() }
            .onSuccess { badges = it }
            .onFailure { errorMsg = it.message }
        runCatching { Moderation.adminReports() }
            .onSuccess { reports = it }
            .onFailure { errorMsg = it.message }
        runCatching { Hospitals.adminRequests() }
            .onSuccess { hospitalReqs = it }
            .onFailure { errorMsg = it.message }
        runCatching { Ambulances.adminPending() }.onSuccess { ambulances = it }.onFailure { errorMsg = it.message }
        runCatching { Pharmacies.adminPending() }.onSuccess { pharmacies = it }.onFailure { errorMsg = it.message }
        if (isOwner) {
            runCatching { Moderation.admins() }
                .onSuccess { owners = it.first; admins = it.second }
                .onFailure { errorMsg = it.message }
        }
        loading = false
    }

    fun act(id: String, block: suspend () -> Unit) {
        scope.launch {
            busyId = id
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message }
            busyId = null
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(tr("Admin", "অ্যাডমিন"), onBack) {
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            if (loading) {
                LoadingBox()
                return@Column
            }

            SectionTitle(tr("Badge applications", "ব্যাজের আবেদন") + " (${num(badges.size)})")
            if (badges.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            badges.forEach { b ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name.ifBlank { "-" }, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            BadgeChip(b.type)
                        }
                        Text(b.email, style = MaterialTheme.typography.bodySmall)
                        Text(tr("Reg. no: ", "রেজি. নম্বর: ") + b.regNo)
                        Text(tr("Workplace: ", "কর্মস্থল: ") + b.workplace)
                        // অনুমোদনের আগে সরকারি তালিকায় নম্বর মিলিয়ে দেখা
                        when (b.type) {
                            "doctor" -> TextButton(onClick = { openUrl(context, "https://www.bmdc.org.bd/") }) {
                                Text(tr("Check on BMDC website", "BMDC ওয়েবসাইটে মিলিয়ে দেখুন"))
                            }
                            "nurse" -> TextButton(onClick = { openUrl(context, "https://www.bnmc.gov.bd/") }) {
                                Text(tr("Check on BNMC website", "BNMC ওয়েবসাইটে মিলিয়ে দেখুন"))
                            }
                            else -> Text(
                                tr("Ask for a certificate before approving.", "অনুমোদনের আগে সনদ দেখে নিন।"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            Button(onClick = { act(b.uid) { Community.resolveBadge(b.uid, true) } }, enabled = busyId == null) {
                                ButtonContent(busyId == b.uid, tr("Approve", "অনুমোদন"))
                            }
                            TextButton(onClick = { act(b.uid) { Community.resolveBadge(b.uid, false) } }, enabled = busyId == null) {
                                Text(tr("Reject", "বাতিল"))
                            }
                        }
                    }
                }
            }

            if (isOwner) {
                SectionTitle(tr("Admins", "অ্যাডমিন"))
                Text(
                    tr(
                        "Admins can approve badges and hospitals and handle reports. Only you (owner) can add or remove them.",
                        "অ্যাডমিনরা ব্যাজ, হাসপাতাল অনুমোদন আর রিপোর্ট দেখতে পারেন। যোগ/বাদ শুধু আপনি (মালিক) করতে পারেন।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                owners.forEach { Text("• $it " + tr("(owner)", "(মালিক)"), style = MaterialTheme.typography.bodyMedium) }
                admins.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• $e", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { act("admin:$e") { Moderation.removeAdmin(e) } }, enabled = busyId == null) {
                            Text(tr("Remove", "বাদ দিন"))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAdmin,
                        onValueChange = { newAdmin = it.trim().take(200) },
                        label = { Text(tr("Email of new admin", "নতুন অ্যাডমিনের ইমেইল")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val e = newAdmin
                            act("admin:add") { Moderation.addAdmin(e) }
                            newAdmin = ""
                        },
                        enabled = busyId == null && newAdmin.contains("@"),
                    ) { Text(tr("Add", "যোগ")) }
                }
                SmallNote(tr("They must log in to the app with this same email.", "তাদের এই ইমেইল দিয়েই অ্যাপে লগইন করতে হবে।"))
            }

            SectionTitle(tr("Pharmacies to approve", "অনুমোদনের অপেক্ষায় ফার্মেসি") + " (${num(pharmacies.size)})")
            if (pharmacies.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            pharmacies.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(tr("Licence: ", "লাইসেন্স: ") + p.licenseNo, style = MaterialTheme.typography.bodyMedium)
                        Text(num(p.phone) + " · " + p.address + " · " + Areas.label(p.district, p.upazilaId), style = MaterialTheme.typography.bodySmall)
                        Text(
                            tr("Check the licence with DGDA before approving.", "অনুমোদনের আগে ঔষধ প্রশাসন অধিদপ্তরে (DGDA) লাইসেন্স যাচাই করুন।"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Row {
                            Button(onClick = { act("ph:" + p.id) { Pharmacies.resolve(p.id, true) } }, enabled = busyId == null) {
                                ButtonContent(busyId == "ph:" + p.id, tr("Approve", "অনুমোদন"))
                            }
                            TextButton(onClick = { act("ph:" + p.id) { Pharmacies.resolve(p.id, false) } }, enabled = busyId == null) {
                                Text(tr("Reject", "বাতিল"))
                            }
                        }
                    }
                }
            }

            SectionTitle(tr("Ambulances to approve", "অনুমোদনের অপেক্ষায় অ্যাম্বুলেন্স") + " (${num(ambulances.size)})")
            if (ambulances.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            ambulances.forEach { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold)
                        Text(tr("Vehicle: ", "গাড়ি: ") + a.vehicleNo + " · " + a.types.joinToString(", ") { AmbulanceTypes.label(it) }, style = MaterialTheme.typography.bodyMedium)
                        Text(num(a.phone) + " · " + Areas.label(a.district, a.upazilaId), style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(onClick = { act("am:" + a.id) { Ambulances.resolve(a.id, true) } }, enabled = busyId == null) {
                                ButtonContent(busyId == "am:" + a.id, tr("Approve", "অনুমোদন"))
                            }
                            TextButton(onClick = { act("am:" + a.id) { Ambulances.resolve(a.id, false) } }, enabled = busyId == null) {
                                Text(tr("Reject", "বাতিল"))
                            }
                        }
                    }
                }
            }

            SectionTitle(tr("Hospital requests", "হাসপাতাল যোগের অনুরোধ") + " (${num(hospitalReqs.size)})")
            if (hospitalReqs.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            AdminHospitalRequests(hospitalReqs, busyId) { id, op, target -> act(id) { Hospitals.resolve(id, op, target) } }

            SectionTitle(tr("Reports", "রিপোর্ট") + " (${num(reports.size)})")
            if (reports.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            reports.forEach { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (r.kind) {
                                "question" -> tr("Question", "প্রশ্ন")
                                "answer" -> tr("Answer", "উত্তর")
                                "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
                                "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
                                "homevisit" -> if (r.reportedRole == "patient") tr("Home visit · patient", "হোম ভিজিট · রোগী") else tr("Home visit · nurse", "হোম ভিজিট · নার্স")
                                else -> tr("Tip", "টিপস")
                            } + " · " + r.authorName.ifBlank { "-" } + " · " + tr("${num(r.count)} report(s)", "${num(r.count)}টি রিপোর্ট"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                        Row {
                            Button(onClick = { act(r.targetPath) { Moderation.resolve(r.targetPath, true, r.reportedUid) } }, enabled = busyId == null) {
                                ButtonContent(
                                    busyId == r.targetPath,
                                    if (r.kind == "homevisit") tr("Block from home visits", "হোম ভিজিট থেকে বাদ দিন") else if (r.kind == "ambulance" || r.kind == "pharmacy") tr("Suspend", "বন্ধ করুন") else tr("Remove post", "পোস্ট মুছুন"),
                                )
                            }
                            TextButton(onClick = { act(r.targetPath) { Moderation.resolve(r.targetPath, false, r.reportedUid) } }, enabled = busyId == null) {
                                Text(tr("Keep", "রেখে দিন"))
                            }
                        }
                    }
                }
            }

            SectionTitle(tr("Date corrections", "তারিখ সংশোধন") + " (${num(items.size)})")
            if (items.isEmpty()) Text(tr("None pending.", "অপেক্ষায় কিছু নেই।"), style = MaterialTheme.typography.bodySmall)
            items.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name.ifBlank { "-" }, fontWeight = FontWeight.SemiBold)
                        Text(tr("Current: ", "এখনকার: ") + (c.currentMillis?.let { Rules.formatDate(it) } ?: "-"))
                        Text(tr("Requested: ", "চাওয়া: ") + (c.requestedMillis?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি")))
                        Text(tr("Reason: ", "কারণ: ") + c.reason, style = MaterialTheme.typography.bodySmall)
                        Row {
                            Button(onClick = { act(c.id) { ServerApi.resolveCorrection(c.id, true) } }, enabled = busyId == null) {
                                ButtonContent(busyId == c.id, tr("Approve", "অনুমোদন"))
                            }
                            TextButton(onClick = { act(c.id) { ServerApi.resolveCorrection(c.id, false) } }, enabled = busyId == null) {
                                Text(tr("Reject", "বাতিল"))
                            }
                        }
                    }
                }
            }
        }
    }
}
