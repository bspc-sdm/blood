@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// মোবাইলের অ্যাডমিন: যেকোনো সময় এক-দুই চাপে সারা যায় এমন কাজ —
// রিপোর্ট, সাপোর্টের উত্তর, জরুরি আবেদন, তারিখ সংশোধন, ইউজার/পেশাজীবী সাময়িক বন্ধ।
// কাগজ দেখে অনুমোদন (ব্যাজ, ফার্মেসি, অ্যাম্বুলেন্স, ব্লাড ব্যাংক, হাসপাতাল) শুধু ওয়েব প্যানেলে — এখানে সংখ্যা আর প্যানেলে খোলার পথ।
// ------------------------------------------------------------

data class AdminTicket(
    val id: String,
    val category: String,
    val name: String,
    val email: String,
    val messages: List<TicketMessage>,
    val updatedAt: Long,
)

data class AdminEmergency(
    val id: String,
    val hospitalName: String,
    val district: String,
    val groups: List<Triple<String, Int, Int>>,
    val live: Boolean,
    val endsAt: Long,
    val waves: Int,
)

data class AdminOverview(
    val reports: List<ReportedItem>,
    val tickets: List<AdminTicket>,
    val corrections: List<CorrectionRequest>,
    /** ওয়েব প্যানেলের কাজ: নাম → সংখ্যা */
    val panel: List<Pair<String, Int>>,
)

data class AdminUserBrief(val uid: String, val name: String, val email: String, val phone: String, val district: String, val disabled: Boolean)

object AdminApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun JSONObject.items(key: String): JSONArray = optJSONObject(key)?.optJSONArray("items") ?: JSONArray()
    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    suspend fun overview(): AdminOverview {
        val o = call("admin_overview")
        val allReports = o.items("reports").objs()
        val reports = allReports.filter { it.optString("kind") != "provider" }.map {
            ReportedItem(
                it.optString("targetPath"), it.optString("kind"), it.optInt("count"), it.optString("text"), it.optString("authorName"),
                if (it.isNull("reportedUid")) null else it.optString("reportedUid").ifBlank { null },
                if (it.isNull("reportedRole")) null else it.optString("reportedRole").ifBlank { null },
            )
        }
        val tickets = o.items("tickets").objs().map { t ->
            AdminTicket(
                t.optString("id"), t.optString("category"), t.optString("name"), t.optString("email"),
                (t.optJSONArray("messages") ?: JSONArray()).objs().map { m -> TicketMessage(m.optString("from") == "admin", m.optString("text"), m.optLong("at")) },
                t.optLong("updatedAt"),
            )
        }
        val corrections = o.items("corrections").objs().map { c ->
            CorrectionRequest(
                id = c.optString("id"), uid = c.optString("uid"), name = c.optString("name"),
                currentMillis = if (c.isNull("currentMillis")) null else c.optLong("currentMillis"),
                requestedMillis = if (c.isNull("requestedMillis")) null else c.optLong("requestedMillis"),
                reason = c.optString("reason"), createdAtMillis = c.optLong("createdAtMillis"),
            )
        }
        val panel = listOf(
            tr("Badges (doctor, nurse…)", "ব্যাজ (ডাক্তার, নার্স…)") to o.items("badges").length(),
            tr("Pharmacies", "ফার্মেসি") to o.items("pharmacies").length(),
            tr("Ambulances", "অ্যাম্বুলেন্স") to o.items("ambulances").length(),
            tr("Blood banks", "ব্লাড ব্যাংক") to o.items("bloodBanks").length(),
            tr("Labs", "প্যাথলজি") to o.items("labs").length(),
            tr("New hospitals", "নতুন হাসপাতাল") to o.items("hospitalRequests").length(),
            tr("Reports on doctors", "ডাক্তারের বিরুদ্ধে রিপোর্ট") to (allReports.size - reports.size),
        )
        return AdminOverview(reports, tickets, corrections, panel)
    }

    suspend fun ticketReply(id: String, text: String?, close: Boolean) {
        call("admin_ticket_reply", JSONObject().put("id", id).put("close", close).apply { if (!text.isNullOrBlank()) put("text", text) })
    }

    suspend fun emergencies(): List<AdminEmergency> = call("admin_emergencies").optJSONArray("items").let { it ?: JSONArray() }.objs().map { e ->
        val g = e.optJSONObject("groups") ?: JSONObject()
        AdminEmergency(
            e.optString("id"), e.optString("hospitalName"), e.optString("district"),
            Rules.BLOOD_GROUPS.mapNotNull { k -> g.optJSONObject(k)?.let { Triple(k, it.optInt("need"), it.optInt("coming")) } },
            e.optBoolean("live"), e.optLong("endsAt"), e.optInt("waves"),
        )
    }

    suspend fun emergencyCreate(body: JSONObject): Int = call("admin_emergency_create", body.put("confirmed", true)).optInt("notified")
    suspend fun emergencyMore(id: String): Int = call("admin_emergency_more", JSONObject().put("id", id)).optInt("notified")
    suspend fun emergencyClose(id: String) = call("admin_emergency_close", JSONObject().put("id", id))

    suspend fun findUsers(q: String): List<AdminUserBrief> = (call("admin_user_find", JSONObject().put("q", q)).optJSONArray("items") ?: JSONArray()).objs().map { brief(it) }

    private fun brief(u: JSONObject) = AdminUserBrief(
        u.optString("uid"), u.optString("name"), u.optString("email"), u.optString("phone"), u.optString("district"), u.optBoolean("disabled"),
    )

    /** ইউজার আর (থাকলে) পেশাজীবীর অবস্থা: first = ব্রিফ, second = পেশাজীবী তালিকায় সাময়িক বন্ধ কি না (null = পেশাজীবী নন) */
    suspend fun user(uid: String): Pair<AdminUserBrief, Boolean?> {
        val u = call("admin_user", JSONObject().put("uid", uid)).getJSONObject("user")
        val p = u.optJSONObject("provider")
        return brief(u) to p?.optBoolean("suspended")
    }

    suspend fun userAction(uid: String, op: String, reason: String = "") {
        call("admin_user_action", JSONObject().put("uid", uid).put("op", op).put("reason", reason))
    }
}

@Composable
fun AdminScreen(isOwner: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<AdminOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = data == null
        runCatching { AdminApi.overview() }.onSuccess { data = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    fun act(id: String, done: String? = null, block: suspend () -> Unit) {
        scope.launch {
            busyId = id
            errorMsg = null
            runCatching { block() }
                .onSuccess { infoMsg = done; reload++ }
                .onFailure { errorMsg = it.message }
            busyId = null
        }
    }

    val d = data
    val count = { n: Int -> if (n > 0) " " + num(n) else "" }
    Scaffold(
        topBar = {
            BackTopBar(tr("Admin", "অ্যাডমিন"), onBack) {
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PillTabs(
                tab,
                listOf(
                    PillTab(tr("Overview", "সব কাজ"), R.drawable.ic_list_alt),
                    PillTab(tr("Reports", "রিপোর্ট") + count(d?.reports?.size ?: 0), R.drawable.ic_flag),
                    PillTab(tr("Support", "সাপোর্ট") + count(d?.tickets?.size ?: 0), R.drawable.ic_forum_outline),
                    PillTab(tr("Emergency", "জরুরি"), R.drawable.ic_bloodtype),
                    PillTab(tr("Dates", "তারিখ") + count(d?.corrections?.size ?: 0), R.drawable.ic_calendar_month),
                    PillTab(tr("Users", "ইউজার"), R.drawable.ic_person_outline),
                ),
                { tab = it },
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                ErrorText(errorMsg)
                InfoText(infoMsg)
            }
            when {
                loading -> LoadingBox()
                d == null -> Unit
                tab == 0 -> AdminOverviewTab(d) { tab = it }
                tab == 1 -> AdminReportsTab(d.reports, busyId) { r, remove ->
                    act(r.targetPath, if (remove) tr("Done", "করা হয়েছে") else tr("Kept", "রেখে দেওয়া হয়েছে")) { Moderation.resolve(r.targetPath, remove, r.reportedUid) }
                }
                tab == 2 -> AdminTicketsTab(d.tickets, busyId) { t, text, close ->
                    act(t.id, if (close && text.isNullOrBlank()) tr("Closed", "বন্ধ করা হয়েছে") else tr("Reply sent", "উত্তর পাঠানো হয়েছে")) { AdminApi.ticketReply(t.id, text, close) }
                }
                tab == 3 -> AdminEmergencyTab()
                tab == 4 -> AdminCorrectionsTab(d.corrections, busyId) { c, approve ->
                    act(c.id, if (approve) tr("Approved", "অনুমোদন দেওয়া হয়েছে") else tr("Rejected", "বাতিল করা হয়েছে")) { ServerApi.resolveCorrection(c.id, approve) }
                }
                else -> AdminUsersTab()
            }
        }
    }
}

/** তালিকার এক সারি: আইকন, নাম, সংখ্যা, › */
@Composable
private fun AdminRow(@DrawableRes icon: Int, title: String, n: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, null, Modifier.size(22.dp), cs.primary)
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (n > 0) Tag(num(n), cs.primaryContainer, cs.onPrimaryContainer)
            else Text("০".takeIf { isBangla } ?: "0", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdminOverviewTab(d: AdminOverview, onTab: (Int) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle(tr("Do it here", "এখানেই করা যায়")) }
        item { AdminRow(R.drawable.ic_flag, tr("Reports", "রিপোর্ট"), d.reports.size) { onTab(1) } }
        item { AdminRow(R.drawable.ic_forum_outline, tr("Support messages", "সাপোর্টের বার্তা"), d.tickets.size) { onTab(2) } }
        item { AdminRow(R.drawable.ic_calendar_month, tr("Date corrections", "তারিখ সংশোধন"), d.corrections.size) { onTab(4) } }
        item { SectionTitle(tr("In the web panel (check documents)", "ওয়েব প্যানেলে (কাগজ দেখে)")) }
        items(d.panel) { (title, n) ->
            AdminRow(R.drawable.ic_verified, title, n) { openUrl(context, ServerApi.BASE_URL + "/admin") }
        }
        item {
            OutlinedButton(onClick = { openUrl(context, ServerApi.BASE_URL + "/admin") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ButtonContent(false, tr("Open web panel", "ওয়েব প্যানেল খুলুন"), R.drawable.ic_language)
            }
        }
    }
}

private fun reportKind(r: ReportedItem): String = when (r.kind) {
    "question" -> tr("Question", "প্রশ্ন")
    "answer" -> tr("Answer", "উত্তর")
    "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
    "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
    "homecare" -> tr("Home care", "বাসায় সেবা")
    "bloodbank" -> tr("Blood bank", "ব্লাড ব্যাংক")
    "homevisit" -> if (r.reportedRole == "patient") tr("Home visit · patient", "হোম ভিজিট · রোগী") else tr("Home visit · nurse", "হোম ভিজিট · নার্স")
    else -> tr("Tip", "টিপস")
}

@Composable
private fun AdminEmpty(text: String) {
    EmptyState(R.drawable.ic_check_circle, text, null, Modifier.fillMaxWidth().padding(top = 32.dp))
}

@Composable
private fun AdminReportsTab(list: List<ReportedItem>, busyId: String?, onResolve: (ReportedItem, Boolean) -> Unit) {
    if (list.isEmpty()) return AdminEmpty(tr("No reports to check", "দেখার মতো কোনো রিপোর্ট নেই"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.targetPath }) { r ->
            val cs = MaterialTheme.colorScheme
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(reportKind(r), cs.secondaryContainer, cs.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("${num(r.count)} report(s)", "${num(r.count)}টা রিপোর্ট"), style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                    Text(r.authorName.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        val removeLabel = when (r.kind) {
                            "homevisit", "homecare" -> tr("Turn off home care", "বাসায় সেবা বন্ধ")
                            "ambulance", "pharmacy", "bloodbank" -> tr("Suspend", "বন্ধ করুন")
                            else -> tr("Remove post", "পোস্ট মুছুন")
                        }
                        SmallAction(removeLabel, R.drawable.ic_delete, { onResolve(r, true) }, primary = true, enabled = busyId == null, busy = busyId == r.targetPath)
                        SmallAction(tr("Keep", "রেখে দিন"), null, { onResolve(r, false) }, enabled = busyId == null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTicketsTab(list: List<AdminTicket>, busyId: String?, onReply: (AdminTicket, String?, Boolean) -> Unit) {
    var open by remember { mutableStateOf<AdminTicket?>(null) }
    if (list.isEmpty()) return AdminEmpty(tr("No open support messages", "খোলা কোনো সাপোর্টের বার্তা নেই"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.id }) { t ->
            val cs = MaterialTheme.colorScheme
            Card(onClick = { open = t }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name.ifBlank { t.email.ifBlank { "-" } }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Tag(Tickets.label(t.category), cs.secondaryContainer, cs.onSecondaryContainer)
                    }
                    t.messages.lastOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    Text(timeAgo(t.updatedAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
    open?.let { t ->
        // তালিকা নতুন করে এলে খোলা টিকিটটা হালনাগাদ
        val cur = list.firstOrNull { it.id == t.id }
        if (cur == null) open = null
        else TicketSheet(cur, busyId == cur.id, onDismiss = { open = null }) { text, close -> onReply(cur, text, close) }
    }
}

@Composable
private fun TicketSheet(t: AdminTicket, busy: Boolean, onDismiss: () -> Unit, onReply: (String?, Boolean) -> Unit) {
    var text by remember(t.id) { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(t.name.ifBlank { "-" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(listOf(t.email, Tickets.label(t.category)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.messages.forEach { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromAdmin) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (m.fromAdmin) cs.primaryContainer else cs.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (if (m.fromAdmin) tr("Lifolo team", "Lifolo টিম") else t.name.ifBlank { tr("User", "ইউজার") }) + ", " + timeAgo(m.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1000) },
                label = { Text(tr("Reply", "উত্তর")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onReply(text.trim().ifBlank { null }, true) }, enabled = !busy) {
                    Text(if (text.isBlank()) tr("Close", "বন্ধ করুন") else tr("Send & close", "পাঠিয়ে বন্ধ"))
                }
                Button(onClick = { onReply(text.trim(), false); text = "" }, enabled = !busy && text.isNotBlank(), shape = RoundedCornerShape(12.dp)) {
                    ButtonContent(busy, tr("Send", "পাঠান"), R.drawable.ic_send)
                }
            }
        }
    }
}

@Composable
private fun AdminCorrectionsTab(list: List<CorrectionRequest>, busyId: String?, onResolve: (CorrectionRequest, Boolean) -> Unit) {
    if (list.isEmpty()) return AdminEmpty(tr("No date corrections waiting", "অপেক্ষায় কোনো তারিখ সংশোধন নেই"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.name.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconLine(R.drawable.ic_history, tr("Now: ", "এখন: ") + (c.currentMillis?.let { Rules.formatDate(it) } ?: "-"))
                    IconLine(R.drawable.ic_edit_note, tr("Wants: ", "চাইছেন: ") + (c.requestedMillis?.let { Rules.formatDate(it) } ?: tr("Never donated", "কখনো দেননি")))
                    if (c.reason.isNotBlank()) Text(c.reason, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SmallAction(tr("Approve", "অনুমোদন"), R.drawable.ic_check_circle, { onResolve(c, true) }, primary = true, enabled = busyId == null, busy = busyId == c.id)
                        SmallAction(tr("Reject", "বাতিল"), null, { onResolve(c, false) }, enabled = busyId == null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminEmergencyTab() {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AdminEmergency>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { AdminApi.emergencies() }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    fun act(id: String, block: suspend () -> String?) {
        scope.launch {
            busyId = id
            runCatching { block() }.onSuccess { infoMsg = it; reload++ }.onFailure { errorMsg = it.message }
            busyId = null
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                ButtonContent(false, tr("New emergency appeal", "নতুন জরুরি আবেদন"), R.drawable.ic_add)
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }
        val l = list
        when {
            l == null -> item { LoadingBox() }
            l.isEmpty() -> item { AdminEmpty(tr("No appeals yet", "এখনো কোনো আবেদন নেই")) }
            else -> items(l, key = { it.id }) { e ->
                val cs = MaterialTheme.colorScheme
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.hospitalName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (e.live) Tag(tr("Live", "চালু"), cs.tertiaryContainer, cs.onTertiaryContainer)
                            else Tag(tr("Ended", "শেষ"), cs.surfaceVariant, cs.onSurfaceVariant)
                        }
                        IconLine(R.drawable.ic_location_on, Districts.label(e.district))
                        IconLine(
                            R.drawable.ic_bloodtype,
                            e.groups.joinToString(" · ") { (g, need, coming) -> "$g " + tr("${num(coming)}/${num(need)} coming", "${num(need)} ব্যাগ, আসছেন ${num(coming)}") },
                        )
                        if (e.live) {
                            IconLine(R.drawable.ic_schedule, tr("Ends ", "শেষ হবে ") + Rules.formatDate(e.endsAt) + ", " + Rules.formatTime(e.endsAt))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                SmallAction(tr("Notify more donors", "আরও ডোনার জানান"), R.drawable.ic_notifications, {
                                    act(e.id) { val n = AdminApi.emergencyMore(e.id); tr("Notified $n more", "আরও ${num(n)} জনকে জানানো হয়েছে") }
                                }, primary = true, enabled = busyId == null, busy = busyId == e.id)
                                SmallAction(tr("End", "শেষ করুন"), null, {
                                    act(e.id) { AdminApi.emergencyClose(e.id); tr("Ended", "শেষ করা হয়েছে") }
                                }, enabled = busyId == null)
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        EmergencyCreateSheet(onDismiss = { creating = false }) { n ->
            creating = false
            infoMsg = tr("Appeal sent to $n donors", "${num(n)} জন ডোনারকে আবেদন পাঠানো হয়েছে")
            reload++
        }
    }
}

/** নতুন জরুরি আবেদন: হাসপাতাল, জেলা, কোন গ্রুপে কত ব্যাগ, কত দূর পর্যন্ত, কতক্ষণ, যোগাযোগ */
@Composable
private fun EmergencyCreateSheet(onDismiss: () -> Unit, onSent: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var hospital by remember { mutableStateOf("") }
    var district by remember { mutableStateOf<String?>(null) }
    val bags = remember { mutableStateMapOf<String, String>() }
    var radius by remember { mutableIntStateOf(25) }
    var hours by remember { mutableIntStateOf(12) }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var pickDistrict by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Emergency appeal", "জরুরি আবেদন"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(hospital, { hospital = it.take(120) }, label = { Text(tr("Hospital", "হাসপাতাল")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickDistrict = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, district?.let { Districts.label(it) } ?: tr("Choose district", "জেলা বেছে নিন"), R.drawable.ic_location_on)
            }
            Text(tr("Bags needed", "কোন গ্রুপে কত ব্যাগ"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
                Rules.BLOOD_GROUPS.forEach { g ->
                    OutlinedTextField(
                        value = bags[g].orEmpty(),
                        onValueChange = { v -> bags[g] = v.filter { it.isDigit() }.take(3) },
                        label = { Text(g) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(tr("How far", "কত দূর পর্যন্ত"), style = MaterialTheme.typography.labelLarge)
            ChipRows(listOf("10", "25", "50"), setOf(radius.toString()), perRow = 3, label = { tr("$it km", "${num(it.toInt())} কিমি") }) { radius = it.toInt() }
            Text(tr("For how long", "কতক্ষণ চালু থাকবে"), style = MaterialTheme.typography.labelLarge)
            ChipRows(listOf("6", "12", "24", "48"), setOf(hours.toString()), perRow = 4, label = { tr("$it h", "${num(it.toInt())} ঘণ্টা") }) { hours = it.toInt() }
            OutlinedTextField(
                phone, { phone = it.take(20) },
                label = { Text(tr("Hospital contact mobile", "হাসপাতালের যোগাযোগের মোবাইল")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                Text(tr("I have spoken to the hospital blood bank", "হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছি"), style = MaterialTheme.typography.bodyMedium)
            }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    val groups = JSONObject().apply { bags.forEach { (g, v) -> v.toIntOrNull()?.takeIf { it > 0 }?.let { put(g, it) } } }
                    errorMsg = when {
                        hospital.isBlank() -> tr("Write the hospital", "হাসপাতালের নাম লিখুন")
                        district == null -> tr("Choose a district", "জেলা বেছে নিন")
                        groups.length() == 0 -> tr("Enter bags for at least one group", "অন্তত একটা গ্রুপে কত ব্যাগ লাগবে দিন")
                        phone.isBlank() -> tr("Enter the contact mobile", "যোগাযোগের মোবাইল নম্বর দিন")
                        !confirmed -> tr("Tick that you have spoken to the blood bank", "ব্লাড ব্যাংকের সাথে কথা বলেছেন — টিক দিন")
                        else -> null
                    }
                    if (errorMsg == null) scope.launch {
                        busy = true
                        runCatching {
                            AdminApi.emergencyCreate(
                                JSONObject().put("hospitalName", hospital.trim()).put("district", district).put("groups", groups)
                                    .put("radiusKm", radius).put("hours", hours).put("contactPhone", phone.trim()).put("note", note.trim()),
                            )
                        }.onSuccess { onSent(it) }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { ButtonContent(busy, tr("Send appeal", "আবেদন পাঠান"), R.drawable.ic_send) }
        }
    }
    if (pickDistrict) {
        SearchPickDialog(
            title = tr("District", "জেলা"),
            items = Districts.ALL,
            label = { Districts.label(it.bn) },
            onPick = { p -> if (p != null) district = p.bn; pickDistrict = false },
            onDismiss = { pickDistrict = false },
        )
    }
}

@Composable
private fun AdminUsersTab() {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AdminUserBrief>?>(null) }
    var selected by remember { mutableStateOf<Pair<AdminUserBrief, Boolean?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // যে কাজটা নিশ্চিত করতে হবে

    fun search() {
        scope.launch {
            busy = true
            errorMsg = null
            selected = null
            runCatching { AdminApi.findUsers(q.trim()) }.onSuccess { results = it }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    fun open(uid: String) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { AdminApi.user(uid) }.onSuccess { selected = it }.onFailure { errorMsg = it.message }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it.take(200) },
                    label = { Text(tr("Email or phone", "ইমেইল বা ফোন নম্বর")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { search() }, enabled = !busy && q.trim().length >= 3) { AppIcon(R.drawable.ic_search, tr("Search", "খুঁজুন")) }
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }
        if (busy) item { LoadingBox() }
        val sel = selected
        if (sel != null) {
            item {
                val (u, suspended) = sel
                val cs = MaterialTheme.colorScheme
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.name.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (u.disabled) Tag(tr("Account off", "অ্যাকাউন্ট বন্ধ"), cs.errorContainer, cs.onErrorContainer)
                        }
                        IconLine(R.drawable.ic_mail, u.email)
                        IconLine(R.drawable.ic_call, num(u.phone))
                        IconLine(R.drawable.ic_location_on, u.district)
                        if (suspended != null) {
                            IconLine(R.drawable.ic_stethoscope, if (suspended) tr("Professional listing paused", "পেশাজীবীর তালিকা সাময়িক বন্ধ") else tr("Professional listing on", "পেশাজীবীর তালিকা চালু"))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (suspended == true) SmallAction(tr("Resume listing", "তালিকা আবার চালু"), null, { confirm = "provider_unsuspend" })
                            if (suspended == false) SmallAction(tr("Pause listing", "তালিকা সাময়িক বন্ধ"), null, { confirm = "provider_suspend" })
                            if (u.disabled) SmallAction(tr("Turn account on", "অ্যাকাউন্ট চালু"), null, { confirm = "enable" })
                            else SmallAction(tr("Turn account off", "অ্যাকাউন্ট বন্ধ"), null, { confirm = "disable" })
                        }
                    }
                }
            }
        }
        results?.let { list ->
            if (list.isEmpty()) item { Text(tr("No one found", "কাউকে পাওয়া যায়নি"), style = MaterialTheme.typography.bodyMedium) }
            items(list, key = { it.uid }) { u ->
                Card(onClick = { open(u.uid) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardAvatar(null, R.drawable.ic_person_outline, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOf(u.email, num(u.phone)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    confirm?.let { op ->
        val u = selected?.first ?: return@let
        var reason by remember(op) { mutableStateOf("") }
        val title = when (op) {
            "provider_suspend" -> tr("Pause this professional's listing?", "এই পেশাজীবীর তালিকা সাময়িক বন্ধ করবেন?")
            "provider_unsuspend" -> tr("Resume the listing?", "তালিকা আবার চালু করবেন?")
            "disable" -> tr("Turn this account off?", "এই অ্যাকাউন্ট বন্ধ করবেন?")
            else -> tr("Turn this account on?", "এই অ্যাকাউন্ট চালু করবেন?")
        }
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(u.name.ifBlank { u.email }, fontWeight = FontWeight.SemiBold)
                    if (op == "provider_suspend") Text(tr("Chambers and home care leave the list and upcoming appointments are cancelled.", "চেম্বার আর বাসায় সেবা তালিকা থেকে সরবে, সামনের অ্যাপয়েন্টমেন্ট বাতিল হবে।"), style = MaterialTheme.typography.bodyMedium)
                    if (op == "disable") {
                        Text(tr("They can't log in until you turn it on again.", "আবার চালু না করা পর্যন্ত লগইন করতে পারবেন না।"), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(reason, { reason = it.take(200) }, label = { Text(tr("Reason (they will see it)", "কারণ (তিনি দেখবেন)")) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMsg = null
                            runCatching { AdminApi.userAction(u.uid, op, reason.trim()); AdminApi.user(u.uid) }
                                .onSuccess { selected = it; infoMsg = tr("Done", "করা হয়েছে") }
                                .onFailure { errorMsg = it.message }
                            busy = false
                            confirm = null
                        }
                    },
                    enabled = !busy && (op != "disable" || reason.isNotBlank()),
                ) { ButtonContent(busy, tr("Yes", "হ্যাঁ")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}
