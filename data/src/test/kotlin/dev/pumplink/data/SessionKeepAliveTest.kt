package dev.pumplink.data

import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.Resolution
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionKeepAliveTest {

    @Test
    fun idleAndEmptyJournalIsNotForeground() {
        assertFalse(SessionKeepAlive.shouldBeForeground(false, JournalSnapshot(emptyList())))
    }

    @Test
    fun sessionRequestedHoldsEvenWhenIdle() {
        assertTrue(SessionKeepAlive.shouldBeForeground(true, JournalSnapshot(emptyList())))
    }

    @Test
    fun pendingInFlightAndResolvingHoldAfterStop() {
        listOf(JournalState.Pending, JournalState.InFlight, JournalState.Resolving).forEach { state ->
            assertTrue(
                SessionKeepAlive.shouldBeForeground(false, JournalSnapshot(listOf(entry(state)))),
                "expected hold for $state",
            )
        }
    }

    @Test
    fun settledRowsDoNotHold() {
        listOf(
            entry(JournalState.Resolved, Resolution.Completed(Dose(Milliunits(1_000)))),
            entry(JournalState.Indeterminate, Resolution.Indeterminate),
            entry(JournalState.Acknowledged, Resolution.Indeterminate),
        ).forEach { row ->
            assertFalse(
                SessionKeepAlive.shouldBeForeground(false, JournalSnapshot(listOf(row))),
                "did not expect hold for ${row.state}",
            )
        }
    }

    @Test
    fun laterResolvedRowWinsOverEarlierInFlight() {
        val command = DomainCommandId(1u)
        val journal = JournalSnapshot(
            listOf(
                entry(JournalState.InFlight, commandId = command),
                entry(
                    JournalState.Resolved,
                    Resolution.Completed(Dose(Milliunits(1_000))),
                    commandId = command,
                ),
            ),
        )
        assertFalse(SessionKeepAlive.shouldBeForeground(false, journal))
    }

    private fun entry(
        state: JournalState,
        resolution: Resolution? = null,
        commandId: DomainCommandId = DomainCommandId(1u),
    ) = JournalEntry(
        commandId = commandId,
        storeInstanceId = DomainStoreId(1uL),
        requested = Dose(Milliunits(1_000)),
        state = state,
        sentAtMillis = 0L,
        resolution = resolution,
    )
}
