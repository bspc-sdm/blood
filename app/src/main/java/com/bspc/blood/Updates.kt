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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * নতুন ভার্সন এসেছে কি না: https://lifolo.com/download/-এ APK-র পাশে version.json থাকে (CI "Build APK" লেখে)।
 * lifolo.com-এর APK-তে এভাবেই ব্যবহারকারী জানতে পারেন। Play Store-এর বিল্ডে (BuildConfig.STORE = "play") এটা বন্ধ —
 * Play-র নীতিতে অ্যাপ নিজে নিজেকে আপডেট করতে পারে না, আপডেট Play দেয়।
 * ২.৩৪.১ থেকে নিজের সার্ভারে (আগে GitHub-এর "latest" রিলিজে — রিপো private হলে ওটা বন্ধ)।
 */
object Updates {
    const val APK_URL = "https://lifolo.com/download/lifolo.apk"
    private const val VERSION_URL = "https://lifolo.com/download/version.json"

    /** version.json-এর url শুধু এই জায়গাগুলোর হলে মানা হয় (GitHub-এরটা আপাতত থাকছে, পুরোনো version.json-এর জন্য) */
    private val TRUSTED_APK_PREFIXES = listOf(
        "https://lifolo.com/download/",
        "https://github.com/bspc-sdm/blood/releases/download/",
    )

    /** url: এই ভার্সনের নিজের নামের APK (version.json-এ থাকলে), না থাকলে স্থায়ী লিংক */
    data class Latest(val code: Long, val name: String, val url: String = APK_URL)

    private var cached: Latest? = null

    /** Play Store থেকে আসা বিল্ডে নিজের আপডেটের কার্ড দেখানো হয় না */
    val enabled: Boolean get() = BuildConfig.STORE != "play"

    /**
     * এই অ্যাপ release key দিয়ে সাইন করা কি না। না হলে (debug key-এর পুরোনো APK) release key-এর নতুন APK
     * এর উপরে ইনস্টল হয় না ("App not installed") — একবার আনইনস্টল করে ইনস্টল করতে হয়।
     * বুঝতে না পারলে true (কিছু বাড়তি দেখানো হয় না)।
     */
    fun signedWithReleaseKey(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching true
            (if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory).map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures.orEmpty().map { it.toByteArray() }
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        certs.any { c -> md.digest(c).joinToString("") { "%02X".format(it) } == BuildConfig.RELEASE_CERT_SHA256 }
    }.getOrDefault(true)

    /** আনইনস্টলের জন্য অ্যাপের তথ্যের পাতা (সেখানে "Uninstall") */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

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
                    val url = o.optString("url").takeIf { u -> TRUSTED_APK_PREFIXES.any { u.startsWith(it) } } ?: APK_URL
                    Latest(o.optLong("versionCode"), o.optString("versionName"), url)
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
    val reinstall = remember { !Updates.signedWithReleaseKey(context) }
    LaunchedEffect(Unit) {
        if (!Updates.enabled) return@LaunchedEffect
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
            // একবারের কাজ: এই অ্যাপ পুরোনো (debug) সাইনের, নতুনটা release key-এর — উপরে ইনস্টল হয় না
            if (reinstall) {
                Text(
                    tr(
                        "One-time step: if the phone says \"App not installed\", uninstall this app first (Remove old app → Uninstall), " +
                            "then install the downloaded file and log in again with your email. Your account and everything saved on the server stay; " +
                            "turn medicine alarms on again from your prescription.",
                        "একবারের কাজ: ফোন \"App not installed\" দেখালে আগে এই অ্যাপটা আনইনস্টল করুন (পুরোনোটা সরান → Uninstall), " +
                            "তারপর ডাউনলোড করা ফাইলটা ইনস্টল করে ইমেইল দিয়ে আবার লগইন করুন। অ্যাকাউন্ট আর সার্ভারে রাখা সব তথ্য থাকবে; " +
                            "ওষুধের অ্যালার্ম প্রেসক্রিপশন থেকে আবার চালু করুন।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, l.url) }) { Text(tr("Update", "আপডেট করুন")) }
                if (reinstall) {
                    TextButton(onClick = { Updates.openAppSettings(context) }) { Text(tr("Remove old app", "পুরোনোটা সরান")) }
                }
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

    FormDialog(
        title = tr("Verify your phone", "ফোন নম্বর যাচাই"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
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
