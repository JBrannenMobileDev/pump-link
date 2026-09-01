package dev.pumplink

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for delivery certainty. These are the app-side twins of the
 * diagram node classes in diagrams/style.puml: a state that reads <<ok>> on a
 * diagram reads [ok] here, and so on. Certainty is never signalled by colour
 * alone — every surface that uses these also carries a label.
 */
@Immutable
data class SafetyPalette(
    val ok: Color,
    val ambiguous: Color,
    val blocked: Color,
    val committed: Color,
    val neutral: Color,
)

internal val ClinicalSafety = SafetyPalette(
    ok = Color(0xFF3DDC97),
    ambiguous = Color(0xFFFFB020),
    blocked = Color(0xFFFF5C5C),
    committed = Color(0xFF4DA3FF),
    neutral = Color(0xFF7A8899),
)

val LocalSafetyPalette: ProvidableCompositionLocal<SafetyPalette> = staticCompositionLocalOf {
    ClinicalSafety
}
