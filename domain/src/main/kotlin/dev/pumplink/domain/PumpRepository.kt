package dev.pumplink.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * App-facing pump session. The ViewModel talks only to this port.
 * Transmission, GATT, and process keep-alive live behind it.
 */
interface PumpRepository {
    val linkStatus: StateFlow<LinkStatus>
    val linkProgress: StateFlow<LinkProgress>
    val pump: StateFlow<PumpSummary?>
    val resolving: StateFlow<DomainCommandId?>
    val vitalsStale: StateFlow<Boolean>
    val journal: StateFlow<JournalSnapshot>
    val pairedIdentity: String

    fun start()
    fun stop()
    suspend fun deliverBolus(dose: Dose)
    suspend fun reissue(commandId: DomainCommandId, dose: Dose)
    suspend fun acknowledge(commandId: DomainCommandId)
    fun requestReconcile()
}
