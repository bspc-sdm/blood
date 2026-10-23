package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md), ফিচার সুইচ health_consent
// - লেখা অ্যাপে রাখা হয় না: সার্ভার consent_status-এ পাঠায় (একটাই ভার্সন করা ফাইল — উকিল সেখানে বদলান, অ্যাপ আপডেট লাগে না)।
// - সার্ভারের 428 consent_required → ServerApi এখানে জিজ্ঞেস করে → "রাজি আছি" হলে একই কাজ আবার পাঠায়।
// - কেয়ারগিভার রোগীর হয়ে: পর্দা নয়, শুধু "রোগীকে নিজে একবার সম্মতি দিতে হবে"।
// - স্বাস্থ্যের পাতা (আমার প্রেসক্রিপশন, আমার অ্যাপয়েন্টমেন্ট) খুললে একবার — অ্যাপ খোলার সাথে সাথে নয়, যাতে রক্ত বা
//   অ্যাম্বুলেন্সের জন্য খোলা মানুষ আটকে না যান। "এখন না" বললে এই লেখার ভার্সনে আর নয় (এই ফোনে)।
// - জরুরি সেবা (রক্তের অনুরোধ, অ্যাম্বুলেন্স, কল, তালিকা) সম্মতি ছাড়াই চলে — সার্ভার কখনো আটকায় না।
// ------------------------------------------------------------

object HealthConsent {
    const val HEALTH_DATA = "health_data"
    const val DATA_ABROAD = "data_abroad"
    val SCOPES = listOf(HEALTH_DATA, DATA_ABROAD)
    const val REQUIRED_CODE = "consent_required"
    const val DECLINED_CODE = "consent_declined"

    data class Section(val key: String, val heading: String, val body: String, val linkLabel: String?, val linkUrl: String?)

    data class Text(
        val title: String,
        val intro: String,
        val sections: List<Section>,
        val scopes: Map<String, String>,
        val agreeNote: String,
        val agree: String,
        val later: String,
        val policyLabel: String,
        val policyUrl: String,
        val caregiver: String,
        val withdrawTitle: String,
        val withdrawBody: String,
        val withdrawButton: String,
        val deleteAccountLabel: String,
    )

    data class ScopeState(val state: String, val version: String?, val at: Long?, val source: String?)

    data class Status(
        val on: Boolean,
        val version: String,
        val current: Boolean,
        val ask: Boolean,
        val needed: List<String>,
        val scopes: Map<String, ScopeState>,
        val text: Text,
    )

    /** পর্দা খোলা: reason "gate" (একটা কাজ আটকেছে), "section" (স্বাস্থ্যের পাতা), "view" (সেটিংসের "পুরো লেখা দেখুন") */
    class Prompt(val reason: String, val answer: CompletableDeferred<Boolean> = CompletableDeferred())

    val status = mutableStateOf<Status?>(null)
    val prompt = mutableStateOf<Prompt?>(null)

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
    private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

    fun parse(j: JSONObject): Status {
        val t = j.optJSONObject("text") ?: JSONObject()
        val sec = t.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until sec.length()).mapNotNull { i ->
            sec.optJSONObject(i)?.let { s -> Section(s.str("key"), s.str("heading"), s.str("body"), s.strOrNull("linkLabel"), s.strOrNull("linkUrl")) }
        }
        val sc = t.optJSONObject("scopes") ?: JSONObject()
        val text = Text(
            title = t.str("title"), intro = t.str("intro"), sections = sections,
            scopes = SCOPES.associateWith { sc.str(it) },
            agreeNote = t.str("agreeNote"), agree = t.str("agree"), later = t.str("later"),
            policyLabel = t.str("policyLabel"), policyUrl = t.str("policyUrl"), caregiver = t.str("caregiver"),
            withdrawTitle = t.str("withdrawTitle"), withdrawBody = t.str("withdrawBody"), withdrawButton = t.str("withdrawButton"),
            deleteAccountLabel = t.str("deleteAccountLabel"),
        )
        val st = j.optJSONObject("scopes") ?: JSONObject()
        val scopes = SCOPES.associateWith { k ->
            val o = st.optJSONObject(k) ?: JSONObject()
            ScopeState(o.str("state").ifBlank { "none" }, o.strOrNull("version"), if (o.has("at") && !o.isNull("at")) o.optLong("at") else null, o.strOrNull("source"))
        }
        val nd = j.optJSONArray("needed") ?: JSONArray()
        return Status(
            on = j.optBoolean("on"), version = j.str("version"), current = j.optBoolean("current"), ask = j.optBoolean("ask"),
            needed = (0 until nd.length()).map { nd.optString(it) }, scopes = scopes, text = text,
        )
    }

    private suspend fun act(action: String, body: JSONObject = JSONObject()): Status =
        parse(ServerApi.post("/api/community", body.put("action", action))).also { status.value = it }

    suspend fun load(): Status = act("consent_status")

    /** "রাজি আছি": দুটো সম্মতি একসাথে (সার্ভারে আলাদা দুটো রেকর্ড), দেখানো লেখার ভার্সনসহ */
    suspend fun grant(): Status {
        val version = status.value?.version ?: load().version
        try {
            return act("consent_grant", JSONObject().put("scopes", JSONArray(SCOPES)).put("version", version).put("source", "app"))
        } catch (e: ServerError) {
            // এর মধ্যে লেখা বদলেছে: নতুন লেখা আনা (পুরোনো লেখায় সম্মতি রাখা হয় না)
            if (e.code == "consent_outdated") runCatching { load() }
            throw e
        }
    }

    /** সম্মতি তুলে নেওয়া — কিছুই মোছে না */
    suspend fun withdraw(scope: String = "all"): Status = act("consent_withdraw", JSONObject().put("scope", scope).put("source", "app"))

    /** পর্দা খুলে উত্তরের অপেক্ষা (true = রাজি আছি) */
    suspend fun ask(reason: String): Boolean {
        prompt.value?.answer?.complete(false)
        val p = Prompt(reason)
        prompt.value = p
        return p.answer.await()
    }

    fun answer(agreed: Boolean) {
        val p = prompt.value ?: return
        prompt.value = null
        p.answer.complete(agreed)
    }

    /** "এখন না"-র পর পাতার বার্তা */
    fun declinedText(): String = tr("Not saved. This needs your consent for health information.", "সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।")

    /** ServerApi-র 428: true হলে কাজটা আবার পাঠানো হয়। কেয়ারগিভার কখনো পর্দা দেখেন না। */
    internal suspend fun onRequired(data: JSONObject?): Boolean {
        if (data?.optBoolean("caregiver") == true) return false
        if (runCatching { load() }.isFailure) return false
        return ask("gate")
    }

    private const val PREFS = "health_consent"

    private fun dismissed(context: Context, uid: String, version: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("later_$uid", null) == version

    private fun rememberDismiss(context: Context, uid: String, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("later_$uid", version).apply()
    }

    private var asking = false

    /** স্বাস্থ্যের পাতা খুললে: সুইচ চালু, নিজের হয়ে, সম্মতি নেই/পুরোনো, আর এই লেখায় আগে "এখন না" বলা হয়নি → একবার */
    suspend fun askOnHealthSection(context: Context) {
        val uid = AuthRepository.currentUid ?: return
        if (CareMode.asUid.value != null || !Features.on(Features.HEALTH_CONSENT) || prompt.value != null || asking) return
        asking = true
        try {
            val s = runCatching { load() }.getOrNull() ?: return
            if (!s.ask || dismissed(context, uid, s.version) || CareMode.asUid.value != null || prompt.value != null) return
            if (!ask("section")) rememberDismiss(context, uid, s.version)
        } finally {
            asking = false
        }
    }

    /** বাংলাদেশের তারিখ (সার্ভারের সময় মিলিসেকেন্ডে) */
    fun dateOf(ms: Long): String = Rules.formatDate(ms + 6 * 3600_000L)
}

/** স্বাস্থ্যের পাতায় একবার: সুইচ চালু আর সম্মতি না থাকলে পর্দা (HealthConsent.askOnHealthSection) */
@Composable
fun HealthConsentPrompt() {
    val context = LocalContext.current
    val on = featureOn(Features.HEALTH_CONSENT)
    LaunchedEffect(on) { if (on) HealthConsent.askOnHealthSection(context) }
}

/** পুরো অ্যাপের ওপরে একবার (App): 428-এর পর বা স্বাস্থ্যের পাতা খোলায় সম্মতির পর্দা */
@Composable
fun HealthConsentHost() {
    val p = HealthConsent.prompt.value ?: return
    val s = HealthConsent.status.value ?: return
    HealthConsentDialog(
        status = s,
        readOnly = p.reason == "view" && s.current,
        onAgree = { HealthConsent.answer(true) },
        onLater = { HealthConsent.answer(false) },
    )
}

private fun sectionIcon(key: String): Int = when (key) {
    "what" -> R.drawable.ic_medical_services
    "where" -> R.drawable.ic_language
    "who" -> R.drawable.ic_person_outline
    "how_long" -> R.drawable.ic_schedule
    "withdraw" -> R.drawable.ic_history
    else -> R.drawable.ic_info
}

/**
 * সম্মতির পর্দা: ছোট, সহজ বাংলা — কী, কেন, কোথায়, আর কে দেখে, কতদিন (প্রাইভেসি পলিসির লিংক), কীভাবে তুলে নেবেন; তারপর দুটো
 * সম্মতি আর একটা "রাজি আছি" ও একটা "এখন না"। রং আর লেখার ধরন থিম থেকে (ফন্ট কোথাও বসানো নেই)।
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthConsentDialog(status: HealthConsent.Status, readOnly: Boolean, onAgree: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(false) }
    val t = status.text
    CardDialog(onDismissRequest = { if (!busy) onLater() }, dismissOnClickOutside = false, fillHeight = 0.92f, tag = "consent_dialog") {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(t.intro, style = MaterialTheme.typography.bodyMedium)
                t.sections.forEach { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(sectionIcon(s.key), null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            Text(s.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.linkUrl != null) {
                                TextButton(onClick = { openUrl(context, s.linkUrl) }) { Text(s.linkLabel ?: s.linkUrl) }
                            }
                        }
                    }
                }
                if (!readOnly) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HealthConsent.SCOPES.forEach { k ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                    AppIcon(R.drawable.ic_check_circle, null, Modifier.size(20.dp))
                                    Text(t.scopes[k].orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Text(t.agreeNote, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { openUrl(context, t.policyUrl) }) { Text(t.policyLabel.ifBlank { t.policyUrl }) }
                if (changed) {
                    Text(
                        tr("The text has just changed — please read it again.", "লেখাটা এইমাত্র বদলেছে — আবার পড়ে দেখুন।"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ErrorText(error)
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (readOnly) {
                    Button(onClick = onLater) { Text(tr("Close", "বন্ধ")) }
                } else {
                    TextButton(onClick = onLater, enabled = !busy, modifier = Modifier.testTag("consent_later")) { Text(t.later) }
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            changed = false
                            scope.launch {
                                try {
                                    HealthConsent.grant()
                                    onAgree()
                                } catch (e: ServerError) {
                                    if (e.code == "consent_outdated") changed = true else error = e.message
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("consent_agree"),
                    ) { Text(t.agree) }
                }
            }
        }
    }
}

/** সেটিংস › গোপনীয়তা: অবস্থা আর তারিখ, "পুরো লেখা দেখুন", "সম্মতি তুলে নিন" (কিছু মোছে না; অ্যাকাউন্ট মোছার লিংক) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthConsentSettings() {
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { HealthConsent.load() }.onFailure { loadError = it.message }
    }
    val s = HealthConsent.status.value
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("consent_settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(R.drawable.ic_privacy_tip, null)
            Text(tr("Privacy", "গোপনীয়তা"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        Text(tr("Consent for health information", "স্বাস্থ্য তথ্যের সম্মতি"), style = MaterialTheme.typography.titleSmall)
        if (s == null) {
            ErrorText(loadError)
            return@Column
        }
        HealthConsent.SCOPES.forEach { k ->
            val st = s.scopes[k]
            val (icon, line) = when (st?.state) {
                "granted" -> R.drawable.ic_check_circle to (tr("Given · ", "দেওয়া আছে · ") + (st.at?.let { HealthConsent.dateOf(it) } ?: ""))
                "outdated" -> R.drawable.ic_warning to tr("The text has changed — please read it and agree again", "লেখা বদলেছে — আবার পড়ে সম্মতি দিন")
                "withdrawn" -> R.drawable.ic_info to (tr("Withdrawn · ", "তুলে নিয়েছেন · ") + (st.at?.let { HealthConsent.dateOf(it) } ?: ""))
                else -> R.drawable.ic_info to tr("Not given", "দেওয়া হয়নি")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(icon, null, Modifier.size(20.dp), if (st?.state == "granted") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Text(s.text.scopes[k].orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("consent_state_$k"))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { HealthConsent.ask("view") } }) { Text(tr("See the full text", "পুরো লেখা দেখুন")) }
            if (!s.current) Button(onClick = { scope.launch { HealthConsent.ask("view") } }) { Text(tr("Give consent", "সম্মতি দিন")) }
            val anyGranted = s.scopes.values.any { it.state == "granted" || it.state == "outdated" }
            if (anyGranted) {
                TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("consent_withdraw")) {
                    Text(s.text.withdrawButton, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirm && s != null) {
        CardDialog(onDismissRequest = { if (!busy) confirm = false }, tag = "consent_withdraw_dialog") {
            Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.text.withdrawTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(s.text.withdrawBody, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    confirm = false
                    AccountNav.open.value = AccountNav.DELETE
                }) { Text(s.text.deleteAccountLabel.ifBlank { tr("Delete account", "অ্যাকাউন্ট মুছুন") }) }
                ErrorText(error)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { confirm = false }, enabled = !busy) { Text(tr("Cancel", "বাতিল")) }
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching { HealthConsent.withdraw() }
                                    .onSuccess { confirm = false }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.testTag("consent_withdraw_confirm"),
                    ) { Text(s.text.withdrawButton) }
                }
            }
        }
    }
}
