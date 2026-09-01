package dev.pumplink.data

import dev.pumplink.domain.AbortReason
import dev.pumplink.domain.Resolution
import dev.pumplink.protocol.AbortCodes
import dev.pumplink.protocol.CommandId
import dev.pumplink.protocol.DeliveryRecord
import dev.pumplink.protocol.RecordState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeliveryOutcomesTest {

    @Test
    fun completedIsCompleted() {
        val resolution = DeliveryOutcomes.resolutionOf(record(RecordState.COMPLETED, delivered = 1_000))
        val completed = assertInstanceOf(Resolution.Completed::class.java, resolution)
        assertEquals(1_000, completed.delivered.milliunits.value)
    }

    @Test
    fun abortedIsNeverCompleted() {
        val resolution = DeliveryOutcomes.resolutionOf(
            record(RecordState.ABORTED, delivered = 0, abort = AbortCodes.EXCEEDS_MAX),
        )
        val aborted = assertInstanceOf(Resolution.Aborted::class.java, resolution)
        assertEquals(AbortReason.PumpRejected, aborted.reason)
        assertEquals(0, aborted.delivered.milliunits.value)
    }

    @Test
    fun abortCodesMapOntoDomainReasons() {
        assertEquals(AbortReason.UserCancelled, AbortReasons.fromCode(AbortCodes.USER_CANCELLED))
        assertEquals(AbortReason.Reservoir, AbortReasons.fromCode(AbortCodes.INSUFFICIENT_RESERVOIR))
        assertEquals(AbortReason.Occlusion, AbortReasons.fromCode(AbortCodes.OCCLUSION))
        assertEquals(AbortReason.PumpRejected, AbortReasons.fromCode(AbortCodes.INVALID_DOSE))
        assertEquals(AbortReason.PumpRejected, AbortReasons.fromCode(AbortCodes.EXCEEDS_MAX))
        assertEquals(AbortReason.PumpRejected, AbortReasons.fromCode(AbortCodes.DELIVERY_ACTIVE))
    }

    @Test
    fun acceptedAndInProgressAreNotOutcomes() {
        assertNull(DeliveryOutcomes.resolutionOf(record(RecordState.ACCEPTED)))
        assertNull(DeliveryOutcomes.resolutionOf(record(RecordState.IN_PROGRESS)))
    }

    private fun record(
        state: RecordState,
        delivered: Int = 0,
        abort: Int = 0,
    ) = DeliveryRecord(
        commandId = CommandId(1u),
        state = state,
        requestedMilliunits = 1_000,
        deliveredMilliunits = delivered,
        abortReason = abort,
        startedAt = 1u,
        endedAt = 2u,
    )
}
