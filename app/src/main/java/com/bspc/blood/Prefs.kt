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

val BloodRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = BloodRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF2E7D32),
    tertiaryContainer = Color(0xFFDDF3DE),
    onTertiaryContainer = Color(0xFF0B3A10),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5DDDA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF4F3),
    surfaceContainer = Color(0xFFFCEEEC),
    surfaceContainerHigh = Color(0xFFF6E8E6),
    surfaceContainerHighest = Color(0xFFF1E2E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB7),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFF8FD694),
    tertiaryContainer = Color(0xFF1E5122),
    onTertiaryContainer = Color(0xFFAAF2AE),
    background = Color(0xFF1A1111),
    surface = Color(0xFF1A1111),
    surfaceVariant = Color(0xFF534341),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
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
