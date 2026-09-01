package dev.pumplink.domain

@JvmInline
value class Milliunits(val value: Int) : Comparable<Milliunits> {
    override fun compareTo(other: Milliunits): Int = value.compareTo(other.value)
}

@JvmInline
value class DomainCommandId(val value: UInt)

@JvmInline
value class DomainStoreId(val value: ULong)

data class Dose(val milliunits: Milliunits)

data class Draft(val milliunits: Milliunits)

enum class AbortReason {
    UserCancelled,
    PumpRejected,
    Reservoir,
    Occlusion,
    Other,
}

sealed interface Resolution {
    data object NeverSeen : Resolution
    data object InFlight : Resolution
    data class Completed(val delivered: Dose) : Resolution
    data class Aborted(val delivered: Dose, val reason: AbortReason) : Resolution
    data object Indeterminate : Resolution
}

sealed interface LinkStatus {
    data object Idle : LinkStatus
    data object Linking : LinkStatus
    data object Ready : LinkStatus
    data object Suspended : LinkStatus
    data object Recovering : LinkStatus
    data object Failed : LinkStatus
    data object Unpaired : LinkStatus
}

/**
 * The ordered substates a link passes through on the way to Ready, exactly as
 * tabulated in docs/03-connection-state-machine.md. Terminal and error states
 * are [LinkStatus] values, not steps, which is why this enum has eight entries
 * and that sealed interface has seven.
 *
 * Dosing never consults this. It exists so the link screen can say which step
 * is outstanding instead of showing an undifferentiated spinner.
 */
enum class LinkStep {
    Scanning,
    Connecting,
    Bonding,
    Discovering,
    Configuring,
    Subscribed,
    Authenticating,
    Reconciling,
}

/**
 * Domain mirror of the retry classes in docs/03. The protocol enum lives in
 * :protocol, which :presentation cannot see; the platform boundary maps one
 * onto the other.
 */
enum class LinkFault {
    TransientLink,
    PeerInitiated,
    StackFault,
    CacheStale,
    AuthFailure,
    ProtocolFault,
    Unrecoverable,
}

/**
 * Detail behind [LinkStatus] for the link screen. [timeoutMillis] is the budget
 * for [step] so the UI can say how long it will wait rather than waiting
 * silently; it is zero when no step is outstanding.
 *
 * [radioEnabled] is the phone's Bluetooth adapter, not the pump link. A GATT
 * client can stay "connected" after the adapter is powered down; dosing must
 * not treat that as Ready.
 */
data class LinkProgress(
    val step: LinkStep? = null,
    val attempts: Int = 0,
    val mtu: Int = 0,
    val fault: LinkFault? = null,
    val timeoutMillis: Long = 0L,
    val radioEnabled: Boolean = true,
)

data class PumpSummary(
    val reservoirMilliunits: Int,
    val batteryPercent: Int,
    val deliveryActive: Boolean,
    val storeInstanceId: DomainStoreId,
)

enum class JournalState {
    Pending,
    InFlight,

    /**
     * The first reply was lost or unfinished, and we are asking the pump
     * what happened. Durable so a process death mid-query still says
     * "we were asking", not "we transmitted and are waiting".
     */
    Resolving,

    Resolved,

    /**
     * The pump could not tell us what happened. Durable, and it gates dosing
     * until a human closes it out.
     */
    Indeterminate,

    /**
     * A human reconciled this record against the pump. It no longer gates
     * dosing, and the row keeps its unresolved [Resolution] so the log still
     * shows that no machine ever resolved it.
     */
    Acknowledged,
}

data class JournalEntry(
    val commandId: DomainCommandId,
    val storeInstanceId: DomainStoreId,
    val requested: Dose,
    val state: JournalState,
    val sentAtMillis: Long,
    val delivered: Dose? = null,
    val resolution: Resolution? = null,
)

data class JournalSnapshot(val entries: List<JournalEntry>) {
    /**
     * The journal is an append-only history: one CommandId owns several rows as
     * it advances. Current state is the last row per CommandId, never a filter
     * over the whole log — an earlier `InFlight` row stays on disk forever, and
     * reading it as current pins the UI to a delivery that already finished.
     */
    fun current(): List<JournalEntry> =
        entries.groupBy { it.commandId }.values.map { it.last() }

    fun inFlight(): List<JournalEntry> = current().filter {
        it.state == JournalState.Pending ||
            it.state == JournalState.InFlight ||
            it.state == JournalState.Resolving
    }

    fun latest(commandId: DomainCommandId): JournalEntry? =
        entries.lastOrNull { it.commandId == commandId }

    fun hasIndeterminate(): Boolean = indeterminate() != null

    fun indeterminate(): JournalEntry? =
        current().lastOrNull { it.state == JournalState.Indeterminate }

    fun asking(): JournalEntry? =
        current().lastOrNull { it.state == JournalState.Resolving }

    /**
     * True when this CommandId ever passed through [JournalState.Resolving].
     * The row stays in the log after the query settles, so a recovered
     * delivery can be labelled without inferring from Pending/InFlight —
     * those are written on every send.
     */
    fun wasRecovered(commandId: DomainCommandId): Boolean =
        entries.any { it.commandId == commandId && it.state == JournalState.Resolving }

    /**
     * A command the pump reports it never saw, still awaiting the operator's
     * reissue decision. Safe to resend under the same CommandId; unsafe to
     * resend under a new one.
     */
    fun awaitingReissue(): JournalEntry? = current().lastOrNull {
        it.state == JournalState.Resolved && it.resolution == Resolution.NeverSeen
    }

    /**
     * The most recent command the pump gave a definite answer about, delivered
     * or aborted. Stays on screen until acknowledged so a result is never
     * silently replaced by a fresh entry field.
     */
    fun lastTerminal(): JournalEntry? = current().lastOrNull {
        it.state == JournalState.Resolved &&
            (it.resolution is Resolution.Completed || it.resolution is Resolution.Aborted)
    }
}

interface CommandJournal {
    suspend fun append(entry: JournalEntry)
    fun snapshot(): JournalSnapshot
}

fun interface Clock {
    fun nowMillis(): Long
}

object SafetyLimits {
    const val MAX_BOLUS_MILLIUNITS = 25_000
    const val INCREMENT_MILLIUNITS = 50
    const val REISSUE_WINDOW_MS = 60_000L
}

fun Draft.isValidIncrement(): Boolean =
    milliunits.value > 0 &&
        milliunits.value <= SafetyLimits.MAX_BOLUS_MILLIUNITS &&
        milliunits.value % SafetyLimits.INCREMENT_MILLIUNITS == 0
