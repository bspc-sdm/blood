package com.bspc.blood

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** নোটিফিকেশনে চাপ দিলে কোন রিকোয়েস্ট খুলতে হবে */
    private val openRequestId = mutableStateOf<String?>(null)
    private val openType = mutableStateOf<String?>(null)
    /** "services" নোটিফিকেশনে কোন ব্যবসা (ambulance, pharmacy, bloodbank, lab) */
    private val openKind = mutableStateOf<String?>(null)
    private val openQuestionId = mutableStateOf<String?>(null)
    /** প্রেসক্রিপশনের নোটিফিকেশন: কোন প্রেসক্রিপশন (rxId), কেয়ারগিভারকে এলে কোন রোগীর (asUid) */
    private val openExtra = mutableStateOf<Map<String, String>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        Catalog.init(this)
        // জেলা-উপজেলা-ইউনিয়নের তালিকা আগেভাগে পড়ে রাখা
        Thread { runCatching { Areas.ensure(this) } }.start()
        Notifier.ensureChannel(this)
        // অ্যাক্টিভিটি নতুন করে তৈরি হলে (যেমন লেখার আকার বদলালে) আগের পাতার অপেক্ষার কাজ আর চলে না — বাতিল
        if (savedInstanceState != null) LoginGate.pending.value = null
        // শুধু প্রথমবার খোলার সময় নোটিফিকেশনের রিকোয়েস্ট পড়া হয়
        if (savedInstanceState == null) {
            openRequestId.value = intent?.getStringExtra(Notifier.EXTRA_REQUEST_ID)
            openType.value = intent?.getStringExtra(Notifier.EXTRA_TYPE)
            openKind.value = intent?.getStringExtra(Notifier.EXTRA_KIND)
            openQuestionId.value = intent?.getStringExtra(Notifier.EXTRA_QUESTION_ID)
            openExtra.value = rxExtras(intent)
            sharedRequestId(intent)?.let { openRequestId.value = it }
            if (openType.value in HOME_CARE_TYPES) CareNav.openHomeCare.value = true
        }
        setContent {
            GetBloodTheme {
                // স্ট্যাটাস বার আর নিচের বার অ্যাপের থিমের (লাইট/ডার্ক) সাথে মিলিয়ে
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                LaunchedEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(style, style)
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        App(
                            openRequestId = openRequestId.value,
                            openAsRequester = openType.value == "response",
                            onRequestOpened = { openRequestId.value = null },
                            openQuestionId = openQuestionId.value,
                            onQuestionOpened = { openQuestionId.value = null },
                            openAppointments = openType.value == "appointment",
                            onAppointmentsOpened = { openType.value = null },
                            openHomeVisit = openType.value in HOME_CARE_TYPES,
                            onHomeVisitOpened = { openType.value = null },
                            openTarget = openType.value?.takeIf { it in setOf("pharmacy_order", "pharmacy_mine", "services", "support", "med_find", "med_find_mine", "emergency", "rx", "rx_pharmacy", "rx_lab", "rx_review", "care") },
                            openTargetKind = openKind.value,
                            openTargetExtra = openExtra.value,
                            onTargetOpened = { openType.value = null; openKind.value = null; openExtra.value = emptyMap() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_REQUEST_ID)?.let {
            openType.value = intent.getStringExtra(Notifier.EXTRA_TYPE)
            openRequestId.value = it
        }
        intent.getStringExtra(Notifier.EXTRA_QUESTION_ID)?.let { openQuestionId.value = it }
        sharedRequestId(intent)?.let {
            openType.value = null
            openRequestId.value = it
        }
        if (intent.getStringExtra(Notifier.EXTRA_TYPE) == "appointment") openType.value = "appointment"
        intent.getStringExtra(Notifier.EXTRA_TYPE)?.takeIf { it in HOME_CARE_TYPES }?.let { openType.value = it }
        intent.getStringExtra(Notifier.EXTRA_TYPE)?.takeIf { it in setOf("pharmacy_order", "pharmacy_mine", "services", "support", "med_find", "med_find_mine", "emergency", "rx", "rx_pharmacy", "rx_lab", "rx_review", "care") }?.let {
            openKind.value = intent.getStringExtra(Notifier.EXTRA_KIND)
            openExtra.value = rxExtras(intent)
            openType.value = it
        }
    }
}

/** নোটিফিকেশনের rxId আর asUid (যেগুলো আছে) */
private fun rxExtras(intent: Intent?): Map<String, String> = listOf(Notifier.EXTRA_RX_ID, Notifier.EXTRA_AS_UID)
    .mapNotNull { k -> intent?.getStringExtra(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

/** বাসায় সেবার নোটিফিকেশন (আগের হোম নার্সের "homevisit" সহ) */
private val HOME_CARE_TYPES = setOf("homevisit", "homecare")

/** শেয়ার করা লিংক থেকে রিকোয়েস্টের id: https://…/r/{id} বা getblood://r/{id} */
private fun sharedRequestId(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    val id = when (uri.scheme) {
        "https" -> uri.pathSegments.takeIf { it.size >= 2 && it[0] == "r" }?.get(1)
        "getblood" -> if (uri.host == "r") uri.pathSegments.firstOrNull() else null
        else -> null
    }
    return id?.takeIf { Regex("^[A-Za-z0-9_-]{1,64}$").matches(it) }
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object EditProfile : Screen
    data object PickLocation : Screen
    /** ট্যাব: 0 হোম, 1 অ্যাপয়েন্টমেন্ট, 2 স্বাস্থ্য, 3 সেবা, 4 প্রোফাইল */
    data class Main(val tab: Int = 0) : Screen
    data object NewRequest : Screen
    data class RequestDetail(val id: String) : Screen
    data class MyRequestDetail(val id: String) : Screen
    data object MyRequests : Screen
    data object Admin : Screen
    data object Settings : Screen
    data class QuestionDetail(val id: String) : Screen
    data object AskQuestion : Screen
    data class TipDetail(val tip: Tip) : Screen
    data object PostTip : Screen
    data object MyAppointments : Screen
    data object MyPrescriptions : Screen
    data object Care : Screen
    data class CareFor(val uid: String, val name: String) : Screen
    data object RxAssist : Screen
    data object RxReviews : Screen
    data object ChamberEdit : Screen
    /** নিজের এক ধরনের ব্যবসা: "ambulance" | "pharmacy" | "bloodbank" | "lab" */
    data class MyBusiness(val kind: String) : Screen
    data object Inbox : Screen
    /** tab: ০ অর্ডার দিন, ১ আমার অর্ডার, ২ ফার্মেসি */
    /** সাহায্য ও আপিল; category খালি হলে শুধু তালিকা */
    data class Help(val category: String? = null) : Screen
}

object Tabs {
    const val HOME = 0
    const val APPOINTMENT = 1
    /** আলোচনা + টিপস */
    const val HEALTH = 2
    /** "সেবা": ফার্মেসি (ওষুধ + নিজের দোকান), অ্যাম্বুলেন্স (খোঁজা + নিজেরটা) */
    const val SERVICES = 3
    /** "আমার কাজ": নিজের ব্যবসা, চেম্বার, বাসায় সেবা — নিচের বারে শুধু যাঁর এগুলো আছে (২.২০.০) */
    const val WORK = 4
    /** নিচের বারে নেই; উপরের প্রোফাইলের ছবিতে চাপলে */
    const val PROFILE = 5
}

@Composable
fun App(
    openRequestId: String?,
    openAsRequester: Boolean,
    onRequestOpened: () -> Unit,
    openQuestionId: String?,
    onQuestionOpened: () -> Unit,
    openAppointments: Boolean,
    onAppointmentsOpened: () -> Unit,
    openHomeVisit: Boolean,
    onHomeVisitOpened: () -> Unit,
    openTarget: String? = null,
    openTargetKind: String? = null,
    openTargetExtra: Map<String, String> = emptyMap(),
    onTargetOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Loading) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    /** নিজের পেশাদার ব্যাজ আর অ্যাডমিন কি না (Discussion/Tips-এর জন্য) */
    var community by remember { mutableStateOf<CommunityMe?>(null) }
    /** অন্য পাতা থেকে মূল পাতায় ফিরলে হোম নতুন করে লোড হয় */
    var homeRefresh by remember { mutableIntStateOf(0) }
    /** প্রশ্ন/টিপস পোস্ট বা মোছা হলে তালিকা নতুন করে লোড হয় */
    var communityRefresh by remember { mutableIntStateOf(0) }
    /** লগআউট/অ্যাকাউন্ট মোছা/বন্ধ হলে বাড়ে: মূল পাতা পুরো নতুন করে তৈরি, আগের মানুষের তথ্য স্ক্রিনে থাকে না */
    var session by remember { mutableIntStateOf(0) }

    fun resetTo(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    /** লগইনের ধাপ (নতুন নকশা N2): "login" → "basic" (নাম-মোবাইল) → "donor" (ডোনারের তথ্য); null = নেই */
    var gateStep by remember { mutableStateOf<String?>(null) }
    var gateError by remember { mutableStateOf<String?>(null) }

    /** প্রোফাইল বসানো, সাথে LoginGate-এর হিসাব (নাম-মোবাইল আছে কি না, ডোনার কি না) */
    fun setProfile(p: Profile?) {
        profile = p
        val loggedIn = AuthRepository.currentUid != null
        LoginGate.profileReady.value = loggedIn && p?.isComplete == true
        LoginGate.isDonor.value = loggedIn && p?.isDonor == true
    }

    // অ্যাকাউন্ট বন্ধ করা হয়েছে (সার্ভার জানাল): সাথে সাথে লগআউট; অতিথি হিসেবে চলে, লগইন পাতায় কারণ আর আপিলের ইমেইল
    val disabledMsg = SessionEvents.disabled.value
    LaunchedEffect(disabledMsg) {
        if (disabledMsg != null && AuthRepository.currentUid != null) {
            AuthRepository.signOutLocal()
            session++
            community = null
            MyWork.clear()
            LoginGate.uid.value = null
            setProfile(GuestArea.load(context))
            resetTo(if (profile?.hasLocation == true) Screen.Main() else Screen.PickLocation)
            gateError = disabledMsg
            LoginGate.login()
        }
        SessionEvents.disabled.value = null
    }

    // মোছার অপেক্ষায় থাকা অ্যাকাউন্টে আবার লগইন করলে সার্ভার মোছা বাতিল করে — জানানো
    val restored = SessionEvents.restored.value
    LaunchedEffect(restored) {
        if (restored) {
            android.widget.Toast.makeText(context, tr("Account deletion cancelled. Everything is back as before.", "অ্যাকাউন্ট মোছা বাতিল হয়েছে। সব আগের মতো আছে।"), android.widget.Toast.LENGTH_LONG).show()
            SessionEvents.restored.value = false
        }
    }

    // অ্যাপে ফিরলে প্রোফাইল নতুন করে (সার্ভার শেষ রক্তদানের তারিখ বা প্রসেসিং তালিকা বদলে থাকতে পারে)
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && profile != null) {
                val uid = AuthRepository.currentUid
                if (uid != null) scope.launch {
                    runCatching { ProfileRepository.load(uid) }.getOrNull()?.let { if (it.isComplete) setProfile(it) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun push(s: Screen) {
        stack.add(s)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (stack.size == 1) homeRefresh++
    }

    /**
     * কেয়ারগিভারের নোটিফিকেশন: রোগীর কেয়ারগিভার মোড খুলে সেই প্রেসক্রিপশন (মোডের "প্রেসক্রিপশন" তালিকা এলে খোলে)।
     * এখন আর কেয়ারগিভার না হলে "কেয়ারগিভার ও পরিবার" পাতা, সাথে ছোট বার্তা।
     */
    fun openCareRx(asUid: String, rxId: String) {
        val top = stack.last()
        if (top is Screen.CareFor && top.uid == asUid) {
            RxNav.rxId.value = rxId
            return
        }
        scope.launch {
            val link = runCatching { CareApi.list() }.getOrNull()?.caringFor?.firstOrNull { it.patientUid == asUid && it.status == "active" }
            if (link != null) {
                RxNav.rxId.value = rxId
                push(Screen.CareFor(asUid, link.patientName))
            } else {
                RxNav.notice.value = tr("You can't open this prescription now — you're no longer their caregiver", "এই প্রেসক্রিপশন এখন খোলা যাচ্ছে না — আপনি আর তাঁর কেয়ারগিভার নন")
                push(Screen.Care)
            }
        }
    }

    /** নোটিফিকেশনের data অনুযায়ী পাতা খোলা (নোটিফিকেশনের তালিকা আর পুশ — দুই জায়গা থেকেই) */
    fun openFromData(data: Map<String, String>) {
        val type = data["type"]
        val requestId = data["requestId"]
        val questionId = data["questionId"]
        when {
            requestId != null -> push(if (type == "response") Screen.MyRequestDetail(requestId) else Screen.RequestDetail(requestId))
            questionId != null -> push(Screen.QuestionDetail(questionId))
            // চেম্বারে আসা বুকিং → "আমার চেম্বার"; নিজের নেওয়া অ্যাপয়েন্টমেন্ট → "আমার অ্যাপয়েন্টমেন্ট"
            type == "appointment" -> push(
                if (data["role"] == "provider" || (data["role"] == null && community?.approvedBadge in CHAMBER_TYPES)) Screen.ChamberEdit
                else Screen.MyAppointments,
            )
            type in HOME_CARE_TYPES -> {
                CareNav.openHomeCare.value = true
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.APPOINTMENT)
            }
            type == "support" -> push(Screen.Help())
            // প্রেসক্রিপশন (নতুন, দোকান/প্যাথলজির খবর, রিপোর্ট, আবার দেখানোর দিন): সরাসরি সেই প্রেসক্রিপশন।
            // rxId না থাকলে (ওষুধের অ্যালার্ম) তালিকা; পুরোনো নোটিফিকেশনে শুধু id (সেটাও প্রেসক্রিপশনের id)
            type == "rx" -> {
                val id = data["rxId"] ?: data["id"]
                if (id != null) RxNav.openRx(id, own = true) else push(Screen.MyPrescriptions)
            }
            type == "rx_review" -> push(Screen.RxReviews)
            // কেয়ারগিভারকে রোগীর প্রেসক্রিপশনের খবর: রোগীর হয়ে (কেয়ারগিভার মোডে) সেই প্রেসক্রিপশন
            type == "care" && data["asUid"] != null && data["rxId"] != null -> openCareRx(data["asUid"]!!, data["rxId"]!!)
            // কেয়ারগিভারকে: যাঁর দেখাশোনা করেন তাঁর অন্য খবর / নতুন আমন্ত্রণ
            type == "care" -> push(Screen.Care)
            // ফার্মেসি/প্যাথলজির মালিক (প্রেসক্রিপশন, পরীক্ষার অনুরোধ), দোকানের কাছে ওষুধ খোঁজা: নিজের পাতা ("আমার কাজ")
            type == "rx_pharmacy" || type == "rx_lab" || type == "med_find" -> push(Screen.MyBusiness(if (type == "rx_lab") "lab" else "pharmacy"))
            // নিজের ওষুধ খোঁজার উত্তর: সেবা › ফার্মেসি › "ওষুধ খুঁজুন"
            type == "med_find_mine" -> {
                ServicesNav.target.value = type
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.SERVICES)
            }
            // জরুরি আহ্বান: হোমের উপরের ব্যানার
            type == "emergency" -> {
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.HOME)
                homeRefresh++
            }
            // দোকানের মালিকের খবর: সরাসরি নিজের ফার্মেসির পাতা
            type == "pharmacy_order" || type == "pharmacy_mine" -> push(Screen.MyBusiness("pharmacy"))
            // অনুমোদন/বাতিল/বন্ধের খবর (kind: কোন ব্যবসা) — নিজের সেই পাতা; kind না থাকলে "আমার কাজ"
            type == "services" -> {
                val kind = data["kind"]
                if (kind in BUSINESS_KINDS) push(Screen.MyBusiness(kind!!))
                else {
                    while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    stack[0] = Screen.Main(if (MyWork.has) Tabs.WORK else Tabs.HOME)
                }
            }
            else -> {
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.HOME)
            }
        }
    }

    suspend fun route() {
        val uid = AuthRepository.currentUid
        LoginGate.uid.value = uid
        if (uid == null) {
            // লগইন ছাড়া (অতিথি, নতুন নকশা N1): এলাকা ফোনে রাখা থাকলে সরাসরি হোম, নইলে আগে এলাকা
            community = null
            MyWork.clear()
            setProfile(GuestArea.load(context))
            resetTo(if (profile?.hasLocation == true) Screen.Main() else Screen.PickLocation)
            return
        }
        // নাম-মোবাইল না থাকলেও (লগইনের পর ফিরে গেলে) দেখা চলে — যে কাজে লাগে সেখানে চাওয়া হয় (নতুন নকশা N2)
        val p = ProfileRepository.load(uid) ?: Profile()
        setProfile(p)
        resetTo(if (p.hasLocation) Screen.Main() else Screen.PickLocation)
    }

    LaunchedEffect(Unit) {
        // লোডিং পাতা (অ্যাপের নাম) অন্তত একটু সময় দেখা যাবে
        runCatching {
            coroutineScope {
                val minSplash = async { delay(900) }
                route()
                minSplash.await()
            }
        }.onFailure {
            errorMsg = tr("Check your internet connection and try again", "ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন")
            resetTo(Screen.Login)
        }
    }

    // নোটিফিকেশন থেকে এলে, মূল স্ক্রিন তৈরি হওয়ার পর রিকোয়েস্টটা খোলা
    val ready = stack.firstOrNull() is Screen.Main
    // ব্যাজের অবস্থা (অ্যাডমিন অনুমোদন দিলে) পাতা বা ট্যাব বদলালেই নতুন করে আনা হয়
    LaunchedEffect(ready, stack.last(), LoginGate.uid.value) {
        if (ready && !LoginGate.isGuest) runCatching { Community.me() }.onSuccess { community = it }
        if (LoginGate.isGuest) community = null
    }
    // "আমার কাজ" (নিজের ব্যবসা, চেম্বার): মূল পাতায় ফিরলে, লগইন বদলালে বা ব্যাজ বদলালে নতুন করে
    val workBadge = community?.approvedBadge
    LaunchedEffect(ready, stack.size == 1, homeRefresh, LoginGate.uid.value, workBadge) {
        if (ready && stack.size == 1) MyWork.refresh(workBadge)
    }
    // শেষ ব্যবসাটাও মুছে গেলে (বা লগআউট) "আমার কাজ" ট্যাব থাকে না — হোমে ফেরা
    val workHas = MyWork.has
    LaunchedEffect(workHas, stack.firstOrNull()) {
        val b = stack.firstOrNull()
        if (!workHas && b is Screen.Main && b.tab == Tabs.WORK) stack[0] = Screen.Main(Tabs.HOME)
    }
    // প্যানেলে বদলানো তালিকা (বিশেষজ্ঞতা, সেবা…) — দিনে একবার
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(ready) {
        if (ready) runCatching { Catalog.refresh(appContext) }
    }
    LaunchedEffect(openAppointments, ready) {
        if (openAppointments && ready) {
            push(if (community?.approvedBadge in CHAMBER_TYPES) Screen.ChamberEdit else Screen.MyAppointments)
            onAppointmentsOpened()
        }
    }
    // প্রোফাইলের "আমার প্রেসক্রিপশন" / "ডাক্তারের সহকারী"
    val rxOpen = RxNav.open.value
    LaunchedEffect(rxOpen) {
        if (rxOpen != null) {
            val next = if (rxOpen == "assist") Screen.RxAssist else if (rxOpen == "care") Screen.Care else Screen.MyPrescriptions
            // "আমার প্রেসক্রিপশন" খোলাই থাকলে আবার নয় — সেই পাতাই প্রেসক্রিপশনটা খোলে
            if (stack.last() != next) push(next)
            RxNav.open.value = null
        }
    }
    // অন্য পাতার "আপিল করুন" / প্রোফাইলের "সাহায্য ও আপিল"
    val helpOpen = HelpNav.open.value
    LaunchedEffect(helpOpen) {
        if (helpOpen != null) {
            push(Screen.Help(helpOpen.ifBlank { null }))
            HelpNav.open.value = null
        }
    }
    // ফার্মেসি আর "সেবা"-র নোটিফিকেশন
    LaunchedEffect(openTarget, ready) {
        if (openTarget != null && ready) {
            openFromData(listOfNotNull("type" to openTarget, openTargetKind?.let { "kind" to it }).toMap() + openTargetExtra)
            onTargetOpened()
        }
    }
    // বাসায় সেবার নোটিফিকেশন (আগের "homevisit"-ও): অ্যাপয়েন্টমেন্ট ট্যাবের "বাসায় সেবা" অংশ
    LaunchedEffect(openHomeVisit, ready) {
        if (openHomeVisit && ready) {
            CareNav.openHomeCare.value = true
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack[0] = Screen.Main(Tabs.APPOINTMENT)
            onHomeVisitOpened()
        }
    }
    LaunchedEffect(openQuestionId, ready) {
        if (openQuestionId != null && ready) {
            push(Screen.QuestionDetail(openQuestionId))
            onQuestionOpened()
        }
    }
    LaunchedEffect(openRequestId, ready) {
        if (openRequestId != null && ready) {
            val target = if (openAsRequester) Screen.MyRequestDetail(openRequestId) else Screen.RequestDetail(openRequestId)
            // শেয়ার করা লিংক লগইন ছাড়া খুললে: আগে লগইন (রিকোয়েস্টের বিস্তারিত লগইন করা মানুষই দেখেন)
            if (LoginGate.isGuest) LoginGate.require(tr("Log in to see and respond to this request", "রিকোয়েস্টটা দেখতে ও সাড়া দিতে লগইন করুন")) { push(target) }
            else push(target)
            onRequestOpened()
        }
    }

    // যে কাজে অ্যাকাউন্ট লাগে (LoginGate.require): লগইন → নাম-মোবাইল → (দরকারে) ডোনারের তথ্য → কাজটা চালানো
    fun nextGateStep() {
        val pd = LoginGate.pending.value
        if (pd == null) {
            gateStep = null
            return
        }
        gateStep = when {
            LoginGate.isGuest -> "login"
            !LoginGate.profileReady.value -> "basic"
            pd.donor && !LoginGate.isDonor.value -> "donor"
            else -> null
        }
        if (gateStep == null) {
            LoginGate.pending.value = null
            gateError = null
            pd.then()
        }
    }

    fun cancelGate() {
        LoginGate.pending.value = null
        gateStep = null
        gateError = null
    }

    val gatePending = LoginGate.pending.value
    LaunchedEffect(gatePending) {
        if (gatePending != null) nextGateStep()
    }

    /**
     * প্রোফাইল সেভ (প্রোফাইল এডিট, নাম-মোবাইল, ডোনারের তথ্য — তিন জায়গা থেকেই); সেভ হলে after।
     * নাম-মোবাইলের ধাপে শুধু নাম-মোবাইল, ডোনারের ধাপে শুধু ডোনারের তথ্য লেখা হয় — অন্য তথ্য (আগের ডোনারের তথ্য,
     * সেটিংস) যাতে পুরোনো বা ফাঁকা মানে মুছে না যায়।
     */
    fun saveProfile(basics: Profile, mode: ProfileMode, after: (Profile) -> Unit) {
        val uid = AuthRepository.currentUid ?: return
        scope.launch {
            busy = true
            errorMsg = null
            // সার্ভার থেকে নতুন প্রোফাইল (রক্ত দেওয়ার পর সার্ভার তারিখ বসিয়ে থাকতে পারে)
            val fresh = runCatching { ProfileRepository.load(uid) }.getOrNull() ?: profile
            val firstDate = fresh?.lastDonationMillis == null
            runCatching {
                when (mode) {
                    ProfileMode.BASIC -> ProfileRepository.saveContact(uid, basics.name, basics.phone)
                    ProfileMode.DONOR -> ProfileRepository.saveDonor(uid, basics, includeDonation = firstDate)
                    ProfileMode.FULL -> ProfileRepository.saveBasics(uid, basics, includeDonation = firstDate)
                }
            }
                .onSuccess {
                    val base = fresh ?: Profile()
                    val merged = when (mode) {
                        ProfileMode.BASIC -> base.copy(name = basics.name, phone = basics.phone)
                        else -> base.copy(
                            name = if (mode == ProfileMode.FULL) basics.name else base.name,
                            phone = if (mode == ProfileMode.FULL) basics.phone else base.phone,
                            bloodGroup = basics.bloodGroup,
                            lastDonationMillis = if (firstDate) basics.lastDonationMillis else base.lastDonationMillis,
                            birthDateMillis = basics.birthDateMillis,
                            weightKg = basics.weightKg,
                            notify = if (mode == ProfileMode.FULL) basics.notify else base.notify,
                        )
                    }
                    setProfile(merged)
                    after(merged)
                }
                .onFailure { errorMsg = tr("Couldn't save", "সেভ হয়নি") + ": ${it.message}" }
            busy = false
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }

    // মূল পাতায় হোম ছাড়া অন্য ট্যাবে থাকলে "পেছনে" চাপলে হোমে ফেরা
    val top = stack.last()
    BackHandler(enabled = stack.size == 1 && top is Screen.Main && top.tab != Tabs.HOME) {
        stack[0] = Screen.Main(Tabs.HOME)
    }

    // মূল পাতা (নিচের ট্যাবগুলো) সবসময় নিচে থাকে; অন্য পাতা তার উপরে খোলে,
    // যাতে ফিরে এলে তালিকা, স্ক্রল আর "আরও দেখুন"-এর পাতা হারিয়ে না যায়
    val base = stack.first()
    val p = profile
    Box(Modifier.fillMaxSize()) {
        if (base is Screen.Main && p != null) androidx.compose.runtime.key(session) {
            MainScreen(
                profile = p,
                tab = base.tab,
                onTabChange = { stack[0] = Screen.Main(it) },
                onOpenRequest = { push(Screen.RequestDetail(it)) },
                onOpenMyRequest = { push(Screen.MyRequestDetail(it)) },
                onOpenMyRequests = { push(Screen.MyRequests) },
                onNewRequest = { LoginGate.require(tr("Log in to request blood", "রক্তের রিকোয়েস্ট দিতে লগইন করুন")) { push(Screen.NewRequest) } },
                onEditProfile = { push(Screen.EditProfile) },
                onChangeLocation = { push(Screen.PickLocation) },
                onProfileChanged = { setProfile(it) },
                onOpenAdmin = { push(Screen.Admin) },
                onOpenSettings = { push(Screen.Settings) },
                community = community,
                homeRefresh = homeRefresh,
                communityRefresh = communityRefresh,
                onCommunityChanged = { community = it },
                onOpenQuestion = { push(Screen.QuestionDetail(it)) },
                onAsk = { LoginGate.require(tr("Log in to ask a question", "প্রশ্ন করতে লগইন করুন")) { push(Screen.AskQuestion) } },
                onOpenTip = { push(Screen.TipDetail(it)) },
                onPostTip = { push(Screen.PostTip) },
                onOpenMyAppointments = { push(Screen.MyAppointments) },
                onEditChamber = { push(Screen.ChamberEdit) },
                onOpenBusiness = { push(Screen.MyBusiness(it)) },
                onOpenInbox = { push(Screen.Inbox) },
                // লগআউট বা অ্যাকাউন্ট মোছার পর লগইন ছাড়া (অতিথি) হিসেবে চলে
                onLogout = {
                    scope.launch {
                        AuthRepository.signOut(context)
                        errorMsg = null
                        session++
                        runCatching { route() }
                    }
                },
                onAccountDeleted = {
                    scope.launch {
                        // ডাটাবেসে কিছু না লিখে শুধু এই ফোন থেকে বের হওয়া (নইলে খালি প্রোফাইল আবার তৈরি হতো)
                        AuthRepository.signOutLocal()
                        errorMsg = null
                        session++
                        runCatching { route() }
                        android.widget.Toast.makeText(context, tr("Your account will be deleted in 30 days. Log in again before then to cancel.", "৩০ দিন পর আপনার অ্যাকাউন্ট মুছে যাবে। এর মধ্যে আবার লগইন করলে মোছা বাতিল হবে।"), android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onLogin = { LoginGate.login() },
                onOpenRequestGuest = { id ->
                    LoginGate.require(tr("Log in to respond to this request", "সাড়া দিতে লগইন করুন"), donor = true) { push(Screen.RequestDetail(id)) }
                },
                onBecomeDonor = {
                    LoginGate.require(tr("Log in to become a blood donor", "রক্তদাতা হতে লগইন করুন"), donor = true) {}
                },
            )
        }
        if (!(top is Screen.Main && p != null)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // পাতা বদলালে (একই ধরনের অন্য রিকোয়েস্ট/প্রশ্নও) নতুন করে তৈরি — আগের পাতার তথ্য থেকে যায় না
                val current = stack.last()
                androidx.compose.runtime.key(current) {
                when (current) {
            Screen.Loading -> SplashScreen()

            Screen.Login -> LoginScreen(
                errorMsg = errorMsg,
                onLoggedIn = {
                    scope.launch {
                        errorMsg = null
                        runCatching { route() }
                            .onFailure { errorMsg = tr("Couldn't load. Try again.", "লোড হয়নি, আবার চেষ্টা করুন") }
                    }
                },
            )

            Screen.EditProfile -> ProfileEditScreen(
                existing = profile,
                busy = busy,
                errorMsg = errorMsg,
                onSave = { basics ->
                    saveProfile(basics, if (profile?.isComplete == true) ProfileMode.FULL else ProfileMode.BASIC) { merged ->
                        when {
                            !merged.hasLocation -> resetTo(Screen.PickLocation)
                            stack.size > 1 -> back()
                            else -> resetTo(Screen.Main())
                        }
                    }
                },
                onCancel = if (stack.size > 1) {
                    { back() }
                } else {
                    null
                },
                // নাম-মোবাইল নেই: শুধু নাম-মোবাইল; প্রোফাইল থেকে এডিট: সব
                mode = if (profile?.isComplete == true) ProfileMode.FULL else ProfileMode.BASIC,
            )

            Screen.PickLocation -> LocationPicker(
                title = tr("Where do you live?", "আপনি কোন এলাকায় থাকেন?"),
                busy = busy,
                errorMsg = errorMsg,
                onPicked = { lat, lng, place, source ->
                    val uid = AuthRepository.currentUid
                    if (uid == null) {
                        // অতিথি: এলাকা শুধু এই ফোনে
                        GuestArea.save(context, lat, lng, place, source)
                        setProfile(GuestArea.load(context))
                        if (stack.size > 1) back() else resetTo(Screen.Main())
                    } else {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { ProfileRepository.saveLocation(uid, lat, lng, place, source) }
                                .onSuccess {
                                    setProfile((profile ?: Profile()).copy(
                                        lat = Geo.round(lat),
                                        lng = Geo.round(lng),
                                        placeName = place,
                                        locationSource = source,
                                    ))
                                    if (stack.size > 1) back() else resetTo(Screen.Main())
                                }
                                .onFailure { errorMsg = tr("Couldn't save", "সেভ হয়নি") + ": ${it.message}" }
                            busy = false
                        }
                    }
                },
                onCancel = if (profile?.hasLocation == true && stack.size > 1) {
                    { back() }
                } else {
                    null
                },
            )

            is Screen.Main -> SplashScreen()

            Screen.NewRequest -> {
                val p = profile
                if (p == null) {
                    LoadingBox()
                } else {
                    NewRequestScreen(
                        profile = p,
                        onCreated = { id ->
                            back()
                            push(Screen.MyRequestDetail(id))
                        },
                        onCancel = { back() },
                    )
                }
            }

            is Screen.RequestDetail -> {
                val p = profile
                if (p == null) {
                    LoadingBox()
                } else {
                    RequestDetailScreen(
                        requestId = current.id,
                        profile = p,
                        onProfileChanged = { setProfile(it) },
                        onOpenMine = { id -> stack[stack.lastIndex] = Screen.MyRequestDetail(id) },
                        onBack = { back() },
                    )
                }
            }

            Screen.MyRequests -> MyRequestsScreen(
                onOpenMyRequest = { push(Screen.MyRequestDetail(it)) },
                onNewRequest = { push(Screen.NewRequest) },
                onBack = { back() },
            )

            Screen.Admin -> AdminScreen(isOwner = community?.isOwner == true, onBack = { back() })

            Screen.Settings -> {
                val p = profile
                if (p == null) {
                    LoadingBox()
                } else {
                    SettingsScreen(
                        profile = p,
                        onProfileChanged = { setProfile(it) },
                        onChangeLocation = { push(Screen.PickLocation) },
                        onBack = { back() },
                    )
                }
            }

            is Screen.QuestionDetail -> QuestionDetailScreen(
                current.id,
                community,
                onChanged = { communityRefresh++ },
                onBack = { back() },
            )

            Screen.AskQuestion -> AskQuestionScreen(
                onPosted = { id ->
                    communityRefresh++
                    back()
                    push(Screen.QuestionDetail(id))
                },
                onBack = { back() },
            )

            is Screen.TipDetail -> TipDetailScreen(
                current.tip,
                community,
                onChanged = { communityRefresh++ },
                onBack = { back() },
            )

            Screen.PostTip -> PostTipScreen(
                onPosted = {
                    communityRefresh++
                    back()
                },
                onBack = { back() },
            )

            Screen.MyAppointments -> MyAppointmentsScreen(onBack = { back() })
            Screen.MyPrescriptions -> profile?.let { pr -> MyPrescriptionsScreen(pr, onBack = { back() }) }
            Screen.Care -> CareScreen(onBack = { back() }, onOpenFor = { uid, name -> push(Screen.CareFor(uid, name)) })
            is Screen.CareFor -> profile?.let { pr -> CareModeScreen(current.uid, current.name, pr, onBack = { back() }) }
            Screen.RxAssist -> RxAssistScreen(onBack = { back() })
            Screen.RxReviews -> RxReviewsScreen(onBack = { back() })

            Screen.Inbox -> InboxScreen(onBack = { back() }, onOpen = { data -> openFromData(data) })
            is Screen.Help -> HelpScreen(current.category, onBack = { back() })

            Screen.ChamberEdit -> profile?.let { pr ->
                ChamberHubScreen(type = community?.approvedBadge ?: "doctor", profile = pr, onBack = { back() })
            }

            is Screen.MyBusiness -> profile?.let { pr -> MyBusinessScreen(current.kind, pr, onBack = { back() }) }

            is Screen.MyRequestDetail -> MyRequestDetailScreen(
                requestId = current.id,
                onBack = { back() },
            )
        }
                }
            }
        }
        // "ব্যবসা যোগ করুন" (প্রোফাইল, অতিথির সেটিংস, "আমার কাজ"): কোন ব্যবসা → সেই পাতা (কিছু না থাকলে পাতাটাই ফর্ম)
        if (WorkNav.picker.value && !LoginGate.isGuest) {
            AddBusinessDialog(
                onPick = { k ->
                    WorkNav.picker.value = false
                    push(Screen.MyBusiness(k))
                },
                onDismiss = { WorkNav.picker.value = false },
            )
        }
        // লগইন/নাম-মোবাইল/ডোনারের তথ্য: নিচের পাতার উপরে (নিচের পাতা হারায় না, লগইনের পর সেখানেই ফেরা যায়)
        val step = gateStep
        val pd = LoginGate.pending.value
        if (step != null && pd != null) {
            // আলাদা পুরো-পর্দার ডায়ালগ: খোলা শিট বা ডায়ালগ (যেমন ডাক্তারের বিস্তারিত) থেকে চাইলেও সবার উপরে দেখায়
            FullScreenDialog(onDismissRequest = { cancelGate() }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.runtime.key(step) {
                    when (step) {
                        "login" -> LoginScreen(
                            errorMsg = gateError,
                            reason = pd.reason.ifBlank { null },
                            onCancel = { cancelGate() },
                            onLoggedIn = {
                                scope.launch {
                                    gateError = null
                                    val uid = AuthRepository.currentUid ?: return@launch
                                    LoginGate.uid.value = uid
                                    runCatching { ProfileRepository.load(uid) }
                                        .onSuccess { loaded ->
                                            var p2 = loaded ?: Profile()
                                            // অতিথি থাকার সময়ের এলাকা: প্রোফাইলে এলাকা না থাকলে সেটাই বসানো (আবার জিজ্ঞেস নয়)
                                            val g = GuestArea.load(context)
                                            if (!p2.hasLocation && g.lat != null && g.lng != null) {
                                                runCatching { ProfileRepository.saveLocation(uid, g.lat, g.lng, g.placeName ?: "", g.locationSource ?: "district") }
                                                    .onSuccess { p2 = p2.copy(lat = g.lat, lng = g.lng, placeName = g.placeName, locationSource = g.locationSource) }
                                            }
                                            setProfile(p2)
                                            // অতিথির পাতা এলাকা বাছাই ছিল (যেমন অ্যাকাউন্ট বন্ধের পর): অ্যাকাউন্টে এলাকা থাকলে সরাসরি হোম
                                            if (stack.first() !is Screen.Main && p2.hasLocation) resetTo(Screen.Main())
                                            homeRefresh++
                                            nextGateStep()
                                        }
                                        .onFailure {
                                            // প্রোফাইল আনা গেল না: অর্ধেক লগইনে না রেখে বের হওয়া (নইলে পুরোনো তথ্যে সেভ হয়ে আসল তথ্য মুছতে পারত)
                                            AuthRepository.signOutLocal()
                                            LoginGate.uid.value = null
                                            gateError = tr("Couldn't load your profile. Check your internet and log in again.", "প্রোফাইল আনা যায়নি। ইন্টারনেট দেখে আবার লগইন করুন।")
                                        }
                                }
                            },
                        )
                        "basic" -> ProfileEditScreen(
                            existing = profile,
                            busy = busy,
                            errorMsg = errorMsg,
                            onSave = { basics -> saveProfile(basics, ProfileMode.BASIC) { nextGateStep() } },
                            onCancel = { cancelGate() },
                            mode = ProfileMode.BASIC,
                        )
                        else -> ProfileEditScreen(
                            existing = profile,
                            busy = busy,
                            errorMsg = errorMsg,
                            onSave = { basics -> saveProfile(basics, ProfileMode.DONOR) { nextGateStep() } },
                            onCancel = { cancelGate() },
                            mode = ProfileMode.DONOR,
                        )
                    }
                }
            }
            }
        }
    }
}
