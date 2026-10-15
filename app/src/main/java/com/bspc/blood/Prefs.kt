package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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

/** বর্তমান ভাষা অনুযায়ী লেখা: tr("English", "বাংলা") */
fun tr(en: String, bn: String): String = if (isBangla) bn else en

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

@Composable
fun GetBloodTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.theme.value) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
