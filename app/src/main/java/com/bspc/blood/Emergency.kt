package com.bspc.blood

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// জরুরি রক্তের আহ্বান (দুর্ঘটনা ইত্যাদি) — Lifolo টিম ওয়েব প্যানেল থেকে দেয়।
// হোমের সবার উপরে ব্যানার: কোন গ্রুপের কত দরকার, কতজন আসছেন; নিজের গ্রুপ দরকার হলে "আসছি"।
// যথেষ্ট মানুষ আসছেন হলে সেই গ্রুপ বন্ধ — হাসপাতালে ভিড় এড়াতে।
// ------------------------------------------------------------

/** শেষ হওয়া আহ্বান, যেখানে "আসছি" বলেছিলেন — রক্ত দিয়েছেন কি না জানতে চাওয়া */
data class EmergencyAsk(val id: String, val hospitalName: String, val endedAt: Long)

data class EmergencyGroup(val group: String, val need: Int, val coming: Int, val full: Boolean)

data class EmergencyAppeal(
    val id: String,
    val hospitalName: String,
    val district: String,
    val contactPhone: String,
    val note: String,
    val groups: List<EmergencyGroup>,
    val endsAt: Long,
    val myGroup: String,
    /** "coming", "notified", "cancelled" বা "" */
    val myStatus: String,
    val canCome: Boolean,
    val eligible: Boolean,
)

object Emergencies {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun parse(o: JSONObject): EmergencyAppeal {
        val g = o.optJSONObject("groups") ?: JSONObject()
        val groups = Rules.BLOOD_GROUPS.mapNotNull { k ->
            g.optJSONObject(k)?.let { x -> EmergencyGroup(k, x.optInt("need"), x.optInt("coming"), x.optBoolean("full")) }
        }
        return EmergencyAppeal(
            o.str("id"), o.str("hospitalName"), o.str("district"), o.str("contactPhone"), o.str("note"), groups,
            o.optLong("endsAt"), o.str("myGroup"), o.str("myStatus"), o.optBoolean("canCome"), o.optBoolean("eligible"),
        )
    }

    suspend fun active(): Pair<List<EmergencyAppeal>, List<EmergencyAsk>> {
        val o = call("emergencies_active")
        val a = o.optJSONArray("items") ?: JSONArray()
        val q = o.optJSONArray("ask") ?: JSONArray()
        return (0 until a.length()).map { parse(a.getJSONObject(it)) } to
            (0 until q.length()).map { q.getJSONObject(it).let { x -> EmergencyAsk(x.str("id"), x.str("hospitalName"), x.optLong("endedAt")) } }
    }

    /** রক্ত দিয়েছেন কি না (দিলে শেষ রক্তদানের তারিখ আজ) */
    suspend fun donated(id: String, donated: Boolean) {
        call("emergency_donated", JSONObject().put("id", id).put("donated", donated))
    }

    /** op: coming, cancel */
    suspend fun respond(id: String, op: String) {
        call("emergency_respond", JSONObject().put("id", id).put("op", op))
    }
}

/** হোমের সবার উপরে (চালু আহ্বান থাকলেই দেখায়) */
@Composable
fun EmergencyBanner(refreshKey: Int) {
    var items by remember { mutableStateOf<List<EmergencyAppeal>>(emptyList()) }
    var ask by remember { mutableStateOf<List<EmergencyAsk>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var thanks by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey, reload) {
        runCatching { Emergencies.active() }.onSuccess { items = it.first; ask = it.second }
    }
    if (items.isEmpty() && ask.isEmpty() && thanks == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoText(thanks)
        ask.forEach { q -> EmergencyAskCard(q) { msg -> thanks = msg; reload++ } }
        items.forEach { e -> EmergencyCard(e, onDonated = { thanks = it }) { reload++ } }
    }
}

/** আহ্বান শেষে: "রক্ত দিয়েছেন?" — দিলে শেষ রক্তদানের তারিখ ওঠে, ১২০ দিন রিকোয়েস্টের নোটিফিকেশন আসে না */
@Composable
internal fun EmergencyAskCard(q: EmergencyAsk, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    fun answer(yes: Boolean) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { Emergencies.donated(q.id, yes) }
                .onSuccess {
                    onDone(
                        if (yes) tr("Thank you! Your last donation date is set to today.", "ধন্যবাদ! আপনার শেষ রক্তদানের তারিখ আজ বসানো হয়েছে।")
                        else tr("Thanks for letting us know.", "জানানোর জন্য ধন্যবাদ।"),
                    )
                }
                .onFailure { errorMsg = it.message }
            busy = false
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Did you donate blood?", "রক্ত দিয়েছেন?"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                tr("You said you were coming to the emergency appeal at ${q.hospitalName}.", "${q.hospitalName}-এর জরুরি আহ্বানে আপনি \"আসছি\" বলেছিলেন।"),
                style = MaterialTheme.typography.bodyMedium,
            )
            ErrorText(errorMsg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(tr("Yes, I donated", "হ্যাঁ, দিয়েছি"), R.drawable.ic_check_circle, { answer(true) }, primary = true, busy = busy)
                SmallAction(tr("No", "না, দিইনি"), R.drawable.ic_close, { answer(false) }, enabled = !busy)
            }
        }
    }
}

@Composable
internal fun EmergencyCard(e: EmergencyAppeal, onDonated: (String) -> Unit = {}, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme
    fun act(op: String) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { Emergencies.respond(e.id, op) }.onSuccess { onChanged() }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_warning, null, Modifier.size(20.dp), cs.error)
                Spacer(Modifier.width(8.dp))
                Text(tr("Emergency appeal · Lifolo team", "জরুরি আহ্বান · Lifolo টিম"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.error)
            }
            Column {
                Text(e.hospitalName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(Districts.label(e.district), style = MaterialTheme.typography.bodySmall)
            }
            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium)
            // কোন গ্রুপের কত দরকার, কতজন আসছেন
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                e.groups.forEach { g ->
                    val mine = g.group == e.myGroup
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (g.full) cs.surfaceVariant else if (mine) cs.error else cs.surface,
                        contentColor = if (g.full) cs.onSurfaceVariant else if (mine) cs.onError else cs.onSurface,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(g.group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (g.full) tr("Enough", "যথেষ্ট") else tr("${g.coming}/${g.need} coming", "${num(g.coming)}/${num(g.need)} আসছেন"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            Countdown(e.endsAt)
            val my = e.groups.firstOrNull { it.group == e.myGroup }
            Text(
                when {
                    e.myStatus == "coming" -> tr("You're coming — thank you! Please bring your ID.", "আপনি আসছেন — ধন্যবাদ! সাথে পরিচয়পত্র আনুন।")
                    e.myStatus == "donated" -> tr("You donated — thank you!", "আপনি রক্ত দিয়েছেন — ধন্যবাদ!")
                    e.myGroup == Rules.UNKNOWN_GROUP || e.myGroup.isBlank() -> tr("Add your blood group in your profile to respond. Please tell people you know.", "সাড়া দিতে প্রোফাইলে রক্তের গ্রুপ দিন। পরিচিতদের জানাতে পারেন।")
                    my == null -> tr("Your blood group isn't needed in this appeal. Please tell people you know.", "এই আহ্বানে আপনার গ্রুপ দরকার নেই। পরিচিতদের জানাতে পারেন।")
                    my.full -> tr("Enough donors are already coming for your group — thank you!", "আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!")
                    !e.eligible -> tr("You can't donate right now (last donation, age or weight).", "এখন আপনি রক্ত দিতে পারবেন না (শেষ রক্তদান, বয়স বা ওজন)।")
                    else -> tr("Your group (${e.myGroup}) is needed. Tap \"I'm coming\" only if you can go now.", "আপনার গ্রুপ (${e.myGroup}) দরকার। এখনই যেতে পারলে তবেই \"আসছি\" চাপুন।")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ErrorText(errorMsg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.myStatus == "coming") {
                    // হাসপাতালে রক্ত দেওয়া হয়ে গেলে এখনই জানানো যায়
                    SmallAction(tr("I donated", "রক্ত দিয়েছি"), R.drawable.ic_check_circle, {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { Emergencies.donated(e.id, true) }
                                .onSuccess { onDonated(tr("Thank you! Your last donation date is set to today.", "ধন্যবাদ! আপনার শেষ রক্তদানের তারিখ আজ বসানো হয়েছে।")); onChanged() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }, primary = true, busy = busy)
                    SmallAction(tr("Can't come", "আসতে পারছি না"), R.drawable.ic_close, { act("cancel") }, busy = busy)
                } else if (e.canCome) {
                    SmallAction(tr("I'm coming", "আসছি"), R.drawable.ic_directions_run, { act("coming") }, primary = true, busy = busy)
                }
                if (e.contactPhone.isNotBlank()) {
                    SmallAction(tr("Call hospital", "হাসপাতালে ফোন"), R.drawable.ic_call, { callPhone(context, e.contactPhone) })
                }
            }
        }
    }
}
