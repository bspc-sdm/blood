package com.bspc.blood

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.testTag
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

/** বিস্তারিত শিটের এক ভাগ: শিরোনাম, আইকনসহ লাইন, আর দরকার হলে ভাগের নিজের বাটন (যেমন চেম্বারের সিরিয়াল); rows: ছোট সারণি (প্যাথলজির পরীক্ষা ও দাম) */
data class DetailSection(
    val title: String, val lines: List<Pair<Int, String>>, val actions: List<CardAction> = emptyList(), val rows: List<DetailRow> = emptyList(),
    /** ভাগের নিচে পুরো চওড়া ভরাট বাটন (যেমন "এই প্যাথলজিতে প্রেসক্রিপশন পাঠান") */
    val button: CardAction? = null,
)

/** সারণির সারি: বাঁয়ে নাম (নিচে ছোট লেখা), ডানে মান */
data class DetailRow(val name: String, val value: String, val sub: String = "")

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
    /** শিটের নিচে ফোনের পাশে মূল কাজ (DetailSheet-এর main) */
    main: CardAction? = null,
    /**
     * কার্ডের নিজের মূল কাজ (২.৩২.০, ফার্মেসি: "প্রেসক্রিপশন পাঠান") — ভরাট বড় বাটন, তখন ফোন (primary) ছোট আউটলাইন আইকন-বাটন
     * (বিস্তারিতের নিচের বারের মতো)
     */
    cardMain: CardAction? = null,
    /** তথ্যের লাইনের আগে আলাদা ঘর (ফার্মেসি: প্রেসক্রিপশনের হিসাব) */
    info: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var sheet by remember { mutableStateOf(false) }
    if (sheet && sections != null) {
        DetailSheet(title, icon, photo, iconTint, subtitle, status, rating, ratingCount, meta, sections, primary, secondary, onReport, onDismiss = { sheet = false }, main = main)
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
                // লগইন ছাড়া (অতিথি) রিপোর্ট নেই — রিপোর্ট শুধু লগইন অবস্থায় অ্যাপ থেকে ফোন করার পরে
                if (onReport != null && !LoginGate.isGuest) {
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
            if (info != null) {
                Spacer(Modifier.height(10.dp))
                info()
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
            if (cardMain != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = cardMain.onClick, shape = RoundedCornerShape(12.dp), contentPadding = BAR_PADDING,
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 44.dp).testTag("ph-card-send"),
                    ) { BarButtonText(cardMain.label, cardMain.icon) }
                    if (primary != null) {
                        OutlinedButton(
                            onClick = primary.onClick, shape = RoundedCornerShape(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier.size(44.dp).testTag("ph-card-call"),
                        ) { AppIcon(primary.icon ?: R.drawable.ic_call, tr("Call", "ফোন"), Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (secondary != null) {
                        TextButton(onClick = secondary.onClick) { ButtonContent(false, secondary.label, secondary.icon) }
                    }
                }
            } else if (primary != null || secondary != null) {
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

/** নিচের বারের পাশাপাশি দুই বাটন: কম প্যাডিং, এক লাইন */
private val BAR_PADDING = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)

@Composable
private fun BarButtonText(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        AppIcon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

private fun metaLine(rating: Double, ratingCount: Int, meta: String?): String? = listOfNotNull(
    if (ratingCount > 0) "★ " + num(String.format(java.util.Locale.US, "%.1f", rating)) + " (" + num(ratingCount) + ")" else null,
    meta?.takeIf { it.isNotBlank() },
).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

/**
 * বিস্তারিত শিট — সব তালিকায় এক রকম: বড় ছবি, নাম, বিশেষজ্ঞতা, রেটিং · দূরত্ব,
 * তারপর ভাগে ভাগে পুরো তথ্য (কাটা পড়ে না), নিচে আটকানো বার: ফোন (আর মূল কাজ), রেটিং আর রিপোর্ট।
 *
 * নিচের বার (২.২৭.০, পারভেজ): main থাকলে (ফার্মেসি/প্যাথলজি "প্রেসক্রিপশন পাঠান", ডাক্তারের "অ্যাপয়েন্টমেন্ট")
 * সেটা ভরাট আর ফোন পাশে আউটলাইন — দুটো পাশাপাশি, প্রায় আধাআধি, ছোট লেখা যাতে ৩৬০dp-তেও এক লাইনে ধরে।
 * অ্যাম্বুলেন্স (২.২৮.০): main = "অ্যাম্বুলেন্স ডাকুন" (ভরাট), পাশে "ফোন" (আউটলাইন); ট্রিপে থাকা গাড়িতে main নেই।
 * main না থাকলে (ব্লাড ব্যাংক, বাসায় সেবা, ট্রিপে থাকা অ্যাম্বুলেন্স) ফোন আগের মতো একা, পুরো চওড়া, ভরাট।
 * ভাগের ভেতরের বাটনও ভরাট — শুধু ফোনের বাটনগুলো (আইকন ic_call) আউটলাইন।
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
    /** অবস্থার পাশে ছোট ট্যাগ (ফার্মেসি: "২৪ ঘণ্টা") */
    tags: List<String> = emptyList(),
    /** নিচের বারে ফোনের পাশে মূল কাজ (ভরাট) — ছোট লেখা দিন */
    main: CardAction? = null,
    /** মাথার ঠিক নিচে, ভাগগুলোর আগে (২.৩২.০, ফার্মেসি: প্রেসক্রিপশনের হিসাব) */
    header: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // নিচ থেকে ওঠা প্যানেল, কিন্তু টেনে নামালে বন্ধ হয় না (পড়তে পড়তে স্ক্রল করলে হঠাৎ চলে যেত) — শুধু ✕ বা ফোনের ব্যাক
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    // জানালা EdgeToEdgeDialog-এর (Common.kt): সত্যিই পুরো পর্দার, তাই সিস্টেম আসল মাপ পাঠায়। ওপরে স্ট্যাটাস বার আর
    // নিচে নেভিগেশন বার (তিন বাটন বা অঙ্গভঙ্গি) একবারই — প্যানেলের রং বারের পেছন পর্যন্ত, বাটনগুলো বারের ওপরে।
    // স্ট্যাটাস বারের আইকন সাদা: ওপরে পেছনের পাতা আবছা কালো
    EdgeToEdgeDialog(onDismissRequest = onDismiss, dismissOnClickOutside = false, lightStatusBar = false) {
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = cs.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxH),
        ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(bottom = 16.dp)) {
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
                        if (status != null || tags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (status != null) {
                                    Tag(status.text, if (status.good) cs.tertiaryContainer else cs.surfaceVariant, if (status.good) cs.onTertiaryContainer else cs.onSurfaceVariant)
                                }
                                tags.forEach { Tag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                            }
                        }
                    }
                }
                header()
                sections.forEach { sec ->
                    val lines = sec.lines.filter { it.second.isNotBlank() }
                    if (lines.isEmpty() && sec.actions.isEmpty() && sec.rows.isEmpty() && sec.button == null) return@forEach
                    HorizontalDivider(color = cs.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        sec.rows.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                                    if (r.sub.isNotBlank()) Text(r.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(r.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
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
                                    if (a.icon == R.drawable.ic_call) {
                                        OutlinedButton(onClick = a.onClick, shape = RoundedCornerShape(12.dp)) { ButtonContent(false, a.label, a.icon) }
                                    } else {
                                        Button(onClick = a.onClick, shape = RoundedCornerShape(12.dp)) { ButtonContent(false, a.label, a.icon) }
                                    }
                                }
                            }
                        }
                        sec.button?.let { b ->
                            Button(onClick = b.onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                ButtonContent(false, b.label, b.icon)
                            }
                        }
                    }
                }
                footer()
            }
            // নিচে সব সময় দেখা যায় (নেভিগেশন বারের ওপরে — ওপরের safeDrawing): ফোন আর মূল কাজ, রেটিং, রিপোর্ট
            if (primary != null || secondary != null || onReport != null || main != null) {
                HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(top = 12.dp))
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (main != null && primary != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // পাশে থাকলে ফোন ছোট লেখায় ("ফোন"), আউটলাইন; মূল কাজ ভরাট
                            OutlinedButton(onClick = primary.onClick, shape = RoundedCornerShape(12.dp), contentPadding = BAR_PADDING, modifier = Modifier.weight(1f).height(48.dp)) {
                                BarButtonText(if (primary.icon == R.drawable.ic_call) tr("Call", "ফোন") else primary.label, primary.icon)
                            }
                            Button(onClick = main.onClick, shape = RoundedCornerShape(12.dp), contentPadding = BAR_PADDING, modifier = Modifier.weight(1f).height(48.dp)) {
                                BarButtonText(main.label, main.icon)
                            }
                        }
                    } else if (main != null || primary != null) {
                        val a = main ?: primary!!
                        Button(onClick = a.onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            ButtonContent(false, a.label, a.icon)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (secondary != null) TextButton(onClick = secondary.onClick) { ButtonContent(false, secondary.label, secondary.icon ?: R.drawable.ic_star) }
                        Spacer(Modifier.weight(1f))
                        if (onReport != null && !LoginGate.isGuest) TextButton(onClick = onReport) { ButtonContent(false, tr("Report", "রিপোর্ট"), R.drawable.ic_flag) }
                    }
                }
            }
        }
        }
      }
    }
}
