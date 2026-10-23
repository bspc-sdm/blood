@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bspc.blood

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * "আমার কাজ"-এর প্রথম পাতা — "আজকের কাজ" (CONTRACT.md §১), উপর থেকে নিচে:
 *  ১) ভূমিকার চিপ (একাধিক থাকলে): সব · চেম্বার · ফার্মেসি · ল্যাব · ব্লাড ব্যাংক · অ্যাম্বুলেন্স — বাছাই মনে থাকে
 *  ২) প্রতিটা ব্যবসার ঘর: নাম (চাপলে সেই পাতা), খোলা / বিরতি / বন্ধ (অ্যাম্বুলেন্স: খালি আছি / ট্রিপে আছি; ব্লাড ব্যাংক: স্টক কবের),
 *     তার নিচে আজকের ২–৪টা সংখ্যা (চাপলে সেই তালিকা) — নতুন/অসম্পূর্ণ হলে সংখ্যার জায়গায় "শুরু করতে যা বাকি"
 *  ৩) "এখনই দেখুন": যে কাজ এখন করতে হবে (সর্বোচ্চ ৫টা + "সব দেখুন"), প্রতিটায় একটাই বোতাম; খালি হলে এক লাইন
 *  ৪) "রোগীরা আপনাকে যেভাবে দেখেন" — এক লাইন, "দেখুন" চাপলে পুরো কার্ড
 *  ৫) নিচে ব্যবসার কার্ড আর "আরেকটা ব্যবসা যোগ করুন"
 * যাঁর কিছু নেই: একই ঘরে "সেবা দিন" — কারা যোগ দিতে পারেন আর যোগ করার পথ। লোড হওয়ার সময় কঙ্কাল (ট্যাব পরে লাফিয়ে আসে না)।
 * "আমার কাজ"-এর সব পাতায় একই নিচের বার (WorkShell)।
 */

/** নিচের বারের "আমার কাজ"-এর ঘরটা থেকেই — লগইন, অবস্থা অনুযায়ী হোম / কঙ্কাল / "সেবা দিন" */
@Composable
fun WorkTab(community: CommunityMe?, onOpenBusiness: (String) -> Unit, onOpenChamber: () -> Unit, onTabChange: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val guest = LoginGate.isGuest
    fun badge() {
        LoginGate.require(tr("Log in to apply for a badge", "ব্যাজের আবেদন করতে লগইন করুন")) {
            WorkNav.openBadge.value = true
            onTabChange(Tabs.PROFILE)
        }
    }
    fun pick(kind: String) {
        LoginGate.require(tr("Log in to add your business", "আপনার ব্যবসা যোগ করতে লগইন করুন")) { onOpenBusiness(kind) }
    }
    when {
        guest -> JoinWorkPage(null, ::pick, ::badge)
        MyWork.has -> WorkHomeLoader(onOpenBusiness, onOpenChamber)
        !MyWork.settled && MyWork.error.value == null -> WorkSkeleton()
        !MyWork.settled -> Column(Modifier.fillMaxSize().padding(16.dp).testTag("work_error"), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(WorkText.loadFailed(), style = MaterialTheme.typography.titleMedium)
            ErrorText(MyWork.error.value)
            OutlinedButton(onClick = { scope.launch { MyWork.refresh(MyWork.lastBadge, community != null) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(WorkText.retry())
            }
        }
        else -> JoinWorkPage(community?.badge?.status, ::pick, ::badge)
    }
}

/** তথ্য আনা আর অবস্থা বদলানো — দেখানোটা WorkHome (টেস্টে সরাসরি) */
@Composable
private fun WorkHomeLoader(onOpenBusiness: (String) -> Unit, onOpenChamber: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = MyWork.summary.value
    var chip by remember { mutableStateOf(WorkHomePrefs.role(ctx)) }
    var live by remember { mutableStateOf<WorkToday?>(null) }
    var loading by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf<Provider?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    /** চাপার সাথে সাথে নতুন অবস্থা (সার্ভার না বললে আগেরটায় ফেরে) */
    var optimistic by remember { mutableStateOf<Map<String, Pair<String, Long?>>>(emptyMap()) }
    val pro = s.professional
    LaunchedEffect(reload, pro) {
        // ডাক্তার: আজ কোন চেম্বার বসে/বন্ধ আর রোগীরা যে কার্ড দেখেন (নতুন সার্ভারে chamber_days, পুরোনোয় provider_me)
        if (pro != null && pro != "nurse") {
            val p = runCatching { ProviderSwitches.chamberDays(null) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
                ?: runCatching { Appointments.mine() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            if (p != null) provider = p
        }
    }
    LaunchedEffect(reload, s) {
        runCatching { WorkStatusApi.today() }
            .onSuccess { live = it; errorMsg = null; optimistic = emptyMap() }
            .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; errorMsg = e.message }
        loading = false
    }
    val base = WorkFallback.merge(live, WorkFallback.build(s, provider))
    val today = base.copy(
        items = base.items.map { b -> b.copy(units = b.units.map { u -> optimistic[u.id]?.let { (st, until) -> u.copy(status = st, pausedUntil = until) } ?: u }) },
    )
    val roles = WorkRoles.owned(s, live)
    val selected = if (chip in roles) chip else WorkRoles.ALL
    val shown = if (selected == WorkRoles.ALL) roles else listOf(selected)
    WorkHome(
        s = s,
        today = today,
        loading = loading && live == null,
        roles = roles,
        chip = selected,
        onChip = { chip = it; WorkHomePrefs.setRole(ctx, it) },
        preview = workPreviewCards(s, provider, shown),
        busyId = busy,
        error = errorMsg,
        onSetStatus = { b, st, until ->
            if (busy == null) {
                optimistic = optimistic + (b.id to (st to until))
                busy = b.id
                errorMsg = null
                scope.launch {
                    runCatching { WorkStatusApi.set(b, st, until) }
                        .onSuccess {
                            // নিজের তথ্যও নতুন করে (পুরোনো সার্ভারে অবস্থা ওখান থেকেই)
                            runCatching { MyWork.refresh(MyWork.lastBadge, MyWork.badgeKnown.value) }
                            reload++
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            optimistic = optimistic - b.id
                            errorMsg = e.message
                        }
                    busy = null
                }
            }
        },
        onOpen = { role, target ->
            if (role == WorkRoles.CHAMBER) onOpenChamber()
            else {
                // শুধু যে পাতা পড়ে (ফার্মেসি, ল্যাব) — নইলে পরে অন্য পাতায় গিয়ে পড়ত
                if (role == "pharmacy" || role == "lab") WorkNav.target.value = target
                onOpenBusiness(role)
            }
        },
        onOpenBusiness = onOpenBusiness,
        onOpenChamber = onOpenChamber,
        onAmbulanceChanged = {
            scope.launch {
                runCatching { MyWork.refresh(MyWork.lastBadge, MyWork.badgeKnown.value) }
                reload++
            }
        },
    )
}

/** রোগীরা যে কার্ড দেখেন — শুধু অনুমোদিত (তালিকায় যা আছে) */
fun workPreviewCards(s: WorkSummary, provider: Provider?, roles: List<String>): List<ListingCardData> {
    val out = ArrayList<ListingCardData>()
    if (WorkRoles.CHAMBER in roles) provider?.let { out += providerCard(it, CareFilter(), onOpen = {}, onBook = null) }
    if ("pharmacy" in roles) s.pharmacy?.takeIf { it.status == "approved" }?.let { out += pharmacyCard(it, {}, {}, {}, null) }
    if ("lab" in roles) s.lab?.takeIf { it.status == "approved" }?.let { out += labCard(it, {}, {}, null, null) }
    if ("bloodbank" in roles) s.bloodBanks.filter { it.status == "approved" }.forEach { out += bloodBankCard(it, {}, {}, null) }
    if ("ambulance" in roles) s.ambulances.filter { it.status == "approved" }.forEach { out += ambulanceCard(it, {}, {}, null) }
    return out
}

/** বিরতির কমলা রং (আলো/অন্ধকারে আলাদা) */
@Composable
private fun pauseColors(): Triple<Color, Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // লেখা, ঘরের পটভূমি, ঘরের লেখা
    return if (dark) Triple(Color(0xFFFFB866), Color(0xFF5C3300), Color(0xFFFFDDB3))
    else Triple(Color(0xFFB45309), Color(0xFFFFE0B2), Color(0xFF6B3500))
}

@Composable
fun WorkHome(
    s: WorkSummary,
    today: WorkToday,
    loading: Boolean,
    roles: List<String>,
    chip: String,
    onChip: (String) -> Unit,
    preview: List<ListingCardData>,
    busyId: String?,
    error: String?,
    onSetStatus: (WorkUnit, String, Long?) -> Unit,
    onOpen: (role: String, target: String?) -> Unit,
    onOpenBusiness: (String) -> Unit,
    onOpenChamber: () -> Unit,
    now: Long = System.currentTimeMillis(),
    /** অ্যাম্বুলেন্সের খালি/ট্রিপ বদলালে (নতুন করে আনা) */
    onAmbulanceChanged: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val shown = if (chip == WorkRoles.ALL) roles else listOf(chip)
    val blocks = workBlocks(today, shown)
    val tasks = workTasks(today, blocks, chip == WorkRoles.ALL)
    var allTasks by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf<WorkUnit?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("work_home"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column {
                Text(WorkText.title(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(Rules.formatDate(now), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        if (roles.size > 1) item(key = "chips") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("work_chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(WorkRoles.ALL) + roles).forEach { r ->
                    FilterChip(
                        selected = chip == r,
                        onClick = { onChip(r) },
                        label = { Text(WorkRoles.label(r), fontWeight = if (chip == r) FontWeight.Bold else FontWeight.Normal) },
                        leadingIcon = if (chip == r) ({ AppIcon(R.drawable.ic_done, null, Modifier.size(18.dp)) }) else null,
                        modifier = Modifier.testTag("work_chip_$r"),
                    )
                }
            }
        }
        if (error != null) item(key = "error") { ErrorText(error) }
        blocks.forEach { b ->
            item(key = "block_" + b.role) {
                WorkBlockCard(s, b, today.live, loading, busyId, now, { pausing = it }, onSetStatus, onOpen, onAmbulanceChanged)
            }
        }
        // এখনই দেখুন (সার্ভারের তথ্য থাকলে; লোড হওয়ার সময় কঙ্কাল)
        if (loading) item(key = "now_skel") { SkeletonBar(Modifier.fillMaxWidth().height(72.dp)) }
        else if (today.live) {
            item(key = "now_title") { Text(WorkText.now(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (tasks.all.isEmpty()) item(key = "now_empty") {
                Row(Modifier.fillMaxWidth().testTag("work_now_empty"), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_check_circle, null, Modifier.size(20.dp), cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(WorkText.nothingNow(), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
            } else {
                val visible = if (allTasks) tasks.all else tasks.top
                visible.forEachIndexed { i, t ->
                    item(key = "task_${t.key}_$i") { WorkTaskRow(t, roles.size > 1 && chip == WorkRoles.ALL) { onOpen(t.role, WorkTargets.forTask(t)) } }
                }
                if (tasks.total > tasks.top.size && tasks.all.size > tasks.top.size) item(key = "now_more") {
                    TextButton(onClick = { allTasks = !allTasks }, modifier = Modifier.heightIn(min = 48.dp).testTag("work_now_more")) {
                        Text(if (allTasks) WorkText.showLess() else WorkText.seeAll(tasks.total))
                    }
                }
            }
        }
        if (preview.isNotEmpty()) item(key = "preview") { WorkPreviewLine(preview.first()) { previewOpen = true } }
        item(key = "biz_title") { Text(WorkText.businesses(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item(key = "biz") { WorkBusinessList(shown.toSet(), onOpenBusiness, onOpenChamber) }
    }
    pausing?.let { b ->
        PauseDialog(onPick = { until -> pausing = null; onSetStatus(b, "paused", until) }, onDismiss = { pausing = null }, now = now)
    }
    if (previewOpen) {
        AlertDialog(
            onDismissRequest = { previewOpen = false },
            title = { Text(WorkText.preview()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("work_preview_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    preview.forEach { ListingCard(it) }
                }
            },
            confirmButton = { TextButton(onClick = { previewOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Close", "বন্ধ করুন")) } },
        )
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)))
}

/** লোড হওয়ার সময়: একই মাপের ফাঁকা ঘর (তথ্য এলে জায়গা নড়ে না) */
@Composable
fun WorkSkeleton() {
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("work_skeleton"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBar(Modifier.width(160.dp).height(28.dp))
        SkeletonBar(Modifier.width(110.dp).height(16.dp))
        SkeletonBar(Modifier.fillMaxWidth().height(168.dp))
        SkeletonBar(Modifier.fillMaxWidth().height(72.dp))
        SkeletonBar(Modifier.fillMaxWidth().height(72.dp))
    }
}

private fun blockTitle(s: WorkSummary, b: WorkBlock): Pair<String, String> = when (val role = b.role) {
    // শুধু অন্য ডাক্তারের সহকারী: ডাক্তারের নাম
    WorkRoles.CHAMBER -> if (s.professional == null && b.items.isNotEmpty() && b.items.all { it.access == "assistant" }) {
        b.items.first().name to tr("Assistant", "সহকারী")
    } else {
        (if (s.professional == "nurse") tr("My home care", "আমার বাসায় সেবা") else tr("My chamber", "আমার চেম্বার")) to
            (professionalLine(s)?.substringBefore(" · ") ?: WorkRoles.label(role))
    }
    "pharmacy" -> (s.pharmacy?.name ?: WorkRoles.label(role)) to WorkRoles.label(role)
    "lab" -> (s.lab?.name ?: WorkRoles.label(role)) to WorkRoles.label(role)
    "bloodbank" -> (if (s.bloodBanks.size == 1) s.bloodBanks[0].name else WorkRoles.label(role)) to
        (if (s.bloodBanks.size > 1) tr("${num(s.bloodBanks.size)} blood banks", "${num(s.bloodBanks.size)}টা ব্লাড ব্যাংক") else WorkRoles.label(role))
    "ambulance" -> WorkRoles.label(role) to s.ambulances.filter { it.status == "approved" }.let { live ->
        tr("${num(s.ambulances.size)} in total", "${num(s.ambulances.size)}টা") +
            if (live.isNotEmpty()) tr(" · ${num(live.count { it.available })} free", " · ${num(live.count { it.available })}টা খালি") else ""
    }
    else -> WorkRoles.label(role) to ""
}

@Composable
private fun WorkBlockCard(
    s: WorkSummary,
    b: WorkBlock,
    live: Boolean,
    loading: Boolean,
    busyId: String?,
    now: Long,
    onPause: (WorkUnit) -> Unit,
    onSetStatus: (WorkUnit, String, Long?) -> Unit,
    onOpen: (String, String?) -> Unit,
    onAmbulanceChanged: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (title, sub) = blockTitle(s, b)
    val pending = b.pending
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("work_block_${b.role}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // মাথা: চাপলে সেই পাতা
            // একটাই দোকান/ল্যাব: অবস্থার লেখা মাথায় ("ফার্মেসি" লেখার জায়গায়) — ঘর ছোট থাকে
            val single = b.units.singleOrNull()?.takeIf { b.role == "pharmacy" || b.role == "lab" }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onOpen(b.role, null) }.testTag("work_open_${b.role}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = cs.primaryContainer) {
                    AppIcon(WorkRoles.icon(b.role, s.professional), null, Modifier.padding(8.dp).size(22.dp), cs.onPrimaryContainer)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val head = single?.let { u -> statusLine(u, b.role).takeIf { it.first.isNotBlank() }?.let { Triple(it.first, it.second, u.id) } }
                    if (head != null) StatusDot(head.first, head.second, "work_status_line_${head.third}", Modifier.padding(top = 2.dp))
                    else if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(WorkText.openPage(), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 8.dp))
                AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.primary)
            }
            if (pending) {
                Text(WorkText.underReview(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.testTag("work_pending_${b.role}"))
            }
            // অবস্থা
            if (b.role == WorkRoles.CHAMBER && s.professional != "nurse" && b.units.isEmpty() && !loading) {
                Text(WorkText.noChamberToday(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            // অ্যাম্বুলেন্স: খালি আছি / ট্রিপে আছি — গাড়ির পাতার একই অংশ (Ambulance.kt), এখানেই একবার (পাতায় আর নেই)
            if (b.role == "ambulance") AmbulanceAvailabilityContent(s.ambulances, onAmbulanceChanged)
            b.units.forEach { r ->
                when (b.role) {
                    "ambulance" -> Unit
                    "bloodbank" -> StockRow(r, b.units.size > 1, now) { onOpen(b.role, null) }
                    else -> StatusRow(r, b.role, WorkStatusApi.canPause(b.role, live), busyId == r.id, showName = b.role == WorkRoles.CHAMBER, showLine = single == null, onPause = { onPause(r) }) { st -> onSetStatus(r, st, null) }
                }
            }
            // শুরু করতে যা বাকি (থাকলে সংখ্যার জায়গায়) — নইলে আজকের সংখ্যা
            val setup = b.setupLeft()
            val tiles = b.tiles()
            // নতুন (অর্ধেকের কম হয়েছে, বা অনুমোদন বাকি): তালিকাই সংখ্যার জায়গায়; প্রায় শেষ হলে সংখ্যার উপরে ছোট করে
            val newcomer = setup.isNotEmpty() && (setup.count { it.done } * 2 < setup.size || setup.any { it.key == "approved" && !it.done })
            if (setup.isNotEmpty()) SetupChecklist(setup) { onOpen(b.role, if (b.role == "pharmacy" || b.role == "lab") "shop" else null) }
            when {
                newcomer -> Unit
                loading && !live && b.role != "bloodbank" -> SkeletonBar(Modifier.fillMaxWidth().height(64.dp))
                tiles.isNotEmpty() -> TilesRow(b.role, tiles) { key -> onOpen(b.role, WorkTargets.forCount(b.role, key)) }
            }
        }
    }
}

/** অবস্থার লেখা আর রং: "এখন খোলা" / "বিরতি · আবার খুলবে …" (কমলা) / "বন্ধ" (লাল); চেম্বারে "আজ সিরিয়াল চলছে" / "আজ বন্ধ" */
@Composable
private fun statusLine(r: WorkUnit, role: String): Pair<String, Color> {
    val cs = MaterialTheme.colorScheme
    val chamber = role == WorkRoles.CHAMBER
    val text = when (r.status) {
        "paused" -> WorkText.paused() + (r.pausedUntil?.let { " · " + WorkText.reopensAt(it) } ?: "")
        "closed" -> if (chamber) WorkText.closedToday() else WorkText.closed()
        "open" -> if (chamber) WorkText.serialToday() else WorkText.openNow()
        else -> ""
    }
    return text to when (r.status) {
        "paused" -> pauseColors().first
        "closed" -> cs.error
        else -> cs.primary
    }
}

@Composable
private fun StatusDot(text: String, color: Color, tag: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.testTag(tag), maxLines = 2)
    }
}

/** খোলা / বিরতি / বন্ধ — বাছা ঘর রঙে আর ✓-এ (শুধু রঙ নয়); showLine = false হলে লেখাটা ঘরের মাথায় (একটাই দোকান/ল্যাব) */
@Composable
private fun StatusRow(r: WorkUnit, role: String, canPause: Boolean, busy: Boolean, showName: Boolean, showLine: Boolean, onPause: () -> Unit, onSet: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (_, pauseBg, pauseFg) = pauseColors()
    val (line, lineColor) = statusLine(r, role)
    Column(Modifier.fillMaxWidth().testTag("work_status_${r.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // চেম্বার: নাম আর অবস্থা এক লাইনে
        if ((showName && r.name.isNotBlank()) || (showLine && line.isNotBlank())) Row(verticalAlignment = Alignment.CenterVertically) {
            if (showName && r.name.isNotBlank()) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
            }
            if (showLine && line.isNotBlank()) StatusDot(line, lineColor, "work_status_line_${r.id}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateButton(WorkText.open(), R.drawable.ic_check_circle, r.status == "open", cs.primaryContainer, cs.onPrimaryContainer, !busy, "work_set_open_${r.id}") { onSet("open") }
            if (canPause) StateButton(WorkText.paused(), R.drawable.ic_schedule, r.status == "paused", pauseBg, pauseFg, !busy, "work_set_paused_${r.id}") { onPause() }
            StateButton(WorkText.closed(), R.drawable.ic_close, r.status == "closed", cs.errorContainer, cs.onErrorContainer, !busy, "work_set_closed_${r.id}") { onSet("closed") }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StateButton(
    label: String,
    icon: Int,
    selected: Boolean,
    bg: Color,
    fg: Color,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) bg else cs.surface,
        contentColor = if (selected) fg else cs.onSurface,
        border = if (selected) null else BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) onClick() }),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            AppIcon(if (selected) R.drawable.ic_done else icon, null, Modifier.size(18.dp), if (selected) fg else cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}

/** ব্লাড ব্যাংক: স্টক কবের — পুরনো হলে লাল, আর "স্টক দিন" */
@Composable
private fun StockRow(r: WorkUnit, showName: Boolean, now: Long, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val note = Listing.stockNote(r.stockUpdatedAt, r.stockStale) ?: WorkText.stockNever()
    val bad = r.stockStale || r.stockUpdatedAt <= 0L
    Row(Modifier.fillMaxWidth().testTag("work_status_${r.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (showName) Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = if (bad) cs.error else cs.onSurfaceVariant)
        }
        if (bad) Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag("work_stock_${r.id}")) { Text(WorkText.stockGive()) }
        else OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag("work_stock_${r.id}")) { Text(WorkText.stockGive()) }
    }
}

/** আজকের সংখ্যা: ২–৪টা ঘর, চাপলে সেই তালিকা */
@Composable
private fun TilesRow(role: String, tiles: List<Pair<String, Double>>, onTap: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // ৪টা হলে ২×২ (টাকার অঙ্ক কাটে না), নইলে এক সারি
    Column(Modifier.fillMaxWidth().testTag("work_tiles_$role"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      tiles.chunked(if (tiles.size == 4) 2 else 3).forEach { rowTiles ->
       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowTiles.forEach { (k, v) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cs.surface,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("work_tile_${role}_$k").clickable(role = Role.Button) { onTap(k) },
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    val value = WorkText.countValue(k, v)
                    Text(
                        value, style = if (value.length > 6) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1, color = if (v > 0 && !WorkText.isMoney(k)) cs.primary else cs.onSurface,
                    )
                    Text(WorkText.count(role, k).orEmpty(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
        }
       }
      }
    }
}

/** শুরু করতে যা বাকি: বাকিগুলো এক এক সারি (চাপলে সেটআপ), হয়ে যাওয়াগুলো এক লাইনে ✓ */
@Composable
private fun SetupChecklist(items: List<WorkSetup>, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = items.filter { it.done }
    Column(Modifier.fillMaxWidth().testTag("work_setup"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            WorkText.setupTitle() + " · " + tr("${num(done.size)} of ${num(items.size)} done", "${num(items.size)}টার ${num(done.size)}টা হয়েছে"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
        items.filter { !it.done }.forEach { item ->
            // অনুমোদন নিজে দেওয়া যায় না — শুধু "যাচাই চলছে"
            val waiting = item.key == "approved"
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !waiting, role = Role.Button, onClick = onOpen).testTag("work_setup_${item.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(cs.outlineVariant).padding(2.dp).clip(CircleShape).background(cs.surfaceContainerLow))
                Spacer(Modifier.width(10.dp))
                Text(
                    WorkText.setup(item.key) + if (waiting) " — " + WorkText.pending() else "",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                )
                if (!waiting) Text(tr("Add", "দিন"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
        if (done.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("work_setup_done"), verticalAlignment = Alignment.Top) {
            AppIcon(R.drawable.ic_check_circle, null, Modifier.size(20.dp), cs.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                tr("Done: ", "হয়েছে: ") + done.joinToString(" · ") { WorkText.setup(it.key) },
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkTaskRow(t: WorkTask, showRole: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("work_task")) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showRole) Tag(WorkRoles.label(t.role), cs.secondaryContainer, cs.onSecondaryContainer)
                Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = t.sub.ifBlank { if (t.at > 0) timeAgo(t.at) else "" }
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("work_task_action")) {
                Text(WorkText.action(t.action), maxLines = 1)
            }
        }
    }
}

/** "রোগীরা আপনাকে যেভাবে দেখেন" — এক লাইন, "দেখুন" চাপলে পুরো কার্ড */
@Composable
private fun WorkPreviewLine(first: ListingCardData, onSee: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSee).testTag("work_preview"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_person_outline, null, Modifier.size(20.dp), cs.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(WorkText.preview(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(Listing.keyLine(first.name, first.keyLine), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onSee, modifier = Modifier.heightIn(min = 48.dp).testTag("work_preview_see")) { Text(WorkText.see()) }
        }
    }
}

@Composable
private fun PauseDialog(onPick: (Long?) -> Unit, onDismiss: () -> Unit, now: Long) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(WorkText.pauseFor()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    WorkText.min30() to now + 30 * 60_000L,
                    WorkText.hour1() to now + 60 * 60_000L,
                    // আজকের মতো: until ছাড়া (ফার্মেসি/ল্যাব — পরের নির্ধারিত বদল পর্যন্ত; চেম্বার — আজকের মাঝরাত)
                    WorkText.restOfDay() to null,
                ).forEachIndexed { i, (label, until) ->
                    OutlinedButton(onClick = { onPick(until) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("work_pause_$i")) {
                        Text(label + (until?.let { " · " + WorkText.reopensAt(it) } ?: ""))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(WorkText.cancel()) } },
    )
}

/** যাঁর কোনো ব্যবসা/চেম্বার নেই: "সেবা দিন" — কারা যোগ দিতে পারেন, আর যোগ করার পথ */
@Composable
fun JoinWorkPage(badgeStatus: String?, onPick: (String) -> Unit, onBadge: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("work_join"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(WorkText.join(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    tr(
                        "Do you serve patients? Join Lifolo so patients nearby can find you, and run your daily work here.",
                        "আপনি কি রোগীদের সেবা দেন? Lifolo-তে যোগ দিলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন, আর রোজকার কাজ এখান থেকেই করবেন।",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        item {
            val pending = badgeStatus == "pending"
            JoinRow(
                R.drawable.ic_stethoscope,
                tr("Doctor or health expert", "ডাক্তার ও স্বাস্থ্য বিশেষজ্ঞ"),
                if (pending) tr("Your badge is under review", "আপনার ব্যাজ যাচাই চলছে")
                else tr("Get a professional badge, then open your chamber", "পেশাদার ব্যাজ নিন, তারপর চেম্বার খুলুন"),
                if (pending) null else tr("Apply", "আবেদন করুন"),
                "join_doctor",
                if (pending) null else onBadge,
            )
        }
        val rows = listOf(
            Triple("pharmacy", tr("Pharmacy", "ফার্মেসি"), tr("Take orders, write sales", "অর্ডার নিন, বিক্রি লিখুন")),
            Triple("lab", tr("Lab", "ল্যাব"), tr("Take test requests, send reports", "পরীক্ষার অনুরোধ নিন, রিপোর্ট পাঠান")),
            Triple("bloodbank", tr("Blood bank", "ব্লাড ব্যাংক"), tr("Tell people what blood you have", "কোন রক্ত আছে জানান")),
            Triple("ambulance", tr("Ambulance", "অ্যাম্বুলেন্স"), tr("Get calls from patients", "রোগীর ডাক পান")),
        )
        rows.forEach { (k, title, sub) ->
            item(key = "join_$k") { JoinRow(businessIcon(k), title, sub, tr("Add", "যোগ করুন"), "join_$k") { onPick(k) } }
        }
    }
}

@Composable
private fun JoinRow(icon: Int, title: String, sub: String, action: String?, tag: String, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.primaryContainer) {
                AppIcon(icon, null, Modifier.padding(8.dp).size(22.dp), cs.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (action != null) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 8.dp))
                AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.primary)
            }
        }
    }
}

/**
 * "আমার কাজ"-এর ভেতরের পাতা (ব্যবসার পাতা, চেম্বার): একই নিচের বার ("আমার কাজ" বাছা), উপরে পাতার নাম (পাতার নিজের BackTopBar)।
 * কিবোর্ড খোলা থাকলে বার লুকায় (ফর্মের জায়গা খায় না)।
 */
@Composable
fun WorkShell(onTab: (Int) -> Unit, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density) > 0
    Scaffold(bottomBar = { if (!ime) ModernNavBar(mainNavItems(), Tabs.WORK, onTab) }) { p ->
        Box(Modifier.fillMaxSize().padding(p).consumeWindowInsets(p).testTag("work_shell")) { content() }
    }
}
