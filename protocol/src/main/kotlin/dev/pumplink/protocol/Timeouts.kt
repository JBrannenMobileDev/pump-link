package dev.pumplink.protocol

object Timeouts {
    const val T_RESP_MS = 2_000L
    const val T_FRAG_MS = 500L
    const val T_AUTH_MS = 5_000L
    const val T_RESOLVE_MS = 10_000L
    const val T_POLL_MS = 5_000L
    const val POLL_FAILURE_LIMIT = 3
    const val RETRY_ATTEMPTS = 2
    val RETRY_BACKOFF_MS = longArrayOf(500L, 1_500L)

    const val SCAN_MS = 30_000L
    const val CONNECT_MS = 10_000L
    const val BOND_MS = 30_000L
    const val DISCOVER_MS = 10_000L
    const val CONFIGURE_MS = 5_000L
    const val SUBSCRIBED_MS = 5_000L

    const val LINK_MAX_ATTEMPTS = 8
    val LINK_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    const val LINK_BACKOFF_JITTER = 0.20
}
