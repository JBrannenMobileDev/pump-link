package dev.pumplink.simulator.host

import dev.pumplink.protocol.DemoKeys
import dev.pumplink.protocol.Fragmenter
import dev.pumplink.protocol.Reassembler
import dev.pumplink.protocol.ReassemblyResult
import dev.pumplink.protocol.Timeouts
import dev.pumplink.simulator.Fault
import dev.pumplink.simulator.PumpCore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * JVM side of the Mac harness. Listens on localhost:17341 and runs PumpCore.
 */
fun main() {
    PumpHost().serve()
}

class PumpHost(
    private val core: PumpCore = PumpCore(DemoKeys.PUMP_ID, DemoKeys.PAIRING_KEY),
) {
    private val reassembler = Reassembler()
    private var mtu: Int = 23

    fun serve() {
        ServerSocket(HarnessFrame.PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            println("pump-host listening on 127.0.0.1:${HarnessFrame.PORT}")
            while (true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    runSession(input, output)
                    core.resetSession()
                    reassembler.reset()
                }
            }
        }
    }

    private fun runSession(input: DataInputStream, output: DataOutputStream) {
        try {
            // STATUS is a plain GATT read with no subscription required
            // (docs/02). Push it before any central can connect so the Mac
            // already has a cached identity when the first read arrives.
            sendStatus(output)
            while (true) {
                val frame = input.readFrame()
                when (frame.type) {
                    HarnessFrame.MTU_CHANGED -> {
                        if (frame.payload.size >= 2) {
                            mtu = ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                        }
                    }
                    HarnessFrame.WRITE_RECEIVED -> {
                        if (frame.payload.isEmpty()) continue
                        val fragment = frame.payload.copyOfRange(1, frame.payload.size)
                        when (val result = reassembler.accept(fragment)) {
                            is ReassemblyResult.Complete -> {
                                if (core.fault == Fault.DELAY_PAST_T_RESP &&
                                    !core.isStatusRequest(result.message)
                                ) {
                                    core.fault = Fault.NONE
                                    Thread.sleep(Timeouts.T_RESP_MS + 200)
                                }
                                val replies = core.handleEncoded(result.message)
                                for (reply in replies) {
                                    for (part in Fragmenter.fragment(reply, mtu)) {
                                        val notify = byteArrayOf(HarnessFrame.CHAR_RSP.toByte()) + part
                                        write(output, HarnessFrame.NOTIFY, notify)
                                    }
                                }
                                // Accept, complete, abort, and eviction all
                                // increment recordEpoch. A value pushed only
                                // at connect goes stale, and a stale epoch
                                // is exactly the reconnect signal docs/02
                                // says a returning controller should see.
                                sendStatus(output)
                            }
                            ReassemblyResult.Incomplete,
                            ReassemblyResult.Discarded,
                            -> Unit
                        }
                    }
                    HarnessFrame.SUBSCRIBED -> {
                        if (frame.payload.firstOrNull()?.toInt() == HarnessFrame.CHAR_STATUS) {
                            sendStatus(output)
                        }
                    }
                    HarnessFrame.FAULT -> {
                        val code = frame.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        core.fault = Fault.fromCode(code) ?: Fault.NONE
                        if (core.fault == Fault.RESET_STORE) {
                            core.resetStore()
                            core.fault = Fault.NONE
                            sendStatus(output)
                        }
                        if (core.fault == Fault.EVICT_ALL) {
                            core.evictAll()
                            core.fault = Fault.NONE
                            sendStatus(output)
                        }
                    }
                    HarnessFrame.SET_RESERVOIR -> {
                        if (frame.payload.size >= 2) {
                            core.reservoirMilliunits =
                                ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                        }
                    }
                    HarnessFrame.SET_BATTERY -> {
                        core.batteryPercent = frame.payload.firstOrNull()?.toInt()?.and(0xFF) ?: core.batteryPercent
                    }
                    HarnessFrame.FORCE_DISCONNECT_UP -> {
                        write(output, HarnessFrame.FORCE_DISCONNECT_DOWN, ByteArray(0))
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            // peer closed
        }
    }

    private fun sendStatus(output: DataOutputStream) {
        val status = core.statusValue().encode()
        write(output, HarnessFrame.NOTIFY, byteArrayOf(HarnessFrame.CHAR_STATUS.toByte()) + status)
    }

    private fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
        val frame = HarnessFrame(type, payload)
        output.write(frame.encode())
        output.flush()
    }

}
