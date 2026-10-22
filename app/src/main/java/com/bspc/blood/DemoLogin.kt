// ===== ডেমো (শুধু পরীক্ষার জন্য) — ডেমো মুছলে এই ফাইল আর LoginScreen/Profile-এর "ডেমো" লাইনগুলো মুছবেন =====
// লগইন পাতায় রোলের তালিকা: চাপলেই সেই ডেমো অ্যাকাউন্টে ঢোকা (ইমেইল/কোড নেই)।
// সার্ভারে ডেমো চালু না থাকলে কিছুই দেখায় না।
package com.bspc.blood

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DemoLogin {
    data class Role(val key: String, val name: String, val role: String)

    fun isDemo(uid: String?) = uid?.startsWith("demo-") == true

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(ServerApi.BASE_URL + "/api/auth/verify-code").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!ok) throw IllegalStateException(json.optString("error").ifBlank { tr("Login failed", "লগইন হয়নি") })
            json
        } finally {
            conn.disconnect()
        }
    }

    suspend fun roles(): List<Role> {
        val r = post(JSONObject().put("demoRoles", true))
        if (!r.optBoolean("active")) return emptyList()
        val a = r.optJSONArray("roles") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { Role(it.optString("key"), it.optString("name"), it.optString("role")) } }
    }

    /** আগের অ্যাকাউন্ট থেকে বের হয়ে ডেমো অ্যাকাউন্টে ঢোকা */
    suspend fun login(context: android.content.Context, key: String) {
        val r = post(JSONObject().put("demo", key))
        val token = r.optString("token").ifBlank { throw IllegalStateException(tr("Login failed", "লগইন হয়নি")) }
        if (AuthRepository.currentUid != null) AuthRepository.signOut(context)
        FirebaseAuth.getInstance().signInWithCustomToken(token).await()
        if (r.optBoolean("restored")) SessionEvents.restored.value = true
        // সাময়িক বন্ধ ডেমো অ্যাকাউন্ট: লগইনেই "আবার চালু করুন" পর্দা
        AccountState.fromServer(r.optJSONObject("account"))
    }
}

/** লগইন পাতার নিচে "ডেমো" অংশ */
@Composable
fun DemoRoleList(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var roles by remember { mutableStateOf<List<DemoLogin.Role>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { roles = runCatching { DemoLogin.roles() }.getOrDefault(emptyList()) }
    if (roles.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(tr("Demo (for testing)", "ডেমো (পরীক্ষার জন্য)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        roles.forEach { r ->
            ListItem(
                headlineContent = { Text(r.role) },
                supportingContent = { Text(if (busy == r.key) tr("Logging in…", "ঢুকছে…") else r.name) },
                leadingContent = { AppIcon(R.drawable.ic_person_outline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = busy == null) {
                    scope.launch {
                        busy = r.key
                        error = null
                        runCatching { DemoLogin.login(context, r.key) }
                            .onSuccess { onLoggedIn() }
                            .onFailure { error = it.message }
                        busy = null
                    }
                },
            )
        }
        ErrorText(error)
    }
}

/** প্রোফাইলের "রোল বদলান" — বদলের পর অ্যাপ নতুন করে শুরু হয় নতুন অ্যাকাউন্টে */
@Composable
fun DemoSwitchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Switch role", "রোল বদলান")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DemoRoleList(onLoggedIn = { onDismiss(); (context as? Activity)?.recreate() })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } },
    )
}
