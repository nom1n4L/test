package com.skorsnap.app.ui

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
val Sky = Color(0xFF38BDF8)
val Amber = Color(0xFFFBBF24)
val Rose = Color(0xFFF87171)

private val Dark = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF04121F),
    background = Color(0xFF0A0F1A),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF121A28),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1B2536),
    onSurfaceVariant = Color(0xFF9AAAC2),
    outline = Color(0xFF2A3548),
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
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
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
    p >= 0.60 -> Color(0xFF4ADE80)
    p >= 0.45 -> Amber
    else -> Color(0xFF8FA3BF)
}
