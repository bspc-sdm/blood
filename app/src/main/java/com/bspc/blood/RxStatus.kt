package com.bspc.blood

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// প্রেসক্রিপশন এখন কোন অবস্থায় — সার্ভারের state (backend/docs/RX-STATE.md): তালিকার কার্ডে রঙিন চিপ + পরীক্ষা/ফলো-আপের
// ছোট লাইন, ট্যাব চলমান/পূর্বের/সব, ভেতরে টাইমলাইন। নিয়ম সার্ভারে; এখানে শুধু চাবি → লেখা (ওয়েবের rxstate.ts-এর মতো)।
// পুরোনো সার্ভারে state নেই: বাতিল হলে "পূর্বের", নইলে "চলমান", চিপ শুধু বাতিলে।

data class RxStateMed(val state: String, val bought: Int, val total: Int, val pharmacyName: String, val mode: String)
data class RxStateLab(val state: String, val orders: Int, val reported: Int, val labName: String, val reviewed: Boolean)
data class RxStep(val kind: String, val at: Long, val name: String, val mode: String, val count: Int, val ahead: Boolean)

data class RxState(
    /** "active" | "past" */
    val phase: String,
    val primary: String,
    /** primary | good | info | error | neutral | muted */
    val tone: String,
    val cancelled: Boolean,
    val cancelledAt: Long?,
    val medicine: RxStateMed?,
    val lab: RxStateLab?,
    val followUpDate: Long?,
    val lastActivityAt: Long,
    /** শুধু rx_get-এ */
    val timeline: List<RxStep>? = null,
) {
    companion object {
        private fun JSONObject.longOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k).takeIf { it > 0 }

        fun parse(o: JSONObject?): RxState? {
            if (o == null) return null
            val m = o.optJSONObject("medicine")
            val l = o.optJSONObject("lab")
            val tl = o.optJSONArray("timeline")
            return RxState(
                o.str("phase").ifBlank { "active" }, o.str("primary"), o.str("tone").ifBlank { "neutral" }, o.optBoolean("cancelled"),
                o.longOrNull("cancelledAt"),
                m?.let { RxStateMed(it.str("state"), it.optInt("bought"), it.optInt("total"), it.str("pharmacyName"), it.str("mode")) },
                l?.let { RxStateLab(it.str("state"), it.optInt("orders"), it.optInt("reported"), it.str("labName"), it.optBoolean("reviewed")) },
                o.longOrNull("followUpDate"), o.optLong("lastActivityAt"),
                tl?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                        RxStep(it.str("kind"), it.optLong("at"), it.str("name"), it.str("mode"), it.optInt("count"), it.optBoolean("ahead"))
                    }
                },
            )
        }
    }
}

/** ট্যাব: চলমান / পূর্বের / সব */
enum class RxTab { ACTIVE, PAST, ALL }

object RxStateText {
    /** চলমান না পূর্বের — পুরোনো সার্ভারে বাতিল হলে পূর্বের */
    fun past(rx: Rx): Boolean = rx.state?.let { it.phase == "past" } ?: rx.cancelled

    fun inTab(rx: Rx, tab: RxTab): Boolean = when (tab) {
        RxTab.ALL -> true
        RxTab.PAST -> past(rx)
        RxTab.ACTIVE -> !past(rx)
    }

    fun tabLabel(tab: RxTab, n: Int): String = when (tab) {
        RxTab.ACTIVE -> tr("Ongoing", "চলমান")
        RxTab.PAST -> tr("Previous", "পূর্বের")
        RxTab.ALL -> tr("All", "সব")
    } + " (" + num(n) + ")"

    fun medText(m: RxStateMed): String = when (m.state) {
        "out" -> tr("On the way", "ডেলিভারির পথে")
        "ready" -> tr("Medicines ready — collect at the shop", "ওষুধ তৈরি — দোকান থেকে নিন")
        "accepted" -> tr("Pharmacy accepted", "ফার্মেসি গ্রহণ করেছে")
        "sent" -> tr("Sent to pharmacy — waiting for reply", "ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা")
        "running_out" -> tr("Medicines running out", "ওষুধ শেষ হয়ে আসছে")
        "all_bought" -> tr("All medicines bought", "সব ওষুধ কেনা হয়েছে")
        "declined" -> tr("Pharmacy couldn't give — send to another", "ফার্মেসি দিতে পারেনি — অন্য ফার্মেসিতে পাঠান")
        "partial" -> tr("Partly bought (${m.bought}/${m.total})", "আংশিক কেনা (${num(m.bought)}/${num(m.total)})")
        else -> tr("Medicines not bought", "ওষুধ কেনা হয়নি")
    }

    fun labText(l: RxStateLab): String {
        val base = when (l.state) {
            "sent" -> tr("Tests sent to the lab", "ল্যাবে পাঠানো")
            "received" -> tr("Sample taken", "নমুনা নেওয়া হয়েছে")
            "reported" -> tr("Report ready", "রিপোর্ট এসেছে")
            else -> tr("Tests pending", "পরীক্ষা বাকি")
        }
        val parts = mutableListOf(base)
        if (l.state != "reported" && l.reported > 0) parts += tr("${l.reported}/${l.orders} reports in", "${num(l.reported)}/${num(l.orders)}টা রিপোর্ট এসেছে")
        if (l.state == "reported" && l.reviewed) parts += tr("Doctor checked", "ডাক্তার দেখেছেন")
        return parts.joinToString(" · ")
    }

    fun followUpText(d: Long): String = tr("Follow-up: ", "ফলো-আপ: ") + Rules.formatDate(d)

    val cancelledText: String get() = tr("Cancelled by the doctor", "বাতিল করেছেন ডাক্তার")

    /** কার্ডের রঙিন চিপের এক লাইন (অগ্রাধিকার সার্ভারের primary-তে) আর রং; কিছু না দেখানোর হলে null */
    fun primary(rx: Rx): Pair<String, String>? {
        val s = rx.state ?: return if (rx.cancelled) cancelledText to "muted" else null
        if (s.cancelled) return cancelledText to "muted"
        val m = s.medicine
        val l = s.lab
        return when {
            m != null && s.primary == m.state -> medText(m) to s.tone
            l != null && s.primary == "lab_" + l.state -> labText(l) to s.tone
            s.primary == "follow_up" && s.followUpDate != null -> followUpText(s.followUpDate) to s.tone
            else -> tr("Prescribed", "লেখা হয়েছে") to s.tone
        }
    }

    /** চিপের নিচের ছোট লাইন: পরীক্ষা আর ফলো-আপ (মূল লাইনে না গেলে) */
    fun secondary(rx: Rx): List<Pair<Int, String>> {
        val s = rx.state ?: return emptyList()
        if (s.cancelled) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        s.lab?.let { if (s.primary != "lab_" + it.state) out += R.drawable.ic_list_alt to labText(it) }
        s.followUpDate?.let { if (s.primary != "follow_up") out += R.drawable.ic_calendar_month to followUpText(it) }
        return out
    }

    fun stepText(st: RxStep): String {
        val at = if (st.name.isNotBlank()) " — " + st.name else ""
        return when (st.kind) {
            "written" -> tr("Prescription written", "প্রেসক্রিপশন লেখা হয়েছে")
            "pharmacy_sent" -> (if (st.mode == "online") tr("Online order sent", "অনলাইনে অর্ডার পাঠানো") else tr("Sent to pharmacy", "ফার্মেসিতে পাঠানো")) + at
            "pharmacy_accepted" -> tr("Pharmacy accepted", "ফার্মেসি গ্রহণ করেছে") + at
            "pharmacy_ready" -> tr("Medicines ready at the shop", "দোকানে ওষুধ তৈরি") + at
            "pharmacy_out" -> tr("Out for delivery", "ডেলিভারির পথে") + at
            "pharmacy_delivered" -> tr("Delivered", "পৌঁছে দেওয়া হয়েছে") + at
            "pharmacy_cancelled" -> tr("Pharmacy couldn't give", "ফার্মেসি দিতে পারেনি") + at
            "pharmacy_withdrawn" -> tr("You took it back", "ফিরিয়ে নিয়েছেন") + at
            "bought" -> tr("Bought ${st.count} medicine(s)", "${num(st.count)}টা ওষুধ কেনা হয়েছে") + at
            "lab_sent" -> tr("Tests sent to the lab", "ল্যাবে পরীক্ষা পাঠানো") + at
            "lab_received" -> tr("Sample taken", "নমুনা নেওয়া হয়েছে") + at
            "lab_reported" -> tr("Report ready", "রিপোর্ট এসেছে") + at
            "lab_cancelled" -> tr("Lab couldn't do it", "ল্যাব করতে পারেনি") + at
            "reviewed" -> tr("Doctor checked the report", "ডাক্তার রিপোর্ট দেখেছেন")
            "cancelled" -> cancelledText
            "follow_up" -> if (st.ahead) tr("Follow-up visit", "ফলো-আপে দেখানোর দিন") else tr("Follow-up day", "ফলো-আপের দিন")
            else -> st.kind
        }
    }

    /** ধাপের তারিখ: ফলো-আপ ঢাকার দিন (UTC মধ্যরাত), বাকিগুলো আসল সময় → ঢাকার দিন */
    fun stepDate(st: RxStep): String = Rules.formatDate(if (st.kind == "follow_up") st.at else st.at + DHAKA_MS)

    private const val DHAKA_MS = 6 * 3600 * 1000L
}

/** চিপের রং থিম থেকে (আলো/অন্ধকার দুটোতেই পড়া যায়) */
@Composable
private fun toneColors(tone: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        "primary" -> cs.primaryContainer to cs.onPrimaryContainer
        "good" -> cs.tertiaryContainer to cs.onTertiaryContainer
        "info" -> cs.secondaryContainer to cs.onSecondaryContainer
        "error" -> cs.errorContainer to cs.onErrorContainer
        "muted" -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
}

/** তালিকার কার্ডে আর ভেতরে: রঙিন চিপে এক লাইন + নিচে ছোট লাইন */
@Composable
fun RxStatusLine(rx: Rx) {
    val main = RxStateText.primary(rx) ?: return
    val (bg, fg) = toneColors(main.second)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("rx-status-chip")) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(fg, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(main.first, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        RxStateText.secondary(rx).forEach { (icon, text) -> IconLine(icon, text) }
    }
}

/** ভেতরে উপরে: এখন কোন অবস্থায় + কী কী হয়েছে, তারিখসহ */
@Composable
fun RxTimelineCard(rx: Rx) {
    val s = rx.state ?: return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().testTag("rx-timeline"), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr("Where it is now", "এখন কোন অবস্থায়"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            RxStatusLine(rx)
            val steps = s.timeline.orEmpty()
            if (steps.isNotEmpty()) Spacer(Modifier.height(12.dp))
            steps.forEachIndexed { i, st ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    // বিন্দু আর নিচে দাগ (পরের ধাপ পর্যন্ত — লেখা দুই লাইন হলেও)
                    Column(Modifier.width(16.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val dot = if (st.kind == "cancelled") cs.error else cs.primary
                        Box(
                            Modifier.padding(top = 5.dp).size(10.dp).let {
                                if (st.ahead) it.border(2.dp, dot, CircleShape) else it.background(dot, CircleShape)
                            },
                        )
                        if (i < steps.lastIndex) Box(Modifier.padding(top = 2.dp).width(2.dp).weight(1f).background(cs.outlineVariant))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        RxStateText.stepText(st), style = MaterialTheme.typography.bodyMedium,
                        color = if (st.ahead) cs.onSurfaceVariant else cs.onSurface,
                        modifier = Modifier.weight(1f).padding(bottom = if (i < steps.lastIndex) 10.dp else 0.dp).testTag("rx-step"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(RxStateText.stepDate(st), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
