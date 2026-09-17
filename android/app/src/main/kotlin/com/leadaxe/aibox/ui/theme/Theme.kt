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
 * "Blue sky / white cloud" palettes (user request): a sky-blue gradient
 * canvas with frosted-glass surfaces — light mode is open daylight blue,
 * dark mode is deep twilight blue (never black, never amber).
 */
private val SeedLight = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E5FF),
    onPrimaryContainer = Color(0xFF002B5E),
    inversePrimary = Color(0xFF9DC3FF),
    secondary = Color(0xFF0277BD),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE6FF),
    onSecondaryContainer = Color(0xFF00344F),
    tertiary = Color(0xFF4FA3E3),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7EBFF),
    onTertiaryContainer = Color(0xFF003049),
    background = Color(0xFFEAF4FF),
    onBackground = Color(0xFF0F2436),
    surface = Color(0xFFF3F9FF),
    onSurface = Color(0xFF0F2436),
    surfaceVariant = Color(0xFFDCE9F7),
    onSurfaceVariant = Color(0xFF3E5871),
    surfaceTint = Color(0xFF1565C0),
    inverseSurface = Color(0xFF24384D),
    inverseOnSurface = Color(0xFFE8F2FF),
    outline = Color(0xFF6B85A1),
    outlineVariant = Color(0xFFB9CEE4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6FAFF),
    surfaceContainer = Color(0xFFEFF6FF),
    surfaceContainerHigh = Color(0xFFE7F1FF),
    surfaceContainerHighest = Color(0xFFE0EDFF),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF9DC3FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1B4978),
    onPrimaryContainer = Color(0xFFD3E5FF),
    inversePrimary = Color(0xFF1565C0),
    secondary = Color(0xFF9FD2FF),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF1D4C6B),
    onSecondaryContainer = Color(0xFFCDE6FF),
    tertiary = Color(0xFFA6D4FF),
    onTertiary = Color(0xFF003049),
    tertiaryContainer = Color(0xFF21486A),
    onTertiaryContainer = Color(0xFFD7EBFF),
    // Twilight blue, not black.
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFDFEBFA),
    surface = Color(0xFF132437),
    onSurface = Color(0xFFDFEBFA),
    surfaceVariant = Color(0xFF33475C),
    onSurfaceVariant = Color(0xFFB9CEE4),
    surfaceTint = Color(0xFF9DC3FF),
    inverseSurface = Color(0xFFDFEBFA),
    inverseOnSurface = Color(0xFF182C40),
    outline = Color(0xFF7E97B0),
    outlineVariant = Color(0xFF33475C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0A1626),
    surfaceContainerLow = Color(0xFF111F31),
    surfaceContainer = Color(0xFF16283B),
    surfaceContainerHigh = Color(0xFF1B3145),
    surfaceContainerHighest = Color(0xFF223C53),
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
