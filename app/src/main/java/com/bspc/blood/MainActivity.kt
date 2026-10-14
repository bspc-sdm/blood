package com.bspc.blood

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

val BloodRed = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BloodRed)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        App()
                    }
                }
            }
        }
    }
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data class EditProfile(val existing: Profile?) : Screen
    data class Home(val profile: Profile) : Screen
}

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun route() {
        val uid = AuthRepository.currentUid
        screen = if (uid == null) {
            Screen.Login
        } else {
            val profile = ProfileRepository.load(uid)
            if (profile == null) Screen.EditProfile(null) else Screen.Home(profile)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { route() }.onFailure {
            error = "ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন (${it.message})"
            screen = Screen.Login
        }
    }

    when (val s = screen) {
        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        Screen.Login -> LoginScreen(
            busy = busy,
            error = error,
            onLogin = {
                scope.launch {
                    busy = true
                    error = null
                    runCatching {
                        AuthRepository.signInWithGoogle(context)
                        route()
                    }.onFailure { error = "লগইন হয়নি: ${it.message}" }
                    busy = false
                }
            },
        )

        is Screen.EditProfile -> ProfileScreen(
            existing = s.existing,
            googleName = AuthRepository.currentDisplayName,
            busy = busy,
            error = error,
            onSave = { profile ->
                val uid = AuthRepository.currentUid ?: return@ProfileScreen
                scope.launch {
                    busy = true
                    error = null
                    runCatching {
                        ProfileRepository.save(uid, profile)
                        screen = Screen.Home(profile)
                    }.onFailure { error = "সেভ হয়নি: ${it.message}" }
                    busy = false
                }
            },
            onCancel = if (s.existing != null) {
                { screen = Screen.Home(s.existing) }
            } else null,
        )

        is Screen.Home -> HomeScreen(
            profile = s.profile,
            onEdit = { screen = Screen.EditProfile(s.profile) },
            onLogout = {
                scope.launch {
                    AuthRepository.signOut(context)
                    error = null
                    screen = Screen.Login
                }
            },
        )
    }
}
