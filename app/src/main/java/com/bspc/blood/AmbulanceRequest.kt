@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * অ্যাম্বুলেন্স এক চাপে ডাকুন (২.২৮.০, পারভেজ "বানা", ১৯ অক্টোবর ২০২৬) — সার্ভার: server/lib/ambreq.js
 * রোগী: "অ্যাম্বুলেন্স ডাকুন" → একটা নিশ্চিত করার ঘর (ঠিকানা, নোট, ফোন) → পাঠানো → অপেক্ষার পাতা।
 * কাছের খালি গাড়িগুলোতে একসাথে যায়; আগে যিনি "আসছি" চাপেন তিনিই আসেন। ৬০ সেকেন্ডে কেউ না নিলে
 * আরও গাড়িকে জানানো হয় আর এখানেই কাছের ৩টায় ফোনের বাটন; ৩ মিনিটে সময় শেষ (বাটন থাকে)।
 * অপেক্ষার সময় ফোন করলে সেটা অনুরোধে ওঠে; অ্যাপে ফিরলে একবার "ফোনে কোনো গাড়ি পেয়েছেন?"
 * চালক: "আমার অ্যাম্বুলেন্স" পাতার উপরে "ডাক" অংশ — আসছি/পারব না, তারপর পৌঁছেছি/শেষ/বাতিল।
 * ভাড়া (২.৩০.০, AmbFare.kt): ঘরে "কোথায় যাবেন" (বাধ্যতামূলক), অক্সিজেন, "ভাড়া সর্বোচ্চ ৳…" আর সাথে সাথে
 * "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে"; চালক "আসছি — ৳X" (প্রস্তাব), রোগী একটায় "রাজি" চাপলে তবেই নিশ্চিত।
 */

/** তালিকার গাড়ি (ফোনের বাটন, রাজি হওয়া গাড়ি) */
data class AmbCar(val id: String, val name: String, val phone: String, val km: Int?, val kmApprox: Boolean)

/** রোগীর দিকের অনুরোধ */
data class AmbReq(
    val id: String,
    /** open | accepted | arrived | done | byPhone | cancelled | expired */
    val status: String,
    val createdAt: Long,
    val widenAt: Long,
    val expiresAt: Long,
    val wave: Int,
    val notified: Int,
    val pickup: String,
    val note: String,
    /** গাড়ির ধরন (null = যেকোনো) */
    val type: String?,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    /** রোগী এখনকার জায়গা দিয়েছেন */
    val gps: Boolean,
    val ambulance: AmbCar?,
    val cancelledBy: String?,
    val cancelReason: String?,
    val fallback: List<AmbCar>,
    val called: List<AmbCar>,
    val canRate: Boolean,
    /** ভাড়ার অনুরোধ (২.৩০.০); পুরোনো অ্যাপে ডাকা হলে false */
    val quote: Boolean = false,
    val destLabel: String = "",
    val maxFare: Int? = null,
    val includeUnknown: Boolean = false,
    val oxygen: Boolean = false,
    val quotes: List<AmbQuote> = emptyList(),
    val agreedFare: Int? = null,
    /** কেউ না নিলে "৳X পর্যন্ত বাড়ান"; ভাড়া জানা নেই এমন খালি গাড়ি কয়টা */
    val raiseTo: Int? = null,
    val unknownFree: Int = 0,
    /** যাত্রার রসিদ (চালক "যাত্রা শেষ" করলে; না থাকলে খালি) */
    val invoiceId: String = "",
) {
    val active: Boolean get() = status in listOf("open", "accepted", "arrived")
}

/** চালকের প্রস্তাব (রোগীর পাতায়): গাড়ির id দিয়ে "রাজি" */
data class AmbQuote(val id: String, val name: String, val types: List<String>, val km: Int?, val kmApprox: Boolean, val fare: Int, val rating: Double, val ratingCount: Int, val expiresAt: Long)

/** "এই ভাড়ার মধ্যে কয়টা" (ambreq_count) */
data class FareCount(val count: Int, val unknown: Int, val raiseTo: Int?, val hospitals: List<String>, val night: Boolean)

/** চালকের দিকের অনুরোধ — রাজির আগে ফোন, নাম, ঠিক জায়গা নেই */
data class AmbDriverReq(
    val id: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val ambulanceId: String,
    val km: Int?,
    val kmApprox: Boolean,
    val type: String?,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val pickup: String,
    val note: String,
    /** রোগী এই গাড়িতে ফোন করেছেন */
    val called: Boolean,
    val patientName: String,
    val phone: String,
    val lat: Double?,
    val lng: Double?,
    /** ভাড়ার অনুরোধ (২.৩০.০) */
    val quote: Boolean = false,
    val destLabel: String = "",
    val maxFare: Int? = null,
    val oxygen: Boolean = false,
    val night: Boolean = false,
    val suggestedFare: Int? = null,
    /** নিজের দেওয়া ভাড়া (রোগীর উত্তরের অপেক্ষা) */
    val myQuoteFare: Int? = null,
    val agreedFare: Int? = null,
    /** নিজের দেওয়া রসিদ (যাত্রা শেষ হলে) */
    val invoiceId: String = "",
)

object AmbRequests {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun car(o: JSONObject) = AmbCar(
        o.str("id"), o.str("name"), o.str("phone"),
        if (o.has("km") && !o.isNull("km")) o.optInt("km") else null, o.optBoolean("kmApprox"),
    )

    private fun cars(a: JSONArray?): List<AmbCar> = if (a == null) emptyList() else (0 until a.length()).map { car(a.getJSONObject(it)) }

    private fun parse(o: JSONObject) = AmbReq(
        o.str("id"), o.str("status"), o.optLong("createdAt"), o.optLong("widenAt"), o.optLong("expiresAt"), o.optInt("wave", 1),
        o.optInt("notified"), o.str("pickup"), o.str("note"), o.str("type").ifBlank { null }, o.str("district"),
        o.intOrNull("upazilaId"), o.intOrNull("unionId"), o.optBoolean("gps"), o.optJSONObject("ambulance")?.let { car(it) },
        o.str("cancelledBy").ifBlank { null }, o.str("cancelReason").ifBlank { null },
        cars(o.optJSONArray("fallback")), cars(o.optJSONArray("called")), o.optBoolean("canRate"),
        quote = o.optBoolean("quote"), destLabel = o.str("destLabel"), maxFare = o.intOrNull("maxFare"),
        includeUnknown = o.optBoolean("includeUnknown"), oxygen = o.optBoolean("oxygen"),
        quotes = (o.optJSONArray("quotes") ?: JSONArray()).let { a ->
            (0 until a.length()).map { i ->
                val q = a.getJSONObject(i)
                AmbQuote(
                    q.str("id"), q.str("name"), q.optJSONArray("types").strings(), q.intOrNull("km"), q.optBoolean("kmApprox"),
                    q.optInt("fare"), q.optDouble("rating", 0.0), q.optInt("ratingCount"), q.optLong("expiresAt"),
                )
            }
        },
        agreedFare = o.intOrNull("agreedFare"), raiseTo = o.intOrNull("raiseTo"), unknownFree = o.optInt("unknownFree"), invoiceId = o.str("invoiceId"),
    )

    private fun parseDriver(o: JSONObject) = AmbDriverReq(
        o.str("id"), o.str("status"), o.optLong("createdAt"), o.optLong("expiresAt"), o.str("ambulanceId"),
        if (o.has("km") && !o.isNull("km")) o.optInt("km") else null, o.optBoolean("kmApprox"), o.str("type").ifBlank { null },
        o.str("district"), o.intOrNull("upazilaId"), o.intOrNull("unionId"), o.str("pickup"), o.str("note"), o.optBoolean("called"),
        o.str("patientName"), o.str("phone"),
        if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
        if (o.has("lng") && !o.isNull("lng")) o.optDouble("lng") else null,
        quote = o.optBoolean("quote"), destLabel = o.str("destLabel"), maxFare = o.intOrNull("maxFare"),
        oxygen = o.optBoolean("oxygen"), night = o.optBoolean("night"), suggestedFare = o.intOrNull("suggestedFare"),
        myQuoteFare = o.optJSONObject("myQuote")?.intOrNull("fare"), agreedFare = o.intOrNull("agreedFare"), invoiceId = o.str("invoiceId"),
    )

    /** ডাকা আর গোনার ভাড়ার ঘর: maxFare null = যেকোনো */
    private fun JSONObject.fare(dest: Dest?, maxFare: Int?, includeUnknown: Boolean, oxygen: Boolean): JSONObject = apply {
        if (dest != null) put("dest", dest.json()).put("maxFare", maxFare ?: JSONObject.NULL).put("includeUnknown", includeUnknown).put("oxygen", oxygen)
    }

    /** "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" — ডাকার মতো একই বাছাই, শুধু গোনা */
    suspend fun count(district: String, upazilaId: Int?, lat: Double?, lng: Double?, type: String?, dest: Dest, maxFare: Int?, includeUnknown: Boolean, oxygen: Boolean): FareCount {
        val b = JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).put("type", type ?: JSONObject.NULL)
            .fare(dest, maxFare, includeUnknown, oxygen)
        if (lat != null && lng != null) b.put("lat", lat).put("lng", lng).put("gps", true)
        val o = call("ambreq_count", b)
        return FareCount(o.optInt("count"), o.optInt("unknown"), o.intOrNull("raiseTo"), o.optJSONArray("hospitals").strings(), o.optBoolean("night"))
    }

    /** রোগী একটা প্রস্তাবে "রাজি" */
    suspend fun agree(id: String, ambulanceId: String): AmbReq =
        parse(call("ambreq_agree", JSONObject().put("id", id).put("ambulanceId", ambulanceId)).getJSONObject("request"))

    /** অপেক্ষার সময় "ভাড়া বাড়ান" — ফেরত: কয়টা নতুন গাড়িকে জানানো হলো, নতুন অবস্থা */
    suspend fun raise(id: String, maxFare: Int?, includeUnknown: Boolean): Pair<Int, AmbReq> {
        val o = call("ambreq_budget", JSONObject().put("id", id).put("maxFare", maxFare ?: JSONObject.NULL).put("includeUnknown", includeUnknown))
        return o.optInt("added") to parse(o.getJSONObject("request"))
    }

    /** lat/lng: ফোনের আসল GPS (না থাকলে এলাকা ধরে); unionId: শুধু চালকের জানার জন্য; ambulanceId: শিট থেকে — সেই গাড়ি আগে */
    suspend fun create(
        district: String, upazilaId: Int?, unionId: Int?, lat: Double?, lng: Double?, type: String?, pickup: String, note: String, phone: String, ambulanceId: String?,
        dest: Dest? = null, maxFare: Int? = null, includeUnknown: Boolean = false, oxygen: Boolean = false,
    ): AmbReq {
        val b = JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).put("unionId", unionId ?: JSONObject.NULL).put("type", type ?: JSONObject.NULL)
            .put("pickup", pickup).put("note", note).put("phone", phone).put("ambulanceId", ambulanceId ?: JSONObject.NULL)
            .fare(dest, maxFare, includeUnknown, oxygen)
        if (lat != null && lng != null) b.put("lat", lat).put("lng", lng).put("gps", true)
        return parse(call("ambreq_create", b).getJSONObject("request"))
    }

    /** id না দিলে নিজের চালু অনুরোধ (না থাকলে null) */
    suspend fun status(id: String?): AmbReq? =
        call("ambreq_status", JSONObject().apply { if (id != null) put("id", id) }).optJSONObject("request")?.let { parse(it) }

    suspend fun cancel(id: String, reason: String) {
        call("ambreq_cancel", JSONObject().put("id", id).put("reason", reason))
    }

    suspend fun byPhone(id: String, ambulanceId: String): AmbReq =
        parse(call("ambreq_byphone", JSONObject().put("id", id).put("ambulanceId", ambulanceId)).getJSONObject("request"))

    /** রাজি চালককে বাতিল: ambulanceId দিলে সেই (ফোনে পাওয়া) গাড়ি, না দিলে আবার খোঁজা */
    suspend fun replace(id: String, ambulanceId: String?): AmbReq =
        parse(call("ambreq_replace", JSONObject().put("id", id).apply { if (ambulanceId != null) put("ambulanceId", ambulanceId) }).getJSONObject("request"))

    suspend fun driver(): List<AmbDriverReq> {
        val a = call("ambreq_driver").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { parseDriver(a.getJSONObject(it)) }
    }

    /** "আসছি" — ভাড়ার অনুরোধে fare সহ (প্রস্তাব) */
    suspend fun accept(id: String, fare: Int? = null) {
        call("ambreq_accept", JSONObject().put("id", id).apply { if (fare != null) put("fare", fare) })
    }

    suspend fun decline(id: String) {
        call("ambreq_decline", JSONObject().put("id", id))
    }

    /** "arrived" | "done" | "cancel" */
    suspend fun step(id: String, step: String, reason: String? = null) {
        call("ambreq_step", JSONObject().put("id", id).put("step", step).apply { if (reason != null) put("reason", reason) })
    }
}

object AmbReqNav {
    /** নিজের চালু অনুরোধের id (তালিকার উপরের ব্যানার, ফোনকে অনুরোধে তোলা) */
    val active = mutableStateOf<String?>(null)

    /** অনুরোধ চলার সময় ফোন করা হয়েছে — অ্যাপে ফিরলে একবার "ফোনে কোনো গাড়ি পেয়েছেন?" */
    val askAfterCall = mutableStateOf<String?>(null)

    /** অপেক্ষার পাতা খোলা (তালিকা থেকে) — MainActivity পাতাটা খোলে */
    val open = mutableStateOf<String?>(null)
}

/** অনুরোধ চলার সময় কোনো গাড়িতে ফোন: আগের মতো গোনা, সাথে অনুরোধে তোলা; ফিরে এলে একবার জিজ্ঞেস */
fun callDuringRequest(context: Context, scope: kotlinx.coroutines.CoroutineScope, a: AmbCar, reqId: String?) {
    if (reqId != null) AmbReqNav.askAfterCall.value = reqId
    scope.launch { runCatching { Ambulances.called(a.id, reqId) } }
    callPhone(context, a.phone)
}

private fun kmText(km: Int?, approx: Boolean): String? = when {
    km == null -> null
    approx -> tr("about ${km.coerceAtLeast(1)} km", "প্রায় ${num(km.coerceAtLeast(1))} কিমি")
    km < 1 -> tr("< 1 km", "১ কিমির কম")
    else -> num(km) + tr(" km", " কিমি")
}

private const val PREFS = "amb_req"

/** শেষবারের ঠিকানা (এই ফোনেই থাকে) — পরের বার আগে থেকে লেখা */
private fun lastPickup(context: Context): String =
    runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pickup", "") ?: "" }.getOrDefault("")

/** শেষবারের এলাকা "জেলা|উপজেলা|ইউনিয়ন" — একই জেলায় উপজেলা না বাছা থাকলে আগে থেকে */
private fun lastArea(context: Context): Triple<String, Int?, Int?>? = runCatching {
    val p = (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("area", null) ?: return null).split('|')
    Triple(p[0], p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2)?.toIntOrNull())
}.getOrNull()

/** শেষবারের গন্তব্য (JSON) আর বাজেট ("any" বা টাকা) */
private fun lastDest(context: Context): Dest? = runCatching {
    Dest.parse(JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dest", null) ?: return null))
}.getOrNull()

private fun lastBudget(context: Context): String? =
    runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("budget", null) }.getOrNull()

private fun saveLastFare(context: Context, dest: Dest, maxFare: Int?) {
    runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dest", dest.json().toString())
            .putString("budget", maxFare?.toString() ?: "any").apply()
    }
}

private fun saveLast(context: Context, l: AmbLocation) {
    runCatching {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pickup", l.landmark.trim())
        // জায়গা থেকে আপনা থেকে বসানো উপজেলা পরের বারের "নিজের এলাকা" নয়
        if (!l.upazilaAuto) e.putString("area", "${l.district}|${l.upazilaId ?: ""}|${l.unionId ?: ""}")
        e.apply()
    }
}

/**
 * ডাকার সময় গাড়ির ধরন (২.২৯.০) — সার্ভারের ধরনের চাবি (server/lib/ambulance.js TYPES); null = "যেকোনো" (ফ্রিজার ছাড়া),
 * নন-এসি = general। ফ্রিজার শুধু নিজে বাছলে।
 */
object AmbReqTypes {
    const val ANY = "any"
    val ALL = listOf(ANY, "ac", "general", "icu", "freezer")
    fun label(t: String?): String = when (t) {
        null -> tr("Any (not freezer)", "যেকোনো (ফ্রিজার ছাড়া)")
        ANY -> tr("Any", "যেকোনো")
        "ac" -> tr("AC", "এসি")
        "general" -> tr("Non-AC", "নন-এসি")
        "icu" -> tr("ICU", "আইসিইউ")
        "freezer" -> tr("Freezer (deceased)", "ফ্রিজার (মরদেহ)")
        else -> AmbulanceTypes.label(t)
    }
}

/** ম্যাপের লিংক (ফোনের নিজের ম্যাপ, খরচ নেই): স্থানাঙ্ক থাকলে সেটা, নইলে "চেনা জায়গা, এলাকা, বাংলাদেশ" দিয়ে খোঁজা */
fun ambMapUri(lat: Double?, lng: Double?, pickup: String, district: String, upazilaId: Int?, unionId: Int?): String =
    if (lat != null && lng != null) "geo:$lat,$lng?q=$lat,$lng"
    else "geo:0,0?q=" + android.net.Uri.encode(listOf(pickup, Areas.label(district, upazilaId, unionId), "Bangladesh").filter { it.isNotBlank() }.joinToString(", "))

/** অ্যাপ সামনে আছে কি না, আর প্রতিবার ফিরে আসার গোনা */
@Composable
private fun rememberResumeTick(): Pair<Boolean, Int> {
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed to tick
}

// ------------------------------------------------------------
// রোগী: তালিকার উপরে "অ্যাম্বুলেন্স ডাকুন" + "ফোন"
// ------------------------------------------------------------

/**
 * তালিকার উপরের বড় বাটন: "অ্যাম্বুলেন্স ডাকুন" (ভরাট) — কাছের খালি গাড়িগুলোতে একসাথে; পাশে ছোট "ফোন" —
 * সবচেয়ে কাছের খালি গাড়িতে আগের মতো এক চাপে ফোন (নিচে নাম, দূরত্ব)।
 */
@Composable
fun AmbulanceCallBar(nearest: Ambulance?, nearestSub: String?, onRequest: () -> Unit, onCallNearest: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(cs.primary).clickable(onClick = onRequest)
                    .padding(horizontal = 14.dp, vertical = 10.dp).testTag("amb_request"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(cs.onPrimary), contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_ambulance, null, Modifier.size(20.dp), cs.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Call an ambulance", "অ্যাম্বুলেন্স ডাকুন"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onPrimary)
                    Text(tr("All free ones nearby at once", "কাছের খালি গাড়িগুলোতে একসাথে"), style = MaterialTheme.typography.bodySmall, color = cs.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onCallNearest != null) {
                OutlinedButton(onClick = onCallNearest, shape = RoundedCornerShape(16.dp), modifier = Modifier.height(56.dp)) {
                    ButtonContent(false, tr("Call", "ফোন"), R.drawable.ic_call)
                }
            }
        }
        if (nearest != null && nearestSub != null) {
            Text(
                tr("Call: nearest free — ", "ফোন: সবচেয়ে কাছের খালি — ") + nearestSub,
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** নিজের চালু অনুরোধ থাকলে তালিকার উপরে: "অ্যাম্বুলেন্সের অনুরোধ চলছে — দেখুন" */
@Composable
fun ActiveRequestBanner(r: AmbReq, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_ambulance, null, Modifier.size(24.dp), cs.onTertiaryContainer)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    when (r.status) {
                        "accepted" -> tr("${r.ambulance?.name ?: "Ambulance"} is coming", "${r.ambulance?.name ?: "অ্যাম্বুলেন্স"} আসছে")
                        "arrived" -> tr("${r.ambulance?.name ?: "Ambulance"} has arrived", "${r.ambulance?.name ?: "অ্যাম্বুলেন্স"} পৌঁছেছে")
                        else -> tr("Your ambulance request is running", "আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে")
                    },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer,
                )
                Text(tr("Tap to see", "দেখতে চাপুন"), style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
            }
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.onTertiaryContainer)
        }
    }
}

/**
 * নিশ্চিত করার ঘর (২.২৯.০): উপরে গাড়ির ধরন (বাধ্যতামূলক, "যেকোনো" আগে থেকে; তালিকায় ছাঁকা ধরন থাকলে সেটা),
 * তারপর "কোথা থেকে নেবে" (LocationInput, ২.৩১.০: এখনকার জায়গা / Google ম্যাপ থেকে / লোকেশন লিখুন + এলাকা),
 * "কোথায় যাবেন" (হাসপাতাল/ক্লিনিকের তালিকা — DestField), নোট, মোবাইল, "পাঠান"।
 * gps: আগে থেকে পাওয়া এখনকার জায়গা (বেছে নেওয়া জেলার ভেতরে) — থাকলে "নেওয়া হয়েছে ✓"।
 */
@Composable
fun AmbulanceRequestDialog(
    profile: Profile,
    district: String,
    upazilaId: Int?,
    type: String?,
    gps: Pair<Double, Double>?,
    target: Ambulance?,
    onDismiss: () -> Unit,
    onSent: (AmbReq) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(type?.takeIf { it in AmbReqTypes.ALL } ?: AmbReqTypes.ANY) }
    var loc by remember {
        val last = lastArea(context)?.takeIf { it.first == district && (upazilaId == null || it.second == upazilaId) }
        mutableStateOf(AmbLocation(district, upazilaId ?: last?.second, last?.third, lastPickup(context), gps))
    }
    var note by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(profile.phone) }
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // ভাড়া: গন্তব্য (শেষবারেরটা), অক্সিজেন, বাজেট (চিপ / যেকোনো / নিজে লেখা), ভাড়া না জানা গাড়িও
    var dest by remember { mutableStateOf(lastDest(context)) }
    var oxygen by remember { mutableStateOf(false) }
    var budget by remember {
        val b = lastBudget(context)
        mutableStateOf(if (b == null || b == "any") "any" else if (b.toIntOrNull() in FARE_LEVELS) b else "custom")
    }
    var custom by remember { mutableStateOf(lastBudget(context)?.takeIf { it != "any" && it.toIntOrNull() !in FARE_LEVELS } ?: "") }
    var includeUnknown by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf<FareCount?>(null) }
    var counting by remember { mutableStateOf(false) }
    val customFare = parseTaka(custom)
    val maxFare: Int? = when (budget) {
        "any" -> null
        "custom" -> customFare
        else -> budget.toIntOrNull()
    }
    val budgetBad = budget == "custom" && (customFare == null || customFare < 100)
    val carType = kind.takeIf { it != AmbReqTypes.ANY }
    // সাথে সাথে গোনা (৩৫০ মি.সে. থেমে)
    LaunchedEffect(loc.district, loc.upazilaId, loc.point, carType, dest, maxFare, includeUnknown, oxygen, budgetBad) {
        val d = dest
        if (d == null || budgetBad) { count = null; return@LaunchedEffect }
        counting = true
        delay(350)
        count = runCatching { AmbRequests.count(loc.district, loc.upazilaId, loc.point?.first, loc.point?.second, carType, d, maxFare, includeUnknown, oxygen) }.getOrNull()
        counting = false
    }

    FormDialog(
        title = tr("Call an ambulance", "অ্যাম্বুলেন্স ডাকুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("amb_type")) {
                    Text(tr("Ambulance type", "গাড়ির ধরন"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ChipRows(AmbReqTypes.ALL, setOf(kind), label = { AmbReqTypes.label(it) }) { kind = it }
                    if (kind == "freezer") SmallNote(tr("Only freezer vans (for the deceased) get this request.", "শুধু ফ্রিজার ভ্যান (মরদেহ নেওয়ার গাড়ি) এই অনুরোধ পাবে।"))
                }
                Text(
                    if (target != null) tr("Goes to ${target.name} first, and to the nearest free ambulances within your fare. Drivers offer a fare — tap \"Agree\" on one.", "আগে ${target.name}-কে, সাথে আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোকে যাবে। চালকরা ভাড়া বলবেন — একটায় \"রাজি\" চাপুন।")
                    else tr("Goes to the nearest free ambulances within your fare. Drivers offer a fare — tap \"Agree\" on one.", "আপনার ভাড়ার মধ্যে কাছের খালি গাড়িগুলোতে একসাথে যাবে। চালকরা ভাড়া বলবেন — একটায় \"রাজি\" চাপুন।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LocationInput(loc, { loc = it; errorMsg = null }, showErrors = tried)
                // ২.৩১.০: হাসপাতাল/ক্লিনিকের তালিকা থেকে (জেলারটা আগে, কাছেরটা আগে; দেশের বড়গুলো) বা "অন্য জায়গা লিখুন"
                DestField(tr("Where to go", "কোথায় যাবেন"), loc.district, loc.point, dest, { dest = it; errorMsg = null }, showError = tried)
                CheckLine(oxygen, tr("Oxygen needed", "অক্সিজেন লাগবে"), "amb_oxygen") { oxygen = it }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("amb_budget")) {
                    Text(tr("Fare up to", "ভাড়া সর্বোচ্চ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ChipRows(FARE_LEVELS.map { it.toString() } + listOf("any", "custom"), setOf(budget), label = {
                        when (it) {
                            "any" -> tr("Any", "যেকোনো")
                            "custom" -> tr("Other…", "অন্য…")
                            else -> taka(it.toInt())
                        }
                    }) { budget = it }
                    if (budget == "custom") {
                        OutlinedTextField(
                            custom, { custom = it.take(7) }, label = { Text(tr("Fare up to (৳)", "সর্বোচ্চ ভাড়া (৳)")) },
                            isError = custom.isNotBlank() && budgetBad,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CheckLine(includeUnknown, tr("Also ambulances with unknown fare", "ভাড়া জানা নেই এমন গাড়িও"), "amb_unknown") { includeUnknown = it }
                    FareCountLine(count, counting, dest != null, includeUnknown,
                        onRaise = { to -> if (to in FARE_LEVELS) budget = to.toString() else { budget = "custom"; custom = to.toString() } },
                        onUnknown = { includeUnknown = true })
                }
                OutlinedTextField(
                    note, { note = it.take(200) },
                    label = { Text(tr("Note (optional) — e.g. the patient can't walk", "নোট (ঐচ্ছিক) — যেমন রোগী হাঁটতে পারেন না")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text(tr("Your mobile (for the driver)", "আপনার মোবাইল (চালকের জন্য)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            Button(enabled = !busy && count?.count != 0, onClick = {
                tried = true
                errorMsg = loc.error()
                    ?: (if (dest == null) tr("Choose where to go", "\"কোথায় যাবেন\" বেছে নিন") else null)
                    ?: (if (budgetBad) tr("Write the fare limit in whole taka (৳100 or more)", "সর্বোচ্চ ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)") else null)
                    ?: if (!Regex("^(\\+?880|0)?1[3-9]\\d{8}$").matches(phone) && !(DemoLogin.isDemo(AuthRepository.currentUid) && Rules.DEMO_MOBILE.matches(phone))) tr("Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন") else null
                if (errorMsg != null) return@Button
                val d = dest ?: return@Button
                scope.launch {
                    busy = true
                    val l = loc
                    runCatching {
                        AmbRequests.create(
                            l.district, l.upazilaId, l.unionId, l.point?.first, l.point?.second, kind.takeIf { it != AmbReqTypes.ANY },
                            l.landmark.trim(), note.trim(), phone, target?.id?.takeIf { l.district == district },
                            d, maxFare, includeUnknown, oxygen,
                        )
                    }
                        .onSuccess { saveLast(context, l); saveLastFare(context, d, maxFare); onSent(it) }
                        .onFailure { e ->
                            // আগেই একটা চালু: সেটার পাতা
                            if (e is ServerError && e.code == "amb_req_active") {
                                runCatching { AmbRequests.status(null) }.getOrNull()?.let { onSent(it) } ?: run { errorMsg = e.message }
                            } else errorMsg = e.message
                        }
                    busy = false
                }
            }, modifier = Modifier.testTag("amb_send")) { ButtonContent(busy, tr("Send", "পাঠান"), R.drawable.ic_send) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** টিক বাক্স + লেখা */
@Composable
private fun CheckLine(checked: Boolean, text: String, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.testTag(tag)) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" / ০ হলে "৳X পর্যন্ত বাড়ান" আর "ভাড়া জানা নেই এমন গাড়িও" */
@Composable
private fun FareCountLine(count: FareCount?, counting: Boolean, hasDest: Boolean, includeUnknown: Boolean, onRaise: (Int) -> Unit, onUnknown: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when {
        !hasDest -> SmallNote(tr("Choose where to go to see how many are free.", "কয়টা খালি গাড়ি আছে দেখতে \"কোথায় যাবেন\" বেছে নিন।"))
        count == null -> if (counting) SmallNote(tr("Counting…", "গোনা হচ্ছে…"))
        count.count > 0 -> Surface(shape = RoundedCornerShape(12.dp), color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("amb_count")) {
            Text(
                tr("${count.count} free ambulance(s) within this fare", "এই ভাড়ার মধ্যে ${num(count.count)}টা খালি গাড়ি আছে") +
                    if (count.night) tr(" (night fare included)", " (রাতের ভাড়াসহ)") else "",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.padding(12.dp),
            )
        }
        else -> Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth().testTag("amb_count")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("No ambulance within this fare — raise the fare", "এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান"), style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
                count.raiseTo?.let { to ->
                    Button(onClick = { onRaise(to) }, modifier = Modifier.testTag("amb_raise")) { Text(tr("Raise to ${taka(to)}", "${taka(to)} পর্যন্ত বাড়ান")) }
                }
                if (!includeUnknown && count.unknown > 0) {
                    OutlinedButton(onClick = onUnknown) { Text(tr("Include ${count.unknown} with unknown fare", "ভাড়া জানা নেই এমন ${num(count.unknown)}টাও নিন")) }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// রোগী: অপেক্ষার পাতা
// ------------------------------------------------------------

private fun patientReasons() = listOf(
    "found_other" to tr("Got another ambulance", "অন্য গাড়ি পেয়েছি"),
    "not_needed" to tr("No longer needed", "আর দরকার নেই"),
    "too_slow" to tr("Taking too long", "অনেক দেরি হচ্ছে"),
    "wrong" to tr("Sent by mistake", "ভুল করে পাঠিয়েছি"),
    "other" to tr("Other", "অন্য কারণ"),
)

private fun driverReasons() = listOf(
    "vehicle_problem" to tr("Vehicle problem", "গাড়িতে সমস্যা"),
    "cant_reach" to tr("Can't reach in time", "সময়মতো পৌঁছাতে পারব না"),
    "patient_unreachable" to tr("Patient not reachable", "রোগীকে পাওয়া যাচ্ছে না"),
    "other" to tr("Other", "অন্য কারণ"),
)

private fun cancelReasonText(r: String?): String = (patientReasons() + driverReasons()).firstOrNull { it.first == r }?.second
    ?: if (r == "patient_found_other") tr("Patient found another ambulance", "রোগী অন্য গাড়ি পেয়েছেন") else ""

/** কারণ বেছে বাতিল */
@Composable
private fun ReasonDialog(title: String, reasons: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRows(reasons.map { it.first }, setOfNotNull(picked), label = { k -> reasons.first { it.first == k }.second }) { picked = it }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && picked != null, onClick = {
                scope.launch {
                    busy = true
                    runCatching { onPick(picked!!) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Cancel request", "বাতিল করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Back", "ফিরে যান")) } },
    )
}

/** ফোন করা গাড়িগুলো থেকে একটা বাছা ("ফোনে কোনো গাড়ি পেয়েছেন?" / "ফোনে অন্য গাড়ি পেয়েছি") */
@Composable
private fun PhoneBookingDialog(title: String, cars: List<AmbCar>, noLabel: String, onDismiss: () -> Unit, onPick: suspend (AmbCar?) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    fun go(c: AmbCar?) {
        scope.launch {
            busy = true
            runCatching { onPick(c) }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cars.forEach { c ->
                    OutlinedButton(onClick = { go(c) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, tr("Yes — ", "হ্যাঁ — ") + c.name, R.drawable.ic_check_circle)
                    }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = { TextButton(onClick = { go(null) }, enabled = !busy) { ButtonContent(busy, noLabel) } },
        dismissButton = {},
    )
}

@Composable
fun AmbulanceRequestScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var req by remember { mutableStateOf<AmbReq?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var cancelling by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var okayed by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf(false) }
    var rated by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var agreeing by remember { mutableStateOf<String?>(null) }
    var raising by remember { mutableStateOf(false) }
    var fareInfo by remember { mutableStateOf<String?>(null) }
    val (resumed, tick) = rememberResumeTick()
    fun agree(q: AmbQuote) {
        scope.launch {
            agreeing = q.id
            errorMsg = null
            runCatching { AmbRequests.agree(id, q.id) }.onSuccess { req = it; reload++ }.onFailure { errorMsg = it.message; reload++ }
            agreeing = null
        }
    }
    fun raise(maxFare: Int?, unknown: Boolean) {
        scope.launch {
            raising = true
            errorMsg = null
            runCatching { AmbRequests.raise(id, maxFare, unknown || req?.includeUnknown == true) }
                .onSuccess { (added, r) ->
                    req = r
                    fareInfo = if (added > 0) tr("Sent to $added more ambulance(s)", "আরও ${num(added)}টা গাড়িকে জানানো হয়েছে")
                    else tr("No new ambulance within this fare — drivers already notified can now offer up to it", "এই ভাড়ায় নতুন কোনো গাড়ি নেই — আগে জানানো চালকরা এখন এই পর্যন্ত বলতে পারবেন")
                }
                .onFailure { errorMsg = it.message }
            raising = false
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // অবস্থা: অপেক্ষায় ৪ সেকেন্ড পরপর, রাজির পরে ১০ সেকেন্ড, বন্ধ হলে থামে; অ্যাপ পেছনে গেলে থামে
    LaunchedEffect(id, resumed, reload) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            runCatching { AmbRequests.status(id) }
                .onSuccess { r -> req = r; errorMsg = null; AmbReqNav.active.value = if (r?.active == true) id else null }
                .onFailure { errorMsg = it.message }
            val r = req
            if (r != null && !r.active) break
            delay(if (r?.status == "open") 4_000 else 10_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // ফোন করে ফিরে এলে একবার: "ফোনে কোনো গাড়ি পেয়েছেন?" (খোলা অবস্থায়; রাজি থাকলে নিচের দুই বাটন)
    LaunchedEffect(tick, req?.status, req?.called?.size) {
        val r = req ?: return@LaunchedEffect
        if (AmbReqNav.askAfterCall.value == id && r.status == "open" && r.called.isNotEmpty()) {
            AmbReqNav.askAfterCall.value = null
            asking = true
        }
    }

    Scaffold(topBar = { BackTopBar(tr("Calling an ambulance", "অ্যাম্বুলেন্স ডাকা"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ErrorText(errorMsg)
            val r = req
            if (r == null) {
                if (errorMsg == null) LoadingBox()
                return@Column
            }
            when (r.status) {
                "open" -> {
                    val left = ((r.expiresAt - now) / 1000).coerceAtLeast(0)
                    val quotes = r.quotes.filter { it.expiresAt > now }
                    if (quotes.isNotEmpty()) QuoteList(quotes, now, agreeing) { agree(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (quotes.isNotEmpty()) tr("Waiting for more offers…", "আরও প্রস্তাবের অপেক্ষা…") else tr("Looking for an ambulance…", "অ্যাম্বুলেন্স খোঁজা হচ্ছে…"),
                                style = if (quotes.isNotEmpty()) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (r.wave > 1) tr("Sent to ${r.notified} ambulances — more were added", "${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে — আরও গাড়ি যোগ হয়েছে")
                                else if (r.quote) tr("Sent to ${r.notified} nearby ambulances within your fare. Offers will show here.", "আপনার ভাড়ার মধ্যে কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। প্রস্তাব এলে এখানে দেখাবে।")
                                else tr("Sent to ${r.notified} nearby ambulances. We'll tell you as soon as one accepts.", "কাছের ${num(r.notified)}টা অ্যাম্বুলেন্সকে জানানো হয়েছে। কেউ রাজি হলেই জানাবো।"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(tr("Waiting: ", "অপেক্ষা: ") + num("${left / 60}:${(left % 60).toString().padStart(2, '0')}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                "accepted", "arrived" -> r.ambulance?.let { a -> AcceptedCard(r, a, scope, id) }
                "done" -> {
                    StatusHead(R.drawable.ic_check_circle, tr("Trip finished", "ট্রিপ শেষ"), r.ambulance?.name ?: "")
                    if (r.canRate && !rated) Button(onClick = { rating = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Rate the ambulance", "অ্যাম্বুলেন্সকে রেটিং দিন"), R.drawable.ic_star) }
                    // চালকের দেওয়া ভাড়ার রসিদ (Receipts.kt)
                    ReceiptLink(r.invoiceId)
                }
                "byPhone" -> {
                    StatusHead(R.drawable.ic_check_circle, tr("You got an ambulance by phone", "ফোনে গাড়ি পেয়েছেন"), r.ambulance?.name ?: "")
                    r.ambulance?.takeIf { it.phone.isNotBlank() }?.let { a ->
                        OutlinedButton(onClick = { callPhone(context, a.phone) }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Call ", "ফোন: ") + num(a.phone), R.drawable.ic_call) }
                    }
                }
                "expired" -> StatusHead(R.drawable.ic_warning, tr("No ambulance accepted", "কোনো অ্যাম্বুলেন্স রাজি হয়নি"), tr("Please call one directly below.", "নিচে থেকে সরাসরি ফোন করুন।"))
                "cancelled" -> StatusHead(
                    R.drawable.ic_info,
                    if (r.cancelledBy == "driver") tr("${r.ambulance?.name ?: "The driver"} cancelled", "${r.ambulance?.name ?: "চালক"} বাতিল করেছেন") else tr("You cancelled the request", "আপনি অনুরোধ বাতিল করেছেন"),
                    cancelReasonText(r.cancelReason),
                )
            }

            // ৬০ সেকেন্ডে কেউ ভাড়া বলেননি: "ভাড়া বাড়ান" (ফোনের বাটনের পাশাপাশি)
            if (r.status == "open" && r.quote && r.wave > 1 && r.quotes.none { it.expiresAt > now }) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("amb_raise_box")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Nobody has offered yet. Raise the fare to reach more ambulances:", "এখনো কেউ ভাড়া বলেননি। আরও গাড়ি পেতে ভাড়া বাড়ান:"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        r.raiseTo?.let { to -> Button(onClick = { raise(to, false) }, enabled = !raising, modifier = Modifier.testTag("amb_raise")) { ButtonContent(raising, tr("Raise to ${taka(to)}", "${taka(to)} পর্যন্ত বাড়ান")) } }
                        if (r.maxFare != null) OutlinedButton(onClick = { raise(null, false) }, enabled = !raising) { Text(tr("Any fare", "যেকোনো ভাড়া")) }
                        if (!r.includeUnknown && r.unknownFree > 0) {
                            OutlinedButton(onClick = { raise(r.maxFare, true) }, enabled = !raising) { Text(tr("Include ${r.unknownFree} with unknown fare", "ভাড়া জানা নেই এমন ${num(r.unknownFree)}টাও")) }
                        }
                    }
                }
            }
            InfoText(fareInfo)

            // ৬০ সেকেন্ড পরে / সময় শেষে / চালক বাতিল করলে: কাছের ৩টা গাড়িতে সরাসরি ফোন
            if (r.fallback.isNotEmpty() && r.status in listOf("open", "expired", "cancelled")) {
                Text(
                    if (r.status == "open") tr("Or call one now:", "অথবা এখনই ফোন করুন:") else tr("Nearest free ambulances:", "কাছের খালি অ্যাম্বুলেন্স:"),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                r.fallback.forEach { a ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                kmText(a.km, a.kmApprox)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Button(onClick = { callDuringRequest(context, scope, a, if (r.active) id else null) }) {
                                ButtonContent(false, tr("Call", "ফোন করুন"), R.drawable.ic_call)
                            }
                        }
                    }
                }
            }

            if (r.status == "open" && r.called.isNotEmpty()) {
                OutlinedButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Got an ambulance by phone?", "ফোনে কোনো গাড়ি পেয়েছেন?"), R.drawable.ic_call) }
            }

            // রাজি হয়েছেন, কিন্তু রোগী অপেক্ষার সময় অন্য গাড়িতেও ফোন করেছিলেন
            val others = r.called.filter { it.id != r.ambulance?.id }
            if (r.status in listOf("accepted", "arrived") && others.isNotEmpty() && okayed != r.ambulance?.id) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("${r.ambulance?.name} is coming. Did you also book another one by phone?", "${r.ambulance?.name} আসছে। ফোনে অন্য গাড়িও ঠিক করেছেন?"), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { okayed = r.ambulance?.id }) { Text(tr("OK", "ঠিক আছে")) }
                            OutlinedButton(onClick = { replacing = true }) { Text(tr("Got another by phone — cancel this one", "ফোনে অন্য গাড়ি পেয়েছি — এঁকে বাতিল"), maxLines = 2) }
                        }
                    }
                }
            }

            if (r.active) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp).testTag("amb_req_info"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconLine(R.drawable.ic_ambulance, tr("Type: ", "ধরন: ") + AmbReqTypes.label(r.type))
                        IconLine(R.drawable.ic_map, Areas.label(r.district, r.upazilaId, r.unionId) + if (r.gps) tr(" · current location given", " · এখনকার জায়গা দেওয়া") else "")
                        IconLine(R.drawable.ic_location_on, r.pickup)
                        if (r.quote && r.destLabel.isNotBlank()) IconLine(R.drawable.ic_local_hospital, tr("Going to: ", "যাবেন: ") + r.destLabel + if (r.oxygen) tr(" · oxygen", " · অক্সিজেন") else "")
                        if (r.quote && r.status == "open") IconLine(R.drawable.ic_tune, tr("Fare up to: ", "ভাড়া সর্বোচ্চ: ") + (r.maxFare?.let { taka(it) } ?: tr("any", "যেকোনো")))
                        if (r.note.isNotBlank()) IconLine(R.drawable.ic_info, r.note)
                    }
                }
                TextButton(onClick = { cancelling = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Cancel request", "অনুরোধ বাতিল করুন"), color = MaterialTheme.colorScheme.error)
                }
            } else {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Back to the list", "তালিকায় ফিরুন"), R.drawable.ic_arrow_back) }
            }
            EmergencyCallCard("amb_req")
        }
    }

    if (cancelling) {
        ReasonDialog(tr("Why cancel?", "কেন বাতিল করছেন?"), patientReasons(), onDismiss = { cancelling = false }) { reason ->
            AmbRequests.cancel(id, reason)
            cancelling = false
            reload++
        }
    }
    val r = req
    if (asking && r != null) {
        PhoneBookingDialog(
            tr("Got an ambulance by phone?", "ফোনে কোনো গাড়ি পেয়েছেন?"), r.called, tr("No, keep looking", "না, খুঁজতে থাকুন"),
            onDismiss = { asking = false },
        ) { c ->
            if (c != null) req = AmbRequests.byPhone(id, c.id)
            asking = false
        }
    }
    if (replacing && r != null) {
        PhoneBookingDialog(
            tr("Which one did you get by phone?", "ফোনে কোন গাড়ি পেয়েছেন?"), r.called.filter { it.id != r.ambulance?.id },
            tr("None — keep looking", "কোনোটা না — আবার খুঁজুন"),
            onDismiss = { replacing = false },
        ) { c ->
            req = AmbRequests.replace(id, c?.id)
            replacing = false
            reload++
        }
    }
    if (rating && r?.ambulance != null) {
        StarRatingDialog(
            title = tr("Rate ", "রেটিং: ") + r.ambulance.name,
            onDismiss = { rating = false },
            // অ্যাপে ভাড়া ঠিক হয়ে থাকলে: "বলা ভাড়ার চেয়ে বেশি নিয়েছে"
            extra = r.agreedFare?.let { f -> @Composable { CheckLine(over, tr("Charged more than the agreed fare (${taka(f)})", "বলা ভাড়ার চেয়ে বেশি নিয়েছে (${taka(f)})"), "amb_overcharged") { over = it } } },
            onSubmit = { stars, comment ->
                Ambulances.rate(r.ambulance.id, stars, comment, if (r.agreedFare != null) over else null)
                rating = false
                rated = true
            },
        )
    }
}

/** চালকদের প্রস্তাব: ভাড়া কম আগে (সার্ভার সাজায়), প্রতিটায় "রাজি" */
@Composable
private fun QuoteList(quotes: List<AmbQuote>, now: Long, agreeing: String?, onAgree: (AmbQuote) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("amb_quotes")) {
        Text(tr("Fare offers — pick one", "ভাড়ার প্রস্তাব — একটা বেছে নিন"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SmallNote(tr("Confirmed only when you tap \"Agree\". Each offer stays 2 minutes.", "\"রাজি\" চাপলে তবেই নিশ্চিত। প্রতিটা প্রস্তাব ২ মিনিট থাকে।"))
        quotes.forEach { q ->
            val secs = ((q.expiresAt - now) / 1000).coerceAtLeast(0)
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                q.types.joinToString(", ") { AmbReqTypes.label(it) }.ifBlank { null }, kmText(q.km, q.kmApprox),
                                if (q.ratingCount > 0) "★ ${num(q.rating.toString())} (${num(q.ratingCount)})" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        Text(tr("Expires in ", "থাকবে আর ") + num("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(taka(q.fare), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.primary)
                        Button(onClick = { onAgree(q) }, enabled = agreeing == null, modifier = Modifier.testTag("amb_agree")) {
                            ButtonContent(agreeing == q.id, tr("Agree", "রাজি"), R.drawable.ic_check_circle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHead(icon: Int, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, null, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** রাজি হওয়া গাড়ি: নাম, দূরত্ব, "আসছে"/"পৌঁছেছে", চালকের ফোন (বড় বাটন) — গাড়ির নম্বর নয় */
@Composable
private fun AcceptedCard(r: AmbReq, a: AmbCar, scope: kotlinx.coroutines.CoroutineScope, id: String) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_ambulance, null, Modifier.size(26.dp), cs.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (r.status == "arrived") tr("Arrived", "পৌঁছেছে") else tr("Coming", "আসছে"), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                    Text(a.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                    kmText(a.km, a.kmApprox)?.let { Text(it + tr(" away", " দূরে"), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer) }
                }
            }
            // ঠিক হওয়া ভাড়া — বড় করে
            r.agreedFare?.let { f ->
                Surface(shape = RoundedCornerShape(12.dp), color = cs.surface, modifier = Modifier.fillMaxWidth().testTag("amb_agreed_fare")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(tr("Agreed fare", "ঠিক হওয়া ভাড়া"), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Text(taka(f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                        if (r.destLabel.isNotBlank()) Text(tr("To ", "গন্তব্য: ") + r.destLabel, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (a.phone.isNotBlank()) {
                Button(onClick = { callDuringRequest(context, scope, a, id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    ButtonContent(false, tr("Call driver ", "চালককে ফোন ") + num(a.phone), R.drawable.ic_call)
                }
            }
        }
    }
}

// ------------------------------------------------------------
// চালক: "আমার অ্যাম্বুলেন্স" পাতার উপরে "ডাক"
// ------------------------------------------------------------

/**
 * চালক/মালিকের "ডাক": তাঁর কাছে আসা খোলা অনুরোধ (দূরত্ব, ঠিকানা, নোট, কতক্ষণ আগে) — "আসছি" / "পারব না";
 * নিজের নেওয়াটা (রোগীর নাম, ফোন, ঠিকানা, "ম্যাপে খুলুন") — "পৌঁছেছি" / "শেষ" / "বাতিল"।
 * পাতা খোলা থাকলে ৫ সেকেন্ড পরপর নতুন করে (অ্যাপ পেছনে গেলে থামে)। onChanged: গাড়ির খালি/ব্যস্ত বদলেছে।
 */
@Composable
fun AmbulanceCallsSection(names: Map<String, String>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AmbDriverReq>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf<AmbDriverReq?>(null) }
    var finishing by remember { mutableStateOf<AmbDriverReq?>(null) }
    val invAmb = invOn(InvoiceFeatures.key("ambulance"))
    var reload by remember { mutableIntStateOf(0) }
    // প্রতিটা ডাকের ভাড়ার ঘর (না লিখলে আনুমানিক ভাড়া)
    var fares by remember { mutableStateOf(mapOf<String, String>()) }
    val (resumed, _) = rememberResumeTick()

    LaunchedEffect(resumed, reload) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            runCatching { AmbRequests.driver() }.onSuccess { items = it; loaded = true; errorMsg = null }.onFailure { errorMsg = it.message }
            delay(5_000)
        }
    }

    fun act(r: AmbDriverReq, changed: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            busy = r.id
            errorMsg = null
            runCatching { block() }.onSuccess { if (changed) onChanged() }.onFailure { errorMsg = it.message }
            busy = null
            reload++
        }
    }

    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_notifications_active, null, Modifier.size(20.dp), cs.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr("Calls", "ডাক"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        ErrorText(errorMsg)
        InfoText(infoMsg)
        if (loaded && items.isEmpty()) {
            SmallNote(tr("When a patient nearby calls an ambulance, it shows here. Keep \"Available now\" on.", "কাছের কোনো রোগী অ্যাম্বুলেন্স ডাকলে এখানে আসবে। \"এখন খালি আছি\" চালু রাখুন।"))
        }
        items.forEach { r ->
            val mine = r.status != "open"
            Surface(shape = RoundedCornerShape(16.dp), color = if (mine) cs.primaryContainer else cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val where = (kmText(r.km, r.kmApprox)?.let { it + tr(" away", " দূরে") + " · " } ?: "") + Areas.label(r.district, r.upazilaId, r.unionId)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (mine) (if (r.status == "arrived") tr("You arrived", "আপনি পৌঁছেছেন") else tr("You're going", "আপনি যাচ্ছেন")) else tr("Ambulance needed", "অ্যাম্বুলেন্স দরকার"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Text(timeAgo(r.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    if (r.called) Tag(tr("The patient called you", "রোগী আপনাকে ফোন করেছেন"), cs.error, cs.onError)
                    IconLine(R.drawable.ic_map, where)
                    IconLine(R.drawable.ic_location_on, r.pickup)
                    if (r.note.isNotBlank()) IconLine(R.drawable.ic_info, r.note)
                    IconLine(R.drawable.ic_ambulance, tr("Type: ", "ধরন: ") + AmbReqTypes.label(r.type) + if (r.oxygen) tr(" · oxygen needed", " · অক্সিজেন লাগবে") else "")
                    if (r.quote && r.destLabel.isNotBlank()) IconLine(R.drawable.ic_local_hospital, tr("Going to: ", "যাবেন: ") + r.destLabel)
                    names[r.ambulanceId]?.let { IconLine(R.drawable.ic_ambulance, tr("Your vehicle: ", "আপনার গাড়ি: ") + it) }
                    if (mine && r.agreedFare != null) {
                        Surface(shape = RoundedCornerShape(10.dp), color = cs.surface, modifier = Modifier.testTag("amb_driver_agreed")) {
                            Text(tr("Agreed fare ", "ঠিক হওয়া ভাড়া ") + taka(r.agreedFare), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    if (mine) {
                        if (r.patientName.isNotBlank()) IconLine(R.drawable.ic_person_outline, r.patientName)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (r.phone.isNotBlank()) Button(onClick = { callPhone(context, r.phone) }) { ButtonContent(false, num(r.phone), R.drawable.ic_call) }
                            // ফোনের নিজের ম্যাপ অ্যাপ — আলাদা ম্যাপ SDK নেই, খরচ নেই; ঠিক জায়গা না থাকলে ঠিকানা আর এলাকা দিয়ে খোঁজা
                            OutlinedButton(onClick = { openUrl(context, ambMapUri(r.lat, r.lng, r.pickup, r.district, r.upazilaId, r.unionId)) }) {
                                ButtonContent(false, tr("Open in map", "ম্যাপে খুলুন"), R.drawable.ic_map)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (r.status == "accepted") {
                                Button(onClick = { act(r) { AmbRequests.step(r.id, "arrived") } }, enabled = busy == null) { ButtonContent(busy == r.id, tr("Arrived", "পৌঁছেছি")) }
                            }
                            // "যাত্রা শেষ": ভাড়া + অতিরিক্ত + কীভাবে পেলেন → রসিদ (ambreq_finish, InvoiceForms.kt; inv_ambulance) — বন্ধ থাকলে আগের "শেষ"
                            if (invAmb) {
                                Button(onClick = { finishing = r }, enabled = busy == null, modifier = Modifier.testTag("amb_finish")) {
                                    ButtonContent(busy == r.id, tr("Finish trip", "যাত্রা শেষ"))
                                }
                            } else {
                                Button(onClick = { act(r, changed = true) { AmbRequests.step(r.id, "done"); infoMsg = tr("Trip finished. You're available again.", "ট্রিপ শেষ। আপনি আবার খালি।") } }, enabled = busy == null) {
                                    ButtonContent(busy == r.id, tr("Finish", "শেষ"))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { cancelling = r }, enabled = busy == null) { Text(tr("Cancel", "বাতিল"), color = cs.error) }
                        }
                    } else if (r.quote && r.myQuoteFare != null) {
                        Surface(shape = RoundedCornerShape(12.dp), color = cs.surface, modifier = Modifier.fillMaxWidth().testTag("amb_quote_wait")) {
                            Column(Modifier.padding(12.dp)) {
                                Text(tr("Waiting for the patient…", "রোগীর উত্তরের অপেক্ষা…"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(tr("Your fare ", "আপনার ভাড়া ") + taka(r.myQuoteFare) + tr(" — confirmed only when the patient agrees.", " — রোগী \"রাজি\" চাপলে তবেই নিশ্চিত।"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    } else if (r.quote) {
                        val text = fares[r.id] ?: r.suggestedFare?.toString() ?: ""
                        val fare = parseTaka(text)
                        val over = fare != null && fare > 0 && r.maxFare != null && fare > r.maxFare
                        val okFare = fare != null && fare >= 100 && !over
                        SmallNote(
                            (r.suggestedFare?.let { tr("From your fare list: ", "আপনার ভাড়ার তালিকা থেকে: ") + taka(it) } ?: tr("Not in your fare list — write your fare.", "আপনার ভাড়ার তালিকায় নেই — ভাড়া লিখুন।")) +
                                (r.maxFare?.let { tr(" · patient's limit ", " · রোগীর সর্বোচ্চ ") + taka(it) } ?: "") + if (r.night) tr(" · night", " · রাত") else "",
                        )
                        OutlinedTextField(
                            text, { fares = fares + (r.id to it.take(7)) }, label = { Text(tr("Your fare (৳)", "আপনার ভাড়া (৳)")) },
                            isError = over, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("amb_fare_input"),
                        )
                        if (over) ErrorText(tr("The patient's limit is ${taka(r.maxFare)} — you can't offer more.", "রোগীর সর্বোচ্চ ${taka(r.maxFare)} — এর বেশি দেওয়া যায় না।"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { act(r, changed = true) { AmbRequests.accept(r.id, fare) } },
                                enabled = busy == null && okFare, modifier = Modifier.weight(1f).height(48.dp).testTag("amb_accept"),
                            ) { ButtonContent(busy == r.id, tr("Coming", "আসছি") + if (okFare) " — " + taka(fare) else "", R.drawable.ic_check_circle) }
                            OutlinedButton(onClick = { act(r) { AmbRequests.decline(r.id) } }, enabled = busy == null, modifier = Modifier.height(48.dp)) { Text(tr("Can't", "পারব না")) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { act(r, changed = true) { AmbRequests.accept(r.id) } },
                                enabled = busy == null, modifier = Modifier.weight(1f).height(48.dp).testTag("amb_accept"),
                            ) { ButtonContent(busy == r.id, tr("Coming", "আসছি"), R.drawable.ic_check_circle) }
                            OutlinedButton(onClick = { act(r) { AmbRequests.decline(r.id) } }, enabled = busy == null, modifier = Modifier.height(48.dp)) { Text(tr("Can't", "পারব না")) }
                        }
                    }
                }
            }
        }
    }
    finishing?.let { r ->
        AmbFinishDialog(r, onDismiss = { finishing = null }) {
            finishing = null
            infoMsg = tr("Trip finished. You're available again.", "ট্রিপ শেষ। আপনি আবার খালি।")
            onChanged()
            reload++
        }
    }
    cancelling?.let { r ->
        ReasonDialog(tr("Why cancel?", "কেন বাতিল করছেন?"), driverReasons(), onDismiss = { cancelling = null }) { reason ->
            AmbRequests.step(r.id, "cancel", reason)
            cancelling = null
            onChanged()
            reload++
        }
    }
}
