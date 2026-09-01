import Foundation

/// Same UUIDs as docs/02-protocol.md L0.
enum GattUuids {
    static let service = "6f1c0001-4e7a-4b16-9c3d-2f8a5d61b704"
    static let cmd = "6f1c0002-4e7a-4b16-9c3d-2f8a5d61b704"
    static let rsp = "6f1c0003-4e7a-4b16-9c3d-2f8a5d61b704"
    static let status = "6f1c0004-4e7a-4b16-9c3d-2f8a5d61b704"
}

enum Harness {
    static let port: UInt16 = 17341
    static let mtuChanged: UInt8 = 0x01
    static let subscribed: UInt8 = 0x02
    static let unsubscribed: UInt8 = 0x03
    static let writeReceived: UInt8 = 0x04
    static let notify: UInt8 = 0x05
    static let fault: UInt8 = 0x10
    static let setReservoir: UInt8 = 0x11
    static let setBattery: UInt8 = 0x12
    static let forceDisconnectUp: UInt8 = 0x13
    static let forceDisconnectDown: UInt8 = 0x14
    static let charCmd: UInt8 = 0x00
    static let charRsp: UInt8 = 0x01
    static let charStatus: UInt8 = 0x02
}
