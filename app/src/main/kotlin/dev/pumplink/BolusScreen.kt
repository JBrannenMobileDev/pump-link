package dev.pumplink

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.SafetyLimits
import dev.pumplink.presentation.BolusAction
import dev.pumplink.presentation.BolusIntent
import dev.pumplink.presentation.BolusScreenState
import dev.pumplink.presentation.BolusUiState
import dev.pumplink.presentation.HistoryOutcome
import dev.pumplink.presentation.HistoryRow
import dev.pumplink.presentation.actions

@Composable
fun BolusScreen(
    state: BolusScreenState,
    onIntent: (BolusIntent) -> Unit,
    onOpenLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ActionBar(state.bolus, onIntent, onOpenLink) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "pump-link",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LinkBar(state.link, state.progress, onOpenLink)
            BolusCard(state.bolus, onIntent)
            state.pump?.let { Vitals(it, state.vitalsStale) }
            if (state.history.isNotEmpty()) {
                HistorySection(state.history)
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: BolusUiState,
    onIntent: (BolusIntent) -> Unit,
    onOpenLink: () -> Unit,
) {
    val items = actions(state)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (items.isEmpty()) {
            Text(
                "Waiting for the pump\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.forEachIndexed { index, action ->
                    val filled = isFilledAction(action, items.size, index)
                    val enabled = actionEnabled(action, state)
                    ActionButton(
                        label = actionLabel(action),
                        filled = filled,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { dispatch(action, onIntent, onOpenLink) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(label)
        }
    }
}

private fun isFilledAction(action: BolusAction, count: Int, index: Int): Boolean {
    if (count > 1) return index == count - 1
    return when (action) {
        BolusAction.Recheck,
        BolusAction.ConfirmCheckedAtPump,
        -> false
        BolusAction.ReviewDose,
        BolusAction.Cancel,
        BolusAction.Deliver,
        is BolusAction.Done,
        BolusAction.Reissue,
        BolusAction.DeclineReissue,
        BolusAction.OpenLinkPanel,
        -> true
    }
}

private fun actionEnabled(action: BolusAction, state: BolusUiState): Boolean = when (action) {
    BolusAction.ReviewDose -> {
        val milliunits = (state as? BolusUiState.Entering)?.draft?.milliunits?.value ?: 0
        milliunits >= SafetyLimits.INCREMENT_MILLIUNITS
    }
    BolusAction.Cancel,
    BolusAction.Deliver,
    is BolusAction.Done,
    BolusAction.Reissue,
    BolusAction.DeclineReissue,
    BolusAction.Recheck,
    BolusAction.ConfirmCheckedAtPump,
    BolusAction.OpenLinkPanel,
    -> true
}

private fun actionLabel(action: BolusAction): String = when (action) {
    BolusAction.ReviewDose -> "Review dose"
    BolusAction.Cancel -> "Cancel"
    BolusAction.Deliver -> "Deliver"
    is BolusAction.Done -> "Done"
    BolusAction.Reissue -> "Reissue"
    BolusAction.DeclineReissue -> "Do not deliver"
    BolusAction.Recheck -> "Check the pump again"
    BolusAction.ConfirmCheckedAtPump -> "I checked the pump"
    BolusAction.OpenLinkPanel -> "Open link panel"
}

private fun dispatch(
    action: BolusAction,
    onIntent: (BolusIntent) -> Unit,
    onOpenLink: () -> Unit,
) = when (action) {
    BolusAction.ReviewDose,
    BolusAction.Deliver,
    -> onIntent(BolusIntent.Confirmed)
    BolusAction.Cancel -> onIntent(BolusIntent.Cancelled)
    is BolusAction.Done -> onIntent(BolusIntent.Acknowledged(action.commandId))
    BolusAction.Reissue -> onIntent(BolusIntent.ReissueConfirmed)
    BolusAction.DeclineReissue -> onIntent(BolusIntent.ReissueDeclined)
    BolusAction.Recheck -> onIntent(BolusIntent.RecheckRequested)
    BolusAction.ConfirmCheckedAtPump -> onIntent(BolusIntent.PumpVerifiedByUser)
    BolusAction.OpenLinkPanel -> onOpenLink()
}

@Composable
private fun LinkBar(link: LinkStatus, progress: LinkProgress, onClick: () -> Unit) {
    val tone = linkTone(link)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tone)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(linkLabel(link), style = MaterialTheme.typography.bodyLarge)
                val step = progress.step
                val fault = progress.fault
                val detail = when {
                    !progress.radioEnabled -> "Bluetooth is off"
                    step != null -> stepLabel(step)
                    fault != null -> faultLabel(fault)
                    else -> "Tap for details"
                }
                MonoDetail(detail)
            }
            Text(
                "Details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Vitals(pump: PumpSummary, stale: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Meter(
                    label = "Reservoir",
                    value = "${formatUnits(pump.reservoirMilliunits)} U",
                    fraction = pump.reservoirMilliunits / RESERVOIR_FULL_MILLIUNITS,
                    tone = if (stale || pump.reservoirMilliunits <= RESERVOIR_LOW_MILLIUNITS) {
                        Tone.Ambiguous
                    } else {
                        Tone.Ok
                    },
                    modifier = Modifier.weight(1f),
                )
                Meter(
                    label = "Battery",
                    value = "${pump.batteryPercent}%",
                    fraction = pump.batteryPercent / 100f,
                    tone = if (stale || pump.batteryPercent <= BATTERY_LOW_PERCENT) {
                        Tone.Ambiguous
                    } else {
                        Tone.Ok
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (stale) {
                Text(
                    "Vitals are stale",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.Ambiguous.color(),
                )
                MonoDetail("Last confirmed reading. Dosing is still allowed.")
            }
        }
    }
}

@Composable
private fun BolusCard(state: BolusUiState, onIntent: (BolusIntent) -> Unit) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it::class },
        label = "bolus-card",
    ) { current ->
        when (current) {
            is BolusUiState.Entering -> EnteringCard(current, onIntent)
            is BolusUiState.Confirming -> ConfirmingCard(current)
            is BolusUiState.Delivering -> DeliveringCard(current)
            is BolusUiState.Delivered -> DeliveredCard(current)
            is BolusUiState.PartiallyDelivered -> PartialCard(current)
            is BolusUiState.AwaitingReissue -> ReissueCard(current)
            is BolusUiState.Resolving -> ResolvingCard(current)
            is BolusUiState.Blocked -> BlockedCard()
            is BolusUiState.Indeterminate -> IndeterminateCard(current)
            is BolusUiState.DosingDisabled -> DisabledCard(current)
        }
    }
}

@Composable
private fun EnteringCard(state: BolusUiState.Entering, onIntent: (BolusIntent) -> Unit) {
    val milliunits = state.draft.milliunits.value
    ToneCard(Tone.Ok) {
        CardTitle("Ready to dose", Tone.Ok)
        DoseStepper(milliunits, onIntent)
        PresetRow(onIntent)
        CardBody("Choose a dose, then review it.")
    }
}

@Composable
private fun DoseStepper(milliunits: Int, onIntent: (BolusIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("\u2212") {
            val next = (milliunits - SafetyLimits.INCREMENT_MILLIUNITS)
                .coerceAtLeast(SafetyLimits.INCREMENT_MILLIUNITS)
            onIntent(BolusIntent.DoseEntered(next))
        }
        DoseReadout(milliunits, modifier = Modifier.weight(1f))
        StepButton("+") {
            val next = (milliunits + SafetyLimits.INCREMENT_MILLIUNITS)
                .coerceAtMost(SafetyLimits.MAX_BOLUS_MILLIUNITS)
            onIntent(BolusIntent.DoseEntered(next))
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PresetRow(onIntent: (BolusIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRESETS_MILLIUNITS.forEach { preset ->
            OutlinedButton(
                onClick = { onIntent(BolusIntent.DoseEntered(preset)) },
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(formatUnits(preset), style = MonoLabelStyle)
            }
        }
    }
}

@Composable
private fun ConfirmingCard(state: BolusUiState.Confirming) {
    ToneCard(Tone.Committed) {
        CardTitle("Confirm delivery", Tone.Committed)
        DoseReadout(state.dose.milliunits.value, tone = Tone.Committed)
        CardBody("The pump confirms every dose before it delivers.")
    }
}

@Composable
private fun DeliveringCard(state: BolusUiState.Delivering) {
    ToneCard(Tone.Committed) {
        CardTitle("Delivering", Tone.Committed)
        DeliveryArc(state.delivered.milliunits.value, state.requested.milliunits.value)
        CardBody("Waiting for the pump to confirm.")
    }
}

@Composable
private fun DeliveryArc(delivered: Int, requested: Int) {
    val fraction = if (requested <= 0) 0f else (delivered.toFloat() / requested).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "delivery-arc")
    val waiting = delivered == 0
    val infinite = rememberInfiniteTransition(label = "delivery-wait")
    val sweepOffset by infinite.animateFloat(
        initialValue = 0f,
        targetValue = FULL_CIRCLE_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(INDETERMINATE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "delivery-sweep",
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = Tone.Committed.color()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ARC_BOX_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(ARC_SIZE_DP.dp)) {
            val stroke = Stroke(width = ARC_STROKE_DP.dp.toPx())
            drawArc(
                color = track,
                startAngle = ARC_START_ANGLE,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                style = stroke,
            )
            if (waiting) {
                drawArc(
                    color = accent,
                    startAngle = ARC_START_ANGLE + sweepOffset,
                    sweepAngle = INDETERMINATE_SWEEP,
                    useCenter = false,
                    style = stroke,
                )
            } else {
                drawArc(
                    color = accent,
                    startAngle = ARC_START_ANGLE,
                    sweepAngle = ARC_SWEEP * animatedFraction,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatUnits(delivered), style = MaterialTheme.typography.headlineLarge)
            Text(
                "of ${formatUnits(requested)} U",
                style = MonoLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeliveredCard(state: BolusUiState.Delivered) {
    ToneCard(Tone.Ok) {
        CardTitle("Delivered", Tone.Ok)
        DoseReadout(state.delivered.milliunits.value, tone = Tone.Ok)
        CardBody(
            if (state.recovered) {
                "Confirmed by asking the pump after the first reply was lost."
            } else {
                "Confirmed by the pump."
            },
        )
    }
}

@Composable
private fun PartialCard(state: BolusUiState.PartiallyDelivered) {
    ToneCard(Tone.Ambiguous) {
        CardTitle("Partially delivered", Tone.Ambiguous)
        DoseReadout(state.delivered.milliunits.value, tone = Tone.Ambiguous)
        CardBody(
            if (state.recovered) {
                "Confirmed by asking the pump after the first reply was lost. " +
                    "The pump stopped early: ${abortLabel(state.reason)}."
            } else {
                "The pump stopped early: ${abortLabel(state.reason)}."
            },
        )
    }
}

@Composable
private fun ReissueCard(state: BolusUiState.AwaitingReissue) {
    ToneCard(Tone.Ambiguous) {
        CardTitle("Pump never saw this command", Tone.Ambiguous)
        DoseReadout(state.dose.milliunits.value, tone = Tone.Ambiguous)
        CardBody(
            "The pump has no record of this dose, so nothing was delivered. " +
                "Sending it again is safe \u2014 if the pump had received it, it would refuse the repeat.",
        )
    }
}

@Composable
private fun ResolvingCard(state: BolusUiState.Resolving) {
    ToneCard(Tone.Ambiguous) {
        CardTitle("Outcome unknown \u2014 asking the pump", Tone.Ambiguous)
        DoseReadout(state.dose.milliunits.value, tone = Tone.Ambiguous)
        CardBody(
            "No response yet. The command is not being sent again. " +
                "The pump is being asked what happened, and that answer decides what happens next.",
        )
    }
}

@Composable
private fun BlockedCard() {
    ToneCard(Tone.Blocked) {
        CardTitle("Dosing blocked", Tone.Blocked)
        CardBody(
            "The link is up and the pump is readable, but something is " +
                "unresolved. Dosing stays refused until it is settled.",
        )
        CardBody("The app re-checks the pump automatically every few seconds.")
    }
}

@Composable
private fun IndeterminateCard(state: BolusUiState.Indeterminate) {
    ToneCard(Tone.Blocked) {
        CardTitle("Outcome cannot be determined", Tone.Blocked)
        DoseReadout(state.dose.milliunits.value, tone = Tone.Blocked)
        CardBody(
            "The pump can no longer tell us what happened to this command. " +
                "Check the pump's own history before dosing again. This state " +
                "survives restarting the app.",
        )
    }
}

@Composable
private fun DisabledCard(state: BolusUiState.DosingDisabled) {
    ToneCard(linkTone(state.link)) {
        CardTitle("Dosing unavailable", linkTone(state.link))
        CardBody(disabledReason(state.link))
    }
}

private fun disabledReason(link: LinkStatus): String = when (link) {
    LinkStatus.Idle -> "Not linked to a pump. Open the link panel to connect."
    LinkStatus.Linking -> "Establishing a session with the pump."
    LinkStatus.Recovering -> "The link dropped. Dosing stays refused until it is back."
    LinkStatus.Failed -> "The link could not be established. Retry from the link panel."
    LinkStatus.Unpaired -> "This phone is not paired with the pump."
    LinkStatus.Ready, LinkStatus.Suspended -> "Dosing is unavailable."
}

@Composable
private fun HistorySection(rows: List<HistoryRow>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionLabel("Recent commands")
            Spacer(Modifier.height(6.dp))
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                HistoryLine(row)
            }
        }
    }
}

@Composable
private fun HistoryLine(row: HistoryRow) {
    val tone = historyTone(row.outcome)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            formatAge(row.age),
            style = MonoLabelStyle,
            modifier = Modifier.weight(1f),
        )
        Text(
            historyAmount(row),
            style = MonoLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            historyLabel(row),
            style = MaterialTheme.typography.labelSmall,
            color = tone.color(),
        )
    }
}

private fun historyAmount(row: HistoryRow): String {
    val requested = formatUnits(row.requested.milliunits.value)
    val delivered = row.delivered?.milliunits?.value
    return if (delivered == null || delivered == row.requested.milliunits.value) {
        "$requested U"
    } else {
        "${formatUnits(delivered)} / $requested U"
    }
}

private fun historyLabel(row: HistoryRow): String = when {
    row.recovered && row.outcome == HistoryOutcome.Delivered -> "recovered"
    row.recovered && row.outcome == HistoryOutcome.Partial -> "recovered"
    else -> when (row.outcome) {
        HistoryOutcome.InFlight -> "in flight"
        HistoryOutcome.Delivered -> "delivered"
        HistoryOutcome.Partial -> "partial"
        HistoryOutcome.NeverSeen -> "never seen"
        HistoryOutcome.Unknown -> "unknown"
        HistoryOutcome.Acknowledged -> "closed by user"
    }
}

private fun historyTone(outcome: HistoryOutcome): Tone = when (outcome) {
    HistoryOutcome.Delivered -> Tone.Ok
    HistoryOutcome.InFlight -> Tone.Committed
    HistoryOutcome.Partial, HistoryOutcome.NeverSeen -> Tone.Ambiguous
    HistoryOutcome.Unknown -> Tone.Blocked
    HistoryOutcome.Acknowledged -> Tone.Neutral
}

private val PRESETS_MILLIUNITS = listOf(500, 1_000, 2_000, 5_000)
private const val RESERVOIR_FULL_MILLIUNITS = 300_000f
private const val RESERVOIR_LOW_MILLIUNITS = 20_000
private const val BATTERY_LOW_PERCENT = 20
private const val ARC_BOX_HEIGHT_DP = 190
private const val ARC_SIZE_DP = 168
private const val ARC_STROKE_DP = 12
private const val ARC_START_ANGLE = 135f
private const val ARC_SWEEP = 270f
private const val INDETERMINATE_SWEEP = 48f
private const val INDETERMINATE_MS = 1_200
private const val FULL_CIRCLE_DEGREES = 360f
