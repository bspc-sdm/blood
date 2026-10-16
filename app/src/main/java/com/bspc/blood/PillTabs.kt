@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.animation.animateContentSize

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// অ্যাপের সব ট্যাব: নিচে দাগওয়ালা লম্বা ট্যাবের বদলে আইকনসহ ছোট "পিল" বাটন, পাশাপাশি।
// বেছে নেওয়াটা ভরাট রঙে; বেশি হলে পাশে সরানো যায়।

data class PillTab(
    val label: String,
    @DrawableRes val icon: Int? = null,
    /** বেছে নেওয়া অবস্থায় অন্য আইকন (যেমন ভরাট) */
    @DrawableRes val selectedIcon: Int? = null,
    /** পাশে ছোট সংখ্যা (যেমন অপেক্ষায় থাকা অনুরোধ) */
    val count: Int = 0,
)

/**
 * secondary = ভেতরের (দ্বিতীয় স্তরের) ট্যাব: হালকা রঙ আর একটু ছোট, যাতে উপরেরটার সাথে গুলিয়ে না যায়।
 */
@Composable
fun PillTabs(
    selected: Int,
    tabs: List<PillTab>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    // বেছে নেওয়া ট্যাব যেন সবসময় পুরোটা দেখা যায়: চাপলে সারিটা নিজে সরে ট্যাবটাকে মাঝখানে আনে
    // (দুই পাশের ট্যাবও একটু দেখা যায়, বোঝা যায় আরও আছে)। আগে সারি শুধু হাতে সরানো যেত, নিজে সরত না।
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val padPx = with(density) { 16.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val widths = remember(tabs.size) { mutableStateListOf(*Array(tabs.size) { 0 }) }
    var viewport by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf(true) }
    val ready = viewport > 0 && widths.isNotEmpty() && widths.all { it > 0 }
    LaunchedEffect(selected, ready, viewport) {
        if (!ready || selected !in widths.indices) return@LaunchedEffect
        val left = padPx + (0 until selected).sumOf { widths[it] + gapPx }
        val target = (left + widths[selected] / 2 - viewport / 2).coerceIn(0, scroll.maxValue)
        if (first) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        first = false
    }
    Row(
        modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = if (secondary) 4.dp else 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            val bg = when {
                sel && !secondary -> cs.primary
                sel -> cs.secondaryContainer
                secondary -> Color.Transparent
                else -> cs.surfaceContainerHigh
            }
            val fg = when {
                sel && !secondary -> cs.onPrimary
                sel -> cs.onSecondaryContainer
                else -> cs.onSurfaceVariant
            }
            Surface(
                selected = sel,
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(50),
                color = bg,
                contentColor = fg,
                border = if (secondary && !sel) BorderStroke(1.dp, cs.outlineVariant) else null,
                modifier = Modifier
                    .onSizeChanged { if (i < widths.size) widths[i] = it.width }
                    .heightIn(min = if (secondary) 32.dp else 36.dp)
                    .semantics { role = Role.Tab },
            ) {
                Row(
                    Modifier.padding(horizontal = if (secondary) 12.dp else 14.dp, vertical = if (secondary) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val ic = if (sel) t.selectedIcon ?: t.icon else t.icon
                    if (ic != null) AppIcon(ic, null, Modifier.size(if (secondary) 16.dp else 18.dp))
                    Text(
                        t.label,
                        style = if (secondary) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (t.count > 0) {
                        Surface(shape = RoundedCornerShape(50), color = if (sel && !secondary) cs.onPrimary else cs.primary) {
                            Text(
                                num(t.count),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel && !secondary) cs.primary else cs.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * কার্ডের ভেতরের ছোট কাজের বাটন (আইকন + লেখা, গোল)। পুরো চওড়া বড় বাটনের বদলে পাশাপাশি কয়েকটা।
 * primary = মূল কাজ (ভরাট রঙ), নইলে হালকা।
 */
@Composable
fun SmallAction(
    text: String,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (primary) cs.primary else cs.secondaryContainer
    val fg = if (primary) cs.onPrimary else cs.onSecondaryContainer
    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(50),
        color = if (enabled) bg else cs.surfaceContainerHighest,
        contentColor = if (enabled) fg else cs.onSurfaceVariant,
        modifier = modifier.heightIn(min = 32.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
            } else if (icon != null) {
                AppIcon(icon, null, Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** নিচের কোণে ভাসমান ছোট বাটন (বড় FAB-এর বদলে): রিকোয়েস্ট দিন, প্রশ্ন করুন, টিপস দিন */
@Composable
fun SmallFab(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // তালিকা নিচে স্ক্রল করলে শুধু আইকন, উপরে স্ক্রল করলে আবার লেখাসহ (FabScroll)
    val expanded = FabScroll.expanded.value
    androidx.compose.runtime.DisposableEffect(Unit) {
        FabScroll.expanded.value = true
        onDispose { }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = cs.primary,
        contentColor = cs.onPrimary,
        shadowElevation = 4.dp,
        modifier = modifier.heightIn(min = 44.dp).animateContentSize(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (expanded) 16.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, if (expanded) null else text, Modifier.size(20.dp))
            androidx.compose.animation.AnimatedVisibility(
                expanded,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** ভাসমান বাটনের লেখা দেখানো/লুকানো: তালিকার ঘরে Modifier.nestedScroll(FabScroll.connection) */
object FabScroll {
    val expanded = androidx.compose.runtime.mutableStateOf(true)
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            // আঙুল উপরে (তালিকা নিচে যায়) = ছোট; আঙুল নিচে (তালিকা উপরে যায়) = বড়
            if (available.y < -6f) expanded.value = false else if (available.y > 6f) expanded.value = true
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
}
