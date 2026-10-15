@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
// সব তালিকার উপরে Uber-এর মতো পুরো চওড়া লোকেশন বার: [📍 ঈশ্বরগঞ্জ, ময়মনসিংহ ▾]  ⚙²  🔍
// এলাকা সবসময় চোখের সামনে আর প্রায় পুরো লাইন পায়; ফিল্টার আর খোঁজা ডানে ছোট আইকন।
// কোনো ফিল্টার চালু থাকলে শুধু তখন নিচে ছোট চিপ (✕ চাপলে সরে যায়), যাতে বোঝা যায় তালিকা কেন ছোট।
// ------------------------------------------------------------

/** চালু থাকা একটা ফিল্টার (চিপে দেখায়, ✕ চাপলে সরে) */
data class ActiveFilter(val label: String, val onRemove: () -> Unit)

/** ছোট এলাকা আগে: "ঈশ্বরগঞ্জ, ময়মনসিংহ" — কাটলেও শেষের জেলাটা কাটে (জেলা না থাকলে "সব জেলা") */
fun areaText(district: String?, upazilaId: Int?, unionId: Int? = null): String =
    if (district == null) tr("All districts", "সব জেলা")
    else listOfNotNull(Areas.union(unionId)?.label, Areas.upazila(upazilaId)?.label, Districts.label(district)).joinToString(", ")

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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // লোকেশন বার: পুরো চওড়া
            Surface(
                onClick = onAreaClick,
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_location_on, null, Modifier.size(20.dp), cs.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        areaLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppIcon(R.drawable.ic_expand_more_round, tr("Change area", "এলাকা বদলান"), Modifier.size(20.dp), cs.onSurfaceVariant)
                }
            }
            if (onFilterClick != null) {
                IconButton(onClick = onFilterClick) {
                    BadgedBox(badge = { if (filterCount > 0) Badge(containerColor = cs.primary, contentColor = cs.onPrimary) { Text(num(filterCount)) } }) {
                        AppIcon(R.drawable.ic_tune, tr("Filter", "ফিল্টার"), tint = if (filterCount > 0) cs.primary else cs.onSurface)
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

/** শেষ বাছা এলাকাগুলো (ফোনেই থাকে) */
object RecentAreas {
    private const val FILE = "recent_areas"

    fun get(context: Context): List<Triple<String, Int?, Int?>> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("list", "").orEmpty()
            .split(";").filter { it.isNotBlank() }.mapNotNull { e ->
                val p = e.split("|")
                if (p.size != 3 || p[0].isBlank()) null else Triple(p[0], p[1].toIntOrNull(), p[2].toIntOrNull())
            }

    fun add(context: Context, area: Triple<String, Int?, Int?>) {
        val list = (listOf(area) + get(context).filter { it != area }).take(3)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("list", list.joinToString(";") { "${it.first}|${it.second ?: ""}|${it.third ?: ""}" }).apply()
    }
}

/**
 * এলাকা বাছাই — Uber-এর মতো পুরো পাতা: উপরে একটাই সার্চ বক্স, তারপর "আমার বর্তমান লোকেশন",
 * সাম্প্রতিক এলাকা, আর লিখতে থাকলে জেলা/উপজেলা/ইউনিয়ন একসাথে। একবার চাপলেই হয়ে যায়।
 * (নাম AreaSheet থেকে গেছে, যাতে সব জায়গার ডাক একই থাকে)
 */
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
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var query by remember { mutableStateOf("") }
    val recent = remember { RecentAreas.get(context) }
    val focus = remember { FocusRequester() }
    fun pick(d: String?, u: Int?, n: Int?) {
        if (d != null) RecentAreas.add(context, Triple(d, u, n))
        onNearMe?.invoke(false)
        onChange(d, u, if (showUnion) n else null)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(40) },
                        placeholder = { Text(if (showUnion) tr("District, upazila or union", "জেলা, উপজেলা বা ইউনিয়ন লিখুন") else tr("District or upazila", "জেলা বা উপজেলা লিখুন")) },
                        leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { AppIcon(R.drawable.ic_close, tr("Clear", "মুছুন")) } }) else null,
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                val hits = remember(query) { Areas.search(query, showUnion) }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (query.isBlank()) {
                        if (nearMe != null && onNearMe != null) {
                            item {
                                AreaRow(R.drawable.ic_location_on, tr("My current location", "আমার বর্তমান লোকেশন"), tr("Nearest first", "কাছেরগুলো আগে"), nearMe) {
                                    onNearMe(true)
                                    onDismiss()
                                }
                            }
                        }
                        if (allowAllDistricts) {
                            item { AreaRow(R.drawable.ic_map, tr("All districts", "সব জেলা"), null, district == null && nearMe != true) { pick(null, null, null) } }
                        }
                        if (recent.isNotEmpty()) {
                            item { AreaHeader(tr("Recent", "সাম্প্রতিক")) }
                            items(recent) { (d, u, n) ->
                                AreaRow(R.drawable.ic_history, areaText(d, u, n), null, nearMe != true && d == district && u == upazilaId && n == unionId) { pick(d, u, n) }
                            }
                        }
                        // এখনকার জেলার উপজেলাগুলো, এক চাপে
                        district?.let { d ->
                            item { AreaHeader(tr("Upazilas in ", "") + Districts.label(d) + tr("", "-এর উপজেলা")) }
                            item { AreaRow(R.drawable.ic_location_on, tr("Whole district", "পুরো জেলা") + " · " + Districts.label(d), null, nearMe != true && upazilaId == null) { pick(d, null, null) } }
                            items(Areas.upazilasOf(d)) { u ->
                                AreaRow(R.drawable.ic_location_on, u.label, null, nearMe != true && upazilaId == u.id && unionId == null) { pick(d, u.id, null) }
                            }
                        }
                    } else if (hits.isEmpty()) {
                        item { Text(tr("No area found", "কোনো এলাকা পাওয়া যায়নি"), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        items(hits) { (d, u, n) ->
                            val title = Areas.union(n)?.label ?: Areas.upazila(u)?.label ?: Districts.label(d)
                            val sub = when {
                                n != null -> tr("Union", "ইউনিয়ন") + " · " + listOfNotNull(Areas.upazila(u)?.label, Districts.label(d)).joinToString(", ")
                                u != null -> tr("Upazila", "উপজেলা") + " · " + Districts.label(d)
                                else -> tr("District", "জেলা")
                            }
                            AreaRow(R.drawable.ic_location_on, title, sub, false) { pick(d, u, n) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AreaRow(icon: Int, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { AppIcon(icon, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) AppIcon(R.drawable.ic_check_circle, tr("Selected", "বাছা আছে"), Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
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
