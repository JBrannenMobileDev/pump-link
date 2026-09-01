package dev.pumplink.protocol

import kotlinx.coroutines.channels.Channel

/**
 * In-memory fragment pipe for Session tests. The test owns both ends.
 */
class TestTransport(
    override var mtu: Int = ProtocolLimits.MIN_ATT_MTU,
) : Transport {
    val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun send(fragment: ByteArray) {
        outbound.send(fragment)
    }

    override suspend fun receive(): ByteArray = inbound.receive()

    override suspend fun close() {
        inbound.close()
        outbound.close()
    }

    suspend fun inject(pdu: Pdu, key: ByteArray? = null) {
        val encoded = PduCodec.encode(pdu, key)
        for (fragment in Fragmenter.fragment(encoded, mtu)) {
            inbound.send(fragment)
        }
    }
}
