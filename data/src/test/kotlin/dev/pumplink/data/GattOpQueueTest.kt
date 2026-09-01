package dev.pumplink.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattOpQueueTest {

    @Test
    fun failedInitiationReleasesGateAndRunsNext() = runTest {
        val stalled = mutableListOf<String>()
        val started = mutableListOf<String>()
        val queue = GattOpQueue(this, timeoutMillis = 1_000L, onStall = { stalled.add(it) })
        queue.submit("fail") { false }
        queue.submit("ok") {
            started.add("ok")
            true
        }
        assertEquals(listOf("fail"), stalled)
        assertEquals(listOf("ok"), started)
        queue.complete()
        var ran = false
        queue.submit("after") {
            ran = true
            true
        }
        assertTrue(ran)
    }

    @Test
    fun thrownOpReleasesGateAndRunsNext() = runTest {
        val stalled = mutableListOf<String>()
        val started = mutableListOf<String>()
        val queue = GattOpQueue(this, timeoutMillis = 1_000L, onStall = { stalled.add(it) })
        queue.submit("boom") { error("nope") }
        queue.submit("ok") {
            started.add("ok")
            true
        }
        assertEquals(listOf("boom"), stalled)
        assertEquals(listOf("ok"), started)
    }

    @Test
    fun watchdogReleasesGateAfterTimeout() = runTest {
        val stalled = mutableListOf<String>()
        val queue = GattOpQueue(backgroundScope, timeoutMillis = 50L, onStall = { stalled.add(it) })
        queue.submit("hang") { true }
        advanceTimeBy(49)
        assertTrue(stalled.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("hang"), stalled)
        var ran = false
        queue.submit("next") {
            ran = true
            true
        }
        assertTrue(ran)
    }

    @Test
    fun resetClearsGateAndDropsQueuedOps() = runTest {
        val started = mutableListOf<String>()
        val queue = GattOpQueue(this, timeoutMillis = 1_000L, onStall = { })
        queue.submit("hang") { true }
        queue.submit("queued") {
            started.add("queued")
            true
        }
        queue.reset()
        assertTrue(started.isEmpty())
        queue.submit("after") {
            started.add("after")
            true
        }
        assertEquals(listOf("after"), started)
    }

    @Test
    fun completeDuringStartDoesNotCancelNextWatchdog() = runTest {
        val stalled = mutableListOf<String>()
        val queue = GattOpQueue(backgroundScope, timeoutMillis = 50L, onStall = { stalled.add(it) })
        queue.submit("first") {
            queue.submit("second") { true }
            queue.complete()
            true
        }
        advanceTimeBy(49)
        assertTrue(stalled.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("second"), stalled)
    }

    @Test
    fun onlyOneOpRunsUntilComplete() = runTest {
        val started = mutableListOf<String>()
        val queue = GattOpQueue(this, timeoutMillis = 1_000L, onStall = { })
        queue.submit("a") {
            started.add("a")
            true
        }
        queue.submit("b") {
            started.add("b")
            true
        }
        assertEquals(listOf("a"), started)
        queue.complete()
        assertEquals(listOf("a", "b"), started)
    }
}
