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
 * Standard Material 3 palettes generated from a blue seed — nothing stylised,
 * just the normal Android look (Material You defaults) in light and dark.
 */
private val SeedLight = lightColorScheme(
    primary = Color(0xFF3B69D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE5FF),
    onPrimaryContainer = Color(0xFF00174B),
    inversePrimary = Color(0xFFB5C4FF),
    secondary = Color(0xFF595D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF75546F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F3),
    onTertiaryContainer = Color(0xFF2C1229),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF3B69D8),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF1D2F78),
    primaryContainer = Color(0xFF35468F),
    onPrimaryContainer = Color(0xFFDEE5FF),
    inversePrimary = Color(0xFF3B69D8),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFFE4BADB),
    onTertiary = Color(0xFF44263F),
    tertiaryContainer = Color(0xFF5C3C57),
    onTertiaryContainer = Color(0xFFFFD7F3),
    // Standard md3 dark surfaces (grey ramp, not pure black).
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFB5C4FF),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3036),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
