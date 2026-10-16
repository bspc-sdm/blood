package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ------------------------------------------------------------
// হোমের "চলমান": কোনো সার্ভিসে আবেদন/অনুরোধের পর কাজ শেষ হওয়া পর্যন্ত প্রতিটা ধাপ (সার্ভার: lib/ongoing.js)।
// কার্ডে চাপলে নোটিফিকেশনের মতোই সেই সার্ভিসের পাতা খোলে।
// ------------------------------------------------------------

data class OngoingItem(
    val key: String,
    /** pharmacy, ambulance, bloodbank, badge, medfind, appointment, job */
    val service: String,
    val title: String,
    val line: String,
    val note: String,
    /** কিছু করতে হবে (উত্তর দিন, ছবি দিন) */
    val action: Boolean,
    /** দেখানোর তারিখ (০ = নেই); dateTime হলে সময়সহ */
    val date: Long,
    val dateTime: Boolean,
    val open: Map<String, String>,
)

object OngoingApi {
    private var cache: List<OngoingItem>? = null
    private var cachedAt = 0L

    /** হোম বারবার তৈরি হলে (ট্যাব বদল, ফিরে আসা) সার্ভারে বারবার না গিয়ে ১ মিনিট আগের তালিকা */
    suspend fun cached(force: Boolean): List<OngoingItem> {
        val now = System.currentTimeMillis()
        cache?.let { if (!force && now - cachedAt < 60_000) return it }
        return list().also { cache = it; cachedAt = now }
    }

    fun clear() {
        cache = null
    }

    suspend fun list(): List<OngoingItem> {
        val a = ServerApi.post("/api/community", JSONObject().put("action", "home_ongoing")).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val op = o.optJSONObject("open") ?: JSONObject()
            OngoingItem(
                o.str("key"), o.str("service"), o.str("title"), o.str("line"), o.str("note"), o.optBoolean("action"),
                o.optLong("date"), o.optBoolean("dateTime"),
                op.keys().asSequence().associateWith { k -> op.optString(k) },
            )
        }
    }

    fun icon(service: String): Int = when (service) {
        "pharmacy", "medfind" -> R.drawable.ic_medication
        "ambulance" -> R.drawable.ic_ambulance
        "bloodbank" -> R.drawable.ic_bloodtype
        "badge" -> R.drawable.ic_workspace_premium
        "appointment" -> R.drawable.ic_calendar_month
        "job" -> R.drawable.ic_nav_work // FEATURE:jobs
        else -> R.drawable.ic_info
    }
}

/** হোমে "চলমান" অংশ; কিছু না থাকলে কিছুই দেখায় না */
@Composable
fun OngoingSection(refreshKey: Int, manualKey: Int, onOpen: (Map<String, String>) -> Unit) {
    var list by remember { mutableStateOf<List<OngoingItem>>(emptyList()) }
    var lastManual by remember { mutableStateOf(manualKey) }
    LaunchedEffect(refreshKey, manualKey) {
        // হোমে ফিরে এলে ১ মিনিটের ক্যাশ; রিফ্রেশ বাটন চাপলে সরাসরি নতুন করে
        val force = manualKey != lastManual
        lastManual = manualKey
        runCatching { OngoingApi.cached(force) }.onSuccess { list = it }
    }
    OngoingCards(list, onOpen)
}

@Composable
fun OngoingCards(list: List<OngoingItem>, onOpen: (Map<String, String>) -> Unit) {
    if (list.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("In progress", "চলমান"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        list.forEach { x ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpen(x.open) },
                colors = CardDefaults.cardColors(containerColor = if (x.action) cs.secondaryContainer else cs.surfaceContainerLow),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = if (x.action) cs.primary else cs.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            AppIcon(OngoingApi.icon(x.service), null, Modifier.size(22.dp), if (x.action) cs.onPrimary else cs.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(
                            x.line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (x.action) cs.primary else cs.onSurfaceVariant,
                            fontWeight = if (x.action) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        val extra = listOfNotNull(
                            x.date.takeIf { it > 0 }?.let { if (x.dateTime) Rules.formatDate(it + Rules.DHAKA_OFFSET_MS) + ", " + Rules.formatTime(it) else Rules.formatDate(it) },
                            x.note.ifBlank { null },
                        ).joinToString(" · ")
                        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
                    }
                    AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.onSurfaceVariant)
                }
            }
        }
    }
}
