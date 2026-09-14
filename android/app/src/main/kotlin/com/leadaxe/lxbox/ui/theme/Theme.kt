package com.leadaxe.lxbox.ui.theme

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
import com.leadaxe.lxbox.app.ColorModeDark
import com.leadaxe.lxbox.app.ColorModeLight
import com.leadaxe.lxbox.app.ColorModeSystem

/** Brand-tinted neutrals + an accent that reads as "tunnel on" in both light and dark. */
private val SeedLight = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    secondary = Color(0xFF6E7F8F),
    onSecondary = Color.White,
    tertiary = Color(0xFF9D5CFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF0D1117),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1117),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF4C5663),
    error = Color(0xFFCC3344),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF0B1F3A),
    secondary = Color(0xFFA0AEC0),
    onSecondary = Color(0xFF0B0F14),
    tertiary = Color(0xFFC1A7FF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFFB0B8C1),
    error = Color(0xFFF85149),
)

private val LxTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
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
        content = content,
    )
}