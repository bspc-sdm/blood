package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

enum class Lang(val code: String) { EN("en"), BN("bn") }
enum class ThemeMode(val code: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

/**
 * এই ফোনের নিজস্ব পছন্দ: অ্যাপের ভাষা আর থিম।
 * Compose-এর state, তাই বদলালে সব স্ক্রিন সাথে সাথে নতুন ভাষা/থিমে আঁকা হয়।
 */
object AppPrefs {
    private const val FILE = "app_prefs"
    val lang = mutableStateOf(Lang.EN)
    val theme = mutableStateOf(ThemeMode.SYSTEM)

    fun init(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // প্রথমবার: ফোনের ভাষা বাংলা হলে বাংলা, নইলে ইংরেজি
        val phoneLang = if (java.util.Locale.getDefault().language == "bn") Lang.BN else Lang.EN
        lang.value = Lang.entries.firstOrNull { it.code == p.getString("lang", null) } ?: phoneLang
        theme.value = ThemeMode.entries.firstOrNull { it.code == p.getString("theme", null) } ?: ThemeMode.SYSTEM
    }

    fun setLang(context: Context, l: Lang) {
        lang.value = l
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("lang", l.code).apply()
    }

    fun setTheme(context: Context, t: ThemeMode) {
        theme.value = t
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("theme", t.code).apply()
    }
}

val isBangla: Boolean get() = AppPrefs.lang.value == Lang.BN

/** পাতায় ঢুকে কেটে দেওয়া নোটিশ (যেমন ৯৯৯-এর লাইন) — একবার কাটলে ওই পাতায় আর আসে না */
object Dismissed {
    private const val FILE = "dismissed_notes"

    fun isOn(context: Context, key: String): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, false) }.getOrDefault(false)

    fun set(context: Context, key: String) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply() }
    }
}

/** বর্তমান ভাষা অনুযায়ী লেখা: tr("English", "বাংলা") */
fun tr(en: String, bn: String): String = if (isBangla) bn else TitleCase.ui(en)

/**
 * ইংরেজি ছোট লেখায় প্রতিটা শব্দের প্রথম অক্ষর বড় (Title Case) — শিরোনাম, বাটন, ট্যাব, লেবেল।
 * a, of, in, to… (শুরুতে না হলে) ছোট থাকে; আগে থেকে বড় শব্দ (ICU, MBBS) আর একক (km, kg) যেমন আছে তেমন।
 * পুরো বাক্যে (দাঁড়ি/প্রশ্নচিহ্ন আছে, বা লম্বা) লাগে না — বাক্য বড় হাতের হলে পড়তে কষ্ট।
 */
object TitleCase {
    private val MINOR = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "for", "so", "yet", "as", "at", "by", "in", "of", "off", "on", "per",
        "to", "up", "via", "with", "from", "into", "onto", "over", "than", "vs",
    )
    private val UNITS = setOf("km", "kg", "mg", "ml", "g", "cm", "yrs", "min", "sec", "e.g.", "i.e.")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** বদলানো রূপ → আসল লেখা (ভুল/তথ্যের বার্তা Title Case ছাড়া দেখাতে) */
    private val original = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun isSentence(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (t.last() in ".?!:;") return true
        if (Regex("[.?!;](\\s|$)").containsMatchIn(t)) return true
        return t.split(' ').count { it.isNotBlank() } > 8
    }

    /** অ্যাপের নিজের লেখা (শুধু ছোট লেখায়) */
    fun ui(s: String): String {
        if (s.length > 80) return s
        return cache.getOrPut(s) {
            val out = if (isSentence(s)) s else words(s)
            if (out != s) original[out] = s
            out
        }
    }

    /** বার্তা (ভুল, তথ্য): Title Case-এর আগের রূপ — "Enter a Valid Email" নয়, "Enter a valid email" */
    fun plain(s: String): String = original[s] ?: s

    /**
     * ব্যবহারকারীর লেখা নাম (মানুষ, হাসপাতাল, দোকান) — বাংলায় কিছু বদলায় না।
     * হাইফেন বা ঊর্ধ্বকমার পরের অংশও বড় হাতের: "Al-Amin", "O'Brien" (কিন্তু "Mother's" ঠিক থাকে)
     */
    fun name(s: String): String =
        words(s.trim().replace(Regex("\\s+"), " "))
            .replace(Regex("(?<=[A-Za-z][-'’])([a-z])(?=[a-z])")) { it.value.uppercase() }

    private fun words(s: String): String {
        val parts = s.split(" ")
        val last = parts.indexOfLast { it.isNotBlank() }
        return parts.mapIndexed { i, w ->
            val core = w.trim { !it.isLetterOrDigit() }.lowercase()
            val k = w.indexOfFirst { it.isLetter() }
            when {
                k < 0 -> w
                !w[k].isLowerCase() -> w
                core in UNITS -> w
                i != 0 && i != last && core in MINOR -> w
                // শব্দের আগে অঙ্ক থাকলে (যেমন "3rd") বদলায় না
                w.substring(0, k).any { it.isDigit() } -> w
                else -> w.substring(0, k) + w[k].uppercaseChar() + w.substring(k + 1)
            }
        }.joinToString(" ")
    }
}

/** ভাষা অনুযায়ী অঙ্ক (বাংলায় ০-৯) */
fun num(n: Number): String = num(n.toString())
fun num(s: String): String =
    if (isBangla) s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("") else s

// ---- থিম ----

/**
 * শান্ত রং: বিপদের সময় লাল চারদিকে উত্তেজনা বাড়ায়, তাই মূল রং টিল (নীলাভ-সবুজ)।
 * লাল শুধু ভুলের বার্তায় (error), "জরুরি" ট্যাগে তার হালকা রূপ।
 */
val BrandTeal = Color(0xFF1F6F78)

private val LightColors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDEF),
    onPrimaryContainer = Color(0xFF00363C),
    secondary = Color(0xFF4F6366),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7EA),
    onSecondaryContainer = Color(0xFF0B1F22),
    tertiary = Color(0xFF3B6939),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EED3),
    onTertiaryContainer = Color(0xFF0F2E0F),
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF7A2420),
    background = Color(0xFFF7FAFA),
    surface = Color(0xFFF7FAFA),
    surfaceVariant = Color(0xFFDCE5E6),
    onSurfaceVariant = Color(0xFF3F4949),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F5),
    surfaceContainer = Color(0xFFEBF0F0),
    surfaceContainerHigh = Color(0xFFE5EBEB),
    surfaceContainerHighest = Color(0xFFDFE6E6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD3DB),
    onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF004F56),
    onPrimaryContainer = Color(0xFFA6EFF7),
    secondary = Color(0xFFB4CACD),
    onSecondary = Color(0xFF1F3437),
    secondaryContainer = Color(0xFF354A4D),
    onSecondaryContainer = Color(0xFFD0E6E9),
    tertiary = Color(0xFFA1D39B),
    onTertiary = Color(0xFF0B390D),
    tertiaryContainer = Color(0xFF23501F),
    onTertiaryContainer = Color(0xFFBCF0B5),
    errorContainer = Color(0xFF5C1F1B),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1415),
    surface = Color(0xFF0F1415),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainerLowest = Color(0xFF0A0F10),
    surfaceContainerLow = Color(0xFF171D1E),
    surfaceContainer = Color(0xFF1B2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GetBloodTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.theme.value) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography) {
        // ছোঁয়ার ঘর ৪৮ থেকে ৪০dp: চিপ আর বাটনের চারপাশে বাড়তি ফাঁকা জায়গা কমে
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp, content = content)
    }
}

/** বাংলা অক্ষর Hind Siliguri-র, ইংরেজি অক্ষর Roboto-র (একই ফাইলে মেলানো; Hind-এর ইংরেজি ফাঁকা ফাঁকা দেখাত) — দুটোই খোলা লাইসেন্স (OFL / Apache) */
val HindSiliguri = FontFamily(
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold),
    Font(R.font.hind_siliguri_bold, FontWeight.ExtraBold),
)

/** Material-এর মাপ থেকে একটু ছোট, আর বাংলার জন্য লাইনের উচ্চতা ঠিক করা */
private val AppTypography: Typography = Typography().let { t ->
    fun TextStyle.f(size: Int, line: Int) = copy(fontFamily = HindSiliguri, fontSize = size.sp, lineHeight = line.sp)
    Typography(
        displayLarge = t.displayLarge.f(52, 60),
        displayMedium = t.displayMedium.f(42, 50),
        displaySmall = t.displaySmall.f(34, 42),
        headlineLarge = t.headlineLarge.f(28, 36),
        headlineMedium = t.headlineMedium.f(25, 32),
        headlineSmall = t.headlineSmall.f(22, 29),
        titleLarge = t.titleLarge.f(20, 27),
        titleMedium = t.titleMedium.f(16, 23),
        titleSmall = t.titleSmall.f(14, 20),
        bodyLarge = t.bodyLarge.f(15, 22),
        bodyMedium = t.bodyMedium.f(14, 20),
        bodySmall = t.bodySmall.f(12, 17),
        labelLarge = t.labelLarge.f(14, 18),
        labelMedium = t.labelMedium.f(12, 16),
        labelSmall = t.labelSmall.f(11, 15),
    )
}
