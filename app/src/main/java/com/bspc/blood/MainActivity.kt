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
    private val openQuestionId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        Catalog.init(this)
        // জেলা-উপজেলা-ইউনিয়নের তালিকা আগেভাগে পড়ে রাখা
        Thread { runCatching { Areas.ensure(this) } }.start()
        Notifier.ensureChannel(this)
        // শুধু প্রথমবার খোলার সময় নোটিফিকেশনের রিকোয়েস্ট পড়া হয়
        if (savedInstanceState == null) {
            openRequestId.value = intent?.getStringExtra(Notifier.EXTRA_REQUEST_ID)
            openType.value = intent?.getStringExtra(Notifier.EXTRA_TYPE)
            openQuestionId.value = intent?.getStringExtra(Notifier.EXTRA_QUESTION_ID)
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
                            onTargetOpened = { openType.value = null },
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
        intent.getStringExtra(Notifier.EXTRA_TYPE)?.takeIf { it in setOf("pharmacy_order", "pharmacy_mine", "services", "support", "med_find", "med_find_mine", "emergency", "rx", "rx_pharmacy", "rx_lab", "rx_review", "care") }?.let { openType.value = it }
    }
}

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
    /** নিচের বারে নেই; উপরের প্রোফাইলের ছবিতে চাপলে */
    const val PROFILE = 4
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
            community = null
            LoginGate.uid.value = null
            setProfile(GuestArea.load(context))
            resetTo(if (profile?.hasLocation == true) Screen.Main() else Screen.PickLocation)
            gateError = disabledMsg
            LoginGate.login()
        }
        SessionEvents.disabled.value = null
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
            // প্রেসক্রিপশন (নতুন, আবার দেখানোর দিন, ওষুধের অ্যালার্ম)
            type == "rx" -> push(Screen.MyPrescriptions)
            type == "rx_review" -> push(Screen.RxReviews)
            // কেয়ারগিভারকে: যাঁর দেখাশোনা করেন তাঁর খবর / নতুন আমন্ত্রণ
            type == "care" -> push(Screen.Care)
            // ফার্মেসি/প্যাথলজির মালিক: নিজের পাতা
            type == "rx_pharmacy" || type == "rx_lab" -> {
                ServicesNav.target.value = if (type == "rx_lab") "lab_mine" else "pharmacy_mine"
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.SERVICES)
            }
            // পুরোনো ওষুধের অর্ডারের নোটিফিকেশনও "সেবা" ট্যাবে
            type == "med_find" || type == "med_find_mine" -> {
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
            type == "services" || type == "pharmacy_order" || type == "pharmacy_mine" -> {
                // দোকানের মালিকের খবর: সরাসরি নিজের ফার্মেসির পাতা
                if (type != "services") ServicesNav.target.value = "pharmacy_mine"
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.SERVICES)
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
            setProfile(GuestArea.load(context))
            resetTo(if (profile?.hasLocation == true) Screen.Main() else Screen.PickLocation)
            return
        }
        val p = ProfileRepository.load(uid)
        setProfile(p)
        when {
            p == null || !p.isComplete -> resetTo(Screen.EditProfile)
            !p.hasLocation -> resetTo(Screen.PickLocation)
            else -> resetTo(Screen.Main())
        }
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
            push(if (rxOpen == "assist") Screen.RxAssist else if (rxOpen == "care") Screen.Care else Screen.MyPrescriptions)
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
            openFromData(mapOf("type" to openTarget))
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

    /** প্রোফাইল সেভ (প্রোফাইল এডিট, নাম-মোবাইল, ডোনারের তথ্য — তিন জায়গা থেকেই); সেভ হলে after */
    fun saveProfile(basics: Profile, after: (Profile) -> Unit) {
        val uid = AuthRepository.currentUid ?: return
        scope.launch {
            busy = true
            errorMsg = null
            // সার্ভার থেকে নতুন প্রোফাইল (রক্ত দেওয়ার পর সার্ভার তারিখ বসিয়ে থাকতে পারে)
            val fresh = runCatching { ProfileRepository.load(uid) }.getOrNull() ?: profile
            val firstDate = fresh?.lastDonationMillis == null
            runCatching { ProfileRepository.saveBasics(uid, basics, includeDonation = firstDate) }
                .onSuccess {
                    val merged = (fresh ?: Profile()).copy(
                        name = basics.name,
                        phone = basics.phone,
                        bloodGroup = basics.bloodGroup,
                        lastDonationMillis = if (firstDate) basics.lastDonationMillis else fresh?.lastDonationMillis,
                        birthDateMillis = basics.birthDateMillis,
                        weightKg = basics.weightKg,
                        notify = basics.notify,
                    )
                    setProfile(merged)
                    after(merged)
                }
                .onFailure { errorMsg = tr("Couldn't save", "সেভ হয়নি") + ": ${it.message}" }
            busy = false
        }
    }

    /** হোমের চারটা বাটন: সংশ্লিষ্ট ট্যাবের তালিকা */
    fun openQuick(key: String) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        when (key) {
            "doctor" -> {
                CareNav.openDoctors.value = true
                stack[0] = Screen.Main(Tabs.APPOINTMENT)
            }
            else -> {
                ServicesNav.target.value = when (key) {
                    "medicine" -> "med_search"
                    "bloodbank" -> "bloodbank"
                    else -> "ambulance"
                }
                stack[0] = Screen.Main(Tabs.SERVICES)
            }
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
        if (base is Screen.Main && p != null) {
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
                onOpenInbox = { push(Screen.Inbox) },
                // লগআউট বা অ্যাকাউন্ট মোছার পর লগইন ছাড়া (অতিথি) হিসেবে চলে
                onLogout = {
                    scope.launch {
                        AuthRepository.signOut(context)
                        errorMsg = null
                        runCatching { route() }
                    }
                },
                onAccountDeleted = {
                    scope.launch {
                        // ডাটাবেসে কিছু না লিখে শুধু এই ফোন থেকে বের হওয়া (নইলে খালি প্রোফাইল আবার তৈরি হতো)
                        AuthRepository.signOutLocal()
                        errorMsg = null
                        runCatching { route() }
                        android.widget.Toast.makeText(context, tr("Your account has been deleted.", "আপনার অ্যাকাউন্ট মুছে ফেলা হয়েছে।"), android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onLogin = { LoginGate.login() },
                onQuick = { openQuick(it) },
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
                    saveProfile(basics) { merged ->
                        when {
                            !merged.hasLocation -> resetTo(Screen.PickLocation)
                            stack.size > 1 -> back()
                            else -> resetTo(Screen.Main())
                        }
                    }
                },
                onCancel = if (profile?.isComplete == true && stack.size > 1) {
                    { back() }
                } else {
                    null
                },
                // প্রথমবার (নাম-মোবাইল নেই): শুধু নাম-মোবাইল; প্রোফাইল থেকে এডিট: সব
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

            is Screen.MyRequestDetail -> MyRequestDetailScreen(
                requestId = current.id,
                onBack = { back() },
            )
        }
                }
            }
        }
        // লগইন/নাম-মোবাইল/ডোনারের তথ্য: নিচের পাতার উপরে (নিচের পাতা হারায় না, লগইনের পর সেখানেই ফেরা যায়)
        val step = gateStep
        val pd = LoginGate.pending.value
        if (step != null && pd != null) {
            // আলাদা পুরো-পর্দার ডায়ালগ: খোলা শিট বা ডায়ালগ (যেমন ডাক্তারের বিস্তারিত) থেকে চাইলেও সবার উপরে দেখায়
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { cancelGate() },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
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
                                            homeRefresh++
                                            nextGateStep()
                                        }
                                        .onFailure { gateError = tr("Couldn't load. Try again.", "লোড হয়নি, আবার চেষ্টা করুন") }
                                }
                            },
                        )
                        "basic" -> ProfileEditScreen(
                            existing = profile,
                            busy = busy,
                            errorMsg = errorMsg,
                            onSave = { basics -> saveProfile(basics) { nextGateStep() } },
                            onCancel = { cancelGate() },
                            mode = ProfileMode.BASIC,
                        )
                        else -> ProfileEditScreen(
                            existing = profile,
                            busy = busy,
                            errorMsg = errorMsg,
                            onSave = { basics -> saveProfile(basics) { nextGateStep() } },
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
