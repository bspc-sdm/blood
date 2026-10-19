package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/*
 * "কোথায় যাবেন" (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — ওয়েবের FacilityPicker.tsx-এর অ্যাপ রূপ।
 * অ্যাম্বুলেন্স যায় হাসপাতাল/ক্লিনিকে, তাই গন্তব্য হাসপাতাল/ক্লিনিকের তালিকা থেকে (সার্ভারের amb_facilities: DGHS Facility
 * Registry, ডায়াগনস্টিক বাদ; server/lib/ambplace.js) — রোগীর জেলারটা আগে (জায়গা থাকলে কাছেরটা আগে), তারপর সারা দেশের
 * বড় হাসপাতাল (ঢাকা মেডিকেল, হৃদরোগ ইনস্টিটিউট, বিএসএমএমইউ, তাজউদ্দীন…)। খোঁজা সারা দেশে, বাংলা/ইংরেজি ("য়" একরকম)।
 * তালিকায় না থাকলে "অন্য জায়গা লিখুন"। মালিকের ভাড়ার তালিকার হাসপাতালের সারিও এখান থেকে (AmbFare.kt DestPicker)।
 */

/** তালিকার হাসপাতাল/ক্লিনিক */
data class Facility(val id: String, val nameEn: String, val nameBn: String, val type: String, val district: String, val upazilaId: Int?) {
    val name: String get() = if (isBangla) nameBn.ifBlank { nameEn } else nameEn
    fun dest() = Dest("hospital", upazilaId = upazilaId, district = district, name = name, facilityId = id)

    fun sub(): String = listOfNotNull(
        TYPE[type]?.let { tr(it.first, it.second) },
        Areas.upazila(upazilaId)?.label,
        Districts.label(district).ifBlank { null },
    ).joinToString(" · ")

    companion object {
        private val TYPE = mapOf(
            "govt_mch" to ("Medical college" to "মেডিকেল কলেজ"), "govt_specialized" to ("Specialised (govt)" to "বিশেষায়িত (সরকারি)"),
            "govt_district" to ("District hospital" to "জেলা হাসপাতাল"), "govt_uhc" to ("Upazila health complex" to "উপজেলা স্বাস্থ্য কমপ্লেক্স"),
            "govt_hospital" to ("Govt hospital" to "সরকারি হাসপাতাল"), "private_hospital" to ("Private hospital" to "বেসরকারি হাসপাতাল"),
            "clinic" to ("Clinic" to "ক্লিনিক"),
        )

        /** সার্ভারের ছোট রূপ: [id, ইংরেজি, বাংলা, ধরন, জেলা, উপজেলা] */
        fun parse(a: JSONArray) = Facility(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4), if (a.isNull(5)) null else a.optInt(5))
    }
}

object AmbPlaces {
    private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { Facility.parse(a.getJSONArray(it)) }

    /** জেলার তালিকা (q খালি) বা সারা দেশে খোঁজা; top = দেশের বড় হাসপাতাল */
    suspend fun facilities(district: String, q: String = "", near: Pair<Double, Double>? = null): Pair<List<Facility>, List<Facility>> {
        val b = JSONObject().put("action", "amb_facilities").put("district", district)
        if (q.isNotBlank()) b.put("q", q)
        if (near != null) b.put("lat", near.first).put("lng", near.second)
        val o = ServerApi.post("/api/community", b)
        return list(o.optJSONArray("items")) to list(o.optJSONArray("top"))
    }
}

/**
 * রোগীর "কোথায় যাবেন": বোতাম চাপলে তালিকা। বাছা থাকলে নাম আর "বদলান"।
 */
@Composable
fun DestField(label: String, district: String, near: Pair<Double, Double>?, value: Dest?, onChange: (Dest?) -> Unit, showError: Boolean = false, tag: String = "amb_dest") {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            AppIcon(R.drawable.ic_local_hospital, null, Modifier.size(18.dp))
            Text(
                value?.label()?.ifBlank { null } ?: tr("Choose a hospital or clinic…", "হাসপাতাল বা ক্লিনিক বেছে নিন…"),
                modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 2,
            )
            Text(if (value != null) tr("Change", "বদলান") else tr("Choose", "বাছুন"), style = MaterialTheme.typography.labelLarge)
        }
        if (showError && value == null) ErrorText(tr("Choose where to go", "\"কোথায় যাবেন\" বেছে নিন"))
    }
    if (open) {
        FacilityDialog(
            title = label, district = district, near = near, allowOther = true,
            onPick = { open = false; onChange(it) },
            onDismiss = { open = false },
        )
    }
}

/** তালিকার ডায়ালগ (মাঝখানের কার্ড): খোঁজার ঘর, "অন্য জায়গা লিখুন" (allowOther), জেলার তালিকা, দেশের বড় হাসপাতাল */
@Composable
fun FacilityDialog(title: String, district: String, near: Pair<Double, Double>?, allowOther: Boolean, onPick: (Dest) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    remember(context) { Areas.ensure(context); true }
    var query by remember { mutableStateOf("") }
    var base by remember { mutableStateOf<Pair<List<Facility>, List<Facility>>?>(null) }
    var remote by remember { mutableStateOf<List<Facility>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var other by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(district, near) {
        runCatching { AmbPlaces.facilities(district, near = near) }.onSuccess { base = it }.onFailure { errorMsg = it.message }
    }
    val q = query.trim()
    // খোঁজা: আগে জেলার তালিকায় সাথে সাথে, তারপর সার্ভারে সারা দেশে (৩০০ মি.সে. থেমে)
    LaunchedEffect(q) {
        remote = null
        if (q.length < 2) return@LaunchedEffect
        delay(300)
        remote = runCatching { AmbPlaces.facilities(district, q = q).first }.getOrNull()
    }
    val items = base?.first ?: emptyList()
    val top = base?.second ?: emptyList()
    val local: List<Facility>
    val topShown: List<Facility>
    if (q.isEmpty()) {
        val ids = items.map { it.id }.toSet()
        local = items.take(300)
        topShown = top.filter { it.id !in ids }
    } else {
        val words = Areas.bnNorm(q).lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        fun hit(f: Facility): Boolean {
            val hay = Areas.bnNorm(f.nameBn) + " " + f.nameEn.lowercase()
            return words.all { hay.contains(it) }
        }
        val l = (items + top).filter(::hit)
        val seen = l.map { it.id }.toSet()
        local = (l + (remote ?: emptyList()).filter { it.id !in seen }).take(80)
        topShown = emptyList()
    }

    CardDialog(onDismissRequest = onDismiss, color = MaterialTheme.colorScheme.surface, fillHeight = if (other == null) 0.9f else null, tag = "fac_dialog") {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            val o = other
            if (o != null) {
                OutlinedTextField(
                    o, { other = it.take(80) },
                    label = { Text(tr("Hospital, clinic or place name", "হাসপাতাল, ক্লিনিক বা জায়গার নাম")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("fac_other_input"),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { other = null }) { Text(tr("Back", "ফিরে যান")) }
                    TextButton(enabled = o.trim().length >= 3, onClick = { onPick(Dest("hospital", name = o.trim())) }, modifier = Modifier.testTag("fac_other_ok")) { Text(tr("OK", "ঠিক আছে")) }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("Search hospital or clinic", "হাসপাতাল বা ক্লিনিক খুঁজুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("fac_search"),
                )
                if (allowOther) {
                    OutlinedButton(onClick = { other = q }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("fac_other")) {
                        ButtonContent(false, tr("Write another place", "অন্য জায়গা লিখুন"), R.drawable.ic_edit)
                    }
                }
                ErrorText(errorMsg)
                LazyColumn(Modifier.weight(1f).testTag("fac_list")) {
                    if (base == null && errorMsg == null) {
                        item { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
                    }
                    if (local.isNotEmpty()) item {
                        SectionHead(
                            when {
                                q.isNotEmpty() -> tr("Results", "খোঁজার ফল")
                                near != null -> tr("In your district — nearest first", "আপনার জেলায় — কাছেরটা আগে")
                                else -> tr("In your district", "আপনার জেলায়")
                            },
                        )
                    }
                    items(local, key = { "l_" + it.id }) { f -> FacilityRow(f) { onPick(f.dest()) } }
                    if (topShown.isNotEmpty()) item { SectionHead(tr("Major hospitals in the country", "দেশের বড় হাসপাতাল")) }
                    items(topShown, key = { "t_" + it.id }) { f -> FacilityRow(f) { onPick(f.dest()) } }
                    if (q.isNotEmpty() && local.isEmpty() && remote != null) {
                        item {
                            Text(
                                if (allowOther) tr("Not found — use \"Write another place\".", "পাওয়া যায়নি — \"অন্য জায়গা লিখুন\" দিয়ে লিখুন।") else tr("Nothing found", "কিছু পাওয়া যায়নি"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun FacilityRow(f: Facility, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(R.drawable.ic_local_hospital, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge)
            Text(f.sub(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
