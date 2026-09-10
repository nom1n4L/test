package com.skorlogi.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green = Color(0xFF22C55E)
val GreenSoft = Color(0xFF4ADE80)
val Sky = Color(0xFF38BDF8)
val Amber = Color(0xFFFBBF24)
val Rose = Color(0xFFF87171)

private val Dark = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF04240F),
    secondary = Sky,
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E9F0),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1B263C),
    onSurfaceVariant = Color(0xFF9FB0C7),
    outline = Color(0xFF2C3A54),
    error = Rose,
)

private val Light = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color.White,
    secondary = Color(0xFF0369A1),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF51637C),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun SkorlogiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}

/** Colour for a probability, so a strong number reads as strong at a glance. */
fun probColor(p: Double): Color = when {
    p >= 0.55 -> Green
    p >= 0.40 -> GreenSoft
    p >= 0.25 -> Amber
    else -> Color(0xFF8FA3BF)
}
