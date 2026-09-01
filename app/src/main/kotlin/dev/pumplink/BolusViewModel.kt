package dev.pumplink

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pumplink.data.BleController
import dev.pumplink.data.FileJournal
import dev.pumplink.data.PersistentCommandIds
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.Draft
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
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
import java.io.File

private data class LinkSnapshot(
    val status: LinkStatus,
    val progress: LinkProgress,
    val pump: PumpSummary?,
    val resolving: DomainCommandId?,
    val vitalsStale: Boolean,
)

class BolusViewModel(application: Application) : AndroidViewModel(application) {
    private val journal = FileJournal(File(application.filesDir, "journal.log"))
    private val ids = PersistentCommandIds(File(application.filesDir, "command-id.txt"))
    private val controller = BleController.demo(
        context = application,
        journal = journal,
        ids = ids,
        scope = viewModelScope,
    )
    private val draft = MutableStateFlow(Draft(Milliunits(SafetyLimits.INCREMENT_MILLIUNITS * 20)))
    private val stage = MutableStateFlow(Stage())

    val pairedIdentity: String get() = controller.pairedIdentity

    private val link = combine(
        controller.linkStatus,
        controller.linkProgress,
        controller.pump,
        controller.resolving,
        controller.vitalsStale,
    ) { status, progress, pump, resolving, vitalsStale ->
        LinkSnapshot(status, progress, pump, resolving, vitalsStale)
    }

    val uiState: StateFlow<BolusScreenState> = combine(
        link,
        controller.revision,
        draft,
        stage,
    ) { snapshot, _, currentDraft, currentStage ->
        screenState(
            link = snapshot.status,
            progress = snapshot.progress,
            journal = controller.journalSnapshot,
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
            journal = journal.snapshot(),
            pump = null,
            draft = draft.value,
            nowMillis = System.currentTimeMillis(),
        ),
    )

    fun start() {
        controller.start()
    }

    fun stop() {
        controller.stop()
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
            BolusIntent.RecheckRequested -> controller.requestReconcile()
        }
    }

    private fun deliver() = viewModelScope.launch {
        controller.deliverBolus(draft.value.milliunits.value)
    }

    /**
     * Reissues the dose recorded in the journal, not whatever is in the draft.
     * The operator is agreeing to resend one specific command, and the entry
     * field may have moved on since it was sent.
     */
    private fun reissue() = viewModelScope.launch {
        val entry = controller.journalSnapshot.awaitingReissue() ?: return@launch
        controller.reissue(entry.commandId, entry.requested.milliunits.value)
    }

    private fun declineReissue() = viewModelScope.launch {
        val entry = controller.journalSnapshot.awaitingReissue() ?: return@launch
        controller.acknowledge(entry.commandId)
    }

    private fun acknowledgeHazard() = viewModelScope.launch {
        val entry = controller.journalSnapshot.indeterminate() ?: return@launch
        controller.acknowledge(entry.commandId)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
