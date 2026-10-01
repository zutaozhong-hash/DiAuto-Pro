package com.andrerinas.openheadunit.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Finds the BYD instrument-cluster projection display.
 *
 * BYD publishes the cluster's projection area as a **public presentation display**, which is the
 * only reason a third-party app can draw there at all. `getDisplays(DISPLAY_CATEGORY_PRESENTATION)`
 * is therefore the correct query: it returns exactly the displays the window manager will let us
 * put a window on, so a display we cannot use never becomes a candidate in the first place.
 *
 * The name-based matching itself lives in [ClusterDisplayPolicy] so it can be tested without a
 * device.
 */
object ClusterDisplayLocator {

    /** A usable cluster candidate. */
    data class Candidate(val display: Display, val name: String)

    /**
     * Displays the window manager would let us present on, cluster ones first.
     *
     * An empty result is a normal outcome on a unit whose cluster is not shared — the caller must
     * treat it as "feature unavailable" and stay silent rather than retrying aggressively.
     */
    fun presentationCandidates(context: Context): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return emptyList()
        val manager = displayManager(context) ?: return emptyList()
        val displays = runCatching {
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.toList().orEmpty()
        }.getOrElse { error ->
            AppLog.w("Cluster: presentation displays unavailable (${error.message})")
            emptyList()
        }
        return displays.map { Candidate(it, it.name ?: "") }
    }

    /**
     * Every display the device reports, for diagnostics and for the explicit-displayId escape hatch.
     *
     * A display listed here but absent from [presentationCandidates] is one the window manager keeps
     * to itself; naming it explicitly will not make it usable, it only makes the failure diagnosable.
     */
    fun allCandidates(context: Context): List<Candidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return emptyList()
        val manager = displayManager(context) ?: return emptyList()
        val displays = runCatching { manager.displays?.toList().orEmpty() }.getOrElse { emptyList() }
        return displays.map { Candidate(it, it.name ?: "") }
    }

    /**
     * The cluster display to use, or null when the unit does not expose one.
     *
     * @param preferredName exact display name pinned by the user; wins over the automatic order when
     *   that exact display is present, so an advanced user can always override the heuristic.
     * @param explicitDisplayId display id pinned by the user, or -1. Searched only among the
     *   presentation candidates, so it cannot be used to reach a display we are not allowed to use.
     */
    fun find(context: Context, preferredName: String?, explicitDisplayId: Int): Candidate? {
        val presentations = presentationCandidates(context)
        if (explicitDisplayId >= 0) {
            presentations.firstOrNull { it.display.displayId == explicitDisplayId }?.let { return it }
            AppLog.w(
                "Cluster: displayId=$explicitDisplayId was pinned but is not a presentation display; " +
                    "available=${describe(presentations)}"
            )
        }
        val selected = ClusterDisplayPolicy.selectDisplayName(presentations.map { it.name }, preferredName)
            ?: run {
                AppLog.i("Cluster: no cluster display among presentations=${describe(presentations)}")
                return null
            }
        return presentations.firstOrNull { it.name == selected }
    }

    /** `displayId:name` for every display of the given list, for one-line diagnostics. */
    fun describe(candidates: List<Candidate>): String =
        if (candidates.isEmpty()) "none" else candidates.joinToString(",") { "${it.display.displayId}:${it.name}" }

    /** `displayId:name` for every display on the device, including ones we may not present on. */
    fun describeAll(context: Context): String = describe(allCandidates(context))

    /** `displayId:name(WxH)` for logs; size is best-effort because a removed display can race here. */
    fun describeDetailed(context: Context): String {
        val candidates = presentationCandidates(context)
        if (candidates.isEmpty()) return "none"
        return candidates.joinToString(",") { candidate ->
            val size = sizeOf(candidate.display)
            "${candidate.display.displayId}:${candidate.name}(${size.first}x${size.second})"
        }
    }

    @Suppress("DEPRECATION")
    fun sizeOf(display: Display): Pair<Int, Int> {
        val point = android.graphics.Point()
        return runCatching {
            display.getRealSize(point)
            point.x to point.y
        }.getOrElse {
            display.width to display.height
        }
    }

    private fun displayManager(context: Context): DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
}
