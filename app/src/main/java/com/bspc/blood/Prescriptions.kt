@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// ডিজিটাল প্রেসক্রিপশন (ধাপ ১): ডাক্তার লেখেন, সহকারী রোগী এন্ট্রি করেন,
// রোগী "আমার প্রেসক্রিপশন"-এ দেখেন (PDF, যাচাইয়ের QR, ওষুধের অ্যালার্ম, আবার দেখানোর সিরিয়াল)।
// ------------------------------------------------------------

data class RxMed(
    val name: String,
    val form: String = "",
    val strength: String = "",
    val dose: String = "",
    val timing: String = "",
    val days: Int = 0,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("form", form).put("strength", strength).put("dose", dose)
        .put("timing", timing).put("days", days).put("note", note)

    /** "Tab. Napa (Paracetamol 500 mg)" */
    val title: String get() = listOf(RxText.formShort(form), name).filter { it.isNotBlank() }.joinToString(" ") + if (strength.isNotBlank()) " ($strength)" else ""

    /** "১+০+১ · খাবারের পরে · ৫ দিন" */
    val howTo: String
        get() = listOf(
            num(dose),
            RxText.timing(timing),
            if (days > 0) tr("$days days", "${num(days)} দিন") else tr("Continue", "চলবে"),
        ).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        fun parse(o: JSONObject) = RxMed(o.str("name"), o.str("form"), o.str("strength"), o.str("dose"), o.str("timing"), o.optInt("days"), o.str("note"))
    }
}

data class Rx(
    val id: String,
    val code: String,
    val status: String,
    val createdAt: Long,
    val doctorUid: String,
    val doctorName: String,
    val degrees: String,
    val regNo: String,
    val specialties: List<String>,
    val chamberName: String,
    val chamberAddress: String,
    val chamberPhone: String,
    val patientName: String,
    val patientAge: Int?,
    val patientGender: String,
    val complaints: String,
    val findings: Map<String, String>,
    val diagnosis: String,
    val medicines: List<RxMed>,
    val tests: List<String>,
    val testPlace: String,
    val advice: String,
    val followUpDate: Long?,
    val followUpBookDate: Long?,
    val followUpNote: String,
    val chamberIndex: Int?,
    /** ডাক্তারের সুপারিশ করা পরীক্ষার জায়গা (হাসপাতাল/ডায়াগনস্টিকের id) */
    val testPlaceId: String? = null,
) {
    val cancelled: Boolean get() = status == "cancelled"
    val verifyUrl: String get() = ServerApi.BASE_URL + "/rx/" + code
    val patientLine: String
        get() = listOfNotNull(
            patientName,
            patientAge?.let { tr("$it yrs", "${num(it)} বছর") },
            RxText.gender(patientGender).ifBlank { null },
        ).joinToString(" · ")

    companion object {
        fun parse(o: JSONObject): Rx {
            val d = o.optJSONObject("doctor") ?: JSONObject()
            val ch = d.optJSONObject("chamber")
            val p = o.optJSONObject("patient") ?: JSONObject()
            val f = o.optJSONObject("findings") ?: JSONObject()
            val meds = o.optJSONArray("medicines") ?: JSONArray()
            return Rx(
                o.str("id"), o.str("code"), o.str("status"), o.optLong("createdAt"), o.str("doctorUid"),
                d.str("name"), d.str("degrees"), d.str("regNo"), d.optJSONArray("specialties").strings(),
                ch?.str("name").orEmpty(), ch?.str("address").orEmpty(), ch?.str("phone").orEmpty(),
                p.str("name"), if (p.isNull("age") || !p.has("age")) null else p.optInt("age"), p.str("gender"),
                o.str("complaints"), RxText.FINDINGS.associateWith { f.str(it) }.filterValues { it.isNotBlank() }, o.str("diagnosis"),
                (0 until meds.length()).map { RxMed.parse(meds.getJSONObject(it)) }, o.optJSONArray("tests").strings(), o.str("testPlace"),
                o.str("advice"),
                if (o.isNull("followUpDate")) null else o.optLong("followUpDate").takeIf { it > 0 },
                if (o.isNull("followUpBookDate")) null else o.optLong("followUpBookDate").takeIf { it > 0 },
                o.str("followUpNote"),
                if (o.isNull("chamberIndex") || !o.has("chamberIndex")) null else o.optInt("chamberIndex"),
                o.str("testPlaceId").ifBlank { null },
            )
        }
    }
}

data class RxPatient(val id: String, val name: String, val age: Int?, val gender: String, val phone: String, val linked: Boolean)
data class RxAppt(val id: String, val name: String, val date: Long, val serial: Int?, val chamber: String)
data class RxDoctor(val uid: String, val name: String, val degrees: String)

object RxText {
    val FINDINGS = listOf("bp", "pulse", "temp", "weight", "spo2", "other")

    fun finding(k: String): String = when (k) {
        "bp" -> tr("BP", "প্রেসার")
        "pulse" -> tr("Pulse", "পালস")
        "temp" -> tr("Temp", "তাপমাত্রা")
        "weight" -> tr("Weight", "ওজন")
        "spo2" -> "SpO₂"
        else -> tr("Other", "অন্যান্য")
    }

    fun timing(t: String): String = when (t) {
        "before" -> tr("Before food", "খাবারের আগে")
        "after" -> tr("After food", "খাবারের পরে")
        else -> ""
    }

    fun gender(g: String): String = when (g) {
        "male" -> tr("Male", "পুরুষ")
        "female" -> tr("Female", "মহিলা")
        else -> ""
    }

    /** "Tablet" → "Tab." */
    fun formShort(f: String): String {
        val l = f.lowercase()
        return when {
            l.isBlank() -> ""
            "tablet" in l -> "Tab."
            "capsule" in l -> "Cap."
            "syrup" in l || "suspension" in l || "oral solution" in l -> "Syr."
            "injection" in l || "infusion" in l -> "Inj."
            "drop" in l -> "Drop"
            "cream" in l -> "Cream"
            "ointment" in l -> "Oint."
            "inhal" in l -> "Inh."
            else -> f
        }
    }

    val DOSES = listOf("1+0+1", "1+0+0", "0+0+1", "1+1+1", "0+1+0", "1+1+1+1")
    val DAYS = listOf(3, 5, 7, 10, 14, 30)
    val FOLLOW = listOf(3, 7, 15, 30)
    val COMMON_TESTS = listOf("CBC", "RBS", "FBS", "HbA1c", "Lipid profile", "S. Creatinine", "SGPT", "Urine R/E", "X-ray chest P/A", "ECG", "USG of whole abdomen", "TSH")
}

object RxApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    suspend fun mine(): List<Rx> = call("rx_mine").optJSONArray("items").objs().map { Rx.parse(it) }
    suspend fun doctorList(): List<Rx> = call("rx_doctor_list").optJSONArray("items").objs().map { Rx.parse(it) }
    suspend fun claim(code: String, phone: String): Int = call("rx_claim", JSONObject().put("code", code).put("phone", phone)).optInt("added")
    suspend fun cancel(id: String) = call("rx_cancel", JSONObject().put("id", id))
    suspend fun create(body: JSONObject): String = call("rx_create", body).optString("id")
    suspend fun get(id: String): Rx = Rx.parse(call("rx_get", JSONObject().put("id", id)).getJSONObject("item"))

    suspend fun team(): List<String> = call("rx_team").optJSONArray("emails").strings()
    suspend fun setTeam(emails: List<String>): List<String> = call("rx_team_set", JSONObject().put("emails", JSONArray(emails))).optJSONArray("emails").strings()
    suspend fun assisting(): List<RxDoctor> = call("rx_assisting").optJSONArray("items").objs().map { RxDoctor(it.str("doctorUid"), it.str("name"), it.str("degrees")) }

    private fun patient(o: JSONObject) = RxPatient(o.str("id"), o.str("name"), if (o.isNull("age") || !o.has("age")) null else o.optInt("age"), o.str("gender"), o.str("phone"), o.optBoolean("linked"))

    /** (এন্ট্রি করা রোগী, আজকের আশেপাশের অ্যাপয়েন্টমেন্ট) */
    suspend fun patients(doctorUid: String? = null): Pair<List<RxPatient>, List<RxAppt>> {
        val o = call("rx_patients", JSONObject().apply { if (doctorUid != null) put("doctorUid", doctorUid) })
        return o.optJSONArray("patients").objs().map(::patient) to
            o.optJSONArray("appointments").objs().map { RxAppt(it.str("id"), it.str("name"), it.optLong("date"), it.optInt("serial").takeIf { s -> s > 0 }, it.str("chamber")) }
    }

    suspend fun addPatient(doctorUid: String?, name: String, age: Int?, gender: String, phone: String): RxPatient {
        val b = JSONObject().put("name", name).put("age", age ?: JSONObject.NULL).put("gender", gender).put("phone", phone)
        if (doctorUid != null) b.put("doctorUid", doctorUid)
        return patient(call("rx_patient_add", b).getJSONObject("patient"))
    }
}

/** অন্য পাতা থেকে প্রেসক্রিপশনের পাতা খোলা: "mine" (রোগী), "assist" (সহকারী) */
object RxNav {
    val open = mutableStateOf<String?>(null)
}

// ------------------------------------------------------------
// প্রেসক্রিপশনের কাগজ (রোগী আর ডাক্তার দুজনেই একই রকম দেখেন)
// ------------------------------------------------------------

@Composable
fun RxPaper(rx: Rx) {
    val cs = MaterialTheme.colorScheme
    val qr = remember(rx.code) { Qr.bitmap(rx.verifyUrl, 360) }
    Surface(color = cs.surface, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rx.doctorName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (rx.degrees.isNotBlank()) Text(rx.degrees, style = MaterialTheme.typography.bodyMedium)
                    if (rx.specialties.isNotEmpty()) Text(rx.specialties.joinToString(", ") { Specialties.label(it) }, style = MaterialTheme.typography.bodyMedium, color = cs.primary)
                    if (rx.regNo.isNotBlank()) Text("BMDC " + rx.regNo, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (rx.chamberName.isNotBlank()) Text(listOf(rx.chamberName, rx.chamberAddress).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (qr != null) Image(qr.asImageBitmap(), tr("Verification QR", "যাচাইয়ের QR"), Modifier.size(84.dp))
            }
            if (rx.cancelled) Tag(tr("Cancelled", "বাতিল"), cs.errorContainer, cs.onErrorContainer)
            HorizontalDivider()
            Row {
                Text(rx.patientLine, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(Rules.formatDate(rx.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (rx.complaints.isNotBlank()) PaperBlock(tr("Complaints", "সমস্যা"), rx.complaints)
            if (rx.findings.isNotEmpty()) PaperBlock(tr("Findings", "পরীক্ষায় পাওয়া"), rx.findings.entries.joinToString(" · ") { RxText.finding(it.key) + ": " + it.value })
            if (rx.diagnosis.isNotBlank()) PaperBlock(tr("Diagnosis", "রোগনির্ণয়"), rx.diagnosis)
            if (rx.medicines.isNotEmpty()) {
                Text("℞", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                rx.medicines.forEachIndexed { i, m ->
                    Row {
                        Text(num(i + 1) + ".", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(26.dp))
                        Column {
                            Text(m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(m.howTo, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            if (rx.tests.isNotEmpty()) {
                PaperBlock(tr("Tests", "পরীক্ষা"), rx.tests.joinToString("\n") { "• $it" })
                if (rx.testPlace.isNotBlank()) Text(tr("Suggested place: ", "করাতে পারেন: ") + rx.testPlace + tr(" (or anywhere you like)", " (চাইলে অন্য জায়গাতেও)"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (rx.advice.isNotBlank()) PaperBlock(tr("Advice", "পরামর্শ"), rx.advice)
            rx.followUpDate?.let { d ->
                PaperBlock(tr("Come again", "আবার দেখাবেন"), Rules.formatDate(d) + if (rx.followUpNote.isNotBlank()) " · " + rx.followUpNote else "")
            }
            Text(tr("Verify: ", "যাচাই: ") + rx.code, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaperBlock(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** পুরো পাতায় একটা প্রেসক্রিপশন: কাগজ, PDF, অ্যালার্ম, আবার দেখানোর সিরিয়াল; ডাক্তারের জন্য বাতিল */
@Composable
fun RxDetailDialog(rx: Rx, asDoctor: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit = {}, profile: Profile? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var alarms by remember { mutableStateOf(false) }
    var alarmOn by remember { mutableStateOf(MedAlarms.isOn(context, rx.id)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                BackTopBar(tr("Prescription", "প্রেসক্রিপশন"), onDismiss) {
                    IconButton(onClick = { runCatching { RxPdf.share(context, rx) }.onFailure { errorMsg = it.message } }) {
                        AppIcon(R.drawable.ic_share, tr("Share PDF", "PDF শেয়ার"))
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorText(errorMsg)
                InfoText(infoMsg)
                RxPaper(rx)
                // ওষুধ কেনা, ফার্মেসি, পরীক্ষা, রিপোর্ট, ডাক্তারকে দেখানো
                if (!asDoctor && profile != null) RxActivitySection(rx, profile)
                if (!asDoctor && !rx.cancelled) {
                    // আবার দেখানো: এক চাপে সেই চেম্বারে সিরিয়াল
                    val book = rx.followUpBookDate
                    if (book != null && rx.chamberIndex != null && book >= Rules.todayUtcMidnight()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tr("See the doctor again", "আবার ডাক্তার দেখানো"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(Rules.formatDate(book) + (if (rx.chamberName.isNotBlank()) " · " + rx.chamberName else ""), style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            errorMsg = null
                                            runCatching {
                                                Appointments.book(rx.doctorUid, rx.chamberIndex, book, rx.followUpNote.ifBlank { tr("Follow-up", "ফলো-আপ") })
                                            }.onSuccess { infoMsg = tr("Request sent. You'll get a notification when the doctor confirms.", "অনুরোধ গেছে। ডাক্তার নিশ্চিত করলে নোটিফিকেশন পাবেন।") }
                                                .onFailure { errorMsg = it.message }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(12.dp),
                                ) { ButtonContent(busy, tr("Get a serial", "সিরিয়াল নিন"), R.drawable.ic_calendar_month) }
                            }
                        }
                    }
                    // ওষুধ খাওয়ার অ্যালার্ম
                    if (rx.medicines.any { MedAlarms.slotsOf(it.dose).isNotEmpty() }) {
                        if (alarmOn) {
                            OutlinedButton(onClick = { MedAlarms.turnOff(context, rx.id); alarmOn = false }, modifier = Modifier.fillMaxWidth()) {
                                ButtonContent(false, tr("Medicine alarm is on · Turn off", "ওষুধের অ্যালার্ম চালু · বন্ধ করুন"), R.drawable.ic_notifications_active)
                            }
                        } else {
                            Button(onClick = { alarms = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                                ButtonContent(false, tr("Remind me to take medicines", "ওষুধ খাওয়ার অ্যালার্ম দিন"), R.drawable.ic_notifications)
                            }
                        }
                    }
                }
                if (asDoctor && !rx.cancelled) {
                    TextButton(onClick = { confirmCancel = true }) { ButtonContent(false, tr("Cancel this prescription", "এই প্রেসক্রিপশন বাতিল করুন"), R.drawable.ic_close) }
                }
            }
        }
    }
    if (alarms) {
        MedAlarmDialog(rx, onDismiss = { alarms = false }) {
            alarms = false
            alarmOn = true
            infoMsg = tr("Medicine alarm is on", "ওষুধের অ্যালার্ম চালু হয়েছে")
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(tr("Cancel this prescription?", "প্রেসক্রিপশনটা বাতিল করবেন?")) },
            text = { Text(tr("Pharmacies will see it as cancelled. You can write a new one.", "ফার্মেসি যাচাই করলে বাতিল দেখাবে। চাইলে নতুন করে লিখতে পারেন।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    scope.launch {
                        runCatching { RxApi.cancel(rx.id) }.onSuccess { onChanged(); onDismiss() }.onFailure { errorMsg = it.message }
                    }
                }) { Text(tr("Cancel it", "বাতিল করুন")) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(tr("Keep", "রেখে দিন")) } },
        )
    }
}

@Composable
private fun RxListCard(rx: Rx, asDoctor: Boolean, onClick: () -> Unit) {
    DirectoryCard(
        title = if (asDoctor) rx.patientName else rx.doctorName,
        icon = R.drawable.ic_medication,
        subtitle = if (asDoctor) rx.patientLine.removePrefix(rx.patientName).removePrefix(" · ").ifBlank { null } else rx.degrees.ifBlank { null },
        status = if (rx.cancelled) CardStatus(tr("Cancelled", "বাতিল"), false) else null,
        meta = Rules.formatDate(rx.createdAt),
        details = listOf(
            R.drawable.ic_medication to rx.medicines.take(3).joinToString(", ") { it.name } + if (rx.medicines.size > 3) " +" + num(rx.medicines.size - 3) else "",
            R.drawable.ic_list_alt to if (rx.tests.isNotEmpty()) tr("${rx.tests.size} test(s)", "${num(rx.tests.size)}টা পরীক্ষা") else "",
            R.drawable.ic_calendar_month to (rx.followUpDate?.let { tr("Come again: ", "আবার দেখাবেন: ") + Rules.formatDate(it) } ?: ""),
        ),
        onClick = onClick,
    )
}

// ------------------------------------------------------------
// রোগী: আমার প্রেসক্রিপশন
// ------------------------------------------------------------

@Composable
fun MyPrescriptionsScreen(profile: Profile, onBack: () -> Unit) {
    var list by remember { mutableStateOf<List<Rx>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Rx?>(null) }
    var claiming by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { RxApi.mine() }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    Scaffold(
        topBar = {
            BackTopBar(tr("My prescriptions", "আমার প্রেসক্রিপশন"), onBack) {
                IconButton(onClick = { claiming = true }) { AppIcon(R.drawable.ic_add, tr("Add a prescription", "প্রেসক্রিপশন যোগ করুন")) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ErrorText(errorMsg)
                InfoText(infoMsg)
            }
            val l = list
            when {
                l == null && errorMsg == null -> item { LoadingBox() }
                l.isNullOrEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            R.drawable.ic_medication,
                            tr("No prescriptions yet", "এখনো কোনো প্রেসক্রিপশন নেই"),
                            tr("Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.", "Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।"),
                        )
                        OutlinedButton(onClick = { claiming = true }) { ButtonContent(false, tr("Add with code", "কোড দিয়ে যোগ করুন"), R.drawable.ic_add) }
                    }
                }
                else -> items(l, key = { it.id }) { rx -> RxListCard(rx, asDoctor = false) { open = rx } }
            }
        }
    }
    open?.let { rx -> RxDetailDialog(rx, asDoctor = false, onDismiss = { open = null }, profile = profile) }
    if (claiming) {
        RxClaimDialog(profile, onDismiss = { claiming = false }) { n ->
            claiming = false
            infoMsg = tr("Added $n prescription(s)", "${num(n)}টা প্রেসক্রিপশন যোগ হয়েছে")
            reload++
        }
    }
}

/** কাগজের প্রেসক্রিপশনের কোড + সহকারীকে দেওয়া ফোন নম্বর */
@Composable
private fun RxClaimDialog(profile: Profile, onDismiss: () -> Unit, onAdded: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(profile.phone) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Add a prescription", "প্রেসক্রিপশন যোগ করুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Write the code printed under the QR, and the mobile number you gave at the chamber.", "QR-এর নিচে লেখা কোড আর চেম্বারে যে মোবাইল নম্বর দিয়েছিলেন, সেটা দিন।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text(tr("Code", "কোড")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    phone, { phone = it.take(20) },
                    label = { Text(tr("Mobile number", "মোবাইল নম্বর")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { RxApi.claim(code, phone.trim()) }.onSuccess(onAdded).onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy && code.length == 10 && phone.isNotBlank(),
            ) { ButtonContent(busy, tr("Add", "যোগ করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// রোগী বাছাই আর নতুন রোগী (ডাক্তার ও সহকারী)
// ------------------------------------------------------------

/** একজন রোগী, যাঁর জন্য লেখা হবে: অ্যাপের অ্যাপয়েন্টমেন্ট অথবা এন্ট্রি করা রোগী */
data class RxTarget(val appointmentId: String? = null, val rxPatientId: String? = null, val name: String, val age: Int? = null, val gender: String = "")

@Composable
private fun PatientRow(title: String, subtitle: String, tag: String?, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CardAvatar(null, R.drawable.ic_person_outline, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (tag != null) Tag(tag, cs.secondaryContainer, cs.onSecondaryContainer)
            if (onClick != null) AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.onSurfaceVariant)
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() } else Card(Modifier.fillMaxWidth()) { content() }
}

/** ডাক্তার/সহকারীর রোগীর তালিকা: আজকের অ্যাপয়েন্টমেন্ট, এন্ট্রি করা রোগী, খোঁজা, নতুন যোগ */
@Composable
fun RxPatientsPane(doctorUid: String?, onPick: ((RxTarget) -> Unit)?, header: @Composable () -> Unit = {}) {
    var data by remember { mutableStateOf<Pair<List<RxPatient>, List<RxAppt>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(reload, doctorUid) {
        runCatching { RxApi.patients(doctorUid) }.onSuccess { data = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { header() }
        item {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                ButtonContent(false, tr("New patient", "নতুন রোগী"), R.drawable.ic_add)
            }
            ErrorText(errorMsg)
        }
        val d = data
        if (d == null) {
            if (errorMsg == null) item { LoadingBox() }
        } else {
            if (d.second.isNotEmpty()) {
                item { SectionTitle(tr("Appointments in the app", "অ্যাপের অ্যাপয়েন্টমেন্ট")) }
                items(d.second, key = { "a" + it.id }) { a ->
                    PatientRow(
                        a.name.ifBlank { "-" },
                        listOf(Rules.formatDate(a.date), a.chamber).filter { it.isNotBlank() }.joinToString(" · "),
                        a.serial?.let { tr("Serial $it", "সিরিয়াল ${num(it)}") },
                        onPick?.let { p -> { p(RxTarget(appointmentId = a.id, name = a.name)) } },
                    )
                }
            }
            item {
                OutlinedTextField(
                    q, { q = it.take(40) },
                    label = { Text(tr("Search name or phone", "নাম বা ফোন দিয়ে খুঁজুন")) },
                    singleLine = true,
                    leadingIcon = { AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val shown = d.first.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) || it.phone.contains(q.trim()) }
            if (shown.isEmpty()) item { SmallNote(tr("No patients entered yet", "এখনো কোনো রোগী এন্ট্রি করা হয়নি")) }
            items(shown, key = { "p" + it.id }) { p ->
                PatientRow(
                    p.name,
                    listOfNotNull(p.age?.let { tr("$it yrs", "${num(it)} বছর") }, RxText.gender(p.gender).ifBlank { null }, num(p.phone)).joinToString(" · "),
                    if (p.linked) tr("In app", "অ্যাপে আছেন") else null,
                    onPick?.let { pick -> { pick(RxTarget(rxPatientId = p.id, name = p.name, age = p.age, gender = p.gender)) } },
                )
            }
        }
    }
    if (adding) {
        RxPatientAddDialog(doctorUid, onDismiss = { adding = false }) { p ->
            adding = false
            reload++
            onPick?.invoke(RxTarget(rxPatientId = p.id, name = p.name, age = p.age, gender = p.gender))
        }
    }
}

@Composable
private fun RxPatientAddDialog(doctorUid: String?, onDismiss: () -> Unit, onAdded: (RxPatient) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("New patient", "নতুন রোগী"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Name", "নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    age, { age = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(tr("Age (years)", "বয়স (বছর)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRows(listOf("male", "female"), setOf(gender), perRow = 2, label = { RxText.gender(it) }) { gender = if (gender == it) "" else it }
                OutlinedTextField(
                    phone, { phone = it.take(20) },
                    label = { Text(tr("Mobile number", "মোবাইল নম্বর")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallNote(tr("The patient adds the prescription to their own app with this number and the code.", "রোগী এই নম্বর আর কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { RxApi.addPatient(doctorUid, name.trim(), age.toIntOrNull(), gender, phone.trim()) }
                            .onSuccess(onAdded).onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy && name.isNotBlank() && phone.isNotBlank(),
            ) { ButtonContent(busy, tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ডাক্তার: "আমার চেম্বার" → প্রেসক্রিপশন
// ------------------------------------------------------------

@Composable
fun RxDoctorTab() {
    var list by remember { mutableStateOf<List<Rx>?>(null) }
    var chambers by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { chambers = runCatching { Appointments.mine()?.chambers?.map { it.label } }.getOrNull().orEmpty() }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Rx?>(null) }
    var picking by remember { mutableStateOf(false) }
    var writingFor by remember { mutableStateOf<RxTarget?>(null) }
    var team by remember { mutableStateOf(false) }
    var sub by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runCatching { RxApi.doctorList() }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    Column(Modifier.fillMaxSize()) {
    PillTabs(sub, listOf(PillTab(tr("Prescriptions", "প্রেসক্রিপশন")), PillTab(tr("Reports", "রিপোর্ট"))), { sub = it }, secondary = true)
    if (sub == 1) {
        RxReviewsPane(null, asDoctor = true)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                ButtonContent(false, tr("New prescription", "নতুন প্রেসক্রিপশন"), R.drawable.ic_edit_note)
            }
            TextButton(onClick = { team = true }) { ButtonContent(false, tr("Assistants", "সহকারী"), R.drawable.ic_group_round) }
            ErrorText(errorMsg)
        }
        val l = list
        when {
            l == null && errorMsg == null -> item { LoadingBox() }
            l.isNullOrEmpty() -> item {
                EmptyState(R.drawable.ic_medication, tr("No prescriptions yet", "এখনো কোনো প্রেসক্রিপশন নেই"), null, Modifier.fillMaxWidth().padding(top = 24.dp))
            }
            else -> items(l, key = { it.id }) { rx -> RxListCard(rx, asDoctor = true) { open = rx } }
        }
    }
    }
    if (picking) {
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(topBar = { BackTopBar(tr("Choose patient", "রোগী বাছুন"), { picking = false }) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    RxPatientsPane(null, onPick = { t -> picking = false; writingFor = t })
                }
            }
        }
    }
    writingFor?.let { t ->
        RxEditorDialog(t, chambers, onDismiss = { writingFor = null }) { rx ->
            writingFor = null
            reload++
            open = rx
        }
    }
    open?.let { rx -> RxDetailDialog(rx, asDoctor = true, onDismiss = { open = null }, onChanged = { reload++ }) }
    if (team) RxTeamDialog { team = false }
}

/** সহকারীদের ইমেইল (তাঁরা এই ইমেইলে লগইন করে রোগী এন্ট্রি করবেন) */
@Composable
private fun RxTeamDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val emails = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { RxApi.team() }.onSuccess { emails.addAll(it) }.onFailure { errorMsg = it.message }
        loaded = true
    }
    FormDialog(
        title = tr("Assistants", "সহকারী"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Assistants log in with this email and enter patients. Only you write prescriptions.", "সহকারী এই ইমেইলে লগইন করে রোগী এন্ট্রি করবেন। প্রেসক্রিপশন লিখবেন শুধু আপনি।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!loaded) LoadingBox()
                emails.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { emails.remove(e) }) { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(18.dp)) }
                    }
                }
                if (emails.size < 5) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            input, { input = it.trim().take(200) },
                            label = { Text(tr("Assistant's email", "সহকারীর ইমেইল")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { emails.add(input.lowercase()); input = "" }, enabled = input.contains("@")) { Text(tr("Add", "যোগ")) }
                    }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { RxApi.setTeam(emails.toList()) }.onSuccess { onDismiss() }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy && loaded,
            ) { ButtonContent(busy, tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// প্রেসক্রিপশন লেখা
// ------------------------------------------------------------

@Composable
private fun RxEditorDialog(target: RxTarget, chambers: List<String>, onDismiss: () -> Unit, onIssued: (Rx) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(target.name) }
    var age by remember { mutableStateOf(target.age?.toString().orEmpty()) }
    var gender by remember { mutableStateOf(target.gender) }
    var complaints by remember { mutableStateOf("") }
    val findings = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var diagnosis by remember { mutableStateOf("") }
    val meds = remember { mutableStateListOf<RxMed>() }
    val tests = remember { mutableStateListOf<String>() }
    var testInput by remember { mutableStateOf("") }
    var testPlace by remember { mutableStateOf("") }
    var testPlaceId by remember { mutableStateOf<String?>(null) }
    var pickPlace by remember { mutableStateOf(false) }
    var advice by remember { mutableStateOf("") }
    var followDays by remember { mutableIntStateOf(0) }
    var followNote by remember { mutableStateOf("") }
    var chamber by remember { mutableIntStateOf(0) }
    var editMed by remember { mutableStateOf<Int?>(null) } // -1 = নতুন
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun issue() {
        scope.launch {
            busy = true
            errorMsg = null
            val body = JSONObject()
                .put("patient", JSONObject().put("name", name.trim()).put("age", age.toIntOrNull() ?: JSONObject.NULL).put("gender", gender))
                .put("complaints", complaints.trim())
                .put("findings", JSONObject().apply { findings.forEach { (k, v) -> put(k, v.trim()) } })
                .put("diagnosis", diagnosis.trim())
                .put("medicines", JSONArray(meds.map { it.toJson() }))
                .put("tests", JSONArray(tests.toList()))
                .put("testPlace", testPlace.trim())
                .apply { testPlaceId?.let { put("testPlaceId", it) } }
                .put("advice", advice.trim())
                .put("followUpDays", followDays)
                .put("followUpNote", followNote.trim())
                .apply {
                    if (chambers.isNotEmpty()) put("chamberIndex", chamber)
                    target.appointmentId?.let { put("appointmentId", it) }
                    target.rxPatientId?.let { put("rxPatientId", it) }
                }
            runCatching { RxApi.get(RxApi.create(body)) }
                .onSuccess(onIssued)
                .onFailure { errorMsg = it.message }
            busy = false
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(topBar = { BackTopBar(tr("Prescription", "প্রেসক্রিপশন"), { if (!busy) onDismiss() }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(tr("Patient", "রোগী"))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Name", "নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        age, { age = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text(tr("Age", "বয়স")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(100.dp),
                    )
                    ChipRows(listOf("male", "female"), setOf(gender), perRow = 2, label = { RxText.gender(it) }) { gender = if (gender == it) "" else it }
                }
                OutlinedTextField(complaints, { complaints = it.take(1000) }, label = { Text(tr("Complaints", "সমস্যা")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(tr("Findings", "পরীক্ষায় পাওয়া"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                    listOf("bp", "pulse", "temp", "weight", "spo2").forEach { k ->
                        OutlinedTextField(
                            findings[k].orEmpty(), { findings[k] = it.take(20) },
                            label = { Text(RxText.finding(k)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(diagnosis, { diagnosis = it.take(500) }, label = { Text(tr("Diagnosis", "রোগনির্ণয়")) }, modifier = Modifier.fillMaxWidth())

                SectionTitle(tr("Medicines", "ওষুধ"))
                meds.forEachIndexed { i, m ->
                    Card(onClick = { editMed = i }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(num(i + 1) + ". " + m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(m.howTo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { meds.removeAt(i) }) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান"), Modifier.size(20.dp)) }
                        }
                    }
                }
                OutlinedButton(onClick = { editMed = -1 }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Add medicine", "ওষুধ যোগ করুন"), R.drawable.ic_add) }

                SectionTitle(tr("Tests", "পরীক্ষা"))
                ChipRows(RxText.COMMON_TESTS + tests.filter { it !in RxText.COMMON_TESTS }, tests.toSet(), perRow = 3) { t ->
                    if (t in tests) tests.remove(t) else tests.add(t)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(testInput, { testInput = it.take(80) }, label = { Text(tr("Other test", "অন্য পরীক্ষা")) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (testInput.isNotBlank() && testInput.trim() !in tests) tests.add(testInput.trim()); testInput = "" }, enabled = testInput.isNotBlank()) { Text(tr("Add", "যোগ")) }
                }
                if (tests.isNotEmpty()) {
                    OutlinedButton(onClick = { pickPlace = true }, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, testPlace.ifBlank { tr("Suggest where to test (optional)", "কোথায় করাবেন (ঐচ্ছিক)") }, R.drawable.ic_local_hospital)
                    }
                    if (testPlace.isNotBlank()) TextButton(onClick = { testPlace = ""; testPlaceId = null }) { Text(tr("Remove suggestion", "প্রস্তাব সরান")) }
                }

                SectionTitle(tr("Advice", "পরামর্শ"))
                OutlinedTextField(advice, { advice = it.take(1000) }, label = { Text(tr("Advice", "পরামর্শ")) }, minLines = 2, modifier = Modifier.fillMaxWidth())

                SectionTitle(tr("Come again", "আবার দেখাবেন"))
                ChipRows(listOf("0") + RxText.FOLLOW.map { it.toString() }, setOf(followDays.toString()), perRow = 5, label = {
                    if (it == "0") tr("No", "দরকার নেই") else tr("$it days", "${num(it.toInt())} দিন পর")
                }) { followDays = it.toInt() }
                if (followDays > 0) {
                    OutlinedTextField(followNote, { followNote = it.take(80) }, label = { Text(tr("Note (e.g. bring the reports)", "নোট (যেমন: রিপোর্ট নিয়ে আসবেন)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (chambers.size > 1) {
                    Text(tr("Chamber", "চেম্বার"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(chambers.indices.map { it.toString() }, setOf(chamber.toString()), perRow = 1, label = { chambers[it.toInt()] }) { chamber = it.toInt() }
                }
                ErrorText(errorMsg)
                Button(
                    onClick = { confirm = true },
                    enabled = !busy && name.isNotBlank() && (meds.isNotEmpty() || tests.isNotEmpty() || advice.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { ButtonContent(busy, tr("Give prescription", "প্রেসক্রিপশন দিন"), R.drawable.ic_check_circle) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    editMed?.let { i ->
        MedEditDialog(meds.getOrNull(i), onDismiss = { editMed = null }) { m ->
            if (i >= 0 && i < meds.size) meds[i] = m else meds.add(m)
            editMed = null
        }
    }
    if (pickPlace) {
        HospitalPickerDialog(
            initialDistrict = null,
            onPick = { h -> testPlace = (if (isBangla && h.nameBn.isNotBlank()) h.nameBn else h.nameEn); testPlaceId = h.id; pickPlace = false },
            onDismiss = { pickPlace = false },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Give this prescription?", "প্রেসক্রিপশনটা দেবেন?")) },
            text = { Text(tr("It can't be edited afterwards; you can only cancel it and write a new one.", "দেওয়ার পর আর বদলানো যাবে না; শুধু বাতিল করে নতুন লেখা যাবে।")) },
            confirmButton = { TextButton(onClick = { confirm = false; issue() }) { Text(tr("Give", "দিন")) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Check again", "আবার দেখি")) } },
        )
    }
}

/** এক ওষুধ: নাম (তালিকা থেকে খুঁজে), মাত্রা, খাওয়ার নিয়ম, খাবারের আগে/পরে, কত দিন, নোট */
@Composable
private fun MedEditDialog(initial: RxMed?, onDismiss: () -> Unit, onSave: (RxMed) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var form by remember { mutableStateOf(initial?.form.orEmpty()) }
    var strength by remember { mutableStateOf(initial?.strength.orEmpty()) }
    var dose by remember { mutableStateOf(initial?.dose ?: "1+0+1") }
    var timing by remember { mutableStateOf(initial?.timing ?: "after") }
    var days by remember { mutableStateOf((initial?.days ?: 5).toString()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var suggestions by remember { mutableStateOf<List<FindDrug>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(initial != null) }
    LaunchedEffect(name) {
        if (picked || name.trim().length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = runCatching { MedFind.search(name.trim()) }.getOrDefault(emptyList())
        menu = suggestions.isNotEmpty()
    }
    FormDialog(
        title = if (initial == null) tr("Add medicine", "ওষুধ যোগ করুন") else tr("Medicine", "ওষুধ"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedTextField(
                        name, { name = it.take(100); picked = false },
                        label = { Text(tr("Medicine name", "ওষুধের নাম")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        suggestions.take(8).forEach { d ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(d.name, fontWeight = FontWeight.SemiBold)
                                        Text(listOf(d.generic, d.form, d.company).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = {
                                    picked = true
                                    name = d.name
                                    form = d.form
                                    strength = d.generic
                                    menu = false
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(form, { form = it.take(40) }, label = { Text(tr("Form", "ধরন")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(strength, { strength = it.take(40) }, label = { Text(tr("Strength", "মাত্রা")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(tr("How to take", "খাওয়ার নিয়ম"), style = MaterialTheme.typography.labelLarge)
                ChipRows(RxText.DOSES, setOf(dose), perRow = 3, label = { num(it) }) { dose = it }
                OutlinedTextField(dose, { dose = it.take(30) }, label = { Text(tr("Or write it", "অথবা লিখুন")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChipRows(listOf("before", "after"), setOf(timing), perRow = 2, label = { RxText.timing(it) }) { timing = if (timing == it) "" else it }
                Text(tr("For how many days", "কত দিন"), style = MaterialTheme.typography.labelLarge)
                ChipRows(RxText.DAYS.map { it.toString() } + "0", setOf(days), perRow = 4, label = { if (it == "0") tr("Continue", "চলবে") else num(it.toInt()) + tr(" d", " দিন") }) { days = it }
                OutlinedTextField(
                    days, { days = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(tr("Days (0 = continue)", "দিন (০ = চলবে)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(note, { note = it.take(120) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(RxMed(name.trim(), form.trim(), strength.trim(), dose.trim().replace(" ", ""), timing, (days.toIntOrNull() ?: 0).coerceIn(0, 365), note.trim())) },
                enabled = name.isNotBlank(),
            ) { Text(tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// সহকারী: ডাক্তারের রোগী এন্ট্রি
// ------------------------------------------------------------

@Composable
fun RxAssistScreen(onBack: () -> Unit) {
    var doctors by remember { mutableStateOf<List<RxDoctor>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var sub by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { RxApi.assisting() }.onSuccess { doctors = it }.onFailure { errorMsg = it.message }
    }
    Scaffold(topBar = { BackTopBar(tr("Doctor's assistant", "ডাক্তারের সহকারী"), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val d = doctors
            when {
                errorMsg != null -> Column(Modifier.padding(16.dp)) { ErrorText(errorMsg) }
                d == null -> LoadingBox()
                d.isEmpty() -> EmptyState(
                    R.drawable.ic_group_round,
                    tr("You're not an assistant yet", "আপনি এখনো কারো সহকারী নন"),
                    tr("Ask the doctor to add your email as an assistant.", "ডাক্তারকে বলুন আপনার ইমেইল সহকারী হিসেবে যোগ করতে।"),
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                )
                else -> {
                    if (d.size > 1) PillTabs(tab, d.map { PillTab(it.name) }, { tab = it })
                    val doc = d[tab.coerceIn(0, d.lastIndex)]
                    PillTabs(sub, listOf(PillTab(tr("Patients", "রোগী")), PillTab(tr("Payments", "টাকা মেলানো"))), { sub = it }, secondary = true)
                    if (sub == 1) RxReviewsPane(doc.uid, asDoctor = false)
                    else RxPatientsPane(doc.uid, onPick = null) {
                        if (d.size == 1) Text(doc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
