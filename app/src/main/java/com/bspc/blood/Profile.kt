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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun ProfileTab(
    profile: Profile,
    community: CommunityMe?,
    onCommunityChanged: (CommunityMe) -> Unit,
    onEditProfile: () -> Unit,
    onOpenMyRequests: () -> Unit,
    onOpenMyAppointments: () -> Unit,
    onOpenSettings: () -> Unit,
    onProfileChanged: (Profile) -> Unit,
    onLogout: () -> Unit,
    onOpenAdmin: () -> Unit,
    onAccountDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notEligible = Rules.notEligibleReason(profile)
    val avatar = remember(profile.photo) { Photo.decodeAvatar(profile.photo) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var demoSwitch by remember { mutableStateOf(false) } // ডেমো
    var showBadgeDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    // "আপনার কি কোনো ব্যবসা আছে?" — যাঁর এখনো নেই; "না" বললে কার্ডের বদলে নিচে সাধারণ এক লাইন (২.২০.০)
    val businessKey = "business_q_" + AuthRepository.currentUid.orEmpty()
    var businessNo by remember { mutableStateOf(Dismissed.isOn(context, businessKey)) }
    val hasBusiness = MyWork.summary.value.hasBusiness
    // প্রোফাইল খুললে নিজের ব্যবসার হিসাব নতুন করে (আগে আনতে ব্যর্থ হয়ে থাকলে)
    LaunchedEffect(Unit) { if (!MyWork.loaded.value) MyWork.refresh(community?.approvedBadge) }
    var assisting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { assisting = runCatching { RxApi.assisting().isNotEmpty() }.getOrDefault(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // উপরের ছবি-☰ থেকে আসা পাতা: প্রোফাইলসহ পুরো অ্যাপের সেটিংস। ভাষা/থিম ইত্যাদি
            // আলাদা পাতা "অ্যাপ সেটিংস" (নিচের সারিতে) — নইলে "সেটিংসের ভেতরে সেটিংস" গুলিয়ে যেত
            Text(
                tr("Settings", "সেটিংস"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- ছবি, নাম, ব্যাজ ----
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !photoBusy) { if (avatar == null) pickPhoto() else photoMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        photoBusy -> CircularProgressIndicator(Modifier.size(32.dp))
                        avatar != null -> Image(
                            avatar,
                            contentDescription = tr("Profile photo", "প্রোফাইলের ছবি"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AppIcon(R.drawable.ic_person_filled, null, Modifier.size(56.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !photoBusy) { pickPhoto() },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(R.drawable.ic_photo_camera, tr("Change photo", "ছবি বদলান"), Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            community?.approvedBadge?.let {
                Spacer(Modifier.height(6.dp))
                BadgeChip(it)
            }
            AuthRepository.currentEmail?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ErrorText(errorMsg)
        }

        // ---- রক্তের গ্রুপ ও যোগ্যতা (রক্তদাতার তথ্য না থাকলে "রক্ত দিতে চাই") ----
        if (!profile.isDonor) {
            BecomeDonorCard { LoginGate.require(tr("Add your donor details", "রক্তদাতার তথ্য দিন"), donor = true) {} }
        } else Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (notEligible == null) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BloodGroupBadge(profile.bloodGroup, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (notEligible == null) tr("You can donate now", "এখন রক্ত দিতে পারবেন")
                        else tr("Not eligible right now", "এখন রক্ত দিতে পারবেন না"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (notEligible != null) Text(notEligible, style = MaterialTheme.typography.bodySmall)
                }
                AppIcon(
                    if (notEligible == null) R.drawable.ic_check_circle else R.drawable.ic_schedule,
                    null,
                    Modifier.size(28.dp),
                )
            }
        }

        // নিজের ব্যবসার তথ্য আনা যায়নি: কারণ দেখানো আর আবার চেষ্টা (নইলে "আমার কাজ" ট্যাব চুপচাপ হারিয়ে থাকে)
        MyWork.error.value?.let { err ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        tr("Couldn't load your business", "আপনার ব্যবসার তথ্য আনা যায়নি"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { scope.launch { MyWork.refresh(community?.approvedBadge) } }) {
                        Text(tr("Try again", "আবার চেষ্টা করুন"))
                    }
                }
            }
        }
        if (MyWork.loaded.value && !hasBusiness && !businessNo) {
            BusinessQuestionCard(
                onYes = { WorkNav.startAdd() },
                onNo = {
                    Dismissed.set(context, businessKey)
                    businessNo = true
                },
            )
        }

        // ---- তথ্য ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoLine(
                    R.drawable.ic_history,
                    tr("Last donation", "শেষ রক্তদান"),
                    profile.lastDonationMillis?.let { Rules.formatDate(it) } ?: tr("Never", "কখনো দেননি"),
                )
                InfoLine(
                    R.drawable.ic_person_outline,
                    tr("Age · Weight", "বয়স · ওজন"),
                    listOfNotNull(
                        Rules.ageYears(profile.birthDateMillis)?.let { num(it) + tr(" yrs", " বছর") },
                        profile.weightKg?.let { num(it) + tr(" kg", " কেজি") },
                    ).joinToString(" · "),
                )
                InfoLine(R.drawable.ic_call, tr("Phone", "ফোন"), num(profile.phone))
                InfoLine(R.drawable.ic_location_on, tr("Area", "এলাকা"), Districts.label(profile.placeName))
            }
        }

        // ---- কাজ ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            ActionRow(R.drawable.ic_list_alt, tr("My Requests", "আমার রিকোয়েস্ট"), null, onOpenMyRequests)
            HorizontalDivider()
            // নিজে নেওয়া অ্যাপয়েন্টমেন্ট (চেম্বারের বুকিং "আমার চেম্বার"-এ)
            ActionRow(R.drawable.ic_calendar_month, tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"), null, onOpenMyAppointments)
            HorizontalDivider()
            ActionRow(R.drawable.ic_medication, tr("My prescriptions", "আমার প্রেসক্রিপশন"), null, { RxNav.open.value = "mine" })
            HorizontalDivider()
            // কেয়ারগিভার (জরুরি যোগাযোগ, আমার হয়ে কাজ করতে পারেন), যাঁদের দেখাশোনা করি, পরিবারের সদস্য
            ActionRow(R.drawable.ic_health_and_safety_filled, tr("Caregivers & family", "কেয়ারগিভার ও পরিবার"), tr("People who can help you in the app", "যাঁরা অ্যাপে আপনার হয়ে কাজ করতে পারেন"), { RxNav.open.value = "care" })
            HorizontalDivider()
            // কোনো ডাক্তার সহকারী হিসেবে যোগ করলে তবেই
            if (assisting) {
                ActionRow(R.drawable.ic_group_round, tr("Doctor's assistant", "ডাক্তারের সহকারী"), null, { RxNav.open.value = "assist" })
                HorizontalDivider()
            }
            ActionRow(
                R.drawable.ic_history,
                tr("Change last donation date", "শেষ রক্তদানের তারিখ বদলান"),
                profile.lastDonationMillis?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি"),
                { showDateDialog = true },
            )
            HorizontalDivider()
            ActionRow(R.drawable.ic_edit, tr("Edit profile", "প্রোফাইল এডিট"), null, onEditProfile)
            HorizontalDivider()
            if (MyWork.loaded.value && !hasBusiness && businessNo) {
                ActionRow(R.drawable.ic_storefront, tr("Add a business", "ব্যবসা যোগ করুন"), tr("Ambulance, pharmacy, blood bank, lab", "অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি"), { WorkNav.startAdd() })
                HorizontalDivider()
            }
            val badge = community?.badge
            ActionRow(
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
            // বাতিলের কারণ, আর অ্যাডমিন সনদের ছবি চাইলে এখানেই দেওয়া
            if (badge != null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    StatusReasonLine(badge.status, badge.statusReason)
                    DocsNeededNotice("badge", AuthRepository.currentUid.orEmpty(), VerifyDocs.badge(badge.type), badge.docsMissing && badge.status == "pending", badge.docsRequested, badge.docsNote) {
                        onCommunityChanged((community ?: CommunityMe(null, false)).copy(badge = badge.copy(docsMissing = false, docsRequested = false)))
                    }
                }
            }
            HorizontalDivider()
            ActionRow(
                R.drawable.ic_settings_outline,
                tr("App settings", "অ্যাপ সেটিংস"),
                tr("Language, theme, notifications, area", "ভাষা, থিম, নোটিফিকেশন, এলাকা"),
                onOpenSettings,
            )
            HorizontalDivider()
            ActionRow(
                R.drawable.ic_help,
                tr("Help & appeals", "সাহায্য ও আপিল"),
                null,
                { HelpNav.open.value = "" },
            )
            if (community?.isAdmin == true || AuthRepository.currentEmail.equals(SUPPORT_EMAIL, ignoreCase = true)) {
                HorizontalDivider()
                ActionRow(R.drawable.ic_admin_panel_settings, tr("Admin", "অ্যাডমিন"), null, onOpenAdmin)
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            if (DemoLogin.isDemo(AuthRepository.currentUid)) { // ডেমো
                ActionRow(R.drawable.ic_sync, tr("Switch role", "রোল বদলান"), tr("Demo", "ডেমো"), { demoSwitch = true })
                HorizontalDivider()
            }
            ActionRow(R.drawable.ic_logout, tr("Log out", "লগআউট"), null, onLogout)
            HorizontalDivider()
            ActionRow(
                R.drawable.ic_delete,
                tr("Delete account", "অ্যাকাউন্ট মুছুন"),
                null,
                { confirmDelete = true },
                MaterialTheme.colorScheme.error,
            )
        }
    }

    if (demoSwitch) DemoSwitchDialog(onDismiss = { demoSwitch = false }) // ডেমো
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text(tr("Delete your account?", "অ্যাকাউন্ট মুছবেন?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "Your account will be deleted permanently after 30 days: profile, photo, phone number, requests, questions, answers, tips, appointments, badge and your businesses. Until then your listings and notifications are off and you are logged out everywhere. Log in again within 30 days to cancel and get everything back.",
                            "৩০ দিন পর আপনার অ্যাকাউন্ট চিরতরে মুছে যাবে: প্রোফাইল, ছবি, ফোন নম্বর, রিকোয়েস্ট, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, ব্যাজ আর আপনার ব্যবসা। এর মধ্যে আপনার তালিকা আর নোটিফিকেশন বন্ধ থাকবে, সব ফোন থেকে লগআউট হবে। ৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে।",
                        )
                    )
                    if (deleting) CircularProgressIndicator(Modifier.size(24.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            runCatching { Moderation.deleteAccount() }
                                .onSuccess {
                                    confirmDelete = false
                                    onAccountDeleted()
                                }
                                .onFailure {
                                    errorMsg = it.message
                                    confirmDelete = false
                                }
                            deleting = false
                        }
                    },
                    enabled = !deleting,
                ) { Text(tr("Delete account", "অ্যাকাউন্ট মুছুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text(tr("Cancel", "বাতিল")) } },
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
                    )
                )
            },
            current = cur,
        )
    }
}

@Composable
private fun ActionRow(
    icon: Int,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    color: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = if (subtitle != null) ({ Text(subtitle) }) else null,
        leadingContent = { AppIcon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        trailingContent = if (onClick != null) ({ AppIcon(R.drawable.ic_chevron_right, null) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
