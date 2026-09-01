package dev.pumplink.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolveInFlightCommandUseCaseTest {

    @Test
    fun `markResolving is durable and wasRecovered survives settlement`() = runTest {
        val journal = InMemoryJournal()
        val ids = CommandIdSource { DomainCommandId(1u) }
        val deliver = DeliverBolusUseCase(journal, ids, Clock { 1_000L })
        val resolve = ResolveInFlightCommandUseCase(journal)
        val command = deliver.prepare(Dose(Milliunits(1_000)), DomainStoreId(9uL)).commandId

        resolve.markResolving(command)
        assertEquals(JournalState.Resolving, journal.snapshot().latest(command)?.state)
        assertTrue(journal.snapshot().wasRecovered(command))
        assertEquals(command, journal.snapshot().asking()?.commandId)

        resolve.resolve(command, Resolution.Completed(Dose(Milliunits(1_000))))
        assertEquals(JournalState.Resolved, journal.snapshot().latest(command)?.state)
        assertTrue(journal.snapshot().wasRecovered(command))
        assertEquals(null, journal.snapshot().asking())
    }
}
