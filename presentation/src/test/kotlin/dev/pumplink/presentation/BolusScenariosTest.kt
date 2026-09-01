package dev.pumplink.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BolusScenariosTest {

    @Test
    fun `every scenario projects to the declared state`() {
        BolusScenarios.all.forEach { scenario ->
            val projected = scenario.screen().bolus
            assertEquals(
                scenario.expected,
                BolusScenarios.classify(projected),
                "${scenario.name} projected to ${projected::class.simpleName}",
            )
        }
    }

    @Test
    fun `every BolusUiState subclass has a scenario`() {
        val covered = BolusScenarios.all.map { it.expected }.toSet()
        val required = setOf(
            BolusUiState.Entering::class,
            BolusUiState.Confirming::class,
            BolusUiState.Delivering::class,
            BolusUiState.Delivered::class,
            BolusUiState.PartiallyDelivered::class,
            BolusUiState.AwaitingReissue::class,
            BolusUiState.Resolving::class,
            BolusUiState.Blocked::class,
            BolusUiState.Indeterminate::class,
            BolusUiState.DosingDisabled::class,
        )
        assertEquals(required, covered)
        // classify() is the compile-time half of this assertion: a new
        // subclass that is missing from that when will not compile.
        assertTrue(required.isNotEmpty())
    }

    @Test
    fun `a recovered delivery is labelled recovered`() {
        val recovered = BolusScenarios.all.first { it.name.contains("recovered") }
        val state = recovered.screen().bolus
        assertTrue(state is BolusUiState.Delivered && state.recovered)
        assertTrue(recovered.screen().history.first().recovered)
    }
}
