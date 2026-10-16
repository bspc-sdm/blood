@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// "ওষুধ খুঁজুন": তালিকা থেকে ওষুধ বেছে কাছের দোকানগুলোকে জিজ্ঞেস করা; দোকান এক চাপে "আছে"/"নেই"।
// ক্রেতা "আছে" বলা দোকানে সরাসরি ফোন করেন। দাম, অর্ডার, ঠিকানা কিছুই অ্যাপে নেই।
// ------------------------------------------------------------

data class FindDrug(val i: Int, val name: String, val generic: String, val form: String, val company: String) {
    val detail: String get() = listOf(generic, form, company).filter { it.isNotBlank() }.joinToString(" · ")
}

data class FindShop(val id: String, val name: String, val phone: String, val address: String, val openNow: Boolean, val open24: Boolean, val km: Double?)

data class MyFind(val id: String, val drug: FindDrug, val area: String, val open: Boolean, val notified: Int, val answered: Int, val have: List<FindShop>, val createdAt: Long)

data class FindRequest(val id: String, val drug: FindDrug, val area: String, val km: Double?, val answer: String?, val createdAt: Long)

/** নোটিফিকেশন থেকে "সেবা" ট্যাবের নির্দিষ্ট অংশ খোলা: "med_find" (দোকানের অনুরোধ) / "med_find_mine" (নিজের খোঁজ) */
object ServicesNav {
    val target = mutableStateOf<String?>(null)
}

object MedFind {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun drug(o: JSONObject?, i: Int = -1) = FindDrug(
        o?.optInt("i", i) ?: i, o?.optString("name").orEmpty(), o?.optString("generic").orEmpty(), o?.optString("form").orEmpty(), o?.optString("company").orEmpty(),
    )

    suspend fun search(q: String): List<FindDrug> {
        val a = call("drug_search", JSONObject().put("q", q)).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { drug(a.getJSONObject(it)) }
    }

    /** ফেরত: কয়টা দোকানে গেল; ০ মানে কাছে কোনো দোকান নেই */
    suspend fun ask(i: Int, district: String, upazilaId: Int?, lat: Double?, lng: Double?): Int {
        val r = call(
            "med_find",
            JSONObject().put("i", i).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL),
        )
        // ০: জেলায় অ্যাপে কোনো ফার্মেসি নেই; -১: আছে, কিন্তু এখন কোনোটা খোলা নেই
        return when {
            r.optBoolean("noPharmacy") -> 0
            r.optBoolean("noneOpen") -> -1
            else -> r.optInt("notified")
        }
    }

    suspend fun mine(): List<MyFind> {
        val a = call("med_finds_mine").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { k ->
            val x = a.getJSONObject(k)
            val h = x.optJSONArray("have") ?: JSONArray()
            MyFind(
                x.optString("id"), drug(x.optJSONObject("drug")), x.optString("area"), x.optBoolean("open"),
                x.optInt("notified"), x.optInt("answered"),
                (0 until h.length()).map { j ->
                    val s = h.getJSONObject(j)
                    FindShop(
                        s.optString("id"), s.optString("name"), s.optString("phone"), s.optString("address"),
                        s.optBoolean("openNow"), s.optBoolean("open24"), if (s.isNull("km")) null else s.optDouble("km"),
                    )
                },
                x.optLong("createdAt"),
            )
        }
    }

    suspend fun close(id: String) {
        call("med_find_close", JSONObject().put("id", id))
    }

    /** দোকান: খোলা অনুরোধ, আর অনুরোধ নেওয়া বন্ধ কি না */
    suspend fun requests(): Pair<List<FindRequest>, Boolean> {
        val r = call("med_find_requests")
        val a = r.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { k ->
            val x = a.getJSONObject(k)
            FindRequest(
                x.optString("id"), drug(x.optJSONObject("drug")), x.optString("area"),
                if (x.isNull("km")) null else x.optDouble("km"), if (x.isNull("answer")) null else x.optString("answer"), x.optLong("createdAt"),
            )
        } to r.optBoolean("findOff")
    }

    suspend fun answer(id: String, have: Boolean) {
        call("med_find_answer", JSONObject().put("id", id).put("have", have))
    }

    suspend fun setOff(off: Boolean) {
        call("pharmacy_find_setting", JSONObject().put("off", off))
    }
}

/** ক্রেতা: ওষুধ বেছে কাছের দোকানগুলোকে জিজ্ঞেস করা, আর নিজের খোঁজের উত্তর */
@Composable
fun MedFindPanel(profile: Profile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { Areas.ensure(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FindDrug>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<FindDrug?>(null) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    val gps = profile.locationSource == "gps"
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf<List<MyFind>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var reporting by remember { mutableStateOf<FindShop?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350) // লেখা থামলে তবেই সার্ভারে খোঁজা
        searching = true
        results = runCatching { MedFind.search(q) }.getOrDefault(emptyList())
        searching = false
    }
    LaunchedEffect(reload) {
        runCatching { MedFind.mine() }.onSuccess { mine = it }.onFailure { if (mine == null) mine = emptyList() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val c = chosen
        if (c == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                label = { Text(tr("Medicine name", "ওষুধের নাম")) },
                placeholder = { Text(tr("e.g. Napa Extend, Seclo, Esomeprazole", "যেমন: Napa Extend, Seclo, Esomeprazole")) },
                leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                // সার্চের ঠিক নিচে ছোট করে বোঝানো
                supportingText = { Text(tr("Nearby pharmacies will say if they have it.", "কাছের ফার্মেসি জানাবে কার কাছে আছে।")) },
            )
            results.forEach { d ->
                Surface(
                    onClick = { chosen = d; errorMsg = null; infoMsg = null },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(d.name + if (d.form.isNotBlank()) " · " + d.form else "", fontWeight = FontWeight.SemiBold)
                        Text(listOf(d.generic, d.company).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (searching) SmallNote(tr("Searching…", "খোঁজা হচ্ছে…"))
            else if (query.trim().length >= 2 && results.isEmpty()) {
                SmallNote(tr("Not in the list — call a nearby pharmacy and ask.", "তালিকায় নেই — কাছের ফার্মেসিতে ফোন করে জিজ্ঞেস করুন।"))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { chosen = null }) { Text(tr("Change", "বদলান")) }
                    }
                    Text(tr("Where", "কোথায়"), style = MaterialTheme.typography.titleSmall)
                    AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                    SmallNote(tr("If it's a prescription medicine, the pharmacy will need to see the prescription.", "প্রেসক্রিপশনের ওষুধ হলে কিনতে দোকানে প্রেসক্রিপশন দেখাতে হবে।"))
                    ErrorText(errorMsg)
                    Button(
                        onClick = {
                            val d = district ?: run { errorMsg = tr("Choose a district", "জেলা বেছে নিন"); return@Button }
                            scope.launch {
                                busy = true
                                errorMsg = null
                                runCatching { MedFind.ask(c.i, d, upazilaId, if (gps) profile.lat else null, if (gps) profile.lng else null) }
                                    .onSuccess { n ->
                                        if (n == 0) {
                                            errorMsg = tr("No pharmacy in this district has joined the app yet.", "এই জেলায় এখনো অ্যাপে কোনো ফার্মেসি যোগ হয়নি।")
                                        } else if (n < 0) {
                                            errorMsg = tr("No pharmacy nearby can take a question right now (closed or busy). Please try again later.", "এই মুহূর্তে কাছের কোনো ফার্মেসি খোলা বা জিজ্ঞাসা নেওয়ার মতো নেই। একটু পরে আবার চেষ্টা করুন।")
                                        } else {
                                            infoMsg = tr("Asked $n nearby pharmacies. Answers will appear below, with a notification.", "কাছের ${num(n)}টা দোকানকে জিজ্ঞেস করা হয়েছে। উত্তর নিচে আসবে, নোটিফিকেশনও পাবেন।")
                                            chosen = null
                                            query = ""
                                            reload++
                                        }
                                    }
                                    .onFailure { errorMsg = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonContent(busy, tr("Ask nearby pharmacies", "কাছের দোকানগুলোকে জিজ্ঞেস করুন"), R.drawable.ic_send) }
                }
            }
        }
        InfoText(infoMsg)

        // ---- আমার খোঁজ ----
        val list = mine
        if (list != null && list.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("My searches", "আমার খোঁজ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { reload++ }) { Text(tr("Refresh", "রিফ্রেশ")) }
            }
            list.forEach { f -> MyFindCard(f, onCall = { s ->
                scope.launch { runCatching { Pharmacies.called(s.id) } }
                callPhone(context, s.phone)
            }, onReport = { reporting = it }, onClose = {
                scope.launch { runCatching { MedFind.close(f.id) }.onSuccess { reload++ }.onFailure { errorMsg = it.message } }
            }) }
        }
    }

    reporting?.let { s ->
        ReasonReportDialog(
            onDismiss = { reporting = null },
            onSubmit = { reason ->
                Moderation.report("pharmacy", s.id, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
    }
}

@Composable
private fun MyFindCard(f: MyFind, onCall: (FindShop) -> Unit, onReport: (FindShop) -> Unit, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.drug.name + if (f.drug.form.isNotBlank()) " · " + f.drug.form else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SmallNote(
                listOf(
                    f.area,
                    timeAgo(f.createdAt),
                    tr("asked ${f.notified} shops", "${num(f.notified)}টা দোকানে জিজ্ঞেস"),
                    if (f.open) tr("open", "খোলা") else tr("closed", "বন্ধ"),
                ).filter { it.isNotBlank() }.joinToString(" · "),
            )
            if (f.have.isEmpty()) {
                SmallNote(
                    if (f.open) tr("No pharmacy has said yes yet. You'll get a notification.", "এখনো কোনো দোকান \"আছে\" বলেনি। বললে নোটিফিকেশন পাবেন।")
                    else tr("No pharmacy said it has this.", "কোনো দোকান \"আছে\" বলেনি।"),
                )
            }
            f.have.forEach { s ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                tr("Has it", "আছে বলেছে"),
                                s.km?.let { Rules.formatKm(it) },
                                if (s.open24) tr("24 hours", "২৪ ঘণ্টা") else if (s.openNow) tr("open now", "এখন খোলা") else tr("closed now", "এখন বন্ধ"),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (s.address.isNotBlank()) Text(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = { onCall(s) }) { ButtonContent(false, tr("Call", "ফোন"), R.drawable.ic_call) }
                        TextButton(onClick = { onReport(s) }) { Text(tr("Report", "রিপোর্ট"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (f.open) {
                Spacer(Modifier.padding(top = 2.dp))
                TextButton(onClick = onClose) { Text(tr("Found it — stop asking", "পেয়ে গেছি — খোঁজ বন্ধ করুন")) }
            }
        }
    }
}

/** দোকান: কাছের মানুষ কী খুঁজছেন — এক চাপে "আছে"/"নেই" */
@Composable
fun PharmacyFindRequests() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FindRequest>?>(null) }
    var off by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        runCatching { MedFind.requests() }.onSuccess { (l, o) -> items = l; off = o }.onFailure { errorMsg = it.message; if (items == null) items = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Medicine requests", "ওষুধ খোঁজার অনুরোধ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SmallNote(tr("People nearby ask if you have a medicine. Answer in one tap; they call you.", "কাছের মানুষ জানতে চান কোনো ওষুধ আপনার কাছে আছে কি না। এক চাপে উত্তর দিন; তারা ফোন করবেন।"))
            }
            Switch(checked = !off, onCheckedChange = { on ->
                scope.launch { runCatching { MedFind.setOff(!on) }.onSuccess { off = !on }.onFailure { errorMsg = it.message } }
            })
        }
        ErrorText(errorMsg)
        val list = items
        when {
            off -> SmallNote(tr("Turned off — you won't get requests.", "বন্ধ — অনুরোধ আসবে না।"))
            list == null -> LoadingBox()
            list.isEmpty() -> SmallNote(tr("No open requests now.", "এখন কোনো খোলা অনুরোধ নেই।"))
            else -> list.forEach { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.drug.name + if (r.drug.form.isNotBlank()) " · " + r.drug.form else "", fontWeight = FontWeight.SemiBold)
                        if (r.drug.generic.isNotBlank()) Text(r.drug.generic, style = MaterialTheme.typography.bodySmall)
                        SmallNote(listOfNotNull(r.area.ifBlank { null }, r.km?.let { Rules.formatKm(it) }, timeAgo(r.createdAt)).joinToString(" · "))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            fun send(have: Boolean) {
                                scope.launch {
                                    busyId = r.id
                                    runCatching { MedFind.answer(r.id, have) }.onSuccess { reload++ }.onFailure { errorMsg = it.message }
                                    busyId = null
                                }
                            }
                            if (r.answer == "yes") {
                                Text(tr("You said: have it", "আপনার উত্তর: আছে"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { send(false) }, enabled = busyId == null) { Text(tr("Change to no", "\"নেই\" করুন")) }
                            } else {
                                Button(onClick = { send(true) }, enabled = busyId == null) { ButtonContent(busyId == r.id, tr("Have it", "আছে")) }
                                OutlinedButton(onClick = { send(false) }, enabled = busyId == null && r.answer != "no") { Text(if (r.answer == "no") tr("Said no", "\"নেই\" বলেছেন") else tr("Don't have", "নেই")) }
                            }
                        }
                    }
                }
            }
        }
    }
}
