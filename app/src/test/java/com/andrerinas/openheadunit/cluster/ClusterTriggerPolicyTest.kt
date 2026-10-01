package com.andrerinas.openheadunit.cluster

import com.andrerinas.openheadunit.cluster.ClusterTriggerPolicy.Signals
import com.andrerinas.openheadunit.cluster.ClusterTriggerPolicy.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTriggerPolicyTest {

    private val now = 1_000_000L

    private fun signals(
        clusterStarted: Boolean = false,
        guidanceActive: Boolean = false,
        lastMessageAt: Long = 0L,
    ) = Signals(clusterStarted, guidanceActive, lastMessageAt)

    private fun mirroring(trigger: Trigger, signals: Signals, nowMs: Long = now): Boolean =
        ClusterTriggerPolicy.shouldMirror(trigger, signals, nowMs)

    // ------------------------------------------------------------------ activity

    @Test
    fun `nothing on the navigation channel means no map is on screen`() {
        assertEquals(
            NavigationActivity.NONE,
            ClusterTriggerPolicy.activity(signals(), now),
        )
    }

    @Test
    fun `the cluster start latch marks a map on screen even before any message has a timestamp`() {
        // This is the case the latch exists for: the map app announced itself and has not been
        // chatty since, which is normal while cruising a long straight road.
        assertEquals(
            NavigationActivity.CRUISE,
            ClusterTriggerPolicy.activity(signals(clusterStarted = true), now),
        )
    }

    @Test
    fun `a map without a route is cruising, and a route makes it navigation`() {
        assertEquals(
            NavigationActivity.CRUISE,
            ClusterTriggerPolicy.activity(signals(clusterStarted = true, guidanceActive = false), now),
        )
        assertEquals(
            NavigationActivity.NAVIGATION,
            ClusterTriggerPolicy.activity(signals(clusterStarted = true, guidanceActive = true), now),
        )
    }

    @Test
    fun `a live message is enough on its own, without the start latch`() {
        // Not every map app announces itself; the position stream is the fallback that keeps the
        // tiers working on a head unit whose map never sends INSTRUMENT_CLUSTER_START.
        assertEquals(
            NavigationActivity.CRUISE,
            ClusterTriggerPolicy.activity(signals(lastMessageAt = now - 1_000), now),
        )
    }

    @Test
    fun `a quiet channel is forgotten once the timeout passes`() {
        val stale = signals(lastMessageAt = now - ClusterTriggerPolicy.SIGNAL_TIMEOUT_MS - 1)
        assertEquals(NavigationActivity.NONE, ClusterTriggerPolicy.activity(stale, now))
    }

    @Test
    fun `the timeout boundary itself still counts as present`() {
        val boundary = signals(lastMessageAt = now - ClusterTriggerPolicy.SIGNAL_TIMEOUT_MS)
        assertEquals(NavigationActivity.CRUISE, ClusterTriggerPolicy.activity(boundary, now))
    }

    @Test
    fun `the start latch survives the message timeout`() {
        // The stop message is what ends this, not silence; otherwise a map that only speaks when it
        // has something to say would drop the mirror mid-journey.
        val latched = signals(clusterStarted = true, lastMessageAt = now - 10 * ClusterTriggerPolicy.SIGNAL_TIMEOUT_MS)
        assertEquals(NavigationActivity.CRUISE, ClusterTriggerPolicy.activity(latched, now))
    }

    @Test
    fun `a message timestamped in the future is not mistaken for an ancient one`() {
        // The writer and the reader are different threads, so the timestamp can be a moment ahead of
        // the clock read here. Treating that as stale would switch the mirror off for no reason.
        val ahead = signals(lastMessageAt = now + 5_000)
        assertEquals(NavigationActivity.CRUISE, ClusterTriggerPolicy.activity(ahead, now))
    }

    @Test
    fun `the zero timestamp is the absence of a message, not a message from the epoch`() {
        assertFalse(
            ClusterTriggerPolicy.shouldMirror(Trigger.CRUISE_AND_NAV, signals(lastMessageAt = 0L), now)
        )
    }

    // ------------------------------------------------------------------ tiers

    @Test
    fun `always mirrors with no map at all`() {
        assertTrue(mirroring(Trigger.ALWAYS, signals()))
    }

    @Test
    fun `cruise and navigation waits for a map`() {
        assertFalse(mirroring(Trigger.CRUISE_AND_NAV, signals()))
        assertTrue(mirroring(Trigger.CRUISE_AND_NAV, signals(clusterStarted = true)))
        assertTrue(mirroring(Trigger.CRUISE_AND_NAV, signals(clusterStarted = true, guidanceActive = true)))
    }

    @Test
    fun `navigation only refuses to mirror a map that is merely cruising`() {
        // The whole point of the strictest tier: a map on screen is not a route being guided.
        assertFalse(mirroring(Trigger.NAV_ONLY, signals(clusterStarted = true, guidanceActive = false)))
        assertTrue(mirroring(Trigger.NAV_ONLY, signals(clusterStarted = true, guidanceActive = true)))
    }

    @Test
    fun `navigation only still needs the map to be present`() {
        // A route flag left latched by a map that has gone away must not keep a window on the
        // cluster: the guidance count alone is not proof that anything is still drawing.
        val orphaned = signals(clusterStarted = false, guidanceActive = true, lastMessageAt = 0L)
        assertFalse(mirroring(Trigger.NAV_ONLY, orphaned))
    }

    @Test
    fun `navigation only drops out when the map goes quiet`() {
        val stale = signals(
            clusterStarted = false,
            guidanceActive = true,
            lastMessageAt = now - ClusterTriggerPolicy.SIGNAL_TIMEOUT_MS - 1,
        )
        assertEquals(NavigationActivity.NONE, ClusterTriggerPolicy.activity(stale, now))
        assertFalse(mirroring(Trigger.NAV_ONLY, stale))
    }

    @Test
    fun `the three tiers nest, so a stricter tier never allows more than a looser one`() {
        val cases = listOf(
            signals(),
            signals(lastMessageAt = now),
            signals(clusterStarted = true),
            signals(clusterStarted = true, guidanceActive = true),
            signals(lastMessageAt = now - ClusterTriggerPolicy.SIGNAL_TIMEOUT_MS - 1),
            signals(clusterStarted = true, guidanceActive = true, lastMessageAt = now - 1),
            signals(guidanceActive = true, lastMessageAt = 0L),
        )
        for (case in cases) {
            assertTrue("ALWAYS must hold for $case", mirroring(Trigger.ALWAYS, case))
            if (mirroring(Trigger.NAV_ONLY, case)) {
                assertTrue("NAV_ONLY implies CRUISE_AND_NAV for $case", mirroring(Trigger.CRUISE_AND_NAV, case))
            }
        }
    }
}
