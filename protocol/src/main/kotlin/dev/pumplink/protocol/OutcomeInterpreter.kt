package dev.pumplink.protocol

/**
 * The pump reports absence as NEVER_SEEN or EVICTED. STORE_REPLACED is the
 * controller's reading of NEVER_SEEN against a journaled storeInstanceId —
 * the pump after a reset has no memory of the previous store.
 */
object OutcomeInterpreter {
    fun interpret(wire: OutcomePayload, journaledStore: StoreInstanceId): CommandOutcome {
        if (wire.outcome == CommandOutcome.NEVER_SEEN &&
            journaledStore != StoreInstanceId.UNKNOWN &&
            wire.storeInstanceId != journaledStore
        ) {
            return CommandOutcome.STORE_REPLACED
        }
        return wire.outcome
    }
}
