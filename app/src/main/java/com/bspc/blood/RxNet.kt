@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// প্রেসক্রিপশনের পরের ধাপ: ফার্মেসি (ওষুধ দেওয়া, স্টক), প্যাথলজি (পরীক্ষা, রিপোর্ট),
// আর রিপোর্ট দেখানো (সিরিয়াল, অথবা অনলাইনে — ডাক্তারের নম্বরে টাকা পাঠিয়ে TrxID)।
// ------------------------------------------------------------

data class MedStat(val i: Int, val name: String, val form: String, val dose: String, val days: Int, val need: Int?, val got: Int, val left: Int?, val done: Boolean) {
    /** "কেনা ৯/১৫", "কেনা ৩০ (চলবে)", "কেনা হয়নি" */
    val boughtText: String
        get() = when {
            need != null -> tr("Bought $got/$need", "কেনা ${num(got)}/${num(need)}")
            got > 0 -> tr("Bought $got", "কেনা ${num(got)}") + if (days == 0) tr(" (continue)", " (চলবে)") else ""
            else -> tr("Not bought yet", "এখনো কেনা হয়নি")
        }
}

data class Dispensing(val name: String, val at: Long, val items: List<Pair<Int, Int>>)
data class RxOrder(val id: String, val pharmacyName: String, val status: String, val reason: String, val createdAt: Long)
data class LabResult(val test: String, val result: String, val unit: String, val range: String, val flag: String)
data class LabReport(val items: List<LabResult>, val note: String, val reportedAt: Long, val photoCount: Int, val photos: List<String>)
data class LabOrder(
    val id: String, val rxId: String, val code: String, val labName: String, val patientName: String, val patientAge: Int?,
    val patientGender: String, val doctorName: String, val tests: List<String>, val status: String, val createdAt: Long, val report: LabReport?,
)
data class RxReview(
    val id: String, val rxId: String, val labOrderId: String, val doctorUid: String, val doctorName: String, val patientName: String,
    val labName: String, val tests: List<String>, val status: String, val fee: String, val noFee: Boolean, val payNumber: String,
    val payMethods: List<String>, val method: String, val trxId: String, val rejectNote: String, val comment: String, val updatedAt: Long,
)
data class RxActivity(val meds: List<MedStat>, val dispensed: List<Dispensing>, val orders: List<RxOrder>, val labOrders: List<LabOrder>, val reviews: List<RxReview>, val nextOpen: Long?)
data class PharmOption(val id: String, val name: String, val phone: String, val address: String, val km: Double?, val openNow: Boolean, val keepsStock: Boolean, val have: Int, val total: Int, val missing: List<String>)
data class PharmRx(val id: String, val code: String, val status: String, val createdAt: Long, val doctorName: String, val regNo: String, val patientName: String, val patientAge: Int?, val meds: List<MedStat>, val dispensed: List<Dispensing>)
data class PharmOrder(val id: String, val code: String, val patientName: String, val note: String, val status: String, val createdAt: Long)
data class Lab(
    val id: String, val name: String, val phone: String, val address: String, val district: String, val upazilaId: Int?, val facilityId: String?,
    val homeCollection: Boolean, val hours: String, val licenseNo: String = "", val status: String = "", val statusReason: String = "",
    val docsMissing: Boolean = false, val docsRequested: Boolean = false, val docsNote: String = "",
)
data class ReviewSettings(val enabled: Boolean, val noFee: Boolean, val fee: String, val payNumber: String, val payMethods: List<String>)

object RxNet {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.intN(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)

    private fun med(o: JSONObject) = MedStat(o.optInt("i"), o.str("name"), o.str("form"), o.str("dose"), o.optInt("days"), o.intN("need"), o.optInt("got"), o.intN("left"), o.optBoolean("done"))
    private fun disp(o: JSONObject) = Dispensing(o.str("name"), o.optLong("at"), o.optJSONArray("items").objs().map { it.optInt("i") to it.optInt("qty") })
    private fun labOrder(o: JSONObject) = LabOrder(
        o.str("id"), o.str("rxId"), o.str("code"), o.str("labName"), o.str("patientName"), o.intN("patientAge"), o.str("patientGender"),
        o.str("doctorName"), o.optJSONArray("tests").strings(), o.str("status"), o.optLong("createdAt"),
        o.optJSONObject("report")?.let { r ->
            LabReport(
                r.optJSONArray("items").objs().map { LabResult(it.str("test"), it.str("result"), it.str("unit"), it.str("range"), it.str("flag")) },
                r.str("note"), r.optLong("reportedAt"), r.optInt("photoCount"), r.optJSONArray("photos").strings(),
            )
        },
    )
    private fun review(o: JSONObject) = RxReview(
        o.str("id"), o.str("rxId"), o.str("labOrderId"), o.str("doctorUid"), o.str("doctorName"), o.str("patientName"), o.str("labName"),
        o.optJSONArray("tests").strings(), o.str("status"), o.str("fee"), o.optBoolean("noFee"), o.str("payNumber"), o.optJSONArray("payMethods").strings(),
        o.str("method"), o.str("trxId"), o.str("rejectNote"), o.str("comment"), o.optLong("updatedAt"),
    )
    private fun lab(o: JSONObject) = Lab(
        o.str("id"), o.str("name"), o.str("phone"), o.str("address"), o.str("district"), o.intN("upazilaId"), o.str("facilityId").ifBlank { null },
        o.optBoolean("homeCollection"), o.str("hours"), o.str("licenseNo"), o.str("status"), o.str("statusReason"),
        o.optBoolean("docsMissing"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
    )

    suspend fun activity(rxId: String): RxActivity {
        val o = call("rx_activity", JSONObject().put("rxId", rxId))
        return RxActivity(
            o.optJSONArray("medicines").objs().map(::med), o.optJSONArray("dispensed").objs().map(::disp),
            o.optJSONArray("orders").objs().map { RxOrder(it.str("id"), it.str("pharmacyName"), it.str("status"), it.str("reason"), it.optLong("createdAt")) },
            o.optJSONArray("labOrders").objs().map(::labOrder), o.optJSONArray("reviews").objs().map(::review),
            if (o.isNull("nextOpen") || !o.has("nextOpen")) null else o.optLong("nextOpen"),
        )
    }

    suspend fun pharmacyOptions(rxId: String, district: String, lat: Double?, lng: Double?): List<PharmOption> =
        call("rx_pharmacy_options", JSONObject().put("rxId", rxId).put("district", district).apply { if (lat != null && lng != null) put("lat", lat).put("lng", lng) })
            .optJSONArray("items").objs().map {
                PharmOption(it.str("id"), it.str("name"), it.str("phone"), it.str("address"), if (it.isNull("km")) null else it.optDouble("km"),
                    it.optBoolean("openNow"), it.optBoolean("keepsStock"), it.optInt("have"), it.optInt("total"), it.optJSONArray("missing").strings())
            }

    suspend fun sendPharmacy(rxId: String, pharmacyId: String) = call("rx_send_pharmacy", JSONObject().put("rxId", rxId).put("pharmacyId", pharmacyId))
    suspend fun labs(district: String, facilityId: String? = null): List<Lab> =
        call("labs", JSONObject().put("district", district).apply { if (facilityId != null) put("facilityId", facilityId) }).optJSONArray("items").objs().map(::lab)
    suspend fun sendLab(rxId: String, labId: String, tests: List<String>) = call("rx_send_lab", JSONObject().put("rxId", rxId).put("labId", labId).put("tests", JSONArray(tests)))
    suspend fun labOrder(id: String): LabOrder = labOrder(call("lab_order_get", JSONObject().put("id", id)).getJSONObject("item"))
    suspend fun pay(id: String, method: String, trxId: String) = call("review_pay", JSONObject().put("id", id).put("method", method).put("trxId", trxId))

    // ফার্মেসি
    private fun pharmRx(o: JSONObject): PharmRx {
        val d = o.optJSONObject("doctor") ?: JSONObject()
        val p = o.optJSONObject("patient") ?: JSONObject()
        return PharmRx(o.str("id"), o.str("code"), o.str("status"), o.optLong("createdAt"), d.str("name"), d.str("regNo"), p.str("name"), p.intN("age"),
            o.optJSONArray("medicines").objs().map(::med), o.optJSONArray("dispensed").objs().map(::disp))
    }
    suspend fun pharmacyOpen(code: String): PharmRx = pharmRx(call("rx_pharmacy_open", JSONObject().put("code", code)).getJSONObject("item"))
    suspend fun dispense(code: String, items: Map<Int, Int>): PharmRx =
        pharmRx(call("rx_dispense", JSONObject().put("code", code).put("items", JSONArray(items.filterValues { it > 0 }.map { (i, q) -> JSONObject().put("i", i).put("qty", q) }))).getJSONObject("item"))
    suspend fun pharmacyOrders(): List<PharmOrder> = call("rx_orders_pharmacy").optJSONArray("items").objs().map { PharmOrder(it.str("id"), it.str("code"), it.str("patientName"), it.str("note"), it.str("status"), it.optLong("createdAt")) }
    suspend fun orderUpdate(id: String, status: String, reason: String = "") = call("rx_order_update", JSONObject().put("id", id).put("status", status).put("reason", reason))
    suspend fun stock(): List<String> = call("stock_get").optJSONArray("items").strings()
    suspend fun stockSet(add: List<String>, remove: List<String>): List<String> = call("stock_set", JSONObject().put("add", JSONArray(add)).put("remove", JSONArray(remove))).optJSONArray("items").strings()

    // প্যাথলজি
    suspend fun labMine(): Lab? = call("lab_me").optJSONObject("lab")?.let(::lab)
    suspend fun labSave(facilityId: String?, name: String, district: String, upazilaId: Int?, address: String, licenseNo: String, phone: String, hours: String, homeCollection: Boolean, docs: Map<String, String>) =
        call("lab_save", JSONObject().put("facilityId", facilityId ?: JSONObject.NULL).put("name", name).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
            .put("address", address).put("licenseNo", licenseNo).put("phone", phone).put("hours", hours).put("homeCollection", homeCollection).put("termsAccepted", true).put("docs", VerifyDocs.json(docs)))
    suspend fun labDelete() = call("lab_delete")
    suspend fun labOrders(): List<LabOrder> = call("lab_orders").optJSONArray("items").objs().map(::labOrder)
    suspend fun labOpenCode(code: String): LabOrder = labOrder(call("lab_open_code", JSONObject().put("code", code)).getJSONObject("item"))
    suspend fun labStatus(id: String, status: String) = call("lab_order_status", JSONObject().put("id", id).put("status", status))
    suspend fun labReport(id: String, items: List<LabResult>, note: String, photos: List<String>) = call(
        "lab_report",
        JSONObject().put("id", id).put("note", note).put("photos", JSONArray(photos))
            .put("items", JSONArray(items.map { JSONObject().put("test", it.test).put("result", it.result).put("unit", it.unit).put("range", it.range).put("flag", it.flag) })),
    )

    // ডাক্তার/সহকারী
    suspend fun reviews(doctorUid: String? = null): List<RxReview> = call("reviews_doctor", JSONObject().apply { if (doctorUid != null) put("doctorUid", doctorUid) }).optJSONArray("items").objs().map(::review)
    suspend fun confirm(id: String, ok: Boolean, note: String = "", doctorUid: String? = null) =
        call("review_confirm", JSONObject().put("id", id).put("ok", ok).put("note", note).apply { if (doctorUid != null) put("doctorUid", doctorUid) })
    suspend fun reviewDone(id: String, comment: String) = call("review_done", JSONObject().put("id", id).put("comment", comment))
    suspend fun settings(set: ReviewSettings? = null): ReviewSettings {
        val b = JSONObject()
        if (set != null) b.put("set", true).put("enabled", set.enabled).put("noFee", set.noFee).put("fee", set.fee).put("payNumber", set.payNumber).put("payMethods", JSONArray(set.payMethods))
        val o = call("review_settings", b)
        return ReviewSettings(o.optBoolean("enabled"), o.optBoolean("noFee"), o.str("fee"), o.str("payNumber"), o.optJSONArray("payMethods").strings())
    }
}

object RxNetText {
    fun pay(m: String): String = when (m) {
        "bkash" -> "bKash"
        "nagad" -> "Nagad"
        "rocket" -> "Rocket"
        else -> m
    }

    fun orderStatus(s: String): String = when (s) {
        "sent" -> tr("Sent — waiting", "পাঠানো হয়েছে — অপেক্ষায়")
        "ready" -> tr("Ready to collect", "তৈরি — নিয়ে আসুন")
        "done" -> tr("Given", "দেওয়া হয়েছে")
        "cancelled" -> tr("Can't provide", "দিতে পারবে না")
        else -> s
    }

    fun labStatus(s: String): String = when (s) {
        "sent" -> tr("Sent — waiting", "পাঠানো হয়েছে — অপেক্ষায়")
        "received" -> tr("Sample taken", "নমুনা নেওয়া হয়েছে")
        "reported" -> tr("Report ready", "রিপোর্ট এসেছে")
        "cancelled" -> tr("Can't do", "করতে পারবে না")
        else -> s
    }

    fun reviewStatus(s: String): String = when (s) {
        "unpaid" -> tr("Not paid", "টাকা দেওয়া হয়নি")
        "claimed" -> tr("Payment sent — doctor is checking", "টাকা পাঠিয়েছেন — ডাক্তার মিলিয়ে দেখছেন")
        "paid" -> tr("Doctor is checking the report", "ডাক্তার রিপোর্ট দেখছেন")
        "done" -> tr("Doctor checked", "ডাক্তার দেখেছেন")
        else -> ""
    }

    fun flag(f: String): String = when (f) {
        "high" -> tr("High", "বেশি")
        "low" -> tr("Low", "কম")
        else -> ""
    }
}

// ------------------------------------------------------------
// রোগী: প্রেসক্রিপশনের ভেতরে — ওষুধ কেনা, ফার্মেসি, পরীক্ষা, রিপোর্ট, ডাক্তারকে দেখানো
// ------------------------------------------------------------

@Composable
fun RxActivitySection(rx: Rx, profile: Profile) {
    val scope = rememberCoroutineScope()
    var act by remember { mutableStateOf<RxActivity?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var pickPharmacy by remember { mutableStateOf(false) }
    var pickLab by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<RxReview?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { RxNet.activity(rx.id) }.onSuccess { act = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    val a = act
    ErrorText(errorMsg)
    InfoText(infoMsg)
    if (a == null) return
    val cs = MaterialTheme.colorScheme

    // ---- ওষুধ ----
    if (rx.medicines.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Medicines bought", "ওষুধ কেনা"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                a.meds.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Tag(m.boughtText, if (m.done) cs.tertiaryContainer else cs.surfaceVariant, if (m.done) cs.onTertiaryContainer else cs.onSurfaceVariant)
                    }
                }
                a.dispensed.forEach { d -> SmallNote(d.name + " · " + Rules.formatDate(d.at)) }
                a.orders.forEach { o ->
                    IconLine(R.drawable.ic_storefront, o.pharmacyName + " — " + RxNetText.orderStatus(o.status) + if (o.reason.isNotBlank()) " (${o.reason})" else "")
                }
                if (!rx.cancelled && a.meds.any { !it.done }) {
                    OutlinedButton(onClick = { pickPharmacy = true }, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, tr("Send to a pharmacy", "ফার্মেসিতে পাঠান"), R.drawable.ic_storefront)
                    }
                    SmallNote(tr("Or show the QR at any pharmacy. They note what they give; the rest can come from another pharmacy.", "অথবা যেকোনো ফার্মেসিতে QR দেখান। যতটা দেবে লিখে রাখবে; বাকিটা অন্য ফার্মেসি থেকে নিতে পারবেন।"))
                }
            }
        }
    }

    // ---- পরীক্ষা ও রিপোর্ট ----
    if (rx.tests.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Tests and reports", "পরীক্ষা ও রিপোর্ট"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                a.labOrders.forEach { o ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(o.labName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Tag(RxNetText.labStatus(o.status), if (o.status == "reported") cs.tertiaryContainer else cs.surfaceVariant, if (o.status == "reported") cs.onTertiaryContainer else cs.onSurfaceVariant)
                        }
                        SmallNote(o.tests.joinToString(", "))
                        if (o.status == "reported") {
                            SmallAction(tr("See report", "রিপোর্ট দেখুন"), R.drawable.ic_list_alt, { report = o.id }, primary = true)
                            val r = a.reviews.firstOrNull { it.labOrderId == o.id }
                            ReportReviewBox(rx, r, a.nextOpen, busy, onPay = { paying = it }) { date ->
                                scope.launch {
                                    busy = true
                                    errorMsg = null
                                    runCatching { Appointments.book(rx.doctorUid, rx.chamberIndex ?: 0, date, tr("Show the report", "রিপোর্ট দেখানো")) }
                                        .onSuccess { infoMsg = tr("Serial request sent", "সিরিয়ালের অনুরোধ গেছে") }
                                        .onFailure { errorMsg = it.message }
                                    busy = false
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
                if (!rx.cancelled) {
                    OutlinedButton(onClick = { pickLab = true }, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, tr("Send tests to a lab", "পরীক্ষা করাতে পাঠান"), R.drawable.ic_local_hospital)
                    }
                }
            }
        }
    }

    if (pickPharmacy) PharmacyPickDialog(rx, profile, onDismiss = { pickPharmacy = false }) { name ->
        pickPharmacy = false
        infoMsg = tr("Sent to $name", "$name-এ পাঠানো হয়েছে")
        reload++
    }
    if (pickLab) LabPickDialog(rx, profile, onDismiss = { pickLab = false }) { name ->
        pickLab = false
        infoMsg = tr("Sent to $name", "$name-এ পাঠানো হয়েছে")
        reload++
    }
    report?.let { id -> LabReportDialog(id) { report = null } }
    paying?.let { r ->
        PayDialog(r, onDismiss = { paying = null }) {
            paying = null
            infoMsg = tr("Sent. The doctor will check the payment.", "পাঠানো হয়েছে। ডাক্তার টাকা মিলিয়ে দেখবেন।")
            reload++
        }
    }
}

/** রিপোর্ট আসার পর: সিরিয়াল নিয়ে দেখানো, অথবা অনলাইনে (ফি দিয়ে) — অবস্থা অনুযায়ী */
@Composable
private fun ReportReviewBox(rx: Rx, r: RxReview?, nextOpen: Long?, busy: Boolean, onPay: (RxReview) -> Unit, onSerial: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r != null && r.status != "off" && r.status.isNotBlank()) {
            Text(RxNetText.reviewStatus(r.status), style = MaterialTheme.typography.bodyMedium, color = cs.primary)
            if (r.status == "unpaid") {
                Text(
                    tr("Show online: fee ${r.fee}. Send to ${r.payNumber} (", "অনলাইনে দেখান: ফি ${r.fee}। ${num(r.payNumber)} নম্বরে (") +
                        r.payMethods.joinToString(", ") { RxNetText.pay(it) } + tr("), then enter the TrxID.", ") পাঠিয়ে TrxID দিন।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (r.rejectNote.isNotBlank()) Text(tr("Doctor's note: ", "ডাক্তারের নোট: ") + r.rejectNote, style = MaterialTheme.typography.bodySmall, color = cs.error)
                SmallAction(tr("I've sent the money", "টাকা পাঠিয়েছি"), R.drawable.ic_payments, { onPay(r) }, primary = true)
            }
            if (r.status == "done" && r.comment.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(r.doctorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(r.comment, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (nextOpen != null && rx.chamberIndex != null && r?.status != "done") {
            SmallAction(tr("Get a serial to show it (", "দেখানোর সিরিয়াল নিন (") + Rules.formatDate(nextOpen) + ")", R.drawable.ic_calendar_month, { onSerial(nextOpen) }, busy = busy)
        }
    }
}

/** কাছের ফার্মেসি: কোনটায় প্রেসক্রিপশনের কয়টা ওষুধ আছে (যাঁরা স্টক রাখেন), পাঠানো বা ফোন */
@Composable
private fun PharmacyPickDialog(rx: Rx, profile: Profile, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var district by remember { mutableStateOf(profile.placeName) }
    var list by remember { mutableStateOf<List<PharmOption>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    LaunchedEffect(district) {
        val d = district ?: return@LaunchedEffect
        list = null
        val gps = profile.locationSource == "gps"
        runCatching { RxNet.pharmacyOptions(rx.id, d, if (gps) profile.lat else null, if (gps) profile.lng else null) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message; list = emptyList() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Choose a pharmacy", "ফার্মেসি বাছুন"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    FilterBar(areaLabel = areaText(district, null), onAreaClick = { showArea = true })
                    ErrorText(errorMsg)
                }
                val l = list
                when {
                    l == null -> item { LoadingBox() }
                    l.isEmpty() -> item { EmptyState(R.drawable.ic_storefront, tr("No pharmacy in the app here yet", "এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই"), null, Modifier.fillMaxWidth().padding(top = 24.dp)) }
                    else -> items(l, key = { it.id }) { p ->
                        DirectoryCard(
                            title = p.name,
                            icon = R.drawable.ic_storefront,
                            status = if (p.openNow) CardStatus(tr("Open", "খোলা"), true) else CardStatus(tr("Closed", "বন্ধ"), false),
                            meta = p.km?.let { Rules.formatKm(it) },
                            subtitle = if (p.keepsStock) tr("Has ${p.have} of ${p.total} medicines", "${num(p.total)}টার ${num(p.have)}টা ওষুধ আছে") else tr("Stock not listed — call to ask", "স্টকের তালিকা নেই — ফোনে জেনে নিন"),
                            details = listOf(
                                R.drawable.ic_location_on to p.address,
                                R.drawable.ic_info to if (p.keepsStock && p.missing.isNotEmpty()) tr("Missing: ", "নেই: ") + p.missing.joinToString(", ") else "",
                            ),
                            primary = CardAction(if (busy == p.id) "…" else tr("Send prescription", "প্রেসক্রিপশন পাঠান"), R.drawable.ic_send) {
                                if (busy == null) scope.launch {
                                    busy = p.id
                                    errorMsg = null
                                    runCatching { RxNet.sendPharmacy(rx.id, p.id) }.onSuccess { onSent(p.name) }.onFailure { errorMsg = it.message }
                                    busy = null
                                }
                            },
                            secondary = CardAction(tr("Call", "ফোন"), R.drawable.ic_call) { callPhone(context, p.phone) },
                        )
                    }
                }
            }
        }
    }
    if (showArea) AreaSheet(district, null, null, { d, _, _ -> district = d }, onDismiss = { showArea = false })
}

/** প্যাথলজি বাছাই: ডাক্তারের সুপারিশ করা সেন্টার (অ্যাপে থাকলে) আগে; কোন পরীক্ষা পাঠাবেন */
@Composable
private fun LabPickDialog(rx: Rx, profile: Profile, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var district by remember { mutableStateOf(profile.placeName) }
    var list by remember { mutableStateOf<List<Lab>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf(*rx.tests.toTypedArray()) }
    LaunchedEffect(district) {
        val d = district ?: return@LaunchedEffect
        list = null
        runCatching { RxNet.labs(d, rx.testPlaceId) }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message; list = emptyList() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Choose a lab", "প্যাথলজি বাছুন"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(tr("Tests to send", "কোন পরীক্ষা পাঠাবেন"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(rx.tests, chosen.toSet(), perRow = 3) { t -> if (t in chosen) chosen.remove(t) else chosen.add(t) }
                    Spacer(Modifier.height(8.dp))
                    FilterBar(areaLabel = areaText(district, null), onAreaClick = { showArea = true })
                    ErrorText(errorMsg)
                }
                val l = list
                when {
                    l == null -> item { LoadingBox() }
                    l.isEmpty() -> item {
                        EmptyState(R.drawable.ic_local_hospital, tr("No lab in the app here yet", "এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই"),
                            tr("You can still take the prescription to any lab.", "যেকোনো প্যাথলজিতে প্রেসক্রিপশন নিয়ে গিয়েও করাতে পারেন।"), Modifier.fillMaxWidth().padding(top = 24.dp))
                    }
                    else -> items(l, key = { it.id }) { lab ->
                        val suggested = rx.testPlaceId != null && lab.facilityId == rx.testPlaceId
                        DirectoryCard(
                            title = lab.name,
                            icon = R.drawable.ic_local_hospital,
                            status = if (suggested) CardStatus(tr("Doctor suggested", "ডাক্তারের সুপারিশ"), true) else null,
                            subtitle = if (lab.homeCollection) tr("Sample from home", "বাসা থেকে নমুনা নেয়") else null,
                            details = listOf(R.drawable.ic_location_on to lab.address, R.drawable.ic_schedule to lab.hours),
                            primary = CardAction(if (busy == lab.id) "…" else tr("Send tests", "পরীক্ষা পাঠান"), R.drawable.ic_send) {
                                if (busy == null && chosen.isNotEmpty()) scope.launch {
                                    busy = lab.id
                                    errorMsg = null
                                    runCatching { RxNet.sendLab(rx.id, lab.id, chosen.toList()) }.onSuccess { onSent(lab.name) }.onFailure { errorMsg = it.message }
                                    busy = null
                                }
                            },
                            secondary = CardAction(tr("Call", "ফোন"), R.drawable.ic_call) { callPhone(context, lab.phone) },
                        )
                    }
                }
            }
        }
    }
    if (showArea) AreaSheet(district, null, null, { d, _, _ -> district = d }, onDismiss = { showArea = false })
}

/** রিপোর্ট: ফলের তালিকা (বেশি/কম চিহ্নসহ), নোট, ছবি */
@Composable
fun LabReportDialog(orderId: String, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf<LabOrder?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orderId) { runCatching { RxNet.labOrder(orderId) }.onSuccess { order = it }.onFailure { errorMsg = it.message } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Test report", "পরীক্ষার রিপোর্ট"), onDismiss) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ErrorText(errorMsg)
                val o = order ?: run { if (errorMsg == null) LoadingBox(); return@Column }
                LabReportView(o)
            }
        }
    }
}

@Composable
fun LabReportView(o: LabOrder) {
    val cs = MaterialTheme.colorScheme
    Text(o.labName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(listOfNotNull(o.patientName, o.patientAge?.let { tr("$it yrs", "${num(it)} বছর") }, o.report?.reportedAt?.let { Rules.formatDate(it) }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    val r = o.report ?: return
    if (r.items.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.items.forEach { it ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.test, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (it.range.isNotBlank()) Text(tr("Normal: ", "স্বাভাবিক: ") + it.range, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text(it.result + if (it.unit.isNotBlank()) " " + it.unit else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = if (it.flag.isNotBlank()) cs.error else cs.onSurface)
                        if (it.flag.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Tag(RxNetText.flag(it.flag), cs.errorContainer, cs.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
    r.photos.forEach { p ->
        val bmp = remember(p) { Photo.decodeImage(p) }
        if (bmp != null) Image(bmp, tr("Report photo", "রিপোর্টের ছবি"), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
    }
}

/** রোগী: ডাক্তারের নম্বরে টাকা পাঠিয়ে TrxID */
@Composable
private fun PayDialog(r: RxReview, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf(r.payMethods.firstOrNull().orEmpty()) }
    var trx by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Payment for showing the report", "রিপোর্ট দেখানোর টাকা"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Send ${r.fee} to ${r.payNumber} with ", "${r.fee} পাঠান ${num(r.payNumber)} নম্বরে — ") + r.payMethods.joinToString(" / ") { RxNetText.pay(it) }, style = MaterialTheme.typography.bodyLarge)
                SmallNote(tr("The money goes straight to the doctor. Lifolo doesn't hold or take any of it.", "টাকা সরাসরি ডাক্তারের কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না।"))
                if (r.payMethods.size > 1) ChipRows(r.payMethods, setOf(method), perRow = 3, label = { RxNetText.pay(it) }) { method = it }
                OutlinedTextField(trx, { trx = it.filter { c -> c.isLetterOrDigit() }.take(30) }, label = { Text(tr("Transaction ID (TrxID)", "ট্রানজেকশন আইডি (TrxID)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.pay(r.id, method, trx) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && trx.length >= 6) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ফার্মেসি (মালিক): প্রেসক্রিপশন থেকে ওষুধ দেওয়া, আসা প্রেসক্রিপশন, স্টক
// ------------------------------------------------------------

@Composable
fun PharmacyRxSection() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<PharmRx?>(null) }
    var orders by remember { mutableStateOf<List<PharmOrder>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var stockOpen by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { runCatching { RxNet.pharmacyOrders() }.onSuccess { orders = it } }
    fun open(c: String) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { RxNet.pharmacyOpen(c) }.onSuccess { opened = it }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Prescriptions", "প্রেসক্রিপশন"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SmallNote(tr("Enter the code under the QR. Note how many you give — the rest can be given by another pharmacy.", "QR-এর নিচের কোড দিন। কয়টা দিলেন লিখে রাখুন — বাকিটা অন্য ফার্মেসি দিতে পারবে।"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text(tr("Code", "কোড")) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { open(code) }, enabled = !busy && code.length == 10, shape = RoundedCornerShape(12.dp)) { ButtonContent(busy, tr("Open", "খুলুন")) }
            }
            ErrorText(errorMsg)
            val pending = orders.filter { it.status in listOf("sent", "ready") }
            if (pending.isNotEmpty()) {
                Text(tr("Sent to you", "আপনার কাছে পাঠানো"), style = MaterialTheme.typography.labelLarge)
                pending.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.patientName.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(RxNetText.orderStatus(o.status) + " · " + timeAgo(o.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        SmallAction(tr("Open", "খুলুন"), null, { code = o.code; open(o.code) }, primary = true)
                        if (o.status == "sent") {
                            Spacer(Modifier.width(6.dp))
                            SmallAction(tr("Ready", "তৈরি"), null, { scope.launch { runCatching { RxNet.orderUpdate(o.id, "ready") }.onFailure { errorMsg = it.message }; reload++ } })
                        }
                    }
                }
            }
            OutlinedButton(onClick = { stockOpen = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("My stock list", "আমার স্টকের তালিকা"), R.drawable.ic_list_alt) }
            SmallNote(tr("Keep a stock list so patients can see which medicines you have.", "স্টকের তালিকা রাখলে রোগীরা দেখতে পাবেন আপনার কাছে কোন ওষুধ আছে।"))
        }
    }
    opened?.let { rx -> DispenseDialog(rx, onDismiss = { opened = null; reload++ }) }
    if (stockOpen) StockDialog { stockOpen = false }
}

/** ফার্মেসি: প্রতিটা ওষুধে কয়টা দিলেন */
@Composable
private fun DispenseDialog(start: PharmRx, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rx by remember { mutableStateOf(start) }
    val qty = remember { mutableStateMapOf<Int, String>() }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Give medicines", "ওষুধ দিন"), onDismiss) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val cs = MaterialTheme.colorScheme
                if (rx.status == "cancelled") Tag(tr("Cancelled — do not dispense", "বাতিল — ওষুধ দেবেন না"), cs.errorContainer, cs.onErrorContainer)
                Text(rx.doctorName + if (rx.regNo.isNotBlank()) " · BMDC " + rx.regNo else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(rx.patientName, rx.patientAge?.let { tr("$it yrs", "${num(it)} বছর") }, Rules.formatDate(rx.createdAt)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                ErrorText(errorMsg)
                InfoText(infoMsg)
                rx.meds.forEach { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(listOf(RxText.formShort(m.form), m.name).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(num(m.dose) + " · " + (if (m.days > 0) tr("${m.days} days", "${num(m.days)} দিন") else tr("Continue", "চলবে")), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                Text(m.boughtText + (m.left?.let { tr(" · $it left", " · বাকি ${num(it)}") } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (m.done) cs.primary else cs.onSurfaceVariant)
                            }
                            if (!m.done && rx.status != "cancelled") {
                                OutlinedTextField(
                                    qty[m.i].orEmpty(), { qty[m.i] = it.filter { c -> c.isDigit() }.take(4) },
                                    label = { Text(tr("Qty", "কয়টা")) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(96.dp),
                                )
                            }
                        }
                    }
                }
                if (rx.dispensed.isNotEmpty()) {
                    Text(tr("Given before", "আগে দেওয়া"), style = MaterialTheme.typography.labelLarge)
                    rx.dispensed.forEach { d -> SmallNote(d.name + " · " + Rules.formatDate(d.at) + " · " + d.items.joinToString(", ") { (i, q) -> (rx.meds.getOrNull(i)?.name ?: "") + " ×" + num(q) }) }
                }
                if (rx.status != "cancelled" && rx.meds.any { !it.done }) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                errorMsg = null
                                runCatching { RxNet.dispense(rx.code, qty.mapValues { it.value.toIntOrNull() ?: 0 }) }
                                    .onSuccess { rx = it; qty.clear(); infoMsg = tr("Saved", "লেখা হয়েছে") }
                                    .onFailure { errorMsg = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy && qty.values.any { (it.toIntOrNull() ?: 0) > 0 },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { ButtonContent(busy, tr("Save what I gave", "যা দিলাম লিখে রাখুন"), R.drawable.ic_check_circle) }
                }
            }
        }
    }
}

/** ফার্মেসির স্টকের তালিকা: সরকারি তালিকা থেকে খুঁজে যোগ, সরানো */
@Composable
private fun StockDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<String>?>(null) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<FindDrug>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { RxNet.stock() }.onSuccess { items = it }.onFailure { errorMsg = it.message; items = emptyList() } }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { found = emptyList(); return@LaunchedEffect }
        delay(300)
        found = runCatching { MedFind.search(q.trim()) }.getOrDefault(emptyList())
    }
    fun change(add: List<String>, remove: List<String>) {
        scope.launch { runCatching { RxNet.stockSet(add, remove) }.onSuccess { items = it; errorMsg = null }.onFailure { errorMsg = it.message } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Stock list", "স্টকের তালিকা"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(q, { q = it.take(60) }, label = { Text(tr("Add a medicine", "ওষুধ যোগ করুন")) }, singleLine = true,
                        leadingIcon = { AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth())
                    ErrorText(errorMsg)
                }
                items(found.take(8), key = { "f" + it.i }) { d ->
                    val have = items?.any { it.equals(d.name, ignoreCase = true) } == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(listOf(d.generic, d.form, d.company).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (have) Tag(tr("Added", "আছে"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        else SmallAction(tr("Add", "যোগ"), R.drawable.ic_add, { change(listOf(d.name), emptyList()) }, primary = true)
                    }
                }
                item {
                    HorizontalDivider()
                    Text(tr("In stock (${items?.size ?: 0})", "স্টকে আছে (${num(items?.size ?: 0)})"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if ((items?.size ?: 0) > 10) OutlinedTextField(filter, { filter = it.take(40) }, label = { Text(tr("Find in my list", "তালিকায় খুঁজুন")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val l = items
                if (l == null) item { LoadingBox() }
                else items(l.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }.take(300), key = { "s$it" }) { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { change(emptyList(), listOf(n)) }) { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// প্যাথলজি: "সেবা" ট্যাবের তালিকা আর নিজের সেন্টার
// ------------------------------------------------------------

@Composable
fun LabContent(profile: Profile) {
    val context = LocalContext.current
    var district by remember { mutableStateOf(profile.placeName) }
    var list by remember { mutableStateOf<List<Lab>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    LaunchedEffect(district) {
        val d = district ?: return@LaunchedEffect
        list = null
        runCatching { RxNet.labs(d) }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message; list = emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FilterBar(areaLabel = areaText(district, null), onAreaClick = { showArea = true })
            ErrorText(errorMsg)
        }
        val l = list
        when {
            l == null -> item { LoadingBox() }
            l.isEmpty() -> item {
                EmptyState(R.drawable.ic_local_hospital, tr("No lab in the app here yet", "এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই"),
                    tr("Know a diagnostic centre? Ask them to join with + at the top.", "কোনো ডায়াগনস্টিক সেন্টার চিনলে উপরের + দিয়ে যোগ হতে বলুন।"), Modifier.fillMaxWidth().padding(top = 24.dp))
            }
            else -> items(l, key = { it.id }) { lab ->
                DirectoryCard(
                    title = lab.name,
                    icon = R.drawable.ic_local_hospital,
                    subtitle = if (lab.homeCollection) tr("Sample from home", "বাসা থেকে নমুনা নেয়") else null,
                    details = listOf(R.drawable.ic_location_on to lab.address, R.drawable.ic_schedule to lab.hours),
                    primary = CardAction(tr("Call", "ফোন করুন"), R.drawable.ic_call) { callPhone(context, lab.phone) },
                    sections = listOf(
                        DetailSection(tr("Address", "ঠিকানা"), listOf(R.drawable.ic_location_on to listOf(lab.address, Areas.label(lab.district, lab.upazilaId)).filter { it.isNotBlank() }.joinToString(", "))),
                        DetailSection(tr("Hours", "সময়"), listOf(R.drawable.ic_schedule to lab.hours, R.drawable.ic_home_health to if (lab.homeCollection) tr("Collects samples from home", "বাসা থেকে নমুনা নেয়") else "")),
                        DetailSection(tr("Tests from a prescription", "প্রেসক্রিপশনের পরীক্ষা"), listOf(R.drawable.ic_medication to tr("Open your prescription and tap \"Send tests to a lab\".", "আপনার প্রেসক্রিপশন খুলে \"পরীক্ষা করাতে পাঠান\" চাপুন।"))),
                    ),
                )
            }
        }
    }
    if (showArea) AreaSheet(district, null, null, { d, _, _ -> district = d }, onDismiss = { showArea = false })
}

/** নিজের প্যাথলজি: না থাকলে নিবন্ধন, থাকলে অবস্থা, কাগজ, আসা পরীক্ষা আর রিপোর্ট দেওয়া */
@Composable
fun MyLabPanel(profile: Profile) {
    val scope = rememberCoroutineScope()
    var lab by remember { mutableStateOf<Lab?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var orders by remember { mutableStateOf<List<LabOrder>>(emptyList()) }
    var code by remember { mutableStateOf("") }
    var entering by remember { mutableStateOf<LabOrder?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { RxNet.labMine() }.onSuccess { lab = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loaded = true
        if (lab?.status == "approved") runCatching { RxNet.labOrders() }.onSuccess { orders = it }
    }
    if (editing) {
        androidx.activity.compose.BackHandler { editing = false }
        LabEditScreen(lab, profile, onBack = { editing = false }) { pending ->
            editing = false
            infoMsg = if (pending) tr("Sent for approval. An admin will check the licence.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।") else tr("Saved", "সেভ হয়েছে")
            reload++
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ErrorText(errorMsg)
        InfoText(infoMsg)
        val l = lab
        val cs = MaterialTheme.colorScheme
        when {
            !loaded -> LoadingBox()
            l == null && errorMsg == null -> {
                EmptyState(R.drawable.ic_local_hospital, tr("Run a diagnostic centre?", "ডায়াগনস্টিক সেন্টার চালান?"),
                    tr("Join so patients can send you tests from their prescriptions, and give reports in the app. Free.", "যোগ দিন — রোগীরা প্রেসক্রিপশন থেকে আপনার কাছে পরীক্ষা পাঠাবেন, রিপোর্ট অ্যাপেই দেবেন। বিনামূল্যে।"),
                    Modifier.padding(top = 24.dp))
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Add my lab", "আমার প্যাথলজি যোগ করুন"), R.drawable.ic_add) }
            }
            l != null -> {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(statusText(l.status), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        StatusReasonLine(l.status, l.statusReason)
                        DocsNeededNotice("lab", l.id, VerifyDocs.lab(), l.docsMissing, l.docsRequested, l.docsNote) { reload++ }
                        IconLine(R.drawable.ic_call, num(l.phone))
                        if (l.status == "pending") SmallNote(tr("You'll appear in the list after approval.", "অনুমোদনের পর তালিকায় দেখাবে।"))
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
                        if (l.status == "suspended" || l.status == "rejected") AppealButton("other")
                    }
                }
                if (l.status == "approved") {
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(tr("Tests", "পরীক্ষা"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            SmallNote(tr("Patient came with a paper prescription? Enter the code under the QR.", "রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে QR-এর নিচের কোড দিন।"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text(tr("Code", "কোড")) }, singleLine = true, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    scope.launch {
                                        busy = true
                                        errorMsg = null
                                        runCatching { RxNet.labOpenCode(code) }.onSuccess { code = ""; entering = it; reload++ }.onFailure { errorMsg = it.message }
                                        busy = false
                                    }
                                }, enabled = !busy && code.length == 10, shape = RoundedCornerShape(12.dp)) { ButtonContent(busy, tr("Open", "খুলুন")) }
                            }
                            if (orders.isEmpty()) SmallNote(tr("No test requests yet", "এখনো কোনো পরীক্ষার অনুরোধ নেই"))
                            orders.forEach { o ->
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(o.patientName.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(o.tests.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                        Text(RxNetText.labStatus(o.status) + " · " + timeAgo(o.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                    when (o.status) {
                                        "sent" -> SmallAction(tr("Sample taken", "নমুনা নিয়েছি"), null, { scope.launch { runCatching { RxNet.labStatus(o.id, "received") }.onFailure { errorMsg = it.message }; reload++ } })
                                        "received" -> SmallAction(tr("Give report", "রিপোর্ট দিন"), null, { entering = o }, primary = true)
                                        "reported" -> SmallAction(tr("View", "দেখুন"), null, { viewing = o.id })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    entering?.let { o -> LabReportEntryDialog(o, onDismiss = { entering = null }) { entering = null; infoMsg = tr("Report sent to the patient", "রোগীর কাছে রিপোর্ট গেছে"); reload++ } }
    viewing?.let { id -> LabReportDialog(id) { viewing = null } }
}

@Composable
private fun LabEditScreen(existing: Lab?, profile: Profile, onBack: () -> Unit, onSaved: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var facilityId by remember { mutableStateOf(existing?.facilityId) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName.orEmpty()) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var license by remember { mutableStateOf(existing?.licenseNo.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var hours by remember { mutableStateOf(existing?.hours.orEmpty()) }
    var homeCollection by remember { mutableStateOf(existing?.homeCollection ?: false) }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
    var agreed by remember { mutableStateOf(existing != null) }
    var pick by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar(if (existing == null) tr("Add my lab", "আমার প্যাথলজি যোগ করুন") else tr("My lab", "আমার প্যাথলজি"), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, if (facilityId != null) name else tr("Choose from the DGHS list", "স্বাস্থ্য অধিদপ্তরের তালিকা থেকে বেছে নিন"), R.drawable.ic_local_hospital)
            }
            SmallNote(tr("Not in the list? Write the details below.", "তালিকায় না পেলে নিচে নিজে লিখুন।"))
            OutlinedTextField(name, { name = it.take(120); facilityId = null }, label = { Text(tr("Centre name", "সেন্টারের নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Address", "ঠিকানা")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(license, { license = it.take(60) }, label = { Text(tr("DGHS licence number", "স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.take(20) }, label = { Text(tr("Mobile number", "মোবাইল নম্বর")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hours, { hours = it.take(80) }, label = { Text(tr("Hours (e.g. 8 AM – 10 PM)", "সময় (যেমন: সকাল ৮টা – রাত ১০টা)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Collect samples from home", "বাসা থেকে নমুনা নিই"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = homeCollection, onCheckedChange = { homeCollection = it })
            }
            DocPhotosField(VerifyDocs.lab(), docs, { docs = it }, if (existing != null) tr("Add new photos only if the papers changed.", "কাগজ বদলালে তবেই নতুন ছবি দিন।") else null)
            ServiceTermsCheck(agreed) { agreed = it }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    errorMsg = when {
                        name.isBlank() -> tr("Write the centre name", "সেন্টারের নাম লিখুন")
                        district.isBlank() -> tr("Choose a district", "জেলা বেছে নিন")
                        existing == null && VerifyDocs.missing(VerifyDocs.lab(), docs) != null -> VerifyDocs.missing(VerifyDocs.lab(), docs)
                        !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                        else -> null
                    }
                    if (errorMsg == null) scope.launch {
                        busy = true
                        runCatching { RxNet.labSave(facilityId, name.trim(), district, upazilaId, address.trim(), license.trim(), phone.trim(), hours.trim(), homeCollection, docs) }
                            .onSuccess { onSaved(it.optBoolean("pending")) }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }
    if (pick) {
        HospitalPickerDialog(
            initialDistrict = district.ifBlank { null },
            onPick = { h ->
                facilityId = h.id
                name = if (isBangla && h.nameBn.isNotBlank()) h.nameBn else h.nameEn
                district = h.district
                upazilaId = h.upazilaId
                if (address.isBlank()) address = h.address
                pick = false
            },
            onDismiss = { pick = false },
        )
    }
}

/** প্যাথলজি: ফল লেখা (প্রতিটা পরীক্ষায় ফল, একক, স্বাভাবিক মাত্রা, বেশি/কম) আর রিপোর্টের ছবি */
@Composable
private fun LabReportEntryDialog(order: LabOrder, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf(*order.tests.map { LabResult(it, "", "", "", "") }.toTypedArray()) }
    var note by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val photoSpecs = remember {
        listOf(
            DocSpec("p1", tr("Report photo 1", "রিপোর্টের ছবি ১"), tr("Optional", "ঐচ্ছিক"), false),
            DocSpec("p2", tr("Report photo 2", "রিপোর্টের ছবি ২"), tr("Optional", "ঐচ্ছিক"), false),
        )
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(topBar = { BackTopBar(tr("Report", "রিপোর্ট") + " · " + order.patientName, { if (!busy) onDismiss() }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEachIndexed { i, r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(r.test, { rows[i] = r.copy(test = it.take(80)) }, label = { Text(tr("Test", "পরীক্ষা")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(r.result, { rows[i] = r.copy(result = it.take(60)) }, label = { Text(tr("Result", "ফল")) }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(r.unit, { rows[i] = r.copy(unit = it.take(30)) }, label = { Text(tr("Unit", "একক")) }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(r.range, { rows[i] = r.copy(range = it.take(60)) }, label = { Text(tr("Normal range", "স্বাভাবিক মাত্রা")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            ChipRows(listOf("high", "low"), setOf(r.flag), perRow = 2, label = { RxNetText.flag(it) }) { rows[i] = r.copy(flag = if (r.flag == it) "" else it) }
                        }
                    }
                }
                TextButton(onClick = { rows.add(LabResult("", "", "", "", "")) }) { ButtonContent(false, tr("Add a row", "আরেকটা সারি"), R.drawable.ic_add) }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                DocPhotosField(photoSpecs, photos, { photos = it }, tr("Photos of the printed report, if you like.", "চাইলে ছাপানো রিপোর্টের ছবি দিন।"))
                ErrorText(errorMsg)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { RxNet.labReport(order.id, rows.filter { it.test.isNotBlank() && it.result.isNotBlank() }, note.trim(), photos.values.filter { it.isNotBlank() }) }
                                .onSuccess { onSent() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && (rows.any { it.test.isNotBlank() && it.result.isNotBlank() } || photos.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { ButtonContent(busy, tr("Send report", "রিপোর্ট পাঠান"), R.drawable.ic_send) }
            }
        }
    }
}

// ------------------------------------------------------------
// ডাক্তার/সহকারী: রিপোর্ট দেখার অনুরোধ — টাকা মেলানো, রিপোর্ট পড়ে মন্তব্য
// ------------------------------------------------------------

@Composable
fun RxReviewsPane(doctorUid: String?, asDoctor: Boolean) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<RxReview>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<RxReview?>(null) }
    LaunchedEffect(reload, doctorUid) { runCatching { RxNet.reviews(doctorUid) }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (asDoctor) TextButton(onClick = { settings = true }) { ButtonContent(false, tr("Online report review: fee & payment number", "অনলাইনে রিপোর্ট দেখা: ফি ও টাকার নম্বর"), R.drawable.ic_settings_outline) }
            ErrorText(errorMsg)
        }
        val l = list
        when {
            l == null && errorMsg == null -> item { LoadingBox() }
            l.isNullOrEmpty() -> item { EmptyState(R.drawable.ic_list_alt, tr("No reports yet", "এখনো কোনো রিপোর্ট নেই"), tr("Reports from labs for your prescriptions appear here.", "আপনার প্রেসক্রিপশনের পরীক্ষার রিপোর্ট এখানে আসবে।"), Modifier.fillMaxWidth().padding(top = 24.dp)) }
            else -> items(l, key = { it.id }) { r ->
                val cs = MaterialTheme.colorScheme
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.patientName.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Tag(RxNetText.reviewStatus(r.status).ifBlank { r.status }, if (r.status == "claimed" || r.status == "paid") cs.tertiaryContainer else cs.surfaceVariant,
                                if (r.status == "claimed" || r.status == "paid") cs.onTertiaryContainer else cs.onSurfaceVariant)
                        }
                        Text(r.labName + " · " + r.tests.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (r.status == "claimed") {
                            Text(RxNetText.pay(r.method) + " · TrxID: " + r.trxId + if (r.fee.isNotBlank()) " · " + r.fee else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            SmallNote(tr("Check your payment app for this TrxID.", "আপনার bKash/Nagad-এ এই TrxID মিলিয়ে দেখুন।"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction(tr("Received", "টাকা পেয়েছি"), R.drawable.ic_check_circle, {
                                    scope.launch { runCatching { RxNet.confirm(r.id, true, doctorUid = doctorUid) }.onFailure { errorMsg = it.message }; reload++ }
                                }, primary = true)
                                SmallAction(tr("Not found", "মেলেনি"), null, {
                                    scope.launch { runCatching { RxNet.confirm(r.id, false, tr("TrxID not found", "TrxID মেলেনি"), doctorUid) }.onFailure { errorMsg = it.message }; reload++ }
                                })
                            }
                        }
                        if (r.status == "paid" && asDoctor) SmallAction(tr("Read report & reply", "রিপোর্ট দেখে জানান"), R.drawable.ic_list_alt, { reading = r }, primary = true)
                        if (r.status == "done" && r.comment.isNotBlank()) Text(r.comment, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (settings) ReviewSettingsDialog { settings = false }
    reading?.let { r -> ReviewReadDialog(r, onDismiss = { reading = null }) { reading = null; reload++ } }
}

@Composable
private fun ReviewReadDialog(r: RxReview, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf<LabOrder?>(null) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id) { runCatching { RxNet.labOrder(r.labOrderId) }.onSuccess { order = it }.onFailure { errorMsg = it.message } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(r.patientName, { if (!busy) onDismiss() }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ErrorText(errorMsg)
                order?.let { LabReportView(it) } ?: run { if (errorMsg == null) LoadingBox() }
                OutlinedTextField(comment, { comment = it.take(1000) }, label = { Text(tr("What should the patient know?", "রোগীকে কী জানাবেন?")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                SmallNote(tr("Need to change medicines? Write a new prescription from the Prescriptions tab.", "ওষুধ বদলাতে হলে \"প্রেসক্রিপশন\" থেকে নতুন লিখুন।"))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { RxNet.reviewDone(r.id, comment.trim()) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && comment.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { ButtonContent(busy, tr("Send to patient", "রোগীকে পাঠান"), R.drawable.ic_send) }
            }
        }
    }
}

@Composable
private fun ReviewSettingsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<ReviewSettings?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { RxNet.settings() }.onSuccess { s = it }.onFailure { errorMsg = it.message } }
    FormDialog(
        title = tr("Online report review", "অনলাইনে রিপোর্ট দেখা"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val v = s ?: run { if (errorMsg == null) LoadingBox(); ErrorText(errorMsg); return@Column }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Check reports online", "অনলাইনে রিপোর্ট দেখব"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = v.enabled, onCheckedChange = { s = v.copy(enabled = it) })
                }
                if (v.enabled) {
                    OutlinedTextField(v.fee, { s = v.copy(fee = it.take(40)) }, label = { Text(tr("Fee", "ফি")) }, enabled = !v.noFee, singleLine = true, modifier = Modifier.fillMaxWidth())
                    NoFeeCheck(v.noFee) { s = v.copy(noFee = it, fee = if (it) "" else v.fee) }
                    if (!v.noFee) {
                        OutlinedTextField(v.payNumber, { s = v.copy(payNumber = it.take(20)) }, label = { Text(tr("Number to receive money", "যে নম্বরে টাকা নেবেন")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                        ChipRows(listOf("bkash", "nagad", "rocket"), v.payMethods.toSet(), perRow = 3, label = { RxNetText.pay(it) }) { m ->
                            s = v.copy(payMethods = if (m in v.payMethods) v.payMethods - m else v.payMethods + m)
                        }
                        SmallNote(tr("Patients send money to you directly and enter the TrxID. You or your assistant confirm it; then the report opens for you.", "রোগী সরাসরি আপনাকে টাকা পাঠিয়ে TrxID দেবেন। আপনি বা সহকারী মিলিয়ে নিশ্চিত করলে তবেই রিপোর্ট খুলবে।"))
                    }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = s ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.settings(v) }.onSuccess { onDismiss() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && s != null) { ButtonContent(busy, tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ডাক্তারের "রিপোর্ট" পাতা (নোটিফিকেশন থেকে) */
@Composable
fun RxReviewsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(tr("Reports", "রিপোর্ট"), onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { RxReviewsPane(null, asDoctor = true) }
    }
}
