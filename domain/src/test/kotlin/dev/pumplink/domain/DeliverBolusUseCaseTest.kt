package dev.pumplink.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliverBolusUseCaseTest {

    @Test
    fun `journals Pending then InFlight before returning`() = runTest {
        val journal = InMemoryJournal()
        var next = 1u
        val useCase = DeliverBolusUseCase(
            journal = journal,
            ids = CommandIdSource { DomainCommandId(next++) },
            clock = Clock { 1_000L },
        )
        val entry = useCase.prepare(Dose(Milliunits(1_000)), DomainStoreId(9uL))
        assertEquals(JournalState.InFlight, entry.state)
        val states = journal.snapshot().entries.map { it.state }
        assertEquals(listOf(JournalState.Pending, JournalState.InFlight), states)
        assertTrue(journal.snapshot().inFlight().isNotEmpty())
    }
}

class InMemoryJournal : CommandJournal {
    private val entries = mutableListOf<JournalEntry>()

    override suspend fun append(entry: JournalEntry) {
        entries += entry
    }

    override fun snapshot(): JournalSnapshot = JournalSnapshot(entries.toList())
}
