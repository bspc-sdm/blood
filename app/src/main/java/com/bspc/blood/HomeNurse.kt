@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// হোম নার্স ট্যাব
// ------------------------------------------------------------

@Composable
fun HomeNurseTab(profile: Profile, me: CommunityMe?) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var visits by remember { mutableStateOf(HomeVisitLists(emptyList(), emptyList())) }
    var nurses by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var requesting by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Provider?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf<HomeVisit?>(null) }
    var reporting by remember { mutableStateOf<HomeVisit?>(null) }
    var confirmOp by remember { mutableStateOf<Pair<HomeVisit, String>?>(null) }
    val isNurse = me?.approvedBadge == "nurse"

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching { HomeVisits.list() }.onSuccess { visits = it }.onFailure { errorMsg = it.message }
        runCatching { Appointments.providers("nurse", CareFilter(district = profile.placeName), homeVisitOnly = true) }
            .onSuccess { nurses = it.filter { n -> n.uid != AuthRepository.currentUid } }
        loading = false
    }

    fun act(v: HomeVisit, op: String) {
        scope.launch {
            busyId = v.id
            errorMsg = null
            runCatching { HomeVisits.update(v.id, op) }
                .onSuccess { reload++ }
                .onFailure {
                    errorMsg = it.message
                    reload++
                }
            busyId = null
        }
    }

    val offered = visits.asNurse.filter { it.status == "open" }
    val nursing = visits.asNurse.filter { it.status != "open" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_home_health, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("Need a nurse at home?", "বাসায় নার্স দরকার?"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ"), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
                    }
                    Text(
                        tr(
                            "Injection, saline, dressing, BP check and more. Verified nurses nearby get your request; the first one to accept comes.",
                            "ইনজেকশন, স্যালাইন, ড্রেসিং, প্রেসার মাপা ইত্যাদি। কাছের যাচাই করা নার্সরা অনুরোধ পান; যিনি প্রথমে নেন তিনি আসেন।",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Button(onClick = { requesting = true }) { ButtonContent(false, tr("Request a home nurse", "হোম নার্সের অনুরোধ"), R.drawable.ic_add) }
                }
            }
        }
        item { EmergencyNotice() }
        item {
            InfoText(infoMsg)
            ErrorText(errorMsg)
        }
        if (loading && visits.asPatient.isEmpty() && visits.asNurse.isEmpty()) item { LoadingBox() }

        if (isNurse) {
            item { SectionTitle(tr("Requests near you", "আপনার কাছের অনুরোধ") + if (offered.isNotEmpty()) " (${num(offered.size)})" else "") }
            if (offered.isEmpty()) {
                item { SmallNote(tr("New requests will appear here and you'll get a notification. Turn on home visits in My chambers & profile.", "নতুন অনুরোধ এখানে আসবে, নোটিফিকেশনও পাবেন। \"আমার চেম্বার ও প্রোফাইল\" থেকে হোম ভিজিট চালু রাখুন।")) }
            }
            items(offered, key = { "o" + it.id }) { v ->
                VisitCard(v, busyId, onAct = { op -> if (op == "accept") act(v, op) else confirmOp = v to op }, onRate = {}, onReport = { reporting = v })
            }
        }
        // ব্যাজ চলে গেলেও নেওয়া ভিজিট শেষ বা বাতিল করা যায়
        if (nursing.isNotEmpty()) {
            item { SectionTitle(tr("My visits as nurse", "নার্স হিসেবে আমার ভিজিট")) }
            items(nursing, key = { "n" + it.id }) { v ->
                VisitCard(v, busyId, onAct = { op -> if (op == "on_the_way") act(v, op) else confirmOp = v to op }, onRate = {}, onReport = { reporting = v })
            }
        }

        if (visits.asPatient.isNotEmpty()) {
            item { SectionTitle(tr("My requests", "আমার অনুরোধ")) }
            items(visits.asPatient, key = { "p" + it.id }) { v ->
                VisitCard(v, busyId, onAct = { op -> confirmOp = v to op }, onRate = { rating = v }, onReport = { reporting = v })
            }
        }

        if (nurses.isNotEmpty()) {
            item { SectionTitle(tr("Home nurses in ", "হোম নার্স: ") + Districts.label(profile.placeName)) }
            items(nurses, key = { "h" + it.uid }) { n ->
                Card(
                    onClick = { detail = n },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (n.gender == "female") Tag(tr("Female", "মহিলা"), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        RatingLine(n.rating, n.ratingCount)
                        n.homeVisit?.let { h ->
                            Text(h.services.joinToString(", ") { HomeServices.label(it) }, style = MaterialTheme.typography.bodySmall)
                            if (h.fee.isNotBlank()) Text(tr("Fee: ", "ফি: ") + h.fee, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (requesting) {
        HomeVisitRequestDialog(
            profile = profile,
            nurse = null,
            onDismiss = { requesting = false },
            onSent = {
                requesting = false
                infoMsg = it
                reload++
            },
        )
    }
    detail?.let { p ->
        ProviderDetailDialog(p, profile, onDismiss = {
            detail = null
            reload++
        }, onBooked = { detail = null })
    }
    rating?.let { v ->
        RateVisitDialog(v, onDismiss = { rating = null }, onDone = {
            rating = null
            infoMsg = tr("Thanks for the rating!", "রেটিং দেওয়ার জন্য ধন্যবাদ!")
            reload++
        })
    }
    reporting?.let { v ->
        ReportVisitDialog(v, onDismiss = { reporting = null }, onDone = {
            reporting = null
            infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
        })
    }
    confirmOp?.let { (v, op) ->
        val (title, text) = when (op) {
            "done" -> tr("Mark as done?", "সেবা শেষ?") to tr("Confirm that the visit is complete.", "সেবা সম্পন্ন হয়েছে নিশ্চিত করুন।")
            "decline" -> tr("Can't go?", "যেতে পারবেন না?") to tr("This request will be hidden from you.", "এই অনুরোধ আর আপনাকে দেখাবে না।")
            else -> tr("Cancel?", "বাতিল করবেন?") to if (v.role == "nurse") {
                tr("The patient will be told and the request opens again for other nurses.", "রোগীকে জানানো হবে, আর অনুরোধটা অন্য নার্সদের জন্য আবার খুলে যাবে।")
            } else {
                tr("The nurse will be told.", "নার্সকে জানানো হবে।")
            }
        }
        AlertDialog(
            onDismissRequest = { confirmOp = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOp = null
                    act(v, op)
                }) { Text(tr("Yes", "হ্যাঁ")) }
            },
            dismissButton = { TextButton(onClick = { confirmOp = null }) { Text(tr("No", "না")) } },
        )
    }
}

private fun whenLabel(v: HomeVisit): String =
    if (v.whenMillis == 0L) tr("As soon as possible", "যত তাড়াতাড়ি সম্ভব")
    else Rules.formatDate(v.whenMillis + Rules.DHAKA_OFFSET_MS) + ", " + Rules.formatTime(v.whenMillis)

@Composable
private fun VisitCard(
    v: HomeVisit,
    busyId: String?,
    onAct: (String) -> Unit,
    onRate: () -> Unit,
    onReport: () -> Unit,
) {
    val context = LocalContext.current
    val live = v.isLive
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.services.joinToString(", ") { HomeServices.label(it) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Tag(
                    HomeVisits.statusLabel(v.status, v.role),
                    if (v.isActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    if (v.isActive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconLine(R.drawable.ic_schedule, whenLabel(v))
            val place = listOf(v.placeName, Areas.label(v.district, v.upazilaId)).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            IconLine(R.drawable.ic_location_on, place + (v.km?.let { " · " + Rules.formatKm(it) } ?: ""))
            if (v.otherName.isNotBlank()) {
                IconLine(R.drawable.ic_person_outline, (if (v.role == "patient") tr("Nurse: ", "নার্স: ") else tr("Patient: ", "রোগী: ")) + v.otherName)
            }
            if (v.role == "patient" && v.status == "open") {
                SmallNote(
                    if (v.mode == "direct") tr("Waiting for the nurse to reply", "নার্সের উত্তরের অপেক্ষা")
                    else tr("Sent to ${v.notifiedCount} nurses nearby", "কাছের ${num(v.notifiedCount)} জন নার্সকে পাঠানো হয়েছে"),
                )
            }
            if (v.femalePreferred) SmallNote(tr("Female nurse preferred", "মহিলা নার্স চাওয়া হয়েছে"))
            if (v.prescription) SmallNote(tr("Has a doctor's prescription", "ডাক্তারের প্রেসক্রিপশন আছে"))
            if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.bodyMedium)
            if (v.address.isNotBlank()) IconLine(R.drawable.ic_home_outline, v.address, MaterialTheme.colorScheme.onSurface)
            if (v.role == "nurse" && v.status == "open") {
                SmallNote(tr("Address and phone are shown after you accept.", "নেওয়ার পর ঠিকানা আর ফোন দেখা যাবে।"))
            }
            if (v.rating > 0) RatingLine(v.rating.toDouble(), 1)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.otherPhone.isNotBlank()) {
                    Button(onClick = { callPhone(context, v.otherPhone) }) { ButtonContent(false, tr("Call", "ফোন"), R.drawable.ic_call) }
                }
                if (v.role == "nurse" && live && v.lat != null && v.lng != null) {
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${v.lat},${v.lng}?q=${v.lat},${v.lng}")))
                        }
                    }) { ButtonContent(false, tr("Map", "ম্যাপ"), R.drawable.ic_map) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val busy = busyId == v.id
                val enabled = busyId == null
                when {
                    v.role == "nurse" && v.status == "open" -> {
                        Button(onClick = { onAct("accept") }, enabled = enabled) { ButtonContent(busy, tr("Accept", "নেব"), R.drawable.ic_check_circle) }
                        TextButton(onClick = { onAct("decline") }, enabled = enabled) { Text(tr("Can't go", "যেতে পারব না")) }
                    }
                    v.role == "nurse" && v.status == "accepted" -> {
                        Button(onClick = { onAct("on_the_way") }, enabled = enabled) { ButtonContent(busy, tr("On the way", "রওনা দিলাম"), R.drawable.ic_directions_walk) }
                        TextButton(onClick = { onAct("done") }, enabled = enabled) { Text(tr("Done", "শেষ")) }
                    }
                    v.role == "nurse" && v.status == "on_the_way" -> {
                        Button(onClick = { onAct("done") }, enabled = enabled) { ButtonContent(busy, tr("Visit done", "সেবা শেষ"), R.drawable.ic_check_circle) }
                    }
                    v.role == "patient" && live -> {
                        Button(onClick = { onAct("done") }, enabled = enabled) { ButtonContent(busy, tr("Visit done", "সেবা শেষ"), R.drawable.ic_check_circle) }
                    }
                    v.role == "patient" && v.canRate -> {
                        Button(onClick = onRate) { ButtonContent(false, tr("Rate the nurse", "নার্সকে রেটিং দিন"), R.drawable.ic_star) }
                    }
                }
                Spacer(Modifier.weight(1f))
                val canCancel = (v.role == "patient" && v.isActive) || (v.role == "nurse" && live)
                if (canCancel) TextButton(onClick = { onAct("cancel") }, enabled = busyId == null) { Text(tr("Cancel", "বাতিল")) }
                if ((v.role == "patient" && v.canReport) || (v.role == "nurse" && v.status != "open")) {
                    IconButton(onClick = onReport) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
                }
            }
        }
    }
}

@Composable
private fun RateVisitDialog(v: HomeVisit, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Rate ", "রেটিং: ") + v.otherName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    (1..5).forEach { i ->
                        IconButton(onClick = { stars = i }) {
                            AppIcon(
                                if (i <= stars) R.drawable.ic_star_fill else R.drawable.ic_star,
                                num(i),
                                Modifier.size(32.dp),
                                MaterialTheme.colorScheme.tertiary,
                            )
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
                    runCatching { HomeVisits.update(v.id, "rate", stars, comment.trim()) }
                        .onSuccess { onDone() }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Submit", "জমা দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Later", "পরে")) } },
    )
}

@Composable
private fun ReportVisitDialog(v: HomeVisit, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Report a problem", "সমস্যা জানান")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(
                        "Tell us what happened. If anyone is in danger, call 999 first.",
                        "কী হয়েছে লিখুন। কেউ বিপদে থাকলে আগে ৯৯৯-এ ফোন করুন।",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                    runCatching { Moderation.report("homevisit", v.id, reason = reason.trim()) }
                        .onSuccess { onDone() }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Report", "রিপোর্ট")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// হোম নার্সের অনুরোধ
// ------------------------------------------------------------

/** nurse দিলে শুধু সেই নার্সের কাছে, না দিলে কাছের সব নার্সের কাছে */
@Composable
fun HomeVisitRequestDialog(profile: Profile, nurse: Provider?, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    val scope = rememberCoroutineScope()
    val services = remember { mutableStateListOf<String>() }
    var prescription by remember { mutableStateOf(false) }
    var asap by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf<Long?>(null) }
    var hour by remember { mutableStateOf<Int?>(null) }
    var minute by remember { mutableStateOf<Int?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf(profile.lat) }
    var lng by remember { mutableStateOf(profile.lng) }
    var district by remember { mutableStateOf(profile.placeName) }
    var preciseLocation by remember { mutableStateOf(profile.locationSource == "gps") }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var pickLocation by remember { mutableStateOf(false) }
    var pickUpazila by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var female by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var othersAvailable by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var verifyPhone by remember { mutableStateOf(false) }

    val options = nurse?.homeVisit?.services ?: HomeServices.ALL
    val needsRx = services.any { it in HomeServices.NEEDS_PRESCRIPTION }

    fun submit() {
        val la = lat
        val ln = lng
        val d = district
        val whenMillis = if (asap) 0L else {
            val dd = date
            val h = hour
            if (dd == null || h == null) -1L else Rules.dhakaMillis(dd, h, minute ?: 0)
        }
        errorMsg = when {
            services.isEmpty() -> tr("Choose what you need", "কী সেবা দরকার বেছে নিন")
            needsRx && !prescription -> tr("Injection or saline needs a doctor's prescription", "ইনজেকশন বা স্যালাইনের জন্য ডাক্তারের প্রেসক্রিপশন লাগবে")
            whenMillis < 0 -> tr("Pick the date and time", "তারিখ ও সময় বেছে নিন")
            la == null || ln == null || d == null -> tr("Set the patient's location", "রোগীর লোকেশন দিন")
            address.isBlank() -> tr("Write the address (house, road, area)", "ঠিকানা লিখুন (বাসা, রোড, এলাকা)")
            !agreed -> tr("Please accept the Home Nurse terms", "হোম নার্সের শর্তে সম্মতি দিন")
            else -> null
        }
        if (errorMsg != null || la == null || ln == null || d == null) return
        scope.launch {
            busy = true
            othersAvailable = false
            runCatching {
                HomeVisits.request(
                    nurse?.uid, services.toList(), whenMillis, la, ln, d, upazilaId, Districts.label(d), address.trim(), note.trim(),
                    female && nurse == null, prescription,
                )
            }
                .onSuccess { r ->
                    when (r) {
                        is HomeVisitResult.Sent -> onSent(
                            if (nurse != null) tr("Request sent to ${nurse.name}.", "${nurse.name}-এর কাছে অনুরোধ পাঠানো হয়েছে।")
                            else tr(
                                "Request sent to ${r.notified} nurses nearby. You'll be notified when one accepts.",
                                "কাছের ${num(r.notified)} জন নার্সকে পাঠানো হয়েছে। কেউ নিলে নোটিফিকেশন পাবেন।",
                            ),
                        )
                        is HomeVisitResult.NoNurse -> {
                            othersAvailable = r.othersAvailable
                            errorMsg = if (r.othersAvailable) {
                                tr("No female nurse nearby right now, but other nurses are available.", "এখন কাছে কোনো মহিলা নার্স নেই, তবে অন্য নার্স আছেন।")
                            } else {
                                tr(
                                    "No home nurse covers this area yet. Try choosing the upazila, or ask a nearby nurse from the Nurse tab.",
                                    "এই এলাকায় এখনো কোনো হোম নার্স নেই। উপজেলা বেছে দেখুন, অথবা \"নার্স\" ট্যাব থেকে কাছের নার্সের সাথে যোগাযোগ করুন।",
                                )
                            }
                        }
                    }
                }
                .onFailure {
                    if ((it as? ServerError)?.code == "phone_unverified") verifyPhone = true else errorMsg = it.message
                }
            busy = false
        }
    }

    if (verifyPhone) {
        PhoneVerifyDialog(onDismiss = { verifyPhone = false }, onVerified = {
            verifyPhone = false
            submit()
        })
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (pickLocation) {
            LocationPicker(
                title = tr("Patient's location", "রোগীর লোকেশন"),
                busy = false,
                errorMsg = null,
                onPicked = { la, ln, place, source ->
                    lat = la
                    lng = ln
                    preciseLocation = source == "gps"
                    if (place != district) upazilaId = null
                    district = place
                    pickLocation = false
                },
                onCancel = { pickLocation = false },
            )
            return@Dialog
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { BackTopBar(nurse?.name ?: tr("Request a home nurse", "হোম নার্সের অনুরোধ"), onDismiss) },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("What's needed", "কী সেবা দরকার"), style = MaterialTheme.typography.titleSmall)
                ChipRows(options, services.toSet(), perRow = 2, label = { HomeServices.label(it) }) {
                    if (it in services) services.remove(it) else services.add(it)
                }
                if (needsRx) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { prescription = !prescription }) {
                        Checkbox(checked = prescription, onCheckedChange = { prescription = it })
                        Text(
                            tr("The patient has a doctor's prescription for this", "এর জন্য রোগীর ডাক্তারের প্রেসক্রিপশন আছে"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SmallNote(tr("Keep the prescription and medicine ready for the nurse.", "নার্সের জন্য প্রেসক্রিপশন আর ওষুধ হাতের কাছে রাখুন।"))
                }

                Text(tr("When", "কখন"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = asap, onClick = { asap = true }, label = { Text(tr("As soon as possible", "যত তাড়াতাড়ি সম্ভব")) })
                    FilterChip(selected = !asap, onClick = { asap = false; showDate = true }, label = { Text(tr("Pick a time", "সময় বেছে নিন")) })
                }
                if (!asap) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showDate = true }) { Text(date?.let { Rules.formatDate(it) } ?: tr("Date", "তারিখ")) }
                        OutlinedButton(onClick = { showTime = true }) { Text(hour?.let { Rules.formatTime(it, minute ?: 0) } ?: tr("Time", "সময়")) }
                    }
                }

                Text(tr("Where", "কোথায়"), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconLine(R.drawable.ic_location_on, Districts.label(district).ifBlank { tr("Not set", "দেওয়া নেই") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pickLocation = true }) { Text(tr("Change", "বদলান")) }
                }
                if (!preciseLocation) {
                    InfoText(
                        tr(
                            "This is only the district. For the nearest nurse, use the current location (if you're with the patient) or choose the upazila.",
                            "এটা শুধু জেলা। কাছের নার্স পেতে বর্তমান লোকেশন দিন (রোগীর কাছে থাকলে) অথবা উপজেলা বেছে নিন।",
                        ),
                    )
                    GpsButton(tr("Use current location", "বর্তমান লোকেশন দিন"), onLocated = { la, ln, d ->
                        lat = la
                        lng = ln
                        if (d != district) upazilaId = null
                        district = d
                        preciseLocation = true
                    })
                }
                if (district != null) {
                    OutlinedButton(onClick = { pickUpazila = true }) {
                        Text(Areas.upazila(upazilaId)?.label ?: tr("Choose upazila (recommended)", "উপজেলা বেছে নিন (ভালো হয়)"))
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(200) },
                    label = { Text(tr("Address (house, road, area)", "ঠিকানা (বাসা, রোড, এলাকা)")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallNote(tr("Only the nurse who accepts sees the address and your phone number.", "শুধু যে নার্স নেবেন তিনিই ঠিকানা আর আপনার ফোন নম্বর দেখবেন।"))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text(tr("Note for the nurse (optional)", "নার্সের জন্য নোট (ঐচ্ছিক)")) },
                    placeholder = { Text(tr("e.g. Patient is 70, bedridden", "যেমন: রোগীর বয়স ৭০, শয্যাশায়ী")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nurse == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { female = !female }) {
                        Checkbox(checked = female, onCheckedChange = { female = it })
                        Text(tr("Female nurse preferred", "মহিলা নার্স হলে ভালো"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                SmallNote(tr("The fee is paid to the nurse in cash. The app takes no money.", "ফি নার্সকে নগদে দেবেন। অ্যাপ কোনো টাকা নেয় না।"))
                TermsCheck(agreed) { agreed = it }
                ErrorText(errorMsg)
                if (othersAvailable) {
                    OutlinedButton(onClick = { female = false; submit() }, enabled = !busy) { Text(tr("Send to all nurses", "সব নার্সকে পাঠান")) }
                }
                Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(busy, tr("Send request", "অনুরোধ পাঠান"), R.drawable.ic_send)
                }
            }
        }
    }

    if (showDate) {
        DatePickerPopup(initial = date ?: Rules.todayUtcMidnight(), onPicked = {
            date = it
            if (it != null && hour == null) showTime = true
        }, onDismiss = { showDate = false })
    }
    if (showTime) {
        TimePickerPopup(hour, minute, onPicked = { h, m -> hour = h; minute = m }, onDismiss = { showTime = false }, title = tr("When should the nurse come?", "নার্স কখন আসবেন?"))
    }
    if (pickUpazila) {
        SearchPickDialog(
            title = tr("Upazila", "উপজেলা"),
            items = Areas.upazilasOf(district.orEmpty()),
            label = { it.label },
            noneLabel = tr("Not sure", "জানি না"),
            onPick = {
                upazilaId = it?.id
                pickUpazila = false
            },
            onDismiss = { pickUpazila = false },
        )
    }
}

// ------------------------------------------------------------
// হোমে চলমান হোম ভিজিটের ছোট কার্ড
// ------------------------------------------------------------

@Composable
fun ActiveVisitCard(refreshKey: Int, onOpen: () -> Unit) {
    var active by remember { mutableStateOf<HomeVisit?>(null) }
    LaunchedEffect(refreshKey) {
        runCatching { HomeVisits.list() }.onSuccess { active = it.active }
    }
    val v = active ?: return
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_home_health, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Home nurse", "হোম নার্স") + " · " + HomeVisits.statusLabel(v.status, v.role),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    listOf(v.services.joinToString(", ") { HomeServices.label(it) }, v.otherName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                )
            }
            AppIcon(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** শর্তে সম্মতির টিক, শর্তের পাতা খোলার লিংক সহ */
@Composable
fun TermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(
                tr("I have read and accept the Home Nurse terms", "হোম নার্সের শর্ত পড়েছি এবং মেনে নিচ্ছি"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onChange(!checked) },
            )
            Text(
                tr("Read the terms", "শর্তগুলো পড়ুন"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(context, TERMS_URL) },
            )
        }
    }
}
