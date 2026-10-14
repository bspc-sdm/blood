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
    var lat by remember { mutableStateOf(profile.lat) }
    var lng by remember { mutableStateOf(profile.lng) }
    var place by remember { mutableStateOf(profile.placeName ?: "") }
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
                    Text(place.ifBlank { "বেছে নেওয়া হয়নি" }, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { picking = true }) { Text("বদলান") }
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
            "রিকোয়েস্ট দেওয়ার পর আশেপাশের যোগ্য ডোনাররা নোটিফিকেশন পাবেন। কেউ \"রক্ত দেব\" চাপলে আপনি তার নম্বর দেখবেন, আর সে আপনার নম্বর দেখবে।",
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
                            RequestRepository.create(
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
                val eligible = Rules.isEligible(profile.lastDonationMillis)
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

                    resp == null && !eligible -> Text(
                        "আপনি ${Rules.formatDate(Rules.nextEligibleMillis(profile.lastDonationMillis) ?: 0L)} থেকে আবার রক্ত দিতে পারবেন।"
                    )

                    resp == null -> {
                        Button(
                            onClick = { act { RequestRepository.respond(r.id, uid!!, profile) } },
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
                                onClick = { act { RequestRepository.respond(r.id, uid!!, profile) } },
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
                if (r.isActive) {
                    Text(
                        "আশেপাশের যোগ্য ডোনাররা সাধারণত ১৫–৩০ মিনিটের মধ্যে নোটিফিকেশন পান। কেউ রাজি হলে নিচে তার নাম ও নম্বর দেখাবে।",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { reload++ }) { Text("🔄 রিফ্রেশ") }

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
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("রিকোয়েস্ট বন্ধ করবেন?") },
            text = { Text("বন্ধ করলে আর কোনো ডোনার এটা দেখবেন না বা নোটিফিকেশন পাবেন না।") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    act { RequestRepository.close(requestId) }
                }) { Text("বন্ধ করুন") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("না") } },
        )
    }
}
