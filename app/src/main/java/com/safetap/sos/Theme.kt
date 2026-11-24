package com.safetap.sos

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

// Contrast-checked pairs (all >= 7:1 for body text). Sizes use sp so system font scaling works.
private val Light = lightColorScheme(
    primary = Color(0xFF0B3D91), onPrimary = Color.White,
    secondary = Color(0xFF1B5E20), onSecondary = Color.White,
    error = Color(0xFF8B0000), onError = Color.White,
    background = Color.White, onBackground = Color(0xFF101010),
    surface = Color.White, onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFE9ECF2), onSurfaceVariant = Color(0xFF1A1A1A),
    outline = Color(0xFF3A3A3A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9C8FF), onPrimary = Color(0xFF001A40),
    secondary = Color(0xFF9FE5A5), onSecondary = Color(0xFF00210A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF410002),
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF0A0A0A), onSurface = Color.White,
    surfaceVariant = Color(0xFF22262E), onSurfaceVariant = Color(0xFFF2F2F2),
    outline = Color(0xFFD0D0D0),
)

private val BigType = Typography(
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SafeTapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = BigType, content = content)
}
