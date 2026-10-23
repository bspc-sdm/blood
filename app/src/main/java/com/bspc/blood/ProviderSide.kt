@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

// ------------------------------------------------------------
// "আমার কাজ" — সেবাদাতার দিক (মালিকের অনুমোদন, ২৩ অক্টোবর ২০২৬), পাঁচ ধরনেই একই গঠন:
//  ১) "রোগীরা আপনাকে যেভাবে দেখেন": সবার তালিকার একই কার্ড, একই তথ্য — ভাঁজ করা যায় (এই ফোনে, ব্যবহারকারী ধরে মনে থাকে),
//     চাপলে রোগীরা যে বিস্তারিত দেখেন সেটা। নিচের সুইচ বদলালে সাথে সাথে বদলায়।
//  ২) এক চাপের সুইচ (কার্ডের মূল লাইন এগুলো থেকেই): ফার্মেসি খোলা/বন্ধ এখন · ডেলিভারি; ল্যাব খোলা/বন্ধ এখন · বাসা থেকে নমুনা;
//     অ্যাম্বুলেন্স খালি আছি / ট্রিপে আছি; ব্লাড ব্যাংক আট গ্রুপের স্টক; ডাক্তার চেম্বারপ্রতি আজ সিরিয়াল চলছে / আজ বন্ধ।
//  ৩) নিচে নিজের কাজের তালিকা: নতুন / চলছে / শেষ (সংখ্যাসহ) — আগের তালিকাই, শুধু একই রকম সাজানো।
// সার্ভার কোনো সুইচের কাজ না চিনলে (পুরোনো সার্ভার) সুইচটা সরে যায়, বাকিটা আগের মতো।
// ------------------------------------------------------------

object ProviderText {
    fun preview(): String = tr("How patients see you", "রোগীরা আপনাকে যেভাবে দেখেন")
    fun switches(): String = tr("Quick switches", "এক চাপে বদলান")
    fun openNow(): String = tr("Open now", "এখন খোলা")
    fun closedNow(): String = tr("Closed now", "এখন বন্ধ")
    fun overrideNote(): String = tr("Until the next opening/closing time", "পরের খোলা/বন্ধের সময় পর্যন্ত")
    fun deliveryOn(): String = tr("Delivery on", "ডেলিভারি চালু")
    fun deliveryOff(): String = tr("Delivery off", "ডেলিভারি বন্ধ")
    fun homeOn(): String = tr("Home sample on", "বাসা থেকে নমুনা চালু")
    fun homeOff(): String = tr("Home sample off", "বাসা থেকে নমুনা বন্ধ")
    fun delivery(): String = tr("Home delivery", "ডেলিভারি")
    fun homeSample(): String = tr("Sample from home", "বাসা থেকে নমুনা")
    fun available(): String = tr("I am free", "খালি আছি")
    fun onTrip(): String = tr("On a trip", "ট্রিপে আছি")
    fun todaySerial(): String = tr("Serial running today", "আজ সিরিয়াল চলছে")
    fun todayClosed(): String = tr("Closed today", "আজ বন্ধ")
    fun stockAllGood(): String = tr("All correct — update now", "সব ঠিক আছে — এখন হালনাগাদ")
    fun saveStock(): String = tr("Save stock", "মজুদ সেভ করুন")
    fun notYet(): String = tr("This switch isn't available on the server yet", "এই সুইচ সার্ভারে এখনো চালু হয়নি")
    fun segNew(): String = tr("New", "নতুন")
    fun segActive(): String = tr("In progress", "চলছে")
    fun segDone(): String = tr("Done", "শেষ")
}

/** নতুন / চলছে / শেষ — সব সেবাদাতার কাজের তালিকায় একই ভাগ */
object WorkQueue {
    val SEGS = listOf("new", "active", "done")
    /** সার্ভারের ভাগ → তিন ভাগ: new → নতুন; active/ready → চলছে; reported/closed/done → শেষ */
    fun of(serverSeg: String): String = when (serverSeg) {
        "new" -> "new"
        "active" -> "active"
        else -> "done"
    }

    fun counts(server: Map<String, Int>?): Map<String, Int> =
        (server ?: emptyMap()).entries.groupBy({ of(it.key) }, { it.value }).mapValues { it.value.sum() }

    fun label(s: String): String = when (s) {
        "new" -> ProviderText.segNew()
        "active" -> ProviderText.segActive()
        else -> ProviderText.segDone()
    }
}

@Composable
fun WorkSegTabs(selected: String, counts: Map<String, Int>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val i = WorkQueue.SEGS.indexOf(selected).coerceAtLeast(0)
    Column(modifier.testTag("work_segs")) {
        PillTabs(i, WorkQueue.SEGS.map { PillTab(WorkQueue.label(it), count = counts[it] ?: 0) }, { onSelect(WorkQueue.SEGS[it]) }, secondary = true)
    }
}

/** ভাঁজ করা আছে কি না — এই ফোনে, ব্যবহারকারী আর ধরন ধরে */
object PreviewPrefs {
    private const val FILE = "listing_preview"
    private fun key(type: ListingType) = (AuthRepository.currentUid ?: "guest") + "_" + type.key
    fun collapsed(context: Context, type: ListingType): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key(type), false) }.getOrDefault(false)
    fun set(context: Context, type: ListingType, v: Boolean) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key(type), v).apply() }
    }
}

/** "রোগীরা আপনাকে যেভাবে দেখেন" — সবার তালিকার একই কার্ড; চাপলে রোগীদের বিস্তারিত (card.onOpen) */
@Composable
fun PatientViewPreview(card: ListingCardData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var collapsed by remember(card.type) { mutableStateOf(PreviewPrefs.collapsed(context, card.type)) }
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag("patient_preview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { collapsed = !collapsed; PreviewPrefs.set(context, card.type, collapsed) }.padding(vertical = 4.dp).testTag("patient_preview_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_person_outline, null, Modifier.size(18.dp), cs.primary)
            Spacer(Modifier.width(8.dp))
            Text(ProviderText.preview(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppIcon(R.drawable.ic_expand_more_round, if (collapsed) tr("Show", "দেখুন") else tr("Hide", "লুকান"), Modifier.size(20.dp).rotate(if (collapsed) 0f else 180f), cs.onSurfaceVariant)
        }
        if (!collapsed) ListingCard(card)
    }
}

/** এক চাপের সুইচের সারি */
@Composable
fun QuickSwitch(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit, busy: Boolean = false, tag: String? = null, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        }
    }
}

/** সুইচগুলোর সার্ভারের কাজ (CONTRACT.md "Changes"-এ নাম) */
object ProviderSwitches {
    private suspend fun call(action: String, body: JSONObject): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    /** open: true/false = হাতে খোলা/বন্ধ (পরের নির্ধারিত বদল পর্যন্ত), null = সময় অনুযায়ী */
    // ফার্মেসি/ল্যাবের id = মালিকের uid — সার্ভার নিজে নেয়, id পাঠানো হয় না (backend/docs/LISTINGS.md "Provider side")
    suspend fun pharmacyOpen(open: Boolean?): JSONObject = call("pharmacy_open_override", JSONObject().put("open", open ?: JSONObject.NULL))
    suspend fun pharmacyDelivery(on: Boolean): JSONObject = call("pharmacy_delivery_set", JSONObject().put("delivery", on))
    suspend fun labOpen(open: Boolean?): JSONObject = call("lab_open_override", JSONObject().put("open", open ?: JSONObject.NULL))
    suspend fun labHome(on: Boolean): JSONObject = call("lab_home_collection_set", JSONObject().put("homeCollection", on))

    /** ডাক্তার/সহকারী: আজ এই চেম্বার বন্ধ (closed) বা চলছে — বন্ধ দিন nextSlot বাদ দেয়, বুকিং নেয় না */
    suspend fun chamberToday(doctorUid: String?, chamberIndex: Int, closed: Boolean): JSONObject =
        call("chamber_day_set", JSONObject().put("chamberIndex", chamberIndex).put("closed", closed).apply { if (doctorUid != null) put("doctorUid", doctorUid) })

    /** নিজের (বা সহকারী হলে ডাক্তারের) কার্ড — তালিকার একই ঘর — আর চেম্বারপ্রতি আজ বসেন/বন্ধ */
    suspend fun chamberDays(doctorUid: String?): Provider? {
        val o = call("chamber_days", JSONObject().apply { if (doctorUid != null) put("doctorUid", doctorUid) })
        val card = o.optJSONObject("card")?.let { Appointments.parseProvider(it) } ?: return null
        val a = o.optJSONArray("items") ?: org.json.JSONArray()
        val closed = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { it.optInt("chamberIndex", -1) to it.optBoolean("closedToday") }
        return card.copy(chambers = card.chambers.mapIndexed { i, c -> c.copy(closedToday = closed[i] ?: c.closedToday) })
    }

    /** সার্ভার কাজটা চেনে না (পুরোনো সার্ভার) — সুইচ লুকানো */
    fun unknown(e: Throwable): Boolean {
        if (e !is ServerError) return false
        val c = e.code.orEmpty()
        val m = e.message.orEmpty()
        // সার্ভারের CommunityController: কাজ না চিনলে 400 "Unknown request. Please update the app." / "অজানা অনুরোধ…"
        return c == "unknown_action" || m.contains("Unknown request", true) || m.contains("অজানা অনুরোধ")
    }
}

/**
 * সুইচ চালানোর ছোট সাহায্য: চাপলে সাথে সাথে নতুন মান দেখায় (কার্ডও), সার্ভার না বললে আগেরটায় ফেরে।
 * সার্ভার কাজটা না চিনলে hidden = true (সুইচ সরে যায়)।
 */
class SwitchState<T>(initial: T) {
    var value by mutableStateOf(initial)
    var busy by mutableStateOf(false)
    var hidden by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun <T> rememberSwitch(key: Any?, initial: T): SwitchState<T> = remember(key) { SwitchState(initial) }

fun <T> SwitchState<T>.toggle(scope: kotlinx.coroutines.CoroutineScope, next: T, block: suspend () -> Unit, onDone: () -> Unit = {}) {
    if (busy) return
    val prev = value
    value = next
    busy = true
    error = null
    scope.launch {
        runCatching { block() }
            .onSuccess { onDone() }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                value = prev
                if (ProviderSwitches.unknown(e)) hidden = true else error = e.message
            }
        busy = false
    }
}

/**
 * ব্লাড ব্যাংকের আট গ্রুপ: আছে / কম / নেই (আবার চাপলে "জানা নেই")। "স্টক সেভ করুন" বদল থাকলে,
 * "সব ঠিক আছে — এখন হালনাগাদ" একই অবস্থা আবার পাঠায় (শুধু হালনাগাদের সময় বদলায়)।
 */
@Composable
fun StockEditor(b: BloodBank, onSaved: (ListingExtras) -> Unit) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var groups by remember(b.id, b.extras.stockUpdatedAt) { mutableStateOf<Map<String, String?>>(b.extras.stock) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val changed = groups.filterValues { it != null } != b.extras.stock
    fun send() {
        busy = true
        errorMsg = null
        scope.launch {
            runCatching { if (changed) BloodBanks.setStock(b.id, groups) else BloodBanks.refreshStock(b.id) }
                .onSuccess { onSaved(it) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; errorMsg = e.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("stock_editor"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ListingText.bloodStock(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Listing.stockNote(b.extras.stockUpdatedAt, b.extras.stockStale())?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (b.extras.stockStale()) cs.error else cs.onSurfaceVariant)
        }
        ListingExtras.GROUPS.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ListingExtras.STOCK_STATES.forEach { st ->
                        val on = groups[g] == st
                        FilterChip(
                            selected = on,
                            onClick = { groups = groups + (g to if (on) null else st) },
                            label = { Text(ListingText.stock(st)) },
                            enabled = !busy,
                            modifier = Modifier.testTag("stock_${g}_$st"),
                        )
                    }
                }
            }
        }
        ErrorText(errorMsg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (changed) {
                Button(onClick = { send() }, enabled = !busy, modifier = Modifier.testTag("stock_save")) { ButtonContent(busy, ProviderText.saveStock(), R.drawable.ic_done) }
            } else {
                OutlinedButton(onClick = { send() }, enabled = !busy, modifier = Modifier.testTag("stock_refresh")) { ButtonContent(busy, ProviderText.stockAllGood(), R.drawable.ic_sync) }
            }
        }
    }
}

// ---------------- ধরনপ্রতি উপরের অংশ ----------------

/** ফার্মেসি: রোগীরা যেভাবে দেখেন + খোলা/বন্ধ এখন + ডেলিভারি */
@Composable
fun PharmacyProviderTop(p: Pharmacy, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val open = rememberSwitch(p, pharmacyOpen(p) ?: true)
    val delivery = rememberSwitch(p, p.extras.delivery ?: p.delivery)
    var detail by remember { mutableStateOf(false) }
    val cur = p.copy(
        openNow = open.value, closedUntil = if (open.value) 0 else p.closedUntil, delivery = delivery.value,
        extras = p.extras.copy(openNow = open.value, delivery = delivery.value || p.online),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("provider_top"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PatientViewPreview(pharmacyCard(cur, { detail = true }, {}, {}, null))
        if (!open.hidden) QuickSwitch(
            if (open.value) ProviderText.openNow() else ProviderText.closedNow(), ProviderText.overrideNote(), open.value,
            { v -> open.toggle(scope, v, { ProviderSwitches.pharmacyOpen(v) }, onChanged) }, open.busy, "sw_open",
        )
        if (!delivery.hidden) QuickSwitch(
            if (delivery.value) ProviderText.deliveryOn() else ProviderText.deliveryOff(), null, delivery.value,
            { v -> delivery.toggle(scope, v, { ProviderSwitches.pharmacyDelivery(v) }, onChanged) }, delivery.busy, "sw_delivery",
        )
        ErrorText(open.error ?: delivery.error)
    }
    if (detail) ListingDetail(pharmacyDetail(cur, false, {}, {}, null, null), { detail = false })
}

/** ল্যাব: রোগীরা যেভাবে দেখেন + খোলা/বন্ধ এখন + বাসা থেকে নমুনা */
@Composable
fun LabProviderTop(l: Lab, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val open = rememberSwitch(l, l.extras.openNow ?: true)
    val home = rememberSwitch(l, labHome(l))
    var detail by remember { mutableStateOf(false) }
    val cur = l.copy(homeCollection = home.value, extras = l.extras.copy(openNow = if (open.hidden) l.extras.openNow else open.value, homeCollection = home.value))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("provider_top"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PatientViewPreview(labCard(cur, { detail = true }, {}, {}, null))
        if (!open.hidden) QuickSwitch(
            if (open.value) ProviderText.openNow() else ProviderText.closedNow(), ProviderText.overrideNote(), open.value,
            { v -> open.toggle(scope, v, { ProviderSwitches.labOpen(v) }, onChanged) }, open.busy, "sw_open",
        )
        if (!home.hidden) QuickSwitch(
            if (home.value) ProviderText.homeOn() else ProviderText.homeOff(), null, home.value,
            { v -> home.toggle(scope, v, { ProviderSwitches.labHome(v) }, onChanged) }, home.busy, "sw_home",
        )
        ErrorText(open.error ?: home.error)
    }
    if (detail) LabDetailScreen(cur, {}, { detail = false }, preview = true)
}

/**
 * ডাক্তার (আমার চেম্বার › বুকিং-এর উপরে): রোগীরা যেভাবে দেখেন + চেম্বারপ্রতি "আজ সিরিয়াল চলছে / আজ বন্ধ"
 * (আজ যে চেম্বারে বসার কথা, বা আজ বন্ধ দেওয়া আছে)। বন্ধ দিলে তালিকার "পরের সিরিয়াল" আজকে বাদ দেয় (nextSlot)।
 */
@Composable
fun DoctorProviderTop(doctorUid: String? = null) {
    var me by remember { mutableStateOf<Provider?>(null) }
    var reload by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(reload) {
        // নতুন সার্ভার: chamber_days (কার্ড + আজ বন্ধ); পুরোনো সার্ভারে provider_me (তখন সুইচ চাপলে সরে যায়)
        val p = runCatching { ProviderSwitches.chamberDays(doctorUid) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            ?: if (doctorUid == null) runCatching { Appointments.mine() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null } else null
        if (p != null) me = p
    }
    val p = me ?: return
    DoctorProviderTopContent(p, doctorUid) { reload++ }
}

@Composable
fun DoctorProviderTopContent(p: Provider, doctorUid: String? = null, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var closed by remember(p) { mutableStateOf(p.chambers.map { it.closedToday }) }
    var busy by remember { mutableStateOf<Int?>(null) }
    var hidden by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(false) }
    val cur = p.copy(chambers = p.chambers.mapIndexed { i, c -> c.copy(closedToday = closed.getOrElse(i) { false }) }, extras = p.extras.copy(nextSlot = null).let { x -> if (closed == p.chambers.map { it.closedToday }) p.extras else x })
    Column(Modifier.fillMaxWidth().testTag("provider_top"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PatientViewPreview(providerCard(cur, CareFilter(), onOpen = { detail = true }, onBook = {}))
        if (!hidden) {
            p.chambers.forEachIndexed { i, c ->
                val st = remember(c) { ChamberClock.state(c) }
                val today = st.kind == ChamberStateKind.NOW || st.kind == ChamberStateKind.TODAY || c.closedToday
                if (!today) return@forEachIndexed
                val isClosed = closed.getOrElse(i) { false }
                QuickSwitch(
                    if (isClosed) ProviderText.todayClosed() else ProviderText.todaySerial(),
                    ChamberClock.shortName(c) + if (!isClosed && st.text.isNotBlank()) " · " + st.text else "",
                    !isClosed,
                    { on ->
                        if (busy != null) return@QuickSwitch
                        val prev = closed
                        closed = closed.toMutableList().also { it[i] = !on }
                        busy = i
                        errorMsg = null
                        scope.launch {
                            runCatching { ProviderSwitches.chamberToday(doctorUid, i, !on) }
                                .onSuccess { onChanged() }
                                .onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    closed = prev
                                    if (ProviderSwitches.unknown(e)) hidden = true else errorMsg = e.message
                                }
                            busy = null
                        }
                    },
                    busy = busy == i, tag = "sw_chamber_$i",
                )
            }
        }
        ErrorText(errorMsg)
    }
    if (detail) ProviderDetailDialog(cur, Profile(), { detail = false }, {}, preview = true)
}
