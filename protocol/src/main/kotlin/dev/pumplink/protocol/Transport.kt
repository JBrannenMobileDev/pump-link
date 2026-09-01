package dev.pumplink.protocol

/**
 * Byte-oriented fragment transport. One call is one GATT write or notify.
 * Implementations include the in-process loopback and the harness socket.
 */
interface Transport {
    var mtu: Int
    suspend fun send(fragment: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun close()
}

class TransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
