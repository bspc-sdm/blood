package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/*
 * "আমার কাজ" ট্যাব (২.২০.০, পারভেজ ১৭ অক্টোবর)।
 * সেবা ও অ্যাপয়েন্টমেন্ট ট্যাব এখন সবার জন্য শুধু তালিকা। নিজের অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি আর
 * পেশাদারের চেম্বার বা বাসায় সেবা — সব এই ট্যাবে। ট্যাবটা নিচের বারে আসে শুধু যাঁর এগুলোর কোনোটা আছে
 * (যাচাই চলছে বা বাতিল হলেও, যাতে অবস্থা আর কারণ দেখা যায়)। যাঁর নেই তাঁর বার আগের মতো চার ট্যাবের।
 * নতুন ব্যবসা যোগ করা: প্রোফাইলের "আপনার কি কোনো ব্যবসা আছে?" (অতিথির বেলায় সেটিংসে)।
 */

/** ব্যবসার ধরন: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি */
internal val BUSINESS_KINDS = listOf("ambulance", "pharmacy", "bloodbank", "lab")

internal fun businessName(kind: String): String = when (kind) {
    "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
    "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
    "bloodbank" -> tr("Blood bank", "ব্লাড ব্যাংক")
    else -> tr("Lab", "প্যাথলজি")
}

internal fun businessIcon(kind: String): Int = when (kind) {
    "ambulance" -> R.drawable.ic_ambulance
    "pharmacy" -> R.drawable.ic_storefront
    "bloodbank" -> R.drawable.ic_bloodtype
    else -> R.drawable.ic_local_hospital
}

/** নিজের পাতার শিরোনাম: "আমার অ্যাম্বুলেন্স" … */
internal fun myBusinessTitle(kind: String): String = when (kind) {
    "ambulance" -> tr("My ambulances", "আমার অ্যাম্বুলেন্স")
    "pharmacy" -> tr("My pharmacy", "আমার ফার্মেসি")
    "bloodbank" -> tr("My blood banks", "আমার ব্লাড ব্যাংক")
    else -> tr("My lab", "আমার প্যাথলজি")
}

object WorkNav {
    /** কোন ব্যবসা যোগ করবেন — বেছে নেওয়ার ডায়ালগ (MainActivity দেখায়) */
    val picker = mutableStateOf(false)

    /** নোটিফিকেশন থেকে ফার্মেসি/প্যাথলজির কোন ট্যাব: "orders" (নতুন অর্ডার/অনুরোধ → "নতুন" ভাগ) | "shop" (ওষুধ খোঁজার অনুরোধ) — MyWorkTabs.kt */
    val target = mutableStateOf<String?>(null)

    /** "ব্যবসা যোগ করুন": লগইন না থাকলে আগে লগইন আর নাম-মোবাইল (রক্তের তথ্য নয়), তারপর বেছে নেওয়া */
    fun startAdd() {
        LoginGate.require(tr("Log in to add your business", "আপনার ব্যবসা যোগ করতে লগইন করুন")) { picker.value = true }
    }
}

/** নিজের কী কী আছে (সার্ভার থেকে) */
data class WorkSummary(
    val ambulances: List<Ambulance> = emptyList(),
    val pharmacy: Pharmacy? = null,
    val bloodBanks: List<BloodBank> = emptyList(),
    val lab: Lab? = null,
    /** অনুমোদিত পেশাদার ব্যাজ (ডাক্তার, নার্স, পুষ্টিবিদ, ট্রেইনার) — চেম্বার বা বাসায় সেবা এখানেই */
    val professional: String? = null,
) {
    fun owns(kind: String): Boolean = when (kind) {
        "ambulance" -> ambulances.isNotEmpty()
        "pharmacy" -> pharmacy != null
        "bloodbank" -> bloodBanks.isNotEmpty()
        else -> lab != null
    }

    val hasBusiness: Boolean get() = BUSINESS_KINDS.any { owns(it) }
    val any: Boolean get() = hasBusiness || professional != null
}

object MyWork {
    val summary = mutableStateOf(WorkSummary())

    /** অন্তত একবার আনা হয়েছে (তার আগে প্রোফাইলে "আপনার কি কোনো ব্যবসা আছে?" দেখানো হয় না — মালিকের কাছে ঝলক দিত) */
    val loaded = mutableStateOf(false)

    /** শেষবার আনতে গিয়ে কোন ভুল হয়েছিল (প্রোফাইলে দেখায়, "আবার চেষ্টা করুন" সহ); সব এলে null */
    val error = mutableStateOf<String?>(null)

    /** নিচের বারে "আমার কাজ" দেখাবে কি না */
    val has: Boolean get() = summary.value.any

    fun clear() {
        summary.value = WorkSummary()
        loaded.value = false
        error.value = null
    }

    /** একবার ব্যর্থ হলে (ধীর সার্ভার, নেটওয়ার্ক) আরেকবার চেষ্টা */
    private suspend fun <T> twice(block: suspend () -> T): Result<T> {
        val first = runCatching { block() }
        if (first.isSuccess) return first
        if (first.exceptionOrNull() is kotlinx.coroutines.CancellationException) return first
        kotlinx.coroutines.delay(1500)
        return runCatching { block() }
    }

    /** কোনো একটা আনতে না পারলে (ইন্টারনেট) সেটার আগের হিসাবই থাকে — ট্যাব হঠাৎ হারিয়ে যায় না */
    suspend fun refresh(badge: String?) {
        if (LoginGate.isGuest) {
            clear()
            return
        }
        val old = summary.value
        coroutineScope {
            val amb = async { twice { Ambulances.mine() } }
            val ph = async { twice { Pharmacies.mine() } }
            val bb = async { twice { BloodBanks.mine() } }
            val lb = async { twice { RxNet.labMine() } }
            val a = amb.await()
            val p = ph.await()
            val b = bb.await()
            val l = lb.await()
            // অপেক্ষার মাঝে লগআউট হলে আগের মানুষের তথ্য বসানো যাবে না
            if (LoginGate.isGuest) return@coroutineScope
            summary.value = WorkSummary(
                ambulances = a.getOrElse { old.ambulances },
                pharmacy = if (p.isSuccess) p.getOrNull() else old.pharmacy,
                bloodBanks = b.getOrElse { old.bloodBanks },
                lab = if (l.isSuccess) l.getOrNull() else old.lab,
                professional = badge?.takeIf { it in PROVIDER_TYPES },
            )
            val failed = listOf("ambulance" to a, "pharmacy" to p, "bloodbank" to b, "lab" to l).filter { it.second.isFailure }
            // বাতিল (পাতা বদলানো) ভুল নয়
            if (failed.any { it.second.exceptionOrNull() is kotlinx.coroutines.CancellationException }) return@coroutineScope
            if (failed.isEmpty()) {
                loaded.value = true
                error.value = null
            } else {
                error.value = failed.joinToString(" · ") { (k, r) -> businessName(k) + ": " + (r.exceptionOrNull()?.message ?: "?") }
            }
        }
    }
}

/** অনুমোদনের অবস্থা — শুধু যেটা চোখে পড়া দরকার (চালু থাকলে কিছু লেখা হয় না) */
private fun statusNote(status: String): String? = when (status) {
    "pending" -> tr("Under review", "যাচাই চলছে")
    "rejected" -> tr("Not approved", "অনুমোদন হয়নি")
    "suspended" -> tr("Suspended", "বন্ধ করা হয়েছে")
    else -> null
}

/** একাধিক থাকলে: কয়টা যাচাই চলছে / বাতিল / বন্ধ */
private fun statusCounts(statuses: List<String>): List<String> = listOf("pending", "rejected", "suspended").mapNotNull { s ->
    val n = statuses.count { it == s }
    if (n == 0) null else when (s) {
        "pending" -> tr("${num(n)} under review", "${num(n)}টা যাচাই চলছে")
        "rejected" -> tr("${num(n)} not approved", "${num(n)}টা অনুমোদন হয়নি")
        else -> tr("${num(n)} suspended", "${num(n)}টা বন্ধ")
    }
}

// ------------------------------------------------------------
// "আমার কাজ" ট্যাব
// ------------------------------------------------------------

@Composable
fun WorkTab(onOpenBusiness: (String) -> Unit, onOpenChamber: () -> Unit) {
    val s = MyWork.summary.value
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(tr("My work", "আমার কাজ"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        // পেশাদার: চেম্বার (ডাক্তার, পুষ্টিবিদ, ট্রেইনার) বা বাসায় সেবা (নার্স)
        s.professional?.let { type ->
            item(key = "pro") {
                WorkRow(
                    Badges.icon(type),
                    if (type == "nurse") tr("My home care", "আমার বাসায় সেবা") else tr("My chamber", "আমার চেম্বার"),
                    if (type == "nurse") Badges.label(type) else Badges.label(type) + tr(" · bookings, patients, setup", " · বুকিং, রোগী, সেটআপ"),
                    onOpenChamber,
                )
            }
        }
        if (s.ambulances.isNotEmpty()) item(key = "ambulance") {
            val live = s.ambulances.filter { it.status == "approved" }
            WorkRow(
                businessIcon("ambulance"),
                businessName("ambulance"),
                (listOf(num(s.ambulances.size) + tr(" in total", "টা")) +
                    (if (live.isNotEmpty()) listOf(num(live.count { it.available }) + tr(" free", "টা খালি")) else emptyList()) +
                    statusCounts(s.ambulances.map { it.status })).joinToString(" · "),
            ) { onOpenBusiness("ambulance") }
        }
        s.pharmacy?.let { ph ->
            item(key = "pharmacy") {
                WorkRow(businessIcon("pharmacy"), businessName("pharmacy"), listOfNotNull(ph.name, statusNote(ph.status)).joinToString(" · ")) { onOpenBusiness("pharmacy") }
            }
        }
        if (s.bloodBanks.isNotEmpty()) item(key = "bloodbank") {
            WorkRow(
                businessIcon("bloodbank"),
                businessName("bloodbank"),
                (
                    listOf(if (s.bloodBanks.size == 1) s.bloodBanks[0].name else num(s.bloodBanks.size) + tr(" blood banks", "টা")) +
                        statusCounts(s.bloodBanks.map { it.status })
                    ).joinToString(" · "),
            ) { onOpenBusiness("bloodbank") }
        }
        s.lab?.let { lab ->
            item(key = "lab") {
                WorkRow(businessIcon("lab"), businessName("lab"), listOfNotNull(lab.name, statusNote(lab.status)).joinToString(" · ")) { onOpenBusiness("lab") }
            }
        }
        // যে ধরনের ব্যবসা এখনো নেই, সেটা যোগ করা (একই ধরনের আরেকটা অ্যাম্বুলেন্স/ব্লাড ব্যাংক — সেই পাতার ভেতর থেকে)
        if (BUSINESS_KINDS.any { !s.owns(it) }) item(key = "add") {
            OutlinedButton(onClick = { WorkNav.picker.value = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Add another business", "আরেকটা ব্যবসা যোগ করুন"), R.drawable.ic_add)
            }
        }
    }
}

@Composable
private fun WorkRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                AppIcon(icon, null, Modifier.padding(10.dp).size(24.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** নিজের এক ধরনের ব্যবসার পাতা (নতুন যোগ করাও এখান থেকেই — কিছু না থাকলে পাতাটাই ফর্ম দেখায়) */
@Composable
fun MyBusinessScreen(kind: String, profile: Profile, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(myBusinessTitle(kind), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (kind) {
                "ambulance" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MyAmbulancesSection(profile)
                }
                "pharmacy" -> MyPharmacyPanel(profile)
                "bloodbank" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MyBloodBanksSection(profile)
                }
                else -> MyLabPanel(profile)
            }
        }
    }
}

/** কোন ব্যবসা যোগ করবেন — যেটা আগে থেকে আছে সেটা চাপলে নিজের পাতাই খোলে */
@Composable
fun AddBusinessDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("What is your business?", "আপনার কোন ব্যবসা?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BUSINESS_KINDS.forEach { k ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(k) },
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(businessIcon(k), null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                            Text(businessName(k), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            if (MyWork.summary.value.owns(k)) Text(tr("Added", "যোগ করা আছে"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(
                        "Doctors, nurses and health experts: apply for a professional badge in your profile.",
                        "ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞ: প্রোফাইলের “পেশাদার ব্যাজ” থেকে আবেদন করুন।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/**
 * প্রোফাইলে: "আপনার কি কোনো ব্যবসা আছে?" — যাঁর এখনো কোনো ব্যবসা নেই।
 * "না" চাপলে কার্ডটা আর আসে না, প্রোফাইলের তালিকায় সাধারণ এক লাইন "ব্যবসা যোগ করুন" থাকে।
 */
@Composable
fun BusinessQuestionCard(onYes: () -> Unit, onNo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_storefront, null, Modifier.size(24.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    tr("Do you have a business?", "আপনার কি কোনো ব্যবসা আছে?"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                tr(
                    "Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.",
                    "অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                androidx.compose.material3.Button(onClick = onYes) { Text(tr("Yes, add it", "হ্যাঁ, যোগ করুন")) }
                TextButton(onClick = onNo) { Text(tr("No", "না")) }
            }
        }
    }
}
