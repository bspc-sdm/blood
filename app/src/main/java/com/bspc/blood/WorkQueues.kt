@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------
// "আমার কাজ"-এর কাজের তালিকা (মালিকের অনুমোদন, ২৪ অক্টোবর ২০২৬ — mywork-review/CONTRACT.md §২):
//  • সর্বোচ্চ ৪টা স্থির ট্যাব, ১–২ শব্দ, পাশে সংখ্যা; ছোট ফোনেও লেখা কাটা যায় না (পাশে সরানো সারি নয় — সমান চওড়া,
//    দরকারে দুই লাইনে)। বেছে নেওয়াটা দুইভাবে বোঝা যায়: ভরাট রঙ আর মোটা লেখা।
//  • প্রতিটা সারিতে একটাই মূল বাটন; বাকি কাজ "আরও"-র ভেতরে (চাপলে সারির নিচে খোলে — ডায়ালগের ওপর ডায়ালগ নয়)।
//  • খালি থাকলে এক লাইনের লেখা।
//  • চাপার জায়গা অন্তত ৪৮dp।
// ------------------------------------------------------------

object QueueText {
    fun more(): String = tr("More", "আরও")
    fun less(): String = tr("Less", "কম")
}

/**
 * স্থির ট্যাব (২–৪টা): সমান চওড়া, পাশে সরে না, লেখা কাটে না। secondary = ভেতরের ভাগ (নতুন · চলছে · শেষ) — হালকা।
 * আইকন শুধু ৩টা বা কম ট্যাবে (৪টায় জায়গা লেখার)।
 */
@Composable
fun FixedTabs(
    selected: Int,
    tabs: List<PillTab>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (secondary) 2.dp else 6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (secondary) 6.dp else 8.dp),
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
                shape = RoundedCornerShape(if (secondary) 12.dp else 16.dp),
                color = bg,
                contentColor = fg,
                border = if (secondary && !sel) BorderStroke(1.dp, cs.outlineVariant) else null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.Tab }
                    .testTag("tab_$i"),
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ic = if (sel) t.selectedIcon ?: t.icon else t.icon
                    if (ic != null && !secondary && tabs.size <= 3) {
                        AppIcon(ic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    // সংখ্যা লেখার সাথেই ("বুকিং ১২") — জায়গা না হলে সংখ্যাটা নিচের লাইনে যায়, কাটে না
                    val text = androidx.compose.ui.text.buildAnnotatedString {
                        append(t.label)
                        if (t.count > 0) {
                            append(" ")
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = if (sel && !secondary) cs.onPrimary else cs.primary))
                            append(num(t.count))
                            pop()
                        }
                    }
                    Text(
                        text,
                        style = if (secondary) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * সারির কাজ: বাঁয়ে একটাই মূল বাটন (primary), ডানে "আরও" — চাপলে নিচে বাকি কাজগুলো (more)।
 * more-এর ভেতরে যেকোনো বাটন বসে (কল/মেসেজ, রসিদ, টাকা চাওয়া — তাদের নিজের ডায়ালগসহ)।
 */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    key: String = "",
    showPrimary: Boolean = true,
    showMore: Boolean = true,
    primary: @Composable () -> Unit = {},
    more: @Composable () -> Unit = {},
) {
    if (!showPrimary && !showMore) return
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f)) { if (showPrimary) primary() }
            if (showMore) {
                TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("more_actions")) {
                    AppIcon(if (open) R.drawable.ic_expand_more_round else R.drawable.ic_more_vert, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (open) QueueText.less() else QueueText.more())
                }
            }
        }
        if (open && showMore) {
            FlowRow(
                Modifier.fillMaxWidth().testTag("more_actions_open"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { more() }
        }
    }
}

/** সারির মূল বাটন — ভরাট, অন্তত ৪৮dp উঁচু */
@Composable
fun PrimaryAction(text: String, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = 48.dp)) {
        ButtonContent(busy, text, icon)
    }
}

/** কাজের ভাগে কিছু নেই: এক লাইন */
@Composable
fun OneLineEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("empty_line"),
    )
}

/** বাংলা অঙ্ক → ইংরেজি অঙ্ক (খোঁজায় দুটোই চলে) */
fun latinDigits(s: String): String = s.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")

/** ফোন নম্বর সবসময় ইংরেজি অঙ্কে (চুক্তি: প্রেসক্রিপশনের কোড, রসিদ/অর্ডারের আইডি আর ফোন — ল্যাটিন অঙ্ক) */
fun phoneText(p: String): String = latinDigits(p)

/** বাসা থেকে নমুনার চাওয়া সময় পেরিয়ে গেছে কি না — সার্ভার "expired" বলে; পুরোনো তথ্যে দিন আর সময়ের শেষ ঘণ্টা দেখে */
object LabSlot {
    private val END = mapOf("early" to 9, "morning" to 12, "afternoon" to 18)

    fun expired(h: LabHome, now: Long = System.currentTimeMillis()): Boolean {
        if (h.status == "expired") return true
        if (h.status != "requested" || h.date <= 0L) return false
        val end = END[h.slot] ?: 24
        // h.date = ঢাকার সেই দিনের UTC মধ্যরাত (LabPriceText.today) — ঢাকার "end"টা বাজে UTC-তে date + end ঘণ্টা − ৬ ঘণ্টা
        return now > h.date + end * 3_600_000L - Rules.DHAKA_OFFSET_MS
    }
}

/** WorkNav.target "orders:<সার্ভারের ভাগ>" → তালিকার তিন ভাগ (নতুন · চলছে · শেষ); অন্য কিছু → নতুন */
fun WorkNav.seg(target: String?): String {
    val raw = target?.substringAfter("orders:", "")?.trim().orEmpty()
    return if (raw.isBlank()) "new" else WorkQueue.of(raw)
}

/** ডায়ালগ আলাদা জানালা — সেখানেও testTag যেন resource-id হয় (Maestro-র id দিয়ে খোঁজা) */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.testTagsAsIds(): Modifier = this.semantics { testTagsAsResourceId = true }
