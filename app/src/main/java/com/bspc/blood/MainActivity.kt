package com.bspc.blood

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

val BloodRed = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    /** নোটিফিকেশনে চাপ দিলে কোন রিকোয়েস্ট খুলতে হবে */
    private val openRequestId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        CheckWorker.schedule(this)
        openRequestId.value = intent?.getStringExtra(Notifier.EXTRA_REQUEST_ID)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BloodRed)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        App(
                            openRequestId = openRequestId.value,
                            onRequestOpened = { openRequestId.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_REQUEST_ID)?.let { openRequestId.value = it }
    }
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object EditProfile : Screen
    data object PickLocation : Screen
    data class Main(val tab: Int = 0) : Screen
    data object NewRequest : Screen
    data class RequestDetail(val id: String) : Screen
    data class MyRequestDetail(val id: String) : Screen
}

@Composable
fun App(openRequestId: String?, onRequestOpened: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Loading) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

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
            p == null -> resetTo(Screen.EditProfile)
            !p.hasLocation -> resetTo(Screen.PickLocation)
            else -> resetTo(Screen.Main())
        }
    }

    LaunchedEffect(Unit) {
        runCatching { route() }.onFailure {
            errorMsg = "ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন (${it.message})"
            resetTo(Screen.Login)
        }
    }

    // নোটিফিকেশন থেকে এলে, মূল স্ক্রিন তৈরি হওয়ার পর রিকোয়েস্টটা খোলা
    val ready = stack.firstOrNull() is Screen.Main
    LaunchedEffect(openRequestId, ready) {
        if (openRequestId != null && ready) {
            push(Screen.RequestDetail(openRequestId))
            onRequestOpened()
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }

    when (val current = stack.last()) {
        Screen.Loading -> LoadingBox()

        Screen.Login -> LoginScreen(
            busy = busy,
            errorMsg = errorMsg,
            onLogin = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching {
                        AuthRepository.signInWithGoogle(context)
                        route()
                    }.onFailure { errorMsg = "লগইন হয়নি: ${it.message}" }
                    busy = false
                }
            },
        )

        Screen.EditProfile -> ProfileScreen(
            existing = profile,
            googleName = AuthRepository.currentDisplayName,
            busy = busy,
            errorMsg = errorMsg,
            onSave = { basics ->
                val uid = AuthRepository.currentUid ?: return@ProfileScreen
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
                                notify = basics.notify,
                            )
                            profile = merged
                            when {
                                !merged.hasLocation -> resetTo(Screen.PickLocation)
                                stack.size > 1 -> back()
                                else -> resetTo(Screen.Main())
                            }
                        }
                        .onFailure { errorMsg = "সেভ হয়নি: ${it.message}" }
                    busy = false
                }
            },
            onCancel = if (profile != null && stack.size > 1) {
                { back() }
            } else {
                null
            },
        )

        Screen.PickLocation -> LocationPicker(
            title = "আপনি কোন এলাকায় থাকেন?",
            subtitle = "এই এলাকার আশেপাশে কারো রক্ত লাগলে আপনাকে জানানো হবে।",
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
                            .onFailure { errorMsg = "সেভ হয়নি: ${it.message}" }
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
                LoadingBox()
            } else {
                MainScreen(
                    profile = p,
                    tab = current.tab,
                    onTabChange = { stack[stack.lastIndex] = Screen.Main(it) },
                    onOpenRequest = { push(Screen.RequestDetail(it)) },
                    onOpenMyRequest = { push(Screen.MyRequestDetail(it)) },
                    onNewRequest = { push(Screen.NewRequest) },
                    onEditProfile = { push(Screen.EditProfile) },
                    onChangeLocation = { push(Screen.PickLocation) },
                    onProfileChanged = { profile = it },
                    onLogout = {
                        scope.launch {
                            AuthRepository.signOut(context)
                            profile = null
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
                        stack[stack.lastIndex] = Screen.Main(1)
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

        is Screen.MyRequestDetail -> MyRequestDetailScreen(
            requestId = current.id,
            onBack = { back() },
        )
    }
}
