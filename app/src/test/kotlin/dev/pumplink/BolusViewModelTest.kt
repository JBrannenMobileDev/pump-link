package dev.pumplink

import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalEntry
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.Resolution
import dev.pumplink.presentation.BolusIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BolusViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val pump = FakePumpRepository()
    private lateinit var viewModel: BolusViewModel

    @BeforeEach
    fun installMain() {
        Dispatchers.setMain(dispatcher)
        viewModel = BolusViewModel(pump)
    }

    @AfterEach
    fun clearMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstConfirmedDoesNotTransmit() = runTest(dispatcher) {
        viewModel.onIntent(BolusIntent.Confirmed)
        advanceUntilIdle()
        assertTrue(pump.delivered.isEmpty())
    }

    @Test
    fun secondConfirmedDeliversTheDraft() = runTest(dispatcher) {
        viewModel.onIntent(BolusIntent.Confirmed)
        viewModel.onIntent(BolusIntent.Confirmed)
        advanceUntilIdle()
        assertEquals(listOf(Dose(Milliunits(1_000))), pump.delivered)
    }

    @Test
    fun reissueSendsTheJournaledDoseNotTheDraft() = runTest(dispatcher) {
        val command = DomainCommandId(7u)
        val journaled = Dose(Milliunits(2_000))
        pump.journal.value = JournalSnapshot(
            listOf(
                JournalEntry(
                    commandId = command,
                    storeInstanceId = DomainStoreId(1uL),
                    requested = journaled,
                    state = JournalState.Resolved,
                    sentAtMillis = 0L,
                    resolution = Resolution.NeverSeen,
                ),
            ),
        )
        viewModel.onIntent(BolusIntent.DoseEntered(500))
        viewModel.onIntent(BolusIntent.ReissueConfirmed)
        advanceUntilIdle()
        assertEquals(listOf(command to journaled), pump.reissued)
    }

    @Test
    fun pumpVerifiedAcknowledgesTheIndeterminateEntry() = runTest(dispatcher) {
        val command = DomainCommandId(9u)
        pump.journal.value = JournalSnapshot(
            listOf(
                JournalEntry(
                    commandId = command,
                    storeInstanceId = DomainStoreId(1uL),
                    requested = Dose(Milliunits(1_000)),
                    state = JournalState.Indeterminate,
                    sentAtMillis = 0L,
                    resolution = Resolution.Indeterminate,
                ),
            ),
        )
        viewModel.onIntent(BolusIntent.PumpVerifiedByUser)
        advanceUntilIdle()
        assertEquals(listOf(command), pump.acknowledged)
    }
}
