@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// ফর্মের অংশ (নতুন ও এডিট দুটোতেই)
// ------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun BagsPicker(bags: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ((1..5).toList() + listOf(bags).filter { it > 5 }).forEach { n ->
            FilterChip(selected = bags == n, onClick = { onChange(n) }, label = { Text(num(n)) })
        }
    }
}

@Composable
private fun DateTimeFields(
    date: Long,
    hour: Int?,
    minute: Int?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickDate, modifier = Modifier.weight(1f)) {
            AppIcon(R.drawable.ic_calendar_month, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(Rules.formatDate(date), maxLines = 1)
        }
        OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f)) {
            AppIcon(R.drawable.ic_schedule, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (hour != null && minute != null) Rules.formatTime(hour, minute) else tr("Time", "সময়"), maxLines = 1)
        }
    }
}

@Composable
private fun TravelFareSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("I'll pay donors' travel cost", "ডোনারের যাতায়াত খরচ দেব"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onChange)
        }
        Text(
            tr(
                "Travel cost only — buying or selling blood is not allowed. Donors farther away may need this.",
                "শুধু যাতায়াত খরচ — রক্ত কেনাবেচা নিষিদ্ধ। দূরের ডোনারদের এটা লাগতে পারে।",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UrgentSwitch(urgent: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Urgent", "জরুরি"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = urgent, onCheckedChange = onChange)
    }
}

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
    var neededHour by remember { mutableStateOf<Int?>(null) }
    var neededMinute by remember { mutableStateOf<Int?>(null) }
    var urgent by remember { mutableStateOf(false) }
    var travelFare by remember { mutableStateOf(false) }
    // প্রাইভেসি: রিকোয়েস্ট সবাই দেখে, তাই বাসার এলাকা আপনা থেকে বসানো হয় না
    var lat by remember { mutableStateOf<Double?>(null) }
    var lng by remember { mutableStateOf<Double?>(null) }
    var place by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // ফোনের "পেছনে" চাপলে শুধু এলাকা বাছাই বন্ধ হবে, পুরো ফর্ম নয়
    BackHandler(enabled = picking) { picking = false }
    if (picking) {
        LocationPicker(
            title = tr("Where is the patient?", "রোগী কোথায় আছেন?"),
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

    Scaffold(topBar = { BackTopBar(tr("Create Request", "রিকোয়েস্ট দিন"), onCancel) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FieldLabel(tr("Patient's blood group", "রোগীর রক্তের গ্রুপ"))
            BloodGroupChips(selected = group, onSelect = { group = it })

            FieldLabel(tr("Bags needed", "কত ব্যাগ"))
            BagsPicker(bags) { bags = it }

            // লিখতে লিখতে নিচে হাসপাতালের তালিকা (বেছে নেওয়া এলাকার, নইলে নিজের জেলার)
            HospitalField(hospital, { hospital = it }, place.ifBlank { null } ?: profile.placeName)

            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (place.isBlank()) tr("Choose area", "এলাকা বেছে নিন") else Districts.label(place))
            }

            FieldLabel(tr("Needed by", "কবে লাগবে"))
            DateTimeFields(neededBy, neededHour, neededMinute, { showDate = true }, { showTime = true })

            UrgentSwitch(urgent) { urgent = it }
            TravelFareSwitch(travelFare) { travelFare = it }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text(tr("Note (optional)", "অতিরিক্ত তথ্য (ঐচ্ছিক)")) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            ErrorText(errorMsg)

            Button(
                onClick = {
                    val uid = AuthRepository.currentUid
                    val la = lat
                    val ln = lng
                    val h = neededHour
                    val m = neededMinute
                    errorMsg = when {
                        uid == null -> tr("Please log in again", "আবার লগইন করুন")
                        group.isEmpty() -> tr("Choose the patient's blood group", "রোগীর রক্তের গ্রুপ বেছে নিন")
                        hospital.isBlank() -> tr("Enter the hospital", "হাসপাতালের নাম লিখুন")
                        la == null || ln == null -> tr("Choose the area", "এলাকা বেছে নিন")
                        neededBy < Rules.todayUtcMidnight() -> tr("Pick today or a later date", "আজ বা পরের কোনো তারিখ দিন")
                        h == null || m == null -> tr("Pick the time", "সময় বেছে নিন")
                        Rules.dhakaMillis(neededBy, h, m) <= System.currentTimeMillis() ->
                            tr("That time has passed", "এই সময় পার হয়ে গেছে")
                        else -> null
                    }
                    if (errorMsg == null && uid != null && la != null && ln != null && h != null && m != null) {
                        scope.launch {
                            busy = true
                            runCatching {
                                val open = RequestRepository.mine(uid).count { it.isActive }
                                if (open >= Rules.MAX_OPEN_REQUESTS) {
                                    throw IllegalStateException(
                                        tr(
                                            "You can keep up to ${num(Rules.MAX_OPEN_REQUESTS)} open requests",
                                            "একসাথে সর্বোচ্চ ${num(Rules.MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়",
                                        )
                                    )
                                }
                                val id = RequestRepository.create(
                                    BloodRequest(
                                        requesterUid = uid,
                                        requesterName = profile.name,
                                        patientGroup = group,
                                        bags = bags,
                                        hospital = TitleCase.name(hospital),
                                        placeName = place,
                                        lat = la,
                                        lng = ln,
                                        neededByMillis = neededBy,
                                        neededAtMillis = Rules.dhakaMillis(neededBy, h, m),
                                        urgent = urgent,
                                        travelFare = travelFare,
                                        note = note.trim(),
                                    ),
                                    profile.phone,
                                )
                                // আশেপাশের ডোনারদের জানানো; না গেলে রিকোয়েস্টের পাতায় আবার চেষ্টার বাটন থাকে
                                runCatching { ServerApi.notifyRequest(id) }
                                id
                            }.onSuccess { onCreated(it) }
                                .onFailure { errorMsg = it.message ?: tr("Couldn't create the request", "রিকোয়েস্ট দেওয়া যায়নি") }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Create Request", "রিকোয়েস্ট দিন"), R.drawable.ic_send) }
        }
    }

    if (showDate) {
        DatePickerPopup(
            initial = neededBy,
            onPicked = { picked -> if (picked != null) neededBy = picked },
            onDismiss = { showDate = false },
            minDate = Rules.todayUtcMidnight(),
            maxDate = Rules.todayUtcMidnight() + 30 * DAY_MS,
        )
    }
    if (showTime) {
        TimePickerPopup(
            hour = neededHour,
            minute = neededMinute,
            onPicked = { h, m ->
                neededHour = h
                neededMinute = m
            },
            onDismiss = { showTime = false },
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
            // নম্বর শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার পড়তে পারেন, আর রিকোয়েস্ট খোলা থাকলে
            // (বাতিল/বাদ পড়ার পর পড়তে গেলে অনুমতি মেলে না, তাতে পুরো পাতা লোড আটকে যেত)
            val canSeeContact = r != null && r.status == "open" &&
                (resp?.status == "accepted" || resp?.status == "donated")
            val c = if (canSeeContact) runCatching { RequestRepository.requesterContact(requestId) }.getOrNull() else null
            Triple(r, resp, c)
        }.onSuccess { (r, resp, c) ->
            request = r
            myResponse = resp
            contact = c
        }.onFailure { errorMsg = tr("Couldn't load", "লোড হয়নি") }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message ?: tr("Something went wrong", "কিছু একটা সমস্যা হয়েছে") }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(tr("Blood Request", "রক্তের রিকোয়েস্ট"), onBack) {
                val r = request
                if (r != null && r.isActive) {
                    IconButton(onClick = { shareRequest(context, r) }) { AppIcon(R.drawable.ic_share, tr("Share", "শেয়ার")) }
                }
                if (r != null && r.requesterUid != uid) {
                    IconButton(onClick = { confirmReport = true }) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val r = request
            when {
                loading && r == null -> LoadingBox()
                r == null -> ErrorText(errorMsg ?: tr("Request not found", "রিকোয়েস্টটা পাওয়া যায়নি"))
                else -> {
                    val pLat = profile.lat
                    val pLng = profile.lng
                    val distance = if (pLat != null && pLng != null) Geo.distanceKm(pLat, pLng, r.lat, r.lng) else null
                    val resp = myResponse
                    val compatible = BloodCompat.canDonate(profile.bloodGroup, r.patientGroup)
                    val notEligible = Rules.notEligibleReason(profile)
                    val eligible = notEligible == null

                    if (resp?.status == "accepted" && r.isActive) {
                        ProcessingCard(r, onClick = null)
                        // আগে রাজি হওয়া (এই সুবিধার আগের) রিকোয়েস্টও প্রসেসিং তালিকায় তোলা
                        LaunchedEffect(r.id) {
                            if (uid != null && r.id !in profile.commitments) {
                                runCatching { ProfileRepository.addCommitment(uid, r.id) }
                                    .onSuccess { onProfileChanged(profile.copy(commitments = profile.commitments + r.id)) }
                            }
                        }
                    }

                    RequestInfo(r, distance)

                    // সবার জন্য: কেউ রাজি হয়েছেন কি না, আর পরিচিতদের পাঠানোর বাটন
                    if (r.isActive && r.requesterUid != uid) {
                        val noDonor = r.acceptedCount == 0 && !r.fulfilled
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (noDonor) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DonorStatusTag(r)
                                Text(
                                    if (noDonor) {
                                        tr(
                                            "No one has agreed yet. Know someone with a matching group, even far away? Send it to them.",
                                            "এখনো কেউ রাজি হননি। মিলে যায় এমন গ্রুপের কাউকে চেনেন, দূরে হলেও? তাঁকে পাঠান।",
                                        )
                                    } else {
                                        tr("Know someone who could help? Share it.", "সাহায্য করতে পারেন এমন কাউকে চেনেন? শেয়ার করুন।")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                FilledTonalButton(onClick = { shareRequest(context, r) }, modifier = Modifier.fillMaxWidth()) {
                                    ButtonContent(false, tr("Send to people you know", "পরিচিতদের পাঠান"), R.drawable.ic_share)
                                }
                            }
                        }
                    }

                    when {
                        r.requesterUid == uid -> Button(onClick = { onOpenMine(r.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("View responses", "সাড়া দেওয়া ডোনারদের দেখুন"))
                        }

                        resp == null && !r.isActive -> {}

                        // ডোনারের তথ্য (গ্রুপ, জন্ম তারিখ, ওজন) নেই: আগে সেটা চেয়ে নেওয়া (নতুন নকশা N2)
                        resp == null && !profile.isDonor -> {
                            Button(
                                onClick = { LoginGate.require(tr("Add your donor details to respond", "সাড়া দিতে রক্তদাতার তথ্য দিন"), donor = true) {} },
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonContent(false, tr("I want to donate", "রক্ত দিতে চাই"), R.drawable.ic_volunteer_activism) }
                            Note(tr("You can still share this request.", "চাইলে রিকোয়েস্টটা পরিচিতদের পাঠাতে পারেন।"))
                        }

                        resp == null && !compatible -> Note(
                            tr(
                                "Your group (${profile.bloodGroup}) can't donate to ${r.patientGroup}. You can share it with others.",
                                "আপনার গ্রুপ (${profile.bloodGroup}) থেকে ${r.patientGroup} রোগীকে রক্ত দেওয়া যায় না। পরিচিতদের জানাতে পারেন।",
                            )
                        )

                        resp == null && !eligible -> Note(notEligible ?: "")

                        resp == null -> {
                            Button(
                                onClick = { showChecklist = true },
                                enabled = !busy && uid != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonContent(busy, tr("I'll donate", "রক্ত দেব"), R.drawable.ic_volunteer_activism) }
                            Note(tr("Your name and number will be shared with the requester.", "রক্তপ্রার্থী আপনার নাম ও নম্বর দেখতে পাবেন।"))
                        }

                        // রিকোয়েস্ট বন্ধ/মেয়াদ শেষ: আর হাসপাতালে যাওয়ার দরকার নেই; রক্ত দিয়ে থাকলে জানানোর সুযোগ
                        resp.status == "accepted" && !r.isActive -> {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(R.drawable.ic_info, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        tr(
                                            "This request is closed — you don't need to go. If you already donated, tell us below.",
                                            "রিকোয়েস্টটা বন্ধ হয়ে গেছে, আর যেতে হবে না। আগেই রক্ত দিয়ে থাকলে নিচে জানান।",
                                        ),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    act {
                                        ServerApi.updateResponse(r.id, "donated")
                                        // তারিখ সার্ভার বসায় (বাংলাদেশের দিন ধরে); ফোন থেকে আলাদা করে লেখা হয় না
                                        ProfileRepository.load(uid!!)?.let(onProfileChanged)
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonContent(busy, tr("I donated for this", "এতে রক্ত দিয়েছি"), R.drawable.ic_check_circle) }
                        }

                        resp.status == "accepted" -> {
                            // রক্তপ্রার্থী নিশ্চিত করেছেন কি না
                            val (statusIcon, statusText) = when {
                                resp.confirmed -> R.drawable.ic_check_circle to tr(
                                    "The requester confirmed you. Please go to the hospital on time.",
                                    "রক্তপ্রার্থী আপনাকে নিশ্চিত করেছেন। সময়মতো হাসপাতালে যান।",
                                )
                                r.fulfilled -> R.drawable.ic_info to tr(
                                    "Enough donors are confirmed for now. Thank you! You'll be told if you're needed.",
                                    "আপাতত যথেষ্ট ডোনার নিশ্চিত হয়েছেন। ধন্যবাদ! দরকার হলে জানানো হবে।",
                                )
                                else -> R.drawable.ic_schedule to tr(
                                    "Waiting for the requester to confirm you.",
                                    "রক্তপ্রার্থী আপনাকে নিশ্চিত করার অপেক্ষায়।",
                                )
                            }
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (resp.confirmed) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(statusIcon, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(statusText, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(tr("Contact the requester", "রক্তপ্রার্থীর সাথে যোগাযোগ করুন"), fontWeight = FontWeight.Bold)
                                    val c = contact
                                    if (c != null) {
                                        Text(c.first)
                                        Text(num(c.second), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        // কল (ফোন কল · অ্যাপ কল · ভিডিও কল) · মেসেজ — ডোনার → রক্তপ্রার্থী অ্যাপে শুধু নিশ্চিত হলে
                                        // (রক্তপ্রার্থী আগে কল করলে কলের তালিকা থেকে ফিরতি কল); জরুরি রিকোয়েস্টে ফোনই বড়
                                        ContactActions(
                                            CallCtx.BLOOD, r.id, r.requesterUid.ifBlank { null }, c.first, c.second,
                                            if (r.urgent) ContactStyle.PRIMARY_PHONE else ContactStyle.ROW,
                                            appAllowed = r.isActive && resp.confirmed,
                                            onMessage = { openChat(CallCtx.BLOOD, r.id, r.requesterUid.ifBlank { null }) },
                                        )
                                    } else {
                                        Text(tr("Couldn't load the number. Open this page again.", "নম্বর লোড হয়নি, পাতাটা আবার খুলুন।"))
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    act {
                                        ServerApi.updateResponse(r.id, "donated")
                                        // তারিখ সার্ভার বসায় (বাংলাদেশের দিন ধরে); ফোন থেকে আলাদা করে লেখা হয় না
                                        ProfileRepository.load(uid!!)?.let(onProfileChanged)
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonContent(busy, tr("I donated", "রক্ত দিয়েছি"), R.drawable.ic_check_circle) }
                            TextButton(
                                onClick = {
                                    act {
                                        ServerApi.updateResponse(r.id, "cancel")
                                        runCatching { ProfileRepository.removeCommitments(uid!!, listOf(r.id)) }
                                        onProfileChanged(profile.copy(commitments = profile.commitments - r.id))
                                        infoMsg = tr(
                                            "Cancelled. The requester can no longer see your number.",
                                            "বাতিল করা হয়েছে। রক্তপ্রার্থী এখন আর আপনার নম্বর দেখবেন না।",
                                        )
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("I can't make it, cancel", "আর পারছি না, বাতিল করুন")) }
                        }

                        resp.status == "donated" -> Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(R.drawable.ic_favorite, null, Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    tr("Thank you! You helped save a life.", "ধন্যবাদ! আপনি একজনের জীবন বাঁচাতে সাহায্য করেছেন।"),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        resp.status == "declined" -> Note(
                            tr(
                                "The requester no longer needs you for this request — you don't need to go. Thank you!",
                                "রক্তপ্রার্থী এই রিকোয়েস্টে আপনাকে আর রাখেননি, আপনাকে যেতে হবে না। ধন্যবাদ!",
                            )
                        )

                        else -> {
                            Note(tr("You cancelled for this request.", "আপনি এই রিকোয়েস্টে রক্ত দেওয়া বাতিল করেছেন।"))
                            if (r.isActive && compatible && eligible) {
                                OutlinedButton(
                                    onClick = { showChecklist = true },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(tr("I can donate after all", "আবার রক্ত দিতে চাই")) }
                            }
                        }
                    }

                    ErrorText(errorMsg)
                    InfoText(infoMsg)
                    Disclaimer()
                }
            }
        }
    }

    if (showChecklist) {
        DonationChecklistDialog(
            onDismiss = { showChecklist = false },
            onConfirm = {
                showChecklist = false
                act {
                    ServerApi.respond(requestId)
                    if (requestId !in profile.commitments) {
                        onProfileChanged(profile.copy(commitments = profile.commitments + requestId))
                    }
                }
            },
        )
    }

    if (confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text(tr("Report this request?", "রিপোর্ট করবেন?")) },
            text = { Text(tr("Report it if it looks fake or abusive.", "ভুয়া বা অপব্যবহার মনে হলে রিপোর্ট করুন।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReport = false
                    val u = uid ?: return@TextButton
                    scope.launch {
                        runCatching { RequestRepository.report(requestId, u) }
                            .onSuccess { infoMsg = tr("Reported. Thank you.", "রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।") }
                            .onFailure { errorMsg = tr("Couldn't send the report", "রিপোর্ট পাঠানো যায়নি") }
                    }
                }) { Text(tr("Report", "রিপোর্ট করুন")) }
            },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ------------------------------------------------------------
// রক্তপ্রার্থীর নিজের রিকোয়েস্ট
// ------------------------------------------------------------

@Composable
fun MyRequestDetailScreen(requestId: String, onBack: () -> Unit, onDeleted: (String) -> Unit = { onBack() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<BloodRequest?>(null) }
    var responses by remember { mutableStateOf<List<DonorResponse>>(emptyList()) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<DonorResponse?>(null) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching {
            RequestRepository.get(requestId) to RequestRepository.responses(requestId)
        }.onSuccess { (r, list) ->
            request = r
            responses = list
        }.onFailure { errorMsg = tr("Couldn't load", "লোড হয়নি") }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message ?: tr("Something went wrong", "কিছু একটা সমস্যা হয়েছে") }
            busy = false
        }
    }

    BackHandler(enabled = editing) { editing = false }
    val editTarget = request
    if (editing && editTarget != null) {
        EditRequestScreen(
            r = editTarget,
            onSaved = { notified ->
                editing = false
                infoMsg = if (notified > 0) {
                    tr("Saved. ${num(notified)} donor(s) were notified.", "সেভ হয়েছে। রাজি থাকা ${num(notified)} জন ডোনারকে জানানো হয়েছে।")
                } else {
                    tr("Saved.", "সেভ হয়েছে।")
                }
                reload++
            },
            onCancel = { editing = false },
        )
        return
    }

    Scaffold(
        topBar = {
            BackTopBar(tr("My Request", "আমার রিকোয়েস্ট"), onBack) {
                val r = request
                if (r != null && r.isActive) {
                    IconButton(onClick = { shareRequest(context, r) }) { AppIcon(R.drawable.ic_share, tr("Share", "শেয়ার")) }
                }
                if (r != null && r.isActive) {
                    IconButton(onClick = { infoMsg = null; editing = true }, enabled = !busy) {
                        AppIcon(R.drawable.ic_edit, tr("Edit", "এডিট"))
                    }
                }
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                if (r != null) {
                    IconButton(onClick = { confirmDelete = true }, enabled = !busy) { AppIcon(R.drawable.ic_delete, tr("Delete", "মুছে ফেলুন")) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val r = request
            when {
                loading && r == null -> LoadingBox()
                r == null -> ErrorText(errorMsg ?: tr("Request not found", "রিকোয়েস্টটা পাওয়া যায়নি"))
                else -> {
                    RequestInfo(r, null)
                    if (r.isActive && r.notified) {
                        IconLine(
                            R.drawable.ic_notifications_active,
                            if (r.notifiedCount > 0) tr("${num(r.notifiedCount)} nearby donors notified", "আশেপাশের ${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে")
                            else tr("No donors with notifications on nearby yet", "এখনো আশেপাশে নোটিফিকেশন চালু থাকা ডোনার পাওয়া যায়নি"),
                        )
                    }
                    if (r.isActive && !r.notified) {
                        SmallAction(
                            tr("Notify nearby donors", "আশেপাশের ডোনারদের জানান"),
                            R.drawable.ic_notifications,
                            {
                                act {
                                    val n = ServerApi.notifyRequest(r.id)
                                    infoMsg = tr("${num(n)} donors notified", "${num(n)} জন ডোনারকে জানানো হয়েছে")
                                }
                            },
                            primary = true,
                            busy = busy,
                        )
                    }
                    InfoText(infoMsg)

                    val willing = responses.willing()
                    if (r.isActive) RequestProgress(r, responses)
                    SectionTitle(tr("Donors (${num(willing.size)})", "রাজি হয়েছেন (${num(willing.size)})"))
                    if (willing.isEmpty()) {
                        Note(tr("No one has responded yet.", "এখনো কেউ সাড়া দেননি।"))
                    } else if (r.isActive) {
                        Note(tr("Confirm the donors you'll take. Others are told when enough are confirmed.", "যাঁদের নেবেন তাঁদের নিশ্চিত করুন। যথেষ্ট নিশ্চিত হলে বাকিদের জানানো হয়।"))
                    }
                    removed?.let { d ->
                        RemovedNotice(d.donorName, busy, onUndo = {
                            act { ServerApi.updateResponse(r.id, "restore", d.donorUid); removed = null }
                        }, onDismiss = { removed = null })
                    }
                    willing.sortedByDescending { it.counts() }.forEach { d ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                DonorRow(
                                    d,
                                    active = r.isActive,
                                    busy = busy,
                                    onConfirm = { act { ServerApi.confirmDonor(r.id, d.donorUid) } },
                                    onRemove = { act { ServerApi.updateResponse(r.id, "remove", d.donorUid); removed = d } },
                                    canConfirm = r.confirmedCount < r.bags,
                                    requestId = r.id,
                                )
                            }
                        }
                    }

                    ErrorText(errorMsg)
                    // ভুল করে বন্ধ করলে ১ ঘণ্টার মধ্যে আবার খোলা
                    val now = System.currentTimeMillis()
                    if (r.status == "closed" && r.closedBy in setOf("requester", "not_needed") &&
                        now - r.closedAtMillis < 60 * 60 * 1000L && r.activeUntilMillis > now
                    ) {
                        SmallAction(tr("Closed by mistake? Open again", "ভুল করে বন্ধ? আবার খুলুন"), R.drawable.ic_refresh, { act { ServerApi.reopenRequest(r.id) } }, busy = busy)
                    }
                    if (r.isActive) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            SmallAction(tr("Got blood, close", "রক্ত পেয়েছি, বন্ধ করুন"), R.drawable.ic_check_circle, { confirmClose = true }, primary = true, enabled = !busy)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val active = request?.isActive == true
        val willingCount = responses.willing().size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this request?", "রিকোয়েস্টটা মুছে ফেলবেন?")) },
            text = {
                Text(
                    when {
                        active && willingCount > 0 -> tr(
                            "It will be removed for everyone. The ${num(willingCount)} donor(s) who agreed will be told they don't need to come.",
                            "সবার কাছ থেকে সরে যাবে। রাজি হওয়া ${num(willingCount)} জন ডোনারকে জানানো হবে যে আর আসার দরকার নেই।",
                        )
                        active -> tr("It will be removed for everyone and donors will stop seeing it.", "সবার কাছ থেকে সরে যাবে, ডোনাররা আর দেখবেন না।")
                        else -> tr("It will be removed from your list. This can't be undone.", "আপনার তালিকা থেকে মুছে যাবে। আর ফেরানো যাবে না।")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { ServerApi.deleteRequest(requestId) }
                            .onSuccess { onDeleted(requestId) }
                            .onFailure { errorMsg = it.message ?: tr("Something went wrong", "কিছু একটা সমস্যা হয়েছে") }
                        busy = false
                    }
                }) { Text(tr("Delete", "মুছে ফেলুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }

    if (confirmClose) {
        CloseRequestDialog(
            requestId = requestId,
            bags = request?.bags ?: 1,
            already = request?.bagsReceived ?: 0,
            responses = responses,
            onDismiss = { confirmClose = false },
            onClose = { chosen, got ->
                confirmClose = false
                act { ServerApi.closeRequest(requestId, chosen, got) }
            },
        )
    }
}

// ------------------------------------------------------------
// "রক্ত দেব" চাপার আগে স্বাস্থ্য যাচাই (উত্তর কোথাও সেভ হয় না)
// ------------------------------------------------------------

@Composable
fun DonationChecklistDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val items = listOf(
        tr("I feel well today and slept and ate well", "আজ সুস্থ বোধ করছি, ভালো ঘুম ও খাওয়া হয়েছে"),
        tr("No fever, cold or antibiotics in the last 7 days", "গত ৭ দিনে জ্বর, সর্দি-কাশি বা অ্যান্টিবায়োটিক নেই"),
        tr("No surgery, tattoo, piercing or transfusion in 6 months", "গত ৬ মাসে অপারেশন, ট্যাটু, কান-নাক ফোঁড়ানো বা রক্ত নেওয়া হয়নি"),
        tr("No hepatitis B/C, HIV, malaria or heart disease", "হেপাটাইটিস বি/সি, এইচআইভি, ম্যালেরিয়া বা হৃদরোগ নেই"),
        tr("Not pregnant or breastfeeding (women)", "গর্ভবতী নই বা বুকের দুধ খাওয়াচ্ছি না (নারীদের জন্য)"),
    )
    var checked by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Before you donate", "রক্ত দেওয়ার আগে")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i in checked, onCheckedChange = { on -> checked = if (on) checked + i else checked - i })
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = checked.size == items.size) { Text(tr("I'll donate", "রক্ত দেব")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// রিকোয়েস্ট এডিট (রক্তপ্রার্থী)
// ------------------------------------------------------------

/**
 * ব্যাগ, হাসপাতাল, তারিখ-সময়, জরুরি আর অতিরিক্ত তথ্য বদলানো যায়।
 * রক্তের গ্রুপ আর এলাকা বদলানো যায় না: ডোনারদের এগুলো দেখেই বাছাই করে জানানো হয়েছে।
 */
@Composable
fun EditRequestScreen(r: BloodRequest, onSaved: (Int) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bags by remember { mutableIntStateOf(r.bags) }
    var hospital by remember { mutableStateOf(r.hospital) }
    var note by remember { mutableStateOf(r.note) }
    var neededBy by remember { mutableLongStateOf(r.neededByMillis) }
    val initialTime = r.neededAtMillis?.let { Rules.dhakaHourMinute(it) }
    var neededHour by remember { mutableStateOf(initialTime?.first) }
    var neededMinute by remember { mutableStateOf(initialTime?.second) }
    var urgent by remember { mutableStateOf(r.urgent) }
    var travelFare by remember { mutableStateOf(r.travelFare) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // যোগাযোগ নম্বর (রাজি ডোনাররা এতে ফোন করেন) — রোগীর লোকের নম্বরও দেওয়া যায়
    var savedPhone by remember { mutableStateOf<String?>(null) }
    var contactPhone by remember { mutableStateOf("") }
    LaunchedEffect(r.id) {
        runCatching { RequestRepository.requesterContact(r.id) }.getOrNull()?.let { savedPhone = it.second; contactPhone = it.second }
    }

    Scaffold(topBar = { BackTopBar(tr("Edit Request", "রিকোয়েস্ট এডিট"), onCancel) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BloodGroupBadge(r.patientGroup, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(Districts.label(r.placeName), fontWeight = FontWeight.SemiBold)
                    Note(tr("Group and area can't be changed", "গ্রুপ আর এলাকা বদলানো যায় না"))
                }
            }

            FieldLabel(tr("Bags needed", "কত ব্যাগ"))
            BagsPicker(bags) { bags = it }

            HospitalField(hospital, { hospital = it }, r.placeName.ifBlank { null })

            FieldLabel(tr("Needed by", "কবে লাগবে"))
            DateTimeFields(neededBy, neededHour, neededMinute, { showDate = true }, { showTime = true })

            UrgentSwitch(urgent) { urgent = it }
            TravelFareSwitch(travelFare) { travelFare = it }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text(tr("Note (optional)", "অতিরিক্ত তথ্য (ঐচ্ছিক)")) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (savedPhone != null) {
                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it.take(20) },
                    label = { Text(tr("Contact number", "যোগাযোগ নম্বর")) },
                    supportingText = { Text(tr("Donors who agreed call this number — it can be a family member's.", "রাজি ডোনাররা এই নম্বরে ফোন করবেন — রোগীর লোকের নম্বরও দিতে পারেন।")) },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ErrorText(errorMsg)

            Button(
                onClick = {
                    val h = neededHour
                    val m = neededMinute
                    val at = if (h != null && m != null) Rules.dhakaMillis(neededBy, h, m) else null
                    errorMsg = when {
                        hospital.isBlank() -> tr("Enter the hospital", "হাসপাতালের নাম লিখুন")
                        neededBy < Rules.todayUtcMidnight() -> tr("Pick today or a later date", "আজ বা পরের কোনো তারিখ দিন")
                        at == null -> tr("Pick the time", "সময় বেছে নিন")
                        at != r.neededAtMillis && at <= System.currentTimeMillis() -> tr("That time has passed", "এই সময় পার হয়ে গেছে")
                        else -> null
                    }
                    if (errorMsg == null && at != null) {
                        scope.launch {
                            busy = true
                            runCatching {
                                ServerApi.editRequest(
                                    r.id, bags, TitleCase.name(hospital), neededBy, at, urgent, travelFare, note.trim(),
                                    contactPhone.trim().takeIf { it.isNotBlank() && it != savedPhone },
                                )
                            }.onSuccess { onSaved(it) }
                                .onFailure { errorMsg = it.message ?: tr("Couldn't save", "সেভ হয়নি") }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }

    if (showDate) {
        DatePickerPopup(
            initial = neededBy,
            onPicked = { picked -> if (picked != null) neededBy = picked },
            onDismiss = { showDate = false },
            minDate = Rules.todayUtcMidnight(),
            maxDate = Rules.todayUtcMidnight() + 30 * DAY_MS,
        )
    }
    if (showTime) {
        TimePickerPopup(
            hour = neededHour,
            minute = neededMinute,
            onPicked = { h, m ->
                neededHour = h
                neededMinute = m
            },
            onDismiss = { showTime = false },
        )
    }
}

/**
 * হাসপাতালের ঘর: লিখতে থাকলে নিচে ওই জেলার হাসপাতালের নাম আসে (ওষুধের নামের মতো), চাপলে বসে যায়।
 * তালিকায় না থাকলে লেখা নামটাই থাকে।
 */
@Composable
private fun HospitalField(value: String, onChange: (String) -> Unit, district: String?) {
    var all by remember(district) { mutableStateOf<List<Hospital>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var addedMsg by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(false) }
    LaunchedEffect(district) {
        if (district != null) all = runCatching { Hospitals.inDistrict(district) }.getOrDefault(emptyList())
    }
    val q = value.trim()
    val matches = remember(q, all, picked) {
        if (q.length < 2 || picked) emptyList()
        else {
            val direct = all.filter { it.nameEn.contains(q, true) || it.nameBn.contains(q) }
                .sortedBy { if (it.nameEn.startsWith(q, true) || it.nameBn.startsWith(q)) 0 else 1 }
            // বানান একটু ভুল হলেও কাছের নাম
            (direct + Hospitals.similar(q, all, 6)).distinctBy { it.id }.take(6)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(120)); picked = false },
            label = { Text(tr("Hospital", "হাসপাতাল")) },
            leadingIcon = { AppIcon(R.drawable.ic_local_hospital, null) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused && matches.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column {
                    matches.forEach { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onChange(h.label.take(120)); picked = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(h.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val area = Areas.label(h.district, h.upazilaId, h.unionId)
                            if (area.isNotBlank()) Text(area, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // তালিকায় না পেলে: অ্যাডমিনকে যোগ করতে বলা (রিকোয়েস্টে আপাতত লেখা নামই থাকে)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Not in the list?", "তালিকায় নেই?"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { adding = true }) { Text(tr("Request to add", "যোগ করার অনুরোধ")) }
        }
        InfoText(addedMsg)
    }
    if (adding) {
        AddHospitalDialog(
            initialName = value.trim(),
            initialDistrict = district,
            initialUpazilaId = null,
            onExisting = { h -> adding = false; onChange(h.label.take(120)) },
            onSent = { msg -> adding = false; addedMsg = msg },
            onDismiss = { adding = false },
        )
    }
}
