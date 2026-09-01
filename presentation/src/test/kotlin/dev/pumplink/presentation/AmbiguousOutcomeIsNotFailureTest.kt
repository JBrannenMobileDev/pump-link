package dev.pumplink.presentation

import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hazard H-02, docs/04-hazard-analysis.md: an ambiguous outcome reported to the
 * user as failure invites a second dose by another route, which is the
 * catastrophic case. The control is that exhausting the retry budget produces an
 * outcome-unknown state, never a negative result.
 */
class AmbiguousOutcomeIsNotFailureTest {

    private val pump = PumpSummary(100_000, 80, false, DomainStoreId(1uL))
    private val cmd = DomainCommandId(7u)

    /** A bolus was sent and nothing has come back yet. */
    private val outstanding = JournalSnapshot(
        listOf(
            JournalEntry(
                commandId = cmd,
                storeInstanceId = DomainStoreId(1uL),
                requested = Dose(Milliunits(4_500)),
                state = JournalState.Pending,
                sentAtMillis = 0L,
            ),
            JournalEntry(
                commandId = cmd,
                storeInstanceId = DomainStoreId(1uL),
                requested = Dose(Milliunits(4_500)),
                state = JournalState.InFlight,
                sentAtMillis = 0L,
            ),
        ),
    )

    /**
     * Failed is the state the link reaches when the retry budget is exhausted.
     * The dose outcome is still unknown at that point, so the screen must say
     * so rather than reporting the delivery as failed.
     */
    @Test
    fun `retry exhaustion projects to Resolving, not a failure`() {
        val state = project(LinkStatus.Failed, outstanding, pump, null)
        assertIs<BolusUiState.Resolving>(state)
        assertEquals(cmd, state.commandId)
        assertEquals(4_500, state.dose.milliunits.value)
    }

    @Test
    fun `no unreachable link state reports an outcome for an outstanding command`() {
        val links = listOf(
            LinkStatus.Idle,
            LinkStatus.Linking,
            LinkStatus.Recovering,
            LinkStatus.Failed,
            LinkStatus.Unpaired,
        )
        links.forEach { link ->
            val state = project(link, outstanding, pump, null)
            assertIs<BolusUiState.Resolving>(state)
        }
    }

    /**
     * The CommandId has to survive into the state, because recovery is
     * query-then-decide: the app asks the pump about this exact command rather
     * than resending it.
     */
    @Test
    fun `Resolving carries the CommandId needed to query the original command`() {
        val state = project(LinkStatus.Recovering, outstanding, pump, null)
        assertIs<BolusUiState.Resolving>(state)
        assertEquals(cmd, state.commandId)
    }

    @Test
    fun `no state with an outstanding command permits dosing`() {
        val links = listOf(
            LinkStatus.Idle,
            LinkStatus.Linking,
            LinkStatus.Ready,
            LinkStatus.Suspended,
            LinkStatus.Recovering,
            LinkStatus.Failed,
            LinkStatus.Unpaired,
        )
        links.forEach { link ->
            val state = project(link, outstanding, pump, null)
            assertTrue(
                state !is BolusUiState.Entering && state !is BolusUiState.Confirming,
                "$link projected to a dosable state with a command outstanding: $state",
            )
        }
    }
}
