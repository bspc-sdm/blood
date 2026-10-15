package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Ambulance(
    val id: String,
    val name: String,
    val phone: String,
    val types: List<String>,
    val district: String,
    val upazilaId: Int?,
    val area: String,
    val fare: String,
    val available: Boolean,
    val rating: Double,
    val ratingCount: Int,
    /** শুধু নিজের অ্যাম্বুলেন্সে */
    val vehicleNo: String = "",
    val status: String = "",
)

object AmbulanceTypes {
    val ALL = listOf("general", "ac", "icu", "freezer")

    fun label(t: String): String = when (t) {
        "general" -> tr("General", "সাধারণ")
        "ac" -> tr("AC", "এসি")
        "icu" -> tr("ICU", "আইসিইউ")
        else -> tr("Freezer van", "ফ্রিজার ভ্যান")
    }
}

object Ambulances {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun parse(o: JSONObject) = Ambulance(
        o.str("id"), o.str("name"), o.str("phone"), o.optJSONArray("types").strings(), o.str("district"),
        o.intOrNull("upazilaId"), o.str("area"), o.str("fare"), o.optBoolean("available", true),
        o.optDouble("rating", 0.0), o.optInt("ratingCount"), o.str("vehicleNo"), o.str("status"),
    )

    private fun list(o: JSONObject): List<Ambulance> {
        val a = o.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    suspend fun near(district: String, upazilaId: Int?, type: String?): List<Ambulance> =
        list(call("ambulances", JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).apply { if (type != null) put("type", type) }))

    suspend fun mine(): List<Ambulance> = list(call("ambulance_mine"))

    /** পাঠালে আবার যাচাই লাগবে কি না ফেরত দেয় */
    suspend fun save(id: String?, name: String, phone: String, vehicleNo: String, types: List<String>, district: String, upazilaId: Int?, area: String, fare: String): Boolean =
        call(
            "ambulance_save",
            JSONObject().put("id", id ?: JSONObject.NULL).put("name", name).put("phone", phone).put("vehicleNo", vehicleNo)
                .put("types", JSONArray(types)).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("area", area).put("fare", fare).put("termsAccepted", true),
        ).optBoolean("pending")

    suspend fun setAvailable(id: String, available: Boolean) {
        call("ambulance_available", JSONObject().put("id", id).put("available", available))
    }

    suspend fun delete(id: String) {
        call("ambulance_delete", JSONObject().put("id", id))
    }

    suspend fun called(id: String) {
        call("ambulance_called", JSONObject().put("id", id))
    }

    suspend fun rate(id: String, rating: Int, comment: String) {
        call("ambulance_rate", JSONObject().put("id", id).put("rating", rating).put("comment", comment))
    }

    suspend fun adminPending(): List<Ambulance> = list(call("admin_ambulances"))

    suspend fun resolve(id: String, approve: Boolean) {
        call("admin_ambulance_resolve", JSONObject().put("id", id).put("approve", approve))
    }
}

fun statusText(s: String): String = when (s) {
    "approved" -> tr("Listed", "তালিকায় আছে")
    "pending" -> tr("Waiting for approval", "অনুমোদনের অপেক্ষায়")
    "rejected" -> tr("Not approved", "অনুমোদন হয়নি")
    "suspended" -> tr("Suspended after reports", "রিপোর্টের পর বন্ধ")
    else -> s
}

/** ৯৯৯ কার্ড: সবচেয়ে উপরে */
@Composable
fun EmergencyCallCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Emergency? Call 999", "জরুরি? ৯৯৯-এ ফোন করুন"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    tr("Free, 24 hours, works even with zero balance. They can send an ambulance.", "বিনামূল্যে, ২৪ ঘণ্টা, ব্যালেন্স না থাকলেও যায়। অ্যাম্বুলেন্স পাঠাতে পারে।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            // শান্ত রং: হালকা লাল কার্ডে টিল বাটন (গাঢ় লাল ভয় বাড়ায়)
            Button(onClick = { callPhone(context, "999") }) { ButtonContent(false, num("999"), R.drawable.ic_call) }
        }
    }
}

@Composable
fun AmbulanceScreen(profile: Profile, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { Areas.ensure(context) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var called by remember { mutableStateOf(setOf<String>()) }
    var rating by remember { mutableStateOf<Ambulance?>(null) }
    var reporting by remember { mutableStateOf<Ambulance?>(null) }

    LaunchedEffect(district, upazilaId, type) {
        val d = district ?: return@LaunchedEffect
        loading = true
        errorMsg = null
        runCatching { Ambulances.near(d, upazilaId, type) }.onSuccess { list = it }.onFailure { errorMsg = it.message }
        loading = false
    }

    Scaffold(topBar = { BackTopBar(tr("Ambulance", "অ্যাম্বুলেন্স"), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EmergencyCallCard() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == null, onClick = { type = null }, label = { Text(tr("All", "সব")) })
                        AmbulanceTypes.ALL.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(AmbulanceTypes.label(t)) })
                        }
                    }
                }
            }
            item {
                InfoText(infoMsg)
                ErrorText(errorMsg)
            }
            when {
                loading -> item { LoadingBox() }
                list.isEmpty() -> item {
                    EmptyState(
                        R.drawable.ic_ambulance,
                        tr("No ambulance listed in this area yet", "এই এলাকায় এখনো কোনো অ্যাম্বুলেন্স যোগ হয়নি"),
                        tr("Call 999 above. Know an ambulance service? Ask them to join from the Services tab.", "উপরের ৯৯৯-এ ফোন করুন। কোনো অ্যাম্বুলেন্স সার্ভিস চিনলে \"সেবা দিন\" ট্যাব থেকে যোগ হতে বলুন।"),
                        Modifier.padding(top = 24.dp),
                    )
                }
                else -> items(list, key = { it.id }) { a ->
                    AmbulanceCard(
                        a,
                        onCall = {
                            called = called + a.id
                            scope.launch { runCatching { Ambulances.called(a.id) } }
                            callPhone(context, a.phone)
                        },
                        onRate = if (a.id in called) ({ rating = a }) else null,
                        onReport = { reporting = a },
                    )
                }
            }
        }
    }

    rating?.let { a ->
        StarRatingDialog(
            title = tr("Rate ", "রেটিং: ") + a.name,
            onDismiss = { rating = null },
            onSubmit = { stars, comment ->
                Ambulances.rate(a.id, stars, comment)
                rating = null
                infoMsg = tr("Thanks for the rating!", "রেটিং দেওয়ার জন্য ধন্যবাদ!")
            },
        )
    }
    reporting?.let { a ->
        ReasonReportDialog(
            onDismiss = { reporting = null },
            onSubmit = { reason ->
                Moderation.report("ambulance", a.id, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
    }
}

@Composable
private fun AmbulanceCard(a: Ambulance, onCall: () -> Unit, onRate: (() -> Unit)?, onReport: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_ambulance, null, Modifier.size(26.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (a.available) {
                    Tag(tr("Available", "খালি আছে"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                } else {
                    Tag(tr("Busy", "ব্যস্ত"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(a.types.joinToString(" · ") { AmbulanceTypes.label(it) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            IconLine(R.drawable.ic_location_on, listOf(Areas.label(a.district, a.upazilaId), a.area).filter { it.isNotBlank() }.joinToString(" · "))
            if (a.fare.isNotBlank()) IconLine(R.drawable.ic_info, tr("Fare: ", "ভাড়া: ") + a.fare)
            RatingLine(a.rating, a.ratingCount)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCall) { ButtonContent(false, tr("Call", "ফোন করুন") + " " + num(a.phone), R.drawable.ic_call) }
                Spacer(Modifier.weight(1f))
                if (onRate != null) TextButton(onClick = onRate) { Text(tr("Rate", "রেটিং")) }
                IconButton(onClick = onReport) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
            }
        }
    }
}

/** তারা আর মন্তব্য দিয়ে রেটিং (অ্যাম্বুলেন্স, ফার্মেসি) */
@Composable
fun StarRatingDialog(title: String, onDismiss: () -> Unit, onSubmit: suspend (Int, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    (1..5).forEach { i ->
                        IconButton(onClick = { stars = i }) {
                            AppIcon(if (i <= stars) R.drawable.ic_star_fill else R.drawable.ic_star, num(i), Modifier.size(32.dp), MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(300) },
                    label = { Text(tr("Comment (optional)", "মন্তব্য (ঐচ্ছিক)")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && stars > 0, onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSubmit(stars, comment.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Submit", "জমা দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Later", "পরে")) } },
    )
}

/** কারণ লিখে রিপোর্ট */
@Composable
fun ReasonReportDialog(onDismiss: () -> Unit, onSubmit: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Report a problem", "সমস্যা জানান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Wrong number, overcharging, didn't come…", "ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = { Text(tr("What happened", "কী হয়েছে")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && reason.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSubmit(reason.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Report", "রিপোর্ট")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// "সেবা দিন": নিজের অ্যাম্বুলেন্স
// ------------------------------------------------------------

@Composable
fun MyAmbulancesSection(profile: Profile) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Ambulance?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        runCatching { Ambulances.mine() }.onSuccess { list = it }.onFailure { errorMsg = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoText(infoMsg)
        ErrorText(errorMsg)
        list.forEach { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            SmallNote(a.vehicleNo + " · " + statusText(a.status))
                        }
                        IconButton(onClick = { editing = a }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
                    }
                    if (a.status == "approved") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Available now", "এখন খালি আছি"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = a.available, onCheckedChange = { on ->
                                scope.launch {
                                    runCatching { Ambulances.setAvailable(a.id, on) }.onSuccess { reload++ }.onFailure { errorMsg = it.message }
                                }
                            })
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            ButtonContent(false, tr("Add ambulance", "অ্যাম্বুলেন্স যোগ করুন"), R.drawable.ic_add)
        }
    }

    if (adding || editing != null) {
        AmbulanceEditDialog(
            existing = editing,
            profile = profile,
            onDismiss = { adding = false; editing = null },
            onSaved = { pending ->
                adding = false
                editing = null
                infoMsg = if (pending) tr("Sent for approval. It will be listed after an admin checks it.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন যাচাই করলে তালিকায় আসবে।") else tr("Saved", "সেভ হয়েছে")
                reload++
            },
            onDeleted = {
                editing = null
                reload++
            },
        )
    }
}

@Composable
private fun AmbulanceEditDialog(existing: Ambulance?, profile: Profile, onDismiss: () -> Unit, onSaved: (Boolean) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var vehicleNo by remember { mutableStateOf(existing?.vehicleNo ?: "") }
    var types by remember { mutableStateOf(existing?.types?.toSet() ?: setOf("general")) }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var area by remember { mutableStateOf(existing?.area ?: "") }
    var fare by remember { mutableStateOf(existing?.fare ?: "") }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (existing == null) tr("Add ambulance", "অ্যাম্বুলেন্স যোগ") else tr("Edit ambulance", "অ্যাম্বুলেন্স বদলান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Service / driver name", "সার্ভিস বা চালকের নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text(tr("Phone for calls", "যে নম্বরে ফোন আসবে")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(vehicleNo, { vehicleNo = it.take(40) }, label = { Text(tr("Vehicle registration no.", "গাড়ির রেজিস্ট্রেশন নম্বর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
                ChipRows(AmbulanceTypes.ALL, types, label = { AmbulanceTypes.label(it) }) { t -> types = if (t in types) types - t else types + t }
                Text(tr("Base area", "কোথা থেকে চলে"), style = MaterialTheme.typography.labelLarge)
                AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                OutlinedTextField(area, { area = it.take(120) }, label = { Text(tr("Where you go (optional)", "কোন কোন এলাকায় যান (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fare, { fare = it.take(120) }, label = { Text(tr("Fare (optional)", "ভাড়া (ঐচ্ছিক)")) }, placeholder = { Text(tr("e.g. Within city 800 Tk, per km 40 Tk", "যেমন: শহরের মধ্যে ৮০০ টাকা, প্রতি কিমি ৪০ টাকা")) }, modifier = Modifier.fillMaxWidth())
                ServiceTermsCheck(agreed) { agreed = it }
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            runCatching { Ambulances.delete(existing.id) }.onSuccess { onDeleted() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }, enabled = !busy) { Text(tr("Remove this ambulance", "এই অ্যাম্বুলেন্স সরিয়ে দিন"), color = MaterialTheme.colorScheme.error) }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val d = district
                errorMsg = when {
                    name.isBlank() -> tr("Enter the service or driver name", "সার্ভিস বা চালকের নাম লিখুন")
                    phone.length < 11 -> tr("Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন")
                    vehicleNo.isBlank() -> tr("Enter the vehicle registration number", "গাড়ির রেজিস্ট্রেশন নম্বর দিন")
                    types.isEmpty() -> tr("Choose the ambulance type", "অ্যাম্বুলেন্সের ধরন বেছে নিন")
                    d == null -> tr("Choose a district", "জেলা বেছে নিন")
                    !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                    else -> null
                }
                if (errorMsg != null || d == null) return@TextButton
                scope.launch {
                    busy = true
                    runCatching { Ambulances.save(existing?.id, name.trim(), phone, vehicleNo.trim(), types.toList(), d, upazilaId, area.trim(), fare.trim()) }
                        .onSuccess { onSaved(it) }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** অ্যাম্বুলেন্স/ফার্মেসির শর্তে সম্মতি */
@Composable
fun ServiceTermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(tr("I accept the terms for service providers", "সেবাদাতাদের শর্ত মেনে নিচ্ছি"), style = MaterialTheme.typography.bodyMedium)
            Text(
                tr("Read the terms", "শর্তগুলো পড়ুন"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { openUrl(context, TERMS_URL) },
            )
        }
    }
}
