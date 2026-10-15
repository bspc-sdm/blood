package com.bspc.blood

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * নতুন ভার্সন এসেছে কি না: GitHub-এর "latest" রিলিজে APK-র পাশে version.json থাকে (CI লেখে)।
 * অ্যাপ Play Store-এ নেই, তাই এভাবেই ব্যবহারকারী জানতে পারেন।
 */
object Updates {
    const val APK_URL = "https://github.com/bspc-sdm/blood/releases/download/latest/roktodan.apk"
    private const val VERSION_URL = "https://github.com/bspc-sdm/blood/releases/download/latest/version.json"

    data class Latest(val code: Long, val name: String)

    private var cached: Latest? = null

    fun currentCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** সর্বশেষ ভার্সন (না পেলে null; ইন্টারনেট না থাকলে চুপচাপ) */
    suspend fun latest(): Latest? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    Latest(o.optLong("versionCode"), o.optString("versionName"))
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.also { cached = it }
        }
    }
}

/** হোমের উপরে: নতুন ভার্সন থাকলে ছোট কার্ড (এই চালুতে "পরে" চাপলে লুকায়) */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    var latest by remember { mutableStateOf<Updates.Latest?>(null) }
    var hidden by remember { mutableStateOf(UpdateState.dismissed) }
    LaunchedEffect(Unit) {
        val l = Updates.latest()
        if (l != null && l.code > Updates.currentCode(context)) latest = l
    }
    val l = latest ?: return
    if (hidden) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_sync, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("New version available", "নতুন ভার্সন এসেছে") + if (l.name.isNotBlank()) " (${num(l.name)})" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                tr(
                    "Download and install it over this one — your account and data stay.",
                    "ডাউনলোড করে এর উপরেই ইনস্টল করুন — অ্যাকাউন্ট আর তথ্য থেকে যাবে।",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, Updates.APK_URL) }) { Text(tr("Update", "আপডেট করুন")) }
                TextButton(onClick = {
                    UpdateState.dismissed = true
                    hidden = true
                }) { Text(tr("Later", "পরে")) }
            }
        }
    }
}

private object UpdateState {
    var dismissed = false
}

/** ফোন নম্বর যাচাই: SMS-এ কোড পাঠানো আর মেলানো */
object PhoneCheck {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    /** যে নম্বরে পাঠানো হলো (আংশিক লুকানো) */
    suspend fun sendCode(): String = call("phone_send_code").optString("to")

    suspend fun verify(code: String) {
        call("phone_verify_code", JSONObject().put("code", code))
    }
}

@Composable
fun PhoneVerifyDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sentTo by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun send() {
        scope.launch {
            busy = true
            error = null
            runCatching { PhoneCheck.sendCode() }.onSuccess { sentTo = it }.onFailure { error = it.message }
            busy = false
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Verify your phone", "ফোন নম্বর যাচাই")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr(
                        "A nurse will come to a home, so we check that the phone number in your profile is really yours.",
                        "নার্স বাসায় যাবেন, তাই প্রোফাইলের নম্বরটা সত্যিই আপনার কি না যাচাই করা হয়।",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (sentTo != null) {
                    Text(tr("Code sent to ", "কোড পাঠানো হয়েছে: ") + num(sentTo!!), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(tr("6-digit code", "৬ সংখ্যার কোড")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { send() }, enabled = !busy) { Text(tr("Send again", "আবার পাঠান")) }
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            if (sentTo == null) {
                TextButton(onClick = { send() }, enabled = !busy) { ButtonContent(busy, tr("Send code", "কোড পাঠান")) }
            } else {
                TextButton(
                    enabled = !busy && code.length == 6,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { PhoneCheck.verify(code) }.onSuccess { onVerified() }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { ButtonContent(busy, tr("Verify", "যাচাই করুন")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}
