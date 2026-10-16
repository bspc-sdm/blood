@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// মডেল ও সার্ভার
// ------------------------------------------------------------

data class Question(
    val id: String,
    val text: String,
    /** null = নাম ছাড়া (anonymous) */
    val authorName: String?,
    val mine: Boolean,
    val createdAt: Long,
    val answerCount: Int,
    val edited: Boolean = false,
    /** নিজের প্রশ্নে: নাম লুকানো আছে কি না */
    val anonymous: Boolean = false,
)

data class Answer(
    val id: String,
    val text: String,
    val authorName: String,
    val badge: String,
    val mine: Boolean,
    val createdAt: Long,
    val edited: Boolean = false,
)

data class Tip(
    val id: String,
    val title: String,
    val body: String,
    val authorName: String,
    val badge: String,
    val mine: Boolean,
    val createdAt: Long,
    val edited: Boolean = false,
)

/** নিজের ব্যাজ: type ("doctor" …), status ("pending" / "approved" / "rejected") */
data class BadgeState(
    val type: String,
    val status: String,
    val regNo: String = "",
    val workplace: String = "",
    /** অনুমোদিত ব্যাজের তথ্য সংশোধনের অনুরোধ যাচাই চলছে */
    val changePending: Boolean = false,
    val statusReason: String = "",
    /** সনদের ছবি বাকি / অ্যাডমিন ছবি চেয়েছেন */
    val docsMissing: Boolean = false,
    val docsRequested: Boolean = false,
    val docsNote: String = "",
)

data class CommunityMe(
    val badge: BadgeState?,
    val isAdmin: Boolean,
    val isOwner: Boolean = false,
    /** SMS দিয়ে ফোন যাচাই চালু আছে কি না */
    val phoneCheck: Boolean = false,
    val phoneVerified: Boolean = true,
    /** না-পড়া নোটিফিকেশন */
    val unread: Int = 0,
) {
    /** অনুমোদিত ব্যাজ থাকলে তার ধরন */
    val approvedBadge: String? get() = badge?.takeIf { it.status == "approved" }?.type
}

data class BadgeRequest(
    val uid: String,
    val type: String,
    val regNo: String,
    val workplace: String,
    val name: String,
    val email: String,
)

object Community {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action).put("lang", AppPrefs.lang.value.code))

    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun question(o: JSONObject) = Question(
        id = o.optString("id"),
        text = o.optString("text"),
        authorName = o.str("authorName"),
        mine = o.optBoolean("mine"),
        createdAt = o.optLong("createdAt"),
        answerCount = o.optInt("answerCount"),
        edited = o.optLong("editedAt") > 0,
        anonymous = o.optBoolean("anonymous"),
    )

    suspend fun me(): CommunityMe {
        val o = call("me")
        val b = o.optJSONObject("badge")
        return CommunityMe(
            b?.let {
                BadgeState(
                    it.optString("type"), it.optString("status"), it.optString("regNo"), it.optString("workplace"), it.optBoolean("changePending"),
                    it.optString("statusReason"), it.optBoolean("docsMissing"), it.optBoolean("docsRequested"), it.optString("docsRequestNote"),
                )
            }, o.optBoolean("isAdmin"), o.optBoolean("isOwner"),
            o.optBoolean("phoneCheck"), o.optBoolean("phoneVerified", true), o.optInt("unread"),
        )
    }

    suspend fun questions(before: Long? = null): List<Question> =
        call("questions", JSONObject().apply { if (before != null) put("before", before) })
            .optJSONArray("items")?.objects().orEmpty().map(::question)

    suspend fun question(id: String): Pair<Question, List<Answer>> {
        val o = call("question", JSONObject().put("id", id))
        val answers = o.optJSONArray("answers")?.objects().orEmpty().map {
            Answer(
                it.optString("id"), it.optString("text"), it.optString("authorName"),
                it.optString("badge"), it.optBoolean("mine"), it.optLong("createdAt"), it.optLong("editedAt") > 0,
            )
        }
        return question(o.getJSONObject("question")) to answers
    }

    suspend fun ask(text: String, anonymous: Boolean): String =
        call("ask", JSONObject().put("text", text).put("anonymous", anonymous)).optString("id")

    suspend fun answer(id: String, text: String) {
        call("answer", JSONObject().put("id", id).put("text", text))
    }

    suspend fun deleteQuestion(id: String) {
        call("delete_question", JSONObject().put("id", id))
    }

    suspend fun deleteAnswer(id: String, answerId: String) {
        call("delete_answer", JSONObject().put("id", id).put("answerId", answerId))
    }

    suspend fun tips(before: Long? = null): List<Tip> =
        call("tips", JSONObject().apply { if (before != null) put("before", before) })
            .optJSONArray("items")?.objects().orEmpty().map {
                Tip(
                    it.optString("id"), it.optString("title"), it.optString("body"), it.optString("authorName"),
                    it.optString("badge"), it.optBoolean("mine"), it.optLong("createdAt"), it.optLong("editedAt") > 0,
                )
            }

    suspend fun editQuestion(id: String, text: String, anonymous: Boolean? = null) {
        call("edit_question", JSONObject().put("id", id).put("text", text).apply { if (anonymous != null) put("anonymous", anonymous) })
    }

    suspend fun editAnswer(id: String, answerId: String, text: String) {
        call("edit_answer", JSONObject().put("id", id).put("answerId", answerId).put("text", text))
    }

    suspend fun editTip(id: String, title: String, body: String) {
        call("edit_tip", JSONObject().put("id", id).put("title", title).put("body", body))
    }

    suspend fun postTip(title: String, body: String) {
        call("post_tip", JSONObject().put("title", title).put("body", body))
    }

    suspend fun deleteTip(id: String) {
        call("delete_tip", JSONObject().put("id", id))
    }

    suspend fun applyBadge(type: String, regNo: String, workplace: String, docs: Map<String, String> = emptyMap()) {
        call("badge_apply", JSONObject().put("type", type).put("regNo", regNo).put("workplace", workplace).put("docs", VerifyDocs.json(docs)))
    }

    suspend fun adminBadges(): List<BadgeRequest> =
        // তথ্য সংশোধনের অনুরোধ শুধু ওয়েব প্যানেলে (এখানে "বাতিল" চাপলে ব্যাজই চলে যেত)
        call("admin_badges").optJSONArray("items")?.objects().orEmpty().filter { it.isNull("change") || !it.has("change") }.map {
            BadgeRequest(
                it.optString("uid"), it.optString("type"), it.optString("regNo"),
                it.optString("workplace"), it.optString("name"), it.optString("email"),
            )
        }

    suspend fun resolveBadge(uid: String, approve: Boolean) {
        call("admin_badge_resolve", JSONObject().put("uid", uid).put("approve", approve))
    }
}

/** অ্যাডমিনের কাছে রিপোর্ট হওয়া পোস্ট */
data class ReportedItem(
    val targetPath: String,
    val kind: String,
    val count: Int,
    val text: String,
    val authorName: String,
    /** হোম ভিজিটে যাকে রিপোর্ট করা হয়েছে */
    val reportedUid: String? = null,
    val reportedRole: String? = null,
)

object Moderation {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    suspend fun report(kind: String, id: String, answerId: String? = null, reason: String? = null) {
        call(
            "report",
            JSONObject().put("kind", kind).put("id", id).apply {
                if (answerId != null) put("answerId", answerId)
                if (reason != null) put("reason", reason)
            },
        )
    }

    suspend fun adminReports(): List<ReportedItem> {
        val arr = call("admin_reports").optJSONArray("items") ?: JSONArray()
        // ডাক্তারের চেম্বারের রিপোর্ট শুধু ওয়েব প্যানেলে (আলাদা ব্যবস্থা লাগে)
        return (0 until arr.length()).filter { arr.getJSONObject(it).optString("kind") != "provider" }.map {
            val o = arr.getJSONObject(it)
            ReportedItem(
                o.optString("targetPath"), o.optString("kind"), o.optInt("count"), o.optString("text"), o.optString("authorName"),
                if (o.isNull("reportedUid")) null else o.optString("reportedUid").ifBlank { null },
                if (o.isNull("reportedRole")) null else o.optString("reportedRole").ifBlank { null },
            )
        }
    }

    suspend fun resolve(targetPath: String, remove: Boolean, reportedUid: String? = null) {
        call(
            "admin_report_resolve",
            JSONObject().put("targetPath", targetPath).put("remove", remove).apply { if (reportedUid != null) put("reportedUid", reportedUid) },
        )
    }

    /** মালিক: অ্যাডমিনের তালিকা (মালিকেরা + যোগ করা অ্যাডমিন) */
    suspend fun admins(): Pair<List<String>, List<String>> {
        val o = call("admin_list")
        return o.optJSONArray("owners").strings() to (o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("email") } } ?: emptyList())
    }

    suspend fun addAdmin(email: String) {
        call("admin_add", JSONObject().put("email", email))
    }

    suspend fun removeAdmin(email: String) {
        call("admin_remove", JSONObject().put("email", email))
    }

    /** অ্যাকাউন্ট আর সব তথ্য মোছা */
    suspend fun deleteAccount() {
        call("delete_account")
    }
}

// ------------------------------------------------------------
// ব্যাজ
// ------------------------------------------------------------

object Badges {
    val ALL = listOf("doctor", "nurse", "nutritionist", "trainer", "expert")

    fun label(type: String): String = when (type) {
        "doctor" -> tr("Doctor", "ডাক্তার")
        "nurse" -> tr("Nurse", "নার্স")
        "nutritionist" -> tr("Nutritionist", "পুষ্টিবিদ")
        "trainer" -> tr("Fitness trainer", "ফিটনেস ট্রেইনার")
        else -> tr("Health Expert", "স্বাস্থ্য বিশেষজ্ঞ")
    }

    @DrawableRes
    fun icon(type: String): Int = when (type) {
        "doctor" -> R.drawable.ic_stethoscope
        "nurse" -> R.drawable.ic_medical_services
        "nutritionist" -> R.drawable.ic_restaurant
        "trainer" -> R.drawable.ic_directions_run
        else -> R.drawable.ic_health_and_safety_filled
    }
}

/** নামের পাশে ব্যাজ */
@Composable
fun BadgeChip(type: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_verified, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(Badges.label(type), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** "৫ মিনিট আগে" ধরনের সময় */
fun timeAgo(millis: Long): String {
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0)
    val min = diff / 60_000
    return when {
        min < 1 -> tr("just now", "এইমাত্র")
        min < 60 -> tr("${num(min)}m ago", "${num(min)} মিনিট আগে")
        min < 60 * 24 -> tr("${num(min / 60)}h ago", "${num(min / 60)} ঘণ্টা আগে")
        min < 60 * 24 * 7 -> tr("${num(min / 1440)}d ago", "${num(min / 1440)} দিন আগে")
        else -> Rules.formatDate(millis)
    }
}

@Composable
private fun AuthorLine(name: String?, badge: String?, createdAt: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name ?: tr("Anonymous", "নাম প্রকাশে অনিচ্ছুক"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            BadgeChip(badge)
        }
        Spacer(Modifier.width(8.dp))
        Text(timeAgo(createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------
// Health Discussion
// ------------------------------------------------------------

@Composable
fun HealthDiscussionTab(refreshKey: Int, onOpenQuestion: (String) -> Unit, onAsk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Question>>(emptyList()) }

    LaunchedEffect(reload, refreshKey) {
        loading = true
        errorMsg = null
        runCatching { Community.questions() }
            .onSuccess { list = it; hasMore = it.size >= 30 }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Box(Modifier.fillMaxSize().nestedScroll(FabScroll.connection)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Health Discussion", "স্বাস্থ্য আলোচনা"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                }
            }
            when {
                loading -> item { LoadingBox() }
                errorMsg != null -> item { ErrorText(errorMsg) }
                list.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            R.drawable.ic_forum_outline,
                            tr("No questions yet", "এখনো কোনো প্রশ্ন নেই"),
                            tr("Ask a health question and verified doctors will answer.", "স্বাস্থ্য নিয়ে প্রশ্ন করুন, যাচাই করা ডাক্তাররা উত্তর দেবেন।"),
                        )
                    }
                }
                else -> {
                    items(list, key = { it.id }) { q ->
                        Card(
                            onClick = { onOpenQuestion(q.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AuthorLine(q.authorName, null, q.createdAt)
                                Text(q.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                IconLine(
                                    R.drawable.ic_forum_outline,
                                    if (q.answerCount == 0) tr("No answers yet", "এখনো উত্তর নেই")
                                    else tr("${num(q.answerCount)} answer${if (q.answerCount > 1) "s" else ""}", "${num(q.answerCount)}টি উত্তর"),
                                    if (q.answerCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (hasMore) {
                        item {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        loadingMore = true
                                        runCatching { Community.questions(list.last().createdAt) }
                                            .onSuccess { more ->
                                                val ids = list.map { it.id }.toSet()
                                                list = list + more.filter { it.id !in ids }
                                                hasMore = more.size >= 30
                                            }
                                        loadingMore = false
                                    }
                                },
                                enabled = !loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("Load more", "আরও দেখুন")) }
                        }
                    }
                }
            }
        }
        SmallFab(tr("Question", "প্রশ্ন"), R.drawable.ic_edit_note, onAsk, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
fun AskQuestionScreen(onPosted: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var anonymous by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar(tr("Ask a question", "প্রশ্ন করুন"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1000) },
                label = { Text(tr("Your question", "আপনার প্রশ্ন")) },
                minLines = 5,
                // ৯৯৯-এর বড় কার্ডের বদলে ঘরের নিচে ছোট এক লাইন
                supportingText = {
                    Row {
                        Text(tr("Answers are general advice, not a diagnosis.", "উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।"), Modifier.weight(1f))
                        Text("${num(text.length)}/${num(1000)}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = anonymous, onCheckedChange = { anonymous = it })
                Text(tr("Hide my name", "আমার নাম দেখাবে না"))
            }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    if (text.isBlank()) {
                        errorMsg = tr("Write your question", "প্রশ্ন লিখুন")
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { Community.ask(text.trim(), anonymous) }
                            .onSuccess { onPosted(it) }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Post", "পোস্ট করুন"), R.drawable.ic_send) }
        }
    }
}

@Composable
fun QuestionDetailScreen(id: String, me: CommunityMe?, onChanged: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<Pair<Question, List<Answer>>?>(null) }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** কী বদলানো হচ্ছে: প্রশ্ন (null) বা উত্তর (answer) */
    var editingQuestion by remember { mutableStateOf(false) }
    var editingAnswer by remember { mutableStateOf<Answer?>(null) }
    var deletingAnswer by remember { mutableStateOf<Answer?>(null) }
    /** কী রিপোর্ট হচ্ছে: ("question", null) বা ("answer", answerId) */
    var reportTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { Community.question(id) }
            .onSuccess { data = it; errorMsg = null }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    val canAnswer = me?.approvedBadge != null
    val q = data?.first

    Scaffold(
        topBar = {
            BackTopBar(tr("Question", "প্রশ্ন"), onBack) {
                if (q != null && !q.mine) {
                    IconButton(onClick = { reportTarget = "question" to null }) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
                }
                if (q != null && q.mine) {
                    IconButton(onClick = { editingQuestion = true }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
                }
                if (q != null && (q.mine || me?.isAdmin == true)) {
                    IconButton(onClick = { confirmDelete = true }) { AppIcon(R.drawable.ic_delete, tr("Delete", "মুছুন")) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val d = data
            when {
                loading && d == null -> item { LoadingBox() }
                d == null -> item { ErrorText(errorMsg) }
                else -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthorLine(d.first.authorName, null, d.first.createdAt)
                            Text(d.first.text, style = MaterialTheme.typography.titleMedium)
                            if (d.first.edited) EditedNote()
                            // নিজের প্রশ্নে পরে নাম লুকানো/দেখানো
                            if (d.first.mine) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("Hide my name", "আমার নাম দেখাবে না"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = d.first.anonymous, onCheckedChange = { on ->
                                        scope.launch {
                                            runCatching { Community.editQuestion(id, d.first.text, on) }
                                                .onSuccess { onChanged(); reload++ }
                                                .onFailure { errorMsg = it.message }
                                        }
                                    })
                                }
                            }
                        }
                    }
                    item {
                        SectionTitle(
                            if (d.second.isEmpty()) tr("No answers yet", "এখনো উত্তর নেই")
                            else tr("Answers", "উত্তর") + " (${num(d.second.size)})"
                        )
                    }
                    items(d.second, key = { it.id }) { a ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AuthorLine(a.authorName, a.badge, a.createdAt)
                                Text(a.text, style = MaterialTheme.typography.bodyLarge)
                                if (a.edited) EditedNote()
                                Row {
                                    if (!a.mine) {
                                        TextButton(onClick = { reportTarget = "answer" to a.id }) { Text(tr("Report", "রিপোর্ট")) }
                                    }
                                    if (a.mine) {
                                        TextButton(onClick = { editingAnswer = a }) { Text(tr("Edit", "বদলান")) }
                                    }
                                    if (a.mine || me?.isAdmin == true) {
                                        TextButton(onClick = { deletingAnswer = a }) { Text(tr("Delete", "মুছুন")) }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        if (canAnswer) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = reply,
                                    onValueChange = { reply = it.take(2000) },
                                    label = { Text(tr("Write an answer", "উত্তর লিখুন")) },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        if (reply.isBlank()) return@Button
                                        scope.launch {
                                            busy = true
                                            runCatching { Community.answer(id, reply.trim()) }
                                                .onSuccess { reply = ""; errorMsg = null; onChanged(); reload++ }
                                                .onFailure { errorMsg = it.message }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { ButtonContent(busy, tr("Answer", "উত্তর দিন"), R.drawable.ic_send) }
                            }
                        } else {
                            Text(
                                tr("Only verified health professionals can answer.", "শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা উত্তর দিতে পারেন।"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ErrorText(errorMsg)
                        InfoText(infoMsg)
                    }
                }
            }
        }
    }

    reportTarget?.let { (kind, answerId) ->
        ReportDialog(
            onDismiss = { reportTarget = null },
            onConfirm = {
                reportTarget = null
                scope.launch {
                    runCatching { Moderation.report(kind, id, answerId) }
                        .onSuccess { infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।") }
                        .onFailure { errorMsg = it.message }
                }
            },
        )
    }

    if (editingQuestion && q != null) {
        EditTextDialog(
            title = tr("Edit question", "প্রশ্ন বদলান"),
            initial = q.text,
            maxLen = 1000,
            onDismiss = { editingQuestion = false },
            onSave = { text, _ ->
                Community.editQuestion(id, text)
                editingQuestion = false
                onChanged()
                reload++
            },
        )
    }
    editingAnswer?.let { a ->
        EditTextDialog(
            title = tr("Edit answer", "উত্তর বদলান"),
            initial = a.text,
            maxLen = 2000,
            onDismiss = { editingAnswer = null },
            onSave = { text, _ ->
                Community.editAnswer(id, a.id, text)
                editingAnswer = null
                reload++
            },
        )
    }

    deletingAnswer?.let { a ->
        AlertDialog(
            onDismissRequest = { deletingAnswer = null },
            title = { Text(tr("Delete this answer?", "উত্তরটা মুছবেন?")) },
            text = { if (!a.mine) Text(tr("You're deleting someone else's answer as an admin. This is recorded.", "অ্যাডমিন হিসেবে অন্যের উত্তর মুছছেন। এটা রেকর্ডে থাকবে।")) },
            confirmButton = {
                TextButton(onClick = {
                    deletingAnswer = null
                    scope.launch {
                        runCatching { Community.deleteAnswer(id, a.id) }
                            .onSuccess { onChanged(); reload++ }
                            .onFailure { errorMsg = it.message }
                    }
                }) { Text(tr("Delete", "মুছুন")) }
            },
            dismissButton = { TextButton(onClick = { deletingAnswer = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this question?", "প্রশ্নটা মুছবেন?")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { Community.deleteQuestion(id) }
                            .onSuccess { onChanged(); onBack() }
                            .onFailure { errorMsg = it.message }
                    }
                }) { Text(tr("Delete", "মুছুন")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

// ------------------------------------------------------------
// Health Tips
// ------------------------------------------------------------

@Composable
fun HealthTipsTab(refreshKey: Int, me: CommunityMe?, onOpenTip: (Tip) -> Unit, onPostTip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Tip>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(reload, refreshKey) {
        loading = true
        errorMsg = null
        runCatching { Community.tips() }
            .onSuccess { list = it; hasMore = it.size >= 30 }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Box(Modifier.fillMaxSize().nestedScroll(FabScroll.connection)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Health Tips", "হেলথ টিপস"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                }
            }
            when {
                loading -> item { LoadingBox() }
                errorMsg != null -> item { ErrorText(errorMsg) }
                list.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            R.drawable.ic_lightbulb_outline,
                            tr("No tips yet", "এখনো কোনো টিপস নেই"),
                            tr("Doctors, nurses and health experts will share tips here.", "ডাক্তার, নার্স আর স্বাস্থ্য বিশেষজ্ঞরা এখানে টিপস দেবেন।"),
                        )
                    }
                }
                else -> {
                    items(list, key = { it.id }) { t ->
                        Card(
                            onClick = { onOpenTip(t) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AuthorLine(t.authorName, t.badge, t.createdAt)
                                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    t.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (hasMore) {
                        item {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        loadingMore = true
                                        runCatching { Community.tips(list.last().createdAt) }
                                            .onSuccess { more ->
                                                val ids = list.map { it.id }.toSet()
                                                list = list + more.filter { it.id !in ids }
                                                hasMore = more.size >= 30
                                            }
                                        loadingMore = false
                                    }
                                },
                                enabled = !loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("Load more", "আরও দেখুন")) }
                        }
                    }
                }
            }
        }
        if (me?.approvedBadge != null) {
            SmallFab(tr("Tip", "টিপস"), R.drawable.ic_edit_note, onPostTip, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

@Composable
fun TipDetailScreen(tip0: Tip, me: CommunityMe?, onChanged: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // বদলালে এখানেই নতুনটা দেখায়
    var tip by remember(tip0.id) { mutableStateOf(tip0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(tr("Health Tips", "হেলথ টিপস"), onBack) {
                if (!tip.mine) {
                    IconButton(onClick = { confirmReport = true }) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
                }
                if (tip.mine) {
                    IconButton(onClick = { editing = true }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
                }
                if (tip.mine || me?.isAdmin == true) {
                    IconButton(onClick = { confirmDelete = true }) { AppIcon(R.drawable.ic_delete, tr("Delete", "মুছুন")) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tip.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AuthorLine(tip.authorName, tip.badge, tip.createdAt)
            Spacer(Modifier.height(4.dp))
            Text(tip.body, style = MaterialTheme.typography.bodyLarge)
            if (tip.edited) EditedNote()
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }
    }

    if (confirmReport) {
        ReportDialog(
            onDismiss = { confirmReport = false },
            onConfirm = {
                confirmReport = false
                scope.launch {
                    runCatching { Moderation.report("tip", tip.id) }
                        .onSuccess { infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট পাঠানো হয়েছে। অ্যাডমিন দেখবেন।") }
                        .onFailure { errorMsg = it.message }
                }
            },
        )
    }

    if (editing) {
        EditTextDialog(
            title = tr("Edit tip", "টিপস বদলান"),
            initial = tip.body,
            maxLen = 3000,
            initialTitle = tip.title,
            onDismiss = { editing = false },
            onSave = { body, title ->
                Community.editTip(tip.id, title, body)
                tip = tip.copy(title = title, body = body, edited = true)
                editing = false
                onChanged()
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this tip?", "টিপসটা মুছবেন?")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { Community.deleteTip(tip.id) }
                            .onSuccess { onChanged(); onBack() }
                            .onFailure { errorMsg = it.message }
                    }
                }) { Text(tr("Delete", "মুছুন")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

@Composable
fun PostTipScreen(onPosted: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar(tr("Post a tip", "টিপস দিন"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                label = { Text(tr("Title", "শিরোনাম")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(3000) },
                label = { Text(tr("Tip", "টিপস")) },
                minLines = 8,
                supportingText = { Text("${num(body.length)}/${num(3000)}") },
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(errorMsg)
            Button(
                onClick = {
                    if (title.isBlank() || body.isBlank()) {
                        errorMsg = tr("Add a title and the tip", "শিরোনাম আর টিপস দুটোই লিখুন")
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { Community.postTip(title.trim(), body.trim()) }
                            .onSuccess { onPosted() }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Post", "পোস্ট করুন"), R.drawable.ic_send) }
        }
    }
}

// ------------------------------------------------------------
// ব্যাজের আবেদন (প্রোফাইল থেকে)
// ------------------------------------------------------------

@Composable
fun BadgeApplyDialog(onDismiss: () -> Unit, onApplied: (String) -> Unit, current: BadgeState? = null) {
    val scope = rememberCoroutineScope()
    // current = অনুমোদিত ব্যাজ: তথ্য সংশোধনের অনুরোধ (ব্যাজ আর তালিকা আগের মতো থাকে)
    val correction = current?.status == "approved"
    var type by remember { mutableStateOf(current?.type ?: "doctor") }
    var regNo by remember { mutableStateOf(current?.regNo ?: "") }
    var workplace by remember { mutableStateOf(current?.workplace ?: "") }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (correction) tr("Correct badge details", "ব্যাজের তথ্য সংশোধন") else tr("Professional badge", "পেশাদার ব্যাজ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (correction) {
                    Text(
                        tr(
                            "Fix your registration number or workplace. To fix your name, change it in Edit profile first, then send this. You stay listed while an admin checks.",
                            "রেজিস্ট্রেশন নম্বর বা কর্মস্থল ঠিক করুন। নাম ঠিক করতে আগে \"প্রোফাইল এডিট\"-এ নাম বদলে তারপর এটা পাঠান। অ্যাডমিন দেখা পর্যন্ত আপনি আগের মতোই তালিকায় থাকবেন।",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(tr("Profession", "পেশা"), style = MaterialTheme.typography.labelLarge)
                Badges.ALL.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { b ->
                            FilterChip(
                                selected = type == b,
                                onClick = { type = b },
                                // সংশোধনে পেশা বদলানো যায় না
                                enabled = !correction || type == b,
                                label = { Text(Badges.label(b)) },
                                leadingIcon = { AppIcon(Badges.icon(b), null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = regNo,
                    onValueChange = { regNo = it.take(60) },
                    label = { Text(tr("Registration / licence no.", "রেজিস্ট্রেশন / লাইসেন্স নম্বর")) },
                    placeholder = { Text(if (type == "doctor") "BMDC A-12345" else "") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = workplace,
                    onValueChange = { workplace = it.take(120) },
                    label = { Text(tr("Workplace", "কর্মস্থল")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DocPhotosField(VerifyDocs.badge(type), docs, { docs = it },
                    if (correction) tr("Add a photo if your registration number changed.", "রেজিস্ট্রেশন নম্বর বদলালে সনদের ছবি দিন।") else null)
                Text(
                    tr("An admin will verify this before the badge appears.", "অ্যাডমিন যাচাই করার পর ব্যাজ দেখা যাবে।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!correction) VerifyDocs.missing(VerifyDocs.badge(type), docs)?.let { errorMsg = it; return@TextButton }
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { Community.applyBadge(type, regNo.trim(), workplace.trim(), docs) }
                            .onSuccess { onApplied(type) }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(if (correction) tr("Send", "পাঠান") else tr("Apply", "আবেদন করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** জরুরি অবস্থায় এখানে নয় — স্থায়ী সতর্কবার্তা */
@Composable
fun EmergencyNotice(key: String = "health") {
    // ✕ চাপলে এই পাতায় আর আসে না
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(Dismissed.isOn(context, "999_$key")) }
    if (hidden) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_info, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                tr(
                    "Emergency? Call 999 now. Answers here are general guidance, not a diagnosis.",
                    "জরুরি অবস্থায় এখনই ৯৯৯-এ ফোন করুন। এখানের উত্তর সাধারণ পরামর্শ, রোগ নির্ণয় নয়।",
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { Dismissed.set(context, "999_$key"); hidden = true }, modifier = Modifier.size(32.dp)) {
                AppIcon(R.drawable.ic_close, tr("Hide", "সরান"), Modifier.size(18.dp), MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
fun ReportDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Report this?", "রিপোর্ট করবেন?")) },
        text = { Text(tr("Report if it's wrong, harmful or abusive. An admin will review it.", "ভুল, ক্ষতিকর বা আপত্তিকর মনে হলে রিপোর্ট করুন। অ্যাডমিন দেখবেন।")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Report", "রিপোর্ট করুন")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

@Composable
private fun EditedNote() {
    Text(tr("Edited", "সম্পাদিত"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** নিজের লেখা বদলানো (প্রশ্ন, উত্তর; টিপস হলে শিরোনামও) */
@Composable
private fun EditTextDialog(
    title: String,
    initial: String,
    maxLen: Int,
    onDismiss: () -> Unit,
    onSave: suspend (String, String) -> Unit,
    initialTitle: String? = null,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var head by remember { mutableStateOf(initialTitle.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initialTitle != null) {
                    OutlinedTextField(head, { head = it.take(120) }, label = { Text(tr("Title", "শিরোনাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(text, { text = it.take(maxLen) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank() && (initialTitle == null || head.isNotBlank()), onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSave(text.trim(), head.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}
