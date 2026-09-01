package dev.pumplink

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ClinicalScheme = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF04212A),
    primaryContainer = Color(0xFF0E3A45),
    onPrimaryContainer = Color(0xFFB6EEF6),
    secondary = Color(0xFF7FDBA4),
    onSecondary = Color(0xFF062514),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFFA8B6C8),
    outline = Color(0xFF33404F),
    error = Color(0xFFFF5C5C),
    onError = Color(0xFF2B0708),
)

private val ClinicalTypography = Typography().let { base ->
    base.copy(labelSmall = base.labelSmall.copy(letterSpacing = 1.sp))
}

/**
 * Dose numerals are monospaced so a value does not reflow while it is being
 * stepped: the decimal point stays put between 0.05 U and 25.00 U.
 */
val DoseNumeralStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Light,
    fontSize = 64.sp,
    lineHeight = 68.sp,
)

val MonoLabelStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    letterSpacing = 0.5.sp,
)

/**
 * Fixed dark scheme rather than following the system. A controller for a
 * delivery device should look the same in every recording and every room; a
 * surprise light theme mid-demo is a reviewability problem, not a preference.
 */
@Composable
fun PumpLinkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSafetyPalette provides ClinicalSafety) {
        MaterialTheme(
            colorScheme = ClinicalScheme,
            typography = ClinicalTypography,
            content = content,
        )
    }
}
