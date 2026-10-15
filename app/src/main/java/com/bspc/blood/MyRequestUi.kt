package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/** রাজি থাকা বা রক্ত দেওয়া ডোনার (রক্তপ্রার্থী শুধু এদেরই দেখতে পান) */
fun List<DonorResponse>.willing() = filter { it.status == "accepted" || it.status == "donated" }

/** নিশ্চিত বা রক্ত দেওয়া — ব্যাগ পূরণের হিসাবে ধরা হয় */
fun DonorResponse.counts() = status == "donated" || (status == "accepted" && confirmed)

/** "৩ জন রাজি · ১ জন নিশ্চিত" — বামে মানুষের আইকন */
@Composable
fun AgreedLine(responses: List<DonorResponse>, iconSlot: Dp? = null) {
    val willing = responses.willing()
    SlotRow(R.drawable.ic_group_round, MaterialTheme.colorScheme.onSurface, iconSlot) {
        Text(
            tr(
                "${num(willing.size)} agreed · ${num(willing.count { it.counts() })} confirmed",
                "${num(willing.size)} জন রাজি · ${num(willing.count { it.counts() })} জন নিশ্চিত",
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** কার্ডের বাম পাশের রক্তের গ্রুপের গোল যত চওড়া; নিচের আইকনগুলো এর মাঝ বরাবর থাকে */
private val BADGE_SLOT = 44.dp

/**
 * বামে আইকন, ডানে লেখা। iconSlot দিলে আইকন ওই চওড়া ঘরের মাঝখানে বসে আর লেখা শুরু হয়
 * হাসপাতালের নামের সাথে একই সোজা লাইনে — দেখতে গোছানো, সব আইকন গ্রুপের গোলের নিচে মাঝ বরাবর।
 */
@Composable
private fun SlotRow(@androidx.annotation.DrawableRes icon: Int?, tint: Color, iconSlot: Dp?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(if (iconSlot != null) Modifier.width(iconSlot) else Modifier, contentAlignment = Alignment.Center) {
            if (icon != null) AppIcon(icon, null, Modifier.size(20.dp), tint)
        }
        Spacer(Modifier.width(if (iconSlot != null) 12.dp else 8.dp))
        content()
    }
}

/** ১, ২, ৩… করে প্রতিটা ব্যাগ — যতগুলো পূরণ হয়েছে সেগুলো ভরাট */
@Composable
fun BagRow(r: BloodRequest, responses: List<DonorResponse>) {
    // নিশ্চিত/রক্ত দেওয়া ডোনার, অথবা রক্তপ্রার্থী নিজে যত ব্যাগ পেয়েছেন বলেছেন — যেটা বেশি
    val done = maxOf(responses.willing().count { it.counts() }, r.bagsReceived).coerceAtMost(r.bags)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..r.bags).forEach { i -> BloodBag(i, i <= done) }
    }
}

@Composable
fun RequestProgress(r: BloodRequest, responses: List<DonorResponse>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgreedLine(responses)
        BagRow(r, responses)
    }
}

/** রক্তের ব্যাগের ছবি, ভেতরে নম্বর; পূরণ হলে লাল ভরাট, নইলে শুধু রেখা */
@Composable
fun BloodBag(number: Int, filled: Boolean, description: String? = null, onClick: (() -> Unit)? = null) {
    val red = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    Box(
        Modifier.size(width = 30.dp, height = 40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
            contentDescription = description ?: if (filled) tr("Bag $number arranged", "${num(number)} নম্বর ব্যাগ পূরণ") else tr("Bag $number needed", "${num(number)} নম্বর ব্যাগ বাকি")
        },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            // উপরের নল আর ঝোলানোর ফুটো
            drawLine(red, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, h * 0.16f), stroke)
            drawRoundRect(
                red,
                topLeft = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.12f),
                size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.1f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
            // ব্যাগ
            val top = h * 0.2f
            val body = androidx.compose.ui.geometry.Size(w - stroke, h - top - stroke / 2)
            val corner = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
            val tl = androidx.compose.ui.geometry.Offset(stroke / 2, top)
            drawRoundRect(if (filled) red else empty, topLeft = tl, size = body, cornerRadius = corner)
            drawRoundRect(red, topLeft = tl, size = body, cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
        Text(
            num(number),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (filled) MaterialTheme.colorScheme.onError else red,
            modifier = Modifier.padding(top = 8.dp),
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
    var askRemove by remember { mutableStateOf(false) }
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
            // ছোট বাটন, নামের নিচে বাঁ দিকে মিলিয়ে
            Row(Modifier.padding(start = 46.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!d.confirmed) SmallAction(tr("Confirm", "নিশ্চিত করুন"), R.drawable.ic_check_circle, onConfirm, busy = busy)
                SmallAction(tr("Remove", "বাদ দিন"), R.drawable.ic_close, { askRemove = true }, enabled = !busy)
            }
        }
    }
    // ভুল চাপে ডোনার যেন হারিয়ে না যায়: আগে জিজ্ঞেস
    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text(tr("Remove ${d.donorName}?", "${d.donorName}-কে বাদ দেবেন?")) },
            text = {
                Text(
                    tr(
                        "They'll be told they don't need to come. If it's a mistake, you can bring them back within 30 minutes.",
                        "তাঁকে জানানো হবে যে আর আসতে হবে না। ভুল হলে ৩০ মিনিটের মধ্যে ফিরিয়ে আনতে পারবেন।",
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { askRemove = false; onRemove() }) {
                    Text(tr("Remove", "বাদ দিন"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

/** এইমাত্র বাদ দেওয়া ডোনার — ভুল হলে ফিরিয়ে আনা (৩০ মিনিট পর্যন্ত) */
@Composable
fun RemovedNotice(name: String, busy: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("$name was removed", "$name-কে বাদ দেওয়া হয়েছে"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SmallAction(tr("Undo", "ফিরিয়ে আনুন"), R.drawable.ic_refresh, onUndo, busy = busy)
            IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, tr("Close", "বন্ধ"), Modifier.size(18.dp)) }
        }
    }
}

/** "রক্ত পেয়েছি": কে কে রক্ত দিয়েছেন বেছে রিকোয়েস্ট বন্ধ */
@Composable
fun CloseRequestDialog(requestId: String, bags: Int, responses: List<DonorResponse>, onDismiss: () -> Unit, onClose: (List<String>, Int) -> Unit, already: Int = 0) {
    val willing = responses.willing()
    // নিশ্চিত করা ডোনারদের আগে থেকেই টিক দেওয়া
    var donated by remember(requestId) { mutableStateOf(willing.filter { it.counts() }.map { it.donorUid }.toSet()) }
    // কত ব্যাগ পেয়েছেন (সবগুলো না-ও পেতে পারেন); শুরুতে নিশ্চিত ডোনারের সংখ্যা
    var received by remember(requestId) { mutableIntStateOf(maxOf(donated.size, already).coerceIn(1, bags.coerceAtLeast(1))) }
    // সব ব্যাগ পেলে বন্ধ; কম পেলে রিকোয়েস্ট খোলা থাকে, শুধু হিসাব সেভ হয়
    val all = received >= bags
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (all) tr("Close this request?", "রিকোয়েস্ট বন্ধ করবেন?") else tr("Blood received so far", "এখন পর্যন্ত কত রক্ত পেলেন")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("How many bags did you get?", "কত ব্যাগ পেয়েছেন?"), fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..bags.coerceAtLeast(1)).forEach { i -> BloodBag(i, i <= received) { received = i } }
                }
                Text(
                    if (all) tr("All bags received — the request will close and donors won't see it anymore.", "সব ব্যাগ পেয়েছেন — রিকোয়েস্ট বন্ধ হবে, ডোনাররা আর দেখবেন না।")
                    else tr("${num(received)} of ${num(bags)} received. The request stays open for the remaining ${num(bags - received)}.", "${num(bags)} ব্যাগের ${num(received)}টা পেয়েছেন। বাকি ${num(bags - received)}টার জন্য রিকোয়েস্ট চালু থাকবে।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        confirmButton = { TextButton(onClick = { onClose(donated.toList(), received) }) { Text(if (all) tr("Close", "বন্ধ করুন") else tr("Save", "সেভ করুন")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// হোমে "My Request": নিজের খোলা রিকোয়েস্টের অবস্থা (অস্থায়ী, বন্ধ হলে চলে যায়)
// ------------------------------------------------------------

@Composable
fun MyRequestSection(refreshKey: Int, onOpen: (String) -> Unit) {
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

    var removed by remember { mutableStateOf<Pair<String, DonorResponse>?>(null) }
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
            removed?.let { (rid, d) ->
                RemovedNotice(d.donorName, busyId == rid, onUndo = {
                    act(rid) { ServerApi.updateResponse(rid, "restore", d.donorUid); removed = null }
                }, onDismiss = { removed = null })
            }
            items.forEach { (r, responses) ->
                MyRequestCard(
                    r,
                    responses,
                    busyId,
                    onNotify = { act(r.id) { ServerApi.notifyRequest(r.id) } },
                    onConfirm = { d -> act(r.id) { ServerApi.confirmDonor(r.id, d.donorUid) } },
                    onRemove = { d -> act(r.id) { ServerApi.updateResponse(r.id, "remove", d.donorUid); removed = r.id to d } },
                    onOpen = { onOpen(r.id) },
                    onClose = { closing = r to responses },
                )
            }
        }
    }

    closing?.let { (r, responses) ->
        CloseRequestDialog(
            requestId = r.id,
            bags = r.bags,
            already = r.bagsReceived,
            responses = responses,
            onDismiss = { closing = null },
            onClose = { chosen, got ->
                closing = null
                act(r.id) { ServerApi.closeRequest(r.id, chosen, got) }
            },
        )
    }
}

/** "আমার রিকোয়েস্ট"-এর একটা কার্ড: অবস্থা, রাজি ডোনার (৩ জন পর্যন্ত), নিচে ছোট বাটন */
@Composable
internal fun MyRequestCard(
    r: BloodRequest,
    responses: List<DonorResponse>,
    busyId: String?,
    onNotify: () -> Unit,
    onConfirm: (DonorResponse) -> Unit,
    onRemove: (DonorResponse) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val busy = busyId == r.id
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
                        Rules.formatNeeded(r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { shareRequest(context, r) }) {
                    AppIcon(R.drawable.ic_share, tr("Share", "শেয়ার"), tint = MaterialTheme.colorScheme.primary)
                }
            }
            // সময়, ডোনার জানানো আর রাজি/নিশ্চিত — কাছাকাছি একসাথে। আইকনগুলো উপরের গ্রুপের গোলের
            // ঠিক নিচে মাঝ বরাবর, লেখাগুলো হাসপাতালের নামের সাথে এক লাইনে
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Countdown(r.deadlineMillis, iconSlot = BADGE_SLOT)
                if (r.notified) {
                    SlotRow(R.drawable.ic_notifications_active, MaterialTheme.colorScheme.onSurfaceVariant, BADGE_SLOT) {
                        Text(
                            tr("${num(r.notifiedCount)} donors notified", "${num(r.notifiedCount)} জন ডোনারকে জানানো হয়েছে"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    SlotRow(null, Color.Unspecified, BADGE_SLOT) {
                        SmallAction(
                            tr("Notify nearby donors", "আশেপাশের ডোনারদের জানান"),
                            R.drawable.ic_notifications,
                            { onNotify() },
                            primary = true,
                            enabled = busyId == null || busy,
                            busy = busy,
                        )
                    }
                }
                AgreedLine(responses, iconSlot = BADGE_SLOT)
            }
            Box(Modifier.padding(start = BADGE_SLOT + 12.dp)) { BagRow(r, responses) }

            if (willing.isNotEmpty()) {
                HorizontalDivider()
                willing.sortedByDescending { it.counts() }.take(3).forEach { d ->
                    DonorRow(
                        d,
                        active = true,
                        busy = busy,
                        onConfirm = { onConfirm(d) },
                        onRemove = { onRemove(d) },
                    )
                }
                if (willing.size > 3) {
                    TextButton(onClick = onOpen) {
                        Text(tr("See all ${num(willing.size)}", "সবাই দেখুন (${num(willing.size)})"))
                    }
                }
            }

            HorizontalDivider()
            // নিচে ছোট বাটন পাশাপাশি: বিস্তারিত, রক্ত পেয়েছি (শেয়ার উপরে আইকনে)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallAction(tr("Details", "বিস্তারিত"), R.drawable.ic_list_alt, onOpen)
                SmallAction(tr("Got blood", "রক্ত পেয়েছি"), R.drawable.ic_check_circle, onClose, primary = willing.isNotEmpty())
            }
        }
    }
}
