package dev.pumplink.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dev.pumplink.protocol.GattUuids
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Central-only GATT transport. All operations go through one queue because
 * Android permits a single outstanding GATT call (D-09). The queue is released
 * by a callback, a failed initiation, a throw, a 3 s watchdog, or [close] —
 * never left held because a callback never arrived.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : Transport {
    override var mtu: Int = ProtocolLimits.MIN_ATT_MTU

    private var incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var gatt: BluetoothGatt? = null
    private var cmd: BluetoothGattCharacteristic? = null
    private var rsp: BluetoothGattCharacteristic? = null
    private var status: BluetoothGattCharacteristic? = null

    private val _events = MutableStateFlow<GattEvent>(GattEvent.Idle)
    val events: StateFlow<GattEvent> = _events

    private val queue = GattOpQueue(scope, OP_TIMEOUT_MS, ::onStall)

    val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                this@BleTransport.gatt = gatt
                _events.value = GattEvent.Connected
            } else {
                _events.value = GattEvent.Disconnected(status)
            }
            finish()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(UUID.fromString(GattUuids.SERVICE))
            cmd = service?.getCharacteristic(UUID.fromString(GattUuids.CMD))
            rsp = service?.getCharacteristic(UUID.fromString(GattUuids.RSP))
            this@BleTransport.status = service?.getCharacteristic(UUID.fromString(GattUuids.STATUS))
            _events.value = if (service != null) GattEvent.ServicesDiscovered else GattEvent.Failed(status)
            finish()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleTransport.mtu = mtu
                _events.value = GattEvent.MtuSettled(mtu)
            } else {
                Log.w(TAG, "onMtuChanged status=$status")
            }
            finish()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            onCccdWrite(status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid == UUID.fromString(GattUuids.STATUS)) {
                // A failed read and an empty success mean the same thing to
                // the link machine: identity not confirmed. Emit empty bytes
                // so BleController.onStatusBytes retries instead of waiting
                // out the 5 s Subscribed timeout.
                val bytes = if (status == BluetoothGatt.GATT_SUCCESS) value else ByteArray(0)
                _events.value = GattEvent.StatusBytes(bytes)
            }
            finish()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            finish()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.trySend(value)
        }
    }

    fun connect(device: BluetoothDevice) {
        enqueue("connect") {
            val client = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (client != null) {
                gatt = client
                true
            } else {
                false
            }
        }
    }

    fun discover() {
        enqueue("discoverServices") { gatt?.discoverServices() == true }
    }

    fun requestMtu() {
        enqueue("requestMtu") { gatt?.requestMtu(ProtocolLimits.MAX_ATT_MTU) == true }
    }

    fun enableNotifications() {
        enqueue("enableNotifications") {
            val characteristic = rsp ?: return@enqueue false
            val cccd = characteristic.getDescriptor(CCCD) ?: return@enqueue false
            if (gatt?.setCharacteristicNotification(characteristic, true) != true) {
                return@enqueue false
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            gatt?.writeDescriptor(cccd) == true
        }
    }

    fun readStatus() {
        enqueue("readStatus") {
            val target = status ?: return@enqueue false
            gatt?.readCharacteristic(target) == true
        }
    }

    fun disconnect() {
        enqueue("disconnect") {
            val client = gatt ?: return@enqueue false
            client.disconnect()
            true
        }
    }

    override suspend fun send(fragment: ByteArray) {
        val characteristic = cmd ?: error("CMD characteristic missing")
        enqueue("writeCharacteristic") {
            characteristic.value = fragment
            gatt?.writeCharacteristic(characteristic) == true
        }
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    override suspend fun close() {
        queue.reset()
        gatt?.close()
        gatt = null
        cmd = null
        rsp = null
        status = null
        mtu = ProtocolLimits.MIN_ATT_MTU
        // Unblock a receive sitting on the previous connection and drop
        // leftover fragments so they cannot be reassembled into the next
        // session. Seq 0 of a new handshake would otherwise collide with them.
        val stale = incoming
        incoming = Channel(Channel.UNLIMITED)
        stale.close()
    }

    private fun enqueue(name: String, start: () -> Boolean) {
        queue.submit(name) {
            val initiated = try {
                start()
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                Log.w(TAG, "$name threw: ${thrown.message}")
                throw thrown
            }
            if (initiated) {
                Log.d(TAG, "$name initiated")
            } else {
                Log.w(TAG, "$name not initiated")
            }
            initiated
        }
    }

    /**
     * Post [GattOpQueue.complete] off the binder callback. Issuing the next
     * GATT write synchronously from `onMtuChanged` is what made Samsung drop
     * the CCCD ACK — Configuring then sat on "Negotiating MTU" until the
     * 5 s state timeout.
     */
    private fun finish() {
        scope.launch { queue.complete() }
    }

    private fun onCccdWrite(status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "onDescriptorWrite status=0")
            _events.value = GattEvent.CccdConfirmed
        } else {
            Log.w(TAG, "onDescriptorWrite status=$status")
            _events.value = GattEvent.Disconnected(status)
        }
        finish()
    }

    private fun onStall(name: String) {
        Log.w(TAG, "GATT op stalled: $name")
        _events.value = GattEvent.Disconnected(BluetoothGatt.GATT_FAILURE)
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleTransport"
        private const val OP_TIMEOUT_MS = 3_000L
    }
}

sealed interface GattEvent {
    data object Idle : GattEvent
    data object Connected : GattEvent
    data class Disconnected(val status: Int) : GattEvent
    data object ServicesDiscovered : GattEvent
    data class MtuSettled(val mtu: Int) : GattEvent
    data object CccdConfirmed : GattEvent
    data class StatusBytes(val value: ByteArray) : GattEvent
    data class Failed(val status: Int) : GattEvent
}
