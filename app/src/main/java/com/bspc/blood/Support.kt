@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// সাহায্য ও আপিল: ব্যবহারকারী লেখেন, Lifolo টিম ওয়েব প্যানেল থেকে উত্তর দেয়
// ------------------------------------------------------------

data class TicketMessage(val fromAdmin: Boolean, val text: String, val at: Long)

data class Ticket(
    val id: String,
    val category: String,
    val status: String,
    val messages: List<TicketMessage>,
    val updatedAt: Long,
) {
    val answered: Boolean get() = messages.lastOrNull()?.fromAdmin == true
}

/** অন্য পাতা থেকে "আপিল করুন" চাপলে এই পাতা খোলে (বিষয় আগে থেকে বাছা) */
object HelpNav {
    val open = mutableStateOf<String?>(null)
}

object Tickets {
    val CATEGORIES = listOf("account", "badge", "pharmacy", "ambulance", "homevisit", "medorder", "appointment", "blood", "other")

    fun label(c: String): String = when (c) {
        "account" -> tr("Account", "অ্যাকাউন্ট")
        "badge" -> tr("Badge (doctor/nurse)", "ব্যাজ (ডাক্তার/নার্স)")
        "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
        "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
        "homevisit" -> tr("Home nurse", "হোম নার্স")
        "medorder" -> tr("Medicine order", "ওষুধের অর্ডার")
        "appointment" -> tr("Appointment", "অ্যাপয়েন্টমেন্ট")
        "blood" -> tr("Blood request", "রক্তের অনুরোধ")
        else -> tr("Something else", "অন্য কিছু")
    }

    fun statusLabel(t: Ticket): String = when {
        t.status == "closed" -> tr("Closed", "বন্ধ")
        t.answered -> tr("Reply received", "উত্তর এসেছে")
        else -> tr("Waiting for reply", "উত্তরের অপেক্ষায়")
    }

    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    suspend fun mine(): List<Ticket> {
        val arr = call("tickets_mine").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.optJSONArray("messages") ?: JSONArray()
            Ticket(
                o.optString("id"), o.optString("category"), o.optString("status"),
                (0 until m.length()).map { k ->
                    val x = m.getJSONObject(k)
                    TicketMessage(x.optString("from") == "admin", x.optString("text"), x.optLong("at"))
                },
                o.optLong("updatedAt"),
            )
        }
    }

    suspend fun create(category: String, text: String) {
        call("ticket_create", JSONObject().put("category", category).put("text", text))
    }

    suspend fun reply(id: String, text: String) {
        call("ticket_reply", JSONObject().put("id", id).put("text", text))
    }
}

@Composable
fun HelpScreen(startCategory: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Ticket>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var writing by rememberSaveable { mutableStateOf(startCategory != null) }
    var category by rememberSaveable { mutableStateOf(startCategory) }
    var text by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        runCatching { Tickets.mine() }
            .onSuccess { items = it; errorMsg = null }
            .onFailure { errorMsg = it.message; if (items == null) items = emptyList() }
    }

    val open = items?.firstOrNull { it.id == openId }

    Scaffold(
        topBar = {
            BackTopBar(
                if (open != null) Tickets.label(open.category) else tr("Help & appeals", "সাহায্য ও আপিল"),
                { if (openId != null) openId = null else onBack() },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            InfoText(infoMsg)

            if (open != null) {
                // ---- একটা অনুরোধের কথোপকথন ----
                Text(Tickets.statusLabel(open), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                open.messages.forEach { m -> MessageBubble(m) }
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it.take(1500) },
                    label = { Text(if (open.status == "closed") tr("Write again (reopens it)", "আবার লিখুন (আবার খুলবে)") else tr("Write more", "আরও লিখুন")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { Tickets.reply(open.id, replyText.trim()) }
                                .onSuccess { replyText = ""; infoMsg = tr("Sent.", "পাঠানো হয়েছে।"); reload++ }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy && replyText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonContent(busy, tr("Send", "পাঠান"), R.drawable.ic_send) }
                return@Column
            }

            SmallNote(
                tr(
                    "Write to the Lifolo team about any problem — a suspended pharmacy or ambulance, a badge, an order, a blocked feature or anything else. You'll get a notification when we reply.",
                    "যেকোনো সমস্যায় Lifolo টিমকে লিখুন: ফার্মেসি বা অ্যাম্বুলেন্স বন্ধ হওয়া, ব্যাজ, অর্ডার, কোনো সুবিধা বন্ধ হওয়া বা অন্য কিছু। উত্তর দিলে নোটিফিকেশন পাবেন।",
                ),
            )

            if (writing) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("What is it about?", "কী নিয়ে?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tickets.CATEGORIES.forEach { c ->
                                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(Tickets.label(c)) })
                            }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(1500) },
                            label = { Text(tr("Describe the problem", "সমস্যাটা খুলে লিখুন")) },
                            supportingText = { Text(tr("Include names, dates or order details if you can.", "পারলে নাম, তারিখ বা অর্ডারের বিবরণ দিন।")) },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val c = category ?: return@Button
                                    scope.launch {
                                        busy = true
                                        errorMsg = null
                                        runCatching { Tickets.create(c, text.trim()) }
                                            .onSuccess {
                                                text = ""
                                                writing = false
                                                infoMsg = tr("Sent. The Lifolo team will reply here.", "পাঠানো হয়েছে। Lifolo টিম এখানেই উত্তর দেবে।")
                                                reload++
                                            }
                                            .onFailure { errorMsg = it.message }
                                        busy = false
                                    }
                                },
                                enabled = !busy && category != null && text.trim().length >= 10,
                            ) { ButtonContent(busy, tr("Send", "পাঠান"), R.drawable.ic_send) }
                            OutlinedButton(onClick = { writing = false }) { Text(tr("Cancel", "বাতিল")) }
                        }
                    }
                }
            } else {
                Button(onClick = { writing = true; infoMsg = null }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Write to us", "আমাদের লিখুন"), R.drawable.ic_edit_note)
                }
            }

            SectionTitle(tr("My messages", "আমার অনুরোধ"))
            when {
                items == null -> LoadingBox()
                items!!.isEmpty() -> SmallNote(tr("Nothing yet.", "এখনো কিছু নেই।"))
                else -> items!!.forEach { t ->
                    Card(
                        onClick = { openId = t.id; infoMsg = null; errorMsg = null },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Tickets.label(t.category), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    Tickets.statusLabel(t),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (t.answered && t.status != "closed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            t.messages.lastOrNull()?.let {
                                Text(it.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                            SmallNote(timeAgo(t.updatedAt))
                        }
                    }
                }
            }
        }
    }
}

/** বন্ধ বা বাতিল হওয়া সেবার পাশে: আপিলের পাতা খোলে */
@Composable
fun AppealButton(category: String) {
    OutlinedButton(onClick = { HelpNav.open.value = category }, modifier = Modifier.fillMaxWidth()) {
        ButtonContent(false, tr("Appeal / contact us", "আপিল করুন / আমাদের লিখুন"), R.drawable.ic_help)
    }
}

@Composable
private fun MessageBubble(m: TicketMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromAdmin) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.fromAdmin) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    (if (m.fromAdmin) tr("Lifolo team", "Lifolo টিম") else tr("You", "আপনি")) + ", " + timeAgo(m.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
