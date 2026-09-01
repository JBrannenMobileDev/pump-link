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
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class BolusUiTest {

    private val pump = PumpSummary(100_000, 80, false, DomainStoreId(1uL))
    private val store = DomainStoreId(1uL)
    private val cmd = DomainCommandId(1u)

    private fun entry(
        state: JournalState,
        resolution: Resolution? = null,
        commandId: DomainCommandId = cmd,
        requested: Int = 1_000,
        delivered: Int? = null,
    ) = JournalEntry(
        commandId = commandId,
        storeInstanceId = store,
        requested = Dose(Milliunits(requested)),
        state = state,
        sentAtMillis = 0L,
        delivered = delivered?.let { Dose(Milliunits(it)) },
        resolution = resolution,
    )

    /** The rows DeliverBolusUseCase.prepare actually writes, in order. */
    private fun sentRows(commandId: DomainCommandId = cmd, requested: Int = 1_000) = listOf(
        entry(JournalState.Pending, commandId = commandId, requested = requested),
        entry(JournalState.InFlight, commandId = commandId, requested = requested),
    )

    @Test
    fun `Ready with an empty journal is Entering`() {
        val state = project(LinkStatus.Ready, JournalSnapshot(emptyList()), pump, Draft(Milliunits(0)))
        assertIs<BolusUiState.Entering>(state)
    }

    @Test
    fun `Ready plus an unresolved journal entry never enables dosing`() {
        val state = project(LinkStatus.Ready, JournalSnapshot(sentRows()), pump, null)
        assertIs<BolusUiState.Delivering>(state)
    }

    @Test
    fun `Indeterminate journal projects to Indeterminate regardless of link`() {
        val journal = JournalSnapshot(sentRows() + entry(JournalState.Indeterminate))
        listOf(LinkStatus.Ready, LinkStatus.Idle, LinkStatus.Failed).forEach { link ->
            assertIs<BolusUiState.Indeterminate>(project(link, journal, pump, null))
        }
    }

    @Test
    fun `linking states collapse to DosingDisabled when nothing is outstanding`() {
        val empty = JournalSnapshot(emptyList())
        listOf(LinkStatus.Idle, LinkStatus.Linking, LinkStatus.Recovering, LinkStatus.Failed, LinkStatus.Unpaired)
            .forEach { link ->
                assertIs<BolusUiState.DosingDisabled>(project(link, empty, pump, null))
            }
    }

    @Test
    fun `Suspended projects to Blocked`() {
        val state = project(LinkStatus.Suspended, JournalSnapshot(emptyList()), pump, null)
        assertIs<BolusUiState.Blocked>(state)
    }

    /**
     * Regression: the journal is append-only, so the Pending and InFlight rows
     * survive resolution. Reading them as current state pinned the screen to
     * Delivering forever and made a second bolus impossible.
     */
    @Test
    fun `a resolved command does not leave stale in-flight rows showing Delivering`() {
        val journal = JournalSnapshot(
            sentRows() + entry(
                JournalState.Resolved,
                Resolution.Completed(Dose(Milliunits(1_000))),
                delivered = 1_000,
            ),
        )
        val state = project(LinkStatus.Ready, journal, pump, null)
        assertIs<BolusUiState.Delivered>(state)
        assertEquals(1_000, state.delivered.milliunits.value)
    }

    @Test
    fun `acknowledging a delivery returns to Entering`() {
        val journal = JournalSnapshot(
            sentRows() + entry(
                JournalState.Resolved,
                Resolution.Completed(Dose(Milliunits(1_000))),
                delivered = 1_000,
            ),
        )
        val acked = project(LinkStatus.Ready, journal, pump, null, Stage(acknowledged = cmd))
        assertIs<BolusUiState.Entering>(acked)
    }

    @Test
    fun `acknowledging one command does not dismiss a later one`() {
        val second = DomainCommandId(2u)
        val journal = JournalSnapshot(
            sentRows() + entry(JournalState.Resolved, Resolution.Completed(Dose(Milliunits(1_000)))) +
                sentRows(second, 500) +
                entry(JournalState.Resolved, Resolution.Completed(Dose(Milliunits(500))), commandId = second),
        )
        val state = project(LinkStatus.Ready, journal, pump, null, Stage(acknowledged = cmd))
        assertIs<BolusUiState.Delivered>(state)
        assertEquals(second, state.commandId)
    }

    @Test
    fun `a command the pump never saw asks for a reissue decision`() {
        val journal = JournalSnapshot(sentRows() + entry(JournalState.Resolved, Resolution.NeverSeen))
        val state = project(LinkStatus.Ready, journal, pump, null)
        assertIs<BolusUiState.AwaitingReissue>(state)
        assertEquals(cmd, state.commandId)
    }

    @Test
    fun `an acknowledged hazard stops blocking dosing`() {
        val journal = JournalSnapshot(
            sentRows() + entry(JournalState.Indeterminate) + entry(JournalState.Acknowledged),
        )
        assertNull(journal.indeterminate())
        assertIs<BolusUiState.Entering>(project(LinkStatus.Ready, journal, pump, null))
    }

    @Test
    fun `an aborted delivery reports the partial amount and reason`() {
        val journal = JournalSnapshot(
            sentRows() + entry(
                JournalState.Resolved,
                Resolution.Aborted(Dose(Milliunits(600)), AbortReason.Occlusion),
                delivered = 600,
            ),
        )
        val state = project(LinkStatus.Ready, journal, pump, null)
        assertIs<BolusUiState.PartiallyDelivered>(state)
        assertEquals(600, state.delivered.milliunits.value)
        assertEquals(AbortReason.Occlusion, state.reason)
    }

    @Test
    fun `Confirming is reachable only through the stage`() {
        val journal = JournalSnapshot(emptyList())
        val draft = Draft(Milliunits(1_000))
        assertIs<BolusUiState.Entering>(project(LinkStatus.Ready, journal, pump, draft, Stage()))
        val confirming = project(LinkStatus.Ready, journal, pump, draft, Stage(step = Step.Confirming))
        assertIs<BolusUiState.Confirming>(confirming)
        assertEquals(1_000, confirming.dose.milliunits.value)
    }

    @Test
    fun `a confirmed dose needs a second confirmation before it is handed off`() {
        assertEquals(Stage(step = Step.Confirming), reduce(Stage(), BolusIntent.Confirmed))
        assertEquals(Stage(), reduce(Stage(step = Step.Confirming), BolusIntent.Confirmed))
    }

    @Test
    fun `cancelling from Confirming returns to editing`() {
        assertEquals(Stage(), reduce(Stage(step = Step.Confirming), BolusIntent.Cancelled))
    }

    @Test
    fun `editing the dose leaves the confirmation step`() {
        assertEquals(Stage(), reduce(Stage(step = Step.Confirming), BolusIntent.DoseEntered(2_000)))
    }

    @Test
    fun `every intent is handled from every stage`() {
        val stages = listOf(
            Stage(),
            Stage(step = Step.Confirming),
            Stage(acknowledged = cmd),
            Stage(step = Step.Confirming, acknowledged = cmd),
        )
        val intents = listOf(
            BolusIntent.DoseEntered(2_000),
            BolusIntent.Confirmed,
            BolusIntent.Cancelled,
            BolusIntent.Acknowledged(cmd),
            BolusIntent.ReissueConfirmed,
            BolusIntent.ReissueDeclined,
            BolusIntent.PumpVerifiedByUser,
            BolusIntent.RecheckRequested,
        )
        stages.forEach { stage ->
            intents.forEach { intent ->
                // Total function: no combination throws and none is unhandled.
                reduce(stage, intent)
            }
        }
    }

    @Test
    fun `history reports one row per CommandId, most recent first`() {
        val second = DomainCommandId(2u)
        val journal = JournalSnapshot(
            sentRows() + entry(JournalState.Resolved, Resolution.Completed(Dose(Milliunits(1_000)))) +
                sentRows(second, 500),
        )
        val rows = history(journal)
        assertEquals(2, rows.size)
        assertEquals(second, rows[0].commandId)
        assertEquals(HistoryOutcome.InFlight, rows[0].outcome)
        assertEquals(cmd, rows[1].commandId)
        assertEquals(HistoryOutcome.Delivered, rows[1].outcome)
    }

    @Test
    fun `RecheckRequested leaves the stage and the projection untouched`() {
        assertEquals(Stage(), reduce(Stage(), BolusIntent.RecheckRequested))
        assertEquals(
            Stage(step = Step.Confirming),
            reduce(Stage(step = Step.Confirming), BolusIntent.RecheckRequested),
        )
        val acked = Stage(acknowledged = cmd)
        assertEquals(acked, reduce(acked, BolusIntent.RecheckRequested))
        val blocked = project(LinkStatus.Suspended, JournalSnapshot(emptyList()), pump, null)
        assertIs<BolusUiState.Blocked>(blocked)
    }

    @Test
    fun `vitalsStale is chrome, not a dosing gate`() {
        val fresh = screenState(LinkStatus.Ready, LinkProgress(), JournalSnapshot(emptyList()), pump, Draft(Milliunits(0)))
        val stale = screenState(
            LinkStatus.Ready,
            LinkProgress(),
            JournalSnapshot(emptyList()),
            pump,
            Draft(Milliunits(0)),
            vitalsStale = true,
        )
        assertIs<BolusUiState.Entering>(fresh.bolus)
        assertIs<BolusUiState.Entering>(stale.bolus)
        assertEquals(false, fresh.vitalsStale)
        assertEquals(true, stale.vitalsStale)
    }

    @Test
    fun `acknowledging a delivery survives a later confirm`() {
        val dismissed = reduce(Stage(), BolusIntent.Acknowledged(cmd))
        val next = reduce(dismissed, BolusIntent.Confirmed)
        assertEquals(cmd, next.acknowledged)
        assertEquals(Step.Confirming, next.step)
        val journal = JournalSnapshot(
            sentRows() + entry(
                JournalState.Resolved,
                Resolution.Completed(Dose(Milliunits(1_000))),
                delivered = 1_000,
            ),
        )
        val state = project(LinkStatus.Ready, journal, pump, Draft(Milliunits(1_000)), next)
        assertIs<BolusUiState.Confirming>(state)
    }

    @Test
    fun `acknowledging a delivery survives a later edit`() {
        val dismissed = reduce(Stage(), BolusIntent.Acknowledged(cmd))
        val next = reduce(dismissed, BolusIntent.DoseEntered(2_000))
        assertEquals(cmd, next.acknowledged)
        assertEquals(Step.Editing, next.step)
        val journal = JournalSnapshot(
            sentRows() + entry(
                JournalState.Resolved,
                Resolution.Completed(Dose(Milliunits(1_000))),
                delivered = 1_000,
            ),
        )
        val state = project(LinkStatus.Ready, journal, pump, Draft(Milliunits(2_000)), next)
        assertIs<BolusUiState.Entering>(state)
    }

    @Test
    fun `actions is total over every BolusUiState`() {
        everyUiState().forEach { state ->
            actions(state)
        }
    }

    @Test
    fun `Deliver is offered only from Confirming`() {
        everyUiState().forEach { state ->
            val offered = actions(state)
            if (state is BolusUiState.Confirming) {
                assertTrue(BolusAction.Deliver in offered)
            } else {
                assertFalse(BolusAction.Deliver in offered, "$state offered Deliver")
            }
        }
    }

    private fun everyUiState(): List<BolusUiState> = listOf(
        BolusUiState.Entering(Draft(Milliunits(1_000)), pump),
        BolusUiState.Confirming(Dose(Milliunits(1_000)), pump),
        BolusUiState.Delivering(Dose(Milliunits(0)), Dose(Milliunits(1_000)), cmd),
        BolusUiState.Delivered(Dose(Milliunits(1_000)), cmd),
        BolusUiState.PartiallyDelivered(Dose(Milliunits(600)), AbortReason.Occlusion, cmd),
        BolusUiState.AwaitingReissue(Dose(Milliunits(1_000)), Duration.ZERO, cmd),
        BolusUiState.Resolving(Dose(Milliunits(1_000)), cmd),
        BolusUiState.Blocked(Dose(Milliunits(1_000)), cmd),
        BolusUiState.Indeterminate(Dose(Milliunits(1_000)), cmd),
        BolusUiState.DosingDisabled(LinkStatus.Idle),
    )
}
