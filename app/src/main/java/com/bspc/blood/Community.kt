@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
)

data class Answer(
    val id: String,
    val text: String,
    val authorName: String,
    val badge: String,
    val mine: Boolean,
    val createdAt: Long,
)

data class Tip(
    val id: String,
    val title: String,
    val body: String,
    val authorName: String,
    val badge: String,
    val mine: Boolean,
    val createdAt: Long,
)

/** নিজের ব্যাজ: type ("doctor" …), status ("pending" / "approved" / "rejected") */
data class BadgeState(val type: String, val status: String)

data class CommunityMe(val badge: BadgeState?, val isAdmin: Boolean) {
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
    )

    suspend fun me(): CommunityMe {
        val o = call("me")
        val b = o.optJSONObject("badge")
        return CommunityMe(b?.let { BadgeState(it.optString("type"), it.optString("status")) }, o.optBoolean("isAdmin"))
    }

    suspend fun questions(before: Long? = null): List<Question> =
        call("questions", JSONObject().apply { if (before != null) put("before", before) })
            .optJSONArray("items")?.objects().orEmpty().map(::question)

    suspend fun question(id: String): Pair<Question, List<Answer>> {
        val o = call("question", JSONObject().put("id", id))
        val answers = o.optJSONArray("answers")?.objects().orEmpty().map {
            Answer(
                it.optString("id"), it.optString("text"), it.optString("authorName"),
                it.optString("badge"), it.optBoolean("mine"), it.optLong("createdAt"),
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
                    it.optString("badge"), it.optBoolean("mine"), it.optLong("createdAt"),
                )
            }

    suspend fun postTip(title: String, body: String) {
        call("post_tip", JSONObject().put("title", title).put("body", body))
    }

    suspend fun deleteTip(id: String) {
        call("delete_tip", JSONObject().put("id", id))
    }

    suspend fun applyBadge(type: String, regNo: String, workplace: String) {
        call("badge_apply", JSONObject().put("type", type).put("regNo", regNo).put("workplace", workplace))
    }

    suspend fun adminBadges(): List<BadgeRequest> =
        call("admin_badges").optJSONArray("items")?.objects().orEmpty().map {
            BadgeRequest(
                it.optString("uid"), it.optString("type"), it.optString("regNo"),
                it.optString("workplace"), it.optString("name"), it.optString("email"),
            )
        }

    suspend fun resolveBadge(uid: String, approve: Boolean) {
        call("admin_badge_resolve", JSONObject().put("uid", uid).put("approve", approve))
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
        "trainer" -> tr("Health Trainer", "হেলথ ট্রেইনার")
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
fun HealthDiscussionTab(onOpenQuestion: (String) -> Unit, onAsk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Question>>(emptyList()) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching { Community.questions() }
            .onSuccess { list = it; hasMore = it.size >= 30 }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
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
                                            .onSuccess { more -> list = list + more; hasMore = more.size >= 30 }
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
        ExtendedFloatingActionButton(
            onClick = onAsk,
            icon = { AppIcon(R.drawable.ic_edit_note, null) },
            text = { Text(tr("Ask a question", "প্রশ্ন করুন")) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
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
                supportingText = { Text("${num(text.length)}/${num(1000)}") },
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
fun QuestionDetailScreen(id: String, me: CommunityMe?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<Pair<Question, List<Answer>>?>(null) }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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
                                if (a.mine || me?.isAdmin == true) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { Community.deleteAnswer(id, a.id) }
                                                .onFailure { errorMsg = it.message }
                                            reload++
                                        }
                                    }) { Text(tr("Delete", "মুছুন")) }
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
                                                .onSuccess { reply = ""; errorMsg = null; reload++ }
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
                    }
                }
            }
        }
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
                            .onSuccess { onBack() }
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
fun HealthTipsTab(me: CommunityMe?, onOpenTip: (Tip) -> Unit, onPostTip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Tip>>(emptyList()) }

    LaunchedEffect(reload) {
        loading = true
        errorMsg = null
        runCatching { Community.tips() }
            .onSuccess { list = it; hasMore = it.size >= 30 }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
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
                                        runCatching { Community.tips(list.last().createdAt) }
                                            .onSuccess { more -> list = list + more; hasMore = more.size >= 30 }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("Load more", "আরও দেখুন")) }
                        }
                    }
                }
            }
        }
        if (me?.approvedBadge != null) {
            ExtendedFloatingActionButton(
                onClick = onPostTip,
                icon = { AppIcon(R.drawable.ic_edit_note, null) },
                text = { Text(tr("Post a tip", "টিপস দিন")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
fun TipDetailScreen(tip: Tip, me: CommunityMe?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(tr("Health Tips", "হেলথ টিপস"), onBack) {
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
            ErrorText(errorMsg)
        }
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
                            .onSuccess { onBack() }
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
fun BadgeApplyDialog(onDismiss: () -> Unit, onApplied: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("doctor") }
    var regNo by remember { mutableStateOf("") }
    var workplace by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Professional badge", "পেশাদার ব্যাজ")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Profession", "পেশা"), style = MaterialTheme.typography.labelLarge)
                Badges.ALL.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { b ->
                            FilterChip(
                                selected = type == b,
                                onClick = { type = b },
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
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { Community.applyBadge(type, regNo.trim(), workplace.trim()) }
                            .onSuccess { onApplied(type) }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(tr("Apply", "আবেদন করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}
