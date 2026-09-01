package dev.pumplink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.LinkStep
import dev.pumplink.domain.PumpSummary
import dev.pumplink.presentation.HistoryRow

private enum class StepState {
    Done,
    Current,
    Pending,
}

/**
 * The linking substates as an ordered checklist.
 *
 * docs/03 collapses these into one value for the purpose of deciding whether
 * dosing is allowed, and that stays true — this panel cannot enable a dose. It
 * exists because "Linking" with no further detail is undiagnosable: a bond
 * waiting on a system dialog and a stalled MTU negotiation look identical.
 */
@Composable
fun LinkSheet(
    link: LinkStatus,
    progress: LinkProgress,
    identity: String,
    pump: PumpSummary?,
    latestCommand: HistoryRow?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(linkTone(link), size = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Pump link", style = MaterialTheme.typography.titleMedium)
                MonoDetail(linkLabel(link))
            }
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }

        RadioBanner(progress)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                val states = stepStates(link, progress)
                LinkStep.entries.forEach { step ->
                    StepRow(step, states.getValue(step), progress)
                }
            }
        }

        ProtocolDetail(link, progress, identity, pump, latestCommand)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                Text(if (link == LinkStatus.Failed) "Retry" else "Link")
            }
        }

        CardBody(
            "Dosing is reachable only from Ready, and Ready is reachable only " +
                "after every journaled command has been reconciled.",
        )
    }
}

@Composable
private fun StepRow(step: LinkStep, state: StepState, progress: LinkProgress) {
    val tone = when (state) {
        StepState.Done -> Tone.Ok
        StepState.Current -> Tone.Committed
        StepState.Pending -> Tone.Neutral
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StepMarker(state, tone.color())
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stepLabel(step),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == StepState.Pending) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (state == StepState.Current) {
                MonoDetail(stepHint(step))
                if (progress.timeoutMillis > 0) {
                    MonoDetail("waits up to ${progress.timeoutMillis / MILLIS_PER_SECOND}s")
                }
            }
        }
    }
}

@Composable
private fun StepMarker(state: StepState, color: Color) {
    Canvas(modifier = Modifier.size(MARKER_SIZE_DP.dp)) {
        val radius = size.minDimension / 2f
        val center = androidx.compose.ui.geometry.Offset(radius, radius)
        when (state) {
            StepState.Done -> {
                drawCircle(color = color, radius = radius, center = center)
                val stroke = Stroke(width = MARKER_STROKE_DP.dp.toPx(), cap = StrokeCap.Round)
                drawLine(
                    color = Color.Black.copy(alpha = CHECK_ALPHA),
                    start = androidx.compose.ui.geometry.Offset(radius * CHECK_X1, radius * CHECK_Y1),
                    end = androidx.compose.ui.geometry.Offset(radius * CHECK_X2, radius * CHECK_Y2),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.Black.copy(alpha = CHECK_ALPHA),
                    start = androidx.compose.ui.geometry.Offset(radius * CHECK_X2, radius * CHECK_Y2),
                    end = androidx.compose.ui.geometry.Offset(radius * CHECK_X3, radius * CHECK_Y3),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            StepState.Current -> drawCircle(
                color = color,
                radius = radius - MARKER_STROKE_DP.dp.toPx() / 2f,
                center = center,
                style = Stroke(width = MARKER_STROKE_DP.dp.toPx()),
            )
            StepState.Pending -> drawCircle(
                color = color.copy(alpha = PENDING_ALPHA),
                radius = radius / 2.5f,
                center = center,
            )
        }
    }
}

@Composable
private fun RadioBanner(progress: LinkProgress) {
    if (!progress.radioEnabled) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Bluetooth is off on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.Blocked.color(),
                )
                MonoDetail("Dosing stays refused until the radio is back.")
            }
        }
        return
    }
    val fault = progress.fault ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                faultLabel(fault),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Blocked.color(),
            )
            if (progress.attempts > 0) {
                MonoDetail("attempt ${progress.attempts} of $MAX_ATTEMPTS")
            }
        }
    }
}

@Composable
private fun ProtocolDetail(
    link: LinkStatus,
    progress: LinkProgress,
    identity: String,
    pump: PumpSummary?,
    latestCommand: HistoryRow?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailLine("Paired identity", identity.take(IDENTITY_CHARS))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            DetailLine("Negotiated MTU", if (progress.mtu > 0) "${progress.mtu} bytes" else "\u2014")
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            DetailLine("Dosing permitted", if (link == LinkStatus.Ready) "yes" else "no")
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            DetailLine(
                "Store instance",
                pump?.let { storeLabel(it.storeInstanceId) } ?: "\u2014",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            DetailLine(
                "Latest command",
                latestCommand?.let { commandLabel(it.commandId) } ?: "\u2014",
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MonoLabelStyle)
    }
}

/**
 * Steps before the current one are done, the rest are pending. When the link is
 * Recovering or Failed the transport does not record which step failed, so no
 * step is marked current and the fault banner carries the explanation.
 */
private fun stepStates(link: LinkStatus, progress: LinkProgress): Map<LinkStep, StepState> {
    val current = progress.step
    return LinkStep.entries.associateWith { step ->
        when {
            link == LinkStatus.Ready || link == LinkStatus.Suspended -> StepState.Done
            current == null -> StepState.Pending
            step.ordinal < current.ordinal -> StepState.Done
            step == current -> StepState.Current
            else -> StepState.Pending
        }
    }
}

/** Bottom sheet without the experimental Material3 surface area. */
@Composable
fun LinkSheetOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .clickable(onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .align(Alignment.CenterHorizontally)
                            .width(GRABBER_WIDTH_DP.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    content()
                }
            }
        }
    }
}

private const val MAX_ATTEMPTS = 8
private const val IDENTITY_CHARS = 16
private const val MILLIS_PER_SECOND = 1_000
private const val MARKER_SIZE_DP = 18
private const val MARKER_STROKE_DP = 2
private const val GRABBER_WIDTH_DP = 40
private const val SCRIM_ALPHA = 0.6f
private const val PENDING_ALPHA = 0.5f
private const val CHECK_ALPHA = 0.75f
private const val CHECK_X1 = 0.55f
private const val CHECK_Y1 = 1.0f
private const val CHECK_X2 = 0.85f
private const val CHECK_Y2 = 1.35f
private const val CHECK_X3 = 1.45f
private const val CHECK_Y3 = 0.65f
