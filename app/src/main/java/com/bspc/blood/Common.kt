@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** অ্যাপের নাম (আপাতত; চূড়ান্ত হলে শুধু এখানে আর strings.xml-এ বদলাতে হবে) */
const val APP_NAME = "Lifolo"

const val PRIVACY_URL = "https://github.com/bspc-sdm/blood/blob/main/PRIVACY.md"
const val TERMS_URL = "https://github.com/bspc-sdm/blood/blob/main/TERMS.md"
const val SUPPORT_EMAIL = "ekapkbd@gmail.com"
/** শেয়ার করা রিকোয়েস্টের লিংক (সার্ভারের /r/{id} পাতা; অ্যাপ থাকলে সরাসরি অ্যাপে খোলে) */
const val SHARE_BASE = "https://blood-server-ashy.vercel.app/r/"

/** রিকোয়েস্ট WhatsApp/Messenger/SMS-এ পরিচিতদের পাঠানো */
fun shareRequest(context: Context, r: BloodRequest) {
    val noDonor = r.acceptedCount == 0 && !r.fulfilled
    val text = tr(
        "${if (r.urgent) "URGENT: " else ""}${if (noDonor) "No donor yet! " else ""}${r.patientGroup} blood needed — ${r.bags} bag(s) at ${r.hospital}, ${Districts.label(r.placeName)}. " +
            "Needed by ${Rules.formatNeeded(r)}.${if (r.travelFare) " Travel cost will be paid." else ""} Can you help or share? $SHARE_BASE${r.id}",
        "${if (r.urgent) "জরুরি: " else ""}${if (noDonor) "এখনো কোনো ডোনার পাওয়া যায়নি! " else ""}${r.patientGroup} রক্ত দরকার — ${num(r.bags)} ব্যাগ, ${r.hospital}, ${Districts.label(r.placeName)}। " +
            "লাগবে ${Rules.formatNeeded(r)}।${if (r.travelFare) " যাতায়াত খরচ দেওয়া হবে।" else ""} দিতে পারলে বা কাউকে চিনলে সাহায্য করুন: $SHARE_BASE${r.id}",
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, tr("Share request", "রিকোয়েস্ট শেয়ার করুন"))) }
}

/** Google Material Symbols আইকন (res/drawable/ic_*.xml) */
@Composable
fun AppIcon(
    @DrawableRes res: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(res), contentDescription, modifier, tint)
}

/** অ্যাপের লোগো: গোল চিহ্ন + অ্যাপের নাম */
@Composable
fun Logo(iconSize: Dp = 36.dp, showText: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(iconSize).clip(CircleShape).background(BrandTeal),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(R.drawable.ic_bloodtype, null, Modifier.size(iconSize * 0.6f), Color.White)
        }
        if (showText) {
            Spacer(Modifier.width(10.dp))
            Text(
                APP_NAME,
                fontSize = (iconSize.value * 0.78f).sp, // লোগোর নাম একটু বড়
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** শুরুর লোডিং পাতা */
@Composable
fun SplashScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(BrandTeal),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(R.drawable.ic_bloodtype, null, Modifier.size(56.dp), Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            APP_NAME,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** পেছনে যাওয়ার তীর সহ উপরের বার */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
        },
        actions = actions,
    )
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
fun InfoText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** বাটনের ভেতরে: কাজ চলার সময় ঘুরন্ত চিহ্ন, নইলে (আইকন +) লেখা */
@Composable
fun ButtonContent(busy: Boolean, text: String, @DrawableRes icon: Int? = null) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
    } else {
        if (icon != null) {
            AppIcon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** রক্তের গ্রুপের গোল ব্যাজ */
@Composable
fun BloodGroupBadge(group: String, size: Dp = 52.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            group,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.34f).sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** ছোট লেবেল (যেমন "জরুরি", "বন্ধ") */
@Composable
fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun UrgentTag() = Tag(tr("Urgent", "জরুরি"), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)

/** আইকন + লেখার এক লাইন */
@Composable
fun IconLine(@DrawableRes icon: Int, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, null, Modifier.size(16.dp), color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RequestCard(r: BloodRequest, distanceKm: Double?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BloodGroupBadge(r.patientGroup)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.hospital,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (r.urgent && r.isActive) {
                            Spacer(Modifier.width(8.dp))
                            UrgentTag()
                        }
                    }
                    IconLine(
                        R.drawable.ic_location_on,
                        listOfNotNull(Districts.label(r.placeName), distanceKm?.let { Rules.formatKm(it) }).joinToString(" · "),
                    )
                    IconLine(R.drawable.ic_schedule, Rules.formatNeeded(r))
                    if (r.travelFare) IconLine(R.drawable.ic_directions_run, tr("Travel cost paid", "যাতায়াত খরচ দেওয়া হবে"))
                    // কেউ রাজি হয়েছেন কি না (শুধু সংখ্যা)
                    if (r.isActive) DonorStatusTag(r)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    // কত ব্যাগ: রক্তের ব্যাগের ছবিতে সংখ্যা, এক নজরে চোখে পড়ে
                    BloodBag(r.bags, filled = r.isActive, description = tr("${r.bags} bag(s) needed", "${num(r.bags)} ব্যাগ দরকার"))
                    if (!r.isActive) {
                        Spacer(Modifier.height(4.dp))
                        Tag(
                            tr("Closed", "বন্ধ"),
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RequestInfo(r: BloodRequest, distanceKm: Double?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BloodGroupBadge(r.patientGroup, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        tr("${num(r.bags)} bag${if (r.bags > 1) "s" else ""} needed", "${num(r.bags)} ব্যাগ রক্ত দরকার"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (r.urgent && r.isActive) UrgentTag()
                    if (!r.isActive) {
                        Tag(tr("Closed", "বন্ধ"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            InfoLine(R.drawable.ic_local_hospital, tr("Hospital", "হাসপাতাল"), r.hospital)
            InfoLine(
                R.drawable.ic_location_on,
                tr("Area", "এলাকা"),
                listOfNotNull(Districts.label(r.placeName), distanceKm?.let { Rules.formatKm(it) }).joinToString(" · "),
            )
            InfoLine(R.drawable.ic_calendar_month, tr("Needed by", "কবে লাগবে"), Rules.formatNeeded(r))
            InfoLine(
                R.drawable.ic_directions_run,
                tr("Travel cost", "যাতায়াত খরচ"),
                if (r.travelFare) tr("Will be paid", "দেওয়া হবে") else tr("Not provided", "দেওয়া হবে না"),
            )
            if (r.note.isNotBlank()) InfoLine(R.drawable.ic_info, tr("Note", "অতিরিক্ত তথ্য"), r.note)
            InfoLine(R.drawable.ic_person_outline, tr("Requested by", "রিকোয়েস্ট দিয়েছেন"), r.requesterName)
        }
    }
}

@Composable
fun InfoLine(@DrawableRes icon: Int, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        AppIcon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun Disclaimer() {
    Text(
        tr(
            "The blood bank makes the final decision after cross-matching.",
            "রক্ত দেওয়া যাবে কি না, চূড়ান্তভাবে ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** খালি অবস্থা: মাঝখানে হালকা (কম অপাসিটি) আইকন আর লেখা */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.alpha(0.55f).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(icon, null, Modifier.size(64.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

fun callPhone(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun sendEmail(context: Context, to: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to"))) }
}

/** রক্ত লাগার সময় পর্যন্ত প্রতি সেকেন্ডে কমতে থাকা কাউন্টডাউন */
@Composable
fun Countdown(targetMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(targetMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = targetMillis - now
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(R.drawable.ic_schedule, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            if (left > 0) Rules.formatDuration(left) else tr("Time's up", "সময় হয়ে গেছে"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (left > 0) {
            Spacer(Modifier.width(6.dp))
            Text(tr("left", "বাকি"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ডোনার যে রিকোয়েস্টে রাজি হয়েছেন: "প্রসেসিং" কার্ড, কাউন্টডাউন সহ */
@Composable
fun ProcessingCard(r: BloodRequest, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_sync, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    tr("Processing", "প্রসেসিং"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BloodGroupBadge(r.patientGroup, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(r.hospital, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Rules.formatNeeded(r), style = MaterialTheme.typography.bodySmall)
                }
            }
            Countdown(r.deadlineMillis)
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    } else {
        Card(Modifier.fillMaxWidth(), colors = colors) { content() }
    }
}

/** রিকোয়েস্টে ডোনারের অবস্থা: এখনো কেউ নেই / কতজন রাজি / পাওয়া গেছে */
@Composable
fun DonorStatusTag(r: BloodRequest) {
    when {
        r.fulfilled -> Tag(
            tr("Donors found", "ডোনার পাওয়া গেছে"),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        r.acceptedCount > 0 -> Tag(
            tr("${num(r.acceptedCount)} agreed", "${num(r.acceptedCount)} জন রাজি"),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Tag(
            tr("No donor yet", "এখনো কোনো ডোনার নেই"),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
