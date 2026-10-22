package com.openemux.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.retroarch.R

object Oe {
    val Bg = Color(0xFF121216)
    val GameBg = Color(0xFF0B0B0E)
    val Surface = Color(0xFF1C1C23)
    val Surface2 = Color(0xFF24242C)
    val Sheet = Color(0xFF1A1A20)
    val Border = Color(0xFF2B2B35)
    val Divider = Color(0xFF2A2A33)
    val Text = Color(0xFFF3F3F6)
    val Muted = Color(0xFFA3A3B2)
    val Faint = Color(0xFF8C8C9A)
    val Chevron = Color(0xFF6E6E7C)
    val Accent = Color(0xFF7C3AED)
    val AccentBright = Color(0xFF8B5CF6)
    val AccentText = Color(0xFFB79CFF)
    val AccentTint = Color(0xFF221C33)
    val Star = Color(0xFFF5C543)
    val Ok = Color(0xFF7DD3A0)
    val OkBg = Color(0xFF14291E)
    val OkText = Color(0xFF9BE3B8)
    val Warn = Color(0xFFF5B85A)
    val WarnBg = Color(0xFF2A2215)
    val WarnText = Color(0xFFF5C27A)
    val Danger = Color(0xFFFF8A80)
    val DangerBg = Color(0xFF2A1C1F)
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
    val Display = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, color = Oe.Text)
    val Title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, color = Oe.Text)
    val Eyebrow = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp, color = Oe.AccentText)
    val Section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Oe.Text)
    val SectionCaps = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp, color = Oe.Muted)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = Oe.Text)
    val BodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Oe.Text)
    val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Oe.Muted)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Oe.Muted)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
    val Tag = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.4.sp)
}

@Composable
fun OpenEmuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Oe.Accent,
            background = Oe.Bg,
            surface = Oe.Sheet,
            onSurface = Oe.Text,
            onBackground = Oe.Text,
        ),
        content = content,
    )
}
