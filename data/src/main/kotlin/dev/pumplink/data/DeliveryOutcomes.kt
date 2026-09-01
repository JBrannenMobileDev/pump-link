package dev.pumplink.data

import dev.pumplink.domain.Dose
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.Resolution
import dev.pumplink.protocol.DeliveryRecord
import dev.pumplink.protocol.RecordState

/**
 * Maps a pump [DeliveryRecord] onto a domain [Resolution].
 * `ACCEPTED` and `IN_PROGRESS` are not outcomes: the controller must
 * query until the record settles or [dev.pumplink.protocol.Timeouts.T_RESOLVE_MS]
 * expires.
 */
object DeliveryOutcomes {
    fun resolutionOf(record: DeliveryRecord): Resolution? = when (record.state) {
        RecordState.COMPLETED -> Resolution.Completed(
            Dose(Milliunits(record.deliveredMilliunits)),
        )
        RecordState.ABORTED -> Resolution.Aborted(
            Dose(Milliunits(record.deliveredMilliunits)),
            AbortReasons.fromCode(record.abortReason),
        )
        RecordState.ACCEPTED,
        RecordState.IN_PROGRESS,
        -> null
    }
}
