package com.skorsnap.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green = Color(0xFF2BE08A)
val Sky = Color(0xFF35D6FF)
val Amber = Color(0xFFFFC53D)
val Rose = Color(0xFFFF5D73)

/** The second accent, for the neon edge and the scoreboard gradient. */
val Violet = Color(0xFF9D6BFF)

private val Dark = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF04121F),
    // Deeper and cooler than before: the accents only read as neon against a
    // ground this dark, and a betting app is looked at in the evening anyway.
    background = Color(0xFF05070F),
    onBackground = Color(0xFFEAF1FF),
    surface = Color(0xFF0D1322),
    onSurface = Color(0xFFEAF1FF),
    surfaceVariant = Color(0xFF161E33),
    onSurfaceVariant = Color(0xFF8FA2C4),
    outline = Color(0xFF25304A),
    error = Rose,
)

private val Light = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF0B1220),
    surface = Color.White,
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFE6ECF4),
    onSurfaceVariant = Color(0xFF52657E),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
)

private val AppTypography = Typography(
    // Wide tracking on the headings and tight on the numbers: the scoreboard look
    // comes from the spacing far more than from the colours.
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp),
)

@Composable
fun SkorsnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}

/** Colour for a probability, so a strong number reads as strong. */
fun probColor(p: Double): Color = when {
    p >= 0.75 -> Green
    p >= 0.60 -> Color(0xFF7BEFB0)
    p >= 0.45 -> Amber
    else -> Color(0xFF8FA2C4)
}

/**
 * The glow behind a card edge.
 *
 * Kept to the border rather than the fill: a tinted panel behind body text costs
 * contrast, and this app is mostly numbers people have to read exactly. The neon
 * lives on the outline, where it is decoration and nothing else.
 */
@Composable
fun neonEdge(tint: Color, strong: Boolean = false): Brush = Brush.linearGradient(
    listOf(
        tint.copy(alpha = if (strong) 0.85f else 0.45f),
        Violet.copy(alpha = if (strong) 0.55f else 0.22f),
        tint.copy(alpha = if (strong) 0.30f else 0.12f),
    )
)
