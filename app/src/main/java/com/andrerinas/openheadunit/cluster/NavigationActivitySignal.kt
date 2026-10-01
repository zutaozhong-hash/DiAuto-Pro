package com.andrerinas.openheadunit.cluster

import android.os.SystemClock

/**
 * The latest navigation activity seen on Android Auto's `ID_NAV` channel, in the shape
 * [ClusterTriggerPolicy] wants it.
 *
 * Ownership note: this lives in the `cluster` package but is **written by `AapNavigation`**, which
 * sits in `aap`. The dependency already points that way (`AapService` drives the mirror's session
 * lifecycle), so putting it here keeps it one-directional rather than introducing a package cycle.
 *
 * The connection thread writes and the main thread reads, so every field is volatile and
 * [snapshot] returns one consistent copy. The reader polls instead of subscribing on purpose: a
 * missed poll costs one frame of latency, whereas a stale callback could keep a window open on the
 * cluster after the map app has walked away.
 */
object NavigationActivitySignal {

    @Volatile
    private var clusterStarted = false

    @Volatile
    private var guidanceActive = false

    @Volatile
    private var lastMessageMs = 0L

    fun snapshot(): ClusterTriggerPolicy.Signals =
        ClusterTriggerPolicy.Signals(clusterStarted, guidanceActive, lastMessageMs)

    /** `INSTRUMENT_CLUSTER_START`: a map app took the cluster view. */
    fun onClusterStart() {
        clusterStarted = true
        touch()
    }

    /** `INSTRUMENT_CLUSTER_STOP`, or a fresh session: nothing is driving the cluster any more. */
    fun onClusterStop() = reset()

    /**
     * `INSTRUMENT_CLUSTER_NAVIGATION_STATUS`.
     *
     * `ACTIVE` and `REROUTING` mean a route is live; `INACTIVE` means a map is on screen without
     * one, which is precisely the cruise case, and `UNAVAILABLE` means the map app has no route
     * engine at all. So this field both raises and clears the navigating flag — it is the only
     * signal that can clear it, which is why it must not be missed.
     */
    fun onClusterStatus(navigating: Boolean) {
        guidanceActive = navigating
        touch()
    }

    /**
     * `NEXTTURNDETAILS`.
     *
     * Latch only on a real turn. The message itself is not proof of a route: a map that is merely on
     * screen can still publish the road the car is on, with no next turn attached, and treating that
     * as navigating would defeat the point of the "navigation only" tier.
     */
    fun onNextTurnDetails(hasNextTurn: Boolean) {
        if (hasNextTurn) guidanceActive = true
        touch()
    }

    /**
     * `NEXTTURNDISTANCEANDTIME`.
     *
     * The distance to the next turn only exists while a route is being guided, so its presence is a
     * valid latch. A negative value is the protocol's "unknown" and must not count.
     */
    fun onNextTurnDistance(distanceMeters: Int) {
        if (distanceMeters >= 0) guidanceActive = true
        touch()
    }

    /**
     * `INSTRUMENT_CLUSTER_NAVIGATION_STATE`.
     *
     * Latches on only. A route can briefly report zero steps while it is being recalculated, and
     * dropping the mirror for that would be worse than holding it a moment too long. Leaving
     * navigation is announced by [onClusterStatus] instead, which is the authoritative signal.
     */
    fun onSteps(steps: Int) {
        if (steps > 0) guidanceActive = true
        touch()
    }

    /** `INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION`: proof the map is alive, nothing more. */
    fun onPosition() = touch()

    /** Forget everything. Called when a session starts or ends, so no latch outlives its session. */
    fun reset() {
        clusterStarted = false
        guidanceActive = false
        lastMessageMs = 0L
    }

    private fun touch() {
        lastMessageMs = SystemClock.elapsedRealtime()
    }
}
