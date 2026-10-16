package com.bspc.blood

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------
// তালিকার কার্ড — পুরো অ্যাপে এক রকম (ডাক্তার, বাসায় সেবা, অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক):
//   [ছবি/আইকন]  নাম                         [অবস্থা]  ⋮
//                বিশেষজ্ঞতা/ধরন (রঙিন)
//                ★ ৪.৫ (১২) · প্রায় ৩ কিমি      ← দূরত্ব শুধু এখানে, একবারই
//   ── তথ্য (আইকনসহ, সর্বোচ্চ কয়েক লাইন) ──
//   [ফোন করুন]                     [রেটিং]
// কার্ড চাপলে নিচ থেকে বিস্তারিত শিট ওঠে (DetailSheet) — একই মাথা, তারপর ভাগে ভাগে সব তথ্য, নিচে ফোন।
// ------------------------------------------------------------

/** কার্ডের উপরে ডানের অবস্থা: খোলা/খালি (সবুজাভ) বা বন্ধ/ব্যস্ত (ধূসর) */
data class CardStatus(val text: String, val good: Boolean)

data class CardAction(val label: String, @DrawableRes val icon: Int?, val onClick: () -> Unit)

/** বিস্তারিত শিটের এক ভাগ: শিরোনাম, আইকনসহ লাইন, আর দরকার হলে ভাগের নিজের বাটন (যেমন চেম্বারের সিরিয়াল) */
data class DetailSection(val title: String, val lines: List<Pair<Int, String>>, val actions: List<CardAction> = emptyList())

/** গোল ছবি; ছবি না থাকলে আইকন */
@Composable
fun CardAvatar(photo: String?, @DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 52.dp) {
    val bmp = remember(photo) { Photo.decodeAvatar(photo) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            AppIcon(icon, null, Modifier.size(size / 2), tint)
        }
    }
}

@Composable
fun DirectoryCard(
    title: String,
    @DrawableRes icon: Int,
    photo: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    status: CardStatus? = null,
    rating: Double = 0.0,
    ratingCount: Int = 0,
    /** দূরত্ব বা অন্য ছোট তথ্য (রেটিংয়ের পাশে) */
    meta: String? = null,
    details: List<Pair<Int, String>> = emptyList(),
    tags: List<String> = emptyList(),
    note: String? = null,
    primary: CardAction? = null,
    secondary: CardAction? = null,
    onReport: (() -> Unit)? = null,
    /** দিলে কার্ড চাপলে এই ভাগগুলো নিয়ে বিস্তারিত শিট খোলে */
    sections: List<DetailSection>? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var sheet by remember { mutableStateOf(false) }
    if (sheet && sections != null) {
        DetailSheet(title, icon, photo, iconTint, subtitle, status, rating, ratingCount, meta, sections, primary, secondary, onReport, onDismiss = { sheet = false })
    }
    val tap: (() -> Unit)? = onClick ?: if (sections != null) ({ sheet = true }) else null
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CardAvatar(photo, icon, iconTint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (status != null) {
                            Spacer(Modifier.width(8.dp))
                            Tag(status.text, if (status.good) cs.tertiaryContainer else cs.surfaceVariant, if (status.good) cs.onTertiaryContainer else cs.onSurfaceVariant)
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    metaLine(rating, ratingCount, meta)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                if (onReport != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                            AppIcon(R.drawable.ic_more_vert, tr("More", "আরও"), Modifier.size(20.dp), cs.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Report", "রিপোর্ট")) },
                                leadingIcon = { AppIcon(R.drawable.ic_flag, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; onReport() },
                            )
                        }
                    }
                }
            }
            if (details.isNotEmpty() || tags.isNotEmpty() || !note.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    details.filter { it.second.isNotBlank() }.forEach { (icon, text) -> IconLine(icon, text) }
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { Tag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                        }
                    }
                    if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (primary != null || secondary != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (primary != null) {
                        Button(onClick = primary.onClick, shape = RoundedCornerShape(12.dp)) {
                            ButtonContent(false, primary.label, primary.icon)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (secondary != null) {
                        TextButton(onClick = secondary.onClick) { ButtonContent(false, secondary.label, secondary.icon) }
                    }
                }
            }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)
    // আকার আর রং অ্যাপের বাকি কার্ডের মতোই (Card-এর নিজের আকার)
    if (tap != null) Card(onClick = tap, modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    else Card(Modifier.fillMaxWidth(), colors = colors) { content() }
}

private fun metaLine(rating: Double, ratingCount: Int, meta: String?): String? = listOfNotNull(
    if (ratingCount > 0) "★ " + num(String.format(java.util.Locale.US, "%.1f", rating)) + " (" + num(ratingCount) + ")" else null,
    meta?.takeIf { it.isNotBlank() },
).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

/**
 * বিস্তারিত শিট — সব তালিকায় এক রকম: বড় ছবি, নাম, বিশেষজ্ঞতা, রেটিং · দূরত্ব,
 * তারপর ভাগে ভাগে পুরো তথ্য (কাটা পড়ে না), নিচে বড় ফোন বাটন, রেটিং আর রিপোর্ট।
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailSheet(
    title: String,
    @DrawableRes icon: Int,
    photo: String?,
    iconTint: Color,
    subtitle: String?,
    status: CardStatus?,
    rating: Double,
    ratingCount: Int,
    meta: String?,
    sections: List<DetailSection>,
    primary: CardAction?,
    secondary: CardAction?,
    onReport: (() -> Unit)?,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // নিচ থেকে ওঠা প্যানেল, কিন্তু টেনে নামালে বন্ধ হয় না (পড়তে পড়তে স্ক্রল করলে হঠাৎ চলে যেত) — শুধু ✕ বা ফোনের ব্যাক
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = cs.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxH),
        ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, tr("Close", "বন্ধ করুন"), Modifier.size(24.dp), cs.onSurfaceVariant) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardAvatar(photo, icon, iconTint, size = 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.primary)
                        metaLine(rating, ratingCount, meta)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                        if (status != null) {
                            Tag(status.text, if (status.good) cs.tertiaryContainer else cs.surfaceVariant, if (status.good) cs.onTertiaryContainer else cs.onSurfaceVariant)
                        }
                    }
                }
                sections.forEach { sec ->
                    val lines = sec.lines.filter { it.second.isNotBlank() }
                    if (lines.isEmpty() && sec.actions.isEmpty()) return@forEach
                    HorizontalDivider(color = cs.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        lines.forEach { (ic, text) ->
                            Row(verticalAlignment = Alignment.Top) {
                                AppIcon(ic, null, Modifier.padding(top = 2.dp).size(18.dp), cs.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                            }
                        }
                        if (sec.actions.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                sec.actions.forEach { a ->
                                    OutlinedButton(onClick = a.onClick, shape = RoundedCornerShape(12.dp)) { ButtonContent(false, a.label, a.icon) }
                                }
                            }
                        }
                    }
                }
                footer()
            }
            // নিচে সব সময় দেখা যায়: ফোন, রেটিং, রিপোর্ট
            if (primary != null || secondary != null || onReport != null) {
                HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(top = 12.dp))
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (primary != null) {
                        Button(onClick = primary.onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            ButtonContent(false, primary.label, primary.icon)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (secondary != null) TextButton(onClick = secondary.onClick) { ButtonContent(false, secondary.label, secondary.icon ?: R.drawable.ic_star) }
                        Spacer(Modifier.weight(1f))
                        if (onReport != null) TextButton(onClick = onReport) { ButtonContent(false, tr("Report", "রিপোর্ট"), R.drawable.ic_flag) }
                    }
                }
            }
        }
        }
      }
    }
}
