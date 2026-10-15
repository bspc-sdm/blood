package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** রাজি থাকা বা রক্ত দেওয়া ডোনার (রক্তপ্রার্থী শুধু এদেরই দেখতে পান) */
fun List<DonorResponse>.willing() = filter { it.status == "accepted" || it.status == "donated" }

/** নিশ্চিত বা রক্ত দেওয়া — ব্যাগ পূরণের হিসাবে ধরা হয় */
fun DonorResponse.counts() = status == "donated" || (status == "accepted" && confirmed)

/** "৩ জন রাজি · ১ জন নিশ্চিত" আর ব্যাগ পূরণের অগ্রগতি */
@Composable
fun RequestProgress(r: BloodRequest, responses: List<DonorResponse>) {
    val willing = responses.willing()
    val done = willing.count { it.counts() }.coerceAtMost(r.bags)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            tr(
                "${num(willing.size)} agreed · ${num(willing.count { it.counts() })} confirmed",
                "${num(willing.size)} জন রাজি · ${num(willing.count { it.counts() })} জন নিশ্চিত",
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        LinearProgressIndicator(
            progress = { if (r.bags > 0) done.toFloat() / r.bags else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (done >= r.bags) tr("All ${num(r.bags)} bags arranged", "${num(r.bags)} ব্যাগই পূরণ হয়েছে")
            else tr("${num(done)} of ${num(r.bags)} bags arranged", "${num(r.bags)} ব্যাগের ${num(done)}টা পূরণ"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** রাজি হওয়া এক ডোনার: নাম, ফোন, কল, নিশ্চিত / বাদ */
@Composable
fun DonorRow(
    d: DonorResponse,
    active: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BloodGroupBadge(d.donorGroup, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.donorName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        num(d.donorPhone),
                        d.distanceKm?.let { num(it) + tr(" km away", " কিমি দূরে") },
                        if (d.needsFare) tr("needs travel cost", "যাতায়াত খরচ লাগবে") else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.needsFare) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                d.status == "donated" -> Tag(tr("Donated", "রক্ত দিয়েছেন"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                d.confirmed -> Tag(tr("Confirmed", "নিশ্চিত"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            IconButton(onClick = { callPhone(context, d.donorPhone) }) {
                AppIcon(R.drawable.ic_call, tr("Call", "কল করুন"), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (active && d.status == "accepted") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!d.confirmed) {
                    FilledTonalButton(onClick = onConfirm, enabled = !busy) {
                        ButtonContent(busy, tr("Confirm", "নিশ্চিত করুন"), R.drawable.ic_check_circle)
                    }
                }
                TextButton(onClick = onRemove, enabled = !busy) { Text(tr("Remove", "বাদ দিন")) }
            }
        }
    }
}

/** "রক্ত পেয়েছি": কে কে রক্ত দিয়েছেন বেছে রিকোয়েস্ট বন্ধ */
@Composable
fun CloseRequestDialog(requestId: String, responses: List<DonorResponse>, onDismiss: () -> Unit, onClose: (List<String>) -> Unit) {
    val willing = responses.willing()
    // নিশ্চিত করা ডোনারদের আগে থেকেই টিক দেওয়া
    var donated by remember(requestId) { mutableStateOf(willing.filter { it.counts() }.map { it.donorUid }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Close this request?", "রিকোয়েস্ট বন্ধ করবেন?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Donors won't see it or get notified anymore.", "বন্ধ করলে ডোনাররা আর এটা দেখবেন না।"))
                if (willing.isNotEmpty()) {
                    Text(tr("Who donated?", "কে কে রক্ত দিয়েছেন?"), fontWeight = FontWeight.SemiBold)
                    willing.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = d.donorUid in donated,
                                onCheckedChange = { on -> donated = if (on) donated + d.donorUid else donated - d.donorUid },
                            )
                            Text("${d.donorName} (${d.donorGroup})")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(donated.toList()) }) { Text(tr("Close", "বন্ধ করুন")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// হোমে "My Request": নিজের খোলা রিকোয়েস্টের অবস্থা (অস্থায়ী, বন্ধ হলে চলে যায়)
// ------------------------------------------------------------

@Composable
fun MyRequestSection(refreshKey: Int, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Pair<BloodRequest, List<DonorResponse>>>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<Pair<BloodRequest, List<DonorResponse>>?>(null) }

    LaunchedEffect(reload, refreshKey) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        runCatching {
            RequestRepository.mine(uid).filter { it.isActive }.sortedBy { it.deadlineMillis }
                .map { it to RequestRepository.responses(it.id) }
        }.onSuccess { items = it }
    }

    fun act(id: String, block: suspend () -> Unit) {
        scope.launch {
            busyId = id
            errorMsg = null
            runCatching { block() }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message }
            busyId = null
        }
    }

    if (items.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_bloodtype, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(tr("My Request", "আমার রিকোয়েস্ট"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            ErrorText(errorMsg)
            items.forEach { (r, responses) ->
                val willing = responses.willing()
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BloodGroupBadge(r.patientGroup, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.hospital,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (r.urgent) {
                                        Spacer(Modifier.width(6.dp))
                                        UrgentTag()
                                    }
                                }
                                Text(
                                    tr("${num(r.bags)} bag(s) · ", "${num(r.bags)} ব্যাগ · ") + Rules.formatNeeded(r),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (willing.isNotEmpty()) {
                                IconButton(onClick = { shareRequest(context, r) }) { AppIcon(R.drawable.ic_share, tr("Share", "শেয়ার")) }
                            }
                        }
                        Countdown(r.deadlineMillis)

                        if (r.notified) {
                            IconLine(
                                R.drawable.ic_notifications_active,
                                tr("${num(r.notifiedCount)} donors notified", "${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে"),
                            )
                        } else {
                            FilledTonalButton(
                                onClick = { act(r.id) { ServerApi.notifyRequest(r.id) } },
                                enabled = busyId == null,
                            ) { ButtonContent(busyId == r.id, tr("Notify nearby donors", "আশেপাশের ডোনারদের জানান"), R.drawable.ic_notifications) }
                        }

                        RequestProgress(r, responses)

                        if (willing.isNotEmpty()) {
                            HorizontalDivider()
                            willing.sortedByDescending { it.counts() }.take(3).forEach { d ->
                                DonorRow(
                                    d,
                                    active = true,
                                    busy = busyId == r.id,
                                    onConfirm = { act(r.id) { ServerApi.confirmDonor(r.id, d.donorUid) } },
                                    onRemove = { act(r.id) { ServerApi.updateResponse(r.id, "remove", d.donorUid) } },
                                )
                            }
                            if (willing.size > 3) {
                                TextButton(onClick = { onOpen(r.id) }) {
                                    Text(tr("See all ${num(willing.size)}", "সবাই দেখুন (${num(willing.size)})"))
                                }
                            }
                        } else {
                            Text(
                                tr("No one has agreed yet. Share it with people you know — even far away.", "এখনো কেউ রাজি হননি। পরিচিতদের পাঠান — দূরে থাকলেও।"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(onClick = { shareRequest(context, r) }, modifier = Modifier.fillMaxWidth()) {
                                ButtonContent(false, tr("Send to people you know", "পরিচিতদের পাঠান"), R.drawable.ic_share)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onOpen(r.id) }, modifier = Modifier.weight(1f)) { Text(tr("Details", "বিস্তারিত")) }
                            FilledTonalButton(onClick = { closing = r to responses }, modifier = Modifier.weight(1f)) {
                                Text(tr("Got blood", "রক্ত পেয়েছি"), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    closing?.let { (r, responses) ->
        CloseRequestDialog(
            requestId = r.id,
            responses = responses,
            onDismiss = { closing = null },
            onClose = { chosen ->
                closing = null
                act(r.id) { ServerApi.closeRequest(r.id, chosen) }
            },
        )
    }
}
