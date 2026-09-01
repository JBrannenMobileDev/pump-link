package dev.pumplink.data

import dev.pumplink.domain.PumpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session policy in front of [BleController]. [stop] is refused while the
 * journal still has an in-flight command, because tearing the link down then
 * is how a keep-alive loses the radio it exists to protect.
 */
@Singleton
class PumpSession @Inject constructor(
    private val controller: BleController,
) : PumpRepository by controller {
    private val _sessionRequested = MutableStateFlow(false)
    val sessionRequested: StateFlow<Boolean> = _sessionRequested

    /** Process relaunch with rows still in flight: hold the session without a new Start tap. */
    fun holdForInFlightJournal() {
        if (controller.journal.value.inFlight().isNotEmpty()) {
            _sessionRequested.value = true
        }
    }

    override fun start() {
        _sessionRequested.value = true
        controller.start()
    }

    override fun stop() {
        if (controller.journal.value.inFlight().isNotEmpty()) return
        _sessionRequested.value = false
        controller.stop()
    }
}
