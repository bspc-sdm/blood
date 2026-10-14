@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// নতুন রিকোয়েস্ট
// ------------------------------------------------------------

@Composable
fun NewRequestScreen(profile: Profile, onCreated: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf("") }
    var bags by remember { mutableIntStateOf(1) }
    var hospital by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var neededBy by remember { mutableLongStateOf(Rules.todayUtcMidnight()) }
    var urgent by remember { mutableStateOf(false) }
    // প্রাইভেসি: রিকোয়েস্ট সবাই দেখে, তাই বাসার এলাকা আপনা থেকে বসানো হয় না;
    // রোগী যেখানে আছেন সেই এলাকা বেছে নিতে হবে
    var lat by remember { mutableStateOf<Double?>(null) }
    var lng by remember { mutableStateOf<Double?>(null) }
    var place by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    if (picking) {
        LocationPicker(
            title = "রোগী কোথায় আছেন?",
            subtitle = "এখন হাসপাতালে থাকলে বর্তমান লোকেশন দিন, নইলে হাসপাতালের জেলা বেছে নিন।",
            busy = false,
            errorMsg = null,
            onPicked = { la, ln, pl, _ ->
                lat = la
                lng = ln
                place = pl
                picking = false
            },
            onCancel = { picking = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("নতুন রক্তের রিকোয়েস্ট", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("রোগীর রক্তের গ্রুপ", style = MaterialTheme.typography.titleMedium)
        BloodGroupChips(selected = group, onSelect = { group = it })

        Text("কত ব্যাগ লাগবে", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                FilterChip(selected = bags == n, onClick = { bags = n }, label = { Text("$n") })
            }
        }

        OutlinedTextField(
            value = hospital,
            onValueChange = { hospital = it },
            label = { Text("হাসপাতালের নাম ও ঠিকানা") },
            modifier = Modifier.fillMaxWidth(),
        )

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("এলাকা (এখান থেকে আশেপাশের ডোনার খোঁজা হবে)", style = MaterialTheme.typography.labelMedium)
                    Text(place.ifBlank { "বেছে নেওয়া হয়নি — \"বেছে নিন\" চাপুন" }, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { picking = true }) { Text(if (place.isBlank()) "বেছে নিন" else "বদলান") }
            }
        }

        Text("কবে লাগবে", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text(Rules.formatDate(neededBy))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = urgent, onCheckedChange = { urgent = it })
            Text("জরুরি (আজকের মধ্যেই লাগবে)")
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("অতিরিক্ত তথ্য (ঐচ্ছিক)") },
            placeholder = { Text("যেমন: রোগীর অবস্থা, কোন ওয়ার্ড") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "রিকোয়েস্ট দেওয়ার সাথে সাথে আশেপাশের যোগ্য ডোনাররা নোটিফিকেশন পাবেন। কেউ \"রক্ত দেব\" চাপলে আপনি তার নম্বর দেখবেন, আর সে আপনার নম্বর দেখবে।",
            style = MaterialTheme.typography.bodySmall,
        )

        ErrorText(errorMsg)

        Button(
            onClick = {
                val uid = AuthRepository.currentUid
                val la = lat
                val ln = lng
                errorMsg = when {
                    uid == null -> "আবার লগইন করুন"
                    group.isEmpty() -> "রোগীর রক্তের গ্রুপ বেছে নিন"
                    hospital.isBlank() -> "হাসপাতালের নাম লিখুন"
                    la == null || ln == null -> "এলাকা বেছে নিন"
                    neededBy < Rules.todayUtcMidnight() -> "আজ বা পরের কোনো তারিখ দিন"
                    else -> null
                }
                if (errorMsg == null && uid != null && la != null && ln != null) {
                    scope.launch {
                        busy = true
                        runCatching {
                            val open = RequestRepository.mine(uid).count { it.isActive }
                            if (open >= Rules.MAX_OPEN_REQUESTS) {
                                throw IllegalStateException(
                                    "একসাথে সর্বোচ্চ ${Rules.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়"
                                )
                            }
                            val id = RequestRepository.create(
                                BloodRequest(
                                    requesterUid = uid,
                                    requesterName = profile.name,
                                    patientGroup = group,
                                    bags = bags,
                                    hospital = hospital.trim(),
                                    placeName = place,
                                    lat = la,
                                    lng = ln,
                                    neededByMillis = neededBy,
                                    urgent = urgent,
                                    note = note.trim(),
                                ),
                                profile.phone,
                            )
                            // আশেপাশের ডোনারদের জানানো; না গেলে রিকোয়েস্টের পাতায় আবার চেষ্টার বাটন থাকে
                            runCatching { ServerApi.notifyRequest(id) }
                            id
                        }.onSuccess { onCreated(it) }
                            .onFailure { errorMsg = it.message ?: "রিকোয়েস্ট দেওয়া যায়নি" }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonContent(busy, "রিকোয়েস্ট দিন")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
    }

    if (showDate) {
        DatePickerPopup(
            initial = neededBy,
            onPicked = { picked -> if (picked != null) neededBy = picked },
            onDismiss = { showDate = false },
        )
    }
}

// ------------------------------------------------------------
// ডোনারের দিক থেকে রিকোয়েস্ট
// ------------------------------------------------------------

@Composable
fun RequestDetailScreen(
    requestId: String,
    profile: Profile,
    onProfileChanged: (Profile) -> Unit,
    onOpenMine: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = AuthRepository.currentUid
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<BloodRequest?>(null) }
    var myResponse by remember { mutableStateOf<DonorResponse?>(null) }
    var contact by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmReport by remember { mutableStateOf(false) }
    var showChecklist by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching {
            val r = RequestRepository.get(requestId)
            val resp = if (uid != null && r != null && r.requesterUid != uid) {
                RequestRepository.myResponse(requestId, uid)
            } else {
                null
            }
            val c = if (resp != null) RequestRepository.requesterContact(requestId) else null
            Triple(r, resp, c)
        }.onSuccess { (r, resp, c) ->
            request = r
            myResponse = resp
            contact = c
        }.onFailure { errorMsg = "লোড হয়নি: ${it.message}" }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = "হয়নি: ${it.message}" }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("← ফিরে যান") }
        val r = request
        when {
            loading && r == null -> LoadingBox()
            r == null -> ErrorText(errorMsg ?: "রিকোয়েস্টটা পাওয়া যায়নি")
            else -> {
                val pLat = profile.lat
                val pLng = profile.lng
                val distance = if (pLat != null && pLng != null) Geo.distanceKm(pLat, pLng, r.lat, r.lng) else null
                RequestInfo(r, distance)

                val resp = myResponse
                val compatible = BloodCompat.canDonate(profile.bloodGroup, r.patientGroup)
                val notEligible = Rules.notEligibleReason(profile)
                val eligible = notEligible == null
                when {
                    r.requesterUid == uid -> {
                        Text("এটা আপনার নিজের রিকোয়েস্ট।")
                        Button(onClick = { onOpenMine(r.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("সাড়া দেওয়া ডোনারদের দেখুন")
                        }
                    }

                    resp == null && !r.isActive -> {}

                    resp == null && !compatible -> Text(
                        "আপনার গ্রুপ (${profile.bloodGroup}) থেকে ${r.patientGroup} গ্রুপের রোগীকে রক্ত দেওয়া যায় না। পরিচিত কারো সাথে শেয়ার করতে পারেন।"
                    )

                    resp == null && !eligible -> Text(notEligible ?: "")

                    resp == null -> {
                        Button(
                            onClick = { showChecklist = true },
                            enabled = !busy && uid != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { ButtonContent(busy, "🩸 রক্ত দেব") }
                        Text(
                            "চাপলে রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন, আর আপনি তার নম্বর দেখবেন।",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    resp.status == "accepted" -> {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("রক্তপ্রার্থীর সাথে যোগাযোগ করুন", fontWeight = FontWeight.Bold)
                                val c = contact
                                if (c != null) {
                                    Text(c.first)
                                    Text(c.second, style = MaterialTheme.typography.titleLarge)
                                    Button(onClick = { callPhone(context, c.second) }) { Text("📞 কল করুন") }
                                } else {
                                    Text("নম্বর লোড হয়নি, রিফ্রেশ করুন।")
                                }
                            }
                        }
                        Button(
                            onClick = {
                                act {
                                    RequestRepository.setResponseStatus(r.id, uid!!, "donated")
                                    val day = ProfileRepository.markDonatedToday(uid)
                                    onProfileChanged(profile.copy(lastDonationMillis = day))
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { ButtonContent(busy, "✅ রক্ত দিয়েছি") }
                        TextButton(
                            onClick = { act { RequestRepository.setResponseStatus(r.id, uid!!, "cancelled") } },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("আর পারছি না, বাতিল করুন") }
                    }

                    resp.status == "donated" -> Text(
                        "ধন্যবাদ! আপনি একজন মানুষের জীবন বাঁচাতে সাহায্য করেছেন ❤️",
                        fontWeight = FontWeight.Bold,
                    )

                    resp.status == "declined" -> Text(
                        "রক্তপ্রার্থী অন্য ডোনারের কাছ থেকে রক্ত নিচ্ছেন। আপনার আগ্রহের জন্য ধন্যবাদ!"
                    )

                    else -> {
                        Text("আপনি এই রিকোয়েস্টে রক্ত দেওয়া বাতিল করেছেন।")
                        if (r.isActive && compatible && eligible) {
                            TextButton(
                                onClick = { showChecklist = true },
                                enabled = !busy,
                            ) { Text("আবার রাজি হন") }
                        }
                    }
                }

                ErrorText(errorMsg)
                InfoText(infoMsg)
                Disclaimer()
                if (r.requesterUid != uid) {
                    TextButton(onClick = { confirmReport = true }) { Text("⚠️ ভুয়া মনে হলে রিপোর্ট করুন") }
                }
            }
        }
    }

    if (showChecklist) {
        DonationChecklistDialog(
            onDismiss = { showChecklist = false },
            onConfirm = {
                showChecklist = false
                act { ServerApi.respond(requestId) }
            },
        )
    }

    if (confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text("রিপোর্ট করবেন?") },
            text = { Text("রিকোয়েস্টটা ভুয়া বা অপব্যবহার মনে হলে রিপোর্ট করুন। আমরা দেখে ব্যবস্থা নেব।") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReport = false
                    val u = uid ?: return@TextButton
                    scope.launch {
                        runCatching { RequestRepository.report(requestId, u) }
                            .onSuccess { infoMsg = "রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।" }
                            .onFailure { errorMsg = "রিপোর্ট পাঠানো যায়নি: ${it.message}" }
                    }
                }) { Text("রিপোর্ট করুন") }
            },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text("বাতিল") } },
        )
    }
}

// ------------------------------------------------------------
// রক্তপ্রার্থীর দিক থেকে নিজের রিকোয়েস্ট
// ------------------------------------------------------------

@Composable
fun MyRequestDetailScreen(requestId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<BloodRequest?>(null) }
    var responses by remember { mutableStateOf<List<DonorResponse>>(emptyList()) }
    var confirmClose by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching {
            RequestRepository.get(requestId) to RequestRepository.responses(requestId)
        }.onSuccess { (r, list) ->
            request = r
            responses = list
        }.onFailure { errorMsg = "লোড হয়নি: ${it.message}" }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = "হয়নি: ${it.message}" }
            busy = false
        }
    }

    val editTarget = request
    if (editing && editTarget != null) {
        EditRequestScreen(
            r = editTarget,
            onSaved = { notified ->
                editing = false
                infoMsg = if (notified > 0) {
                    "সেভ হয়েছে। রাজি থাকা $notified জন ডোনারকে পরিবর্তন জানানো হয়েছে।"
                } else {
                    "সেভ হয়েছে।"
                }
                reload++
            },
            onCancel = { editing = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("← ফিরে যান") }
        val r = request
        when {
            loading && r == null -> LoadingBox()
            r == null -> ErrorText(errorMsg ?: "রিকোয়েস্টটা পাওয়া যায়নি")
            else -> {
                RequestInfo(r, null)
                if (r.isActive && r.notified) {
                    Text(
                        if (r.notifiedCount > 0) {
                            "আশেপাশের ${r.notifiedCount} জন যোগ্য ডোনারকে নোটিফিকেশন পাঠানো হয়েছে। কেউ রাজি হলে আপনিও নোটিফিকেশন পাবেন, আর নিচে তার নাম ও নম্বর দেখাবে।"
                        } else {
                            "এই মুহূর্তে আশেপাশে নোটিফিকেশন চালু থাকা কোনো যোগ্য ডোনার পাওয়া যায়নি। রিকোয়েস্টটা খোলা থাকবে, অ্যাপ খুললে ডোনাররা তালিকায় দেখতে পাবেন। পরিচিতদের সাথেও শেয়ার করুন।"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (r.isActive && !r.notified) {
                    Text(
                        "আশেপাশের ডোনারদের এখনো জানানো যায়নি।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = {
                            act {
                                val n = ServerApi.notifyRequest(r.id)
                                infoMsg = "$n জন ডোনারকে জানানো হয়েছে"
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonContent(busy, "📣 আশেপাশের ডোনারদের জানান") }
                }
                InfoText(infoMsg)
                Row {
                    TextButton(onClick = { reload++ }) { Text("🔄 রিফ্রেশ") }
                    if (r.isActive) {
                        TextButton(
                            onClick = {
                                infoMsg = null
                                editing = true
                            },
                            enabled = !busy,
                        ) { Text("✏️ এডিট করুন") }
                    }
                }

                val willing = responses.filter { it.status == "accepted" || it.status == "donated" }
                Text("রাজি হয়েছেন: ${willing.size} জন", style = MaterialTheme.typography.titleMedium)
                willing.forEach { d ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.donorGroup, color = BloodRed, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(d.donorName, fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (d.status == "donated") "রক্ত দিয়েছেন ✅" else "রক্ত দিতে রাজি")
                            Text(d.donorPhone, style = MaterialTheme.typography.titleMedium)
                            Row {
                                Button(onClick = { callPhone(context, d.donorPhone) }) { Text("📞 কল করুন") }
                                if (d.status == "accepted" && r.isActive) {
                                    TextButton(
                                        onClick = { act { RequestRepository.setResponseStatus(r.id, d.donorUid, "declined") } },
                                        enabled = !busy,
                                    ) { Text("বাদ দিন") }
                                }
                            }
                        }
                    }
                }

                ErrorText(errorMsg)
                if (r.isActive) {
                    Button(
                        onClick = { confirmClose = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonContent(busy, "✅ রক্ত পেয়েছি — রিকোয়েস্ট বন্ধ করুন") }
                }
            }
        }
    }

    if (confirmClose) {
        val willing = responses.filter { it.status == "accepted" || it.status == "donated" }
        var donated by remember { mutableStateOf(willing.filter { it.status == "donated" }.map { it.donorUid }.toSet()) }
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("রিকোয়েস্ট বন্ধ করবেন?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("বন্ধ করলে আর কোনো ডোনার এটা দেখবেন না বা নোটিফিকেশন পাবেন না।")
                    if (willing.isNotEmpty()) {
                        Text("কে কে রক্ত দিয়েছেন? (তাদের কাছে ১২০ দিন নতুন রিকোয়েস্ট যাবে না)", fontWeight = FontWeight.SemiBold)
                        willing.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = d.donorUid in donated,
                                    onCheckedChange = { on ->
                                        donated = if (on) donated + d.donorUid else donated - d.donorUid
                                    },
                                )
                                Text("${d.donorName} (${d.donorGroup})")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    val chosen = donated.toList()
                    act { ServerApi.closeRequest(requestId, chosen) }
                }) { Text("বন্ধ করুন") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("না") } },
        )
    }
}

/**
 * "রক্ত দেব" চাপার আগে নিজের স্বাস্থ্য যাচাই। উত্তরগুলো কোথাও সেভ হয় না।
 * চূড়ান্ত যাচাই ব্লাড ব্যাংক করবে।
 */
private val DONATION_CHECKLIST = listOf(
    "আজ আমি সুস্থ বোধ করছি, আর গত ২৪ ঘণ্টায় ভালো ঘুম ও খাওয়া হয়েছে",
    "গত ৭ দিনে জ্বর, সর্দি-কাশি বা অ্যান্টিবায়োটিক খাইনি",
    "গত ৬ মাসে বড় অপারেশন, ট্যাটু, কান-নাক ফোঁড়ানো বা রক্ত নেওয়া হয়নি",
    "আমার হেপাটাইটিস বি/সি, এইচআইভি, ম্যালেরিয়া বা হৃদরোগ নেই",
    "আমি গর্ভবতী বা শিশুকে বুকের দুধ খাওয়াচ্ছি না (নারীদের জন্য)",
)

@Composable
fun DonationChecklistDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var checked by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("রক্ত দেওয়ার আগে নিশ্চিত করুন") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DONATION_CHECKLIST.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = i in checked,
                            onCheckedChange = { on -> checked = if (on) checked + i else checked - i },
                        )
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "এর কোনোটা না মিললে এবার রক্ত দেবেন না। চূড়ান্ত পরীক্ষা ব্লাড ব্যাংক করবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = checked.size == DONATION_CHECKLIST.size) {
                Text("🩸 রক্ত দেব")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

// ------------------------------------------------------------
// রিকোয়েস্ট এডিট (রক্তপ্রার্থী)
// ------------------------------------------------------------

/**
 * ব্যাগ, হাসপাতাল, তারিখ, জরুরি আর অতিরিক্ত তথ্য বদলানো যায়।
 * রক্তের গ্রুপ আর এলাকা বদলানো যায় না: ডোনারদের এগুলো দেখেই বাছাই করে জানানো হয়েছে,
 * তাই এগুলো ভুল হলে রিকোয়েস্ট বন্ধ করে নতুন রিকোয়েস্ট দিতে হবে।
 */
@Composable
fun EditRequestScreen(r: BloodRequest, onSaved: (Int) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bags by remember { mutableIntStateOf(r.bags) }
    var hospital by remember { mutableStateOf(r.hospital) }
    var note by remember { mutableStateOf(r.note) }
    var neededBy by remember { mutableLongStateOf(r.neededByMillis) }
    var urgent by remember { mutableStateOf(r.urgent) }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onCancel) { Text("← ফিরে যান") }
        Text("রিকোয়েস্ট এডিট", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("রোগীর গ্রুপ: ${r.patientGroup} · এলাকা: ${r.placeName}", fontWeight = FontWeight.SemiBold)
                Text(
                    "গ্রুপ আর এলাকা দেখেই ডোনারদের জানানো হয়েছে, তাই এ দুটো বদলানো যায় না। ভুল হলে রিকোয়েস্টটা বন্ধ করে নতুন রিকোয়েস্ট দিন।",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("কত ব্যাগ লাগবে", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // আগে ৫-এর বেশি দেওয়া থাকলে সেটাও দেখানো
            ((1..5).toList() + listOf(r.bags).filter { it > 5 }).forEach { n ->
                FilterChip(selected = bags == n, onClick = { bags = n }, label = { Text("$n") })
            }
        }

        OutlinedTextField(
            value = hospital,
            onValueChange = { hospital = it.take(120) },
            label = { Text("হাসপাতালের নাম ও ঠিকানা") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("কবে লাগবে", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text(Rules.formatDate(neededBy))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = urgent, onCheckedChange = { urgent = it })
            Text("জরুরি (আজকের মধ্যেই লাগবে)")
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            label = { Text("অতিরিক্ত তথ্য (ঐচ্ছিক)") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "যারা আগেই \"রক্ত দেব\" চেপেছেন, তারা পরিবর্তনের নোটিফিকেশন পাবেন। একটা রিকোয়েস্ট সর্বোচ্চ ১০ বার এডিট করা যায়।",
            style = MaterialTheme.typography.bodySmall,
        )

        ErrorText(errorMsg)

        Button(
            onClick = {
                errorMsg = when {
                    hospital.isBlank() -> "হাসপাতালের নাম লিখুন"
                    neededBy < Rules.todayUtcMidnight() -> "আজ বা পরের কোনো তারিখ দিন"
                    else -> null
                }
                if (errorMsg == null) {
                    scope.launch {
                        busy = true
                        runCatching {
                            ServerApi.editRequest(r.id, bags, hospital.trim(), neededBy, urgent, note.trim())
                        }.onSuccess { onSaved(it) }
                            .onFailure { errorMsg = it.message ?: "সেভ হয়নি" }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { ButtonContent(busy, "সেভ করুন") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
    }

    if (showDate) {
        DatePickerPopup(
            initial = neededBy,
            onPicked = { picked -> if (picked != null) neededBy = picked },
            onDismiss = { showDate = false },
        )
    }
}
