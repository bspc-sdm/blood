package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** কলের তালিকার এক সারির লেখা (শুধু হিসাব — টেস্ট করা যায়) */
object CallHistoryText {
    fun stateText(c: CallHistoryItem): String = when {
        c.missed -> tr("Missed", "মিসড কল")
        c.state == "declined" -> if (c.incoming) tr("Declined", "কেটে দিয়েছেন") else tr("Not answered", "ধরেননি")
        c.state == "missed" -> tr("No answer", "কেউ ধরেননি")
        c.state == "cancelled" -> tr("Cancelled", "বাতিল")
        c.state == "ringing" -> tr("Ringing", "বাজছে")
        c.durationSec > 0 -> CallMachine.duration(c.durationSec.toLong())
        else -> if (c.incoming) tr("Incoming", "এসেছে") else tr("Outgoing", "করেছেন")
    }

    fun icon(c: CallHistoryItem): Int = when {
        c.missed -> R.drawable.ic_call_missed
        c.incoming -> R.drawable.ic_call_received
        else -> R.drawable.ic_call_made
    }

    fun kind(c: CallHistoryItem): String = (if (c.video) tr("Video", "ভিডিও") else tr("Audio", "অডিও")) +
        CallCtx.label(c.ctx).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

/**
 * অ্যাপে কলের তালিকা (প্রোফাইল → "কল", মিসড কলের নোটিফিকেশন): কে, কখন, অডিও/ভিডিও, কোন সূত্রে, কতক্ষণ বা মিসড।
 * আবার কল: যে সূত্রে কল হয়েছিল সেখানে সম্পর্ক এখনো থাকলে (সার্ভার না বললে বাটন লুকায়)।
 */
@Composable
fun CallHistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CallHistoryItem>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        errorMsg = null
        runCatching { CallsApi.history() }
            .onSuccess { items = it; more = it.size >= 20 }
            .onFailure { errorMsg = it.message }
    }

    Scaffold(topBar = {
        BackTopBar(tr("Calls", "কল"), onBack) {
            IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
        }
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("call_history"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { CallsFullScreenRow() }
            item { ErrorText(errorMsg) }
            val l = items
            when {
                l == null -> if (errorMsg == null) item { LoadingBox() }
                l.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(R.drawable.ic_phone_in_talk, tr("No calls yet", "এখনো কোনো কল নেই"), null)
                    }
                }
                else -> {
                    items(l, key = { it.callId }) { c -> CallRow(c) }
                    if (more) item {
                        TextButton(
                            onClick = {
                                val before = l.lastOrNull()?.startedAt ?: return@TextButton
                                scope.launch {
                                    loadingMore = true
                                    runCatching { CallsApi.history(before) }
                                        .onSuccess { next -> items = (l + next).distinctBy { it.callId }; more = next.size >= 20 }
                                        .onFailure { errorMsg = it.message }
                                    loadingMore = false
                                }
                            },
                            enabled = !loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) { ButtonContent(loadingMore, tr("Older calls", "আরও আগের কল")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(c: CallHistoryItem) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(CallHistoryText.icon(c), null, Modifier.size(22.dp), if (c.missed) cs.error else cs.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.peer.name.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (c.missed) cs.error else cs.onSurface,
                    )
                    Text(
                        listOf(CallHistoryText.kind(c), CallHistoryText.stateText(c), timeAgo(c.startedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            if (c.ctx in CallCtx.ALL && c.refId.isNotBlank()) {
                AppCallButtons(c.ctx, c.refId, peerUid = c.peer.uid.ifBlank { null }, peerName = c.peer.name, emphasis = CallEmphasis.ICONS, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}
