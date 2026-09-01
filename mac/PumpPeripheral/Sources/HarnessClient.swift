import Foundation
import Network

/// Length-prefixed TCP client for the JVM pump-host. Not product protocol.
final class HarnessClient: @unchecked Sendable {
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "harness")

    /// Frames arrive split and coalesced by TCP, so bytes accumulate here and
    /// whole frames are drained out. Assuming one frame per read drops
    /// notifications, which during a demo looks exactly like a protocol bug.
    private var inbound = [UInt8]()

    var onNotify: ((UInt8, Data) -> Void)?
    var onForceDisconnect: (() -> Void)?
    var onState: ((Bool) -> Void)?

    func connect() {
        let connection = NWConnection(
            host: NWEndpoint.Host("127.0.0.1"),
            port: NWEndpoint.Port(rawValue: Harness.port)!,
            using: .tcp
        )
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onState?(true)
                self.receive()
            case .failed, .cancelled:
                self.onState?(false)
                self.scheduleReconnect()
            case .waiting:
                // A refused connection to localhost parks here and does not
                // retry on its own, so tear it down and start a fresh one.
                // The host is launched by hand and may not be up yet.
                self.onState?(false)
                self.scheduleReconnect()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    /// The host is started by hand, so it may not be listening yet when this
    /// window opens. Retry rather than requiring a specific launch order.
    private func scheduleReconnect() {
        connection?.cancel()
        connection = nil
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, self.connection == nil else { return }
            self.inbound.removeAll()
            self.connect()
        }
    }

    func send(type: UInt8, payload: Data = Data()) {
        var length = UInt16(1 + payload.count).bigEndian
        var packet = Data()
        packet.append(Data(bytes: &length, count: 2))
        packet.append(type)
        packet.append(payload)
        connection?.send(content: packet, completion: .contentProcessed { _ in })
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.inbound.append(contentsOf: data)
                self.drain()
            }
            if error != nil || isComplete {
                self.onState?(false)
                self.scheduleReconnect()
                return
            }
            self.receive()
        }
    }

    private func drain() {
        while inbound.count >= 2 {
            let length = Int(inbound[0]) << 8 | Int(inbound[1])
            guard length >= 1, inbound.count >= 2 + length else { return }
            let type = inbound[2]
            let payload = Data(inbound[3..<(2 + length)])
            inbound.removeFirst(2 + length)
            dispatch(type: type, payload: payload)
        }
    }

    private func dispatch(type: UInt8, payload: Data) {
        switch type {
        case Harness.notify:
            if let characteristic = payload.first {
                onNotify?(characteristic, payload.dropFirst())
            }
        case Harness.forceDisconnectDown:
            onForceDisconnect?()
        default:
            break
        }
    }
}
