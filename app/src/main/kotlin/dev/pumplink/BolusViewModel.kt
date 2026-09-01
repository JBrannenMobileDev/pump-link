package dev.pumplink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.Draft
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpRepository
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.SafetyLimits
import dev.pumplink.presentation.BolusIntent
import dev.pumplink.presentation.BolusScreenState
import dev.pumplink.presentation.Stage
import dev.pumplink.presentation.Step
import dev.pumplink.presentation.reduce
import dev.pumplink.presentation.screenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class LinkSnapshot(
    val status: LinkStatus,
    val progress: LinkProgress,
    val pump: PumpSummary?,
    val resolving: DomainCommandId?,
    val vitalsStale: Boolean,
)

@HiltViewModel
class BolusViewModel @Inject constructor(
    private val pump: PumpRepository,
) : ViewModel() {
    private val draft = MutableStateFlow(Draft(Milliunits(SafetyLimits.INCREMENT_MILLIUNITS * 20)))
    private val stage = MutableStateFlow(Stage())

    val pairedIdentity: String get() = pump.pairedIdentity

    private val link = combine(
        pump.linkStatus,
        pump.linkProgress,
        pump.pump,
        pump.resolving,
        pump.vitalsStale,
    ) { status, progress, summary, resolving, vitalsStale ->
        LinkSnapshot(status, progress, summary, resolving, vitalsStale)
    }

    val uiState: StateFlow<BolusScreenState> = combine(
        link,
        pump.journal,
        draft,
        stage,
    ) { snapshot, journal, currentDraft, currentStage ->
        screenState(
            link = snapshot.status,
            progress = snapshot.progress,
            journal = journal,
            pump = snapshot.pump,
            draft = currentDraft,
            stage = currentStage,
            resolving = snapshot.resolving,
            vitalsStale = snapshot.vitalsStale,
            nowMillis = System.currentTimeMillis(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        screenState(
            link = LinkStatus.Idle,
            progress = LinkProgress(),
            journal = pump.journal.value,
            pump = null,
            draft = draft.value,
            nowMillis = System.currentTimeMillis(),
        ),
    )

    fun start() {
        pump.start()
    }

    fun stop() {
        pump.stop()
    }

    fun onIntent(intent: BolusIntent) {
        val previous = stage.value
        stage.value = reduce(previous, intent)
        when (intent) {
            is BolusIntent.DoseEntered -> draft.value = Draft(Milliunits(intent.milliunits))
            // Only the second confirmation transmits. The first moves the stage
            // to Confirming and nothing leaves the phone.
            BolusIntent.Confirmed -> if (previous.step == Step.Confirming) deliver()
            BolusIntent.Cancelled,
            is BolusIntent.Acknowledged,
            -> Unit
            BolusIntent.ReissueConfirmed -> reissue()
            BolusIntent.ReissueDeclined -> declineReissue()
            BolusIntent.PumpVerifiedByUser -> acknowledgeHazard()
            BolusIntent.RecheckRequested -> pump.requestReconcile()
        }
    }

    private fun deliver() = viewModelScope.launch {
        pump.deliverBolus(Dose(draft.value.milliunits))
    }

    /**
     * Reissues the dose recorded in the journal, not whatever is in the draft.
     * The operator is agreeing to resend one specific command, and the entry
     * field may have moved on since it was sent.
     */
    private fun reissue() = viewModelScope.launch {
        val entry = pump.journal.value.awaitingReissue() ?: return@launch
        pump.reissue(entry.commandId, entry.requested)
    }

    private fun declineReissue() = viewModelScope.launch {
        val entry = pump.journal.value.awaitingReissue() ?: return@launch
        pump.acknowledge(entry.commandId)
    }

    private fun acknowledgeHazard() = viewModelScope.launch {
        val entry = pump.journal.value.indeterminate() ?: return@launch
        pump.acknowledge(entry.commandId)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
