package dev.pumplink

import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.PumpRepository
import dev.pumplink.domain.PumpSummary
import kotlinx.coroutines.flow.MutableStateFlow

class FakePumpRepository : PumpRepository {
    override val linkStatus = MutableStateFlow<LinkStatus>(LinkStatus.Ready)
    override val linkProgress = MutableStateFlow(LinkProgress())
    override val pump = MutableStateFlow<PumpSummary?>(null)
    override val resolving = MutableStateFlow<DomainCommandId?>(null)
    override val vitalsStale = MutableStateFlow(false)
    override val journal = MutableStateFlow(JournalSnapshot(emptyList()))
    override val pairedIdentity: String = "test-pump"

    val delivered = mutableListOf<Dose>()
    val reissued = mutableListOf<Pair<DomainCommandId, Dose>>()
    val acknowledged = mutableListOf<DomainCommandId>()
    var startCount = 0
    var stopCount = 0
    var reconcileCount = 0

    override fun start() {
        startCount += 1
    }

    override fun stop() {
        stopCount += 1
    }

    override suspend fun deliverBolus(dose: Dose) {
        delivered += dose
    }

    override suspend fun reissue(commandId: DomainCommandId, dose: Dose) {
        reissued += commandId to dose
    }

    override suspend fun acknowledge(commandId: DomainCommandId) {
        acknowledged += commandId
    }

    override fun requestReconcile() {
        reconcileCount += 1
    }
}
