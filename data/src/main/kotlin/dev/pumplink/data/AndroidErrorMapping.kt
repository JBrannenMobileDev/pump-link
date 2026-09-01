package dev.pumplink.data

import dev.pumplink.protocol.link.ErrorClass

/**
 * The only Android-specific error table in the codebase.
 * See docs/05-parity-contract.md D-06.
 */
object AndroidErrorMapping {
    fun map(status: Int): ErrorClass = when (status) {
        8, 62, 34 -> ErrorClass.TransientLink
        19, 22 -> ErrorClass.PeerInitiated
        133 -> ErrorClass.StackFault
        else -> ErrorClass.StackFault
    }
}
