package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** খোঁজার ঘর সহ তালিকা থেকে একটা বেছে নেওয়া। noneLabel দিলে "সব" ধরনের বিকল্প (null) থাকে। */
@Composable
fun <T> SearchPickDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onPick: (T?) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String? = null,
    subtitle: ((T) -> String?)? = null,
    footer: (@Composable (query: String) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter { label(it).contains(q, true) || (subtitle?.invoke(it)?.contains(q, true) == true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("Search", "খুঁজুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    if (noneLabel != null && q.isEmpty()) {
                        item {
                            PickRow(noneLabel, null) { onPick(null) }
                            HorizontalDivider()
                        }
                    }
                    items(shown) { t ->
                        PickRow(label(t), subtitle?.invoke(t)) { onPick(t) }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                tr("Nothing found", "কিছু পাওয়া যায়নি"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    if (footer != null) item { footer(q) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(text: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** জেলা / উপজেলা / ইউনিয়ন বাছাইয়ের তিনটা চিপ (একটা বদলালে নিচেরগুলো মুছে যায়) */
@Composable
fun AreaChips(
    district: String?,
    upazilaId: Int?,
    unionId: Int?,
    onChange: (String?, Int?, Int?) -> Unit,
    allowAllDistricts: Boolean = true,
    showUnion: Boolean = true,
    /** একই সারিতে শেষে বাড়তি চিপ (যেমন "এখন খোলা"), যাতে আলাদা সারি না লাগে */
    trailing: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var open by remember { mutableStateOf(0) } // 1 জেলা, 2 উপজেলা, 3 ইউনিয়ন
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = district != null,
            onClick = { open = 1 },
            label = { Text(district?.let { Districts.label(it) } ?: tr("All districts", "সব জেলা")) },
            leadingIcon = { AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp)) },
        )
        if (district != null) {
            FilterChip(
                selected = upazilaId != null,
                onClick = { open = 2 },
                label = { Text(Areas.upazila(upazilaId)?.label ?: tr("Upazila", "উপজেলা")) },
            )
        }
        if (showUnion && upazilaId != null) {
            FilterChip(
                selected = unionId != null,
                onClick = { open = 3 },
                label = { Text(Areas.union(unionId)?.label ?: tr("Union", "ইউনিয়ন")) },
            )
        }
        trailing?.invoke()
    }
    when (open) {
        1 -> SearchPickDialog(
            title = tr("District", "জেলা"),
            items = Districts.ALL.sortedBy { Districts.label(it.bn) },
            label = { Districts.label(it.bn) },
            noneLabel = if (allowAllDistricts) tr("All districts", "সব জেলা") else null,
            onPick = { open = 0; onChange(it?.bn, null, null) },
            onDismiss = { open = 0 },
        )
        2 -> SearchPickDialog(
            title = tr("Upazila", "উপজেলা"),
            items = Areas.upazilasOf(district.orEmpty()),
            label = { it.label },
            noneLabel = tr("All upazilas", "সব উপজেলা"),
            onPick = { open = 0; onChange(district, it?.id, null) },
            onDismiss = { open = 0 },
        )
        3 -> SearchPickDialog(
            title = tr("Union", "ইউনিয়ন"),
            items = Areas.unionsOf(upazilaId ?: -1),
            label = { it.label },
            noneLabel = tr("All unions", "সব ইউনিয়ন"),
            onPick = { open = 0; onChange(district, upazilaId, it?.id) },
            onDismiss = { open = 0 },
        )
    }
}

/**
 * হাসপাতাল বেছে নেওয়া: জেলা/উপজেলা দিয়ে তালিকা, খোঁজা, আর না পেলে যোগ করার অনুরোধ।
 */
@Composable
fun HospitalPickerDialog(
    initialDistrict: String?,
    initialUpazilaId: Int? = null,
    onPick: (Hospital) -> Unit,
    onDismiss: () -> Unit,
) {
    var district by remember { mutableStateOf(initialDistrict) }
    var upazilaId by remember { mutableStateOf(initialUpazilaId) }
    var list by remember { mutableStateOf<List<Hospital>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(district) {
        val d = district ?: return@LaunchedEffect
        loading = true
        errorMsg = null
        runCatching { Hospitals.inDistrict(d, fresh = true) }.onSuccess { list = it }.onFailure { errorMsg = it.message }
        loading = false
    }

    val q = query.trim()
    // ঢাকায় আড়াই হাজারের মতো — খোঁজা আলাদা থ্রেডে, যাতে লেখার সময় আটকে না যায়
    val shown by produceState(initialValue = emptyList<Hospital>(), list, upazilaId, q) {
        value = withContext(Dispatchers.Default) {
            val inArea = list.filter { upazilaId == null || it.upazilaId == upazilaId || it.upazilaId == null }
            if (q.isEmpty()) inArea else {
                val direct = inArea.filter { it.nameEn.contains(q, true) || it.nameBn.contains(q) }
                (direct + Hospitals.similar(q, inArea, 10)).distinct()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(tr("Choose hospital", "হাসপাতাল বেছে নিন"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(tr("Hospital / clinic name", "হাসপাতাল / ক্লিনিকের নাম")) },
                        leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfoText(infoMsg)
                    ErrorText(errorMsg)
                }
                LazyColumn(Modifier.weight(1f)) {
                    when {
                        district == null -> item {
                            Text(tr("Choose the district first", "আগে জেলা বেছে নিন"), modifier = Modifier.padding(20.dp))
                        }
                        loading -> item { LoadingBox() }
                        else -> {
                            items(shown, key = { it.id }) { h ->
                                PickRow(h.label, listOf(Hospitals.typeLabel(h.type), Areas.label(null, h.upazilaId)).filter { it.isNotBlank() }.joinToString(" · ")) {
                                    onPick(h)
                                }
                            }
                            item {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        tr("Can't find it?", "তালিকায় নেই?"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(onClick = { adding = true }) {
                                        AppIcon(R.drawable.ic_add, null, Modifier.size(18.dp))
                                        Text("  " + tr("Request to add", "যোগ করার অনুরোধ"))
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) }
                }
            }
        }
    }

    if (adding) {
        AddHospitalDialog(
            initialName = q,
            initialDistrict = district,
            initialUpazilaId = upazilaId,
            onExisting = { h ->
                adding = false
                onPick(h)
            },
            onSent = { msg ->
                adding = false
                infoMsg = msg
            },
            onDismiss = { adding = false },
        )
    }
}

/** হাসপাতাল যোগের অনুরোধ: লেখার সময়ই মিলে যাওয়া হাসপাতাল দেখায়, যাতে একই হাসপাতাল দুবার না আসে */
@Composable
fun AddHospitalDialog(
    initialName: String,
    initialDistrict: String?,
    initialUpazilaId: Int?,
    onExisting: (Hospital) -> Unit,
    onSent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latin = initialName.any { it in 'A'..'Z' || it in 'a'..'z' }
    var nameEn by remember { mutableStateOf(if (latin) initialName else "") }
    var nameBn by remember { mutableStateOf(if (!latin) initialName else "") }
    var type by remember { mutableStateOf("private_hospital") }
    var district by remember { mutableStateOf(initialDistrict) }
    var upazilaId by remember { mutableStateOf(initialUpazilaId) }
    var unionId by remember { mutableStateOf<Int?>(null) }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<Hospital?>(null) }
    var list by remember { mutableStateOf<List<Hospital>>(emptyList()) }

    LaunchedEffect(district) {
        val d = district ?: return@LaunchedEffect
        runCatching { Hospitals.inDistrict(d) }.onSuccess { list = it }
    }
    val inArea = list.filter { upazilaId == null || it.upazilaId == upazilaId || it.upazilaId == null }
    val suggestions = (Hospitals.similar(nameEn, inArea, 4) +
        if (nameBn.trim().length >= 3) inArea.filter { it.nameBn.contains(nameBn.trim()) }.take(3) else emptyList()).distinct()

    FormDialog(
        title = tr("Add a hospital", "হাসপাতাল যোগের অনুরোধ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("An admin checks it before it appears in the list.", "অ্যাডমিন যাচাই করার পর তালিকায় আসবে।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AreaChips(district, upazilaId, unionId, { d, u, n -> district = d; upazilaId = u; unionId = n }, allowAllDistricts = false)
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it.take(120) },
                    label = { Text(tr("Name in English", "ইংরেজিতে নাম")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nameBn,
                    onValueChange = { nameBn = it.take(120) },
                    label = { Text(tr("Name in Bangla (optional)", "বাংলায় নাম (ঐচ্ছিক)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                tr("Is it one of these? Tap to choose.", "এর কোনোটা কি? থাকলে ট্যাপ করুন।"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            suggestions.forEach { h ->
                                Text(
                                    "• " + h.label,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.fillMaxWidth().clickable { onExisting(h) }.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hospitals.TYPES.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(Hospitals.typeLabel(t)) })
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(200) },
                    label = { Text(tr("Address (optional)", "ঠিকানা (ঐচ্ছিক)")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text(tr("Phone (optional)", "ফোন (ঐচ্ছিক)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val d = district
                    errorMsg = when {
                        d == null -> tr("Choose a district", "জেলা বেছে নিন")
                        nameEn.none { it in 'A'..'Z' || it in 'a'..'z' } -> tr("Enter the name in English", "ইংরেজিতে নাম লিখুন")
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@TextButton
                    scope.launch {
                        busy = true
                        runCatching { Hospitals.request(TitleCase.name(nameEn), nameBn.trim(), type, d, upazilaId, unionId, address.trim(), phone) }
                            .onSuccess { r ->
                                when (r) {
                                    is HospitalRequestResult.Exists -> existing = r.hospital
                                    HospitalRequestResult.Merged -> onSent(
                                        tr(
                                            "Someone already asked to add this hospital — your request was added to theirs.",
                                            "এই হাসপাতালের অনুরোধ আগেই এসেছে — আপনারটাও তার সাথে যোগ হলো।",
                                        ),
                                    )
                                    HospitalRequestResult.Sent -> onSent(
                                        tr(
                                            "Request sent. You can choose it after an admin approves.",
                                            "অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে বেছে নিতে পারবেন।",
                                        ),
                                    )
                                }
                            }
                            .onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
            ) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )

    existing?.let { h ->
        AlertDialog(
            onDismissRequest = { existing = null },
            title = { Text(tr("Already in the list", "তালিকায় আগে থেকেই আছে")) },
            text = { Text(h.label + "\n" + Areas.label(h.district, h.upazilaId)) },
            confirmButton = { TextButton(onClick = { onExisting(h) }) { Text(tr("Choose this", "এটাই বেছে নিন")) } },
            dismissButton = { TextButton(onClick = { existing = null }) { Text(tr("Back", "ফিরে যান")) } },
        )
    }
}

/** অ্যাডমিন: হাসপাতাল যোগের অনুরোধ, সম্ভাব্য ডুপ্লিকেট সহ */
@Composable
fun AdminHospitalRequests(items: List<HospitalRequestItem>, busyId: String?, onResolve: (String, String, String?) -> Unit) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { r ->
            val h = r.hospital
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(h.nameEn, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (h.nameBn.isNotBlank()) Text(h.nameBn, style = MaterialTheme.typography.bodyMedium)
                    Text(Hospitals.typeLabel(h.type) + " · " + Areas.label(h.district, h.upazilaId, h.unionId), style = MaterialTheme.typography.bodySmall)
                    if (h.address.isNotBlank()) Text(h.address, style = MaterialTheme.typography.bodySmall)
                    if (h.phone.isNotBlank()) Text(num(h.phone), style = MaterialTheme.typography.bodySmall)
                    if (r.count > 1) Text(tr("Requested by ${r.count} people", "${num(r.count)} জন অনুরোধ করেছেন"), style = MaterialTheme.typography.bodySmall)
                    if (r.possibleDuplicates.isNotEmpty()) {
                        Text(tr("Possible duplicates — merge into:", "সম্ভাব্য একই হাসপাতাল — মিলিয়ে দিন:"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        r.possibleDuplicates.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.label + " · " + Areas.label(null, d.upazilaId), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onResolve(h.id, "merge", d.id) }, enabled = busyId == null) { Text(tr("Merge", "মেলান")) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onResolve(h.id, "approve", null) },
                            enabled = busyId == null,
                            label = { Text(if (busyId == h.id) "…" else tr("Approve", "অনুমোদন")) },
                        )
                        AssistChip(onClick = { onResolve(h.id, "reject", null) }, enabled = busyId == null, label = { Text(tr("Reject", "বাতিল")) })
                    }
                }
            }
        }
    }
}

/** ছোট লেখা সহ সারি (ব্যবহার: খোঁজার ফলাফলের নিচে) */
@Composable
fun SmallNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(min = 0.dp))
}

/**
 * লেখার ঘর থাকা ফর্মের পপআপ। সাধারণ AlertDialog-এ লেখার ঘর আর অন্য অনেক কিছু একসাথে থাকলে
 * টেস্টে স্ক্রিন স্থির হচ্ছিল না, তাই সাধারণ Dialog + নিজের লেআউট।
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    content()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}
