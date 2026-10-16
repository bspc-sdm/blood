package com.bspc.blood

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------
// তালিকার কার্ড — পুরো অ্যাপে এক রকম (ডাক্তার, বাসায় সেবা, অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক):
//   [ছবি/আইকন]  নাম                         [অবস্থা]  ⋮
//                বিশেষজ্ঞতা/ধরন (রঙিন)
//                ★ ৪.৫ (১২) · প্রায় ৩ কিমি      ← দূরত্ব শুধু এখানে, একবারই
//   ── তথ্য (আইকনসহ, সর্বোচ্চ কয়েক লাইন) ──
//   [ফোন করুন]                     [রেটিং]
// ------------------------------------------------------------

/** কার্ডের উপরে ডানের অবস্থা: খোলা/খালি (সবুজাভ) বা বন্ধ/ব্যস্ত (ধূসর) */
data class CardStatus(val text: String, val good: Boolean)

data class CardAction(val label: String, @DrawableRes val icon: Int?, val onClick: () -> Unit)

/** গোল ছবি; ছবি না থাকলে আইকন */
@Composable
fun CardAvatar(photo: String?, @DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.primary) {
    val bmp = remember(photo) { Photo.decodeAvatar(photo) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            AppIcon(icon, null, Modifier.size(26.dp), tint)
        }
    }
}

@Composable
fun DirectoryCard(
    title: String,
    avatar: @Composable () -> Unit,
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
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                avatar()
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
                    val metaParts = listOfNotNull(
                        if (ratingCount > 0) "★ " + num(String.format(java.util.Locale.US, "%.1f", rating)) + " (" + num(ratingCount) + ")" else null,
                        meta?.takeIf { it.isNotBlank() },
                    )
                    if (metaParts.isNotEmpty()) {
                        Text(metaParts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    else Card(Modifier.fillMaxWidth(), colors = colors) { content() }
}
