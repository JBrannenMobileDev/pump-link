package dev.pumplink.simulator.host

import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * One direction of the harness socket, used as a Transport for CMD fragments
 * inbound and RSP/STATUS fragments outbound. Not a product protocol.
 */
class SocketTransport(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    override var mtu: Int = ProtocolLimits.MIN_ATT_MTU,
) : Transport {

    override suspend fun send(fragment: ByteArray) {
        val frame = HarnessFrame(HarnessFrame.NOTIFY, byteArrayOf(HarnessFrame.CHAR_RSP.toByte()) + fragment)
        withContext(Dispatchers.IO) {
            synchronized(output) {
                output.write(frame.encode())
                output.flush()
            }
        }
    }

    override suspend fun receive(): ByteArray =
        error("SocketTransport is write-only for notifies; reads go through PumpHost")

    override suspend fun close() {
        withContext(Dispatchers.IO) { input.close(); output.close() }
    }
}

fun Socket.harnessStreams(): Pair<DataInputStream, DataOutputStream> =
    DataInputStream(getInputStream()) to DataOutputStream(getOutputStream())

fun DataInputStream.readFrame(): HarnessFrame {
    val length = readUnsignedShort()
    require(length in 1..HarnessFrame.MAX) { "bad harness length $length" }
    val type = readUnsignedByte()
    val payload = ByteArray(length - 1)
    readFully(payload)
    return HarnessFrame(type, payload)
}
