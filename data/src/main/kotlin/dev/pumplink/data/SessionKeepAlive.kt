package dev.pumplink.data

import dev.pumplink.domain.JournalSnapshot

/**
 * When the process must be in a connected-device foreground service.
 *
 * H-12 is the minimum: an in-flight command (Pending, InFlight, Resolving)
 * never drops the hold. A shipping session is a stricter hold: once the
 * operator has asked for a link, Android will kill background GATT, so the
 * service stays foreground until they ask to stop *and* nothing is in flight.
 */
object SessionKeepAlive {
    fun shouldBeForeground(sessionRequested: Boolean, journal: JournalSnapshot): Boolean =
        sessionRequested || journal.inFlight().isNotEmpty()
}
