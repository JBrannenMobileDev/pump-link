package dev.pumplink.simulator

import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Transport
import kotlinx.coroutines.channels.Channel

class LoopbackTransport(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    override var mtu: Int = ProtocolLimits.MIN_ATT_MTU,
) : Transport {

    override suspend fun send(fragment: ByteArray) {
        outgoing.send(fragment)
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    override suspend fun close() {
        incoming.close()
        outgoing.close()
    }

    companion object {
        fun pair(mtu: Int = ProtocolLimits.MIN_ATT_MTU): Pair<LoopbackTransport, LoopbackTransport> {
            val toPump = Channel<ByteArray>(Channel.UNLIMITED)
            val toController = Channel<ByteArray>(Channel.UNLIMITED)
            return LoopbackTransport(toController, toPump, mtu) to LoopbackTransport(toPump, toController, mtu)
        }
    }
}
