package com.bspc.blood

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------
// "ওষুধ খুঁজুন" — কতটা লাগবে / কতটা আছে (backend/docs/MEDFIND.md)। একক ওষুধের ধরন অনুযায়ী: ট্যাবলেট/ক্যাপসুল — পাতা
// (শুরুতে) বা পিস; সিরাপ/সাসপেনশন — বোতল; ইনজেকশন — টি/ভায়াল; বাকি — টি। সার্ভারের MedQty আর ওয়েবের medfind.ts একই নিয়ম।
// ------------------------------------------------------------

object MedQty {
    /** ক্রেতা যত চাইতে পারেন */
    const val MAX = 999

    /** দোকান যত আছে বলতে পারে */
    const val HAVE_MAX = 9999

    /** ধরনের সাথে মেলে এমন একক; প্রথমটা শুরুতে */
    fun unitsFor(form: String?): List<String> {
        val f = (form ?: "").lowercase()
        return when {
            f.contains("inject") || f.contains("infusion") || f.contains("vaccine") -> listOf("vial")
            f.contains("tablet") || f.contains("capsule") || f.contains("cozycap") -> listOf("strip", "pcs")
            f.contains("nebuliser") || f.contains("respirator") -> listOf("unit")
            listOf("syrup", "suspension", "elixir", "linctus", "oral solution").any { f.contains(it) } -> listOf("bottle")
            else -> listOf("unit")
        }
    }

    /** বাছাইয়ের বোতামে এককের নাম */
    fun unitName(u: String): String = when (u) {
        "strip" -> tr("Strip", "পাতা")
        "pcs" -> tr("Pieces", "পিস")
        "bottle" -> tr("Bottle", "বোতল")
        "vial" -> tr("Vial", "টি/ভায়াল")
        else -> tr("Pieces", "টি")
    }

    /** লেখা সংখ্যার পরের শব্দ */
    fun unitWord(u: String?): String = when (u) {
        "strip" -> if (isBangla) "পাতা" else "strips"
        "pcs" -> if (isBangla) "পিস" else "pcs"
        "bottle" -> if (isBangla) "বোতল" else "bottles"
        "vial" -> if (isBangla) "টি/ভায়াল" else "vials"
        else -> if (isBangla) "টি" else "pcs"
    }

    /** "৩ পাতা", "১০ পিস", "২টি/ভায়াল", "১টি" — "3 strips", "1 bottle" */
    fun text(qty: Int, u: String?): String {
        if (isBangla) {
            val n = num(qty)
            return when (u) {
                "strip" -> "$n পাতা"
                "pcs" -> "$n পিস"
                "bottle" -> "$n বোতল"
                "vial" -> "${n}টি/ভায়াল"
                else -> "${n}টি"
            }
        }
        val one = qty == 1
        return "$qty " + when (u) {
            "strip" -> if (one) "strip" else "strips"
            "bottle" -> if (one) "bottle" else "bottles"
            "vial" -> if (one) "vial" else "vials"
            else -> if (one) "pc" else "pcs"
        }
    }

    /** "৩ পাতা লাগবে" (পরিমাণ ছাড়া পুরোনো খোঁজে খালি) */
    fun need(qty: Int?, u: String?): String = if (qty == null) "" else if (isBangla) "${text(qty, u)} লাগবে" else "Needs ${text(qty, u)}"

    /** লেখা সংখ্যা → ১…max, নইলে null; বাংলা অঙ্কও চলে */
    fun parse(s: String, max: Int = MAX): Int? {
        val t = s.trim().map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("")
        if (!Regex("^\\d{1,4}$").matches(t)) return null
        return t.toInt().takeIf { it in 1..max }
    }

    /**
     * ক্রেতা যা দেখেন: "আছে — ২০ পিস · পুরোটা আছে" (চাওয়ার সমান বা বেশি), "আছে — ১০ পিস (আপনার দরকার ২০)" (কম)।
     * শুধু যে দোকানে আছে — না থাকলে দোকান উত্তরই দেয় না।
     */
    fun reply(f: MyFind, s: FindShop): String {
        val have = s.haveQty ?: return tr("Has it", "আছে বলেছে")
        val t = text(have, s.haveUnit ?: f.unit)
        return when {
            f.qty != null && have >= f.qty -> if (isBangla) "আছে — $t · পুরোটা আছে" else "Has $t · all you need"
            f.qty != null -> if (isBangla) "আছে — $t (আপনার দরকার ${num(f.qty)})" else "Has $t (you need ${f.qty})"
            else -> if (isBangla) "আছে — $t" else "Has $t"
        }
    }
}

/** "কতটা লাগবে": − [সংখ্যা] + আর একক (একের বেশি হলে বাছাই) */
@Composable
fun QtyPicker(form: String, qty: Int, unit: String, onQty: (Int) -> Unit, onUnit: (String) -> Unit) {
    val units = MedQty.unitsFor(form)
    // লেখার ঘর নিজের লেখা রাখে (মুছে নতুন সংখ্যা লেখার মাঝে খালি থাকতে পারে); ঠিক সংখ্যা হলেই ওপরে যায়
    var text by remember { mutableStateOf(num(qty)) }
    LaunchedEffect(qty) { if (MedQty.parse(text) != qty) text = num(qty) }
    Column(Modifier.fillMaxWidth().testTag("medfind_qty"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("How much do you need", "কতটা লাগবে"), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−", tr("Less", "কম"), qty > 1, "qty_minus") { onQty((qty - 1).coerceAtLeast(1)) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v.take(3)
                            MedQty.parse(text)?.let(onQty)
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        // বাইরের গোল ফ্রেমটাই সীমানা — ভেতরের ঘরের আলাদা দাগ নয়
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                        modifier = Modifier.width(76.dp).testTag("qty_input").semantics { contentDescription = tr("Amount", "পরিমাণ") },
                    )
                    StepButton("+", tr("More", "বেশি"), qty < MedQty.MAX, "qty_plus") { onQty((qty + 1).coerceAtMost(MedQty.MAX)) }
                }
            }
            if (units.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    units.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = u == unit,
                            onClick = { onUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(i, units.size),
                            icon = {},
                            modifier = Modifier.testTag("qty_unit_$u"),
                        ) { Text(MedQty.unitName(u), maxLines = 1) }
                    }
                }
            } else {
                Text(MedQty.unitName(units[0]), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("qty_unit_${units[0]}"))
            }
        }
    }
}

@Composable
private fun StepButton(sign: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }) {
        Text(
            sign, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

/**
 * দোকানের উত্তর একটা খোঁজে: আছে হলে কতটা আছে লিখে পাঠান (ক্রেতার এককে) — আবার পাঠালে সংখ্যা বদলায়। "নেই" নেই: না থাকলে
 * কিছু না করলেই চলে, বা চুপচাপ "বাদ দিন" (শুধু নিজের তালিকা থেকে সরে, ক্রেতা কিছু জানেন না)।
 */
@Composable
fun FindAnswerRow(unit: String, haveQty: Int?, busy: Boolean, enabled: Boolean, onAnswer: (Int) -> Unit, onDismiss: () -> Unit) {
    var editing by remember(haveQty) { mutableStateOf(false) }
    var text by remember(haveQty) { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    if (haveQty != null && !editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isBangla) "আপনার উত্তর: আছে — ${MedQty.text(haveQty, unit)}" else "You said: have ${MedQty.text(haveQty, unit)}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).testTag("find_said"),
            )
            TextButton(onClick = { text = haveQty.toString(); err = null; editing = true }, enabled = enabled) { Text(tr("Change", "বদলান")) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("How many do you have", "কতটা আছে"), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(4); err = null },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp).testTag("find_have_qty").semantics { contentDescription = tr("How many do you have", "কতটা আছে") },
            )
            Text(MedQty.unitWord(unit), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("find_have_unit"))
            Button(
                onClick = {
                    val n = MedQty.parse(text, MedQty.HAVE_MAX)
                    if (n == null) {
                        err = if (isBangla) "১ থেকে ৯৯৯৯-এর মধ্যে একটা সংখ্যা লিখুন" else "Write a number from 1 to 9999"
                    } else {
                        editing = false
                        onAnswer(n)
                    }
                },
                enabled = enabled,
                modifier = Modifier.testTag("find_have_send"),
            ) { ButtonContent(busy, tr("Send", "পাঠান")) }
        }
        ErrorText(err)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (haveQty == null) {
                TextButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.testTag("find_dismiss")) {
                    Text(tr("Dismiss", "বাদ দিন"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                TextButton(onClick = { editing = false; err = null }, enabled = enabled) { Text(tr("Cancel", "বাতিল")) }
            }
        }
    }
}
