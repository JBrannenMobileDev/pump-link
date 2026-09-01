import CoreBluetooth
import Foundation

struct LogLine: Identifiable {
    enum Kind {
        case inbound
        case outbound
        case link
        case fault
    }

    let id = UUID()
    let at: Date
    let kind: Kind
    let text: String
}

/// Hosts the PumpLink GATT service. Each delegate method names its Android
/// BluetoothGattServer counterpart so the mapping is readable from the code.
final class PeripheralController: NSObject, ObservableObject, CBPeripheralManagerDelegate, @unchecked Sendable {
    @Published var status: String = "starting"
    @Published var mtu: Int = 23
    @Published var subscribed: Bool = false
    @Published var statusSubscribed: Bool = false
    @Published var harnessConnected: Bool = false
    @Published var armedFault: UInt8 = 0x00
    @Published var reservoirUnits: Double = 300
    @Published var batteryPercent: Double = 80
    @Published var centralName: String = "—"
    @Published var log: [LogLine] = []

    private var manager: CBPeripheralManager!
    private let harness = HarnessClient()
    private var pendingNotifications: [(target: UInt8, payload: Data)] = []
    private var cachedStatus: Data?
    private var watchdog: Timer?

    /// True only while a disconnect fault is deliberately holding the radio
    /// down, so the watchdog does not undo the injected fault.
    private var suppressAdvertising = false

    private lazy var cmd = CBMutableCharacteristic(
        type: CBUUID(string: GattUuids.cmd),
        properties: [.write],
        value: nil,
        permissions: [.writeable]
    )
    private lazy var rsp = CBMutableCharacteristic(
        type: CBUUID(string: GattUuids.rsp),
        properties: [.notify],
        value: nil,
        permissions: [.readable]
    )
    private lazy var statusChar = CBMutableCharacteristic(
        type: CBUUID(string: GattUuids.status),
        properties: [.read, .notify],
        value: nil,
        permissions: [.readable]
    )

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: nil)
        harness.onNotify = { [weak self] characteristic, payload in
            DispatchQueue.main.async { self?.notify(characteristic: characteristic, payload: payload) }
        }
        harness.onForceDisconnect = { [weak self] in
            DispatchQueue.main.async {
                self?.holdDownRadio(seconds: 5)
                self?.append(.link, "advertising stopped by injected disconnect")
            }
        }
        harness.onState = { [weak self] connected in
            DispatchQueue.main.async {
                guard let self else { return }
                if self.harnessConnected != connected {
                    self.append(.link, connected ? "pump-host connected" : "pump-host unreachable")
                }
                self.harnessConnected = connected
            }
        }
        harness.connect()
    }

    private func append(_ kind: LogLine.Kind, _ text: String) {
        log.append(LogLine(at: Date(), kind: kind, text: text))
        if log.count > 300 {
            log.removeFirst(log.count - 300)
        }
    }

    /// Android: BluetoothGattServer.openGattServer + addService + startAdvertising
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            status = "bluetooth unavailable (\(peripheral.state.rawValue))"
            append(.link, status)
            return
        }
        let service = CBMutableService(type: CBUUID(string: GattUuids.service), primary: true)
        service.characteristics = [cmd, rsp, statusChar]
        peripheral.removeAllServices()
        peripheral.add(service)
    }

    /// Android: BluetoothGattServerCallback.onServiceAdded
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            status = "service failed"
            append(.link, "addService failed: \(error.localizedDescription)")
            return
        }
        ensureAdvertising(reason: "service added")
        startAdvertisingWatchdog()
    }

    /// CoreBluetooth stops advertising as soon as a central connects and does
    /// not resume when it goes away. Re-asserting it is what lets the controller
    /// reconnect after a dropped link, which is the entire recovery scenario.
    private func ensureAdvertising(reason: String) {
        guard manager.state == .poweredOn else { return }
        guard !manager.isAdvertising else { return }
        // Advertises the service UUID only. CoreBluetooth rejects manufacturer data.
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: GattUuids.service)],
            CBAdvertisementDataLocalNameKey: "pump-link",
        ])
        append(.link, "advertising (\(reason))")
        refreshStatus()
    }

    /// The status line is derived from the radio rather than set alongside it.
    /// A label that says "advertising" while the radio is silent is worse than
    /// no label at all.
    private func refreshStatus() {
        if subscribed || statusSubscribed {
            status = "linked, mtu \(mtu)"
        } else if manager.isAdvertising {
            status = "advertising"
        } else if suppressAdvertising {
            status = "disconnected (injected)"
        } else {
            status = "idle"
        }
    }

    private func startAdvertisingWatchdog() {
        guard watchdog == nil else { return }
        watchdog = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            guard let self else { return }
            if !self.suppressAdvertising && !self.subscribed && !self.statusSubscribed {
                self.ensureAdvertising(reason: "watchdog")
            }
            self.refreshStatus()
        }
    }

    /// Android: BluetoothGattServerCallback.onCharacteristicWriteRequest
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            if request.characteristic.uuid == cmd.uuid, let value = request.value {
                var payload = Data([Harness.charCmd])
                payload.append(value)
                harness.send(type: Harness.writeReceived, payload: payload)
                append(.inbound, "CMD write \(value.count) B")
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }

    /// Android: BluetoothGattServerCallback.onCharacteristicReadRequest
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        // CoreBluetooth reserves CBMutableCharacteristic.value for cached
        // read-only characteristics. STATUS is [.read, .notify], so assigning
        // that field is invalid and a later read can still answer empty.
        // Serve from our own cache; if the JVM has not pushed a value yet,
        // an ATT error is honest and the central retries.
        guard let value = cachedStatus else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            append(.inbound, "STATUS read before cache filled")
            return
        }
        request.value = value
        peripheral.respond(to: request, withResult: .success)
        append(.inbound, "STATUS read \(value.count) B")
    }

    /// Android: onDescriptorWrite for CCCD 0x2902. iOS/macOS: didSubscribeTo.
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        centralName = String(central.identifier.uuidString.prefix(8))
        // D-02: usable payload is central.maximumUpdateValueLength, not an assumed MTU.
        mtu = central.maximumUpdateValueLength + 3
        var mtuBytes = Data()
        let value = UInt16(mtu).bigEndian
        withUnsafeBytes(of: value) { mtuBytes.append(contentsOf: $0) }
        harness.send(type: Harness.mtuChanged, payload: mtuBytes)
        let isStatus = characteristic.uuid == statusChar.uuid
        let which: UInt8 = isStatus ? Harness.charStatus : Harness.charRsp
        harness.send(type: Harness.subscribed, payload: Data([which]))
        if isStatus {
            statusSubscribed = true
        } else {
            subscribed = true
        }
        append(.link, "subscribed \(isStatus ? "STATUS" : "RSP"), mtu \(mtu)")
        refreshStatus()
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        let isStatus = characteristic.uuid == statusChar.uuid
        if isStatus {
            statusSubscribed = false
        } else {
            subscribed = false
        }
        let which: UInt8 = isStatus ? Harness.charStatus : Harness.charRsp
        harness.send(type: Harness.unsubscribed, payload: Data([which]))
        append(.link, "unsubscribed \(isStatus ? "STATUS" : "RSP")")
        if !subscribed && !statusSubscribed && !suppressAdvertising {
            ensureAdvertising(reason: "central left")
        }
        refreshStatus()
    }

    /// Android: notifyCharacteristicChanged. Backpressure: peripheralManagerIsReady.
    private func notify(characteristic: UInt8, payload: Data) {
        if characteristic == Harness.charStatus {
            cachedStatus = payload
        }
        let hasSubscriber = characteristic == Harness.charStatus ? statusSubscribed : subscribed
        guard hasSubscriber else {
            if characteristic == Harness.charStatus {
                append(.outbound, "STATUS cached \(payload.count) B (no subscriber)")
            }
            return
        }
        let target = characteristic == Harness.charStatus ? statusChar : rsp
        let sent = manager.updateValue(payload, for: target, onSubscribedCentrals: nil)
        if sent {
            append(.outbound, "\(characteristic == Harness.charStatus ? "STATUS" : "RSP") notify \(payload.count) B")
        } else {
            pendingNotifications.append((characteristic, payload))
            append(.outbound, "notify queued (\(pendingNotifications.count) waiting)")
        }
    }

    /// No Android analogue. CoreBluetooth tells us the notify queue has drained.
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        while let next = pendingNotifications.first {
            pendingNotifications.removeFirst()
            let characteristic = next.target == Harness.charStatus ? statusChar : rsp
            if !peripheral.updateValue(next.payload, for: characteristic, onSubscribedCentrals: nil) {
                pendingNotifications.insert(next, at: 0)
                break
            }
            let name = next.target == Harness.charStatus ? "STATUS" : "RSP"
            append(.outbound, "\(name) notify \(next.payload.count) B (drained)")
        }
    }

    func inject(fault: UInt8, named name: String) {
        armedFault = fault
        harness.send(type: Harness.fault, payload: Data([fault]))
        append(.fault, "armed \(name) (0x\(String(format: "%02X", fault)))")
    }

    func clearFault() {
        armedFault = 0x00
        harness.send(type: Harness.fault, payload: Data([0x00]))
        append(.fault, "cleared armed fault")
    }

    func setReservoir(_ units: Double) {
        reservoirUnits = units
        let milliunits = UInt16(max(0, min(65_535, units * 1_000)))
        var bytes = Data()
        let value = milliunits.bigEndian
        withUnsafeBytes(of: value) { bytes.append(contentsOf: $0) }
        harness.send(type: Harness.setReservoir, payload: bytes)
        append(.fault, "reservoir \(Int(units)) U")
    }

    func setBattery(_ percent: Double) {
        batteryPercent = percent
        harness.send(type: Harness.setBattery, payload: Data([UInt8(max(0, min(100, percent)))]))
        append(.fault, "battery \(Int(percent))%")
    }

    /// Drops the radio, then brings it back so the controller can reconnect and
    /// run query-then-decide. Holding it down forever would only show half the
    /// scenario.
    func forceDisconnect() {
        harness.send(type: Harness.forceDisconnectUp)
        holdDownRadio(seconds: 5)
        append(.fault, "forced disconnect, advertising back in 5s")
    }

    private func holdDownRadio(seconds: Double) {
        suppressAdvertising = true
        manager.stopAdvertising()
        refreshStatus()
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self else { return }
            self.suppressAdvertising = false
            self.ensureAdvertising(reason: "after injected disconnect")
        }
    }

    func resumeAdvertising() {
        suppressAdvertising = false
        ensureAdvertising(reason: "manual")
        refreshStatus()
    }
}
