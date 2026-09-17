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
 * "ClashFest" festive palettes — warm cream surfaces with a coral/rose
 * accent pair and a violet highlight. Every neutral is warm-tinted (no cold
 * blue-greys): light mode is cream paper with peach cards, dark mode is
 * espresso-warm charcoal so the coral stays the loudest thing on screen.
 */
private val SeedLight = lightColorScheme(
    primary = Color(0xFFFF6A3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF330F00),
    inversePrimary = Color(0xFFFFB599),
    secondary = Color(0xFFE34D7F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF3D0720),
    tertiary = Color(0xFF8A5CF6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF22005C),
    background = Color(0xFFFFF7F0),
    onBackground = Color(0xFF231913),
    surface = Color(0xFFFFF7F0),
    onSurface = Color(0xFF231913),
    surfaceVariant = Color(0xFFF4DED1),
    onSurfaceVariant = Color(0xFF57433A),
    surfaceTint = Color(0xFFFF6A3D),
    inverseSurface = Color(0xFF3A2D25),
    inverseOnSurface = Color(0xFFFFEDE2),
    outline = Color(0xFF8A7468),
    outlineVariant = Color(0xFFDCC3B4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFDFB),
    surfaceContainer = Color(0xFFFFF4EB),
    surfaceContainerHigh = Color(0xFFFCEADB),
    surfaceContainerHighest = Color(0xFFF7E0CE),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFFFF9E7D),
    onPrimary = Color(0xFF4A1800),
    primaryContainer = Color(0xFF713711),
    onPrimaryContainer = Color(0xFFFFDBCA),
    inversePrimary = Color(0xFFFF6A3D),
    secondary = Color(0xFFFFADC9),
    onSecondary = Color(0xFF5E0D30),
    secondaryContainer = Color(0xFF852A53),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFFCFBEFF),
    onTertiary = Color(0xFF330A72),
    tertiaryContainer = Color(0xFF4B308B),
    onTertiaryContainer = Color(0xFFE9DDFF),
    // Espresso-warm charcoal: neutral brown ramp, never cold blue-black.
    background = Color(0xFF17120F),
    onBackground = Color(0xFFF4E5DA),
    surface = Color(0xFF17120F),
    onSurface = Color(0xFFF4E5DA),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD8C2B5),
    surfaceTint = Color(0xFFFF9E7D),
    inverseSurface = Color(0xFFF4E5DA),
    inverseOnSurface = Color(0xFF3A2D25),
    outline = Color(0xFFA28E80),
    outlineVariant = Color(0xFF52443B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF110D0B),
    surfaceContainerLow = Color(0xFF1F1915),
    surfaceContainer = Color(0xFF231D19),
    surfaceContainerHigh = Color(0xFF2E2621),
    surfaceContainerHighest = Color(0xFF39302A),
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
