package dev.pumplink.presentation

import dev.pumplink.domain.AbortReason
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.Draft
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStep
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.Resolution
import kotlin.reflect.KClass

/**
 * Named projection inputs plus the [BolusUiState] subclass they must produce.
 * Shared by the JVM totality test and the debug-only state gallery, which is
 * why this lives in main rather than a test fixture.
 */
data class BolusScenario(
    val name: String,
    val link: LinkStatus,
    val progress: LinkProgress,
    val journal: JournalSnapshot,
    val pump: PumpSummary?,
    val draft: Draft?,
    val stage: Stage = Stage(),
    val resolving: DomainCommandId? = null,
    val expected: KClass<out BolusUiState>,
) {
    fun screen(): BolusScreenState = screenState(
        link = link,
        progress = progress,
        journal = journal,
        pump = pump,
        draft = draft,
        stage = stage,
        resolving = resolving,
        nowMillis = NOW_MILLIS,
    )
}

object BolusScenarios {
    private val store = DomainStoreId(1uL)
    private val pump = PumpSummary(100_000, 80, false, store)
    private val cmd = DomainCommandId(1u)
    private val dose = Dose(Milliunits(1_000))
    private val draft = Draft(Milliunits(1_000))
    private val ready = LinkProgress()
    private val linking = LinkProgress(step = LinkStep.Connecting, timeoutMillis = 10_000L)

    val all: List<BolusScenario> = listOf(
        scenario("Entering", LinkStatus.Ready, ready, JournalSnapshot(emptyList()), pump, draft) {
            BolusUiState.Entering::class
        },
        scenario(
            "Confirming",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(emptyList()),
            pump,
            draft,
            stage = Stage(step = Step.Confirming),
        ) { BolusUiState.Confirming::class },
        scenario("Delivering", LinkStatus.Ready, ready, JournalSnapshot(sent(cmd)), pump, null) {
            BolusUiState.Delivering::class
        },
        scenario(
            "Delivered",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + completed(cmd)),
            pump,
            null,
        ) { BolusUiState.Delivered::class },
        scenario(
            "Delivered, recovered by query",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + resolving(cmd) + completed(cmd)),
            pump,
            null,
        ) { BolusUiState.Delivered::class },
        scenario(
            "Partially delivered",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + aborted(cmd)),
            pump,
            null,
        ) { BolusUiState.PartiallyDelivered::class },
        scenario(
            "Awaiting reissue",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + neverSeen(cmd)),
            pump,
            null,
        ) { BolusUiState.AwaitingReissue::class },
        scenario(
            "Resolving",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + resolving(cmd)),
            pump,
            null,
        ) { BolusUiState.Resolving::class },
        scenario("Blocked", LinkStatus.Suspended, ready, JournalSnapshot(emptyList()), pump, null) {
            BolusUiState.Blocked::class
        },
        scenario(
            "Indeterminate",
            LinkStatus.Ready,
            ready,
            JournalSnapshot(sent(cmd) + indeterminate(cmd)),
            pump,
            null,
        ) { BolusUiState.Indeterminate::class },
        scenario(
            "Dosing disabled",
            LinkStatus.Idle,
            linking,
            JournalSnapshot(emptyList()),
            pump,
            null,
        ) { BolusUiState.DosingDisabled::class },
    )

    /**
     * Compiler tripwire: a new [BolusUiState] that is not named here will not
     * compile, and the test asserts every result appears in [all].
     */
    fun classify(state: BolusUiState): KClass<out BolusUiState> = when (state) {
        is BolusUiState.Entering -> BolusUiState.Entering::class
        is BolusUiState.Confirming -> BolusUiState.Confirming::class
        is BolusUiState.Delivering -> BolusUiState.Delivering::class
        is BolusUiState.Delivered -> BolusUiState.Delivered::class
        is BolusUiState.PartiallyDelivered -> BolusUiState.PartiallyDelivered::class
        is BolusUiState.AwaitingReissue -> BolusUiState.AwaitingReissue::class
        is BolusUiState.Resolving -> BolusUiState.Resolving::class
        is BolusUiState.Blocked -> BolusUiState.Blocked::class
        is BolusUiState.Indeterminate -> BolusUiState.Indeterminate::class
        is BolusUiState.DosingDisabled -> BolusUiState.DosingDisabled::class
    }

    private fun scenario(
        name: String,
        link: LinkStatus,
        progress: LinkProgress,
        journal: JournalSnapshot,
        pump: PumpSummary?,
        draft: Draft?,
        stage: Stage = Stage(),
        resolving: DomainCommandId? = null,
        expected: () -> KClass<out BolusUiState>,
    ) = BolusScenario(name, link, progress, journal, pump, draft, stage, resolving, expected())

    private fun sent(commandId: DomainCommandId) = listOf(
        entry(commandId, JournalState.Pending),
        entry(commandId, JournalState.InFlight),
    )

    private fun resolving(commandId: DomainCommandId) = entry(
        commandId,
        JournalState.Resolving,
        Resolution.InFlight,
    )

    private fun completed(commandId: DomainCommandId) = entry(
        commandId,
        JournalState.Resolved,
        Resolution.Completed(dose),
        delivered = 1_000,
    )

    private fun aborted(commandId: DomainCommandId) = entry(
        commandId,
        JournalState.Resolved,
        Resolution.Aborted(Dose(Milliunits(600)), AbortReason.Occlusion),
        delivered = 600,
    )

    private fun neverSeen(commandId: DomainCommandId) =
        entry(commandId, JournalState.Resolved, Resolution.NeverSeen)

    private fun indeterminate(commandId: DomainCommandId) =
        entry(commandId, JournalState.Indeterminate)

    private fun entry(
        commandId: DomainCommandId,
        state: JournalState,
        resolution: Resolution? = null,
        delivered: Int? = null,
    ) = JournalEntry(
        commandId = commandId,
        storeInstanceId = store,
        requested = dose,
        state = state,
        sentAtMillis = SENT_AT_MILLIS,
        delivered = delivered?.let { Dose(Milliunits(it)) },
        resolution = resolution,
    )
}

private const val SENT_AT_MILLIS = 1_000L
private const val NOW_MILLIS = 61_000L
