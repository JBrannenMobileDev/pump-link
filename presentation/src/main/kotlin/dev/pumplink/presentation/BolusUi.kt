package dev.pumplink.presentation

import dev.pumplink.domain.AbortReason
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.Draft
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.Resolution
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface BolusIntent {
    data class DoseEntered(val milliunits: Int) : BolusIntent
    data object Confirmed : BolusIntent
    data object Cancelled : BolusIntent

    /**
     * Clears a finished result so the operator can dose again. Required by
     * ADR-03: results are state, so something has to retire them explicitly.
     * Carries the CommandId so a result arriving while the card is open cannot
     * be dismissed by a tap aimed at the previous one.
     */
    data class Acknowledged(val commandId: DomainCommandId) : BolusIntent
    data object ReissueConfirmed : BolusIntent
    data object ReissueDeclined : BolusIntent
    data object PumpVerifiedByUser : BolusIntent
    data object RecheckRequested : BolusIntent
}

sealed interface BolusUiState {
    data class Entering(val draft: Draft, val pump: PumpSummary) : BolusUiState
    data class Confirming(val dose: Dose, val pump: PumpSummary) : BolusUiState
    data class Delivering(
        val delivered: Dose,
        val requested: Dose,
        val commandId: DomainCommandId,
    ) : BolusUiState
    data class Delivered(
        val delivered: Dose,
        val commandId: DomainCommandId,
        val recovered: Boolean = false,
    ) : BolusUiState
    data class PartiallyDelivered(
        val delivered: Dose,
        val reason: AbortReason,
        val commandId: DomainCommandId,
        val recovered: Boolean = false,
    ) : BolusUiState
    data class AwaitingReissue(
        val dose: Dose,
        val elapsed: Duration,
        val commandId: DomainCommandId,
    ) : BolusUiState
    data class Resolving(val dose: Dose, val commandId: DomainCommandId) : BolusUiState
    data class Blocked(val dose: Dose, val commandId: DomainCommandId?) : BolusUiState
    data class Indeterminate(val dose: Dose, val commandId: DomainCommandId) : BolusUiState
    data class DosingDisabled(val link: LinkStatus) : BolusUiState
}

/**
 * Local, ephemeral UI position. Everything safety-relevant is derived from the
 * journal by [project]; this holds only what is legitimately allowed to be
 * forgotten if the process dies mid-tap.
 *
 * [step] is where the operator is in dose entry. [acknowledged] is the result
 * card they have dismissed. Those are independent facts: acknowledging a
 * delivery must survive the next edit or confirm, or the result card returns
 * and a second bolus is impossible.
 *
 * Clearing an Indeterminate is deliberately *not* here. That is a hazard state,
 * so it is retired through the journal ([dev.pumplink.domain.JournalState.Acknowledged])
 * and survives process death.
 */
data class Stage(
    val step: Step = Step.Editing,
    val acknowledged: DomainCommandId? = null,
)

enum class Step {
    Editing,
    Confirming,
}

fun Stage.acknowledges(commandId: DomainCommandId): Boolean = acknowledged == commandId

/**
 * Reduces the local stage. Only [BolusIntent.Acknowledged] writes
 * [Stage.acknowledged]; every other intent writes only [Stage.step] and
 * carries the dismissal through. This function has no access to the journal,
 * so no sequence of intents can make the UI claim a delivery outcome that
 * the pump did not report.
 */
fun reduce(stage: Stage, intent: BolusIntent): Stage = when (intent) {
    is BolusIntent.DoseEntered -> stage.copy(step = Step.Editing)
    BolusIntent.Confirmed -> when (stage.step) {
        Step.Editing -> stage.copy(step = Step.Confirming)
        // Second confirmation. The delivery is handed off and the projection
        // takes over from here.
        Step.Confirming -> stage.copy(step = Step.Editing)
    }
    BolusIntent.Cancelled -> stage.copy(step = Step.Editing)
    is BolusIntent.Acknowledged -> stage.copy(acknowledged = intent.commandId)
    BolusIntent.ReissueConfirmed,
    BolusIntent.ReissueDeclined,
    BolusIntent.PumpVerifiedByUser,
    -> stage.copy(step = Step.Editing)
    BolusIntent.RecheckRequested -> stage
}

/**
 * What the operator may do from a projected state. A total function so a new
 * [BolusUiState] that forgets to name its actions is a compile error, and so
 * the bottom bar is tested without Compose.
 */
sealed interface BolusAction {
    data object ReviewDose : BolusAction
    data object Cancel : BolusAction
    data object Deliver : BolusAction
    data class Done(val commandId: DomainCommandId) : BolusAction
    data object Reissue : BolusAction
    data object DeclineReissue : BolusAction
    data object Recheck : BolusAction
    data object ConfirmCheckedAtPump : BolusAction
    data object OpenLinkPanel : BolusAction
}

fun actions(state: BolusUiState): List<BolusAction> = when (state) {
    is BolusUiState.Entering -> listOf(BolusAction.ReviewDose)
    is BolusUiState.Confirming -> listOf(BolusAction.Cancel, BolusAction.Deliver)
    is BolusUiState.Delivering,
    is BolusUiState.Resolving,
    -> emptyList()
    is BolusUiState.Delivered -> listOf(BolusAction.Done(state.commandId))
    is BolusUiState.PartiallyDelivered -> listOf(BolusAction.Done(state.commandId))
    is BolusUiState.AwaitingReissue -> listOf(BolusAction.DeclineReissue, BolusAction.Reissue)
    is BolusUiState.Blocked -> listOf(BolusAction.Recheck)
    is BolusUiState.Indeterminate -> listOf(BolusAction.ConfirmCheckedAtPump)
    is BolusUiState.DosingDisabled -> listOf(BolusAction.OpenLinkPanel)
}

/**
 * Everything the screen renders. [bolus] is the safety-critical projection;
 * the rest is chrome that must stay legible in every state, which is why link
 * and pump live here rather than inside individual [BolusUiState] cases.
 */
data class BolusScreenState(
    val bolus: BolusUiState,
    val link: LinkStatus,
    val progress: LinkProgress,
    val pump: PumpSummary?,
    val history: List<HistoryRow>,
    val vitalsStale: Boolean = false,
)

enum class HistoryOutcome {
    InFlight,
    Delivered,
    Partial,
    NeverSeen,
    Unknown,
    Acknowledged,
}

data class HistoryRow(
    val commandId: DomainCommandId,
    val requested: Dose,
    val delivered: Dose?,
    val outcome: HistoryOutcome,
    val sentAtMillis: Long,
    val age: Duration,
    val recovered: Boolean = false,
)

fun screenState(
    link: LinkStatus,
    progress: LinkProgress,
    journal: JournalSnapshot,
    pump: PumpSummary?,
    draft: Draft?,
    stage: Stage = Stage(),
    resolving: DomainCommandId? = null,
    vitalsStale: Boolean = false,
    nowMillis: Long = 0L,
): BolusScreenState = BolusScreenState(
    bolus = project(link, journal, pump, draft, stage, resolving, vitalsStale),
    link = link,
    progress = progress,
    pump = pump,
    history = history(journal, nowMillis),
    vitalsStale = vitalsStale,
)

fun history(
    journal: JournalSnapshot,
    nowMillis: Long = 0L,
    limit: Int = HISTORY_LIMIT,
): List<HistoryRow> =
    journal.current()
        .asReversed()
        .take(limit)
        .map { entry ->
            HistoryRow(
                commandId = entry.commandId,
                requested = entry.requested,
                delivered = entry.delivered,
                outcome = outcomeOf(entry),
                sentAtMillis = entry.sentAtMillis,
                age = elapsedSince(entry.sentAtMillis, nowMillis),
                recovered = journal.wasRecovered(entry.commandId),
            )
        }

private const val HISTORY_LIMIT = 6

private fun outcomeOf(entry: JournalEntry): HistoryOutcome = when (entry.state) {
    JournalState.Pending,
    JournalState.InFlight,
    JournalState.Resolving,
    -> HistoryOutcome.InFlight
    JournalState.Indeterminate -> HistoryOutcome.Unknown
    JournalState.Acknowledged -> HistoryOutcome.Acknowledged
    JournalState.Resolved -> when (entry.resolution) {
        is Resolution.Completed -> HistoryOutcome.Delivered
        is Resolution.Aborted -> HistoryOutcome.Partial
        Resolution.NeverSeen -> HistoryOutcome.NeverSeen
        Resolution.InFlight -> HistoryOutcome.InFlight
        Resolution.Indeterminate, null -> HistoryOutcome.Unknown
    }
}

fun project(
    link: LinkStatus,
    journal: JournalSnapshot,
    pump: PumpSummary?,
    draft: Draft?,
    stage: Stage = Stage(),
    resolving: DomainCommandId? = null,
    @Suppress("UNUSED_PARAMETER") vitalsStale: Boolean = false,
): BolusUiState {
    // Hazard states are checked before the link, because "we do not know what
    // the pump did" is true whether or not a radio is currently connected.
    journal.indeterminate()?.let {
        return BolusUiState.Indeterminate(it.requested, it.commandId)
    }
    journal.asking()?.let {
        return BolusUiState.Resolving(it.requested, it.commandId)
    }
    journal.awaitingReissue()?.let {
        return BolusUiState.AwaitingReissue(it.requested, Duration.ZERO, it.commandId)
    }

    // A query is in flight for this command. H-02: say we are asking, even
    // though the link itself is healthy enough to ask over.
    if (resolving != null) {
        journal.latest(resolving)?.let {
            return BolusUiState.Resolving(it.requested, resolving)
        }
    }

    val outstanding = journal.inFlight().lastOrNull()
    return when (link) {
        LinkStatus.Ready -> projectReady(journal, pump, draft, stage, outstanding)
        LinkStatus.Suspended -> BolusUiState.Blocked(
            outstanding?.requested ?: NO_DOSE,
            outstanding?.commandId,
        )
        LinkStatus.Idle,
        LinkStatus.Linking,
        LinkStatus.Recovering,
        LinkStatus.Failed,
        LinkStatus.Unpaired,
        -> unreachablePump(link, outstanding)
    }
}

/**
 * H-02: an outstanding command over a link we cannot currently ask through is an
 * outcome still being determined, never a failure. Reporting it as failure is
 * what makes a user dose again by another route.
 */
private fun unreachablePump(link: LinkStatus, outstanding: JournalEntry?): BolusUiState =
    if (outstanding == null) {
        BolusUiState.DosingDisabled(link)
    } else {
        BolusUiState.Resolving(outstanding.requested, outstanding.commandId)
    }

private fun projectReady(
    journal: JournalSnapshot,
    pump: PumpSummary?,
    draft: Draft?,
    stage: Stage,
    outstanding: JournalEntry?,
): BolusUiState {
    if (outstanding != null) {
        if (outstanding.state == JournalState.Resolving) {
            return BolusUiState.Resolving(outstanding.requested, outstanding.commandId)
        }
        return when (val resolution = outstanding.resolution) {
            Resolution.InFlight, null -> BolusUiState.Delivering(
                outstanding.delivered ?: NO_DOSE,
                outstanding.requested,
                outstanding.commandId,
            )
            Resolution.NeverSeen -> BolusUiState.AwaitingReissue(
                outstanding.requested,
                Duration.ZERO,
                outstanding.commandId,
            )
            is Resolution.Completed -> BolusUiState.Delivered(
                resolution.delivered,
                outstanding.commandId,
                recovered = journal.wasRecovered(outstanding.commandId),
            )
            is Resolution.Aborted -> BolusUiState.PartiallyDelivered(
                resolution.delivered,
                resolution.reason,
                outstanding.commandId,
                recovered = journal.wasRecovered(outstanding.commandId),
            )
            Resolution.Indeterminate -> BolusUiState.Indeterminate(
                outstanding.requested,
                outstanding.commandId,
            )
        }
    }

    val terminal = journal.lastTerminal()
    if (terminal != null && !stage.acknowledges(terminal.commandId)) {
        val settled = settled(terminal, journal)
        if (settled != null) return settled
    }

    val summary = pump ?: return BolusUiState.DosingDisabled(LinkStatus.Linking)
    val entered = draft ?: Draft(Milliunits(0))
    return when (stage.step) {
        Step.Confirming -> BolusUiState.Confirming(Dose(entered.milliunits), summary)
        Step.Editing -> BolusUiState.Entering(entered, summary)
    }
}

private fun settled(entry: JournalEntry, journal: JournalSnapshot): BolusUiState? = when (val resolution = entry.resolution) {
    is Resolution.Completed -> BolusUiState.Delivered(
        resolution.delivered,
        entry.commandId,
        recovered = journal.wasRecovered(entry.commandId),
    )
    is Resolution.Aborted -> BolusUiState.PartiallyDelivered(
        resolution.delivered,
        resolution.reason,
        entry.commandId,
        recovered = journal.wasRecovered(entry.commandId),
    )
    Resolution.NeverSeen,
    Resolution.InFlight,
    Resolution.Indeterminate,
    null,
    -> null
}

private val NO_DOSE = Dose(Milliunits(0))

fun elapsedSince(sentAtMillis: Long, nowMillis: Long): Duration =
    (nowMillis - sentAtMillis).coerceAtLeast(0L).milliseconds
