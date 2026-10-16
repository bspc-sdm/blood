@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// নিচের বারের "প্রোফাইল" = সিভি (LinkedIn-এর মতো): ছবি, নাম, শিরোনাম, ব্যাজ, পূরণের অগ্রগতি,
// রক্তদানের অংশ, পেশাদার ব্যাজ, সিভি, শেয়ার (লিংক + QR) আর PDF।
// সেটিংস, লগআউট, অ্যাকাউন্ট মোছা এখন উপরের ☰ মেনুতে (Menu.kt)।
// ------------------------------------------------------------

@Composable
fun ProfileTab(
    profile: Profile,
    community: CommunityMe?,
    refreshKey: Int,
    onCommunityChanged: (CommunityMe) -> Unit,
    onProfileChanged: (Profile) -> Unit,
    onEditCv: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenMyApplications: (() -> Unit)? = null, // FEATURE:jobs
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notEligible = Rules.notEligibleReason(profile)
    val avatar = remember(profile.photo) { Photo.decodeAvatar(profile.photo) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var showBadgeDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var cv by remember { mutableStateOf<Cv?>(null) }
    var cvBusy by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey, profile.photo) {
        runCatching { CvApi.mine() }.onSuccess { cv = it }.onFailure { errorMsg = it.message }
    }
    fun saveCv(next: Cv, done: String) {
        scope.launch {
            cvBusy = true
            runCatching { CvApi.save(next) }.onSuccess { cv = it; infoMsg = done }.onFailure { errorMsg = it.message }
            cvBusy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uid = AuthRepository.currentUid
        if (uri != null && uid != null) {
            scope.launch {
                photoBusy = true
                errorMsg = null
                runCatching {
                    val b64 = Photo.toAvatarBase64(context, uri)
                    ProfileRepository.savePhoto(uid, b64)
                    b64
                }.onSuccess { onProfileChanged(profile.copy(photo = it)) }
                    .onFailure { errorMsg = tr("Couldn't upload the photo", "ছবি আপলোড হয়নি") }
                photoBusy = false
            }
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val cs = MaterialTheme.colorScheme
    val c = cv

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- মাথা: ছবি, নাম, শিরোনাম, এলাকা, ব্যাজ ----
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceContainerHigh)
                        .clickable(enabled = !photoBusy) { if (avatar == null) pickPhoto() else photoMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        photoBusy -> CircularProgressIndicator(Modifier.size(32.dp))
                        avatar != null -> Image(avatar, tr("Profile photo", "প্রোফাইলের ছবি"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else -> AppIcon(R.drawable.ic_person_filled, null, Modifier.size(56.dp), cs.onSurfaceVariant)
                    }
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(cs.primary).clickable(enabled = !photoBusy) { pickPhoto() },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(R.drawable.ic_photo_camera, tr("Change photo", "ছবি বদলান"), Modifier.size(18.dp), cs.onPrimary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            c?.headline?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(Districts.label(profile.placeName), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (profile.bloodGroup.isNotBlank() && profile.bloodGroup != Rules.UNKNOWN_GROUP) {
                    Tag(tr("Blood donor ", "রক্তদাতা ") + profile.bloodGroup, cs.errorContainer, cs.onErrorContainer)
                }
                community?.approvedBadge?.let { BadgeChip(it) }
                if (c?.employerVerified == true) Tag(tr("Verified employer", "যাচাই করা নিয়োগকর্তা"), cs.secondaryContainer, cs.onSecondaryContainer)
                if (c?.openToWork == true) Tag(tr("Open to work", "কাজ খুঁজছেন"), cs.tertiaryContainer, cs.onTertiaryContainer)
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }

        // ---- কাজ: সিভি বদলানো, শেয়ার, PDF ----
        if (c != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(tr("Edit CV", "সিভি বদলান"), R.drawable.ic_edit, onEditCv, primary = true)
                CvShareActions(
                    c.copy(name = profile.name, photo = profile.photo.orEmpty()),
                    onMakePublic = { saveCv(c.copy(public = true), tr("Profile link is on", "প্রোফাইলের লিংক চালু হয়েছে")) },
                    onMessage = { infoMsg = it },
                )
            }
        }

        // ---- প্রোফাইল কতটা পূরণ, আর কী বাকি ----
        if (c != null && c.completeness < 100) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Profile ${c.completeness}% complete", "প্রোফাইল ${num(c.completeness)}% পূরণ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { c.completeness / 100f }, modifier = Modifier.fillMaxWidth())
                    SmallNote(tr("A complete profile gets more replies from employers.", "পুরো প্রোফাইল থাকলে নিয়োগকর্তারা বেশি সাড়া দেন।"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.missing.forEach { m ->
                            AssistChip(
                                onClick = { if (m == "photo") pickPhoto() else onEditCv() },
                                label = { Text(missingLabel(m)) },
                                leadingIcon = { AppIcon(R.drawable.ic_add, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }
        }

        // ---- রক্তদান ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (notEligible == null) cs.tertiaryContainer else cs.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BloodGroupBadge(profile.bloodGroup, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (notEligible == null) tr("You can donate now", "এখন রক্ত দিতে পারবেন") else tr("Not eligible right now", "এখন রক্ত দিতে পারবেন না"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        if (notEligible != null) Text(notEligible, style = MaterialTheme.typography.bodySmall)
                        val days = Rules.daysUntilEligible(profile.lastDonationMillis)
                        if (days > 0) Text(tr("$days days to go", "আর ${num(days)} দিন বাকি"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    }
                }
                InfoLine(R.drawable.ic_history, tr("Last donation", "শেষ রক্তদান"), profile.lastDonationMillis?.let { Rules.formatDate(it) } ?: tr("Never", "কখনো দেননি"))
                if ((c?.donationCount ?: 0) > 0) InfoLine(R.drawable.ic_volunteer_activism, tr("Donated through Dorkar", "Dorkar দিয়ে রক্তদান"), tr("${c?.donationCount} time(s)", "${num(c?.donationCount ?: 0)} বার"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(tr("Nearby requests", "কাছের রিকোয়েস্ট"), R.drawable.ic_location_on, onOpenHome)
                    SmallAction(tr("Change last donation date", "শেষ রক্তদানের তারিখ বদলান"), R.drawable.ic_calendar_month, { showDateDialog = true })
                }
                if (c != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Show donor badge on my profile link", "প্রোফাইলের লিংকে রক্তদাতার ব্যাজ দেখাবে"), style = MaterialTheme.typography.bodyMedium)
                            SmallNote(tr("Shows your blood group to people you share the link with.", "যাঁকে লিংক পাঠাবেন তিনি রক্তের গ্রুপ দেখবেন।"))
                        }
                        Switch(checked = c.showDonor, enabled = !cvBusy, onCheckedChange = { on -> saveCv(c.copy(showDonor = on), tr("Saved", "সেভ হয়েছে")) })
                    }
                }
            }
        }

        // ---- পেশাদার ব্যাজ ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            val badge = community?.badge
            MenuRow(
                R.drawable.ic_workspace_premium,
                tr("Professional badge", "পেশাদার ব্যাজ"),
                when (badge?.status) {
                    "approved" -> if (badge.changePending) Badges.label(badge.type) + tr(" · correction under review", " · সংশোধন যাচাই চলছে")
                    else Badges.label(badge.type) + tr(" · tap to correct details", " · তথ্য ঠিক করতে চাপুন")
                    "pending" -> tr("Under review", "যাচাই চলছে")
                    "rejected" -> tr("Not approved. Tap to apply again.", "অনুমোদন হয়নি। আবার আবেদন করুন।")
                    else -> tr("For doctors, nurses and health experts", "ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞদের জন্য")
                },
                if (badge?.status == "pending" || badge?.changePending == true) null else ({ showBadgeDialog = true }),
            )
            if (badge != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    StatusReasonLine(badge.status, badge.statusReason)
                    DocsNeededNotice("badge", AuthRepository.currentUid.orEmpty(), VerifyDocs.badge(badge.type), badge.docsMissing && badge.status == "pending", badge.docsRequested, badge.docsNote) {
                        onCommunityChanged((community ?: CommunityMe(null, false)).copy(badge = badge.copy(docsMissing = false, docsRequested = false)))
                    }
                }
            }
            if (onOpenMyApplications != null) { // FEATURE:jobs
                HorizontalDivider()
                MenuRow(R.drawable.ic_nav_work, tr("My job applications", "আমার চাকরির আবেদন"), null, onOpenMyApplications)
            }
        }

        // ---- সিভি ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c == null -> LoadingBox()
                    c.headline.isBlank() && c.experience.isEmpty() && c.education.isEmpty() -> {
                        Text(tr("Your CV", "আপনার সিভি"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SmallNote(tr("Add your work and education once — apply to jobs with one tap, save as PDF, or share a link.", "কাজ আর পড়াশোনা একবার লিখে রাখুন — এক চাপে চাকরিতে আবেদন, PDF, বা লিংক দিয়ে শেয়ার।"))
                        Button(onClick = onEditCv, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Make my CV", "সিভি বানান"), R.drawable.ic_add) }
                    }
                    else -> CvBody(c)
                }
            }
        }

        // ---- ব্যক্তিগত তথ্য (শুধু আপনি দেখেন) ----
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoLine(
                    R.drawable.ic_person_outline,
                    tr("Age · Weight", "বয়স · ওজন"),
                    listOfNotNull(
                        Rules.ageYears(profile.birthDateMillis)?.let { num(it) + tr(" yrs", " বছর") },
                        profile.weightKg?.let { num(it) + tr(" kg", " কেজি") },
                    ).joinToString(" · "),
                )
                InfoLine(R.drawable.ic_call, tr("Phone", "ফোন"), num(profile.phone))
                AuthRepository.currentEmail?.let { InfoLine(R.drawable.ic_mail, tr("Email", "ইমেইল"), it) }
                TextButton(onClick = onEditProfile) { ButtonContent(false, tr("Edit personal details", "ব্যক্তিগত তথ্য বদলান"), R.drawable.ic_edit) }
            }
        }
    }

    if (photoMenu) {
        AlertDialog(
            onDismissRequest = { photoMenu = false },
            title = { Text(tr("Profile photo", "প্রোফাইলের ছবি")) },
            confirmButton = {
                TextButton(onClick = {
                    photoMenu = false
                    pickPhoto()
                }) { Text(tr("Change", "বদলান")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    photoMenu = false
                    val uid = AuthRepository.currentUid ?: return@TextButton
                    scope.launch {
                        runCatching { ProfileRepository.savePhoto(uid, null) }
                            .onSuccess { onProfileChanged(profile.copy(photo = null)) }
                            .onFailure { errorMsg = tr("Couldn't remove the photo", "ছবি মোছা যায়নি") }
                    }
                }) { Text(tr("Remove", "মুছুন")) }
            },
        )
    }
    if (showDateDialog) {
        DonationDateDialog(profile, onDismiss = { showDateDialog = false }) { d ->
            showDateDialog = false
            onProfileChanged(profile.copy(lastDonationMillis = d))
        }
    }
    if (showBadgeDialog) {
        val cur = community?.badge
        BadgeApplyDialog(
            onDismiss = { showBadgeDialog = false },
            onApplied = { type ->
                showBadgeDialog = false
                onCommunityChanged(
                    (community ?: CommunityMe(null, false)).copy(
                        badge = if (cur?.status == "approved") cur.copy(changePending = true) else BadgeState(type, "pending"),
                    ),
                )
            },
            current = cur,
        )
    }
}

/** প্রোফাইলের বাকি অংশের নাম (পরামর্শের চিপে) */
private fun missingLabel(m: String) = when (m) {
    "photo" -> tr("Add a photo", "ছবি দিন")
    "headline" -> tr("Add a headline", "শিরোনাম লিখুন")
    "about" -> tr("Write about you", "নিজের সম্পর্কে লিখুন")
    "experience" -> tr("Add work experience", "কাজের অভিজ্ঞতা দিন")
    "education" -> tr("Add education", "শিক্ষা দিন")
    "skills" -> tr("Add skills", "দক্ষতা দিন")
    "languages" -> tr("Add languages", "ভাষা দিন")
    else -> m
}
