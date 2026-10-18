@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
data class RxOrder(
    val id: String, val pharmacyName: String, val status: String, val reason: String, val createdAt: Long,
    val mode: String = "shop", val price: String = "", val eta: String = "", val deliveryFee: String = "", val missed: List<String> = emptyList(),
) {
    val open: Boolean get() = status in setOf("sent", "ready", "accepted", "out")
}
data class LabResult(
    val test: String, val result: String, val unit: String, val range: String, val flag: String,
    val group: String = "", val code: String = "", val key: String = "", val low: Double? = null, val high: Double? = null, val long: Boolean = false,
)
data class LabHist(val result: String, val flag: String, val at: Long)
/** পরীক্ষার ছক: ঘর, একক, স্বাভাবিক মাত্রা (ল্যাবের নিজের থাকলে সেটা), লেখা-ফলের বাছাই */
data class LabParam(val key: String, val name: String, val unit: String, val low: Double?, val high: Double?, val range: String, val text: String, val options: List<String>, val long: Boolean)
data class LabForm(val test: String, val code: String?, val name: String, val params: List<LabParam>)
data class Missed(val k: String, val name: String, val count: Int, val lastAt: Long)
data class OnlineSettings(val enabled: Boolean, val radiusKm: Int, val fee: String, val note: String)
data class PharmacyHome(val shopCode: String, val online: OnlineSettings, val hasLocation: Boolean, val missed: List<Missed>)
data class ShopInfo(val id: String, val name: String, val address: String, val phone: String, val online: Boolean, val deliveryFee: String)
data class LabReport(val items: List<LabResult>, val note: String, val reportedAt: Long, val photoCount: Int, val photos: List<String>)
data class LabOrder(
    val id: String, val rxId: String, val code: String, val labName: String, val patientName: String, val patientAge: Int?,
    val patientGender: String, val doctorName: String, val tests: List<String>, val status: String, val createdAt: Long, val report: LabReport?,
    /** একই রোগীর আগের ফল: "code.key" → নতুন আগে */
    val history: Map<String, List<LabHist>> = emptyMap(),
    /** পাঠানোর সময়ের আনুমানিক দাম (LabPrice.kt) */
    val prices: PriceSnap? = null,
    /** বাসা থেকে নমুনা (চাইলে) */
    val home: LabHome? = null,
)
data class RxReview(
    val id: String, val rxId: String, val labOrderId: String, val doctorUid: String, val doctorName: String, val patientName: String,
    val labName: String, val tests: List<String>, val status: String, val fee: String, val noFee: Boolean, val payNumber: String,
    val payMethods: List<String>, val method: String, val trxId: String, val rejectNote: String, val comment: String, val updatedAt: Long,
)
/**
 * ডাক্তারের দিক থেকে ল্যাব অর্ডার (rx_patient_history / rx_get-এর labOrders, সার্ভারের doctorLabView)।
 * অবস্থা সবসময়; report শুধু open হলে (রিপোর্ট দেখার টাকা মিলেছে বা "ফি চাই না") — নইলে null। review: অনুরোধের অবস্থা।
 */
data class DoctorLab(
    val id: String, val rxId: String, val labName: String, val tests: List<String>, val status: String, val createdAt: Long, val reportedAt: Long,
    val review: String?, val open: Boolean, val report: LabReport?, val comment: String,
)
data class RxActivity(val meds: List<MedStat>, val dispensed: List<Dispensing>, val orders: List<RxOrder>, val labOrders: List<LabOrder>, val reviews: List<RxReview>, val nextOpen: Long?)
data class PharmOption(
    val id: String, val name: String, val phone: String, val address: String, val km: Double?, val openNow: Boolean, val keepsStock: Boolean,
    val have: Int, val total: Int, val missing: List<String>, val online: Boolean = false, val deliveryFee: String = "", val onlineNote: String = "",
)
data class PharmRx(val id: String, val code: String, val status: String, val createdAt: Long, val doctorName: String, val regNo: String, val patientName: String, val patientAge: Int?, val meds: List<MedStat>, val dispensed: List<Dispensing>)
data class PharmOrder(
    val id: String, val code: String, val patientName: String, val note: String, val status: String, val createdAt: Long,
    val mode: String = "shop", val price: String = "", val eta: String = "", val deliveryFee: String = "", val address: String = "", val phone: String = "",
    val missed: List<String> = emptyList(),
)
data class Lab(
    val id: String, val name: String, val phone: String, val address: String, val district: String, val upazilaId: Int?, val facilityId: String?,
    val homeCollection: Boolean, val hours: String, val licenseNo: String = "", val status: String = "", val statusReason: String = "",
    val docsMissing: Boolean = false, val docsRequested: Boolean = false, val docsNote: String = "",
    /** পরীক্ষা ও দামের সারাংশ (সবার দেখা; তালিকা না থাকলে null) — পুরো তালিকা RxNet.labPricesPublic */
    val prices: PriceSummary? = null,
)
data class ReviewSettings(val enabled: Boolean, val noFee: Boolean, val fee: String, val payNumber: String, val payMethods: List<String>)

object RxNet {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.intN(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONObject.dblN(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

    private fun med(o: JSONObject) = MedStat(o.optInt("i"), o.str("name"), o.str("form"), o.str("dose"), o.optInt("days"), o.intN("need"), o.optInt("got"), o.intN("left"), o.optBoolean("done"))
    private fun disp(o: JSONObject) = Dispensing(o.str("name"), o.optLong("at"), o.optJSONArray("items").objs().map { it.optInt("i") to it.optInt("qty") })
    private fun reportOf(r: JSONObject) = LabReport(
        r.optJSONArray("items").objs().map {
            LabResult(it.str("test"), it.str("result"), it.str("unit"), it.str("range"), it.str("flag"), it.str("group"), it.str("code"), it.str("key"),
                it.dblN("low"), it.dblN("high"), it.optBoolean("long"))
        },
        r.str("note"), r.optLong("reportedAt"), r.optInt("photoCount"), r.optJSONArray("photos").strings(),
    )
    private fun labOrder(o: JSONObject) = LabOrder(
        o.str("id"), o.str("rxId"), o.str("code"), o.str("labName"), o.str("patientName"), o.intN("patientAge"), o.str("patientGender"),
        o.str("doctorName"), o.optJSONArray("tests").strings(), o.str("status"), o.optLong("createdAt"),
        o.optJSONObject("report")?.let { r -> reportOf(r) },
        prices = o.optJSONObject("prices")?.let(::snap),
        home = o.optJSONObject("home")?.let(::home),
    )
    private fun snap(p: JSONObject) = PriceSnap(
        p.optJSONArray("items").objs().map { SnapItem(it.str("test"), it.str("name"), it.optInt("price"), it.str("tat")) },
        p.optJSONArray("missing").strings(), p.optInt("subtotal"), p.intN("discountPct"), p.optInt("estimate"),
    )
    private fun home(h: JSONObject) = LabHome(
        h.str("status").ifBlank { "requested" }, h.optLong("date"), h.str("slot"), h.str("note"), h.intN("fee"), h.optLong("visitDate"), h.str("time"),
        h.str("confirmNote"), h.str("declineNote"), h.str("address"), h.str("phone"),
    )
    private fun prices(p: JSONObject) = LabPrices(
        p.optJSONArray("items").objs().map { PriceItem(it.str("code").ifBlank { null }, it.str("name"), it.optInt("price"), it.str("tat")) },
        p.intN("discountPct"), p.intN("homeFee"),
    )

    /** ডাক্তার: রোগীর ইতিহাস / প্রেসক্রিপশনের labOrders (ফল শুধু open হলে) */
    fun doctorLabs(a: JSONArray?): List<DoctorLab> = a.objs().map {
        DoctorLab(
            it.str("id"), it.str("rxId"), it.str("labName"), it.optJSONArray("tests").strings(), it.str("status"), it.optLong("createdAt"), it.optLong("reportedAt"),
            it.str("review").ifBlank { null }, it.optBoolean("open"), it.optJSONObject("report")?.let { r -> reportOf(r) }, it.str("comment"),
        )
    }
    private fun review(o: JSONObject) = RxReview(
        o.str("id"), o.str("rxId"), o.str("labOrderId"), o.str("doctorUid"), o.str("doctorName"), o.str("patientName"), o.str("labName"),
        o.optJSONArray("tests").strings(), o.str("status"), o.str("fee"), o.optBoolean("noFee"), o.str("payNumber"), o.optJSONArray("payMethods").strings(),
        o.str("method"), o.str("trxId"), o.str("rejectNote"), o.str("comment"), o.optLong("updatedAt"),
    )
    private fun lab(o: JSONObject) = Lab(
        o.str("id"), o.str("name"), o.str("phone"), o.str("address"), o.str("district"), o.intN("upazilaId"), o.str("facilityId").ifBlank { null },
        o.optBoolean("homeCollection"), o.str("hours"), o.str("licenseNo"), o.str("status"), o.str("statusReason"),
        o.optBoolean("docsMissing"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
        o.optJSONObject("prices")?.let { PriceSummary(it.optInt("count"), it.optInt("from"), it.intN("discountPct"), it.intN("homeFee")) },
    )

    suspend fun activity(rxId: String): RxActivity {
        val o = call("rx_activity", JSONObject().put("rxId", rxId))
        return RxActivity(
            o.optJSONArray("medicines").objs().map(::med), o.optJSONArray("dispensed").objs().map(::disp),
            o.optJSONArray("orders").objs().map {
                RxOrder(it.str("id"), it.str("pharmacyName"), it.str("status"), it.str("reason"), it.optLong("createdAt"),
                    it.str("mode").ifBlank { "shop" }, it.str("price"), it.str("eta"), it.str("deliveryFee"), it.optJSONArray("missed").strings())
            },
            o.optJSONArray("labOrders").objs().map(::labOrder), o.optJSONArray("reviews").objs().map(::review),
            if (o.isNull("nextOpen") || !o.has("nextOpen")) null else o.optLong("nextOpen"),
        )
    }

    /** mode "shop": দোকানে দাঁড়িয়ে (কাছেরটা আগে); "online": অনলাইনে বিক্রি করা দোকান */
    suspend fun pharmacyOptions(rxId: String, district: String, lat: Double?, lng: Double?, mode: String, q: String = ""): List<PharmOption> =
        call("rx_pharmacy_options", JSONObject().put("rxId", rxId).put("district", district).put("mode", mode).put("q", q).apply { if (lat != null && lng != null) put("lat", lat).put("lng", lng) })
            .optJSONArray("items").objs().map {
                PharmOption(it.str("id"), it.str("name"), it.str("phone"), it.str("address"), if (it.isNull("km")) null else it.optDouble("km"),
                    it.optBoolean("openNow"), it.optBoolean("keepsStock"), it.optInt("have"), it.optInt("total"), it.optJSONArray("missing").strings(),
                    it.optBoolean("online"), it.str("deliveryFee"), it.str("onlineNote"))
            }

    suspend fun sendPharmacy(rxId: String, pharmacyId: String, mode: String = "shop", address: String = "", phone: String = "", note: String = "") =
        call("rx_send_pharmacy", JSONObject().put("rxId", rxId).put("pharmacyId", pharmacyId).put("mode", mode).put("address", address).put("phone", phone).put("note", note))
    suspend fun withdraw(orderId: String, notAvailable: Boolean) = call("rx_order_withdraw", JSONObject().put("id", orderId).put("reason", if (notAvailable) "not_available" else "other"))
    suspend fun shopFind(code: String): ShopInfo = call("rx_shop_find", JSONObject().put("code", code)).getJSONObject("item").let {
        ShopInfo(it.str("id"), it.str("name"), it.str("address"), it.str("phone"), it.optBoolean("online"), it.str("deliveryFee"))
    }
    suspend fun labs(district: String, facilityId: String? = null): List<Lab> =
        call("labs", JSONObject().put("district", district).apply { if (facilityId != null) put("facilityId", facilityId) }).optJSONArray("items").objs().map(::lab)
    /** home: বাসা থেকে নমুনা (ঠিকানা, ফোন, দিন ০/১/২, সময়ের ভাগ, নোট) — না চাইলে null */
    suspend fun sendLab(rxId: String, labId: String, tests: List<String>, home: HomeRequest? = null) = call(
        "rx_send_lab",
        JSONObject().put("rxId", rxId).put("labId", labId).put("tests", JSONArray(tests)).apply {
            if (home != null) put("home", JSONObject().put("address", home.address).put("phone", home.phone).put("day", home.day).put("slot", home.slot).put("note", home.note))
        },
    )
    /** একটা প্যাথলজির পুরো পরীক্ষা ও দামের তালিকা (বিস্তারিত খুললে; অতিথিও) — না থাকলে null */
    suspend fun labPricesPublic(labId: String): LabPrices? = call("lab_prices_public", JSONObject().put("labId", labId)).optJSONObject("prices")?.let(::prices)
    /**
     * এই প্রেসক্রিপশনের পরীক্ষা কোন প্যাথলজিতে কয়টা হয়, আনুমানিক কত
     * (সার্ভার সাজায়: ডাক্তারের বলা জায়গা → বেশি পরীক্ষা → কম দাম → কাছে — উপজেলা দিলে একই উপজেলা আগে)
     */
    suspend fun labOptions(rxId: String, district: String, upazilaId: Int?, tests: List<String>): LabOptions {
        val o = call("rx_lab_options", JSONObject().put("rxId", rxId).put("district", district).put("tests", JSONArray(tests)).apply { if (upazilaId != null) put("upazilaId", upazilaId) })
        val last = o.optJSONObject("lastHome")
        return LabOptions(
            o.optJSONArray("items").objs().map {
                LabOption(
                    lab(it), it.optBoolean("suggested"), it.optBoolean("priced"), it.intN("have"), it.optInt("total"), it.optJSONArray("missing").strings(),
                    it.intN("subtotal"), it.intN("discountPct"), it.intN("estimate"), it.intN("homeFee"), it.str("tat"),
                )
            },
            last?.str("address").orEmpty(), last?.str("phone").orEmpty(),
        )
    }
    /** self: ডাক্তার নিজের কাজে (কেয়ারগিভার মোড চালু থাকলেও রোগীর uid নয় — asUid খালি দিলে সার্ভার নিজেকেই ধরে) */
    suspend fun labOrder(id: String, self: Boolean = false): LabOrder {
        val o = call("lab_order_get", JSONObject().put("id", id).apply { if (self) put("asUid", "") })
        val h = o.optJSONObject("history")
        val hist = h?.keys()?.asSequence()?.associateWith { k -> h.optJSONArray(k).objs().map { LabHist(it.str("result"), it.str("flag"), it.optLong("at")) } }.orEmpty()
        return labOrder(o.getJSONObject("item")).copy(history = hist)
    }
    suspend fun pay(id: String, method: String, trxId: String) = call("review_pay", JSONObject().put("id", id).put("method", method).put("trxId", trxId))

    // ফার্মেসি
    private fun pharmRx(o: JSONObject): PharmRx {
        val d = o.optJSONObject("doctor") ?: JSONObject()
        val p = o.optJSONObject("patient") ?: JSONObject()
        return PharmRx(o.str("id"), o.str("code"), o.str("status"), o.optLong("createdAt"), d.str("name"), d.str("regNo"), p.str("name"), p.intN("age"),
            o.optJSONArray("medicines").objs().map(::med), o.optJSONArray("dispensed").objs().map(::disp))
    }
    suspend fun pharmacyOpen(code: String): PharmRx = pharmRx(call("rx_pharmacy_open", JSONObject().put("code", code)).getJSONObject("item"))
    /** missing: যে ওষুধ নেই (প্রেসক্রিপশনের ক্রম) — রোগী জানেন, দোকানের "দিতে পারেননি" তালিকায় জমা */
    suspend fun dispense(code: String, items: Map<Int, Int>, missing: Set<Int> = emptySet()): PharmRx =
        pharmRx(
            call(
                "rx_dispense",
                JSONObject().put("code", code).put("missing", JSONArray(missing.toList()))
                    .put("items", JSONArray(items.filter { it.value > 0 && it.key !in missing }.map { (i, q) -> JSONObject().put("i", i).put("qty", q) })),
            ).getJSONObject("item"),
        )
    suspend fun pharmacyOrders(): List<PharmOrder> = call("rx_orders_pharmacy").optJSONArray("items").objs().map {
        PharmOrder(it.str("id"), it.str("code"), it.str("patientName"), it.str("note"), it.str("status"), it.optLong("createdAt"),
            it.str("mode").ifBlank { "shop" }, it.str("price"), it.str("eta"), it.str("deliveryFee"), it.str("address"), it.str("phone"), it.optJSONArray("missed").strings())
    }
    suspend fun orderUpdate(id: String, status: String, reason: String = "", price: String = "", eta: String = "") =
        call("rx_order_update", JSONObject().put("id", id).put("status", status).put("reason", reason).put("price", price).put("eta", eta))
    suspend fun pharmacyHome(): PharmacyHome {
        val o = call("rx_pharmacy_home")
        val on = o.optJSONObject("online") ?: JSONObject()
        return PharmacyHome(
            o.str("shopCode"), OnlineSettings(on.optBoolean("enabled"), on.optInt("radiusKm", 3), on.str("fee"), on.str("note")), o.optBoolean("hasLocation"),
            o.optJSONArray("missed").objs().map { Missed(it.str("k"), it.str("name"), it.optInt("count"), it.optLong("lastAt")) },
        )
    }
    suspend fun onlineSet(s: OnlineSettings) = call("rx_pharmacy_online_set", JSONObject().put("enabled", s.enabled).put("radiusKm", s.radiusKm).put("fee", s.fee).put("note", s.note))
    suspend fun missedClear(k: String) = call("rx_missed_clear", JSONObject().put("k", k))
    suspend fun stock(): List<String> = call("stock_get").optJSONArray("items").strings()
    suspend fun stockSet(add: List<String>, remove: List<String>): List<String> = call("stock_set", JSONObject().put("add", JSONArray(add)).put("remove", JSONArray(remove))).optJSONArray("items").strings()

    // প্যাথলজি
    suspend fun labMine(): Lab? = call("lab_me").optJSONObject("lab")?.let(::lab)
    suspend fun labSave(facilityId: String?, name: String, district: String, upazilaId: Int?, address: String, licenseNo: String, phone: String, hours: String, homeCollection: Boolean, docs: Map<String, String>) =
        call("lab_save", JSONObject().put("facilityId", facilityId ?: JSONObject.NULL).put("name", name).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
            .put("address", address).put("licenseNo", licenseNo).put("phone", phone).put("hours", hours).put("homeCollection", homeCollection).put("termsAccepted", true).put("docs", VerifyDocs.json(docs)))
    suspend fun labOrders(): List<LabOrder> = call("lab_orders").optJSONArray("items").objs().map(::labOrder)
    suspend fun labOpenCode(code: String): LabOrder = labOrder(call("lab_open_code", JSONObject().put("code", code)).getJSONObject("item"))
    suspend fun labStatus(id: String, status: String) = call("lab_order_status", JSONObject().put("id", id).put("status", status))
    /** বাসা থেকে নমুনা: সময় নিশ্চিত (date: UTC মধ্যরাত, time "08:00") — রোগী জানেন */
    suspend fun homeConfirm(id: String, date: Long, time: String, note: String) = call("lab_home_confirm", JSONObject().put("id", id).put("date", date).put("time", time).put("note", note))
    /** বাসায় যেতে পারব না — অর্ডার থাকে, রোগী জানেন */
    suspend fun homeDecline(id: String, note: String) = call("lab_home_decline", JSONObject().put("id", id).put("note", note))
    /** রোগী: বাসা থেকে নমুনার সময়/ঠিকানা বদল (প্যাথলজি সময় জানানোর আগে) — প্যাথলজি জানে */
    suspend fun homeChange(id: String, home: HomeRequest) = call(
        "lab_home_change",
        JSONObject().put("id", id).put("home", JSONObject().put("address", home.address).put("phone", home.phone).put("day", home.day).put("slot", home.slot).put("note", home.note)),
    )
    /** রোগী: বাসা থেকে নয় — প্যাথলজিতে গিয়ে নমুনা (অর্ডার থাকে), প্যাথলজি জানে */
    suspend fun homeCancel(id: String) = call("lab_home_cancel", JSONObject().put("id", id))
    suspend fun labReport(id: String, items: List<LabResult>, note: String, photos: List<String>) = call(
        "lab_report",
        JSONObject().put("id", id).put("note", note).put("photos", JSONArray(photos))
            .put(
                "items",
                JSONArray(items.map {
                    JSONObject().put("test", it.test).put("result", it.result).put("unit", it.unit).put("range", it.range).put("flag", it.flag).apply {
                        if (it.code.isNotBlank()) put("group", it.group).put("code", it.code).put("key", it.key)
                        if (it.low != null) put("low", it.low)
                        if (it.high != null) put("high", it.high)
                        if (it.long) put("long", true)
                    }
                }),
            ),
    )
    suspend fun labForm(id: String): List<LabForm> = call("lab_form", JSONObject().put("id", id)).optJSONArray("forms").objs().map { f ->
        LabForm(f.str("test"), f.str("code").ifBlank { null }, f.str("name"), f.optJSONArray("params").objs().map {
            LabParam(it.str("key"), it.str("name"), it.str("unit"), it.dblN("low"), it.dblN("high"), it.str("range"), it.str("text"), it.optJSONArray("options").strings(), it.optBoolean("long"))
        })
    }
    /** ল্যাবের নিজের মেশিনের মাত্রা (code, key, low, high) */
    suspend fun labRanges(list: List<LabResult>) = call(
        "lab_ranges_set",
        JSONObject().put("ranges", JSONArray(list.map { JSONObject().put("code", it.code).put("key", it.key).put("low", it.low ?: JSONObject.NULL).put("high", it.high ?: JSONObject.NULL) })),
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

/** দোকানের QR: ভেতরে https://…/shop/{কোড}; স্ক্যান করলে কোড বের করা */
object ShopQr {
    fun url(code: String) = "${ServerApi.BASE_URL}/shop/$code"

    private val CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")

    /** QR-এর লেখা → দোকানের কোড (আমাদের /shop/ লিংক, অথবা শুধু কোড) */
    fun codeOf(raw: String): String? {
        val t = raw.trim()
        Regex("/shop/([A-Za-z0-9]{6})(?:[/?#].*)?$").find(t)?.let { m -> m.groupValues[1].uppercase().takeIf { CODE.matches(it) }?.let { return it } }
        return t.uppercase().takeIf { CODE.matches(it) }
    }

    fun scan(context: android.content.Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, opts).startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue.orEmpty()
                val code = codeOf(raw)
                when {
                    code != null -> onCode(code)
                    raw.contains("/rx/") -> onError(tr("That's a prescription QR. Scan the QR at the pharmacy counter.", "এটা প্রেসক্রিপশনের QR। ফার্মেসির কাউন্টারের QR স্ক্যান করুন।"))
                    else -> onError(tr("This isn't a Lifolo pharmacy QR", "এটা Lifolo ফার্মেসির QR নয়"))
                }
            }
            .addOnFailureListener { onError(tr("Couldn't open the scanner. Type the shop's name or code instead.", "স্ক্যানার খোলা গেল না। দোকানের নাম বা কোড লিখুন।")) }
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
        "accepted" -> tr("Order accepted", "অর্ডার নিয়েছে")
        "out" -> tr("On the way", "পথে আছে")
        "done" -> tr("Given", "দেওয়া হয়েছে")
        "cancelled" -> tr("Can't provide", "দিতে পারবে না")
        "withdrawn" -> tr("Taken back", "ফিরিয়ে নিয়েছেন")
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

    /** রিপোর্ট এসেছে কিন্তু ডাক্তারের জন্য ফল বন্ধ — কেন, কী করলে খুলবে (ওয়েবের labLocked) */
    fun labLocked(review: String?): String = when (review) {
        "unpaid" -> tr("You can see it when the patient asks you to check the report", "রোগী রিপোর্ট দেখানোর অনুরোধ করলে দেখা যাবে")
        "claimed" -> tr("The patient sent the fee — confirm it in Reports to see the results", "রোগী টাকা পাঠিয়েছেন — \"রিপোর্ট\" থেকে টাকা মিলিয়ে নিলে দেখা যাবে")
        else -> tr("Online report review was off — see the report with the patient", "অনলাইনে রিপোর্ট দেখা বন্ধ ছিল — রিপোর্ট রোগীর কাছ থেকে দেখে নিন")
    }

    fun flag(f: String): String = when (f) {
        "high" -> tr("High", "বেশি")
        "low" -> tr("Low", "কম")
        "abnormal" -> tr("Abnormal", "অস্বাভাবিক")
        else -> ""
    }

    /** সংখ্যার ফল মাত্রার সাথে মিলিয়ে বেশি/কম */
    fun autoFlag(result: String, low: Double?, high: Double?): String? {
        if (low == null && high == null) return null
        val v = result.replace(",", "").trim().toDoubleOrNull() ?: return null
        return when {
            high != null && v > high -> "high"
            low != null && v < low -> "low"
            else -> ""
        }
    }

    fun range(low: Double?, high: Double?): String {
        fun f(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
        return when {
            low != null && high != null -> "${f(low)} – ${f(high)}"
            high != null -> "< ${f(high)}"
            low != null -> "> ${f(low)}"
            else -> ""
        }
    }
}

// ------------------------------------------------------------
// রোগী: প্রেসক্রিপশনের ভেতরে — ওষুধ কেনা, ফার্মেসি, পরীক্ষা, রিপোর্ট, ডাক্তারকে দেখানো
// ------------------------------------------------------------

/** paper দিলে: উপরে বড় "ফার্মেসিতে পাঠান" / "প্যাথলজিতে পাঠান", তারপর কাগজ, তারপর বাকিটা — লম্বা কাগজের নিচে বাটন খুঁজে পাওয়া যেত না */
@Composable
fun RxActivitySection(rx: Rx, profile: Profile, paper: (@Composable () -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var act by remember { mutableStateOf<RxActivity?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var pickPharmacy by remember { mutableStateOf<String?>(null) }
    var withdrawing by remember { mutableStateOf<RxOrder?>(null) }
    var pickLab by remember { mutableStateOf(false) }
    var sendChoice by remember { mutableStateOf(false) }
    var homeEdit by remember { mutableStateOf<LabOrder?>(null) }
    var homeCancel by remember { mutableStateOf<LabOrder?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<RxReview?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { RxNet.activity(rx.id) }.onSuccess { act = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    val a = act
    // লোড হওয়ার সময় উপরের বাটন নেই, কাগজ আগের মতোই দেখায়
    if (a == null) {
        ErrorText(errorMsg)
        InfoText(infoMsg)
        paper?.invoke()
        return
    }
    val cs = MaterialTheme.colorScheme

    // ---- উপরে: পাঠানোর বড় বাটন (নিচের বাটনের মতোই শর্ত) ----
    val canPharmacy = !rx.cancelled && rx.medicines.isNotEmpty() && a.meds.any { !it.done }
    val canLab = !rx.cancelled && rx.tests.isNotEmpty()
    if (canPharmacy || canLab) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canPharmacy) {
                Button(onClick = { sendChoice = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                    ButtonContent(false, tr("Send to a pharmacy", "ফার্মেসিতে পাঠান"), R.drawable.ic_medication)
                }
            }
            if (canLab) {
                Button(onClick = { pickLab = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                    ButtonContent(false, tr("Send to a lab", "প্যাথলজিতে পাঠান"), R.drawable.ic_local_hospital)
                }
            }
        }
    }
    ErrorText(errorMsg)
    InfoText(infoMsg)
    paper?.invoke()

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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconLine(
                            if (o.mode == "online") R.drawable.ic_local_shipping else R.drawable.ic_storefront,
                            o.pharmacyName + " — " + RxNetText.orderStatus(o.status) + if (o.reason.isNotBlank()) " (${o.reason})" else "",
                        )
                        val extra = listOf(o.price, o.eta, o.deliveryFee.takeIf { it.isNotBlank() }?.let { tr("Delivery: $it", "ডেলিভারি: $it") }.orEmpty()).filter { it.isNotBlank() }
                        if (o.open && extra.isNotEmpty()) SmallNote(extra.joinToString(" · "))
                        if (o.missed.isNotEmpty()) SmallNote(tr("Didn't have: ", "ছিল না: ") + o.missed.joinToString(", "))
                        // দোকান সব দিতে না পারলে ফিরিয়ে নিয়ে অন্য দোকানে
                        if (o.status in setOf("sent", "ready", "accepted")) SmallAction(tr("Take back", "ফিরিয়ে নিন"), R.drawable.ic_close, { withdrawing = o })
                    }
                }
                if (!rx.cancelled && a.meds.any { !it.done }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickPharmacy = "shop" }, modifier = Modifier.weight(1f)) {
                            ButtonContent(false, tr("I'm at a pharmacy", "দোকানে আছি"), R.drawable.ic_storefront)
                        }
                        OutlinedButton(onClick = { pickPharmacy = "online" }, modifier = Modifier.weight(1f)) {
                            ButtonContent(false, tr("Order online", "অনলাইনে অর্ডার"), R.drawable.ic_local_shipping)
                        }
                    }
                    SmallNote(
                        tr(
                            "At the shop: send the prescription to it in the app, or show the QR. They note what they give; get the rest from another shop.",
                            "দোকানে গিয়ে: অ্যাপে সেই দোকানে পাঠান, অথবা QR দেখান। যতটা দেবে লিখে রাখবে; বাকিটা অন্য দোকান থেকে নিন।",
                        ),
                    )
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
                        // পাঠানোর সময়ের আনুমানিক দাম, বাসা থেকে নমুনার অবস্থা
                        if (o.prices != null && o.status != "cancelled") Text(LabPriceText.snapLine(o.prices, o.home), style = MaterialTheme.typography.bodySmall)
                        if (o.home != null && o.status in setOf("sent", "received")) {
                            IconLine(
                                R.drawable.ic_home_health, LabPriceText.homeStatusPatient(o.home),
                                when (o.home.status) { "declined", "expired" -> cs.error; "confirmed" -> cs.primary; else -> cs.onSurfaceVariant },
                            )
                            if (o.home.status == "confirmed" && o.home.confirmNote.isNotBlank() && o.status == "sent") SmallNote(o.home.confirmNote)
                            // প্যাথলজি সময় জানানোর আগে (সময় পেরিয়ে গেলেও): সময় বদলানো, বা বাসা থেকে নয়
                            if (rx.status != "cancelled" && LabPriceText.homeEditable(o.home, o.status)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallAction(tr("Change time", "সময় বদলান"), R.drawable.ic_edit, { homeEdit = o }, primary = o.home.status == "expired")
                                    SmallAction(tr("I'll go to the lab", "প্যাথলজিতে গিয়ে দেব"), R.drawable.ic_close, { homeCancel = o })
                                }
                            }
                        }
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

    // ফার্মেসিতে পাঠান → দোকানে আছি, না অনলাইনে
    if (sendChoice) {
        AlertDialog(
            onDismissRequest = { sendChoice = false },
            title = { Text(tr("Send to a pharmacy", "ফার্মেসিতে পাঠান")) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(tr("I'm at a pharmacy — send to this shop", "দোকানে আছি — এই দোকানে পাঠান")) },
                        leadingContent = { AppIcon(R.drawable.ic_storefront, null, tint = cs.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { sendChoice = false; pickPharmacy = "shop" },
                    )
                    ListItem(
                        headlineContent = { Text(tr("Order online — home delivery", "অনলাইনে অর্ডার — বাসায় ডেলিভারি")) },
                        leadingContent = { AppIcon(R.drawable.ic_local_shipping, null, tint = cs.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { sendChoice = false; pickPharmacy = "online" },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sendChoice = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    pickPharmacy?.let { mode ->
        PharmacyPickDialog(rx, profile, mode, onDismiss = { pickPharmacy = null }) { name ->
            pickPharmacy = null
            infoMsg = if (mode == "online") tr("Order sent to $name. Pay when the medicines arrive.", "$name-এ অর্ডার গেছে। ওষুধ পৌঁছালে দাম দেবেন।") else tr("Sent to $name", "$name-এ পাঠানো হয়েছে")
            reload++
        }
    }
    withdrawing?.let { o ->
        WithdrawDialog(o, onDismiss = { withdrawing = null }) {
            withdrawing = null
            infoMsg = tr("Taken back. You can send it to another pharmacy.", "ফিরিয়ে নেওয়া হয়েছে। অন্য ফার্মেসিতে পাঠাতে পারেন।")
            reload++
        }
    }
    if (pickLab) LabPickDialog(rx, profile, onDismiss = { pickLab = false }) { name ->
        pickLab = false
        infoMsg = tr("Sent to $name", "$name-এ পাঠানো হয়েছে")
        reload++
    }
    homeEdit?.let { o ->
        val h = o.home
        if (h != null) {
            LabSendDialog(
                o.labName, null, false, h.fee, "", "", "", onDismiss = { homeEdit = null }, change = h,
            ) { req ->
                if (req != null) RxNet.homeChange(o.id, req)
                homeEdit = null
                infoMsg = tr("The lab was told the new time", "প্যাথলজিকে নতুন সময় জানানো হয়েছে")
                reload++
            }
        }
    }
    homeCancel?.let { o ->
        HomeCancelDialog(o, onDismiss = { homeCancel = null }) {
            homeCancel = null
            infoMsg = tr("The lab was told — go there to give the sample", "প্যাথলজিকে জানানো হয়েছে — সেখানে গিয়ে নমুনা দিন")
            reload++
        }
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

/**
 * ফার্মেসি বাছাই। mode "shop" — দোকানে দাঁড়িয়ে: এখনকার লোকেশনে সবচেয়ে কাছের দোকান ওপরে, অথবা দোকানের কোড;
 * "online" — শুধু যাঁরা অনলাইনে বিক্রি করেন (ডেলিভারির দূরত্বে), যেখানে বেশি ওষুধ আছে আগে; ঠিকানা দিয়ে অর্ডার।
 */
@Composable
private fun PharmacyPickDialog(rx: Rx, profile: Profile, mode: String, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var district by remember { mutableStateOf(profile.placeName) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var located by remember { mutableStateOf(mode != "shop" || !LocationHelper.hasPermission(context)) }
    var list by remember { mutableStateOf<List<PharmOption>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var shopCode by remember { mutableStateOf("") }
    var nameQuery by remember { mutableStateOf("") }
    var ordering by remember { mutableStateOf<PharmOption?>(null) }
    // দোকানে দাঁড়িয়ে: এখনকার লোকেশন (অনুমতি থাকলে) — বাড়ির লোকেশন নয়
    LaunchedEffect(Unit) {
        if (!located) {
            runCatching { LocationHelper.current(context) }.getOrNull()?.let { (la, ln) ->
                here = la to ln
                runCatching { Districts.at(context, la, ln).bn }.getOrNull()?.let { district = it }
            }
            located = true
        }
    }
    LaunchedEffect(district, located, nameQuery) {
        val d = district ?: return@LaunchedEffect
        if (!located) return@LaunchedEffect
        // নাম লিখতে থাকলে একটু থেমে খোঁজা
        if (nameQuery.isNotBlank()) delay(400)
        list = null
        val gps = profile.locationSource == "gps"
        val (la, ln) = here ?: if (gps && profile.lat != null && profile.lng != null) profile.lat to profile.lng else (null to null)
        runCatching { RxNet.pharmacyOptions(rx.id, d, la, ln, mode, nameQuery.trim()) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message; list = emptyList() }
    }
    // কোড (লেখা বা QR থেকে) → দোকান → পাঠানো
    fun sendByCode(code: String) {
        scope.launch {
            busy = "code"
            errorMsg = null
            runCatching { RxNet.shopFind(code) }.onSuccess { sh ->
                runCatching { RxNet.sendPharmacy(rx.id, sh.id, "shop") }.onSuccess { onSent(sh.name) }.onFailure { errorMsg = it.message }
            }.onFailure { errorMsg = it.message }
            busy = null
        }
    }
    fun scan() {
        errorMsg = null
        ShopQr.scan(context, onCode = { sendByCode(it) }, onError = { errorMsg = it })
    }
    fun send(id: String, name: String) {
        if (busy == null) scope.launch {
            busy = id
            errorMsg = null
            runCatching { RxNet.sendPharmacy(rx.id, id, "shop") }.onSuccess { onSent(name) }.onFailure { errorMsg = it.message }
            busy = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(if (mode == "online") tr("Order online", "অনলাইনে অর্ডার") else tr("Send to this shop", "এই দোকানে দিন"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (mode == "shop") {
                        // ১) কাউন্টারের QR স্ক্যান
                        Button(onClick = { scan() }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                            ButtonContent(busy == "code", tr("Scan the shop's QR", "দোকানের QR স্ক্যান করুন"), R.drawable.ic_photo_camera)
                        }
                        Spacer(Modifier.height(8.dp))
                        // ২) দোকানের নাম লিখে খোঁজা
                        OutlinedTextField(
                            nameQuery, { nameQuery = it.take(60) },
                            label = { Text(tr("Or type the shop's name", "অথবা দোকানের নাম লিখুন")) },
                            singleLine = true,
                            leadingIcon = { AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // ৩) কোড (QR কাজ না করলে)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(shopCode, { shopCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, label = { Text(tr("Or the shop code", "অথবা দোকানের কোড")) }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { sendByCode(shopCode) }, enabled = busy == null && shopCode.length == 6, shape = RoundedCornerShape(12.dp)) { Text(tr("Send", "পাঠান")) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (nameQuery.isBlank()) tr("Shops near you", "কাছের দোকান") else tr("Shops with this name", "এই নামের দোকান"), style = MaterialTheme.typography.labelLarge)
                    } else {
                        SmallNote(
                            tr(
                                "Only pharmacies that deliver. You pay the shop when the medicines arrive — Lifolo doesn't take money.",
                                "শুধু যাঁরা বাসায় পৌঁছে দেন। ওষুধ পৌঁছালে দোকানকে দাম দেবেন — Lifolo টাকা নেয় না।",
                            ),
                        )
                    }
                    FilterBar(areaLabel = areaText(district, null), onAreaClick = { showArea = true })
                    ErrorText(errorMsg)
                }
                val l = list
                when {
                    l == null -> item { LoadingBox() }
                    l.isEmpty() -> item {
                        EmptyState(
                            if (mode == "online") R.drawable.ic_local_shipping else R.drawable.ic_storefront,
                            if (mode == "online") tr("No pharmacy here sells online yet", "এখানে এখনো কোনো ফার্মেসি অনলাইনে বিক্রি করে না")
                            else if (nameQuery.isNotBlank()) tr("No shop with this name in the app", "এই নামে অ্যাপে কোনো দোকান নেই")
                            else tr("No pharmacy in the app here yet", "এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই"),
                            if (mode == "online") tr("Go to a pharmacy and show the QR.", "ফার্মেসিতে গিয়ে QR দেখিয়ে কিনুন।") else tr("Show the QR at the counter instead.", "কাউন্টারে QR দেখিয়েও কিনতে পারেন।"),
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }
                    else -> items(l, key = { it.id }) { p ->
                        DirectoryCard(
                            title = p.name,
                            icon = if (mode == "online") R.drawable.ic_local_shipping else R.drawable.ic_storefront,
                            status = if (p.openNow) CardStatus(tr("Open", "খোলা"), true) else CardStatus(tr("Closed", "বন্ধ"), false),
                            meta = p.km?.let { Rules.formatKm(it) },
                            subtitle = if (p.keepsStock) tr("Has ${p.have} of ${p.total} medicines", "${num(p.total)}টার ${num(p.have)}টা ওষুধ আছে") else tr("Stock not listed", "স্টকের তালিকা নেই"),
                            details = listOf(
                                R.drawable.ic_location_on to p.address,
                                R.drawable.ic_local_shipping to if (mode == "online" && p.deliveryFee.isNotBlank()) tr("Delivery: ", "ডেলিভারি: ") + p.deliveryFee else "",
                                R.drawable.ic_info to if (mode == "online") p.onlineNote else "",
                                R.drawable.ic_info to if (p.keepsStock && p.missing.isNotEmpty()) tr("Missing: ", "নেই: ") + p.missing.joinToString(", ") else "",
                            ),
                            primary = CardAction(
                                if (busy == p.id) "…" else if (mode == "online") tr("Order", "অর্ডার করুন") else tr("Send here", "এই দোকানে দিন"),
                                if (mode == "online") R.drawable.ic_local_shipping else R.drawable.ic_send,
                            ) { if (mode == "online") ordering = p else send(p.id, p.name) },
                            secondary = CardAction(tr("Call", "ফোন"), R.drawable.ic_call) { callPhone(context, p.phone) },
                        )
                    }
                }
            }
        }
    }
    if (showArea) AreaSheet(district, null, null, { d, _, _ -> district = d }, onDismiss = { showArea = false })
    ordering?.let { p -> OnlineOrderDialog(rx, p, profile, onDismiss = { ordering = null }) { ordering = null; onSent(p.name) } }
}

/** অনলাইন অর্ডার: কোথায় পৌঁছাবে আর ফোন (দাম পৌঁছালে, দোকানকে সরাসরি) */
@Composable
private fun OnlineOrderDialog(rx: Rx, p: PharmOption, profile: Profile, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(profile.phone) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = p.name,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.deliveryFee.isNotBlank()) IconLine(R.drawable.ic_local_shipping, tr("Delivery: ", "ডেলিভারি: ") + p.deliveryFee)
                if (p.keepsStock && p.missing.isNotEmpty()) SmallNote(tr("May not have: ", "না-ও থাকতে পারে: ") + p.missing.joinToString(", "))
                OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Delivery address", "কোথায় পৌঁছাবে (ঠিকানা)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    phone, { phone = it.take(20) },
                    label = { Text(tr("Mobile number", "মোবাইল নম্বর")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                SmallNote(tr("Pay the shop when it arrives (cash on delivery). The shop sees your address and number only for this order.", "ওষুধ পৌঁছালে দোকানকে দাম দেবেন (ক্যাশ অন ডেলিভারি)। ঠিকানা আর নম্বর দোকান শুধু এই অর্ডারের জন্য দেখবে।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.sendPharmacy(rx.id, p.id, "online", address.trim(), phone.trim(), note.trim()) }.onSuccess { onSent() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && address.isNotBlank() && phone.isNotBlank()) { ButtonContent(busy, tr("Place order", "অর্ডার দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** রোগী: দোকান থেকে প্রেসক্রিপশন ফিরিয়ে নেওয়া — ওষুধ না থাকলে দোকানের "দিতে পারেননি" তালিকায় যায় */
@Composable
private fun WithdrawDialog(o: RxOrder, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var notAvailable by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Take back from ${o.pharmacyName}?", "${o.pharmacyName} থেকে ফিরিয়ে নেবেন?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Why?", "কেন?"), style = MaterialTheme.typography.labelLarge)
                ChipRows(listOf("na", "other"), setOf(if (notAvailable) "na" else "other"), perRow = 1, label = {
                    if (it == "na") tr("They don't have the medicines", "ওদের কাছে ওষুধ নেই") else tr("Another reason", "অন্য কারণ")
                }) { notAvailable = it == "na" }
                SmallNote(tr("Medicines not bought yet stay on the prescription — send it to another shop.", "যে ওষুধ কেনা বাকি, তা প্রেসক্রিপশনে থাকবে — অন্য দোকানে পাঠান।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.withdraw(o.id, notAvailable) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy) { ButtonContent(busy, tr("Take back", "ফিরিয়ে নিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/**
 * প্যাথলজি বাছাই: কোন পরীক্ষা পাঠাবেন; প্রতিটা প্যাথলজিতে কয়টা হয় ("৩টার ৩টা"), আনুমানিক কত, কোনটা হয় না, রিপোর্ট কতক্ষণে —
 * বেশি পরীক্ষা → কম দাম → কাছের আগে (সার্ভার সাজায়)। দামের তালিকা না থাকলে "দাম জানা নেই — ফোনে জেনে নিন"।
 * বাসা থেকে নমুনা নিলে পাঠানোর আগে বেছে নেওয়া যায় (LabSendDialog)।
 * only: প্যাথলজির পাতা থেকে এলে শুধু এই প্যাথলজি (তার জেলায় খোঁজা, এলাকা বদলানো নেই) — পরীক্ষা বাছাই আর বাসার নমুনা আগের মতোই।
 */
@Composable
private fun LabPickDialog(rx: Rx, profile: Profile, onDismiss: () -> Unit, only: Lab? = null, onSent: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var district by remember { mutableStateOf(only?.district?.ifBlank { null } ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var opts by remember { mutableStateOf<LabOptions?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf<LabOption?>(null) }
    val chosen = remember { mutableStateListOf(*rx.tests.toTypedArray()) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(district, upazilaId, chosen.toList()) {
        val d = district ?: return@LaunchedEffect
        if (chosen.isEmpty()) { opts = LabOptions(emptyList(), "", ""); return@LaunchedEffect }
        // চিপ টিপতে টিপতে প্রতিবার নয় — থামার ৪৫০ মিলিসেকেন্ড পর (নতুন চাপলে এই ডাক বাতিল হয়ে আবার শুরু; সার্ভারে দিনের সীমাও আছে)
        if (!first) delay(450)
        first = false
        val tests = chosen.toList()
        runCatching { RxNet.labOptions(rx.id, d, upazilaId, tests) }.onSuccess { opts = it; errorMsg = null }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            errorMsg = it.message
            opts = LabOptions(emptyList(), "", "")
        }
    }
    fun send(o: LabOption) {
        if (busy != null || chosen.isEmpty()) return
        if (o.lab.homeCollection) { sending = o; return }
        scope.launch {
            busy = o.lab.id
            errorMsg = null
            runCatching { RxNet.sendLab(rx.id, o.lab.id, chosen.toList()) }.onSuccess { onSent(o.lab.name) }.onFailure { errorMsg = it.message }
            busy = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(only?.name ?: tr("Choose a lab", "প্যাথলজি বাছুন"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(tr("Tests to send", "কোন পরীক্ষা পাঠাবেন"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(rx.tests, chosen.toSet(), perRow = 3) { t -> if (t in chosen) chosen.remove(t) else chosen.add(t) }
                    Spacer(Modifier.height(8.dp))
                    if (only == null) FilterBar(areaLabel = areaText(district, upazilaId), onAreaClick = { showArea = true })
                    ErrorText(errorMsg)
                }
                // শুধু এই প্যাথলজি; তালিকায় না এলে (দূরের সারিতে) দামের হিসাব ছাড়াই — পাঠানো তবু যায়, সার্ভার মিলিয়ে নেয়
                val l = opts?.items?.let { all ->
                    if (only == null) all
                    else all.filter { it.lab.id == only.id }.ifEmpty {
                        listOf(LabOption(only, false, false, null, chosen.size, emptyList(), null, null, null, if (only.homeCollection) only.prices?.homeFee else null, ""))
                    }
                }
                when {
                    l == null -> item { LoadingBox() }
                    chosen.isEmpty() -> item { EmptyState(R.drawable.ic_list_alt, tr("Choose the tests", "পরীক্ষা বেছে নিন"), null, Modifier.fillMaxWidth().padding(top = 24.dp)) }
                    l.isEmpty() -> item {
                        EmptyState(R.drawable.ic_local_hospital, tr("No lab in the app here yet", "এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই"),
                            tr("You can still take the prescription to any lab.", "যেকোনো প্যাথলজিতে প্রেসক্রিপশন নিয়ে গিয়েও করাতে পারেন।"), Modifier.fillMaxWidth().padding(top = 24.dp))
                    }
                    else -> items(l, key = { it.lab.id }) { o ->
                        val lab = o.lab
                        DirectoryCard(
                            title = lab.name,
                            icon = R.drawable.ic_local_hospital,
                            status = if (o.suggested) CardStatus(tr("Doctor suggested", "ডাক্তারের সুপারিশ"), true) else null,
                            // একটাও না হলে দাম নয় (৳০ নয়)
                            subtitle = if (o.priced) LabPriceText.coverEstimate(o.have ?: 0, o.total, o.estimate) else LabPriceText.noPrice(),
                            meta = if (o.priced) LabPriceText.discount(o.discountPct).ifBlank { null } else null,
                            details = listOf(
                                R.drawable.ic_warning to if (o.priced && o.missing.isNotEmpty()) tr("Not here: ", "এখানে হয় না: ") + o.missing.joinToString(", ") else "",
                                R.drawable.ic_schedule to if (o.tat.isNotBlank()) tr("Report: ", "রিপোর্ট: ") + o.tat else "",
                                R.drawable.ic_home_health to if (lab.homeCollection) listOf(
                                    tr("Sample from home", "বাসা থেকে নমুনা নেয়"),
                                    o.homeFee?.let { if (it == 0) tr("free", "ফ্রি") else LabPriceText.taka(it) }.orEmpty(),
                                ).filter { it.isNotBlank() }.joinToString(" · ") else "",
                                R.drawable.ic_location_on to lab.address,
                            ),
                            primary = CardAction(if (busy == lab.id) "…" else tr("Send tests", "পরীক্ষা পাঠান"), R.drawable.ic_send) { send(o) },
                            secondary = CardAction(tr("Call", "ফোন"), R.drawable.ic_call) { callPhone(context, lab.phone) },
                        )
                    }
                }
            }
        }
    }
    // উপজেলাও (ওয়েবের মতো) — একই উপজেলার প্যাথলজি আগে
    if (showArea) AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
    sending?.let { o ->
        // কেয়ারগিভার হলে নিজের নম্বর নয় — আগের বারের (রোগীর) নম্বর, না থাকলে খালি
        LabSendDialog(
            o.lab.name, if (o.priced) LabPriceText.coverEstimate(o.have ?: 0, o.total, o.estimate) else LabPriceText.noPrice(), o.priced, o.homeFee,
            opts?.lastAddress.orEmpty(), opts?.lastPhone.orEmpty(), if (CareMode.asUid.value != null) "" else profile.phone, onDismiss = { sending = null },
        ) { home ->
            RxNet.sendLab(rx.id, o.lab.id, chosen.toList(), home)
            sending = null
            onSent(o.lab.name)
        }
    }
}

/** রিপোর্ট: ফলের তালিকা (বেশি/কম চিহ্নসহ), নোট, ছবি */
@Composable
fun LabReportDialog(orderId: String, self: Boolean = false, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf<LabOrder?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orderId) { runCatching { RxNet.labOrder(orderId, self) }.onSuccess { order = it }.onFailure { errorMsg = it.message } }
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
    // পরীক্ষা ধরে ভাগ (CBC, লিপিড…); একই রোগীর আগের ফল পাশে
    val groups = mutableListOf<Pair<String, MutableList<LabResult>>>()
    r.items.forEach { it -> if (groups.lastOrNull()?.first != it.group) groups.add(it.group to mutableListOf(it)) else groups.last().second.add(it) }
    groups.forEach { (name, rows) ->
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.primary)
                rows.forEach { it ->
                    if (it.long) {
                        Text(it.test, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(it.result, style = MaterialTheme.typography.bodyMedium)
                        return@forEach
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.test, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (it.range.isNotBlank()) Text(tr("Normal: ", "স্বাভাবিক: ") + it.range, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            val prev = if (it.code.isNotBlank()) o.history["${it.code}.${it.key}"].orEmpty() else emptyList()
                            if (prev.isNotEmpty()) {
                                Text(
                                    tr("Before: ", "আগে: ") + prev.take(3).joinToString(", ") { h -> num(h.result) + (if (h.flag == "high") "↑" else if (h.flag == "low") "↓" else "") + " (" + Rules.formatDate(h.at) + ")" },
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                )
                            }
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

// ------------------------------------------------------------
// ডাক্তার: রোগীর ইতিহাসে / প্রেসক্রিপশনে পার্টনার ল্যাবের পরীক্ষা (১৮ অক্টোবর ২০২৬, "ডাক্তারকে রিপোর্ট দেখানো")
// অবস্থা সবসময়; ফল শুধু রিপোর্ট দেখার টাকা মিললে বা "ফি চাই না" হলে — পুরো রিপোর্ট (আগের ফলসহ) LabReportDialog-এ।
// ওয়েবের web/src/sections/rx/doctor/Labs.tsx
// ------------------------------------------------------------

/** "পরীক্ষার রিপোর্ট" কার্ড; কিছু না থাকলে emptyNote (না দিলে কার্ডই নয়) */
@Composable
fun DoctorLabsCard(labs: List<DoctorLab>, emptyNote: String? = null) {
    if (labs.isEmpty() && emptyNote == null) return
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Test reports", "পরীক্ষার রিপোর্ট"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            if (labs.isEmpty()) SmallNote(emptyNote.orEmpty()) else DoctorLabList(labs)
        }
    }
}

@Composable
fun DoctorLabList(labs: List<DoctorLab>) {
    val cs = MaterialTheme.colorScheme
    var reading by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labs.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (tagBg, tagFg) = when (o.status) {
                    "reported" -> cs.tertiaryContainer to cs.onTertiaryContainer
                    "cancelled" -> cs.errorContainer to cs.onErrorContainer
                    else -> cs.surfaceVariant to cs.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.labName.ifBlank { tr("Lab", "প্যাথলজি") }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Tag(RxNetText.labStatus(o.status), tagBg, tagFg)
                }
                SmallNote(listOf(o.tests.joinToString(", "), Rules.formatDate(if (o.reportedAt > 0) o.reportedAt else o.createdAt)).filter { it.isNotBlank() }.joinToString(" · "))
                val r = o.report
                if (o.status == "reported" && o.open && r != null) {
                    // বেশি/কম ফলগুলো এক লাইনে — পুরোটা "রিপোর্ট দেখুন"-এ
                    val flagged = r.items.filter { it.flag.isNotBlank() && !it.long }
                    if (flagged.isNotEmpty()) {
                        Text(
                            tr("High/low: ", "বেশি/কম: ") + flagged.take(4).joinToString(", ") { f ->
                                f.test + " " + f.result + (if (f.unit.isNotBlank()) " " + f.unit else "") + " (" + RxNetText.flag(f.flag) + ")"
                            } + (if (flagged.size > 4) " +" + num(flagged.size - 4) else ""),
                            style = MaterialTheme.typography.bodySmall, color = cs.error,
                        )
                    } else if (r.items.isNotEmpty()) {
                        SmallNote(tr("${r.items.size} result(s)", "${num(r.items.size)}টা ফল"))
                    }
                    if (o.review == "paid") SmallNote(tr("Not replied yet — reply from Reports", "রোগীকে এখনো জানাননি — \"রিপোর্ট\" থেকে জানান"))
                    if (o.comment.isNotBlank()) Text(tr("You told the patient: ", "রোগীকে জানিয়েছিলেন: ") + o.comment, style = MaterialTheme.typography.bodySmall)
                    SmallAction(tr("See report", "রিপোর্ট দেখুন"), R.drawable.ic_list_alt, { reading = o.id }, primary = true)
                } else if (o.status == "reported") {
                    Row(verticalAlignment = Alignment.Top) {
                        AppIcon(R.drawable.ic_privacy_tip, null, Modifier.padding(top = 2.dp).size(16.dp), cs.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        SmallNote(RxNetText.labLocked(o.review))
                    }
                }
            }
        }
    }
    reading?.let { id -> LabReportDialog(id, self = true) { reading = null } }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<PharmRx?>(null) }
    var orders by remember { mutableStateOf<List<PharmOrder>>(emptyList()) }
    var home by remember { mutableStateOf<PharmacyHome?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var stockOpen by remember { mutableStateOf(false) }
    var onlineOpen by remember { mutableStateOf(false) }
    var missedOpen by remember { mutableStateOf(false) }
    var accepting by remember { mutableStateOf<PharmOrder?>(null) }
    LaunchedEffect(reload) {
        runCatching { RxNet.pharmacyOrders() }.onSuccess { orders = it }
        runCatching { RxNet.pharmacyHome() }.onSuccess { home = it }
    }
    fun open(c: String) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { RxNet.pharmacyOpen(c) }.onSuccess { opened = it }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    fun update(o: PharmOrder, status: String, reason: String = "", price: String = "", eta: String = "") {
        scope.launch {
            errorMsg = null
            runCatching { RxNet.orderUpdate(o.id, status, reason, price, eta) }.onSuccess { infoMsg = tr("The patient was told", "রোগীকে জানানো হয়েছে") }.onFailure { errorMsg = it.message }
            reload++
        }
    }
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Prescriptions", "প্রেসক্রিপশন"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            home?.let { h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val qr = remember(h.shopCode) { Qr.bitmap(ShopQr.url(h.shopCode), 360)?.asImageBitmap() }
                    // QR-এর নিচেই কোড (প্রেসক্রিপশনের QR-এর মতো) — QR না থাকলে পাশে বড় করে
                    if (qr != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(qr, tr("Shop QR", "দোকানের QR"), Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                            QrCodeText(h.shopCode)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Shop QR & code", "দোকানের QR ও কোড"), style = MaterialTheme.typography.labelLarge)
                        if (qr == null) Text(h.shopCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                    }
                    Tag(
                        if (h.online.enabled) tr("Selling online", "অনলাইনে বিক্রি চালু") else tr("In-shop only", "শুধু দোকানে"),
                        if (h.online.enabled) cs.tertiaryContainer else cs.surfaceVariant,
                        if (h.online.enabled) cs.onTertiaryContainer else cs.onSurfaceVariant,
                    )
                }
                SmallNote(tr("Put the QR at the counter (print the poster from the web panel or open the link). Customers scan it, type your shop's name or this code to send you their prescription.", "QR কাউন্টারে লাগিয়ে রাখুন (ওয়েব প্যানেল বা নিচের লিংক থেকে পোস্টার ছাপুন)। ক্রেতা QR স্ক্যান করলে, দোকানের নাম বা এই কোড লিখলে প্রেসক্রিপশন আপনার কাছে চলে আসবে।"))
                SmallAction(tr("Open poster to print", "ছাপার পোস্টার খুলুন"), R.drawable.ic_share, { openUrl(context, ShopQr.url(h.shopCode)) })
            }
            SmallNote(tr("Or enter the code under the QR. Note how many you give — the rest can be given by another pharmacy.", "অথবা QR-এর নিচের কোড দিন। কয়টা দিলেন লিখে রাখুন — বাকিটা অন্য ফার্মেসি দিতে পারবে।"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text(tr("Prescription code", "প্রেসক্রিপশনের কোড")) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { open(code) }, enabled = !busy && code.length == 10, shape = RoundedCornerShape(12.dp)) { ButtonContent(busy, tr("Open", "খুলুন")) }
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
            val pending = orders.filter { it.status in listOf("sent", "ready", "accepted", "out") }
            if (pending.isNotEmpty()) {
                Text(tr("Sent to you", "আপনার কাছে পাঠানো"), style = MaterialTheme.typography.labelLarge)
                pending.forEach { o ->
                    val online = o.mode == "online"
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.patientName.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Tag(if (online) tr("Online order", "অনলাইন অর্ডার") else tr("In the shop", "দোকানে এসেছেন"), if (online) cs.primaryContainer else cs.secondaryContainer, if (online) cs.onPrimaryContainer else cs.onSecondaryContainer)
                            }
                            Text(RxNetText.orderStatus(o.status) + " · " + timeAgo(o.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            if (online) {
                                IconLine(R.drawable.ic_location_on, o.address)
                                IconLine(R.drawable.ic_call, num(o.phone))
                            }
                            if (o.note.isNotBlank()) SmallNote(o.note)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallAction(if (online) tr("Open & note medicines", "খুলে ওষুধ লিখুন") else tr("Open", "খুলুন"), null, { code = o.code; open(o.code) }, primary = true)
                                if (!online && o.status == "sent") SmallAction(tr("Ready", "তৈরি"), null, { update(o, "ready") })
                                if (online && o.status == "sent") SmallAction(tr("Accept", "অর্ডার নিলাম"), null, { accepting = o })
                                if (online && o.status in setOf("sent", "accepted")) SmallAction(tr("Sent out", "পাঠিয়ে দিয়েছি"), null, { update(o, "out") })
                                if (online) SmallAction(tr("Call", "ফোন"), R.drawable.ic_call, { callPhone(context, o.phone) })
                                SmallAction(tr("Can't", "পারব না"), null, { update(o, "cancelled", tr("Medicines not available", "ওষুধ নেই")) })
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onlineOpen = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Selling online", "অনলাইনে বিক্রি"), R.drawable.ic_local_shipping) }
            OutlinedButton(onClick = { missedOpen = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Asked for but not available", "চেয়েও পাননি এমন ওষুধ") + (home?.missed?.size?.takeIf { it > 0 }?.let { " (${num(it)})" } ?: ""), R.drawable.ic_list_alt)
            }
            OutlinedButton(onClick = { stockOpen = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("My stock list", "আমার স্টকের তালিকা"), R.drawable.ic_list_alt) }
            SmallNote(tr("Keep a stock list so patients can see which medicines you have.", "স্টকের তালিকা রাখলে রোগীরা দেখতে পাবেন আপনার কাছে কোন ওষুধ আছে।"))
            PharmacyTodayCard() // আজকের হিসাব (পুরো হিসাব ওয়েবে) — PharmacyBooks.kt
        }
    }
    opened?.let { rx -> DispenseDialog(rx, onDismiss = { opened = null; reload++ }) }
    if (stockOpen) StockDialog { stockOpen = false }
    home?.let { h ->
        if (onlineOpen) OnlineSettingsDialog(h, onDismiss = { onlineOpen = false }) { onlineOpen = false; infoMsg = tr("Saved", "সেভ হয়েছে"); reload++ }
        if (missedOpen) MissedDialog(h.missed, onDismiss = { missedOpen = false; reload++ })
    }
    accepting?.let { o ->
        AcceptOrderDialog(o, onDismiss = { accepting = null }) { price, eta ->
            accepting = null
            update(o, "accepted", price = price, eta = eta)
        }
    }
}

@Composable
private fun AcceptOrderDialog(o: PharmOrder, onDismiss: () -> Unit, onAccept: (String, String) -> Unit) {
    var price by remember { mutableStateOf("") }
    var eta by remember { mutableStateOf("") }
    FormDialog(
        title = tr("Accept order", "অর্ডার নিন"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(o.patientName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(price, { price = it.take(40) }, label = { Text(tr("Total price (optional)", "মোট দাম (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(eta, { eta = it.take(60) }, label = { Text(tr("When will it arrive (optional)", "কখন পৌঁছাবে (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SmallNote(tr("The patient pays you on delivery.", "রোগী ওষুধ পেয়ে আপনাকে দাম দেবেন।"))
            }
        },
        confirmButton = { TextButton(onClick = { onAccept(price.trim(), eta.trim()) }) { Text(tr("Accept", "নিলাম")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ফার্মেসি: অনলাইনে বিক্রি চালু/বন্ধ, ডেলিভারির দূরত্ব, চার্জ */
@Composable
private fun OnlineSettingsDialog(h: PharmacyHome, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(h.online.enabled) }
    var radius by remember { mutableStateOf(h.online.radiusKm.toString()) }
    var fee by remember { mutableStateOf(h.online.fee) }
    var note by remember { mutableStateOf(h.online.note) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Selling online", "অনলাইনে বিক্রি"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Take prescription orders and deliver", "প্রেসক্রিপশনের অর্ডার নিই, বাসায় পৌঁছে দিই"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    radius, { radius = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(tr("Up to how many km", "কত কিমি পর্যন্ত")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(fee, { fee = it.take(40) }, label = { Text(tr("Delivery charge (e.g. Tk 30)", "ডেলিভারি চার্জ (যেমন: ৩০ টাকা)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(120) }, label = { Text(tr("Note (e.g. delivery 10 AM – 9 PM)", "নোট (যেমন: সকাল ১০টা–রাত ৯টা)")) }, modifier = Modifier.fillMaxWidth())
                SmallNote(tr("Orders come only from verified prescriptions. Customers pay you directly — Lifolo takes nothing.", "অর্ডার আসবে শুধু যাচাই করা প্রেসক্রিপশন থেকে। ক্রেতা টাকা সরাসরি আপনাকে দেবেন — Lifolo কিছু নেয় না।"))
                if (!h.hasLocation) Text(tr("Add your shop's location first (edit your pharmacy).", "আগে দোকানের লোকেশন দিন (ফার্মেসির তথ্য বদলে)।"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.onlineSet(OnlineSettings(enabled, radius.toIntOrNull()?.coerceIn(1, 30) ?: 3, fee.trim(), note.trim())) }
                        .onSuccess { onSaved() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy) { ButtonContent(busy, tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ক্রেতা চেয়েও পাননি এমন ওষুধ (কতবার) — স্টকে আনলে "এনেছি" */
@Composable
private fun MissedDialog(start: List<Missed>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf(*start.toTypedArray()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Asked for but not available", "চেয়েও পাননি এমন ওষুধ"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SmallNote(tr("Medicines customers came for but you couldn't give. Keep them in stock to sell more.", "ক্রেতা প্রেসক্রিপশন নিয়ে এসে যে ওষুধ পাননি। স্টকে রাখলে বিক্রি বাড়বে।"))
                    ErrorText(errorMsg)
                }
                if (list.isEmpty()) item { EmptyState(R.drawable.ic_check_circle, tr("Nothing yet", "এখনো কিছু নেই"), null, Modifier.fillMaxWidth().padding(top = 24.dp)) }
                items(list, key = { it.k }) { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(tr("${m.count} times · last ", "${num(m.count)} বার · শেষ ") + Rules.formatDate(m.lastAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SmallAction(tr("Got it in stock", "এনেছি"), R.drawable.ic_check_circle, {
                                scope.launch {
                                    runCatching { RxNet.missedClear(m.k); RxNet.stockSet(listOf(m.name), emptyList()) }
                                        .onSuccess { list.remove(m) }.onFailure { errorMsg = it.message }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

/** ফার্মেসি: প্রতিটা ওষুধে কয়টা দিলেন; না থাকলে "নেই" (রোগী জানেন, চাহিদার তালিকায় জমা) */
@Composable
private fun DispenseDialog(start: PharmRx, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rx by remember { mutableStateOf(start) }
    val qty = remember { mutableStateMapOf<Int, String>() }
    val none = remember { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var saleAt by remember { mutableStateOf<Long?>(null) } // বিক্রির হিসাবে তোলার প্রস্তাব (PharmacyBooks.kt)
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
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(listOf(RxText.formShort(m.form), m.name).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(num(m.dose) + " · " + (if (m.days > 0) tr("${m.days} days", "${num(m.days)} দিন") else tr("Continue", "চলবে")), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    Text(m.boughtText + (m.left?.let { tr(" · $it left", " · বাকি ${num(it)}") } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (m.done) cs.primary else cs.onSurfaceVariant)
                                }
                                if (!m.done && rx.status != "cancelled" && m.i !in none) {
                                    OutlinedTextField(
                                        qty[m.i].orEmpty(), { qty[m.i] = it.filter { c -> c.isDigit() }.take(4) },
                                        label = { Text(tr("Qty", "কয়টা")) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(96.dp),
                                    )
                                }
                            }
                            if (!m.done && rx.status != "cancelled") {
                                androidx.compose.material3.FilterChip(
                                    selected = m.i in none,
                                    onClick = { if (m.i in none) none.remove(m.i) else { none.add(m.i); qty.remove(m.i) } },
                                    label = { Text(tr("Don't have it", "নেই")) },
                                )
                            }
                        }
                    }
                }
                if (none.isNotEmpty()) SmallNote(tr("The patient will be told, and it goes on your \"asked for but not available\" list.", "রোগী জানবেন, আর ওষুধটা আপনার \"চেয়েও পাননি\" তালিকায় জমা হবে।"))
                if (rx.dispensed.isNotEmpty()) {
                    Text(tr("Given before", "আগে দেওয়া"), style = MaterialTheme.typography.labelLarge)
                    rx.dispensed.forEach { d -> SmallNote(d.name + " · " + Rules.formatDate(d.at) + " · " + d.items.joinToString(", ") { (i, q) -> (rx.meds.getOrNull(i)?.name ?: "") + " ×" + num(q) }) }
                }
                saleAt?.let { a -> androidx.compose.runtime.key(a) { RxSaleOffer(rx.code, a) { saleAt = null } } }
                if (rx.status != "cancelled" && rx.meds.any { !it.done }) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                errorMsg = null
                                runCatching { RxNet.dispense(rx.code, qty.mapValues { it.value.toIntOrNull() ?: 0 }, none.toSet()) }
                                    .onSuccess {
                                        // নতুন ভাগ লেখা হলে (শুধু "নেই" দিলে নয়) বিক্রির হিসাবে তোলার প্রস্তাব
                                        val last = it.dispensed.lastOrNull()?.at
                                        if (last != null && last != rx.dispensed.lastOrNull()?.at) saleAt = last
                                        rx = it; qty.clear(); none.clear(); infoMsg = tr("Saved", "লেখা হয়েছে")
                                    }
                                    .onFailure { errorMsg = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy && (qty.values.any { (it.toIntOrNull() ?: 0) > 0 } || none.isNotEmpty()),
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
// প্যাথলজি/ফার্মেসির পাতা থেকেই প্রেসক্রিপশন পাঠানো (আগে শুধু প্রেসক্রিপশন খুলে পাঠানো যেত)
// - আগে কোন প্রেসক্রিপশন: রোগীর চালু (বাতিল নয়) — প্যাথলজিতে পরীক্ষা লেখা, ফার্মেসিতে ওষুধ লেখা
//   (সব ওষুধ কেনা হয়ে গেলে সার্ভারের ৪০৯ "সব ওষুধ কেনা হয়ে গেছে" দেখায় — প্রতিটার rx_activity আনা হয় না)
// - তারপর আগের পথই: প্যাথলজি → LabPickDialog (শুধু এই প্যাথলজি); ফার্মেসি → দোকানে গিয়ে নেব / অনলাইনে অর্ডার (OnlineOrderDialog)
// - কেয়ারগিভার মোডেও চলে (rx_mine, rx_send_* — CareMode-এর তালিকায় আছে)
// ------------------------------------------------------------

/** পাঠানোর পর শিটে যা লেখা থাকে */
fun rxSentNote() = tr("Sent — see the status on the prescription", "পাঠানো হয়েছে — প্রেসক্রিপশনে অবস্থা দেখুন")

/**
 * শিটের ওপর থেকে লগইন লাগলে: শিট বন্ধ → লগইন (নাম-মোবাইল) → then (শিট আবার খোলা আর পাঠানো শুরু)।
 * শিট খোলা রেখে লগইন চাইলে লগইনের পাতা শিটের পেছনে পড়ে যেত।
 */
fun rxSendGate(close: () -> Unit, then: () -> Unit) {
    if (!LoginGate.isGuest && LoginGate.profileReady.value) { then(); return }
    close()
    LoginGate.require(tr("Log in to send a prescription", "প্রেসক্রিপশন পাঠাতে লগইন করুন"), then = then)
}

/** lab বা pharmacy — যেটা দেওয়া। প্রেসক্রিপশন বাছাই → (পেছনে গেলে আবার বাছাই) → পাঠানো → onSent */
@Composable
fun RxSendFlow(lab: Lab?, pharmacy: Pharmacy?, profile: Profile, onDismiss: () -> Unit, onSent: () -> Unit) {
    var rx by remember { mutableStateOf<Rx?>(null) }
    val r = rx
    when {
        r == null -> RxPickDialog(forLab = lab != null, onDismiss = onDismiss) { rx = it }
        lab != null -> LabPickDialog(r, profile, onDismiss = { rx = null }, only = lab) { onSent() }
        pharmacy != null -> PharmacySendDialog(r, pharmacy, profile, onDismiss = { rx = null }, onSent = onSent)
    }
}

/** রোগীর চালু প্রেসক্রিপশন — প্রতিটায় ডাক্তার, তারিখ, আর পরীক্ষা (forLab) বা ওষুধের নাম */
@Composable
private fun RxPickDialog(forLab: Boolean, onDismiss: () -> Unit, onPick: (Rx) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var list by remember { mutableStateOf<List<Rx>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { RxApi.mine() }.onSuccess { all ->
            list = all.filter { !it.cancelled && if (forLab) it.tests.isNotEmpty() else it.medicines.isNotEmpty() }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            errorMsg = it.message
            list = emptyList()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { BackTopBar(tr("Which prescription?", "কোন প্রেসক্রিপশন পাঠাবেন?"), onDismiss) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ErrorText(errorMsg) }
                val l = list
                when {
                    l == null -> item { LoadingBox() }
                    l.isEmpty() -> item {
                        EmptyState(
                            R.drawable.ic_list_alt,
                            if (forLab) tr("No prescription with tests", "পরীক্ষা লেখা কোনো প্রেসক্রিপশন নেই") else tr("No prescription with medicines", "ওষুধ লেখা কোনো প্রেসক্রিপশন নেই"),
                            null, Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }
                    else -> items(l, key = { it.id }) { rx ->
                        Card(onClick = { onPick(rx) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(rx.doctorName.ifBlank { tr("Doctor", "ডাক্তার") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(Rules.formatDate(rx.createdAt), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                Text(
                                    if (forLab) rx.tests.joinToString(", ") else rx.medicines.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ফার্মেসির পাতা থেকে: দোকানে গিয়ে নেব (সাথে সাথে পাঠানো), অথবা — দোকান অনলাইনে অর্ডার নিলে — অনলাইনে অর্ডার (ঠিকানা-ফোন-নোট)।
 * দোকান অনলাইনে নেয় কিনা: rx_pharmacy_options (online, দোকানের নাম দিয়ে) — ডেলিভারি চার্জ আর কোন ওষুধ নেই তা-ও আসে।
 */
@Composable
private fun PharmacySendDialog(rx: Rx, p: Pharmacy, profile: Profile, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    // null = খোঁজা চলছে; Result-এর ভেতরে null = অনলাইনে নেয় না
    var online by remember { mutableStateOf<Result<PharmOption?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var ordering by remember { mutableStateOf<PharmOption?>(null) }
    LaunchedEffect(rx.id, p.id) {
        online = runCatching { RxNet.pharmacyOptions(rx.id, p.district, null, null, "online", p.name.take(60)).firstOrNull { it.id == p.id } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    val opt = online?.getOrNull()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(p.name) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(tr("I'll go to the shop", "দোকানে গিয়ে নেব")) },
                    leadingContent = { AppIcon(R.drawable.ic_storefront, null, tint = cs.primary) },
                    trailingContent = if (busy) ({ ButtonContent(true, "") }) else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { RxNet.sendPharmacy(rx.id, p.id, "shop") }.onSuccess { onSent() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                )
                when {
                    online == null -> SmallNote(tr("Checking online orders…", "অনলাইন অর্ডার নেয় কিনা দেখা হচ্ছে…"))
                    opt != null -> ListItem(
                        headlineContent = { Text(tr("Order online — home delivery", "অনলাইনে অর্ডার — বাসায় ডেলিভারি")) },
                        supportingContent = if (opt.deliveryFee.isNotBlank()) ({ Text(tr("Delivery: ", "ডেলিভারি: ") + opt.deliveryFee) }) else null,
                        leadingContent = { AppIcon(R.drawable.ic_local_shipping, null, tint = cs.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = !busy) { ordering = opt },
                    )
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
    ordering?.let { o -> OnlineOrderDialog(rx, o, profile, onDismiss = { ordering = null }) { ordering = null; onSent() } }
}

// ------------------------------------------------------------
// প্যাথলজি: "সেবা" ট্যাবের তালিকা আর নিজের সেন্টার
// ------------------------------------------------------------

@Composable
fun LabContent(profile: Profile) {
    val context = LocalContext.current
    var district by remember { mutableStateOf(profile.placeName) }
    var openLab by remember { mutableStateOf<Lab?>(null) }
    // বিস্তারিত থেকে প্রেসক্রিপশন পাঠানো: কোন প্যাথলজিতে, আর পাঠানোর পর শিটে "পাঠানো হয়েছে"
    var sendTo by remember { mutableStateOf<Lab?>(null) }
    var sentNote by remember { mutableStateOf<String?>(null) }
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
                val p = lab.prices
                DirectoryCard(
                    title = lab.name,
                    icon = R.drawable.ic_local_hospital,
                    subtitle = if (lab.homeCollection) tr("Sample from home", "বাসা থেকে নমুনা নেয়") else null,
                    details = listOf(
                        R.drawable.ic_location_on to lab.address, R.drawable.ic_schedule to lab.hours,
                        R.drawable.ic_payments to if (p != null) {
                            tr("${p.count} tests · from ${LabPriceText.taka(p.from)}", "${num(p.count)}টা পরীক্ষার দাম · ${LabPriceText.taka(p.from)} থেকে") +
                                LabPriceText.discount(p.discountPct).let { if (it.isBlank()) "" else " · $it" }
                        } else "",
                    ),
                    primary = CardAction(tr("Call", "ফোন করুন"), R.drawable.ic_call) { callPhone(context, lab.phone) },
                    // বিস্তারিত: পুরো দামের তালিকা খুললে তবেই আনা হয় (তালিকায় শুধু সারাংশ)
                    onClick = { openLab = lab },
                )
            }
        }
    }
    if (showArea) AreaSheet(district, null, null, { d, _, _ -> district = d }, onDismiss = { showArea = false })
    openLab?.let { lab ->
        LabInfoSheet(
            lab, sentNote,
            onSendRx = { rxSendGate(close = { openLab = null }) { openLab = lab; sendTo = lab } },
            onDismiss = { openLab = null; sentNote = null },
        )
    }
    // শিটের ওপরে (পরে আঁকা ডায়ালগ ওপরে থাকে)
    sendTo?.let { lab ->
        RxSendFlow(lab = lab, pharmacy = null, profile = profile, onDismiss = { sendTo = null }) {
            sendTo = null
            sentNote = rxSentNote()
        }
    }
}

/**
 * প্যাথলজির বিস্তারিত: ঠিকানা, সময়, পরীক্ষা ও দাম — পুরো তালিকা এখানে আলাদা করে (lab_prices_public; অতিথিও পারেন,
 * সার্ভারে এক মিনিট মেমোরিতে)। তালিকা না থাকলে "দাম জানা নেই"।
 * sentNote: এই পাতা থেকে পাঠানোর পর "পাঠানো হয়েছে"; onSendRx: "এই প্যাথলজিতে প্রেসক্রিপশন পাঠান"।
 */
@Composable
private fun LabInfoSheet(lab: Lab, sentNote: String?, onSendRx: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = lab.prices
    // null = আসছে; Result-এর ভেতরে null = তালিকা নেই
    var full by remember { mutableStateOf<Result<LabPrices?>?>(null) }
    LaunchedEffect(lab.id) {
        if (p == null) { full = Result.success(null); return@LaunchedEffect }
        full = runCatching { RxNet.labPricesPublic(lab.id) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    val list = full?.getOrNull()
    DetailSheet(
        lab.name, R.drawable.ic_local_hospital, null, MaterialTheme.colorScheme.primary,
        if (lab.homeCollection) tr("Sample from home", "বাসা থেকে নমুনা নেয়") else null, null, 0.0, 0, null,
        listOf(
            DetailSection(tr("Address", "ঠিকানা"), listOf(R.drawable.ic_location_on to listOf(lab.address, Areas.label(lab.district, lab.upazilaId)).filter { it.isNotBlank() }.joinToString(", "))),
            DetailSection(tr("Hours", "সময়"), listOf(R.drawable.ic_schedule to lab.hours, R.drawable.ic_home_health to if (lab.homeCollection) tr("Collects samples from home", "বাসা থেকে নমুনা নেয়") else "")),
            // পরীক্ষা ও দাম (সব চালু পরীক্ষা), ছাড়, বাসার চার্জ — তালিকা না থাকলে "দাম জানা নেই"
            DetailSection(
                tr("Tests & prices", "পরীক্ষা ও দাম"),
                if (p != null) listOf(
                    R.drawable.ic_payments to LabPriceText.discount(p.discountPct),
                    R.drawable.ic_home_health to if (lab.homeCollection) LabPriceText.homeFee(p.homeFee) else "",
                    R.drawable.ic_sync to if (full == null) tr("Loading the price list…", "দামের তালিকা আসছে…") else "",
                    R.drawable.ic_warning to (full?.exceptionOrNull()?.message ?: ""),
                ) else listOf(R.drawable.ic_call to LabPriceText.noPrice()),
                rows = list?.items?.map { DetailRow(it.name, LabPriceText.taka(it.price), if (it.tat.isNotBlank()) tr("Report: ", "রিপোর্ট: ") + it.tat else "") }.orEmpty(),
            ),
            // প্রেসক্রিপশনের পরীক্ষা: এখান থেকেই পাঠানো (আগে শুধু লেখা ছিল "প্রেসক্রিপশন খুলে … চাপুন")
            DetailSection(
                tr("Tests from a prescription", "প্রেসক্রিপশনের পরীক্ষা"),
                listOf(R.drawable.ic_check_circle to sentNote.orEmpty()),
                button = CardAction(tr("Send a prescription to this lab", "এই প্যাথলজিতে প্রেসক্রিপশন পাঠান"), R.drawable.ic_send, onSendRx),
            ),
        ),
        CardAction(tr("Call", "ফোন করুন"), R.drawable.ic_call) { callPhone(context, lab.phone) }, null, null, onDismiss = onDismiss,
    )
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
    var confirming by remember { mutableStateOf<LabOrder?>(null) }
    var declining by remember { mutableStateOf<LabOrder?>(null) }
    val context = LocalContext.current
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
                            // পরীক্ষা ও দাম বসানো ওয়েবে (বসে করার কাজ)
                            SmallAction(tr("Set prices on the web", "দাম বসাতে ওয়েবে খুলুন"), R.drawable.ic_payments, { openUrl(context, ServerApi.BASE_URL + "/work/lab/orders/prices") })
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
                                // বাসা থেকে নমুনা: ঠিকানা, ফোন, চাওয়া/নিশ্চিত সময় — সময় নিশ্চিত, বা যেতে পারব না
                                val h = o.home
                                if (h != null && o.status in setOf("sent", "received")) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconLine(R.drawable.ic_home_health, LabPriceText.homeLineLab(h), when (h.status) { "requested" -> cs.primary; "expired" -> cs.error; else -> cs.onSurfaceVariant })
                                        if (h.status != "declined") {
                                            if (h.address.isNotBlank()) IconLine(R.drawable.ic_location_on, h.address)
                                            if (h.phone.isNotBlank()) SmallAction(num(h.phone), R.drawable.ic_call, { callPhone(context, h.phone) })
                                            if (h.note.isNotBlank()) SmallNote(h.note)
                                        }
                                        if (o.status == "sent" && h.status != "declined") {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                SmallAction(if (h.status == "confirmed") tr("Change time", "সময় বদলান") else tr("Confirm time", "সময় নিশ্চিত করুন"), R.drawable.ic_check_circle, { confirming = o }, primary = h.status != "confirmed")
                                                SmallAction(tr("Can't go", "যেতে পারব না"), R.drawable.ic_close, { declining = o })
                                            }
                                        }
                                    }
                                }
                                if (o.prices != null && o.status in setOf("sent", "received")) SmallNote(LabPriceText.snapLine(o.prices, o.home))
                            }
                        }
                    }
                }
            }
        }
    }
    entering?.let { o -> LabReportEntryDialog(o, onDismiss = { entering = null }) { entering = null; infoMsg = tr("Report sent to the patient", "রোগীর কাছে রিপোর্ট গেছে"); reload++ } }
    confirming?.let { o -> HomeConfirmDialog(o, onDismiss = { confirming = null }) { confirming = null; infoMsg = tr("The patient was told the time", "রোগীকে সময় জানানো হয়েছে"); reload++ } }
    declining?.let { o -> HomeDeclineDialog(o, onDismiss = { declining = null }) { declining = null; infoMsg = tr("The patient was told", "রোগীকে জানানো হয়েছে"); reload++ } }
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

/** রিপোর্ট লেখার একটা ঘর: ছকের (fixed) হলে নাম-একক বদলায় না; সংখ্যার ঘরে মাত্রা দুটো ঘরে */
private data class EntryRow(
    val r: LabResult,
    val fixed: Boolean,
    val numeric: Boolean,
    val options: List<String>,
    val base: Pair<Double?, Double?>,
    val lowText: String,
    val highText: String,
)

private fun fmtNum(d: Double?): String = when {
    d == null -> ""
    d == Math.floor(d) && !d.isInfinite() -> d.toLong().toString()
    else -> d.toString()
}

/**
 * প্যাথলজি: রিপোর্ট দেওয়া — প্রতিটা পরীক্ষার ছক (ঘর, একক, স্বাভাবিক মাত্রা ভরা), শুধু ফল লিখুন;
 * বেশি/কম নিজে থেকে। মেশিনের মাত্রা আলাদা হলে বদলে "আমার ল্যাবের জন্য রাখুন"। ছক না থাকলে আগের মতো নিজে লেখা।
 */
@Composable
private fun LabReportEntryDialog(order: LabOrder, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<EntryRow>() }
    var loaded by remember { mutableStateOf(false) }
    var keepRanges by remember { mutableStateOf(false) }
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
    LaunchedEffect(order.id) {
        val forms = runCatching { RxNet.labForm(order.id) }.getOrElse { order.tests.map { t -> LabForm(t, null, t, emptyList()) } }
        rows.clear()
        forms.forEach { f ->
            if (f.code != null && f.params.isNotEmpty()) {
                f.params.forEach { p ->
                    val numeric = p.low != null || p.high != null
                    rows.add(
                        EntryRow(
                            LabResult(p.name, "", p.unit, if (numeric) RxNetText.range(p.low, p.high) else p.range.ifBlank { p.text }, "", f.name, f.code, p.key, p.low, p.high, p.long),
                            fixed = true, numeric = numeric, options = p.options, base = p.low to p.high, lowText = fmtNum(p.low), highText = fmtNum(p.high),
                        ),
                    )
                }
            } else {
                rows.add(EntryRow(LabResult(f.test, "", "", "", ""), fixed = false, numeric = false, options = emptyList(), base = null to null, lowText = "", highText = ""))
            }
        }
        loaded = true
    }
    fun flagOf(e: EntryRow): String = if (e.numeric) RxNetText.autoFlag(e.r.result, e.r.low, e.r.high) ?: e.r.flag else e.r.flag
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(topBar = { BackTopBar(tr("Report", "রিপোর্ট") + " · " + order.patientName, { if (!busy) onDismiss() }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val cs = MaterialTheme.colorScheme
                Text(
                    listOfNotNull(
                        order.patientName, order.patientAge?.let { tr("$it yrs", "${num(it)} বছর") },
                        when (order.patientGender) { "female" -> tr("Female", "নারী"); "male" -> tr("Male", "পুরুষ"); else -> null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
                if (!loaded) { LoadingBox(); return@Column }
                if (rows.any { it.fixed }) {
                    SmallNote(tr("Rows, units and normal ranges are filled in — just write the results. High/low is marked by itself.", "ঘর, একক আর স্বাভাবিক মাত্রা ভরা আছে — শুধু ফল লিখুন। বেশি/কম নিজে থেকে বসবে।"))
                }
                var lastGroup: String? = null
                rows.forEachIndexed { i, e ->
                    val r = e.r
                    if (e.fixed && r.group != lastGroup) Text(r.group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                    lastGroup = if (e.fixed) r.group else null
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (e.fixed) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.test, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    val f = flagOf(e)
                                    if (f.isNotBlank()) Tag(RxNetText.flag(f), cs.errorContainer, cs.onErrorContainer)
                                }
                            } else {
                                OutlinedTextField(r.test, { rows[i] = e.copy(r = r.copy(test = it.take(80))) }, label = { Text(tr("Test", "পরীক্ষা")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            if (r.long) {
                                OutlinedTextField(r.result, { rows[i] = e.copy(r = r.copy(result = it.take(2000))) }, label = { Text(tr("Findings", "ফলাফল")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            } else {
                                if (e.options.isNotEmpty()) ChipRows(e.options, setOf(r.result), perRow = 4) { rows[i] = e.copy(r = r.copy(result = if (r.result == it) "" else it)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        r.result, { rows[i] = e.copy(r = r.copy(result = it.take(60))) },
                                        label = { Text(tr("Result", "ফল")) },
                                        singleLine = true,
                                        keyboardOptions = if (e.numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (e.fixed) {
                                        if (r.unit.isNotBlank()) Text(r.unit, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                                    } else {
                                        OutlinedTextField(r.unit, { rows[i] = e.copy(r = r.copy(unit = it.take(30))) }, label = { Text(tr("Unit", "একক")) }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            if (e.numeric) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("Normal", "স্বাভাবিক"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    OutlinedTextField(
                                        e.lowText, { v -> val t = v.filter { c -> c.isDigit() || c == '.' }.take(10); rows[i] = e.copy(lowText = t, r = r.copy(low = t.toDoubleOrNull())) },
                                        label = { Text(tr("Low", "নিচে")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        e.highText, { v -> val t = v.filter { c -> c.isDigit() || c == '.' }.take(10); rows[i] = e.copy(highText = t, r = r.copy(high = t.toDoubleOrNull())) },
                                        label = { Text(tr("High", "ওপরে")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                                    )
                                }
                            } else if (!r.long) {
                                OutlinedTextField(r.range, { rows[i] = e.copy(r = r.copy(range = it.take(60))) }, label = { Text(tr("Normal range", "স্বাভাবিক মাত্রা")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                ChipRows(listOf("high", "low", "abnormal"), setOf(r.flag), perRow = 3, label = { RxNetText.flag(it) }) { rows[i] = e.copy(r = r.copy(flag = if (r.flag == it) "" else it)) }
                            }
                        }
                    }
                }
                TextButton(onClick = { rows.add(EntryRow(LabResult("", "", "", "", ""), false, false, emptyList(), null to null, "", "")) }) { ButtonContent(false, tr("Add a row", "আরেকটা সারি"), R.drawable.ic_add) }
                if (rows.any { it.numeric }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Keep changed ranges for my lab", "বদলানো মাত্রা আমার ল্যাবের জন্য রাখুন"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = keepRanges, onCheckedChange = { keepRanges = it })
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                DocPhotosField(photoSpecs, photos, { photos = it }, tr("Photos of the printed report, if you like.", "চাইলে ছাপানো রিপোর্টের ছবি দিন।"))
                ErrorText(errorMsg)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching {
                                if (keepRanges) {
                                    val changed = rows.filter { it.fixed && it.numeric && (it.r.low != it.base.first || it.r.high != it.base.second) }.map { it.r }
                                    if (changed.isNotEmpty()) RxNet.labRanges(changed)
                                }
                                val items = rows.filter { it.r.test.isNotBlank() && it.r.result.isNotBlank() }.map { e ->
                                    e.r.copy(result = e.r.result.trim(), flag = flagOf(e), range = if (e.numeric) RxNetText.range(e.r.low, e.r.high) else e.r.range)
                                }
                                RxNet.labReport(order.id, items, note.trim(), photos.values.filter { it.isNotBlank() })
                            }.onSuccess { onSent() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && (rows.any { it.r.test.isNotBlank() && it.r.result.isNotBlank() } || photos.isNotEmpty()),
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
    LaunchedEffect(r.id) { runCatching { RxNet.labOrder(r.labOrderId, self = true) }.onSuccess { order = it }.onFailure { errorMsg = it.message } }
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
