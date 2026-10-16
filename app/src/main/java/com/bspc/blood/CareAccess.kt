@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// কেয়ারগিভার (জরুরি যোগাযোগ) আর পরিবারের সদস্য
// - প্রত্যেকে ১–২ জনকে কেয়ারগিভার করতে পারেন (তাঁদের Lifolo অ্যাকাউন্টের ইমেইল)। তাঁরা রাজি হলে
//   নিজের ফোন থেকেই রোগীর প্রেসক্রিপশন, ফার্মেসি, পরীক্ষা, রিপোর্টের টাকা, সিরিয়াল — সব করতে পারেন।
//   লগইন ভাগ করতে হয় না; রোগী যেকোনো সময় সরিয়ে দিতে পারেন।
// - পরিবারের সদস্য (নিজের অ্যাকাউন্ট নেই — বয়স্ক বাবা-মা, শিশু): তাঁদের নামে সিরিয়াল, তাঁদের নম্বরে প্রেসক্রিপশন যোগ।
// ------------------------------------------------------------

/** কেয়ারগিভার যখন রোগীর হয়ে কাজ করছেন: এই কাজগুলোর অনুরোধে রোগীর uid যায় (সার্ভার মিলিয়ে নেয়) */
object CareMode {
    val asUid = mutableStateOf<String?>(null)
    val name = mutableStateOf("")

    private val ACTIONS = setOf(
        "rx_mine", "rx_get", "rx_claim", "rx_activity", "rx_pharmacy_options", "rx_send_pharmacy", "rx_order_withdraw", "rx_shop_find",
        "rx_send_lab", "labs", "lab_order_get", "review_pay", "labtests_get",
        "my_appointments", "book", "appointment_update", "providers", "family_get",
    )

    fun attach(path: String, body: JSONObject) {
        val uid = asUid.value ?: return
        if (path == "/api/community" && !body.has("asUid") && body.optString("action") in ACTIONS) body.put("asUid", uid)
    }
}

data class CareLink(
    val patientUid: String, val caretakerUid: String, val patientName: String, val caretakerName: String,
    val caretakerEmail: String, val status: String, val createdAt: Long,
)

data class FamilyMember(val id: String, val name: String, val relation: String, val age: Int?, val gender: String, val phone: String)

data class CareData(val caretakers: List<CareLink>, val caringFor: List<CareLink>, val family: List<FamilyMember>)

object CareApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun link(o: JSONObject) = CareLink(o.str("patientUid"), o.str("caretakerUid"), o.str("patientName"), o.str("caretakerName"), o.str("caretakerEmail"), o.str("status"), o.optLong("createdAt"))
    private fun member(o: JSONObject) = FamilyMember(o.str("id"), o.str("name"), o.str("relation"), if (o.isNull("age") || !o.has("age")) null else o.optInt("age"), o.str("gender"), o.str("phone"))

    suspend fun list(): CareData {
        val o = call("care_list")
        return CareData(o.optJSONArray("caretakers").objs().map(::link), o.optJSONArray("caringFor").objs().map(::link), o.optJSONArray("family").objs().map(::member))
    }

    suspend fun invite(email: String) = call("care_invite", JSONObject().put("email", email))
    suspend fun respond(patientUid: String, accept: Boolean) = call("care_respond", JSONObject().put("patientUid", patientUid).put("accept", accept))
    suspend fun removeCaretaker(caretakerUid: String) = call("care_remove", JSONObject().put("caretakerUid", caretakerUid))
    suspend fun leave(patientUid: String) = call("care_remove", JSONObject().put("patientUid", patientUid))

    /** কেয়ারগিভার মোডে রোগীর পরিবার */
    suspend fun family(): List<FamilyMember> = call("family_get").optJSONArray("family").objs().map(::member)

    suspend fun familySet(list: List<FamilyMember>): List<FamilyMember> = call(
        "family_set",
        JSONObject().put("family", JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("relation", it.relation).put("age", it.age ?: JSONObject.NULL).put("gender", it.gender).put("phone", it.phone)
        })),
    ).optJSONArray("family").objs().map(::member)
}

// ------------------------------------------------------------
// প্রোফাইল → কেয়ারগিভার ও পরিবার
// ------------------------------------------------------------

@Composable
fun CareScreen(onBack: () -> Unit, onOpenFor: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<CareData?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FamilyMember?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { CareApi.list() }.onSuccess { data = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    fun act(block: suspend () -> Unit, ok: String? = null) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }.onSuccess { if (ok != null) infoMsg = ok; reload++ }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    val cs = MaterialTheme.colorScheme
    Scaffold(topBar = { BackTopBar(tr("Caregivers & family", "কেয়ারগিভার ও পরিবার"), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ErrorText(errorMsg)
                InfoText(infoMsg)
            }
            val d = data
            if (d == null) {
                if (errorMsg == null) item { LoadingBox() }
                return@LazyColumn
            }
            // ---- আমার কেয়ারগিভার ----
            item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("My caregivers", "আমার কেয়ারগিভার"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SmallNote(
                            tr(
                                "Up to 2 people you trust — your emergency contacts. From their own phone they can see your prescriptions and reports, send them to a pharmacy or lab, pay for a report review and get a serial for you. They also get your notifications. They can't change your profile or delete your account. Remove them any time.",
                                "সর্বোচ্চ ২ জন বিশ্বস্ত মানুষ — আপনার জরুরি যোগাযোগ। তাঁরা নিজের ফোন থেকেই আপনার প্রেসক্রিপশন আর রিপোর্ট দেখতে, ফার্মেসি বা প্যাথলজিতে পাঠাতে, রিপোর্ট দেখানোর টাকা দিতে আর আপনার সিরিয়াল নিতে পারবেন; আপনার নোটিফিকেশনও পাবেন। প্রোফাইল বদলাতে বা অ্যাকাউন্ট মুছতে পারবেন না। যেকোনো সময় সরিয়ে দিতে পারবেন।",
                            ),
                        )
                        d.caretakers.forEach { c ->
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CardAvatar(null, R.drawable.ic_person_outline, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.caretakerName.ifBlank { c.caretakerEmail }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(c.caretakerEmail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                Tag(
                                    if (c.status == "active") tr("Active", "চালু") else tr("Waiting for them", "রাজি হওয়ার অপেক্ষায়"),
                                    if (c.status == "active") cs.tertiaryContainer else cs.surfaceVariant,
                                    if (c.status == "active") cs.onTertiaryContainer else cs.onSurfaceVariant,
                                )
                            }
                            SmallAction(tr("Remove", "সরিয়ে দিন"), R.drawable.ic_close, {
                                confirm = tr("Remove ${c.caretakerName.ifBlank { c.caretakerEmail }}? They won't be able to see or do anything for you.", "${c.caretakerName.ifBlank { c.caretakerEmail }}-কে সরাবেন? তিনি আর আপনার কিছু দেখতে বা করতে পারবেন না।") to
                                    suspend { CareApi.removeCaretaker(c.caretakerUid); Unit }
                            })
                        }
                        if (d.caretakers.size < 2) {
                            OutlinedButton(onClick = { inviting = true }, modifier = Modifier.fillMaxWidth()) {
                                ButtonContent(false, tr("Add a caregiver", "কেয়ারগিভার যোগ করুন"), R.drawable.ic_add)
                            }
                        }
                    }
                }
            }
            // ---- যাঁদের দেখাশোনা করি ----
            if (d.caringFor.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(tr("People I care for", "যাঁদের দেখাশোনা করি"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            d.caringFor.forEach { c ->
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CardAvatar(null, R.drawable.ic_health_and_safety_filled, size = 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.patientName.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (c.status == "active") tr("You can act for them", "আপনি ওঁর হয়ে কাজ করতে পারেন") else tr("Wants you as their caregiver", "আপনাকে কেয়ারগিভার করতে চান"),
                                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (c.status == "active") {
                                        SmallAction(tr("Open for them", "ওঁর হয়ে খুলুন"), R.drawable.ic_chevron_right, { onOpenFor(c.patientUid, c.patientName) }, primary = true)
                                        SmallAction(tr("Stop being caregiver", "সরে যান"), R.drawable.ic_close, {
                                            confirm = tr("Stop being ${c.patientName}'s caregiver?", "${c.patientName}-এর কেয়ারগিভার থেকে সরে যাবেন?") to suspend { CareApi.leave(c.patientUid); Unit }
                                        })
                                    } else {
                                        SmallAction(tr("Accept", "রাজি"), R.drawable.ic_check_circle, { act({ CareApi.respond(c.patientUid, true) }, tr("You're now ${c.patientName}'s caregiver", "আপনি এখন ${c.patientName}-এর কেয়ারগিভার")) }, primary = true, busy = busy)
                                        SmallAction(tr("Decline", "না"), R.drawable.ic_close, { act({ CareApi.respond(c.patientUid, false) }) }, busy = busy)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // ---- পরিবারের সদস্য ----
            item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Family members", "পরিবারের সদস্য"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SmallNote(
                            tr(
                                "For family who don't have their own phone or account — elderly parents, children. Get a serial in their name, and add their prescriptions with the code and their mobile number.",
                                "যাঁদের নিজের ফোন বা অ্যাকাউন্ট নেই — বয়স্ক বাবা-মা, শিশু। তাঁদের নামে সিরিয়াল নিন; প্রেসক্রিপশনের কোড আর তাঁদের মোবাইল নম্বর দিয়ে প্রেসক্রিপশন যোগ করুন।",
                            ),
                        )
                        d.family.forEach { m ->
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CardAvatar(null, R.drawable.ic_person_outline, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(m.relation.ifBlank { null }, m.age?.let { tr("$it yrs", "${num(it)} বছর") }, m.phone.ifBlank { null }?.let { num(it) }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                SmallAction(tr("Edit", "বদলান"), R.drawable.ic_edit, { editing = m })
                            }
                        }
                        if (d.family.size < 6) {
                            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                                ButtonContent(false, tr("Add a family member", "পরিবারের সদস্য যোগ করুন"), R.drawable.ic_add)
                            }
                        }
                    }
                }
            }
        }
    }
    if (inviting) {
        InviteDialog(onDismiss = { inviting = false }) {
            inviting = false
            infoMsg = tr("Sent. They'll appear once they accept.", "পাঠানো হয়েছে। তিনি রাজি হলে চালু হবে।")
            reload++
        }
    }
    val d = data
    if ((adding || editing != null) && d != null) {
        MemberDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onDelete = editing?.let { m -> { act({ CareApi.familySet(d.family.filter { it.id != m.id }) }); editing = null } },
        ) { m ->
            val next = if (editing != null) d.family.map { if (it.id == m.id) m else it } else d.family + m
            adding = false
            editing = null
            act({ CareApi.familySet(next) }, tr("Saved", "সেভ হয়েছে"))
        }
    }
    confirm?.let { (text, action) ->
        FormDialog(
            title = tr("Are you sure?", "নিশ্চিত?"),
            onDismiss = { confirm = null },
            content = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { confirm = null; act(action) }) { Text(tr("Yes", "হ্যাঁ")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("No", "না")) } },
        )
    }
}

@Composable
private fun InviteDialog(onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Add a caregiver", "কেয়ারগিভার যোগ করুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Their Lifolo account's email. They need to have logged in to Lifolo once.", "তাঁর Lifolo অ্যাকাউন্টের ইমেইল। তাঁকে অন্তত একবার Lifolo-তে লগইন করে থাকতে হবে।"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    email, { email = it.trim().take(200) },
                    label = { Text(tr("Email", "ইমেইল")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallNote(tr("They'll get a notification and must accept.", "তিনি নোটিফিকেশন পাবেন, রাজি হলে চালু হবে।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { CareApi.invite(email) }.onSuccess { onSent() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && email.contains('@')) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

@Composable
private fun MemberDialog(existing: FamilyMember?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (FamilyMember) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var relation by remember { mutableStateOf(existing?.relation.orEmpty()) }
    var age by remember { mutableStateOf(existing?.age?.toString().orEmpty()) }
    var gender by remember { mutableStateOf(existing?.gender.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    FormDialog(
        title = if (existing == null) tr("Family member", "পরিবারের সদস্য") else existing.name,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(tr("Name", "নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(relation, { relation = it.take(30) }, label = { Text(tr("Relation (e.g. Mother)", "সম্পর্ক (যেমন: মা)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    age, { age = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(tr("Age", "বয়স")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRows(listOf("male", "female"), setOf(gender), perRow = 2, label = { if (it == "male") tr("Male", "পুরুষ") else tr("Female", "নারী") }) { gender = if (gender == it) "" else it }
                OutlinedTextField(
                    phone, { phone = it.take(20) },
                    label = { Text(tr("Mobile number (optional)", "মোবাইল নম্বর (ঐচ্ছিক)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallNote(tr("The number given at the chamber — used to add their prescriptions.", "চেম্বারে যে নম্বর দেওয়া হয় — এটা দিয়ে তাঁর প্রেসক্রিপশন যোগ হবে।"))
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Remove this member", "এই সদস্যকে সরান"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(FamilyMember(existing?.id.orEmpty(), name.trim(), relation.trim(), age.toIntOrNull()?.takeIf { it in 0..120 }, gender, phone.trim())) },
                enabled = name.isNotBlank(),
            ) { Text(tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// কেয়ারগিভার রোগীর হয়ে: প্রেসক্রিপশন আর সিরিয়াল
// ------------------------------------------------------------

@Composable
fun CareModeScreen(patientUid: String, patientName: String, profile: Profile, onBack: () -> Unit) {
    // এই পাতা খোলা থাকতে প্রেসক্রিপশন/সিরিয়ালের অনুরোধ রোগীর হয়ে যায় (প্রথম অনুরোধের আগেই বসানো)
    remember(patientUid) {
        CareMode.asUid.value = patientUid
        CareMode.name.value = patientName
        0
    }
    DisposableEffect(patientUid) {
        CareMode.asUid.value = patientUid
        onDispose {
            if (CareMode.asUid.value == patientUid) {
                CareMode.asUid.value = null
                CareMode.name.value = ""
            }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme
    Scaffold(topBar = { BackTopBar(patientName.ifBlank { tr("Patient", "রোগী") }, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_health_and_safety_filled, null, Modifier.size(22.dp), cs.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("You're acting for $patientName as their caregiver", "আপনি কেয়ারগিভার হিসেবে $patientName-এর হয়ে কাজ করছেন"),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                    )
                }
            }
            PillTabs(
                selected = tab,
                tabs = listOf(PillTab(tr("Prescriptions", "প্রেসক্রিপশন"), R.drawable.ic_medication), PillTab(tr("Serials", "সিরিয়াল"), R.drawable.ic_calendar_month)),
                onSelect = { tab = it },
                secondary = true,
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> MyPrescriptionsScreen(profile, onBack = null)
                    else -> AppointmentsList(role = "patient", title = "", onBack = null)
                }
            }
        }
    }
}
