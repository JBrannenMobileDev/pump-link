package dev.pumplink.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SessionCrypto {
    private const val HMAC = "HmacSHA256"
    private val INFO = "pump-link/v1/session".toByteArray(Charsets.US_ASCII)
    private val CONTROLLER_LABEL = "controller".toByteArray(Charsets.US_ASCII)
    private val PUMP_LABEL = "pump".toByteArray(Charsets.US_ASCII)

    fun deriveSessionKey(pairingKey: ByteArray, nonceC: ByteArray, nonceP: ByteArray): ByteArray {
        require(nonceC.size == 16 && nonceP.size == 16) { "nonces are 16 octets" }
        val salt = nonceC + nonceP
        return hkdfSha256(ikm = pairingKey, salt = salt, info = INFO, length = 32)
    }

    fun controllerVerifyMac(sessionKey: ByteArray, nonceC: ByteArray, nonceP: ByteArray): ByteArray =
        hmacSha256(sessionKey, CONTROLLER_LABEL + nonceC + nonceP).copyOf(ProtocolLimits.AUTH_MAC_SIZE)

    fun pumpVerifyMac(sessionKey: ByteArray, nonceC: ByteArray, nonceP: ByteArray): ByteArray =
        hmacSha256(sessionKey, PUMP_LABEL + nonceP + nonceC).copyOf(ProtocolLimits.AUTH_MAC_SIZE)

    fun pduMac(sessionKey: ByteArray, headerAndPayload: ByteArray): ByteArray =
        hmacSha256(sessionKey, headerAndPayload).copyOf(ProtocolLimits.MAC_SIZE)

    fun verifyPduMac(sessionKey: ByteArray, headerAndPayload: ByteArray, mac: ByteArray): Boolean {
        val expected = pduMac(sessionKey, headerAndPayload)
        return constantTimeEquals(expected, mac)
    }

    fun verifyAuthMac(expected: ByteArray, actual: ByteArray): Boolean =
        constantTimeEquals(expected, actual)

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            val input = previous + info + byteArrayOf(counter.toByte())
            previous = hmacSha256(prk, input)
            val take = minOf(previous.size, length - generated)
            previous.copyInto(result, generated, 0, take)
            generated += take
            counter += 1
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
