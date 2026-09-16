package com.leadaxe.aibox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leadaxe.aibox.app.ColorModeDark
import com.leadaxe.aibox.app.ColorModeLight
import com.leadaxe.aibox.app.ColorModeSystem

/**
 * Palette follows the "single accent on quiet surfaces" idea: neutral
 * obsidian steps in dark mode (true-black canvas, cards one step up), soft
 * cool greys in light mode, one saturated accent that reads as "tunnel on".
 */
private val SeedLight = lightColorScheme(
    primary = Color(0xFF2E6FDE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF5D6B7A),
    onSecondary = Color.White,
    tertiary = Color(0xFF8B5CF6),
    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF171B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B21),
    surfaceVariant = Color(0xFFE7EBF1),
    onSurfaceVariant = Color(0xFF566070),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFF1F4F9),
    outlineVariant = Color(0xFFDCE2EA),
    error = Color(0xFFC4384A),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF7FB3FF),
    onPrimary = Color(0xFF08203E),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFCFE3FF),
    secondary = Color(0xFF98A6B8),
    onSecondary = Color(0xFF0B0F14),
    tertiary = Color(0xFFC4A9FF),
    // Obsidian ramp: canvas is pure black (OLED-friendly), surfaces step up
    // from it so cards read as elevation instead of borders.
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8EDF4),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFE8EDF4),
    surfaceVariant = Color(0xFF1C1E23),
    onSurfaceVariant = Color(0xFFA7B0BD),
    surfaceContainer = Color(0xFF15171B),
    surfaceContainerHigh = Color(0xFF1B1E23),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFFF6B7A),
)

private val LxTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LxTheme(
    colorMode: Int = ColorModeSystem,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (colorMode) {
        ColorModeLight -> false
        ColorModeDark -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) SeedDark else SeedLight,
        typography = LxTypography,
        // Consistently rounder surfaces across every Card/Sheet/Menu that
        // reads the theme defaults — the "softer" look the user asked for.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
