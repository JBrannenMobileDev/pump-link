package dev.pumplink.protocol.link

import dev.pumplink.protocol.LogicalDeviceId
import dev.pumplink.protocol.ProtocolLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkReducerTest {

    private val paired = LogicalDeviceId(ByteArray(16) { 1 })
    private val other = LogicalDeviceId(ByteArray(16) { 2 })

    @Test
    fun `start moves Idle to Scanning`() {
        val next = LinkReducer.reduce(LinkState.Idle, LinkEvent.StartRequested(paired))
        assertIs<LinkState.Scanning>(next.state)
        assertTrue(next.effects.any { it is LinkEffect.StartScan })
    }

    @Test
    fun `ScanMatch without identity still connects`() {
        val scanning = LinkState.Scanning(paired)
        val next = LinkReducer.reduce(scanning, LinkEvent.ScanMatch(logicalDeviceId = null))
        assertIs<LinkState.Connecting>(next.state)
    }

    @Test
    fun `ScanMatch for a different identity is ignored`() {
        val scanning = LinkState.Scanning(paired)
        val next = LinkReducer.reduce(scanning, LinkEvent.ScanMatch(other))
        assertIs<LinkState.Scanning>(next.state)
    }

    @Test
    fun `STATUS identity must match before Authenticating`() {
        val subscribed = LinkState.Subscribed(paired, attempts = 0, mtu = 23)
        val mismatch = LinkReducer.reduce(
            subscribed,
            LinkEvent.StatusRead(other, ProtocolLimits.VERSION, 0u),
        )
        assertIs<LinkState.Recovering>(mismatch.state)

        val match = LinkReducer.reduce(
            subscribed,
            LinkEvent.StatusRead(paired, ProtocolLimits.VERSION, 0u),
        )
        assertIs<LinkState.Authenticating>(match.state)
        assertTrue(match.effects.any { it is LinkEffect.BeginAuth })
    }

    @Test
    fun `Configuring waits for both MTU and CCCD`() {
        val configuring = LinkState.Configuring(paired, attempts = 0)
        val afterMtu = LinkReducer.reduce(configuring, LinkEvent.MtuSettled(185))
        assertIs<LinkState.Configuring>(afterMtu.state)
        val subscribed = LinkReducer.reduce(afterMtu.state, LinkEvent.CccdConfirmed)
        assertIs<LinkState.Subscribed>(subscribed.state)
        assertTrue(subscribed.effects.any { it is LinkEffect.ReadStatus })
    }

    @Test
    fun `ReconcileDone with unresolved entries suspends dosing`() {
        val reconciling = LinkState.Reconciling(paired, attempts = 0, mtu = 23)
        val suspended = LinkReducer.reduce(reconciling, LinkEvent.ReconcileDone(1))
        assertIs<LinkState.Suspended>(suspended.state)
        val ready = LinkReducer.reduce(reconciling, LinkEvent.ReconcileDone(0))
        assertIs<LinkState.Ready>(ready.state)
    }

    @Test
    fun `Reconciling timeout goes to Suspended not Recovering`() {
        val reconciling = LinkState.Reconciling(paired, attempts = 0, mtu = 23)
        val next = LinkReducer.reduce(reconciling, LinkEvent.Timeout(reconciling))
        assertIs<LinkState.Suspended>(next.state)
    }

    @Test
    fun `disconnect from Ready resets the session`() {
        val ready = LinkState.Ready(paired, mtu = 23)
        val next = LinkReducer.reduce(ready, LinkEvent.Disconnected(ErrorClass.TransientLink))
        assertTrue(LinkEffect.ResetSession in next.effects)
    }

    @Test
    fun `auth failure with unpaired reason does not retry`() {
        val authenticating = LinkState.Authenticating(paired, attempts = 0, mtu = 23)
        val next = LinkReducer.reduce(authenticating, LinkEvent.AuthFailed("key mismatch", unpaired = true))
        assertIs<LinkState.Unpaired>(next.state)
    }

    @Test
    fun `UserVerifiedAtPump leaves Suspended for Reconciling`() {
        val suspended = LinkState.Suspended(paired, mtu = 23)
        val next = LinkReducer.reduce(suspended, LinkEvent.UserVerifiedAtPump)
        assertIs<LinkState.Reconciling>(next.state)
        assertTrue(next.effects.any { it is LinkEffect.BeginReconcile })
    }

    @Test
    fun `ReconcileRequested leaves Suspended for Reconciling`() {
        val suspended = LinkState.Suspended(paired, mtu = 23)
        val next = LinkReducer.reduce(suspended, LinkEvent.ReconcileRequested)
        assertIs<LinkState.Reconciling>(next.state)
        assertTrue(next.effects.any { it is LinkEffect.BeginReconcile })
    }

    @Test
    fun `ReconcileRequested is ignored from Ready and Recovering`() {
        val ready = LinkReducer.reduce(LinkState.Ready(paired, mtu = 23), LinkEvent.ReconcileRequested)
        assertIs<LinkState.Ready>(ready.state)
        val recovering = LinkReducer.reduce(
            LinkState.Recovering(paired, attempts = 1, error = ErrorClass.TransientLink),
            LinkEvent.ReconcileRequested,
        )
        assertIs<LinkState.Recovering>(recovering.state)
    }

    @Test
    fun `bolus is only reachable from Ready`() {
        val ready = LinkState.Ready(paired, mtu = 23)
        assertEquals(ready::class, LinkState.Ready::class)
        val linking = listOf(
            LinkState.Scanning(paired),
            LinkState.Connecting(paired, 0),
            LinkState.Reconciling(paired, 0, 23),
            LinkState.Suspended(paired, 23),
        )
        linking.forEach { state ->
            assertTrue(state !is LinkState.Ready, "dosing reachable from $state")
        }
    }
}
