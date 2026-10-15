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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        Notifier.ensureChannel(this)
        // আগের ভার্সনের ৩০ মিনিট পরপর খোঁজার কাজ বন্ধ (এখন সার্ভার পুশ পাঠায়)
        WorkManager.getInstance(this).cancelUniqueWork("check_requests")
        // শুধু প্রথমবার খোলার সময় নোটিফিকেশনের রিকোয়েস্ট পড়া হয়
        if (savedInstanceState == null) {
            openRequestId.value = intent?.getStringExtra(Notifier.EXTRA_REQUEST_ID)
            openType.value = intent?.getStringExtra(Notifier.EXTRA_TYPE)
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
    }
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object EditProfile : Screen
    data object PickLocation : Screen
    /** নিচের ট্যাব: 0 হোম, 1 অ্যাপয়েন্টমেন্ট, 2 Health Discussion, 3 Health Tips, 4 প্রোফাইল */
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
}

object Tabs {
    const val HOME = 0
    const val APPOINTMENT = 1
    const val DISCUSSION = 2
    const val TIPS = 3
    const val PROFILE = 4
}

@Composable
fun App(openRequestId: String?, openAsRequester: Boolean, onRequestOpened: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Loading) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    /** নিজের পেশাদার ব্যাজ আর অ্যাডমিন কি না (Discussion/Tips-এর জন্য) */
    var community by remember { mutableStateOf<CommunityMe?>(null) }

    fun resetTo(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun push(s: Screen) {
        stack.add(s)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
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
        // লোডিং পাতা (Get Blood) অন্তত একটু সময় দেখা যাবে
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
    LaunchedEffect(ready, profile?.name) {
        if (ready) runCatching { Community.me() }.onSuccess { community = it }
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
                    runCatching { ProfileRepository.saveBasics(uid, basics) }
                        .onSuccess {
                            val merged = (profile ?: Profile()).copy(
                                name = basics.name,
                                phone = basics.phone,
                                bloodGroup = basics.bloodGroup,
                                lastDonationMillis = basics.lastDonationMillis,
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

        is Screen.Main -> {
            val p = profile
            if (p == null) {
                SplashScreen()
            } else {
                MainScreen(
                    profile = p,
                    tab = current.tab,
                    onTabChange = { stack[stack.lastIndex] = Screen.Main(it) },
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
                    onCommunityChanged = { community = it },
                    onOpenQuestion = { push(Screen.QuestionDetail(it)) },
                    onAsk = { push(Screen.AskQuestion) },
                    onOpenTip = { push(Screen.TipDetail(it)) },
                    onPostTip = { push(Screen.PostTip) },
                    onLogout = {
                        scope.launch {
                            AuthRepository.signOut(context)
                            profile = null
                            community = null
                            errorMsg = null
                            resetTo(Screen.Login)
                        }
                    },
                )
            }
        }

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

        Screen.Admin -> AdminScreen(onBack = { back() })

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

        is Screen.QuestionDetail -> QuestionDetailScreen(current.id, community, onBack = { back() })

        Screen.AskQuestion -> AskQuestionScreen(
            onPosted = { id ->
                back()
                push(Screen.QuestionDetail(id))
            },
            onBack = { back() },
        )

        is Screen.TipDetail -> TipDetailScreen(current.tip, community, onBack = { back() })

        Screen.PostTip -> PostTipScreen(onPosted = { back() }, onBack = { back() })

        is Screen.MyRequestDetail -> MyRequestDetailScreen(
            requestId = current.id,
            onBack = { back() },
        )
    }
}
