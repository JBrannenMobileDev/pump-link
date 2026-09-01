package dev.pumplink

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import dev.pumplink.domain.AbortReason
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.LinkFault
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.LinkStep

/** Milliunits rendered as units, always two decimals so the point never moves. */
fun formatUnits(milliunits: Int): String = "%.2f".format(milliunits / 1000.0)

fun commandLabel(commandId: DomainCommandId): String =
    "cmd 0x%04X".format(commandId.value.toInt())

fun storeLabel(id: DomainStoreId): String =
    "0x%016X".format(id.value.toLong())

fun formatAge(age: Duration): String {
    val seconds = age.inWholeSeconds
    return when {
        seconds < SECONDS_PER_MINUTE -> "just now"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE} min ago"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR} hr ago"
        else -> "${seconds / SECONDS_PER_DAY}d ago"
    }
}

fun linkLabel(link: LinkStatus): String = when (link) {
    LinkStatus.Idle -> "Not linked"
    LinkStatus.Linking -> "Linking"
    LinkStatus.Ready -> "Ready"
    LinkStatus.Suspended -> "Suspended"
    LinkStatus.Recovering -> "Recovering"
    LinkStatus.Failed -> "Link failed"
    LinkStatus.Unpaired -> "Not paired"
}

fun stepLabel(step: LinkStep): String = when (step) {
    LinkStep.Scanning -> "Scanning"
    LinkStep.Connecting -> "Connecting"
    LinkStep.Bonding -> "Bonding"
    LinkStep.Discovering -> "Discovering services"
    LinkStep.Configuring -> "Negotiating MTU"
    LinkStep.Subscribed -> "Confirming identity"
    LinkStep.Authenticating -> "Authenticating"
    LinkStep.Reconciling -> "Reconciling journal"
}

/** What the operator can actually do about each step, if anything. */
fun stepHint(step: LinkStep): String = when (step) {
    LinkStep.Scanning -> "Looking for the pump's service UUID."
    LinkStep.Connecting -> "Opening a GATT connection."
    LinkStep.Bonding -> "Accept the pairing request on this phone."
    LinkStep.Discovering -> "Reading the pump's service and characteristics."
    LinkStep.Configuring -> "Agreeing a payload size and enabling notifications."
    LinkStep.Subscribed -> "Reading STATUS to confirm this is the paired pump."
    LinkStep.Authenticating -> "Challenge and response over the pairing key."
    LinkStep.Reconciling -> "Asking about every command left unresolved."
}

fun faultLabel(fault: LinkFault): String = when (fault) {
    LinkFault.TransientLink -> "Link dropped; retrying"
    LinkFault.PeerInitiated -> "Pump closed the connection"
    LinkFault.StackFault -> "Bluetooth stack fault"
    LinkFault.CacheStale -> "Cached services were stale"
    LinkFault.AuthFailure -> "Pairing rejected; re-pair required"
    LinkFault.ProtocolFault -> "Protocol fault; session reset"
    LinkFault.Unrecoverable -> "Retries exhausted"
}

fun abortLabel(reason: AbortReason): String = when (reason) {
    AbortReason.UserCancelled -> "cancelled"
    AbortReason.PumpRejected -> "rejected by pump"
    AbortReason.Reservoir -> "reservoir empty"
    AbortReason.Occlusion -> "occlusion detected"
    AbortReason.Other -> "stopped at the pump"
}

@Composable
fun StatusDot(tone: Tone, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.color()),
    )
}

/**
 * A labelled bar. Used for reservoir and battery, where the number matters more
 * than the bar, so the number is always spelled out beside it.
 */
@Composable
fun Meter(
    label: String,
    value: String,
    fraction: Float,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        label = "meter",
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(label)
            Text(value, style = MonoLabelStyle)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tone.color()),
            )
        }
    }
}

/**
 * Card with a tone stripe down the leading edge. The stripe is redundant with
 * the title by design; see [Tone].
 */
@Composable
fun ToneCard(
    tone: Tone,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(tone.color()),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
fun CardTitle(text: String, tone: Tone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = tone.color(),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun CardBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun MonoDetail(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MonoLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Large dose readout. [suffix] is separated so it does not scale with the digits.
 * Numerals are not animated: a digit caught mid-transition is a misread risk.
 */
@Composable
fun DoseReadout(
    milliunits: Int,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            formatUnits(milliunits),
            style = DoseNumeralStyle,
            color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else tone.color(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "U",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp),
        )
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_DAY = 86_400
