@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// ব্লাড ব্যাংক: সরকারি হাসপাতালের, বেসরকারি (লাইসেন্স সহ) আর স্বেচ্ছাসেবী সংগঠন।
// শুধু তালিকা আর সরাসরি ফোন — অ্যাপে কোনো অর্ডার, টাকা বা স্টকের হিসাব নেই।
// ------------------------------------------------------------

data class BloodBank(
    val id: String,
    val name: String,
    /** govt, private, org */
    val kind: String,
    val phone: String,
    val district: String,
    val upazilaId: Int?,
    val address: String,
    val open24: Boolean,
    val openAt: String,
    val closeAt: String,
    val openNow: Boolean,
    val services: List<String>,
    /** শুধু নিজের */
    val licenseNo: String = "",
    val status: String = "",
    val statusReason: String = "",
    /** সাপ্তাহিক বন্ধের দিন (০ = রবিবার) */
    val closedDays: List<Int> = emptyList(),
    val docsMissing: Boolean = false,
    val docsRequested: Boolean = false,
    val docsNote: String = "",
) {
    val hoursText: String
        get() = if (open24) tr("Open 24 hours", "২৪ ঘণ্টা খোলা")
        else if (openAt.isNotBlank()) Weekdays.time(openAt) + " – " + Weekdays.time(closeAt) else ""
}

object BloodBankInfo {
    val KINDS = listOf("govt", "private", "org")
    val SERVICES = listOf("whole_blood", "components", "platelets", "screening", "donate_here")

    fun kind(k: String): String = when (k) {
        "govt" -> tr("Government hospital", "সরকারি হাসপাতাল")
        "private" -> tr("Private", "বেসরকারি")
        else -> tr("Volunteer organisation", "স্বেচ্ছাসেবী সংগঠন")
    }

    fun service(s: String): String = when (s) {
        "whole_blood" -> tr("Whole blood", "পূর্ণ রক্ত")
        "components" -> tr("Plasma & components", "প্লাজমা ও অন্যান্য অংশ")
        "platelets" -> tr("Platelets", "প্লাটিলেট")
        "screening" -> tr("Screening & cross-match", "স্ক্রিনিং ও ক্রস-ম্যাচ")
        else -> tr("You can donate here", "এখানে রক্ত দেওয়া যায়")
    }
}

object BloodBanks {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun parse(o: JSONObject) = BloodBank(
        o.str("id"), o.str("name"), o.str("kind"), o.str("phone"), o.str("district"), o.intOrNull("upazilaId"), o.str("address"),
        o.optBoolean("open24"), o.str("openAt"), o.str("closeAt"), o.optBoolean("openNow", true), o.optJSONArray("services").strings(),
        o.str("licenseNo"), o.str("status"), o.str("statusReason"), o.optJSONArray("closedDays").ints(),
        o.optBoolean("docsMissing"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
    )

    private fun list(o: JSONObject): List<BloodBank> {
        val a = o.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    suspend fun near(district: String, upazilaId: Int?, open24: Boolean): List<BloodBank> =
        list(call("bloodbanks", JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).put("open24", open24)))

    suspend fun mine(): List<BloodBank> = list(call("bloodbank_mine"))

    /** আবার যাচাই লাগবে কি না ফেরত দেয় */
    suspend fun save(b: BloodBank, docs: Map<String, String> = emptyMap()): Boolean =
        call(
            "bloodbank_save",
            JSONObject().put("id", b.id.ifBlank { null } ?: JSONObject.NULL).put("name", b.name).put("kind", b.kind).put("phone", b.phone)
                .put("district", b.district).put("upazilaId", b.upazilaId ?: JSONObject.NULL).put("address", b.address)
                .put("licenseNo", b.licenseNo).put("open24", b.open24).put("openAt", b.openAt).put("closeAt", b.closeAt)
                .put("services", JSONArray(b.services)).put("closedDays", JSONArray(b.closedDays)).put("termsAccepted", true)
                .put("docs", VerifyDocs.json(docs)),
        ).optBoolean("pending")

    suspend fun delete(id: String) {
        call("bloodbank_delete", JSONObject().put("id", id))
    }

    suspend fun called(id: String) {
        call("bloodbank_called", JSONObject().put("id", id))
    }

    suspend fun stats(): JSONObject = call("bloodbank_stats")
}

/** "সেবা" → ব্লাড ব্যাংক: খুঁজুন | আমার ব্লাড ব্যাংক */
@Composable
fun BloodBankContent(profile: Profile) {
    // নিজের ব্লাড ব্যাংক: "সেবা" পাতার নিচের ভাসমান বাটন / উপরের + থেকে (ServicesTab)
    BloodBankFinder(profile)
}

@Composable
private fun BloodBankFinder(profile: Profile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var open24 by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<BloodBank>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf<BloodBank?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(district, upazilaId, open24) {
        val d = district ?: return@LaunchedEffect
        loading = true
        runCatching { BloodBanks.near(d, upazilaId, open24) }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FilterBar(
                areaLabel = areaText(district, upazilaId),
                onAreaClick = { showArea = true },
                filterCount = if (open24) 1 else 0,
                onFilterClick = { showFilter = true },
                active = if (open24) listOf(ActiveFilter(tr("24 hours", "২৪ ঘণ্টা")) { open24 = false }) else emptyList(),
            )
            SmallNote(tr("Buying or selling blood is illegal; only processing costs apply.", "রক্ত কেনা-বেচা অবৈধ; শুধু পরীক্ষা ও প্রসেসিং খরচ লাগে।"))
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_bloodtype,
                    tr("No blood bank in the app here yet", "এখানে এখনো অ্যাপে কোনো ব্লাড ব্যাংক নেই"),
                    tr("Know one? Ask them to join with + at the top.", "কোনো ব্লাড ব্যাংক চিনলে উপরের + দিয়ে যোগ হতে বলুন।"),
                    Modifier.padding(top = 24.dp),
                )
            }
            else -> items(list, key = { it.id }) { b ->
                BloodBankCard(
                    b,
                    onCall = {
                        scope.launch { runCatching { BloodBanks.called(b.id) } }
                        callPhone(context, b.phone)
                    },
                    onReport = { reporting = b },
                )
            }
        }
    }
    if (showArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = { open24 = false }) {
            FilterSwitch(tr("Open 24 hours only", "শুধু ২৪ ঘণ্টা খোলা"), open24) { open24 = it }
        }
    }
    reporting?.let { b ->
        ReasonReportDialog(
            onDismiss = { reporting = null },
            onSubmit = { reason ->
                Moderation.report("bloodbank", b.id, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
    }
}

@Composable
internal fun BloodBankCard(b: BloodBank, onCall: () -> Unit, onReport: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_bloodtype, null, Modifier.size(24.dp), MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(BloodBankInfo.kind(b.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (b.openNow) Tag(tr("Open", "খোলা"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                else Tag(tr("Closed", "বন্ধ"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(b.services.joinToString(" · ") { BloodBankInfo.service(it) }, style = MaterialTheme.typography.bodyMedium)
            IconLine(R.drawable.ic_location_on, listOf(b.address, Areas.label(b.district, b.upazilaId)).filter { it.isNotBlank() }.joinToString(" · "))
            if (b.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, listOf(b.hoursText, closedDaysText(b.closedDays)).filter { it.isNotBlank() }.joinToString(" · "))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCall) { ButtonContent(false, tr("Call", "ফোন করুন") + " " + num(b.phone), R.drawable.ic_call) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onReport) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
            }
        }
    }
}

@Composable
internal fun MyBloodBanksSection(profile: Profile) {
    var list by remember { mutableStateOf<List<BloodBank>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<BloodBank?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var openStats by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        runCatching { BloodBanks.mine() }.onSuccess { list = it }.onFailure { errorMsg = it.message }
        runCatching { BloodBanks.stats() }.onSuccess { o ->
            val a = o.optJSONArray("items") ?: JSONArray()
            stats = (0 until a.length()).associate { a.getJSONObject(it).let { x -> x.optString("id") to x } }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoText(infoMsg)
        ErrorText(errorMsg)
        list.forEach { b ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            SmallNote(BloodBankInfo.kind(b.kind) + " · " + statusText(b.status))
                            StatusReasonLine(b.status, b.statusReason)
                        }
                        IconButton(onClick = { editing = b }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
                    }
                    if (b.status == "suspended" || b.status == "rejected") AppealButton("bloodbank")
                    DocsNeededNotice("bloodbank", b.id, VerifyDocs.bloodBank(b.kind), b.docsMissing, b.docsRequested, b.docsNote) { reload++ }
                    stats[b.id]?.let { st -> CallStatsBlock(st, expanded = openStats == b.id) { openStats = if (openStats == b.id) null else b.id } }
                }
            }
        }
        if (list.size < 3) {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Add blood bank", "ব্লাড ব্যাংক যোগ করুন"), R.drawable.ic_add)
            }
        }
    }

    if (adding || editing != null) {
        BloodBankEditDialog(
            existing = editing,
            profile = profile,
            onDismiss = { adding = false; editing = null },
            onSaved = { pending ->
                adding = false
                editing = null
                infoMsg = if (pending) tr("Sent for approval. It will be listed after an admin checks it.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন যাচাই করলে তালিকায় আসবে।") else tr("Saved", "সেভ হয়েছে")
                reload++
            },
            onDeleted = {
                editing = null
                reload++
            },
        )
    }
}

@Composable
private fun BloodBankEditDialog(existing: BloodBank?, profile: Profile, onDismiss: () -> Unit, onSaved: (Boolean) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: "govt") }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var licenseNo by remember { mutableStateOf(existing?.licenseNo ?: "") }
    var open24 by remember { mutableStateOf(existing?.open24 ?: true) }
    var openAt by remember { mutableStateOf(existing?.openAt?.ifBlank { null } ?: "08:00") }
    var closeAt by remember { mutableStateOf(existing?.closeAt?.ifBlank { null } ?: "20:00") }
    var services by remember { mutableStateOf(existing?.services?.toSet() ?: setOf("whole_blood", "screening")) }
    var closedDays by remember { mutableStateOf(existing?.closedDays?.toSet() ?: emptySet()) }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pickTime by remember { mutableStateOf<Int?>(null) } // 0 খোলে, 1 বন্ধ

    FormDialog(
        title = if (existing == null) tr("Add blood bank", "ব্লাড ব্যাংক যোগ") else tr("Edit blood bank", "ব্লাড ব্যাংক বদলান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Blood bank name", "ব্লাড ব্যাংকের নাম")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
                ChipRows(BloodBankInfo.KINDS, setOf(kind), label = { BloodBankInfo.kind(it) }) { kind = it }
                OutlinedTextField(
                    phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text(tr("Phone for calls", "যে নম্বরে ফোন আসবে")) },
                    supportingText = { Text(tr("Mobile, landline or hotline", "মোবাইল, ল্যান্ডলাইন বা হটলাইন")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (kind == "private") {
                    OutlinedTextField(licenseNo, { licenseNo = it.take(60) }, label = { Text(tr("Licence number", "লাইসেন্স নম্বর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text(tr("Area", "এলাকা"), style = MaterialTheme.typography.labelLarge)
                AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Address", "ঠিকানা")) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Open 24 hours", "২৪ ঘণ্টা খোলা"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = open24, onCheckedChange = { open24 = it })
                }
                if (!open24) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTime = 0 }, modifier = Modifier.weight(1f)) { Text(tr("Opens ", "খোলে ") + Weekdays.time(openAt)) }
                        OutlinedButton(onClick = { pickTime = 1 }, modifier = Modifier.weight(1f)) { Text(tr("Closes ", "বন্ধ ") + Weekdays.time(closeAt)) }
                    }
                }
                Text(tr("Weekly closed days (if any)", "সাপ্তাহিক বন্ধ (থাকলে)"), style = MaterialTheme.typography.labelLarge)
                ChipRows(Weekdays.ORDER.map { it.toString() }, closedDays.map { it.toString() }.toSet(), perRow = 4, label = { Weekdays.short(it.toInt()) }) {
                    val d = it.toInt()
                    closedDays = if (d in closedDays) closedDays - d else if (closedDays.size < 6) closedDays + d else closedDays
                }
                Text(tr("Services", "কী কী সেবা"), style = MaterialTheme.typography.labelLarge)
                ChipRows(BloodBankInfo.SERVICES, services, perRow = 2, label = { BloodBankInfo.service(it) }) { s -> services = if (s in services) services - s else services + s }
                DocPhotosField(VerifyDocs.bloodBank(kind), docs, { docs = it },
                    if (existing != null) tr("Add new photos only if the papers changed.", "কাগজ বদলালে তবেই নতুন ছবি দিন।") else null)
                ServiceTermsCheck(agreed) { agreed = it }
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            runCatching { BloodBanks.delete(existing.id) }.onSuccess { onDeleted() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }, enabled = !busy) { Text(tr("Remove this blood bank", "এই ব্লাড ব্যাংক সরিয়ে দিন"), color = MaterialTheme.colorScheme.error) }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val d = district
                errorMsg = when {
                    name.isBlank() -> tr("Enter the blood bank name", "ব্লাড ব্যাংকের নাম লিখুন")
                    !isContactPhone(phone) -> tr("Enter a valid phone number", "সঠিক ফোন নম্বর দিন")
                    kind == "private" && licenseNo.isBlank() -> tr("Enter the blood bank licence number", "ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন")
                    d == null -> tr("Choose a district", "জেলা বেছে নিন")
                    address.isBlank() -> tr("Write the address", "ঠিকানা লিখুন")
                    !open24 && openAt == closeAt -> tr("Set the opening hours", "খোলার সময় দিন")
                    services.isEmpty() -> tr("Choose the services", "কী কী সেবা দেন বেছে নিন")
                    existing == null && VerifyDocs.missing(VerifyDocs.bloodBank(kind), docs) != null -> VerifyDocs.missing(VerifyDocs.bloodBank(kind), docs)
                    !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                    else -> null
                }
                if (errorMsg != null || d == null) return@TextButton
                scope.launch {
                    busy = true
                    runCatching {
                        BloodBanks.save(
                            BloodBank(
                                existing?.id ?: "", TitleCase.name(name), kind, phone, d, upazilaId, address.trim(), open24,
                                if (open24) "" else openAt, if (open24) "" else closeAt, true, services.toList(),
                                licenseNo.trim(), closedDays = closedDays.sorted(),
                            ),
                            docs,
                        )
                    }
                        .onSuccess { onSaved(it) }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )

    pickTime?.let { which ->
        val cur = (if (which == 0) openAt else closeAt).split(":")
        TimePickerPopup(
            cur.getOrNull(0)?.toIntOrNull(), cur.getOrNull(1)?.toIntOrNull(),
            onPicked = { h, m -> if (which == 0) openAt = Weekdays.hhmm(h, m) else closeAt = Weekdays.hhmm(h, m) },
            onDismiss = { pickTime = null },
            title = if (which == 0) tr("Opens at", "খোলে") else tr("Closes at", "বন্ধ হয়"),
        )
    }
}
