package com.openemux.mobile.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.retroarch.R

/** The theme's colors; each reads [dark], so the whole UI recomposes when it flips. */
object Oe {
    /** The system's night mode; the activities set it from their configuration. */
    var systemDark by mutableStateOf(true)

    val dark: Boolean
        get() = when (ThemePref.value) {
            ThemePref.LIGHT -> false
            ThemePref.DARK -> true
            else -> systemDark
        }

    fun syncSystem(context: Context) {
        systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun pick(dark: Long, light: Long) = Color(if (this.dark) dark else light)

    val Bg: Color get() = pick(0xFF121216, 0xFFF6F5F9)
    val GameBg: Color get() = pick(0xFF0B0B0E, 0xFF0B0B0E)
    val Surface: Color get() = pick(0xFF1C1C23, 0xFFFFFFFF)
    val Surface2: Color get() = pick(0xFF24242C, 0xFFEFEEF4)
    val Sheet: Color get() = pick(0xFF1A1A20, 0xFFFFFFFF)
    val Border: Color get() = pick(0xFF2B2B35, 0xFFE2E0EA)
    val Divider: Color get() = pick(0xFF2A2A33, 0xFFE7E5ED)
    val Text: Color get() = pick(0xFFF3F3F6, 0xFF17171C)
    val Muted: Color get() = pick(0xFFA3A3B2, 0xFF5C5C6B)
    val Faint: Color get() = pick(0xFF8C8C9A, 0xFF6B6B7E)
    val Chevron: Color get() = pick(0xFF6E6E7C, 0xFF8A8A9A)
    val Accent: Color get() = pick(0xFF7C3AED, 0xFF7C3AED)
    val AccentBright: Color get() = pick(0xFF8B5CF6, 0xFF7C3AED)
    val AccentText: Color get() = pick(0xFFB79CFF, 0xFF6D28D9)
    val AccentTint: Color get() = pick(0xFF221C33, 0xFFEFE8FD)
    val AccentSoft: Color get() = pick(0xFF3B2A6B, 0xFFE4D9FD)
    val AccentOnSoft: Color get() = pick(0xFFD4C4FF, 0xFF5B21B6)
    val Control: Color get() = pick(0xFF1E1E25, 0xFFEBEAF1)
    val Raised: Color get() = pick(0xFF2A2A33, 0xFFE9E7EF)
    val Star: Color get() = pick(0xFFF5C543, 0xFFB07A00)
    val Ok: Color get() = pick(0xFF7DD3A0, 0xFF1F9D57)
    val OkBg: Color get() = pick(0xFF14291E, 0xFFE3F5EA)
    val OkText: Color get() = pick(0xFF9BE3B8, 0xFF13703F)
    val Warn: Color get() = pick(0xFFF5B85A, 0xFFC77800)
    val WarnBg: Color get() = pick(0xFF2A2215, 0xFFFBF0DC)
    val WarnText: Color get() = pick(0xFFF5C27A, 0xFF9A5B00)
    val Danger: Color get() = pick(0xFFFF8A80, 0xFFC0302A)
    val DangerBg: Color get() = pick(0xFF2A1C1F, 0xFFFBE6E4)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val Grotesk = FontFamily(
    Font(R.font.grotesk_medium, FontWeight.Medium),
    Font(R.font.grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.grotesk_bold, FontWeight.Bold),
)

object Type {
    val Display: TextStyle get() = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, color = Oe.Text)
    val Title: TextStyle get() = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, color = Oe.Text)
    val Eyebrow: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp, color = Oe.AccentText)
    val Section: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Oe.Text)
    val SectionCaps: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp, color = Oe.Muted)
    val Body: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = Oe.Text)
    val BodyMedium: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Oe.Text)
    val Small: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Oe.Muted)
    val Caption: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Oe.Muted)
    val Button: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
    val Tag: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.4.sp)
}

/** The theme the user picked: "system", "light" or "dark". */
object ThemePref {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    var value by mutableStateOf(SYSTEM)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("openemux", Context.MODE_PRIVATE)

    fun load(context: Context) {
        value = prefs(context).getString("theme", SYSTEM) ?: SYSTEM
    }

    fun set(context: Context, theme: String) {
        value = theme
        prefs(context).edit().putString("theme", theme).apply()
    }
}

@Composable
fun OpenEmuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = (if (Oe.dark) darkColorScheme() else lightColorScheme()).copy(
            primary = Oe.Accent,
            background = Oe.Bg,
            surface = Oe.Sheet,
            onSurface = Oe.Text,
            onBackground = Oe.Text,
        ),
        content = content,
    )
}
