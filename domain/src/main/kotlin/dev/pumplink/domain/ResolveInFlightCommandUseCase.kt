package dev.pumplink.domain

/**
 * Maps a pump outcome onto a journal update. STORE_REPLACED and EVICTED
 * collapse to Indeterminate; NEVER_SEEN stays NeverSeen so the UI can
 * decide whether to reissue.
 */
class ResolveInFlightCommandUseCase(
    private val journal: CommandJournal,
) {
    /**
     * Records that we are about to ask the pump. Must be written before the
     * query so a death mid-ask is distinguishable from a death mid-send.
     */
    suspend fun markResolving(commandId: DomainCommandId) {
        val current = journal.snapshot().latest(commandId)
            ?: error("no journal entry for $commandId")
        if (current.state == JournalState.Resolving) return
        journal.append(
            current.copy(
                state = JournalState.Resolving,
                resolution = current.resolution ?: Resolution.InFlight,
            ),
        )
    }

    suspend fun resolve(commandId: DomainCommandId, resolution: Resolution) {
        val current = journal.snapshot().latest(commandId)
            ?: error("no journal entry for $commandId")
        val state = when (resolution) {
            Resolution.NeverSeen -> JournalState.Resolved
            Resolution.InFlight -> JournalState.InFlight
            is Resolution.Completed -> JournalState.Resolved
            is Resolution.Aborted -> JournalState.Resolved
            Resolution.Indeterminate -> JournalState.Indeterminate
        }
        journal.append(
            current.copy(
                state = state,
                resolution = resolution,
                delivered = when (resolution) {
                    is Resolution.Completed -> resolution.delivered
                    is Resolution.Aborted -> resolution.delivered
                    Resolution.NeverSeen,
                    Resolution.InFlight,
                    Resolution.Indeterminate,
                    -> current.delivered
                },
            ),
        )
    }

    /**
     * Records that a human closed this command out against the pump itself.
     *
     * This is journaled rather than held in memory on purpose: an Indeterminate
     * outcome is a hazard state, and if a process death cleared it the app would
     * come back willing to dose with the same question still open. The row keeps
     * its unresolved [Resolution] so the log never claims the pump answered.
     */
    suspend fun acknowledge(commandId: DomainCommandId) {
        val current = journal.snapshot().latest(commandId)
            ?: error("no journal entry for $commandId")
        journal.append(current.copy(state = JournalState.Acknowledged))
    }
}
