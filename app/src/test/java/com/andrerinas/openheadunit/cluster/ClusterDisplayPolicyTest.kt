package com.andrerinas.openheadunit.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display-name matching and viewport geometry are the part of the cluster mirror that decides
 * whether the picture lands on the instrument cluster or on some unrelated virtual display, so they
 * are kept pure and pinned here. Every name in this file is one that was actually read off a unit
 * or out of the stock APKs; none of them is invented.
 */
class ClusterDisplayPolicyTest {

    private val base = "fission_bg_XDJAScreenProjection"
    private val sharedFull = "shared_fission_bg_XDJAScreenProjection_0"
    private val sharedSide = "shared_fission_bg_XDJAScreenProjection_1"

    // ------------------------------------------------------------------ name matching

    @Test
    fun `every name family BYD publishes the cluster under is recognised`() {
        // DiLink 4.0 / 5.0 project the stock layer under its own name.
        assertTrue(ClusterDisplayPolicy.isClusterDisplayName(base))
        // DiLink 5.1 hides that layer from third parties and publishes these two siblings instead.
        assertTrue(ClusterDisplayPolicy.isClusterDisplayName(sharedFull))
        assertTrue(ClusterDisplayPolicy.isClusterDisplayName(sharedSide))
        // The stock map's own predicate is startsWith("fission_"), so any other suffix counts too.
        assertTrue(ClusterDisplayPolicy.isClusterDisplayName("fission_something_else"))
        // Last-resort name the official map falls back to on firmwares that publish nothing else.
        assertTrue(ClusterDisplayPolicy.isClusterDisplayName("叠加视图 #1"))
    }

    @Test
    fun `ordinary displays are not mistaken for the cluster`() {
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName("内置屏幕"))
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName("Built-in Screen"))
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName("com.byd.containerservice"))
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName(""))
        // A substring hit needs the exact base name, not just a fragment of it.
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName("XDJAScreenProjection"))
    }

    @Test
    fun `a null or absent name never matches`() {
        assertFalse(ClusterDisplayPolicy.isClusterDisplayName(null))
    }

    @Test
    fun `the shared layer family is distinguished from the older single layer`() {
        assertTrue(ClusterDisplayPolicy.isSharedLayer(sharedFull))
        assertTrue(ClusterDisplayPolicy.isSharedLayer(sharedSide))
        assertFalse(ClusterDisplayPolicy.isSharedLayer(base))
        assertFalse(ClusterDisplayPolicy.isSharedLayer("叠加视图 #1"))
        assertFalse(ClusterDisplayPolicy.isSharedLayer(null))
    }

    // ------------------------------------------------------------------ ranking

    @Test
    fun `the full-map layer outranks everything and the overlay layer is the last resort`() {
        assertEquals(0, ClusterDisplayPolicy.rank(sharedFull))
        assertEquals(1, ClusterDisplayPolicy.rank(base))
        assertEquals(2, ClusterDisplayPolicy.rank(sharedSide))
        // Unknown siblings still beat the overlay fallback, whose geometry is undocumented.
        assertEquals(3, ClusterDisplayPolicy.rank("shared_fission_whatever"))
        assertEquals(4, ClusterDisplayPolicy.rank("fission_whatever"))
        assertEquals(6, ClusterDisplayPolicy.rank("叠加视图 #2"))
        assertEquals(Int.MAX_VALUE, ClusterDisplayPolicy.rank("内置屏幕"))
    }

    @Test
    fun `a name containing the base but not starting with it still ranks below the real families`() {
        assertEquals(5, ClusterDisplayPolicy.rank("overlay_${base}_extra"))
    }

    @Test
    fun `ranking orders the layers the way the picker relies on`() {
        val ordered = listOf("叠加视图 #1", base, sharedSide, sharedFull, "fission_x", "内置屏幕")
            .sortedBy { ClusterDisplayPolicy.rank(it) }
        assertEquals(
            listOf(sharedFull, base, sharedSide, "fission_x", "叠加视图 #1", "内置屏幕"),
            ordered,
        )
    }

    // ------------------------------------------------------------------ picking

    @Test
    fun `the highest ranked cluster display is picked automatically`() {
        assertEquals(
            sharedFull,
            ClusterDisplayPolicy.selectDisplayName(listOf(sharedSide, sharedFull, base), null),
        )
        assertEquals(
            base,
            ClusterDisplayPolicy.selectDisplayName(listOf("叠加视图 #1", base), null),
        )
    }

    @Test
    fun `an explicit preference wins over the automatic order when that display is present`() {
        // The escape hatch has to work even for the "worse" layer: the user may have measured that
        // the side card is the one that composites correctly on their firmware.
        assertEquals(
            sharedSide,
            ClusterDisplayPolicy.selectDisplayName(listOf(sharedFull, sharedSide), sharedSide),
        )
    }

    @Test
    fun `a preference for a display that is not there falls back to the automatic order`() {
        assertEquals(
            sharedFull,
            ClusterDisplayPolicy.selectDisplayName(listOf(sharedSide, sharedFull), "gone"),
        )
        // An empty preference is the same as none, so the stored default must not shadow the scan.
        assertEquals(
            sharedFull,
            ClusterDisplayPolicy.selectDisplayName(listOf(sharedSide, sharedFull), ""),
        )
    }

    @Test
    fun `a unit that shares no cluster display selects nothing`() {
        assertNull(ClusterDisplayPolicy.selectDisplayName(listOf("内置屏幕", "HDMI"), null))
        assertNull(ClusterDisplayPolicy.selectDisplayName(emptyList(), null))
        assertNull(ClusterDisplayPolicy.selectDisplayName(emptyList(), sharedFull))
    }

    // ------------------------------------------------------------------ viewport

    @Test
    fun `on the shared layer AUTO keeps the instrument band clear`() {
        // 1920x720 with 144 px of status strip on top and 96 px of gear/range strip at the bottom.
        // Covering those is a safety problem, not a cosmetic one.
        val band = ClusterDisplayPolicy.viewport(
            sharedFull,
            1920,
            720,
            ClusterDisplayPolicy.ViewportMode.AUTO,
        )
        assertEquals(ClusterDisplayPolicy.Viewport(0, 144, 1920, 480), band)
    }

    @Test
    fun `on the older single layer AUTO uses the whole display`() {
        // DiLink 4.0/5.0 publish the projection layer itself, which already excludes the
        // instruments, so there is nothing to protect.
        val full = ClusterDisplayPolicy.viewport(
            base,
            1920,
            720,
            ClusterDisplayPolicy.ViewportMode.AUTO,
        )
        assertEquals(ClusterDisplayPolicy.Viewport(0, 0, 1920, 720), full)
    }

    @Test
    fun `AUTO falls back to the whole display for an unknown name`() {
        val full = ClusterDisplayPolicy.viewport(
            "叠加视图 #1",
            1920,
            720,
            ClusterDisplayPolicy.ViewportMode.AUTO,
        )
        assertEquals(ClusterDisplayPolicy.Viewport(0, 0, 1920, 720), full)
    }

    @Test
    fun `the explicit modes ignore which layer the display is`() {
        val expectedBand = ClusterDisplayPolicy.Viewport(0, 144, 1920, 480)
        // The older layer gets the band too when the user asks for it, which is the point of the
        // explicit modes: the heuristic is a default, not a constraint.
        assertEquals(expectedBand, ClusterDisplayPolicy.viewport(base, 1920, 720, ClusterDisplayPolicy.ViewportMode.MAP_BAND))
        assertEquals(expectedBand, ClusterDisplayPolicy.viewport(sharedSide, 1920, 720, ClusterDisplayPolicy.ViewportMode.MAP_BAND))

        val expectedSide = ClusterDisplayPolicy.Viewport(1320, 0, 600, 720)
        assertEquals(expectedSide, ClusterDisplayPolicy.viewport(sharedFull, 1920, 720, ClusterDisplayPolicy.ViewportMode.SIDE_CARD))

        val expectedFullBleed = ClusterDisplayPolicy.Viewport(0, 0, 1920, 720)
        assertEquals(expectedFullBleed, ClusterDisplayPolicy.viewport(sharedFull, 1920, 720, ClusterDisplayPolicy.ViewportMode.FULL_BLEED))
    }

    @Test
    fun `the band offsets are only applied at the resolution they were measured at`() {
        // A firmware reporting anything other than 1920x720 gets the whole display, because the
        // 144/96 px strips are the only numbers we actually know and guessing them would clip the
        // instruments on exactly the unit we know least about.
        val band = ClusterDisplayPolicy.viewport(sharedFull, 2560, 720, ClusterDisplayPolicy.ViewportMode.MAP_BAND)
        assertEquals(ClusterDisplayPolicy.Viewport(0, 0, 2560, 720), band)

        val side = ClusterDisplayPolicy.viewport(sharedFull, 1280, 480, ClusterDisplayPolicy.ViewportMode.SIDE_CARD)
        assertEquals(ClusterDisplayPolicy.Viewport(0, 0, 1280, 480), side)
    }

    @Test
    fun `a degenerate display size produces an empty viewport the caller must not open`() {
        val zero = ClusterDisplayPolicy.viewport(sharedFull, 0, 720, ClusterDisplayPolicy.ViewportMode.FULL_BLEED)
        assertEquals(ClusterDisplayPolicy.Viewport(0, 0, 0, 0), zero)
        assertTrue(zero.isEmpty)

        val negative = ClusterDisplayPolicy.viewport(sharedFull, -1, -1, ClusterDisplayPolicy.ViewportMode.MAP_BAND)
        assertTrue(negative.isEmpty)

        assertFalse(ClusterDisplayPolicy.Viewport(0, 144, 1920, 480).isEmpty)
    }

    @Test
    fun `viewport edges are derived from the origin and size`() {
        val viewport = ClusterDisplayPolicy.Viewport(1320, 144, 600, 480)
        assertEquals(1920, viewport.right)
        assertEquals(624, viewport.bottom)
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    fun `the unavailable reason distinguishes a device with no displays from one without a cluster`() {
        assertEquals("no displays of any kind", ClusterDisplayPolicy.unavailableReason(emptyList()))
        val reason = ClusterDisplayPolicy.unavailableReason(listOf("内置屏幕"))
        assertTrue(reason.contains("内置屏幕"))
    }
}
