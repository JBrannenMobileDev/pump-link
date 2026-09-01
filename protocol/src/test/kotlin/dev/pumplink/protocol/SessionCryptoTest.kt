package dev.pumplink.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCryptoTest {

    @Test
    fun `controller and pump derive the same session key`() {
        val kp = ByteArray(32) { 4 }
        val nonceC = ByteArray(16) { 1 }
        val nonceP = ByteArray(16) { 2 }
        val left = SessionCrypto.deriveSessionKey(kp, nonceC, nonceP)
        val right = SessionCrypto.deriveSessionKey(kp, nonceC, nonceP)
        assertTrue(left.contentEquals(right))
    }

    @Test
    fun `a different nonce produces a different key`() {
        val kp = ByteArray(32) { 4 }
        val nonceC = ByteArray(16) { 1 }
        val a = SessionCrypto.deriveSessionKey(kp, nonceC, ByteArray(16) { 2 })
        val b = SessionCrypto.deriveSessionKey(kp, nonceC, ByteArray(16) { 3 })
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `verify MACs are directional`() {
        val key = ByteArray(32) { 8 }
        val nonceC = ByteArray(16) { 1 }
        val nonceP = ByteArray(16) { 2 }
        val controller = SessionCrypto.controllerVerifyMac(key, nonceC, nonceP)
        val pump = SessionCrypto.pumpVerifyMac(key, nonceC, nonceP)
        assertFalse(controller.contentEquals(pump))
        assertTrue(SessionCrypto.verifyAuthMac(controller, controller))
        assertFalse(SessionCrypto.verifyAuthMac(controller, pump))
    }
}
