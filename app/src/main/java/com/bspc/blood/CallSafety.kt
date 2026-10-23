package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// কলে ব্লক আর রিপোর্ট (call_block / call_unblock / call_blocks / call_report):
// কল শেষের পর্দা আর কলের তালিকা থেকে; ব্লক করা মানুষের তালিকা নোটিফিকেশনের সেটিংসে ("অ্যাপে কল"-এর নিচে)।
// ব্লক শুধু অ্যাপে কলের — রিকোয়েস্ট/অ্যাপয়েন্টমেন্ট/ফোন নম্বর আগের মতো।
// ------------------------------------------------------------

/** "ব্লক করবেন?" — হ্যাঁ হলে সার্ভারে, তারপর onDone */
@Composable
fun CallBlockDialog(peerUid: String, peerName: String, callId: String?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Block ${peerName.ifBlank { "this person" }}?", "${peerName.ifBlank { "এই মানুষকে" }} ব্লক করবেন?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("They won't be able to call you in the app, and you can't call them. Phone numbers stay as they are. You can unblock in settings.", "উনি আপনাকে অ্যাপে কল করতে পারবেন না, আপনিও পারবেন না। ফোন নম্বর আগের মতোই থাকবে। সেটিংস থেকে আনব্লক করা যায়।"))
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { CallsApi.block(peerUid, callId) }
                        .onSuccess { onDone() }
                        .onFailure { error = it.message }
                    busy = false
                }
            }, enabled = !busy && (peerUid.isNotBlank() || !callId.isNullOrBlank()), modifier = Modifier.testTag("call_block_confirm")) {
                ButtonContent(busy, tr("Block", "ব্লক করুন"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** রিপোর্ট: কারণ (হয়রানি, অকারণে কল, ভুয়া, অন্য) + ঐচ্ছিক লেখা */
@Composable
fun CallReportDialog(callId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Report this call", "কলটা রিপোর্ট করুন")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CallReportReason.ALL.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = reason == r, role = Role.RadioButton) { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(CallReportReason.label(r), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    note, { note = it.take(300) },
                    label = { Text(tr("What happened (optional)", "কী হয়েছিল (ঐচ্ছিক)")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = reason ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    runCatching { CallsApi.report(callId, r, note.trim()) }
                        .onSuccess { onDone() }
                        .onFailure { error = it.message }
                    busy = false
                }
            }, enabled = !busy && reason != null, modifier = Modifier.testTag("call_report_send")) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** সেটিংসে "অ্যাপে কল"-এর নিচে: ব্লক করা মানুষ (চাপলে তালিকা, আনব্লক) */
@Composable
fun CallBlocksRow() {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(tr("Blocked from calling", "কলে ব্লক করা মানুষ")) },
        leadingContent = { AppIcon(R.drawable.ic_close, null) },
        trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true }.testTag("calls_blocked_list"),
    )
    if (open) CallBlocksDialog(onDismiss = { open = false })
}

@Composable
private fun CallBlocksDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CallBlocked>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { CallsApi.blocks() }.onSuccess { items = it }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Blocked from calling", "কলে ব্লক করা মানুষ")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ErrorText(error)
                val l = items
                when {
                    l == null && error == null -> LoadingBox()
                    l != null && l.isEmpty() -> Text(tr("No one is blocked", "কাউকে ব্লক করা নেই"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    l != null -> l.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") }, style = MaterialTheme.typography.titleSmall)
                                if (b.blockedAt > 0) SmallNote(Rules.formatDate(b.blockedAt))
                            }
                            TextButton(onClick = {
                                busy = b.uid
                                scope.launch {
                                    runCatching { CallsApi.unblock(b.uid) }
                                        .onSuccess { items = items?.filter { it.uid != b.uid } }
                                        .onFailure { error = it.message }
                                    busy = null
                                }
                            }, enabled = busy == null) { ButtonContent(busy == b.uid, tr("Unblock", "আনব্লক")) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } },
    )
}
