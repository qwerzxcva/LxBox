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
 * Clean blue-violet palettes inspired by ClashFest and bilipai.
 *
 * Light mode is paper-white with a cobalt primary, so the app reads neutral
 * and clean — nothing warm or beige fighting the status text. Dark mode uses
 * Material-You deep neutral grey tinted with the primary blue (#1A1D2E —
 * notice the "2E" in the blue channel instead of flat 1F), which keeps it
 * readable without looking like a pure-black OLED burn-in special (Android
 * 14+ Material-You guidance). Every surface stays separated by subtle tone
 * steps so collapsed sections and cards read as distinct even on flat screens.
 */
private val BiliLight = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF001A56),
    inversePrimary = Color(0xFFB4C6FF),

    secondary = Color(0xFF7B5CFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E0FF),
    onSecondaryContainer = Color(0xFF1A0F4D),

    tertiary = Color(0xFF00A896),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF7EF5DE),
    onTertiaryContainer = Color(0xFF00201C),

    background = Color(0xFFFAFBFF),
    onBackground = Color(0xFF141B2C),
    surface = Color(0xFFFAFBFF),
    onSurface = Color(0xFF141B2C),
    surfaceVariant = Color(0xFFE0E4EF),
    onSurfaceVariant = Color(0xFF434A5E),
    surfaceTint = Color(0xFF2F6BFF),
    inverseSurface = Color(0xFF283044),
    inverseOnSurface = Color(0xFFF1F4FF),
    outline = Color(0xFF737A8E),
    outlineVariant = Color(0xFFC3C8D9),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6FC),
    surfaceContainer = Color(0xFFEEF1F8),
    surfaceContainerHigh = Color(0xFFE7EBF4),
    surfaceContainerHighest = Color(0xFFDEE2EE),
)

private val BiliDark = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF00297A),
    primaryContainer = Color(0xFF103FA8),
    onPrimaryContainer = Color(0xFFDBE4FF),
    inversePrimary = Color(0xFF2F6BFF),

    secondary = Color(0xFFCCBDFF),
    onSecondary = Color(0xFF2F1F7C),
    secondaryContainer = Color(0xFF463794),
    onSecondaryContainer = Color(0xFFE6E0FF),

    tertiary = Color(0xFF6ED9C2),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005144),
    onTertiaryContainer = Color(0xFF7EF5DE),

    // Blue-tinted deep neutral — not pure black, not flat grey. The 0x1A1D2E
    // base carries just enough of the primary family to read as "AIBox dark"
    // rather than the generic system dark theme.
    background = Color(0xFF1A1D2E),
    onBackground = Color(0xFFE2E5F3),
    surface = Color(0xFF1A1D2E),
    onSurface = Color(0xFFE2E5F3),
    surfaceVariant = Color(0xFF424758),
    onSurfaceVariant = Color(0xFFC3C7D8),
    surfaceTint = Color(0xFFAEC6FF),
    inverseSurface = Color(0xFFE2E5F3),
    inverseOnSurface = Color(0xFF2A2D3E),
    outline = Color(0xFF8D92A5),
    outlineVariant = Color(0xFF424758),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Toned surfaces with increasing elevation — the key to making dark
    // mode feel "designed" instead of flat. Each step is a 2-3% alpha blend
    // of primary over the base #1A1D2E.
    surfaceContainerLowest = Color(0xFF121524),
    surfaceContainerLow = Color(0xFF222638),
    surfaceContainer = Color(0xFF282C40),
    surfaceContainerHigh = Color(0xFF33374C),
    surfaceContainerHighest = Color(0xFF3E4358),
)

private val LxTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
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
        colorScheme = if (darkTheme) BiliDark else BiliLight,
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
