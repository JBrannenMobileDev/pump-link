package dev.pumplink.protocol.link

enum class ErrorClass {
    TransientLink,
    PeerInitiated,
    StackFault,
    CacheStale,
    AuthFailure,
    ProtocolFault,
    Unrecoverable,
}
