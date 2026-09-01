package dev.pumplink.protocol

/**
 * Fixed pairing material for the two-device demo. A real program provisions
 * these during pairing, which is out of scope.
 */
object DemoKeys {
    val PAIRING_KEY = ByteArray(32) { 0x11 }
    val PUMP_ID = LogicalDeviceId(ByteArray(16) { 0x01 })
    val CONTROLLER_ID = LogicalDeviceId(ByteArray(16) { 0x02 })
}
