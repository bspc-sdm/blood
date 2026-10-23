@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ------------------------------------------------------------
// সেবার পাঁচ তালিকা (অ্যাম্বুলেন্স, ডাক্তার, ফার্মেসি, ব্লাড ব্যাংক, ল্যাব) — এক ছাঁচ (মালিকের অনুমোদন, ২৩ অক্টোবর ২০২৬;
// ওয়েবের সাথে একই লেখা)। উপরে ছোট অংশ: খোঁজার ঘর, এলাকার চিপ, সাজানো, এক সারির ফিল্টার চিপ। কার্ড সবার এক রকম:
//   [ছবি] নাম (২ লাইন পর্যন্ত) ✓                ⋮
//         মূল লাইন: তিনটা দরকারি তথ্য "·" দিয়ে
//         ★৪.৬ (৩১) · ব্যাজ
//   [মূল বাটন]                           [কল] [মেসেজ]
// ফোন নম্বর কার্ডে নয় — বিস্তারিতে। বিস্তারিত সবার এক রকম: মাথা → মূল তথ্য → ধরনের নিজের অংশ → ঠিকানা ও ফোন → রেটিং,
// নিচে আটকানো বার (মূল বাটন + কল + মেসেজ)।
// সার্ভারের নতুন ঘর (openNow, distanceKm, rating{avg,count}, nextSlot, stock …) না এলে null — আগের তথ্যেই চলে।
// ------------------------------------------------------------

enum class ListingType(val key: String) { AMBULANCE("ambulance"), DOCTOR("doctor"), PHARMACY("pharmacy"), BLOODBANK("bloodbank"), LAB("lab") }

enum class ListingSort(val wire: String) { NEAR("near"), RATING("rating"), OPEN("open") }

/** ডাক্তারের পরের সিরিয়াল (সার্ভারের nextSlot): label "আজ" / "কাল" / তারিখ */
data class NextSlot(val date: String, val label: String, val available: Boolean, val chamberIndex: Int? = null)

/** তালিকার প্রতিটা ঘরের নতুন (ঐচ্ছিক) তথ্য — না থাকলে null/খালি */
data class ListingExtras(
    val openNow: Boolean? = null,
    val opensAt: String = "",
    val closesAt: String = "",
    val is24h: Boolean? = null,
    val distanceKm: Double? = null,
    /** দূরত্ব উপজেলার কেন্দ্র ধরে (আসল জায়গা নয়) — "প্রায় ৩ কিমি" */
    val distanceApprox: Boolean = false,
    val ratingAvg: Double? = null,
    val ratingCount: Int = 0,
    val nextSlot: NextSlot? = null,
    /** ডাক্তার: টাকার অঙ্ক (০ = ফি নেই, null = জানা নেই) */
    val feeAmount: Long? = null,
    val fee: String = "",
    val systemLabel: String = "",
    val specialtyLabel: String = "",
    val homeCollection: Boolean? = null,
    val delivery: Boolean? = null,
    /** গ্রুপ → have / low / none (জানা না থাকলে গ্রুপটা নেই) */
    val stock: Map<String, String> = emptyMap(),
    val stockUpdatedAt: Long = 0,
    val stockStaleRaw: Boolean? = null,
    val verified: Boolean = false,
) {
    /** ৪৮ ঘণ্টার পুরনো স্টক — সার্ভারের stockStale, না থাকলে হিসাব করে */
    fun stockStale(now: Long = System.currentTimeMillis()): Boolean =
        stockStaleRaw ?: (stockUpdatedAt > 0 && now - stockUpdatedAt > STALE_MS)

    companion object {
        val GROUPS = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")
        val STOCK_STATES = listOf("have", "low", "none")
        const val STALE_MS = 48 * 3_600_000L

        private fun JSONObject.boolN(k: String): Boolean? = if (!has(k) || isNull(k)) null else (opt(k) as? Boolean)
        private fun JSONObject.dblN(k: String): Double? = if (!has(k) || isNull(k)) null else (opt(k) as? Number)?.toDouble()?.takeIf { !it.isNaN() }
        private fun JSONObject.txt(k: String): String = if (!has(k) || isNull(k)) "" else when (val v = opt(k)) {
            is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString()
            is String -> v
            else -> ""
        }

        /** মিলিসেকেন্ড, সেকেন্ড বা ISO তারিখ — যেটাই আসুক */
        fun time(v: Any?): Long = when (v) {
            is Number -> v.toLong().let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
            is String -> v.toLongOrNull()?.let { time(it) } ?: runCatching { java.time.Instant.parse(v).toEpochMilli() }
                .recoverCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrDefault(0L)
            else -> 0L
        }

        fun stock(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            return GROUPS.mapNotNull { g -> o.optString(g).takeIf { it in STOCK_STATES && !o.isNull(g) }?.let { g to it } }.toMap()
        }

        fun parse(o: JSONObject?): ListingExtras {
            if (o == null) return ListingExtras()
            // রেটিং: নতুন ratingSummary {avg, count} (null = কেউ দেননি); না এলে আগের rating (সংখ্যা) + ratingCount
            var avg: Double? = null
            var count = 0
            val rs = o.optJSONObject("ratingSummary")
            if (rs != null) {
                avg = rs.dblN("avg"); count = rs.optInt("count")
            } else {
                when (val r = o.opt("rating")) {
                    is JSONObject -> { avg = r.dblN("avg"); count = r.optInt("count") }
                    is Number -> { avg = r.toDouble(); count = o.optInt("ratingCount") }
                }
            }
            // ফি: feeAmount (টাকা; ০ = ফি নেই) আগে, নইলে পুরোনো fee লেখা
            val feeAmount = o.dblN("feeAmount")
            return ListingExtras(
                openNow = o.boolN("openNow"),
                opensAt = o.txt("opensAt"),
                closesAt = o.txt("closesAt"),
                is24h = o.boolN("is24h"),
                distanceKm = o.dblN("distanceKm"),
                ratingAvg = avg?.takeIf { count > 0 },
                ratingCount = if (avg != null) count else 0,
                distanceApprox = o.optBoolean("distanceApprox"),
                nextSlot = o.optJSONObject("nextSlot")?.let { s -> NextSlot(s.txt("date"), s.txt("label"), s.optBoolean("available"), if (s.has("chamberIndex") && !s.isNull("chamberIndex")) s.optInt("chamberIndex") else null) },
                feeAmount = feeAmount?.toLong(),
                fee = o.txt("fee"),
                systemLabel = o.txt("systemLabel"),
                specialtyLabel = o.txt("specialtyLabel"),
                homeCollection = o.boolN("homeCollection"),
                delivery = o.boolN("delivery"),
                stock = stock(o.optJSONObject("stock")),
                stockUpdatedAt = time(o.opt("stockUpdatedAt")),
                stockStaleRaw = o.boolN("stockStale"),
                verified = o.optBoolean("verified"),
            )
        }
    }
}

/** তালিকার লেখা — ওয়েবের সাথে হুবহু এক */
object ListingText {
    fun typeLabel(t: ListingType): String = when (t) {
        ListingType.AMBULANCE -> tr("Ambulance", "অ্যাম্বুলেন্স")
        ListingType.DOCTOR -> tr("Doctor", "ডাক্তার")
        ListingType.PHARMACY -> tr("Pharmacy", "ফার্মেসি")
        ListingType.BLOODBANK -> tr("Blood bank", "ব্লাড ব্যাংক")
        ListingType.LAB -> tr("Lab", "ল্যাব")
    }

    fun searchHint(t: ListingType): String = when (t) {
        ListingType.AMBULANCE -> tr("Ambulance name or area", "অ্যাম্বুলেন্সের নাম বা এলাকা")
        ListingType.DOCTOR -> tr("Doctor name, specialty or chamber", "ডাক্তারের নাম, বিশেষজ্ঞতা বা চেম্বার")
        ListingType.PHARMACY -> tr("Pharmacy name or area", "ফার্মেসির নাম বা এলাকা")
        ListingType.BLOODBANK -> tr("Blood bank name or area", "ব্লাড ব্যাংকের নাম বা এলাকা")
        ListingType.LAB -> tr("Lab name or area", "ল্যাবের নাম বা এলাকা")
    }

    fun emptyLine(t: ListingType): String = when (t) {
        ListingType.AMBULANCE -> tr("No ambulance in this area yet", "এই এলাকায় এখনো কোনো অ্যাম্বুলেন্স নেই")
        ListingType.DOCTOR -> tr("No doctor in this area yet", "এই এলাকায় এখনো কোনো ডাক্তার নেই")
        ListingType.PHARMACY -> tr("No pharmacy in this area yet", "এই এলাকায় এখনো কোনো ফার্মেসি নেই")
        ListingType.BLOODBANK -> tr("No blood bank in this area yet", "এই এলাকায় এখনো কোনো ব্লাড ব্যাংক নেই")
        ListingType.LAB -> tr("No lab in this area yet", "এই এলাকায় এখনো কোনো ল্যাব নেই")
    }

    fun noMatch(): String = tr("Nothing matches this search", "এই খোঁজে কিছু মেলেনি")
    fun changeArea(): String = tr("Change area", "এলাকা বদলান")
    fun clear(): String = tr("Clear", "মুছুন")
    fun filter(): String = tr("Filter", "ফিল্টার")
    fun sortBy(): String = tr("Sort", "সাজান")

    fun sort(s: ListingSort): String = when (s) {
        ListingSort.NEAR -> tr("Nearest", "নিকটতম")
        ListingSort.RATING -> tr("Rating", "রেটিং")
        ListingSort.OPEN -> tr("Open now first", "এখন খোলা আগে")
    }

    fun open(o: Boolean): String = if (o) tr("Open", "খোলা") else tr("Closed", "বন্ধ")
    fun delivery(d: Boolean): String = if (d) tr("Delivery", "ডেলিভারি আছে") else tr("No delivery", "ডেলিভারি নেই")
    fun homeSample(): String = tr("Sample from home", "বাসা থেকে নমুনা")
    fun hours24(): String = tr("24 hours", "২৪ ঘণ্টা")
    fun discount(pct: Int): String = tr("$pct% off", "${num(pct)}% ছাড়")
    fun available(): String = tr("Available", "খালি আছে")
    fun callToCheck(): String = tr("Call to check", "ফোনে জেনে নিন")
    fun onTrip(): String = tr("On a trip", "ট্রিপে আছে")
    fun slotToday(): String = tr("Serial available today", "আজ সিরিয়াল আছে")
    fun slotNext(label: String): String = tr("Next serial: ", "পরের সিরিয়াল: ") + slotLabel(label)
    fun stock(state: String): String = when (state) {
        "have" -> tr("In stock", "আছে")
        "low" -> tr("Low", "কম")
        else -> tr("None", "নেই")
    }
    fun updated(ago: String): String = tr("Updated: ", "হালনাগাদ: ") + ago
    fun stale(): String = tr("Old information", "পুরনো তথ্য")
    fun noStock(): String = tr("Stock not shared yet", "স্টকের খবর এখনো দেওয়া হয়নি")

    // মূল বাটন
    fun book(): String = tr("Get a serial", "সিরিয়াল নিন")
    fun sendRx(): String = tr("Send prescription", "প্রেসক্রিপশন পাঠান")
    fun sendTests(): String = tr("Send tests", "পরীক্ষা পাঠান")
    fun call(): String = tr("Call", "ফোন করুন")

    // বিস্তারিত
    fun details(): String = tr("Details", "বিস্তারিত")
    fun report(): String = tr("Report", "রিপোর্ট")
    fun keyFacts(): String = tr("Key facts", "মূল তথ্য")
    fun addressPhone(): String = tr("Address and phone", "ঠিকানা ও ফোন")
    fun ratings(): String = tr("Ratings", "রেটিং")
    fun noRating(): String = tr("No ratings yet", "এখনো রেটিং নেই")
    fun rate(): String = tr("Rate", "রেটিং দিন")
    fun copy(): String = tr("Copy", "কপি")
    fun copied(): String = tr("Copied", "কপি হয়েছে")
    fun bloodStock(): String = tr("Blood stock", "রক্তের স্টক")

    /** সার্ভারের label ("আজ"/"কাল"/তারিখ) — ইংরেজিতে today/tomorrow */
    fun slotLabel(label: String): String = when (label.trim()) {
        "আজ", "today", "Today" -> tr("today", "আজ")
        "কাল", "আগামীকাল", "tomorrow", "Tomorrow" -> tr("tomorrow", "কাল")
        else -> num(label)
    }
}

/** তালিকার হিসাব — কম্পোজ ছাড়া (ইউনিট টেস্ট এখানে) */
object Listing {
    /** "★৪.৬ (৩১)" — রেটিং না থাকলে null */
    fun rating(avg: Double?, count: Int): String? =
        if (avg == null || count <= 0) null else "★" + num(String.format(java.util.Locale.US, "%.1f", avg)) + " (" + num(count) + ")"

    /** "৩ কিমি" / "১ কিমির কম"; উপজেলার কেন্দ্র ধরে হলে "প্রায় ৩ কিমি" */
    fun km(d: Double?, approx: Boolean = false): String? {
        if (d == null || d.isNaN()) return null
        if (!approx) return Rules.formatKm(d)
        val n = Math.round(d).coerceAtLeast(1L)
        return tr("about $n km", "প্রায় ${num(n)} কিমি")
    }

    /** feeAmount (টাকা) থেকে "ফি ৳৫০০"; ০ = "ফি নেই" */
    fun feeAmount(n: Long?): String? = when {
        n == null -> null
        n <= 0L -> tr("No fee", "ফি নেই")
        else -> tr("Fee ", "ফি ") + "৳" + num(String.format(java.util.Locale.US, "%,d", n))
    }

    /** ফি-র লেখা থেকে "ফি ৳৫০০" (টাকার অঙ্ক না পেলে লেখাটাই, বাংলা অঙ্কে) */
    fun fee(raw: String, noFee: Boolean = false): String? {
        if (noFee) return tr("No fee", "ফি নেই")
        if (raw.isBlank()) return null
        val ascii = raw.map { ch -> if (ch in '০'..'৯') ('0' + (ch - '০')) else ch }.joinToString("").replace(Regex("(\\d),(?=\\d{3})"), "$1")
        val n = Regex("\\d+").find(ascii)?.value?.toLongOrNull() ?: return tr("Fee ", "ফি ") + num(raw)
        return tr("Fee ", "ফি ") + "৳" + num(String.format(java.util.Locale.US, "%,d", n))
    }

    /** মূল লাইন: খালি অংশ বাদ, "·" দিয়ে */
    fun keyLine(vararg parts: String?): String = parts.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString(" · ")

    fun slotBadge(s: NextSlot?): String? {
        if (s == null || s.label.isBlank()) return null
        val today = s.label.trim() == "আজ" || s.label.trim().equals("today", true)
        return if (today && s.available) ListingText.slotToday() else ListingText.slotNext(s.label)
    }

    /** স্টকের পাশে: "হালনাগাদ: ২ ঘণ্টা আগে" বা "পুরনো তথ্য" */
    fun stockNote(at: Long, stale: Boolean): String? = when {
        at <= 0L -> null
        stale -> ListingText.stale()
        else -> ListingText.updated(timeAgo(at))
    }

    /** খোঁজা: নাম/এলাকায় (ছোট-বড় হাতের আর বাংলা-ইংরেজি অঙ্ক না দেখে) */
    fun matches(q: String, vararg fields: String?): Boolean {
        val n = norm(q)
        if (n.isEmpty()) return true
        return fields.any { it != null && norm(it).contains(n) }
    }

    private fun norm(s: String): String =
        s.trim().lowercase().map { ch -> if (ch in '০'..'৯') ('0' + (ch - '০')) else ch }.joinToString("").replace(Regex("\\s+"), " ")

    /**
     * সাজানো (সার্ভারও sort পায়; পুরোনো সার্ভারে এখানেই) — একই মানের ঘর আগের ক্রমে থাকে।
     * নিকটতম: দূরত্ব না জানা শেষে; রেটিং: গড় বেশি আগে, তারপর বেশি জন; এখন খোলা আগে: খোলা → জানা নেই → বন্ধ।
     */
    fun <T> sorted(items: List<T>, sort: ListingSort?, km: (T) -> Double?, rating: (T) -> Pair<Double, Int>?, open: (T) -> Boolean?): List<T> = when (sort) {
        null -> items
        ListingSort.NEAR -> items.sortedWith(compareBy<T> { km(it) == null }.thenBy { km(it) ?: 0.0 })
        ListingSort.RATING -> items.sortedWith(
            compareBy<T> { rating(it)?.takeIf { r -> r.second > 0 } == null }
                .thenByDescending { rating(it)?.first ?: 0.0 }
                .thenByDescending { rating(it)?.second ?: 0 },
        )
        ListingSort.OPEN -> items.sortedWith(compareBy<T> { when (open(it)) { true -> 0; null -> 1; false -> 2 } })
    }

    /** কোন সাজানো আগে থেকে: জায়গা জানা থাকলে নিকটতম, নইলে সার্ভারের ক্রম */
    fun defaultSort(locationKnown: Boolean): ListingSort? = if (locationKnown) ListingSort.NEAR else null
}

enum class BadgeTone { GOOD, NEUTRAL, WARN, INFO }

data class ListingBadge(val text: String, val tone: BadgeTone = BadgeTone.NEUTRAL)

/** যোগাযোগের দুই আইকন (ContactActions): phone = কলের মেনুর "ফোন কল"; মেসেজ শুধু onMessage দিলে */
data class ListingContact(
    val phone: String?,
    val onPhone: (() -> Unit)? = null,
    val ctx: String = "",
    val refId: String = "",
    val peerName: String = "",
    val onMessage: (() -> Unit)? = null,
)

data class ListingCardData(
    val id: String,
    val type: ListingType,
    val name: String,
    @DrawableRes val icon: Int,
    val keyLine: String,
    val onOpen: () -> Unit,
    val photo: String? = null,
    /** ব্লাড ব্যাংক: লালচে আইকন */
    val danger: Boolean = false,
    val verified: Boolean = false,
    /** নামের পাশে ছোট লেবেল (চিকিৎসা পদ্ধতি) */
    val nameTag: String? = null,
    val ratingText: String? = null,
    val badges: List<ListingBadge> = emptyList(),
    /** ব্লাড ব্যাংক: গ্রুপ → have/low/none */
    val stock: Map<String, String>? = null,
    val stockNote: ListingBadge? = null,
    val primary: CardAction? = null,
    val contact: ListingContact? = null,
    val onReport: (() -> Unit)? = null,
    /** ছাঁকনিতে মেলা চেম্বার ইত্যাদি — মূল লাইনের নিচে এক লাইন (ঐচ্ছিক) */
    val note: String? = null,
)

// ---------------- উপরের অংশ ----------------

/** চিপ সারির একটা চিপ: selected = চালু ছাঁকনি */
data class ListingChip(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val tag: String? = null,
    val count: Int = 0,
    @DrawableRes val icon: Int? = null,
)

/** ডানে (আর সরানো থাকলে বাঁয়ে) ঝাপসা — বোঝা যায় সারি সরানো যায় */
fun Modifier.edgeFade(scroll: ScrollState, width: androidx.compose.ui.unit.Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.value < scroll.maxValue) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - w, endX = size.width),
                blendMode = BlendMode.DstOut,
            )
        }
        if (scroll.value > 0) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w),
                blendMode = BlendMode.DstOut,
            )
        }
    }

/** তালিকার পাশের ফাঁক (১৬dp) — উপরের চিপ সারি পুরো চওড়া, বাকি সব এই ফাঁকে */
val LISTING_GUTTER = 16.dp

/**
 * সেবার ধরন বদলানো (অ্যাম্বুলেন্স / ডাক্তার / ফার্মেসি / ব্লাড ব্যাংক / ল্যাব): এক সারির ছোট চিপ, সরানো যায়, ডানে ঝাপসা।
 * বেছে নেওয়াটা পুরো দেখা না গেলে যতটুকু দরকার ততটুকু সরে (মাঝখানে টেনে আনে না — প্রথমটা অকারণে কাটে না)।
 */
@Composable
fun ListingTypeTabs(types: List<ListingType>, selected: ListingType, onSelect: (ListingType) -> Unit) {
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bounds = remember(types.size) { HashMap<Int, Pair<Int, Int>>() }
    var viewport by remember { mutableStateOf(0) }
    val idx = types.indexOf(selected)
    LaunchedEffect(idx, viewport) {
        val b = bounds[idx] ?: return@LaunchedEffect
        if (viewport <= 0) return@LaunchedEffect
        // b.first: চিপের বাঁ দিক সারির ১৬dp ফাঁকের পর থেকে মাপা; সরানোর মাপে ফাঁকসহ
        val pad = with(density) { LISTING_GUTTER.roundToPx() }
        val left = b.first
        val right = b.first + b.second + 2 * pad
        val target = when {
            left < scroll.value -> left
            right > scroll.value + viewport -> right - viewport
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .androidxOnSize { viewport = it }
            .edgeFade(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = LISTING_GUTTER, vertical = 6.dp)
            .testTag("listing_types"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        types.forEachIndexed { i, t ->
            val sel = t == selected
            Surface(
                selected = sel,
                onClick = { onSelect(t) },
                shape = RoundedCornerShape(50),
                color = if (sel) cs.primary else cs.surfaceContainerHigh,
                contentColor = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .androidxOnPlaced { x, w -> bounds[i] = x to w }
                    .testTag("listing_type_" + t.key),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(listingIcon(t), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ListingText.typeLabel(t),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun Modifier.androidxOnSize(block: (Int) -> Unit): Modifier =
    this.onSizeChanged { block(it.width) }

private fun Modifier.androidxOnPlaced(block: (Int, Int) -> Unit): Modifier =
    this.onPlaced { c -> block(c.positionInParent().x.toInt(), c.size.width) }

@DrawableRes
fun listingIcon(t: ListingType): Int = when (t) {
    ListingType.AMBULANCE -> R.drawable.ic_ambulance
    ListingType.DOCTOR -> R.drawable.ic_stethoscope
    ListingType.PHARMACY -> R.drawable.ic_storefront
    ListingType.BLOODBANK -> R.drawable.ic_bloodtype
    ListingType.LAB -> R.drawable.ic_local_hospital
}

/**
 * উপরের ছোট অংশ: খোঁজার ঘর, তারপর এক সারি চিপ — [📍 এলাকা ▾] [সাজান ▾] [ছাঁকনি…] [মুছুন]।
 * সারি সরানো যায় (ডানে ঝাপসা), প্রথম চিপ কখনো কাটা নয় (১৬dp ফাঁক)। কোনো ছাঁকনি চালু থাকলে "মুছুন"।
 */
@Composable
fun ListingControls(
    type: ListingType,
    query: String,
    onQuery: (String) -> Unit,
    areaLabel: String,
    onArea: () -> Unit,
    sort: ListingSort?,
    sorts: List<ListingSort>,
    onSort: (ListingSort) -> Unit,
    chips: List<ListingChip> = emptyList(),
    onClear: (() -> Unit)? = null,
    chipsTag: String = "listing_chips",
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListingSearchField(query, onQuery, ListingText.searchHint(type), Modifier.padding(horizontal = LISTING_GUTTER))
        val scroll = rememberScrollState()
        Row(
            Modifier.fillMaxWidth().edgeFade(scroll).horizontalScroll(scroll).padding(horizontal = LISTING_GUTTER).testTag(chipsTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // এলাকা: চাপলে আজকের এলাকা বাছাই
            FilterChip(
                selected = false,
                onClick = onArea,
                label = { Text(areaLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)) },
                leadingIcon = { AppIcon(R.drawable.ic_location_on, null, Modifier.size(16.dp), MaterialTheme.colorScheme.primary) },
                trailingIcon = { AppIcon(R.drawable.ic_expand_more_round, ListingText.changeArea(), Modifier.size(16.dp)) },
                modifier = Modifier.testTag("listing_area"),
            )
            if (sorts.isNotEmpty()) SortChip(sort, sorts, onSort)
            chips.forEach { c ->
                val ic = c.icon ?: if (c.selected) R.drawable.ic_done else null
                val lead: (@Composable () -> Unit)? = if (ic != null) ({ AppIcon(ic, null, Modifier.size(16.dp)) }) else null
                FilterChip(
                    selected = c.selected,
                    onClick = c.onClick,
                    label = { Text(if (c.count > 0) c.label + " (" + num(c.count) + ")" else c.label, maxLines = 1) },
                    leadingIcon = lead,
                    modifier = if (c.tag != null) Modifier.testTag(c.tag) else Modifier,
                )
            }
            if (onClear != null) {
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    label = { Text(ListingText.clear(), maxLines = 1) },
                    leadingIcon = { AppIcon(R.drawable.ic_close, null, Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.error, iconColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("listing_clear"),
                )
            }
        }
    }
}

@Composable
private fun SortChip(sort: ListingSort?, sorts: List<ListingSort>, onSort: (ListingSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(sort?.let { ListingText.sort(it) } ?: ListingText.sortBy(), maxLines = 1) },
            trailingIcon = { AppIcon(R.drawable.ic_expand_more_round, null, Modifier.size(16.dp)) },
            modifier = Modifier.testTag("listing_sort"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { s ->
                DropdownMenuItem(
                    text = { Text(ListingText.sort(s)) },
                    trailingIcon = if (s == sort) ({ AppIcon(R.drawable.ic_done, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { open = false; onSort(s) },
                    modifier = Modifier.testTag("listing_sort_" + s.wire),
                )
            }
        }
    }
}

/** ছোট খোঁজার ঘর (৪৪dp) — লিখতে লিখতেই তালিকা ছাঁকে */
@Composable
fun ListingSearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerHigh, modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp), cs.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                if (value.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { onChange(it.take(60)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().testTag("listing_search"),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(36.dp)) {
                    AppIcon(R.drawable.ic_close, ListingText.clear(), Modifier.size(18.dp), cs.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}

// ---------------- তালিকা ----------------

/**
 * তালিকার পুরো পাতা (LazyColumn): উপরের অংশ, তারপর header (অ্যাম্বুলেন্স ডাকুন, ৯৯৯ …), বার্তা, তারপর কার্ড —
 * আসার সময় কার্ডের মাপের কঙ্কাল (জায়গা নড়ে না), কিছু না থাকলে এক লাইন + এক বাটন।
 */
@Composable
fun ListingList(
    controls: @Composable () -> Unit,
    loading: Boolean,
    items: List<ListingCardData>,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
    messages: @Composable () -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize().testTag("listing_list"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "controls") { controls() }
        header()
        item(key = "messages") { Column(Modifier.padding(horizontal = LISTING_GUTTER)) { messages() } }
        when {
            loading -> items(4, key = { "sk$it" }) { ListingSkeleton(Modifier.padding(horizontal = LISTING_GUTTER)) }
            items.isEmpty() -> item(key = "empty") { empty() }
            else -> items(items, key = { it.type.key + "_" + it.id }) { c -> ListingCard(c, Modifier.padding(horizontal = LISTING_GUTTER)) }
        }
        footer()
    }
}

/** খালি: এক লাইন আর এক বাটন (এলাকা বদলান / মুছুন) */
@Composable
fun ListingEmpty(line: String, action: String, onAction: () -> Unit, @DrawableRes icon: Int? = null) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = LISTING_GUTTER, vertical = 24.dp).testTag("listing_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) AppIcon(icon, null, Modifier.size(40.dp), cs.onSurfaceVariant.copy(alpha = 0.6f))
        Text(line, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onAction, modifier = Modifier.testTag("listing_empty_action")) { Text(action) }
    }
}

/** কার্ডের মাপের কঙ্কাল — একই ফাঁক আর উচ্চতা, তথ্য এলে জায়গা নড়ে না */
@Composable
fun ListingSkeleton(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bar = cs.onSurface.copy(alpha = 0.08f)
    Card(modifier.fillMaxWidth().testTag("listing_skeleton"), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(CARD_PAD)) {
            Row {
                Box(Modifier.size(AVATAR).clip(CircleShape).background(bar))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                    Box(Modifier.fillMaxWidth(0.9f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(132.dp).height(BUTTON_H).clip(RoundedCornerShape(12.dp)).background(bar))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(32.dp).clip(CircleShape).background(bar))
            }
        }
    }
}

private val CARD_PAD = 12.dp
private val AVATAR = 44.dp
private val BUTTON_H = 40.dp

@Composable
private fun toneColors(t: BadgeTone): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (t) {
        BadgeTone.GOOD -> cs.tertiaryContainer to cs.onTertiaryContainer
        BadgeTone.WARN -> cs.errorContainer to cs.onErrorContainer
        BadgeTone.INFO -> cs.primaryContainer to cs.onPrimaryContainer
        BadgeTone.NEUTRAL -> cs.surfaceVariant to cs.onSurfaceVariant
    }
}

@Composable
fun ListingBadgeTag(b: ListingBadge) {
    val (bg, fg) = toneColors(b.tone)
    Tag(b.text, bg, fg)
}

/** রক্তের গ্রুপের স্টক: "A+ আছে" (সবুজ) / "B+ কম" (লালচে) / "O- নেই" (ধূসর) */
@Composable
fun StockChips(stock: Map<String, String>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    FlowRow(modifier.testTag("stock_chips"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ListingExtras.GROUPS.forEach { g ->
            val s = stock[g] ?: return@forEach
            val (bg, fg) = when (s) {
                "have" -> cs.tertiaryContainer to cs.onTertiaryContainer
                "low" -> cs.errorContainer to cs.onErrorContainer
                else -> cs.surfaceVariant to cs.onSurfaceVariant
            }
            Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(3.dp))
                    Text(ListingText.stock(s), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun NameRow(name: String, verified: Boolean, nameTag: String?, big: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    // নাম কাটা নয় (৩৬০dp-তেও): দুই লাইনে ভাঙে; খুব লম্বা হলে তিন লাইনে
    Row(verticalAlignment = Alignment.Top) {
        Text(
            name,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (big) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = if (big) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (verified) {
            Spacer(Modifier.width(4.dp))
            AppIcon(R.drawable.ic_verified, tr("Verified", "যাচাই করা"), Modifier.padding(top = 3.dp).size(if (big) 20.dp else 16.dp), cs.primary)
        }
    }
    if (nameTag != null) Tag(nameTag, cs.secondaryContainer, cs.onSecondaryContainer)
}

/** কার্ডের তিন নম্বর সারি: রেটিং আর ব্যাজ (স্টক আলাদা সারিতে) */
@Composable
private fun RatingBadgesRow(ratingText: String?, badges: List<ListingBadge>) {
    if (ratingText == null && badges.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ratingText != null) Text(ratingText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.testTag("listing_rating"))
        badges.forEach { ListingBadgeTag(it) }
    }
}

/** তালিকার কার্ড — পাঁচ ধরনেরই একই গঠন */
@Composable
fun ListingCard(c: ListingCardData, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = c.onOpen, modifier = modifier.fillMaxWidth().testTag("listing_card"), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(CARD_PAD)) {
            Row(verticalAlignment = Alignment.Top) {
                CardAvatar(c.photo, c.icon, if (c.danger) cs.error else cs.primary, size = AVATAR)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NameRow(c.name, c.verified, c.nameTag)
                    if (c.keyLine.isNotBlank()) {
                        Text(c.keyLine, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("listing_keyline"))
                    }
                    if (!c.note.isNullOrBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RatingBadgesRow(c.ratingText, c.badges)
                    if (c.stock != null) {
                        if (c.stock.isEmpty()) Text(ListingText.noStock(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        else StockChips(c.stock)
                        c.stockNote?.let { n -> Text(n.text, style = MaterialTheme.typography.labelSmall, color = if (n.tone == BadgeTone.WARN) cs.error else cs.onSurfaceVariant) }
                    }
                }
                ListingMenu(c.onOpen, c.onReport)
            }
            if (c.primary != null || c.contact != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.primary != null) {
                        Button(
                            onClick = c.primary.onClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.heightIn(min = BUTTON_H).testTag("listing_primary"),
                        ) {
                            if (c.primary.icon != null) {
                                AppIcon(c.primary.icon, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(c.primary.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    c.contact?.let { k -> ListingContactIcons(k, ContactStyle.ICONS) }
                }
            }
        }
    }
}

@Composable
fun ListingContactIcons(k: ListingContact, style: ContactStyle) {
    ContactActions(
        ctx = k.ctx, refId = k.refId, peerName = k.peerName, phone = k.phone?.takeIf { it.isNotBlank() },
        style = style, onPhone = k.onPhone, onMessage = k.onMessage, modifier = Modifier.testTag("listing_contact"),
    )
}

/** ⋮ — সব ধরনে একই: বিস্তারিত, রিপোর্ট (লগইন থাকলে) */
@Composable
private fun ListingMenu(onOpen: (() -> Unit)?, onReport: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp).testTag("listing_menu")) {
            AppIcon(R.drawable.ic_more_vert, tr("More", "আরও"), Modifier.size(20.dp), cs.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onOpen != null) DropdownMenuItem(
                text = { Text(ListingText.details()) },
                leadingIcon = { AppIcon(R.drawable.ic_info, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onOpen() },
            )
            if (onReport != null && !LoginGate.isGuest) {
                DropdownMenuItem(
                    text = { Text(ListingText.report()) },
                    leadingIcon = { AppIcon(R.drawable.ic_flag, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; onReport() },
                )
            }
        }
    }
}

// ---------------- বিস্তারিত ----------------

data class ListingDetailData(
    val type: ListingType,
    val name: String,
    @DrawableRes val icon: Int,
    val keyLine: String,
    val photo: String? = null,
    val danger: Boolean = false,
    val verified: Boolean = false,
    val nameTag: String? = null,
    val badges: List<ListingBadge> = emptyList(),
    val stock: Map<String, String>? = null,
    val stockNote: ListingBadge? = null,
    /** ২) মূল তথ্য */
    val facts: List<Pair<Int, String>> = emptyList(),
    /** ৩) ধরনের নিজের অংশ: চেম্বার/স্টক/পরীক্ষা ও দাম/গাড়ি/খোলার সময় */
    val sections: List<DetailSection> = emptyList(),
    /** ৪) ঠিকানা আর ফোন (কপি করা যায়) */
    val address: List<Pair<Int, String>> = emptyList(),
    val phones: List<String> = emptyList(),
    /** ৫) রেটিং */
    val ratingText: String? = null,
    val ratingExtra: String = "",
    val showRatings: Boolean = true,
    val onRate: (() -> Unit)? = null,
    /** নিচের বার */
    val primary: CardAction? = null,
    val contact: ListingContact? = null,
    val onReport: (() -> Unit)? = null,
)

/**
 * বিস্তারিত — পুরো পর্দা, পাঁচ ধরনেরই একই গঠন: ১) মাথা (নাম, ব্যাজ, মূল লাইন) ২) মূল তথ্য ৩) ধরনের নিজের অংশ
 * ৪) ঠিকানা ও ফোন (কপি) ৫) রেটিং। নিচে আটকানো বার: মূল বাটন + কল + মেসেজ (ফোনে নেভিগেশন বারের ওপরে)।
 * top: মাথার ঠিক নিচে (ডাক্তারের চেম্বার বাছাই); typeSection: ভাগগুলোর পরে নিজের কিছু; footer: একদম নিচে।
 */
@Composable
fun ListingDetail(
    d: ListingDetailData,
    onDismiss: () -> Unit,
    top: @Composable () -> Unit = {},
    typeSection: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    FullScreenDialog(onDismissRequest = onDismiss, dismissOnClickOutside = false) {
        ListingDetailBody(d, onDismiss, top, typeSection, footer)
    }
}

/** বিস্তারিতের ভেতরটা (ডায়ালগ ছাড়া — ছবির টেস্টও এটা আঁকে) */
@Composable
fun ListingDetailBody(
    d: ListingDetailData,
    onDismiss: () -> Unit,
    top: @Composable () -> Unit = {},
    typeSection: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).testTag("listing_detail")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
            Text(ListingText.typeLabel(d.type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (d.onReport != null && !LoginGate.isGuest) ListingMenu(null, d.onReport)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ১) মাথা
            Row(verticalAlignment = Alignment.Top) {
                CardAvatar(d.photo, d.icon, if (d.danger) cs.error else cs.primary, size = 60.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NameRow(d.name, d.verified, d.nameTag, big = true)
                    if (d.keyLine.isNotBlank()) Text(d.keyLine, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("listing_detail_keyline"))
                    if (d.badges.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { d.badges.forEach { ListingBadgeTag(it) } }
                    }
                }
            }
            top()
            // ২) মূল তথ্য
            val facts = d.facts.filter { it.second.isNotBlank() }
            if (facts.isNotEmpty()) DetailBlock(ListingText.keyFacts()) { facts.forEach { (ic, t) -> DetailLine(ic, t) } }
            // ৩) ধরনের নিজের অংশ
            if (d.stock != null) {
                DetailBlock(ListingText.bloodStock()) {
                    if (d.stock.isEmpty()) Text(ListingText.noStock(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    else StockGrid(d.stock)
                    d.stockNote?.let { n -> Text(n.text, style = MaterialTheme.typography.bodySmall, color = if (n.tone == BadgeTone.WARN) cs.error else cs.onSurfaceVariant) }
                }
            }
            d.sections.forEach { sec -> DetailSectionBlock(sec) }
            typeSection()
            // ৪) ঠিকানা ও ফোন
            val addr = d.address.filter { it.second.isNotBlank() }
            val phones = d.phones.filter { it.isNotBlank() }.distinct()
            if (addr.isNotEmpty() || phones.isNotEmpty()) {
                DetailBlock(ListingText.addressPhone()) {
                    addr.forEach { (ic, t) -> DetailLine(ic, t) }
                    phones.forEach { CopyablePhone(it) }
                }
            }
            // ৫) রেটিং
            if (d.showRatings) {
                DetailBlock(ListingText.ratings()) {
                    Text(d.ratingText ?: ListingText.noRating(), style = MaterialTheme.typography.bodyLarge, fontWeight = if (d.ratingText != null) FontWeight.SemiBold else FontWeight.Normal)
                    if (d.ratingExtra.isNotBlank()) Text(d.ratingExtra, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    if (d.onRate != null) {
                        OutlinedButton(onClick = d.onRate, modifier = Modifier.testTag("listing_rate")) { ButtonContent(false, ListingText.rate(), R.drawable.ic_star) }
                    }
                }
            }
            footer()
            Spacer(Modifier.height(8.dp))
        }
        // নিচের আটকানো বার
        if (d.primary != null || d.contact != null) {
            HorizontalDivider(color = cs.outlineVariant)
            Row(
                Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 10.dp).testTag("listing_detail_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (d.primary != null) {
                    Button(
                        onClick = d.primary.onClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("listing_detail_primary"),
                    ) {
                        if (d.primary.icon != null) {
                            AppIcon(d.primary.icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(d.primary.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                d.contact?.let { ListingContactIcons(it, ContactStyle.ROW) }
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = cs.outlineVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.primary)
        content()
    }
}

@Composable
private fun DetailLine(@DrawableRes icon: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        AppIcon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
    }
}

/** Cards.kt-এর DetailSection — একই আঁকা (শিরোনাম, সারণি, লাইন, ভেতরের বাটন) */
@Composable
fun DetailSectionBlock(sec: DetailSection) {
    val cs = MaterialTheme.colorScheme
    val lines = sec.lines.filter { it.second.isNotBlank() }
    if (lines.isEmpty() && sec.actions.isEmpty() && sec.rows.isEmpty() && sec.button == null) return
    DetailBlock(sec.title) {
        sec.rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                    if (r.sub.isNotBlank()) Text(r.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Text(r.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        lines.forEach { (ic, t) -> DetailLine(ic, t) }
        if (sec.actions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sec.actions.forEach { a ->
                    if (a.icon == R.drawable.ic_call) OutlinedButton(onClick = a.onClick, shape = RoundedCornerShape(12.dp)) { ButtonContent(false, a.label, a.icon) }
                    else Button(onClick = a.onClick, shape = RoundedCornerShape(12.dp)) { ButtonContent(false, a.label, a.icon) }
                }
            }
        }
        sec.button?.let { b ->
            Button(onClick = b.onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { ButtonContent(false, b.label, b.icon) }
        }
    }
}

/** বিস্তারিতের স্টক: আট গ্রুপ, চার কলামে — জানা না থাকলে "—" */
@Composable
fun StockGrid(stock: Map<String, String>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("stock_grid")) {
        ListingExtras.GROUPS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { g ->
                    val s = stock[g]
                    val (bg, fg) = when (s) {
                        "have" -> cs.tertiaryContainer to cs.onTertiaryContainer
                        "low" -> cs.errorContainer to cs.onErrorContainer
                        "none" -> cs.surfaceVariant to cs.onSurfaceVariant
                        else -> cs.surfaceContainerHigh to cs.onSurfaceVariant
                    }
                    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(g, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(s?.let { ListingText.stock(it) } ?: "—", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** নম্বর আর পাশে কপির বাটন */
@Composable
private fun CopyablePhone(phone: String) {
    val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    var done by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("listing_phone")) {
        AppIcon(R.drawable.ic_call, null, Modifier.size(18.dp), cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(num(phone), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (done) Text(ListingText.copied(), style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
        IconButton(onClick = { runCatching { clip.setText(AnnotatedString(phone)) }; done = true }, modifier = Modifier.testTag("listing_copy")) {
            AppIcon(R.drawable.ic_content_copy, ListingText.copy(), Modifier.size(18.dp), cs.primary)
        }
    }
}
