package dev.pumplink.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.pumplink.domain.CommandJournal
import dev.pumplink.domain.DeliverBolusUseCase
import dev.pumplink.domain.DomainCommandId
import dev.pumplink.domain.DomainStoreId
import dev.pumplink.domain.Dose
import dev.pumplink.domain.JournalSnapshot
import dev.pumplink.domain.JournalState
import dev.pumplink.domain.LinkProgress
import dev.pumplink.domain.LinkStatus
import dev.pumplink.domain.Milliunits
import dev.pumplink.domain.PumpRepository
import dev.pumplink.domain.PumpSummary
import dev.pumplink.domain.Resolution
import dev.pumplink.domain.ResolveInFlightCommandUseCase
import dev.pumplink.protocol.DemoKeys
import dev.pumplink.protocol.BolusRequest
import dev.pumplink.protocol.CommandId
import dev.pumplink.protocol.CommandOutcome
import dev.pumplink.protocol.DeliveryRecord
import dev.pumplink.protocol.GattUuids
import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.Opcode
import dev.pumplink.protocol.OutcomeInterpreter
import dev.pumplink.protocol.OutcomePayload
import dev.pumplink.protocol.Pdu
import dev.pumplink.protocol.ProtocolLimits
import dev.pumplink.protocol.Session
import dev.pumplink.protocol.StatusValue
import dev.pumplink.protocol.StoreInstanceId
import dev.pumplink.protocol.link.ErrorClass
import dev.pumplink.protocol.link.LinkEvent
import dev.pumplink.protocol.link.LinkEffect
import dev.pumplink.protocol.link.LinkReducer
import dev.pumplink.protocol.link.LinkState
import dev.pumplink.protocol.Timeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class BleController(
    private val context: Context,
    private val commandLog: CommandJournal,
    private val pairingKey: ByteArray,
    private val paired: LogicalDeviceId,
    private val controllerId: LogicalDeviceId,
    private val ids: PersistentCommandIds,
    private val scope: CoroutineScope,
) : PumpRepository {
    private val transport = BleTransport(context, scope)
    private val session = Session(transport, pairingKey, controllerId, paired)
    private val deliver = DeliverBolusUseCase(commandLog, ids) { System.currentTimeMillis() }
    private val resolve = ResolveInFlightCommandUseCase(commandLog)

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    private val _linkStatus = MutableStateFlow<LinkStatus>(LinkStatus.Idle)
    override val linkStatus: StateFlow<LinkStatus> = _linkStatus

    private val _linkProgress = MutableStateFlow(LinkProgress())
    override val linkProgress: StateFlow<LinkProgress> = _linkProgress

    /** Set while an outcome query is outstanding, so the UI can say it is asking. */
    private val _resolving = MutableStateFlow<DomainCommandId?>(null)
    override val resolving: StateFlow<DomainCommandId?> = _resolving

    private val _pump = MutableStateFlow<PumpSummary?>(null)
    override val pump: StateFlow<PumpSummary?> = _pump

    private val _vitalsStale = MutableStateFlow(false)
    override val vitalsStale: StateFlow<Boolean> = _vitalsStale

    /** Hex of the paired logical identity, for the link screen. */
    override val pairedIdentity: String get() = paired.toString()

    /** Last transport failure message, for diagnostics on the link screen. */
    var lastTransportError: String? = null
        private set

    private var statusReadAttempts = 0

    private val _journal = MutableStateFlow(commandLog.snapshot())
    override val journal: StateFlow<JournalSnapshot> = _journal

    private fun bump() {
        _journal.value = commandLog.snapshot()
    }

    private var timeoutJob: Job? = null
    private var pollJob: Job? = null
    private var pollFailures = 0

    /**
     * One worker owns [session]. Two concurrent [Session.sendAndAwait] calls
     * interleave fragments into one reassembler; D-09's GATT queue does not
     * prevent that. A poll that finds this queue non-empty is dropped.
     */
    private val transactions = ConcurrentLinkedQueue<suspend () -> Unit>()
    private val queued = AtomicInteger(0)
    private val running = AtomicInteger(0)

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    private fun adapter(): BluetoothAdapter? = bluetoothManager.adapter

    private fun isAdapterEnabled(): Boolean = adapter()?.isEnabled == true

    private fun scanner() = adapter()?.bluetoothLeScanner

    /**
     * Samsung (and others) often skip [BluetoothGattCallback.onConnectionStateChange]
     * when the user powers the adapter down. The adapter broadcast is the
     * signal that is actually delivered, and Ready must not survive it.
     */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF,
                -> onAdapterOff()
                BluetoothAdapter.STATE_ON -> onAdapterOn()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertised = result.scanRecord
                ?.getManufacturerSpecificData(0xFFFF)
                ?.takeIf { it.size == 16 }
                ?.let { LogicalDeviceId(it) }
            dispatch(LinkEvent.ScanMatch(advertised))
            pendingDevice = result.device
        }
    }

    private var pendingDevice: android.bluetooth.BluetoothDevice? = null

    init {
        registerAdapterReceiver()
        if (!isAdapterEnabled()) {
            publishProgress()
        }
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    GattEvent.Idle -> Unit
                    GattEvent.Connected -> dispatch(LinkEvent.Connected)
                    is GattEvent.Disconnected -> dispatch(
                        LinkEvent.Disconnected(AndroidErrorMapping.map(event.status)),
                    )
                    GattEvent.ServicesDiscovered -> dispatch(LinkEvent.ServicesDiscovered)
                    is GattEvent.MtuSettled -> dispatch(LinkEvent.MtuSettled(event.mtu))
                    GattEvent.CccdConfirmed -> dispatch(LinkEvent.CccdConfirmed)
                    is GattEvent.StatusBytes -> onStatusBytes(event.value)
                    is GattEvent.Failed -> dispatch(
                        LinkEvent.DiscoveryFailed(AndroidErrorMapping.map(event.status)),
                    )
                }
            }
        }
    }

    /**
     * A peripheral may answer the STATUS read before it has a value to give —
     * ours does, because the value arrives over the harness socket a moment
     * after the central subscribes. An unreadable STATUS means identity is not
     * yet confirmed, so ask again and let the `Subscribed` timeout be the
     * backstop. It must never take the process down: this characteristic is
     * readable without a session, which makes it the one piece of pump data an
     * unauthenticated peer can shape.
     */
    private fun onStatusBytes(bytes: ByteArray) {
        if (bytes.size != ProtocolLimits.STATUS_VALUE_SIZE) {
            statusReadAttempts += 1
            if (statusReadAttempts <= STATUS_READ_ATTEMPTS) {
                scope.launch {
                    delay(STATUS_RETRY_MS)
                    transport.readStatus()
                }
            }
            return
        }
        statusReadAttempts = 0
        val value = StatusValue.decode(bytes)
        dispatch(
            LinkEvent.StatusRead(
                value.logicalDeviceId,
                value.protocolVersion,
                value.recordEpoch,
            ),
        )
    }

    override fun start() {
        statusReadAttempts = 0
        if (!isAdapterEnabled()) {
            lastTransportError = "bluetooth off"
            publishProgress()
            return
        }
        dispatch(LinkEvent.StartRequested(paired))
    }

    override fun stop() {
        dispatch(LinkEvent.StopRequested)
    }

    override suspend fun deliverBolus(dose: Dose) {
        // Journal-before-transmit is for an ambiguous radio, not a radio we
        // already know is off. Preparing here would pin an in-flight command
        // that the pump never had a chance to see.
        if (!isAdapterEnabled()) {
            lastTransportError = "bluetooth off"
            onAdapterOff()
            return
        }
        if (_linkStatus.value != LinkStatus.Ready) {
            lastTransportError = "link not ready"
            bump()
            return
        }
        val store = _pump.value?.storeInstanceId ?: DomainStoreId(0uL)
        val entry = deliver.prepare(dose, store)
        transact { transmitBolus(entry.commandId, dose.milliunits.value, store) }
    }

    /**
     * Resends a command the pump reports it never saw, under the **same**
     * CommandId. Allocating a fresh one would look like a new command to the
     * pump and could deliver a second dose, which is the whole reason the
     * CommandId is client-generated and journaled before transmission.
     */
    override suspend fun reissue(commandId: DomainCommandId, dose: Dose) {
        val store = _pump.value?.storeInstanceId ?: DomainStoreId(0uL)
        resolve.resolve(commandId, Resolution.InFlight)
        bump()
        transact { transmitBolus(commandId, dose.milliunits.value, store) }
    }

    /** Records that a human reconciled this command against the pump. */
    override suspend fun acknowledge(commandId: DomainCommandId) {
        resolve.acknowledge(commandId)
        dispatch(LinkEvent.UserVerifiedAtPump)
        bump()
    }

    /** Re-run reconciliation from [LinkStatus.Suspended]. */
    override fun requestReconcile() {
        dispatch(LinkEvent.ReconcileRequested)
    }

    private suspend fun transmitBolus(
        commandId: DomainCommandId,
        milliunits: Int,
        store: DomainStoreId,
    ) {
        try {
            val record = session.sendAndAwait(
                Pdu.request(
                    Opcode.BOLUS_REQ,
                    seq = session.nextSeq(),
                    commandId = CommandId(commandId.value),
                    payload = BolusRequest(milliunits, 60).encode(),
                ),
                Opcode.BOLUS_RSP,
            )
            val decoded = DeliveryRecord.decode(record.payload)
            val settled = DeliveryOutcomes.resolutionOf(decoded)
            if (settled != null) {
                resolve.resolve(commandId, settled)
            } else {
                awaitSettlement(commandId, store)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            // The retry budget is gone and we do not know what the pump did.
            // Never resend a delivery command on an ambiguous response: ask
            // about this CommandId and let the answer decide. If the query also
            // fails the entry stays in flight and Reconciling retries it on the
            // next link.
            resolveAfterFailure(commandId, store, thrown)
        }
        bump()
    }

    private suspend fun resolveAfterFailure(
        commandId: DomainCommandId,
        store: DomainStoreId,
        cause: Throwable,
    ) {
        lastTransportError = cause.message
        resolve.markResolving(commandId)
        _resolving.value = commandId
        bump()
        try {
            query(commandId, store)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            // The query failed too. The entry stays in flight on purpose:
            // Reconciling will ask again on the next link rather than this
            // code guessing an outcome.
            lastTransportError = thrown.message
        } finally {
            _resolving.value = null
        }
    }

    /**
     * The pump accepted but has not finished. Ask until the record settles
     * or [Timeouts.T_RESOLVE_MS] expires; then the outcome is Indeterminate.
     */
    private suspend fun awaitSettlement(commandId: DomainCommandId, store: DomainStoreId) {
        resolve.markResolving(commandId)
        _resolving.value = commandId
        bump()
        try {
            val deadline = System.currentTimeMillis() + Timeouts.T_RESOLVE_MS
            while (System.currentTimeMillis() < deadline) {
                delay(SETTLE_QUERY_MS)
                query(commandId, store)
                if (settled(commandId)) return
            }
            resolve.resolve(commandId, Resolution.Indeterminate)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            lastTransportError = thrown.message
        } finally {
            _resolving.value = null
        }
    }

    private fun settled(commandId: DomainCommandId): Boolean {
        val latest = commandLog.snapshot().latest(commandId) ?: return true
        return when (latest.state) {
            JournalState.Resolved,
            JournalState.Indeterminate,
            JournalState.Acknowledged,
            -> true
            JournalState.Pending,
            JournalState.InFlight,
            JournalState.Resolving,
            -> false
        }
    }

    suspend fun query(commandId: DomainCommandId, store: DomainStoreId) {
        val rsp = session.sendAndAwait(
            Pdu.request(
                Opcode.QUERY_COMMAND_OUTCOME_REQ,
                seq = session.nextSeq(),
                commandId = CommandId(commandId.value),
            ),
            Opcode.QUERY_COMMAND_OUTCOME_RSP,
        )
        val payload = OutcomePayload.decode(rsp.payload)
        val outcome = OutcomeInterpreter.interpret(payload, StoreInstanceId(store.value))
        val resolution = when (outcome) {
            CommandOutcome.NEVER_SEEN -> Resolution.NeverSeen
            CommandOutcome.ACCEPTED, CommandOutcome.IN_PROGRESS -> Resolution.InFlight
            CommandOutcome.COMPLETED -> Resolution.Completed(
                Dose(Milliunits(payload.record.deliveredMilliunits)),
            )
            CommandOutcome.ABORTED -> Resolution.Aborted(
                Dose(Milliunits(payload.record.deliveredMilliunits)),
                AbortReasons.fromCode(payload.record.abortReason),
            )
            CommandOutcome.EVICTED, CommandOutcome.STORE_REPLACED -> Resolution.Indeterminate
        }
        resolve.resolve(commandId, resolution)
        bump()
    }

    companion object {
        private const val STATUS_READ_ATTEMPTS = 5
        private const val STATUS_RETRY_MS = 300L
        private const val SETTLE_QUERY_MS = 500L

        fun demo(
            context: Context,
            journal: CommandJournal,
            ids: PersistentCommandIds,
            scope: CoroutineScope,
        ): BleController = BleController(
            context = context,
            commandLog = journal,
            pairingKey = DemoKeys.PAIRING_KEY,
            paired = DemoKeys.PUMP_ID,
            controllerId = DemoKeys.CONTROLLER_ID,
            ids = ids,
            scope = scope,
        )
    }

    private fun registerAdapterReceiver() {
        ContextCompat.registerReceiver(
            context,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun onAdapterOff() {
        lastTransportError = "bluetooth off"
        val current = _link.value
        val linked = current !is LinkState.Idle &&
            current !is LinkState.Failed &&
            current !is LinkState.Unpaired
        if (linked) {
            dispatch(LinkEvent.Disconnected(ErrorClass.TransientLink))
        } else {
            publishProgress()
        }
    }

    private fun onAdapterOn() {
        lastTransportError = null
        when (_link.value) {
            is LinkState.Recovering,
            is LinkState.Scanning,
            -> dispatch(LinkEvent.StartRequested(paired))
            else -> publishProgress()
        }
    }

    private fun publishProgress(state: LinkState = _link.value) {
        _linkProgress.value = LinkStatusMapper.progress(state).copy(radioEnabled = isAdapterEnabled())
    }

    private fun dispatch(event: LinkEvent) {
        val previous = _link.value
        val transition = LinkReducer.reduce(previous, event)
        _link.value = transition.state
        _linkStatus.value = LinkStatusMapper.map(transition.state)
        publishProgress(transition.state)
        when (transition.state) {
            is LinkState.Ready -> if (previous !is LinkState.Ready) {
                startPoll()
                enqueueInitialStatus()
            }
            is LinkState.Suspended -> if (pollJob == null) startPoll()
            is LinkState.Reconciling,
            is LinkState.Authenticating,
            -> Unit
            else -> stopPoll()
        }
        transition.effects.forEach { apply(it) }
    }

    private fun apply(effect: LinkEffect) {
        when (effect) {
            is LinkEffect.StartScan -> startScan()
            LinkEffect.StopScan -> scanner()?.stopScan(scanCallback)
            LinkEffect.Connect -> pendingDevice?.let { transport.connect(it) }
            LinkEffect.Disconnect -> transport.disconnect()
            LinkEffect.CreateBond -> {
                val device = pendingDevice
                if (device != null && device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    dispatch(LinkEvent.Bonded)
                } else {
                    device?.createBond()
                }
            }
            LinkEffect.DiscoverServices -> transport.discover()
            LinkEffect.RequestMtu -> transport.requestMtu()
            LinkEffect.EnableNotifications -> transport.enableNotifications()
            LinkEffect.ReadStatus -> transport.readStatus()
            LinkEffect.BeginAuth -> scope.launch(Dispatchers.IO) {
                // An authentication failure is a link state, not a crash. The
                // handshake verifies a MAC and a peer identity, and both are
                // attacker-influenced inputs. AuthSucceeded is dispatched after
                // the transaction closes so refreshStatus cannot charge its
                // retry budget against T_AUTH or T_RESOLVE.
                try {
                    transact { session.authenticateAsController() }
                    dispatch(LinkEvent.AuthSucceeded)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                    lastTransportError = thrown.message
                    dispatch(
                        LinkEvent.AuthFailed(
                            reason = thrown.message ?: "authentication failed",
                            unpaired = false,
                        ),
                    )
                }
            }
            LinkEffect.BeginReconcile -> scope.launch(Dispatchers.IO) {
                val open = commandLog.snapshot().inFlight()
                // A query that fails leaves its command unresolved on purpose.
                // Reconcile still finishes and reports the count, because
                // "some commands are still unresolved" is a state the link
                // machine already knows how to refuse dosing from.
                transact {
                    open.forEach { entry ->
                        try {
                            query(entry.commandId, entry.storeInstanceId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                            lastTransportError = thrown.message
                        }
                    }
                    val unresolved = commandLog.snapshot().inFlight().size +
                        if (commandLog.snapshot().hasIndeterminate()) 1 else 0
                    dispatch(LinkEvent.ReconcileDone(unresolved))
                }
            }
            LinkEffect.ResetSession -> session.reset()
            LinkEffect.ReleaseGatt -> scope.launch { transport.close() }
            is LinkEffect.ArmTimeout -> {
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(effect.millis)
                    dispatch(LinkEvent.Timeout(effect.state))
                }
            }
            is LinkEffect.ArmBackoff -> {
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(effect.millis)
                    dispatch(LinkEvent.Timeout(_link.value))
                }
            }
            is LinkEffect.SurfaceFailed,
            is LinkEffect.SurfaceUnpaired,
            -> Unit
        }
    }

    private fun startScan() {
        if (!isAdapterEnabled()) {
            lastTransportError = "bluetooth off"
            publishProgress()
            return
        }
        val leScanner = scanner() ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(GattUuids.SERVICE)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        leScanner.startScan(listOf(filter), settings, scanCallback)
    }

    private suspend fun refreshStatus(attempts: Int = Timeouts.RETRY_ATTEMPTS + 1) {
        val rsp = session.sendAndAwait(
            Pdu.request(Opcode.GET_STATUS_REQ, seq = session.nextSeq()),
            Opcode.GET_STATUS_RSP,
            attempts = attempts,
        )
        val payload = dev.pumplink.protocol.GetStatusPayload.decode(rsp.payload)
        _pump.value = PumpSummary(
            reservoirMilliunits = payload.reservoirMilliunits,
            batteryPercent = payload.batteryPercent,
            deliveryActive = payload.deliveryActive,
            storeInstanceId = DomainStoreId(payload.storeInstanceId.value),
        )
    }

    private fun transactionIdle(): Boolean = queued.get() == 0 && running.get() == 0

    private fun enqueue(block: suspend () -> Unit) {
        queued.incrementAndGet()
        transactions.add(block)
        pumpTransactions()
    }

    private suspend fun transact(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        enqueue {
            try {
                block()
                done.complete(Unit)
            } catch (cancellation: CancellationException) {
                done.completeExceptionally(cancellation)
                throw cancellation
            } catch (thrown: Throwable) {
                done.completeExceptionally(thrown)
            }
        }
        done.await()
    }

    private fun pumpTransactions() {
        if (!running.compareAndSet(0, 1)) return
        val next = transactions.poll()
        if (next == null) {
            running.set(0)
            return
        }
        queued.decrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                next()
            } finally {
                running.set(0)
                pumpTransactions()
            }
        }
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(Timeouts.T_POLL_MS)
                tickPoll()
            }
        }
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun enqueueInitialStatus() {
        enqueue {
            try {
                refreshStatus()
                _vitalsStale.value = false
                pollFailures = 0
                bump()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                lastTransportError = thrown.message
                _vitalsStale.value = true
                bump()
            }
        }
    }

    private fun tickPoll() {
        if (!isAdapterEnabled()) return
        if (!transactionIdle()) return
        when (_link.value) {
            is LinkState.Ready -> enqueueStatusPoll()
            is LinkState.Suspended -> dispatch(LinkEvent.ReconcileRequested)
            is LinkState.Reconciling -> Unit
            else -> Unit
        }
    }

    private fun enqueueStatusPoll() {
        enqueue {
            try {
                refreshStatus(attempts = 1)
                pollFailures = 0
                _vitalsStale.value = false
                bump()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                lastTransportError = thrown.message
                pollFailures += 1
                _vitalsStale.value = true
                bump()
                if (pollFailures >= Timeouts.POLL_FAILURE_LIMIT) {
                    dispatch(LinkEvent.Disconnected(ErrorClass.TransientLink))
                }
            }
        }
    }
}
