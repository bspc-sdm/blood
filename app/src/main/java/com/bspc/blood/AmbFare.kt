package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/*
 * অ্যাম্বুলেন্সের ভাড়া (২.৩০.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — ওয়েবের services/fare.tsx-এর অ্যাপ রূপ।
 * মালিকের ভাড়ার তালিকা (নিজের উপজেলা, গন্তব্যের সারি, বাড়তি), রোগীর "কোথায় যাবেন", টাকার লেখা।
 * হিসাব সার্ভারে (server/lib/ambfare.js) — এখানে শুধু দেখানো আর বাছাই।
 */

/**
 * গন্তব্য: kind = upazila (upazilaId) / district (district) / hospital (name)।
 * ২.৩১.০: হাসপাতাল/ক্লিনিকের তালিকা থেকে বাছা হলে facilityId (সাথে সেটার upazilaId/district — ভাড়ার তালিকায় id দিয়ে মেলে,
 * নইলে হাসপাতালের উপজেলা/জেলা ধরে; সার্ভার তালিকা থেকেই বসায়), "অন্য জায়গা লিখুন" হলে শুধু name।
 */
data class Dest(val kind: String, val upazilaId: Int? = null, val district: String? = null, val name: String? = null, val facilityId: String? = null) {
    fun json(): JSONObject = JSONObject().put("kind", kind).apply {
        when (kind) {
            "upazila" -> put("upazilaId", upazilaId)
            "district" -> put("district", district)
            else -> {
                put("name", name)
                if (facilityId != null) {
                    put("facilityId", facilityId)
                    if (upazilaId != null) put("upazilaId", upazilaId)
                    if (district != null) put("district", district)
                }
            }
        }
    }

    /** তালিকায় একই গন্তব্য দুবার নয় (লেখা হাসপাতাল বাদে — নাম মেলানো সার্ভারে) */
    val key: String get() = when (kind) {
        "upazila" -> "u:$upazilaId"
        "district" -> "d:$district"
        else -> if (facilityId != null) "f:$facilityId" else "h"
    }

    fun label(): String = when (kind) {
        "upazila" -> Areas.upazila(upazilaId)?.let { "${it.label}, ${Districts.label(it.district)}" } ?: ""
        "district" -> Districts.label(district ?: "")
        else -> name ?: ""
    }

    companion object {
        fun parse(o: JSONObject?): Dest? {
            if (o == null) return null
            return when (o.str("kind")) {
                "upazila" -> o.intOrNull("upazilaId")?.let { Dest("upazila", upazilaId = it) }
                "district" -> o.str("district").ifBlank { null }?.let { Dest("district", district = it) }
                "hospital" -> o.str("name").ifBlank { null }?.let {
                    Dest("hospital", name = it, facilityId = o.str("facilityId").ifBlank { null }, upazilaId = o.intOrNull("upazilaId"), district = o.str("district").ifBlank { null })
                }
                else -> null
            }
        }
    }
}


data class FareRow(val dest: Dest, val amount: Int)
data class FareChart(val local: Int?, val rows: List<FareRow>, val oxygen: Int?, val icu: Int?, val night: Int?) {
    fun json(): JSONObject = JSONObject()
        .put("local", local ?: JSONObject.NULL)
        .put("rows", JSONArray(rows.map { it.dest.json().put("amount", it.amount) }))
        .put("oxygen", oxygen ?: JSONObject.NULL).put("icu", icu ?: JSONObject.NULL).put("night", night ?: JSONObject.NULL)

    /** কার্ডে ছোট সারাংশ: "নিজের উপজেলা ৳৮০০ · ঢাকা ৳৫,০০০ · +২" */
    fun summary(): String {
        val parts = (if (local != null) listOf(tr("Local ", "নিজের উপজেলা ") + taka(local)) else emptyList()) +
            rows.take(2).map { it.dest.label() + " " + taka(it.amount) }
        val more = rows.size - 2
        return parts.joinToString(" · ") + if (more > 0) " · +${num(more)}" else ""
    }

    companion object {
        fun parse(o: JSONObject?): FareChart? {
            if (o == null) return null
            val a = o.optJSONArray("rows") ?: JSONArray()
            val rows = (0 until a.length()).mapNotNull { i ->
                val r = a.getJSONObject(i)
                Dest.parse(r)?.let { FareRow(it, r.optInt("amount")) }
            }
            return FareChart(o.intOrNull("local"), rows, o.intOrNull("oxygen"), o.intOrNull("icu"), o.intOrNull("night"))
        }
    }
}

const val MAX_FARE_ROWS = 15
/** রোগীর বাজেটের চিপ (null = যেকোনো) */
val FARE_LEVELS = listOf(1000, 2000, 5000, 10000)

/** "৳১,২০০" */
fun taka(n: Int?): String {
    if (n == null) return ""
    val s = n.toString().reversed().chunked(3).joinToString(",").reversed()
    return "৳" + num(s)
}

/** লেখা → পুরো টাকা: খালি = null, ভুল = -1 */
fun parseTaka(s: String): Int? {
    val t = s.map { c -> if (c in '০'..'৯') ('0' + (c - '০')) else c }.joinToString("").replace(Regex("[,\\s৳]"), "")
    if (t.isEmpty()) return null
    return if (t.all { it.isDigit() } && t.length <= 7) t.toInt() else -1
}

/**
 * গন্তব্য বাছাই: বোতাম চাপলে তালিকা (খোঁজার ঘরসহ) — জেলার উপজেলা, "কোনো হাসপাতাল (নাম লিখুন)", অন্য জেলা।
 * hospitals: নাম মেলাতে পরামর্শ (কাছের গাড়ির তালিকা থেকে); exclude: আগে বাছা (ভাড়ার তালিকায় একই গন্তব্য দুবার নয়)।
 */
@Composable
fun DestPicker(
    label: String, district: String, value: Dest?, onChange: (Dest?) -> Unit,
    hospitals: List<String> = emptyList(), exclude: Set<String> = emptySet(), showError: Boolean = false, tag: String = "amb_dest",
) {
    val context = LocalContext.current
    remember(context) { Areas.ensure(context); true }
    var open by remember { mutableStateOf(false) }
    // ২.৩১.০: হাসপাতাল/ক্লিনিক তালিকা থেকে (facilityId) — লেখার ঘর লাগে না
    var listOpen by remember { mutableStateOf(false) }
    var hospMode by remember { mutableStateOf(value?.kind == "hospital" && value.facilityId == null) }
    var hospText by remember { mutableStateOf(value?.name ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(
                when {
                    hospMode -> tr("Another hospital", "অন্য হাসপাতাল")
                    value != null -> value.label()
                    else -> tr("Choose…", "বেছে নিন…")
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (hospMode) {
            OutlinedTextField(
                hospText,
                {
                    hospText = it.take(80)
                    val t = hospText.trim()
                    onChange(if (t.length >= 3) Dest("hospital", name = t) else null)
                },
                label = { Text(tr("Hospital name", "হাসপাতালের নাম")) },
                isError = showError && value == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("${tag}_hospital"),
            )
            val t = hospText.trim()
            val sugg = hospitals.filter { t.isEmpty() || (it.contains(t, true) && it != t) }.take(4)
            if (sugg.isNotEmpty()) {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEach { h ->
                        androidx.compose.material3.AssistChip(onClick = { hospText = h; onChange(Dest("hospital", name = h)) }, label = { Text(h) })
                    }
                }
            }
        }
        if (showError && value == null) ErrorText(tr("Choose where to go", "\"কোথায় যাবেন\" বেছে নিন"))
    }
    if (listOpen) {
        FacilityDialog(
            title = tr("Choose hospital or clinic", "হাসপাতাল বা ক্লিনিক বাছুন"), district = district, near = null, allowOther = false,
            onPick = { d ->
                listOpen = false
                if (d.key !in exclude) {
                    hospMode = false
                    onChange(d)
                }
            },
            onDismiss = { listOpen = false },
        )
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        val norm = Areas.bnNorm(q.trim())
        val ups = Areas.upazilasOf(district).filter { "u:${it.id}" !in exclude }
        val dists = Districts.ALL.sortedBy { if (isBangla) it.bn else it.en }.filter { "d:${it.bn}" !in exclude }
        fun hit(bn: String, en: String) = norm.isEmpty() || Areas.bnNorm(bn).contains(norm) || en.contains(q.trim(), true)
        val options: List<Pair<String, Dest?>> =
            listOf(tr("A hospital or clinic — from the list", "হাসপাতাল বা ক্লিনিক — তালিকা থেকে") to Dest("pick")) +
            ups.filter { hit(it.bn, it.en) }.map { "${it.label} (${tr("upazila", "উপজেলা")})" to Dest("upazila", upazilaId = it.id) } +
                listOf(tr("Another hospital (type the name)", "অন্য হাসপাতাল (নাম লিখুন)") to null) +
                dists.filter { hit(it.bn, it.en) }.map { "${Districts.label(it.bn)} (${tr("district", "জেলা")})" to Dest("district", district = it.bn) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, label = { Text(tr("Search", "খুঁজুন")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(options) { (text, d) ->
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (d == null || d.kind == "pick") FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    open = false
                                    if (d?.kind == "pick") {
                                        listOpen = true
                                    } else if (d == null) {
                                        hospMode = true
                                        val t = hospText.trim()
                                        onChange(if (t.length >= 3) Dest("hospital", name = t) else null)
                                    } else {
                                        hospMode = false
                                        onChange(d)
                                    }
                                }.padding(vertical = 12.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(tr("Close", "বন্ধ")) } },
        )
    }
}

/** মালিকের ঘরের লেখার অবস্থা */
data class RowDraft(val dest: Dest?, val amount: String)
data class ChartDraft(val local: String, val rows: List<RowDraft>, val oxygen: String, val icu: String, val night: String) {
    companion object {
        fun of(c: FareChart?): ChartDraft = ChartDraft(
            c?.local?.toString() ?: "", c?.rows?.map { RowDraft(it.dest, it.amount.toString()) } ?: emptyList(),
            c?.oxygen?.toString() ?: "", c?.icu?.toString() ?: "", c?.night?.toString() ?: "",
        )
    }

    /** সার্ভারে পাঠানোর তালিকা (খালি = null) অথবা ভুলের লেখা — সার্ভারও একই নিয়মে যাচাই করে */
    fun build(): Pair<FareChart?, String?> {
        val rangeMsg = tr("Fares must be between ৳100 and ৳1,00,000", "ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন")
        val wholeMsg = tr("Write fares in whole taka", "ভাড়া পুরো টাকায় লিখুন")
        val l = parseTaka(local)
        if (l == -1) return null to wholeMsg
        if (l != null && (l < 100 || l > 100000)) return null to rangeMsg
        val out = mutableListOf<FareRow>()
        for (r in rows) {
            if (r.dest == null && r.amount.isBlank()) continue
            if (r.dest == null) return null to tr("Choose each destination", "প্রতিটা গন্তব্য বেছে নিন")
            val a = parseTaka(r.amount) ?: return null to tr("Write the fare for each destination", "প্রতিটা গন্তব্যের ভাড়া লিখুন")
            if (a == -1) return null to wholeMsg
            if (a < 100 || a > 100000) return null to rangeMsg
            out += FareRow(r.dest, a)
        }
        val ex = listOf(oxygen, icu, night).map { parseTaka(it) }
        if (ex.any { it == -1 }) return null to tr("Write extras in whole taka", "বাড়তি ভাড়া পুরো টাকায় লিখুন")
        if (ex.any { it != null && (it < 50 || it > 50000) }) return null to tr("Extras must be between ৳50 and ৳50,000", "বাড়তি ভাড়া ৳৫০ থেকে ৳৫০,০০০-এর মধ্যে লিখুন")
        if (l == null && out.isEmpty()) {
            if (ex.any { it != null }) return null to tr("Write at least one fare (local or a destination)", "অন্তত একটা ভাড়া লিখুন (নিজের উপজেলা বা কোনো গন্তব্য)")
            return null to null
        }
        return FareChart(l, out, ex[0], ex[1], ex[2]) to null
    }
}

@Composable
private fun AmountField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.take(7)) }, label = { Text(label) }, placeholder = { Text("৳") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier,
    )
}

/** মালিকের ফর্মে "ভাড়ার তালিকা": নিজের উপজেলা, গন্তব্যের সারি (সর্বোচ্চ ১৫টা), বাড়তি */
@Composable
fun FareChartEditor(district: String?, upazilaId: Int?, value: ChartDraft, onChange: (ChartDraft) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("fare_chart")) {
        Text(tr("Fare list (helps patients choose by budget)", "ভাড়ার তালিকা (রোগী বাজেট দিয়ে খুঁজতে পারেন)"), style = MaterialTheme.typography.labelLarge)
        AmountField(tr("Within own upazila", "নিজের উপজেলার ভেতরে"), value.local, Modifier.fillMaxWidth()) { onChange(value.copy(local = it)) }
        if (district == null) {
            SmallNote(tr("Choose the base area first.", "আগে \"কোথা থেকে চলে\" বেছে নিন।"))
        } else {
            value.rows.forEachIndexed { i, r ->
                val used = value.rows.filterIndexed { j, _ -> j != i }.mapNotNull { it.dest?.key }.filter { it != "h" }.toSet() +
                    (if (upazilaId != null) setOf("u:$upazilaId") else emptySet())
                Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DestPicker(
                            tr("Destination ${i + 1}", "গন্তব্য ${num(i + 1)}"), district, r.dest,
                            { d -> onChange(value.copy(rows = value.rows.mapIndexed { j, x -> if (j == i) x.copy(dest = d) else x })) },
                            exclude = used, tag = "fare_dest_$i",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AmountField(tr("Fare", "ভাড়া"), r.amount, Modifier.weight(1f)) { a ->
                                onChange(value.copy(rows = value.rows.mapIndexed { j, x -> if (j == i) x.copy(amount = a) else x }))
                            }
                            IconButton(onClick = { onChange(value.copy(rows = value.rows.filterIndexed { j, _ -> j != i })) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = tr("Remove", "সরান"))
                            }
                        }
                    }
                }
            }
            if (value.rows.size < MAX_FARE_ROWS) {
                OutlinedButton(onClick = { onChange(value.copy(rows = value.rows + RowDraft(null, ""))) }, modifier = Modifier.testTag("fare_add")) {
                    Text(tr("+ Add a destination", "+ গন্তব্য যোগ করুন"))
                }
            }
        }
        Text(tr("Extras (optional, added on top)", "বাড়তি (ঐচ্ছিক, ভাড়ার সাথে যোগ হয়)"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(tr("Oxygen", "অক্সিজেন"), value.oxygen, Modifier.weight(1f)) { onChange(value.copy(oxygen = it)) }
            AmountField(tr("ICU equip.", "আইসিইউ যন্ত্র"), value.icu, Modifier.weight(1f)) { onChange(value.copy(icu = it)) }
        }
        AmountField(tr("Night (10 pm–6 am)", "রাতে (রাত ১০টা–সকাল ৬টা)"), value.night, Modifier.fillMaxWidth()) { onChange(value.copy(night = it)) }
    }
}
