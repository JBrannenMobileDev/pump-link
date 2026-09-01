package dev.pumplink.simulator

import dev.pumplink.protocol.Fragmenter
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Reassembler
import dev.pumplink.protocol.ReassemblyResult
import dev.pumplink.protocol.Timeouts
import dev.pumplink.protocol.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PumpEndpoint(
    val core: PumpCore,
    private val transport: Transport,
) {
    private val reassembler = Reassembler()

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val fragment = try {
                transport.receive()
            } catch (_: Throwable) {
                break
            }
            when (val result = reassembler.accept(fragment)) {
                is ReassemblyResult.Complete -> dispatch(result.message)
                ReassemblyResult.Incomplete -> Unit
                ReassemblyResult.Discarded -> Unit
            }
        }
    }

    private suspend fun dispatch(encoded: ByteArray) {
        if (core.fault == Fault.DELAY_PAST_T_RESP && !core.isStatusRequest(encoded)) {
            core.fault = Fault.NONE
            delay(Timeouts.T_RESP_MS + 200)
        }
        val replies = core.handleEncoded(encoded)
        for (reply in replies) {
            for (fragment in Fragmenter.fragment(reply, transport.mtu)) {
                transport.send(fragment)
            }
        }
    }

    companion object {
        val MIN_MTU = ProtocolLimits.MIN_ATT_MTU
    }
}
