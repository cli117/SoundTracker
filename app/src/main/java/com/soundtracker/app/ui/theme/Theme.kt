package com.soundtracker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Anthropic / Claude Warm Editorial Studio Palette
val StudioBg = Color(0xFFF5F2EA)              // Warm Parchment / Alabaster
val StudioSurface = Color(0xFFFAF8F3)         // Pure Warm Ivory Card Surface
val StudioSurfaceElevated = Color(0xFFEDE9DF) // Soft Sand / Warm Stone Inset
val StudioBorder = Color(0xFFE0DBD0)          // Architectural 1dp Hairline Stone

val CyanPrimary = Color(0xFFC96442)           // Claude Signature Terracotta / Clay
val CyanDim = Color(0xFFA84F31)               // Deep Burnt Sienna
val VioletAccent = Color(0xFF1F1E1B)          // Editorial Deep Espresso / Charcoal
val WarmUmber = Color(0xFF7D533E)             // Warm Roast Accent
val EmeraldLossless = Color(0xFF2D6A4F)       // Botanical Sage Green
val AmberMastering = Color(0xFFB56E22)        // Warm Ochre / Brass
val WeChatGreen = Color(0xFF2A7A53)           // Refined Deep Jade
val DangerRed = Color(0xFFB83A32)             // Editorial Terracotta Red

val TextPrimary = Color(0xFF141413)           // Deep Charcoal Ink
val TextSecondary = Color(0xFF5E5B52)         // Warm Graphite
val TextMuted = Color(0xFF8C877C)             // Muted Sandstone
val OnAccentLight = Color(0xFFFAF8F3)         // Warm Ivory on Terracotta / Charcoal buttons

private val StudioColorScheme = lightColorScheme(
    primary = CyanPrimary,
    onPrimary = OnAccentLight,
    primaryContainer = Color(0xFFF5E3DC),
    onPrimaryContainer = Color(0xFF5A2311),
    secondary = VioletAccent,
    onSecondary = OnAccentLight,
    secondaryContainer = StudioSurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = EmeraldLossless,
    onTertiary = OnAccentLight,
    background = StudioBg,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = StudioBorder,
    error = DangerRed,
    onError = OnAccentLight
)

private val StudioTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextPrimary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary
    )
)

@Composable
fun SoundTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content
    )
}
