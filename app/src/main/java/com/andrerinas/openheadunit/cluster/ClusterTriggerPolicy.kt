package com.andrerinas.openheadunit.cluster

/**
 * What a map app is currently doing with the cluster view, as far as Android Auto tells us.
 *
 * [CRUISE] and [NAVIGATION] are distinct states rather than one "active" flag because Android Auto
 * reports them separately: `INSTRUMENT_CLUSTER_NAVIGATION_STATUS` carries an `ACTIVE` / `INACTIVE` /
 * `REROUTING` enum, and `INACTIVE` is exactly "a map is on screen, but no route is being guided".
 * The same reading is already used by the BYD guidance mapper, which ignores everything that is not
 * `ACTIVE` before it converts a snapshot into an instrument payload.
 */
enum class NavigationActivity {
    /** No map app is driving the cluster view. */
    NONE,

    /** A map is on screen without an active route. */
    CRUISE,

    /** A route is being guided. */
    NAVIGATION,
}

/**
 * Decides whether the instrument-cluster mirror may be on right now.
 *
 * The mirror is a sampled copy of the main Android Auto picture, so it cannot know on its own
 * whether the driver is looking at a map or at the media player. This policy supplies that missing
 * judgement from the navigation signals, and it deliberately keeps the rules narrow and total, so
 * every trigger resolves to a plain yes/no and no state can be left ambiguous.
 *
 * Pure logic: no Android types, so the rules are unit tested on the JVM rather than asserted in
 * comments. Only the three tiers differ in how strict they are, and they nest:
 * `NAV_ONLY` implies `CRUISE_AND_NAV` implies `ALWAYS`.
 */
object ClusterTriggerPolicy {

    /** Which of the three timing modes the owner picked. */
    enum class Trigger {
        /** Mirror whenever a session is up, whatever is on screen. */
        ALWAYS,

        /** Mirror while a map is on screen, navigating or just cruising. */
        CRUISE_AND_NAV,

        /** Mirror only while a route is being guided. */
        NAV_ONLY,
    }

    /**
     * How long a navigation message stays meaningful.
     *
     * Android Auto only talks on `ID_NAV` while a map app is driving the cluster view, so silence
     * means the map is gone. The window is generous because a map cruising a long straight road has
     * little to report, and dropping the mirror on a quiet stretch would look like a fault. The
     * `INSTRUMENT_CLUSTER_START` latch below is what normally keeps this from mattering at all.
     */
    const val SIGNAL_TIMEOUT_MS = 30_000L

    /**
     * What has been observed on the navigation channel, as plain data.
     *
     * @param clusterStarted `INSTRUMENT_CLUSTER_START` was seen and `STOP` has not followed. This is
     *   the reliable latch: it is sent when the map app takes the cluster view and again when it
     *   gives it back, so it does not depend on how chatty the map happens to be.
     * @param guidanceActive a route is being guided.
     * @param lastMessageMs elapsed-realtime of the last navigation message, or `0` for none.
     */
    data class Signals(
        val clusterStarted: Boolean = false,
        val guidanceActive: Boolean = false,
        val lastMessageMs: Long = 0L,
    )

    fun activity(signals: Signals, nowMs: Long): NavigationActivity {
        if (!mapPresent(signals, nowMs)) return NavigationActivity.NONE
        return if (signals.guidanceActive) NavigationActivity.NAVIGATION else NavigationActivity.CRUISE
    }

    fun shouldMirror(trigger: Trigger, signals: Signals, nowMs: Long): Boolean = when (trigger) {
        Trigger.ALWAYS -> true
        Trigger.CRUISE_AND_NAV -> activity(signals, nowMs) != NavigationActivity.NONE
        Trigger.NAV_ONLY -> activity(signals, nowMs) == NavigationActivity.NAVIGATION
    }

    /**
     * A map app is driving the cluster view.
     *
     * The age is only bounded above, so a message timestamped a moment *after* the read counts as
     * present rather than as infinitely old. The two timestamps come from different threads and the
     * writer can therefore land a hair in the future; rejecting that would switch the mirror off for
     * the freshest possible frame. A stale reading in the other direction is caught by the upper
     * bound, and both sides read the same monotonic clock (`elapsedRealtime`), so this cannot be
     * defeated by a wall-clock change.
     */
    private fun mapPresent(signals: Signals, nowMs: Long): Boolean {
        if (signals.clusterStarted) return true
        if (signals.lastMessageMs <= 0L) return false
        return nowMs - signals.lastMessageMs <= SIGNAL_TIMEOUT_MS
    }
}
