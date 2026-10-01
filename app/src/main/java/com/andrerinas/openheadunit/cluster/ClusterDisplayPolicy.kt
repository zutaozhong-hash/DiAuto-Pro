package com.andrerinas.openheadunit.cluster

/**
 * Display-name matching and viewport geometry for the BYD instrument-cluster projection display.
 *
 * Everything here is pure (no Android types) so it can be unit-tested on the JVM; the Android side
 * ([ClusterDisplayLocator], [ClusterPresentation], [ClusterProjectionController]) only feeds it
 * display names and sizes.
 *
 * Background, from the reverse engineering of `com.byd.automap` / `com.byd.launchermap` and of
 * DiPlay's working implementation:
 *
 *  - BYD exposes the cluster's projection area as a **public presentation display** owned by
 *    `com.byd.containerservice`, so a third-party app is allowed to put a window on it. That is the
 *    whole reason this is possible without any BYD permission.
 *  - DiLink 4.0 / 5.0 publish `fission_bg_XDJAScreenProjection`; DiLink 5.1 publishes
 *    `shared_fission_bg_XDJAScreenProjection_0` (full-map layer) and `_1` (side-map layer). On 5.1
 *    the stock map's own `fission_*` layer is hidden from third parties, which is why the `shared_`
 *    siblings are what an app can actually see there.
 *  - The stock cluster resolution is 1920x720 on every firmware measured so far.
 */
object ClusterDisplayPolicy {

    const val BASE = "fission_bg_XDJAScreenProjection"
    const val SHARED_FULL = "shared_${BASE}_0"
    const val SHARED_SIDE = "shared_${BASE}_1"

    /** Stock overlay layer name the official map falls back to when nothing else matches. */
    const val OVERLAY_PREFIX = "叠加视图"

    /** The only cluster resolution BYD firmware has been measured at. */
    const val KNOWN_WIDTH = 1920
    const val KNOWN_HEIGHT = 720

    /** Where inside the cluster display the mirror may draw. */
    enum class ViewportMode {
        /** Per-firmware default: see [viewport]. */
        AUTO,

        /** Full-map band of the 1920x720 cluster: 144 px status strip kept on top, 96 px below. */
        MAP_BAND,

        /** Right-hand 600x720 card of the 1920x720 cluster. */
        SIDE_CARD,

        /** The whole display. Only right on a layer that already excludes the instruments. */
        FULL_BLEED,
    }

    /** A rectangle in display pixels. Deliberately not `android.graphics.Rect` so this file stays pure. */
    data class Viewport(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * True for every name family BYD has been observed to publish the cluster under.
     *
     * This is the union of the two predicates we have evidence for: the official map's
     * `display.name.startsWith("fission_")` and DiPlay's `contains("fission_bg_XDJAScreenProjection")`
     * plus the `叠加视图` fallback.
     */
    fun isClusterDisplayName(name: String?): Boolean {
        val value = name ?: return false
        return value.startsWith("fission_") ||
            value.startsWith("shared_fission_") ||
            value.contains(BASE) ||
            value.startsWith(OVERLAY_PREFIX)
    }

    /**
     * Preference order among cluster displays that are actually present.
     *
     * `_0` is the full-map layer and the better mirror target on 5.x; [BASE] is what 4.0/5.0
     * publish. The stock `叠加视图` layer is last because its geometry is undocumented and it is
     * only known to exist as a fallback.
     */
    fun rank(name: String): Int = when {
        name == SHARED_FULL -> 0
        name == BASE -> 1
        name == SHARED_SIDE -> 2
        name.startsWith("shared_fission_") -> 3
        name.startsWith("fission_") -> 4
        name.contains(BASE) -> 5
        name.startsWith(OVERLAY_PREFIX) -> 6
        else -> Int.MAX_VALUE
    }

    /**
     * Picks the display to mirror onto.
     *
     * Returns null when the unit does not expose the cluster to third-party apps at all, which the
     * caller must treat as "feature unavailable" rather than as an error.
     */
    fun selectDisplayName(names: List<String>, preferredName: String?): String? {
        if (!preferredName.isNullOrEmpty() && preferredName in names) return preferredName
        return names.filter { isClusterDisplayName(it) }.minByOrNull { rank(it) }
    }

    /** True when the name belongs to the DiLink 5.x "shared layer" family. */
    fun isSharedLayer(name: String?): Boolean = name?.startsWith("shared_fission_") == true

    /**
     * Geometry of the area the mirror may occupy.
     *
     *  - 5.x publishes the stock map's own layers, so AUTO keeps the top status strip and the bottom
     *    gear/range strip of the full-map band visible ([MAP_BAND]). Covering them would hide the
     *    speed and gear read-outs, which is a safety problem, not just a cosmetic one.
     *  - 4.0/5.0 publish the projection layer itself, which already excludes the instruments, so
     *    AUTO uses the whole display.
     *  - An unknown display falls back to the whole display: it is what an unverified firmware can
     *    still express unambiguously, and a wrong-but-visible picture beats a silently invisible one.
     *
     * A resolution that is not the measured 1920x720 also falls back to the whole display, because
     * the band offsets are only known for that resolution.
     */
    fun viewport(displayName: String?, width: Int, height: Int, mode: ViewportMode): Viewport {
        if (width <= 0 || height <= 0) return Viewport(0, 0, 0, 0)
        return when (mode) {
            ViewportMode.FULL_BLEED -> Viewport(0, 0, width, height)
            ViewportMode.SIDE_CARD -> sideCard(width, height)
            ViewportMode.MAP_BAND -> mapBand(width, height)
            ViewportMode.AUTO ->
                if (isSharedLayer(displayName)) mapBand(width, height) else Viewport(0, 0, width, height)
        }
    }

    private fun sideCard(width: Int, height: Int): Viewport {
        if (width != KNOWN_WIDTH || height != KNOWN_HEIGHT) return Viewport(0, 0, width, height)
        return Viewport(1320, 0, 600, height)
    }

    private fun mapBand(width: Int, height: Int): Viewport {
        if (width != KNOWN_WIDTH || height != KNOWN_HEIGHT) return Viewport(0, 0, width, height)
        return Viewport(0, 144, width, 480)
    }

    /** Human-readable reason a unit has no cluster mirror, for the settings screen. */
    fun unavailableReason(displayNames: List<String>): String =
        if (displayNames.isEmpty()) {
            "no displays of any kind" // cannot happen on a running device, kept for completeness
        } else {
            "no cluster display among [${displayNames.joinToString(", ")}]"
        }
}
