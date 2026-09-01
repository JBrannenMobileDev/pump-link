package dev.pumplink.domain

/**
 * Allocates a CommandId, journals it, and returns the entry that the data
 * layer may transmit. Transmission is not this class's job.
 */
class DeliverBolusUseCase(
    private val journal: CommandJournal,
    private val ids: CommandIdSource,
    private val clock: Clock,
) {
    suspend fun prepare(dose: Dose, store: DomainStoreId): JournalEntry {
        val entry = JournalEntry(
            commandId = ids.next(),
            storeInstanceId = store,
            requested = dose,
            state = JournalState.Pending,
            sentAtMillis = clock.nowMillis(),
        )
        journal.append(entry)
        journal.append(entry.copy(state = JournalState.InFlight))
        return entry.copy(state = JournalState.InFlight)
    }
}

fun interface CommandIdSource {
    fun next(): DomainCommandId
}
