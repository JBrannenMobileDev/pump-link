package dev.pumplink.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot GATT operation queue. Android permits one outstanding GATT call
 * (D-09); this is the gate that makes that a structural property.
 *
 * The gate is released by exactly one of: the matching callback ([complete]),
 * a failed initiation, a thrown [start], a watchdog expiry, or [reset].
 * Never by nothing — a missing callback used to latch the queue forever and
 * drop every later `connectGatt`.
 *
 * The watchdog is armed *before* [start] runs. A callback can beat the
 * return of `requestMtu` / `writeDescriptor`; arming afterwards would
 * cancel the next op's watchdog and leave Configuring waiting on a CCCD
 * write that will never be released.
 */
internal class GattOpQueue(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val onStall: (String) -> Unit,
) {
    private data class Op(val name: String, val start: () -> Boolean)

    private val operations = ConcurrentLinkedQueue<Op>()
    private val busy = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private val inFlight = AtomicReference<String?>(null)
    private val lock = Any()
    private var watchdog: Job? = null

    /** [start] returns false when the call could not be initiated. */
    fun submit(name: String, start: () -> Boolean) {
        operations.add(Op(name, start))
        pump()
    }

    fun complete() {
        inFlight.set(null)
        cancelWatchdog()
        busy.set(0)
        pump()
    }

    fun reset() {
        inFlight.set(null)
        cancelWatchdog()
        operations.clear()
        busy.set(0)
    }

    private fun pump() {
        if (!busy.compareAndSet(0, 1)) return
        var held = true
        while (held) {
            held = startNext()
        }
    }

    /**
     * @return true if the gate is still held and the next queued op should
     * be tried (the last one failed to start).
     */
    private fun startNext(): Boolean {
        val next = operations.poll()
        if (next == null) {
            busy.set(0)
            return false
        }
        inFlight.set(next.name)
        armWatchdog(next.name)
        val initiated = try {
            next.start()
        } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
            return failToStart(next.name)
        }
        if (!initiated) {
            return failToStart(next.name)
        }
        return false
    }

    private fun failToStart(name: String): Boolean {
        if (!inFlight.compareAndSet(name, null)) {
            return false
        }
        cancelWatchdog()
        onStall(name)
        return true
    }

    private fun armWatchdog(name: String) {
        val gen = generation.incrementAndGet()
        synchronized(lock) {
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(timeoutMillis)
                if (generation.get() != gen) return@launch
                if (inFlight.get() != name) return@launch
                inFlight.set(null)
                busy.set(0)
                onStall(name)
                pump()
            }
        }
    }

    private fun cancelWatchdog() {
        generation.incrementAndGet()
        synchronized(lock) {
            watchdog?.cancel()
            watchdog = null
        }
    }
}
