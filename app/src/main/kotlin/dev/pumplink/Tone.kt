package dev.pumplink

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.pumplink.domain.LinkStatus

/**
 * Certainty tone. Every use is paired with a text label — colour alone must
 * never be what tells the operator whether insulin was delivered.
 */
enum class Tone {
    Ok,
    Ambiguous,
    Blocked,
    Committed,
    Neutral,
}

@Composable
fun Tone.color(): Color {
    val palette = LocalSafetyPalette.current
    return when (this) {
        Tone.Ok -> palette.ok
        Tone.Ambiguous -> palette.ambiguous
        Tone.Blocked -> palette.blocked
        Tone.Committed -> palette.committed
        Tone.Neutral -> palette.neutral
    }
}

fun linkTone(link: LinkStatus): Tone = when (link) {
    LinkStatus.Ready -> Tone.Ok
    LinkStatus.Linking, LinkStatus.Recovering -> Tone.Ambiguous
    LinkStatus.Suspended, LinkStatus.Failed, LinkStatus.Unpaired -> Tone.Blocked
    LinkStatus.Idle -> Tone.Neutral
}
