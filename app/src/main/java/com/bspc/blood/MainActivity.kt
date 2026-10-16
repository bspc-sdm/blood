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
import androidx.work.WorkManager
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
        // জেলা-উপজেলা-ইউনিয়নের তালিকা আগেভাগে পড়ে রাখা
        Thread { runCatching { Areas.ensure(this) } }.start()
        Notifier.ensureChannel(this)
        // আগের ভার্সনের ৩০ মিনিট পরপর খোঁজার কাজ বন্ধ (এখন সার্ভার পুশ পাঠায়)
        WorkManager.getInstance(this).cancelUniqueWork("check_requests")
        // শুধু প্রথমবার খোলার সময় নোটিফিকেশনের রিকোয়েস্ট পড়া হয়
        if (savedInstanceState == null) {
            openRequestId.value = intent?.getStringExtra(Notifier.EXTRA_REQUEST_ID)
            openType.value = intent?.getStringExtra(Notifier.EXTRA_TYPE)
            openQuestionId.value = intent?.getStringExtra(Notifier.EXTRA_QUESTION_ID)
            intent?.getStringExtra(Notifier.EXTRA_JOB_ID)?.let { JobsNav.jobId.value = it } // FEATURE:jobs
            intent?.getStringExtra(Notifier.EXTRA_KIND)?.let { ServicesNav.kind.value = it }
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
                            openTarget = openType.value?.takeIf { it in (setOf("pharmacy_order", "pharmacy_mine", "services", "support", "med_find", "med_find_mine", "emergency", "badge") + JobsNav.TYPES /* FEATURE:jobs */) },
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
        intent.getStringExtra(Notifier.EXTRA_JOB_ID)?.let { JobsNav.jobId.value = it } // FEATURE:jobs
        intent.getStringExtra(Notifier.EXTRA_KIND)?.let { ServicesNav.kind.value = it }
        sharedRequestId(intent)?.let {
            openType.value = null
            openRequestId.value = it
        }
        if (intent.getStringExtra(Notifier.EXTRA_TYPE) == "appointment") openType.value = "appointment"
        intent.getStringExtra(Notifier.EXTRA_TYPE)?.takeIf { it in HOME_CARE_TYPES }?.let { openType.value = it }
        intent.getStringExtra(Notifier.EXTRA_TYPE)?.takeIf { it in (setOf("pharmacy_order", "pharmacy_mine", "services", "support", "med_find", "med_find_mine", "emergency", "badge") + JobsNav.TYPES /* FEATURE:jobs */) }?.let { openType.value = it }
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
    /** ট্যাব: 0 হোম, 1 সার্ভিস, 2 নোটিফিকেশন, 3 প্রোফাইল */
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
    data object ChamberEdit : Screen
    data object Inbox : Screen
    data object Ambulance : Screen
    /** tab: ০ অর্ডার দিন, ১ আমার অর্ডার, ২ ফার্মেসি */
    /** সাহায্য ও আপিল; category খালি হলে শুধু তালিকা */
    data class Help(val category: String? = null) : Screen
    /** সার্ভিসের একটা সেবার পাতা (Services.kt) */
    data class Service(val key: String) : Screen
    /** উপরের ☰ মেনু (সেটিংস ইত্যাদি) */
    data object Menu : Screen
    /** সিভি বদলানো (প্রোফাইল থেকে) */
    data object EditCv : Screen
    /** চাকরির সব পাতা (Jobs.kt) */
    data class Jobs(val page: JobsPage) : Screen // FEATURE:jobs
}

/** নিচের বার: হোম · সার্ভিস · নোটিফিকেশন · প্রোফাইল (সেটিংস উপরের ☰ মেনুতে) */
object Tabs {
    const val HOME = 0
    /** সব সেবা টাইল আকারে (ডাক্তার, স্বাস্থ্য, অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, চাকরি…) */
    const val SERVICES = 1
    const val NOTIFICATIONS = 2
    /** প্রোফাইল = সিভি */
    const val PROFILE = 3
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

    // অ্যাকাউন্ট বন্ধ করা হয়েছে (সার্ভার জানাল): সাথে সাথে লগআউট, লগইন পাতায় কারণ আর আপিলের ইমেইল
    val disabledMsg = SessionEvents.disabled.value
    LaunchedEffect(disabledMsg) {
        if (disabledMsg != null && AuthRepository.currentUid != null) {
            AuthRepository.signOutLocal()
            profile = null
            community = null
            errorMsg = disabledMsg
            resetTo(Screen.Login)
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
                    runCatching { ProfileRepository.load(uid) }.getOrNull()?.let { if (it.isComplete) profile = it }
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
     * নিচের বারের "সার্ভিস"-এর ভেতরে একটা সেবার পাতা খোলা।
     * reset = পুশ নোটিফিকেশন থেকে (আগের পাতা মুছে "সার্ভিস" ট্যাবের উপরে); অ্যাপের ভেতর থেকে (হোমের "চলমান",
     * নোটিফিকেশন ট্যাব) খুললে শুধু উপরে বসে, ফিরে গেলে যেখান থেকে এসেছিলেন সেখানেই।
     */
    fun openService(key: String, reset: Boolean = true) {
        if (reset) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack[0] = Screen.Main(Tabs.SERVICES)
        }
        push(Screen.Service(key))
    }

    /** নোটিফিকেশনের data অনুযায়ী পাতা খোলা (নোটিফিকেশনের তালিকা, পুশ আর হোমের "চলমান" — সব জায়গা থেকে) */
    fun openFromData(data: Map<String, String>, fromPush: Boolean = false) {
        val type = data["type"]
        val requestId = data["requestId"]
        val questionId = data["questionId"]
        when {
            requestId != null -> push(if (type == "response") Screen.MyRequestDetail(requestId) else Screen.RequestDetail(requestId))
            questionId != null -> push(Screen.QuestionDetail(questionId))
            type == "appointment" -> push(Screen.MyAppointments)
            type in HOME_CARE_TYPES -> {
                CareNav.openHomeCare.value = true
                openService("doctor", fromPush)
            }
            type == "support" -> push(Screen.Help())
            // FEATURE:jobs START
            type in JobsNav.TYPES -> {
                val (section, page) = JobsNav.route(type, data["jobId"])
                JobsNav.section.value = section
                openService("jobs", fromPush)
                page?.let { push(Screen.Jobs(it)) }
            }
            // FEATURE:jobs END
            type == "med_find" || type == "med_find_mine" -> {
                ServicesNav.target.value = type
                openService("pharmacy", fromPush)
            }
            // জরুরি আহ্বান: হোমের উপরের ব্যানার
            type == "emergency" -> {
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.HOME)
                homeRefresh++
            }
            // মালিকের দিকের খবর: সেই সেবার "আমার …" অংশ
            type == "pharmacy_order" || type == "pharmacy_mine" -> {
                ServicesNav.target.value = "mine"
                openService("pharmacy", fromPush)
            }
            type == "services" -> {
                ServicesNav.target.value = "mine"
                openService(Services.keyForKind(data["kind"]?.ifBlank { null } ?: ServicesNav.kind.value.also { ServicesNav.kind.value = null }), fromPush)
            }
            // পেশাদার ব্যাজ: প্রোফাইলে
            type == "badge" -> {
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.PROFILE)
            }
            else -> {
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Main(Tabs.HOME)
            }
        }
    }

    suspend fun route() {
        val uid = AuthRepository.currentUid
        if (uid == null) {
            resetTo(Screen.Login)
            return
        }
        val p = ProfileRepository.load(uid)
        profile = p
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
    LaunchedEffect(ready, stack.last()) {
        if (ready) runCatching { Community.me() }.onSuccess { community = it }
    }
    LaunchedEffect(openAppointments, ready) {
        if (openAppointments && ready) {
            push(Screen.MyAppointments)
            onAppointmentsOpened()
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
            openFromData(mapOf("type" to openTarget), fromPush = true)
            onTargetOpened()
        }
    }
    // বাসায় সেবার নোটিফিকেশন (আগের "homevisit"-ও): অ্যাপয়েন্টমেন্ট ট্যাবের "বাসায় সেবা" অংশ
    LaunchedEffect(openHomeVisit, ready) {
        if (openHomeVisit && ready) {
            CareNav.openHomeCare.value = true
            openService("doctor")
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
            push(
                if (openAsRequester) Screen.MyRequestDetail(openRequestId)
                else Screen.RequestDetail(openRequestId)
            )
            onRequestOpened()
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
                onNewRequest = { push(Screen.NewRequest) },
                onEditProfile = { push(Screen.EditProfile) },
                onChangeLocation = { push(Screen.PickLocation) },
                onProfileChanged = { profile = it },
                onOpenAdmin = { push(Screen.Admin) },
                onOpenSettings = { push(Screen.Settings) },
                community = community,
                homeRefresh = homeRefresh,
                communityRefresh = communityRefresh,
                onCommunityChanged = { community = it },
                onOpenQuestion = { push(Screen.QuestionDetail(it)) },
                onAsk = { push(Screen.AskQuestion) },
                onOpenTip = { push(Screen.TipDetail(it)) },
                onPostTip = { push(Screen.PostTip) },
                onOpenMyAppointments = { push(Screen.MyAppointments) },
                onEditChamber = { push(Screen.ChamberEdit) },
                onOpenInbox = { push(Screen.Inbox) },
                onOpenAmbulance = { push(Screen.Ambulance) },
                onOpenJobs = { push(Screen.Jobs(it)) }, // FEATURE:jobs
                onOpenMenu = { push(Screen.Menu) },
                onOpenService = { push(Screen.Service(it)) },
                onOpenData = { openFromData(it) },
                onEditCv = { push(Screen.EditCv) },
                onLogout = {
                    scope.launch {
                        AuthRepository.signOut(context)
                        profile = null
                        community = null
                        errorMsg = null
                        resetTo(Screen.Login)
                    }
                },
                onAccountDeleted = {
                    scope.launch {
                        // ডাটাবেসে কিছু না লিখে শুধু এই ফোন থেকে বের হওয়া (নইলে খালি প্রোফাইল আবার তৈরি হতো)
                        AuthRepository.signOutLocal()
                        profile = null
                        community = null
                        errorMsg = tr("Your account has been deleted.", "আপনার অ্যাকাউন্ট মুছে ফেলা হয়েছে।")
                        resetTo(Screen.Login)
                    }
                },
            )
        }
        if (!(top is Screen.Main && p != null)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (val current = stack.last()) {
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
                    val uid = AuthRepository.currentUid ?: return@ProfileEditScreen
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
                                profile = merged
                                when {
                                    !merged.hasLocation -> resetTo(Screen.PickLocation)
                                    stack.size > 1 -> back()
                                    else -> resetTo(Screen.Main())
                                }
                            }
                            .onFailure { errorMsg = tr("Couldn't save", "সেভ হয়নি") + ": ${it.message}" }
                        busy = false
                    }
                },
                onCancel = if (profile?.isComplete == true && stack.size > 1) {
                    { back() }
                } else {
                    null
                },
            )

            Screen.PickLocation -> LocationPicker(
                title = tr("Where do you live?", "আপনি কোন এলাকায় থাকেন?"),
                busy = busy,
                errorMsg = errorMsg,
                onPicked = { lat, lng, place, source ->
                    val uid = AuthRepository.currentUid
                    if (uid != null) {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { ProfileRepository.saveLocation(uid, lat, lng, place, source) }
                                .onSuccess {
                                    profile = (profile ?: Profile()).copy(
                                        lat = Geo.round(lat),
                                        lng = Geo.round(lng),
                                        placeName = place,
                                        locationSource = source,
                                    )
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
                        onProfileChanged = { profile = it },
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
                        onProfileChanged = { profile = it },
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

            Screen.MyAppointments -> MyAppointmentsScreen(
                isProvider = community?.approvedBadge in listOf("doctor", "nurse"),
                onBack = { back() },
            )

            Screen.Inbox -> InboxScreen(onBack = { back() }, onOpen = { data -> openFromData(data) })

            Screen.Ambulance -> profile?.let { pr -> AmbulanceScreen(pr, onBack = { back() }) }
            is Screen.Help -> HelpScreen(current.category, onBack = { back() })
            is Screen.Service -> profile?.let { pr ->
                ServiceScreen(
                    current.key, pr, community, homeRefresh * 1000 + communityRefresh,
                    onBack = { back() },
                    onOpenMyAppointments = { push(Screen.MyAppointments) },
                    onEditChamber = { push(Screen.ChamberEdit) },
                    onOpenQuestion = { push(Screen.QuestionDetail(it)) },
                    onAsk = { push(Screen.AskQuestion) },
                    onOpenTip = { push(Screen.TipDetail(it)) },
                    onPostTip = { push(Screen.PostTip) },
                    onOpenJobs = { push(Screen.Jobs(it)) }, // FEATURE:jobs
                )
            }
            Screen.Menu -> MenuScreen(
                community = community,
                onBack = { back() },
                onOpenMyRequests = { push(Screen.MyRequests) },
                onOpenMyAppointments = { push(Screen.MyAppointments) },
                onOpenSettings = { push(Screen.Settings) },
                onEditProfile = { push(Screen.EditProfile) },
                onOpenAdmin = { push(Screen.Admin) },
                onLogout = {
                    scope.launch {
                        AuthRepository.signOut(context)
                        profile = null
                        community = null
                        errorMsg = null
                        resetTo(Screen.Login)
                    }
                },
                onAccountDeleted = {
                    scope.launch {
                        AuthRepository.signOutLocal()
                        profile = null
                        community = null
                        errorMsg = tr("Your account has been deleted.", "আপনার অ্যাকাউন্ট মুছে ফেলা হয়েছে।")
                        resetTo(Screen.Login)
                    }
                },
            )
            Screen.EditCv -> CvEditScreen(onBack = { back() }, onSaved = { back() })
            is Screen.Jobs -> profile?.let { pr -> // FEATURE:jobs
                JobsPageScreen(current.page, pr, open = { push(Screen.Jobs(it)) }, replace = { stack[stack.lastIndex] = Screen.Jobs(it) }, back = { back() })
            }

            Screen.ChamberEdit -> profile?.let { pr ->
                ChamberEditScreen(type = community?.approvedBadge ?: "doctor", profile = pr, onBack = { back() })
            }

            is Screen.MyRequestDetail -> MyRequestDetailScreen(
                requestId = current.id,
                onBack = { back() },
            )
        }
            }
        }
    }
}
