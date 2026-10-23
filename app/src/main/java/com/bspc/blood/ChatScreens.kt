package com.bspc.blood

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// মেসেজের তালিকা আর একটা কথোপকথনের পাতা
// ------------------------------------------------------------

/** উপরের বারের "মেসেজ" বাটন — না-পড়া থাকলে সংখ্যা */
@Composable
fun ChatButton(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("chat_inbox_button")) {
        BadgedBox(badge = {
            if (unread > 0) Badge(Modifier.testTag("chat_unread_badge")) { Text(if (unread > 99) "99+" else num(unread)) }
        }) {
            AppIcon(if (unread > 0) R.drawable.ic_chat_bubble else R.drawable.ic_chat_bubble_outline, tr("Messages", "মেসেজ"))
        }
    }
}

@Composable
private fun PeerAvatar(name: String, photo: String, size: Int = 44) {
    val img = remember(photo) { Photo.decodeAvatar(photo) }
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.trim().take(1).ifBlank { "?" }, color = cs.onSecondaryContainer, fontWeight = FontWeight.Bold)
    }
}

/**
 * মেসেজের তালিকা (উপরের বার / প্রোফাইল → "মেসেজ"): কার সাথে, কোন সূত্রে ("রক্তের অনুরোধ"…), শেষ মেসেজ, কখন, না-পড়ার সংখ্যা।
 */
@Composable
fun ChatInboxScreen(onBack: () -> Unit, onOpen: (ChatThread) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ChatThread>?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        runCatching { ChatApi.threads() }
            .onSuccess { (l, n) -> items = l; next = n; errorMsg = null }
            .onFailure { errorMsg = it.message }
        ChatUnread.refresh(force = true)
    }
    // নতুন মেসেজ এলে (পুশ) তালিকা নতুন করে
    LaunchedEffect(Unit) {
        ChatEvents.events.collect { e -> if (e is ChatEvents.Event.Incoming || e is ChatEvents.Event.Sent) reload++ }
    }
    OnResume { reload++ }

    Scaffold(topBar = {
        BackTopBar(tr("Messages", "মেসেজ"), onBack) {
            IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("chat_inbox"), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(errorMsg) } }
            val l = items
            when {
                l == null -> if (errorMsg == null) item { LoadingBox() }
                l.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            R.drawable.ic_chat_bubble_outline,
                            tr("No messages yet", "এখনো কোনো মেসেজ নেই"),
                            tr(
                                "Tap \"Message\" on a blood request, appointment, ambulance or order to start.",
                                "রক্তের অনুরোধ, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্স বা অর্ডারের পাতায় \"মেসেজ\" চাপলে কথা শুরু হয়।",
                            ),
                        )
                    }
                }
                else -> {
                    items(l, key = { it.threadId }) { t ->
                        ThreadRow(t) {
                            items = items?.map { if (it.threadId == t.threadId) it.copy(unread = 0) else it }
                            onOpen(t)
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                    if (next != null) item {
                        TextButton(
                            onClick = {
                                val c = next ?: return@TextButton
                                scope.launch {
                                    loadingMore = true
                                    runCatching { ChatApi.threads(c) }
                                        .onSuccess { (more, n) -> items = (l + more).distinctBy { it.threadId }; next = n }
                                        .onFailure { errorMsg = it.message }
                                    loadingMore = false
                                }
                            },
                            enabled = !loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) { ButtonContent(loadingMore, tr("Older", "আরও আগের")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(t: ChatThread, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val unread = t.unread > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("chat_thread_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(t.peer.name, t.peer.photoThumb)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.peer.name.ifBlank { tr("Lifolo user", "Lifolo ব্যবহারকারী") },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(ChatLogic.listTime(t.lastAt), style = MaterialTheme.typography.labelSmall, color = if (unread) cs.primary else cs.onSurfaceVariant)
            }
            ChatCtx.label(t.ctx).takeIf { it.isNotBlank() }?.let {
                Text(it + if (!t.canSend) tr(" · closed", " · বন্ধ") else "", style = MaterialTheme.typography.labelSmall, color = cs.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ChatLogic.preview(t),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) Badge(Modifier.padding(start = 8.dp).testTag("chat_thread_unread")) { Text(if (t.unread > 99) "99+" else num(t.unread)) }
            }
        }
    }
}

/** পর্দা আবার সামনে এলে */
@Composable
private fun OnResume(block: () -> Unit) {
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var first = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else block() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/** শুধু পড়া যায় কেন: আমি যোগাযোগ বন্ধ রেখেছি, নইলে সম্পর্ক শেষ */
private fun closedTextOf(p: ChatPage): String =
    if (p.blocked) tr("You stopped contact with this person. You can allow it again in settings.", "আপনি এই মানুষের সাথে যোগাযোগ বন্ধ করেছেন। সেটিংস থেকে আবার চালু করা যায়।")
    else ChatErrors.message(ChatFailure.CLOSED)

/** কথোপকথনের তথ্য (chat_open + সূত্র) */
private data class ChatInfo(val threadId: String, val ctx: String, val refId: String, val peer: ChatPeer, val canSend: Boolean)

/** নোটিফিকেশন থেকে (শুধু threadId): chat_open {threadId}; সার্ভার না নিলে তালিকায় খুঁজে সূত্র দিয়ে খোলা */
private suspend fun openByThread(threadId: String): Pair<ChatInfo, ChatPage> {
    val direct = runCatching { ChatApi.openThread(threadId) }
    direct.getOrNull()?.let { o ->
        if (o.ctx.isNotBlank() || o.peer.uid.isNotBlank()) {
            return ChatInfo(o.threadId.ifBlank { threadId }, o.ctx, o.refId, o.peer, o.canSend) to o.page
        }
    }
    val err = direct.exceptionOrNull()
    // অনুমতি নেই / বন্ধ — তালিকায় খোঁজা বৃথা
    if (err is ServerError && ChatErrors.classify(err.code).let { it == ChatFailure.NOT_ALLOWED || it == ChatFailure.BLOCKED }) throw err
    var cursor: String? = null
    var pages = 0
    while (pages++ < 5) {
        val (list, n) = ChatApi.threads(cursor)
        list.firstOrNull { it.threadId == threadId }?.let { t ->
            val o = runCatching { ChatApi.open(t.ctx, t.refId, t.peer.uid.ifBlank { null }) }.getOrNull()
            return if (o != null) ChatInfo(o.threadId.ifBlank { threadId }, t.ctx, t.refId, o.peer.takeIf { it.uid.isNotBlank() } ?: t.peer, o.canSend) to o.page
            else ChatInfo(threadId, t.ctx, t.refId, t.peer, t.canSend) to ChatApi.messages(threadId)
        }
        cursor = n ?: break
    }
    return ChatInfo(threadId, "", "", ChatPeer("", ""), true) to ChatApi.messages(threadId)
}

/**
 * একটা কথোপকথন: বাবল, লেখা/ছবি (ক্যামেরা বা গ্যালারি, ছোট করে)/লোকেশন পাঠানো, ✓ পৌঁছেছে / ✓✓ পড়েছেন,
 * ১০ মিনিটের মধ্যে নিজের মেসেজ মুছে ফেলা, চেপে ধরে রিপোর্ট/ব্লক, বন্ধ হলে "শুধু পড়া যাবে", উপরে কলের বাটন।
 * ইন্টারনেট না থাকলেও পাঠানো যায় — সারিতে থাকে (ChatQueue), সংযোগ এলে যায়।
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(target: ChatNav.Target, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = AuthRepository.currentUid.orEmpty()
    var info by remember {
        mutableStateOf(
            if (target.threadId.isNotBlank() && target.ctx.isNotBlank()) {
                ChatInfo(target.threadId, target.ctx, target.refId, ChatPeer(target.peerUid.orEmpty(), target.peerName, target.peerPhoto), true)
            } else null,
        )
    }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var before by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var closedText by remember { mutableStateOf<String?>(null) }
    var loadingOlder by remember { mutableStateOf(false) }
    var lastReadSent by remember { mutableLongStateOf(0L) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { ChatQueue.init(context) }

    val threadId = info?.threadId.orEmpty()
    val queued by ChatQueue.items.collectAsState()
    val shown = remember(messages, queued, threadId) {
        ChatLogic.merge(messages, queued.filter { it.threadId == threadId && it.uid == me && threadId.isNotBlank() }.map { it.asMessage() })
    }

    // খোলা
    LaunchedEffect(target) {
        runCatching {
            if (target.ctx.isNotBlank() && target.refId.isNotBlank()) {
                val o = ChatApi.open(target.ctx, target.refId, target.peerUid)
                ChatInfo(o.threadId, target.ctx, target.refId, o.peer.takeIf { it.uid.isNotBlank() || it.name.isNotBlank() } ?: ChatPeer(target.peerUid.orEmpty(), target.peerName, target.peerPhoto), o.canSend) to o.page
            } else openByThread(target.threadId)
        }.onSuccess { (i, page) ->
            info = i
            messages = page.messages
            before = page.before
            loaded = true
            if (!i.canSend) closedText = closedTextOf(page)
        }.onFailure { e ->
            val f = ChatErrors.of(e)
            openError = when {
                // সার্ভারে সুবিধা বন্ধ (feature_off): "এই সুবিধা শিগগির আসছে", আর সুইচগুলো নতুন করে
                Features.isFeatureOff(e) -> { Features.onPush(); Features.soonText() }
                f == ChatFailure.OTHER || f == ChatFailure.NETWORK -> e.message ?: ChatErrors.message(f)
                else -> ChatErrors.message(f)
            }
        }
    }

    // সামনে খোলা থ্রেড: এর নোটিফিকেশন নয়, আর আগের নোটিফিকেশন সরানো
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, threadId) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    if (threadId.isNotBlank()) { ChatEvents.activeThread = threadId; ChatPush.clear(context, threadId) }
                    refreshTick++
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { resumed = false; if (ChatEvents.activeThread == threadId) ChatEvents.activeThread = null }
                else -> {}
            }
        }
        if (threadId.isNotBlank() && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            ChatEvents.activeThread = threadId
            ChatPush.clear(context, threadId)
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (ChatEvents.activeThread == threadId) ChatEvents.activeThread = null
        }
    }

    suspend fun refresh() {
        if (threadId.isBlank()) return
        runCatching { ChatApi.messages(threadId) }.onSuccess { p ->
            messages = ChatLogic.merge(messages, p.messages)
            // সার্ভার প্রতিবার আবার যাচাই করে: সম্পর্ক শেষ হলে বন্ধ, আবার চালু হলে খোলা
            val can = p.canSend
            if (can != null && can != info?.canSend) {
                info = info?.copy(canSend = can)
                closedText = if (can) null else closedTextOf(p)
            }
        }
    }

    // পুশ, সারি আর পড়ার খবর
    LaunchedEffect(threadId) {
        if (threadId.isBlank()) return@LaunchedEffect
        ChatEvents.events.collect { e ->
            when (e) {
                is ChatEvents.Event.Incoming -> if (e.threadId == threadId) refresh()
                is ChatEvents.Event.Read -> if (e.threadId == threadId) messages = ChatLogic.markReadByPeer(messages, me, e.upTo)
                is ChatEvents.Event.Sent -> if (e.threadId == threadId) messages = ChatLogic.merge(messages, listOf(e.message))
                is ChatEvents.Event.Delayed -> if (e.threadId == threadId) notice = e.text
                is ChatEvents.Event.Failed -> if (e.threadId == threadId) {
                    notice = e.text
                    if (ChatErrors.closesThread(e.failure)) {
                        info = info?.copy(canSend = false)
                        closedText = e.text
                    }
                }
            }
        }
    }
    // খোলা থাকলে কিছুক্ষণ পরপর নতুন মেসেজ (আর ✓✓) — পুশ না এলেও
    LaunchedEffect(threadId, resumed, refreshTick) {
        if (threadId.isBlank() || !resumed) return@LaunchedEffect
        if (refreshTick > 0) refresh()
        while (true) {
            delay(ChatLogic.POLL_MS)
            refresh()
        }
    }
    // পড়েছি জানানো (অন্যজনের নতুন মেসেজ দেখা গেলে)
    LaunchedEffect(shown, resumed) {
        if (!resumed || threadId.isBlank()) return@LaunchedEffect
        val upTo = ChatLogic.readUpTo(messages, me, lastReadSent) ?: return@LaunchedEffect
        lastReadSent = upTo
        runCatching { ChatApi.read(threadId, upTo) }
        ChatUnread.refresh(force = true)
    }
    LaunchedEffect(notice) { if (notice != null) { delay(6_000); notice = null } }

    var blocking by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ChatMessage?>(null) }
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    val i = info

    Scaffold(
        topBar = {
            ChatTopBar(
                info = i,
                fallbackName = target.peerName,
                onBack = onBack,
                menu = headerMenu,
                onMenu = { headerMenu = it },
                onBlock = { blocking = true },
            )
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("chat_screen")) {
            val list = rememberLazyListState()
            // নতুন মেসেজ এলে নিচে (reverseLayout — ০ নম্বর সবচেয়ে নতুন)
            LaunchedEffect(shown.size) { if (shown.isNotEmpty() && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    openError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        EmptyState(R.drawable.ic_chat_bubble_outline, openError!!, null, Modifier.testTag("chat_open_error"))
                    }
                    !loaded -> LoadingBox()
                    else -> LazyColumn(
                        state = list,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val rev = shown.asReversed()
                        if (rev.isEmpty()) item {
                            Text(
                                tr("Say hello. Keep it about the request — don't share bank or card details.", "শুভেচ্ছা জানিয়ে শুরু করুন। শুধু এই অনুরোধের কথা বলুন — ব্যাংক বা কার্ডের তথ্য দেবেন না।"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                        rev.forEachIndexed { idx, m ->
                            item(key = m.id.ifBlank { "q:" + m.clientId }) {
                                Bubble(
                                    m = m,
                                    mine = m.from == me,
                                    blood = i?.ctx == CallCtx.BLOOD,
                                    onPhoto = { viewing = m },
                                    onUnsend = {
                                        scope.launch {
                                            runCatching { ChatApi.unsend(m.id) }
                                                .onSuccess { messages = messages.map { x -> if (x.id == m.id) x.copy(unsent = true, text = "", photoUrl = "", photoThumb = "", location = null) else x } }
                                                .onFailure { e -> notice = ChatErrors.message(ChatErrors.of(e), e.message).takeIf { ChatErrors.of(e) != ChatFailure.NETWORK } ?: tr("No internet — try again", "ইন্টারনেট নেই — আবার চেষ্টা করুন") }
                                        }
                                    },
                                    onReport = { reporting = m },
                                    onBlock = { blocking = true },
                                    onRetry = { ChatQueue.retry(m.clientId) },
                                    onDiscard = { ChatQueue.remove(m.clientId) },
                                )
                            }
                            // তারিখের দাগ (এই দিনের প্রথম মেসেজের আগে)
                            val older = rev.getOrNull(idx + 1)
                            if (m.at > 0 && (older == null || ChatLogic.dayKey(older.at) != ChatLogic.dayKey(m.at))) item(key = "day:" + ChatLogic.dayKey(m.at) + ":" + idx) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Text(ChatLogic.dayLabel(m.at), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                                    }
                                }
                            }
                        }
                        if (before != null) item(key = "older") {
                            TextButton(
                                onClick = {
                                    val c = before ?: return@TextButton
                                    scope.launch {
                                        loadingOlder = true
                                        runCatching { ChatApi.messages(threadId, c) }
                                            .onSuccess { p -> messages = ChatLogic.merge(messages, p.messages); before = p.before }
                                            .onFailure { notice = it.message }
                                        loadingOlder = false
                                    }
                                },
                                enabled = !loadingOlder,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonContent(loadingOlder, tr("Earlier messages", "আগের মেসেজ")) }
                        }
                    }
                }
            }
            notice?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("chat_notice")) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            when {
                openError != null || !loaded -> {}
                i?.canSend == false -> ClosedBanner(closedText)
                else -> Composer(
                    enabled = threadId.isNotBlank(),
                    media = featureOn(Features.CHAT_MEDIA),
                    onText = { t -> ChatQueue.enqueue(context, threadId, text = t) },
                    onPhoto = { bytes -> ChatQueue.enqueue(context, threadId, photoJpeg = bytes) },
                    onLocation = { loc -> ChatQueue.enqueue(context, threadId, location = loc) },
                    onError = { notice = it },
                )
            }
        }
    }

    if (blocking && i != null) CallBlockDialog(i.peer.uid, i.peer.name, null, onDismiss = { blocking = false }) {
        blocking = false
        info = i.copy(canSend = false)
        closedText = tr("You stopped contact with this person. You can allow it again in settings.", "আপনি এই মানুষের সাথে যোগাযোগ বন্ধ করেছেন। সেটিংস থেকে আবার চালু করা যায়।")
    }
    reporting?.let { m ->
        ChatReportDialog(m.id, onDismiss = { reporting = null }) {
            reporting = null
            notice = tr("Report sent. Thank you.", "রিপোর্ট পাঠানো হয়েছে। ধন্যবাদ।")
        }
    }
    viewing?.let { m ->
        PhotoViewer(m, onDismiss = { viewing = null }, onExpired = {
            // লিংকের মেয়াদ শেষ — মেসেজ আবার এনে নতুন লিংক
            scope.launch {
                refresh()
                viewing = messages.firstOrNull { it.id == m.id }
            }
        })
    }
}

@Composable
private fun ChatTopBar(info: ChatInfo?, fallbackName: String, onBack: () -> Unit, menu: Boolean, onMenu: (Boolean) -> Unit, onBlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
            val name = info?.peer?.name?.ifBlank { null } ?: fallbackName.ifBlank { tr("Message", "মেসেজ") }
            PeerAvatar(name, info?.peer?.photoThumb.orEmpty(), 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChatCtx.label(info?.ctx.orEmpty()).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = cs.primary, maxLines = 1)
                }
            }
            if (info != null && info.ctx.isNotBlank() && info.refId.isNotBlank()) {
                ChatHeaderCalls(info.ctx, info.refId, info.peer.uid.ifBlank { null }, info.peer.name)
            }
            if (info != null && info.peer.uid.isNotBlank()) Box {
                IconButton(onClick = { onMenu(true) }, modifier = Modifier.testTag("chat_header_menu")) { AppIcon(R.drawable.ic_more_vert, tr("More", "আরও")) }
                DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                    DropdownMenuItem(
                        text = { Text(tr("Stop contact", "যোগাযোগ বন্ধ করুন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_close, null) },
                        onClick = { onMenu(false); onBlock() },
                        modifier = Modifier.testTag("chat_block"),
                    )
                }
            }
        }
    }
}

/** উপরের বারে কলের বাটন — ContactActions (শুধু অডিও/ভিডিও কল; এখানে ফোন নম্বর নেই, মেসেজের বাটনও লাগে না) */
@Composable
private fun ChatHeaderCalls(ctx: String, refId: String, peerUid: String?, peerName: String) {
    ContactActions(ctx, refId, peerUid = peerUid, peerName = peerName, style = ContactStyle.ICONS, showPhone = false)
}

@Composable
private fun ClosedBanner(text: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("chat_closed_banner")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_info, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                text ?: ChatErrors.message(ChatFailure.CLOSED),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    mine: Boolean,
    blood: Boolean,
    onPhoto: () -> Unit,
    onUnsend: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmUnsend by remember { mutableStateOf(false) }
    val bg = when {
        m.unsent -> cs.surfaceContainer
        mine -> cs.primaryContainer
        else -> cs.surfaceContainerHigh
    }
    val fg = if (mine && !m.unsent) cs.onPrimaryContainer else cs.onSurface
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Surface(
                color = bg, contentColor = fg, shape = shape,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .combinedClickable(
                        onClick = { if (m.hasPhoto && !m.unsent) onPhoto() },
                        onLongClick = { if (!m.unsent && m.id.isNotBlank()) menu = true },
                    )
                    .testTag(if (mine) "chat_bubble_mine" else "chat_bubble_theirs"),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (m.unsent) {
                        Text(ChatLogic.bodyText(m), fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        if (m.hasPhoto) BubblePhoto(m)
                        m.location?.let { loc ->
                            Row(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable { MapsLink.open(context, loc.lat, loc.lng) }.padding(vertical = 4.dp).testTag("chat_location"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(R.drawable.ic_location_on, null, Modifier.size(28.dp), cs.error)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(tr("Location", "লোকেশন"), fontWeight = FontWeight.SemiBold)
                                    Text(tr("Open in map", "ম্যাপে দেখুন"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                                }
                            }
                        }
                        if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (m.at > 0) Text(Rules.formatTime(m.at), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                        if (mine && !m.unsent) {
                            Spacer(Modifier.width(4.dp))
                            when (ChatLogic.tick(m)) {
                                ChatLogic.Tick.PENDING -> AppIcon(R.drawable.ic_schedule, tr("Sending", "পাঠানো হচ্ছে"), Modifier.size(14.dp).testTag("chat_tick_pending"), fg.copy(alpha = 0.7f))
                                ChatLogic.Tick.SENT -> AppIcon(R.drawable.ic_done, tr("Sent", "পৌঁছেছে"), Modifier.size(16.dp).testTag("chat_tick_sent"), fg.copy(alpha = 0.7f))
                                ChatLogic.Tick.READ -> AppIcon(R.drawable.ic_done_all, tr("Read", "পড়েছেন"), Modifier.size(16.dp).testTag("chat_tick_read"), cs.primary)
                                ChatLogic.Tick.FAILED -> AppIcon(R.drawable.ic_warning, tr("Not sent", "পাঠানো যায়নি"), Modifier.size(16.dp), cs.error)
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.text.isNotBlank()) DropdownMenuItem(
                    text = { Text(tr("Copy", "কপি করুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_content_copy, null) },
                    onClick = { menu = false; copy(context, m.text) },
                )
                if (mine) {
                    if (ChatLogic.canUnsend(m, m.from)) DropdownMenuItem(
                        text = { Text(tr("Remove for everyone", "সবার জন্য মুছে ফেলুন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_delete, null) },
                        onClick = { menu = false; confirmUnsend = true },
                        modifier = Modifier.testTag("chat_unsend"),
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(tr("Report", "রিপোর্ট করুন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_flag, null) },
                        onClick = { menu = false; onReport() },
                        modifier = Modifier.testTag("chat_report"),
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Stop contact", "যোগাযোগ বন্ধ করুন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_close, null) },
                        onClick = { menu = false; onBlock() },
                    )
                }
            }
        }
    }
    // সার্ভারের সতর্কতা (অন্য নম্বরে টাকা চাওয়া ইত্যাদি)
    if (!m.unsent) MoneyWarnBanner(m.warn, blood)
    m.failedReason?.let { reason ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(reason, color = cs.error, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onRetry) { Text(tr("Try again", "আবার চেষ্টা")) }
                TextButton(onClick = onDiscard) { Text(tr("Delete", "মুছুন")) }
            }
        }
    }
    if (confirmUnsend) AlertDialog(
        onDismissRequest = { confirmUnsend = false },
        title = { Text(tr("Remove this message?", "মেসেজটা মুছে ফেলবেন?")) },
        text = { Text(tr("It will show as \"Message removed\" for both of you.", "দুজনের কাছেই \"মেসেজ মুছে ফেলা হয়েছে\" দেখাবে।")) },
        confirmButton = { TextButton(onClick = { confirmUnsend = false; onUnsend() }, modifier = Modifier.testTag("chat_unsend_confirm")) { Text(tr("Remove", "মুছে ফেলুন")) } },
        dismissButton = { TextButton(onClick = { confirmUnsend = false }) { Text(tr("Cancel", "বাতিল")) } },
    )
}

private fun copy(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text))
    }
}

@Composable
private fun BubblePhoto(m: ChatMessage) {
    val thumb = remember(m.photoThumb) { ChatPhotos.thumb(m.photoThumb) }
    var full by remember(m.id) { mutableStateOf<ImageBitmap?>(ChatPhotos.cached(m.id)) }
    // ছোট ছবি না থাকলে বড়টা আনা
    LaunchedEffect(m.id, m.photoUrl) {
        if (thumb == null && full == null && m.photoUrl.isNotBlank() && m.id.isNotBlank()) full = runCatching { ChatPhotos.full(m.id, m.photoUrl, 900) }.getOrNull()
    }
    val img = full ?: thumb
    Box(
        Modifier.padding(bottom = 4.dp).size(220.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag("chat_photo"),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, tr("Photo", "ছবি"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else AppIcon(R.drawable.ic_image, tr("Photo", "ছবি"), Modifier.size(40.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.pending) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** বড় করে ছবি (সাময়িক লিংক; মেয়াদ শেষ হলে onExpired — মেসেজ আবার এনে নতুন লিংক) */
@Composable
private fun PhotoViewer(m: ChatMessage, onDismiss: () -> Unit, onExpired: () -> Unit) {
    var img by remember(m.photoUrl) { mutableStateOf(ChatPhotos.cached(m.id) ?: ChatPhotos.thumb(m.photoThumb)) }
    var loading by remember(m.photoUrl) { mutableStateOf(m.photoUrl.isNotBlank()) }
    LaunchedEffect(m.photoUrl) {
        if (m.photoUrl.isBlank() || m.id.isBlank()) { loading = false; return@LaunchedEffect }
        try {
            ChatPhotos.full(m.id, m.photoUrl)?.let { img = it }
        } catch (e: ChatPhotos.Expired) {
            onExpired()
        }
        loading = false
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            img?.let { Image(it, tr("Photo", "ছবি"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (loading) CircularProgressIndicator()
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                AppIcon(R.drawable.ic_close, tr("Close", "বন্ধ"), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/** নিচের লেখার ঘর: সংযুক্তি (ক্যামেরা, গ্যালারি, লোকেশন), লেখা, পাঠান */
@Composable
private fun Composer(
    enabled: Boolean,
    /** ছবি আর লোকেশন (ফিচার সুইচ chat_media) */
    media: Boolean,
    onText: (String) -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onLocation: (ChatLocation) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ByteArray?>(null) }
    var processing by remember { mutableStateOf(false) }
    var askLocation by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun take(uri: Uri?) {
        if (uri == null) return
        processing = true
        scope.launch {
            runCatching { ChatPhotos.compress(context, uri) }
                .onSuccess { preview = it }
                .onFailure { onError(it.message ?: tr("Couldn't read the photo", "ছবিটা পড়া যায়নি")) }
            processing = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { take(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) take(cameraUri) }
    fun openCamera() {
        val uri = runCatching { ChatPhotos.cameraUri(context) }.getOrNull() ?: return
        cameraUri = uri
        runCatching { camera.launch(uri) }.onFailure { onError(tr("No camera app found", "ক্যামেরা খোলা যায়নি")) }
    }
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else onError(tr("Camera permission is needed to take a photo", "ছবি তুলতে ক্যামেরার অনুমতি লাগবে"))
    }

    fun sendLocation() {
        locating = true
        scope.launch {
            val loc = runCatching { LocationHelper.precise(context)?.let { it.first to it.second } ?: LocationHelper.current(context) }.getOrNull()
            locating = false
            if (loc == null) onError(tr("Couldn't find your location. Turn on location and try again.", "আপনার লোকেশন পাওয়া যায়নি। লোকেশন চালু করে আবার চেষ্টা করুন।"))
            else onLocation(ChatLocation(loc.first, loc.second))
        }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) sendLocation() else onError(tr("Location permission is needed to share where you are", "লোকেশন পাঠাতে লোকেশনের অনুমতি লাগবে"))
    }

    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            if (media) Box {
                IconButton(onClick = { attach = true }, enabled = enabled && !processing && !locating, modifier = Modifier.testTag("chat_attach")) {
                    if (processing || locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else AppIcon(R.drawable.ic_attach_file, tr("Attach", "সংযুক্ত করুন"))
                }
                DropdownMenu(expanded = attach, onDismissRequest = { attach = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Camera", "ক্যামেরা")) },
                        leadingIcon = { AppIcon(R.drawable.ic_photo_camera, null) },
                        onClick = {
                            attach = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
                            else cameraPerm.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.testTag("chat_attach_camera"),
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Gallery", "গ্যালারি")) },
                        leadingIcon = { AppIcon(R.drawable.ic_image, null) },
                        onClick = { attach = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.testTag("chat_attach_gallery"),
                    )
                    DropdownMenuItem(
                        text = { Text(tr("My location", "আমার লোকেশন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_location_on, null) },
                        onClick = { attach = false; askLocation = true },
                        modifier = Modifier.testTag("chat_attach_location"),
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ChatApi.MAX_TEXT) },
                placeholder = { Text(tr("Message", "মেসেজ লিখুন")) },
                maxLines = 5,
                enabled = enabled,
                shape = RoundedCornerShape(24.dp),
                supportingText = if (text.length > ChatApi.MAX_TEXT - 100) ({ Text(num(text.length) + "/" + num(ChatApi.MAX_TEXT)) }) else null,
                modifier = Modifier.weight(1f).testTag("chat_input"),
            )
            IconButton(
                onClick = {
                    val t = text.trim()
                    if (ChatLogic.canSendText(t)) { onText(t); text = "" }
                },
                enabled = enabled && ChatLogic.canSendText(text),
                modifier = Modifier.testTag("chat_send"),
            ) { AppIcon(R.drawable.ic_send, tr("Send", "পাঠান"), tint = if (ChatLogic.canSendText(text)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    preview?.let { bytes ->
        val bmp = remember(bytes) { runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(tr("Send this photo?", "ছবিটা পাঠাবেন?")) },
            text = {
                Column {
                    bmp?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit) }
                    SmallNote(num(String.format(java.util.Locale.US, "%.0f", bytes.size / 1024.0)) + " KB")
                }
            },
            confirmButton = { TextButton(onClick = { preview = null; onPhoto(bytes) }, modifier = Modifier.testTag("chat_photo_send")) { Text(tr("Send", "পাঠান")) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    if (askLocation) AlertDialog(
        onDismissRequest = { askLocation = false },
        title = { Text(tr("Send your location?", "আপনার লোকেশন পাঠাবেন?")) },
        text = { Text(tr("They'll see where you are now on a map. Only this once — it isn't tracked.", "উনি ম্যাপে আপনার এখনকার জায়গা দেখতে পাবেন। শুধু এই একবার — পরে আর জানানো হয় না।")) },
        confirmButton = {
            TextButton(onClick = {
                askLocation = false
                if (LocationHelper.hasPermission(context) || LocationHelper.hasFine(context)) sendLocation()
                else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, modifier = Modifier.testTag("chat_location_send")) { Text(tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = { askLocation = false }) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** মেসেজ রিপোর্ট: কলের মতোই কারণ */
@Composable
private fun ChatReportDialog(messageId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Report this message", "মেসেজটা রিপোর্ট করুন")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatReportText.ALL.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = reason == r, role = Role.RadioButton) { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(ChatReportText.label(r), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("What happened (optional)", "কী হয়েছিল (ঐচ্ছিক)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = reason ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    runCatching { ChatApi.report(messageId, r, note.trim()) }
                        .onSuccess { onDone() }
                        .onFailure { error = it.message }
                    busy = false
                }
            }, enabled = !busy && reason != null, modifier = Modifier.testTag("chat_report_send")) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** রিপোর্টের কারণ: কলের কারণগুলো, আর "অন্য নম্বরে টাকা চেয়েছে" ("অন্য কারণ"-এর আগে) */
object ChatReportText {
    const val ASKED_OTHER_NUMBER = "asked_other_number"

    val ALL: List<String> = CallReportReason.ALL.filter { it != "other" } + ASKED_OTHER_NUMBER +
        CallReportReason.ALL.filter { it == "other" }

    fun label(r: String): String = when (r) {
        "spam" -> tr("Unwanted / repeated messages", "অকারণে বা বারবার মেসেজ")
        ASKED_OTHER_NUMBER -> tr("Asked for money to another number", "অন্য নম্বরে টাকা চেয়েছে")
        else -> CallReportReason.label(r)
    }
}

/**
 * টাকা নিয়ে সতর্কতা — সার্ভার মেসেজে `warn:"money_number"` দিলে মেসেজের নিচে। রক্তের কথোপকথনে কড়া কথা
 * (রক্তের জন্য টাকা লেনদেন নিষেধ)। warn খালি হলে কিছুই দেখায় না।
 */
@Composable
fun MoneyWarnBanner(warn: String?, blood: Boolean) {
    if (warn.isNullOrBlank()) return
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.errorContainer, contentColor = cs.onErrorContainer, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).testTag("chat_money_warn"),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_warning, null, Modifier.size(18.dp), cs.error)
            Spacer(Modifier.width(8.dp))
            Text(ChatLogic.moneyWarnText(blood), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
