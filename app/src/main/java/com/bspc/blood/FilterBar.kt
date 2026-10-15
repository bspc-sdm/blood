@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------
// সব তালিকার উপরে একটাই সারি: [📍 এলাকা ▾]  [⚙ ফিল্টার ২]  🔍
// এলাকা সবসময় চোখের সামনে (কোন এলাকার তালিকা দেখছেন); বাকি ফিল্টার এক বাটনের ভেতরে।
// কোনো ফিল্টার চালু থাকলে শুধু তখন নিচে ছোট চিপ (✕ চাপলে সরে যায়), যাতে বোঝা যায় তালিকা কেন ছোট।
// ------------------------------------------------------------

/** চালু থাকা একটা ফিল্টার (চিপে দেখায়, ✕ চাপলে সরে) */
data class ActiveFilter(val label: String, val onRemove: () -> Unit)

/** "ময়মনসিংহ · ত্রিশাল" (জেলা না থাকলে "সব জেলা") */
fun areaText(district: String?, upazilaId: Int?, unionId: Int? = null): String =
    if (district == null) tr("All districts", "সব জেলা")
    else listOfNotNull(Districts.label(district), Areas.upazila(upazilaId)?.label, Areas.union(unionId)?.label).joinToString(" · ")

@Composable
fun FilterBar(
    areaLabel: String,
    onAreaClick: () -> Unit,
    filterCount: Int = 0,
    onFilterClick: (() -> Unit)? = null,
    search: String? = null,
    onSearch: ((String) -> Unit)? = null,
    searchHint: String = tr("Search by name", "নাম দিয়ে খুঁজুন"),
    active: List<ActiveFilter> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var searching by remember { mutableStateOf(!search.isNullOrEmpty()) }
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          // এলাকা আর ফিল্টার বাঁ দিকে যতটা জায়গা লাগে; খোঁজার আইকন ডানে (এলাকার নাম অকারণে কাটা পড়ে না)
          Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // এলাকা: বাটনের লেখাতেই কোন এলাকা
            Surface(
                onClick = onAreaClick,
                shape = RoundedCornerShape(12.dp),
                color = cs.secondaryContainer,
                contentColor = cs.onSecondaryContainer,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 40.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(areaLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.size(4.dp))
                    AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(16.dp))
                }
            }
            if (onFilterClick != null) {
                Surface(
                    onClick = onFilterClick,
                    shape = RoundedCornerShape(12.dp),
                    color = if (filterCount > 0) cs.primary else cs.surfaceContainerHigh,
                    contentColor = if (filterCount > 0) cs.onPrimary else cs.onSurface,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            tr("Filter", "ফিল্টার") + if (filterCount > 0) " " + num(filterCount) else "",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
          }
            if (onSearch != null) {
                IconButton(onClick = {
                    if (searching) onSearch("")
                    searching = !searching
                }) {
                    AppIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search, if (searching) tr("Close search", "খোঁজা বন্ধ") else searchHint)
                }
            }
        }
        if (onSearch != null && searching) {
            OutlinedTextField(
                value = search.orEmpty(),
                onValueChange = onSearch,
                placeholder = { Text(searchHint) },
                leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (active.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                active.forEach { f ->
                    InputChip(
                        selected = true,
                        onClick = f.onRemove,
                        label = { Text(f.label, maxLines = 1, modifier = Modifier.widthIn(max = 220.dp), overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

/** নিচ থেকে ওঠা প্যানেল: শিরোনাম, ভেতরের বিষয়, আর "সব মুছুন" / "দেখান" */
@Composable
fun FilterSheet(title: String, onDismiss: () -> Unit, onClear: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (onClear != null) OutlinedButton(onClick = onClear) { Text(tr("Clear all", "সব মুছুন")) }
                Button(onClick = onDismiss) { Text(tr("Show", "দেখান")) }
            }
        }
    }
}

/** এলাকার প্যানেল: (চাইলে) "আমার কাছে", তারপর জেলা/উপজেলা/ইউনিয়ন */
@Composable
fun AreaSheet(
    district: String?,
    upazilaId: Int?,
    unionId: Int?,
    onChange: (String?, Int?, Int?) -> Unit,
    onDismiss: () -> Unit,
    allowAllDistricts: Boolean = false,
    showUnion: Boolean = false,
    nearMe: Boolean? = null,
    onNearMe: ((Boolean) -> Unit)? = null,
) {
    FilterSheet(tr("Area", "এলাকা"), onDismiss) {
        if (nearMe != null && onNearMe != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Near me", "আমার কাছে"), style = MaterialTheme.typography.titleSmall)
                    SmallNote(tr("From your current location", "আপনার বর্তমান লোকেশন থেকে"))
                }
                Switch(checked = nearMe, onCheckedChange = onNearMe)
            }
        }
        if (nearMe != true) {
            SmallNote(tr("Choose district, then upazila if you like.", "জেলা বেছে নিন, চাইলে উপজেলাও।"))
            AreaChips(district, upazilaId, unionId, onChange, allowAllDistricts = allowAllDistricts, showUnion = showUnion)
        }
    }
}

/** ফিল্টার প্যানেলের ভেতরে একটা চালু/বন্ধ সারি */
@Composable
fun FilterSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** ফিল্টার প্যানেলের ভেতরে একটাই বাছাই (আবার চাপলে বাদ) */
@Composable
fun FilterChoice(title: String, options: List<String>, selected: String?, label: (String) -> String, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = selected == o, onClick = { onSelect(if (selected == o) null else o) }, label = { Text(label(o)) })
            }
        }
    }
}
