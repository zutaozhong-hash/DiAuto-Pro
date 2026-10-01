package com.andrerinas.openheadunit.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop arithmetic decides what the driver actually sees on the cluster, and it is the part that
 * is easiest to get subtly wrong (a flipped axis, a pan in source pixels instead of viewport pixels,
 * a zoom applied before the fit instead of after). It is pure precisely so those cases can be pinned
 * here rather than discovered on a moving car.
 */
class ClusterCropPolicyTest {

    private val delta = 0.01f

    private fun request(
        sourceWidth: Int = 1000,
        sourceHeight: Int = 500,
        viewportWidth: Int = 400,
        viewportHeight: Int = 400,
        sourceLeftPercent: Float = 0f,
        sourceTopPercent: Float = 0f,
        sourceRightPercent: Float = 100f,
        sourceBottomPercent: Float = 100f,
        zoomPercent: Int = 100,
        panXPercent: Int = 0,
        panYPercent: Int = 0,
        fit: ClusterCropPolicy.Fit = ClusterCropPolicy.Fit.FIT_CENTER,
    ) = ClusterCropPolicy.Request(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        sourceLeftPercent = sourceLeftPercent,
        sourceTopPercent = sourceTopPercent,
        sourceRightPercent = sourceRightPercent,
        sourceBottomPercent = sourceBottomPercent,
        zoomPercent = zoomPercent,
        panXPercent = panXPercent,
        panYPercent = panYPercent,
        fit = fit,
    )

    // ------------------------------------------------------------------ degenerate inputs

    @Test
    fun `a frame or viewport with no area yields nothing to draw`() {
        assertEquals(ClusterCropPolicy.Placement.EMPTY, ClusterCropPolicy.compute(request(sourceWidth = 0)))
        assertEquals(ClusterCropPolicy.Placement.EMPTY, ClusterCropPolicy.compute(request(sourceHeight = 0)))
        assertEquals(ClusterCropPolicy.Placement.EMPTY, ClusterCropPolicy.compute(request(viewportWidth = 0)))
        assertEquals(ClusterCropPolicy.Placement.EMPTY, ClusterCropPolicy.compute(request(viewportHeight = 0)))
    }

    @Test
    fun `an empty placement is reported as unusable with no extent`() {
        val empty = ClusterCropPolicy.Placement.EMPTY
        assertFalse(empty.isUsable)
        assertEquals(0f, empty.sourceWidth, delta)
        assertEquals(0f, empty.destHeight, delta)
        assertEquals(0f, ClusterCropPolicy.visibleFraction(empty, 400, 400), delta)
    }

    // ------------------------------------------------------------------ fit

    @Test
    fun `FIT_CENTER shows the whole frame and letterboxes the short axis`() {
        // A 2:1 phone frame into a square viewport: the width is the binding constraint, so the
        // picture is full width and the bars end up above and below.
        val placement = ClusterCropPolicy.compute(request())
        assertEquals(0f, placement.sourceLeft, delta)
        assertEquals(0f, placement.sourceTop, delta)
        assertEquals(1000f, placement.sourceRight, delta)
        assertEquals(500f, placement.sourceBottom, delta)

        assertEquals(0f, placement.destLeft, delta)
        assertEquals(100f, placement.destTop, delta)
        assertEquals(400f, placement.destRight, delta)
        assertEquals(300f, placement.destBottom, delta)
        assertEquals(1f, ClusterCropPolicy.visibleFraction(placement, 400, 400), delta)
    }

    @Test
    fun `CENTER_CROP fills the viewport and pushes the overflow off both sides`() {
        // Same frame, but now the height is the binding constraint: the picture is taller than the
        // viewport in the scaled sense, so the sides are cut rather than the picture being shrunk.
        val placement = ClusterCropPolicy.compute(request(fit = ClusterCropPolicy.Fit.CENTER_CROP))
        assertEquals(800f, placement.destWidth, delta)
        assertEquals(400f, placement.destHeight, delta)
        assertEquals(-200f, placement.destLeft, delta)
        assertEquals(0f, placement.destTop, delta)
        assertEquals(600f, placement.destRight, delta)
        assertEquals(400f, placement.destBottom, delta)

        // Half of the scaled width is off-screen, which is the documented consequence of cropping.
        assertEquals(0.5f, ClusterCropPolicy.visibleFraction(placement, 400, 400), delta)
    }

    @Test
    fun `an already matching aspect ratio fills the viewport either way`() {
        val fit = ClusterCropPolicy.compute(
            request(sourceWidth = 1000, sourceHeight = 1000, fit = ClusterCropPolicy.Fit.FIT_CENTER)
        )
        val crop = ClusterCropPolicy.compute(
            request(sourceWidth = 1000, sourceHeight = 1000, fit = ClusterCropPolicy.Fit.CENTER_CROP)
        )
        assertEquals(fit, crop)
        assertEquals(400f, fit.destWidth, delta)
        assertEquals(400f, fit.destHeight, delta)
        assertEquals(1f, ClusterCropPolicy.visibleFraction(fit, 400, 400), delta)
    }

    // ------------------------------------------------------------------ zoom

    @Test
    fun `zoom scales about the centre of the viewport`() {
        val placement = ClusterCropPolicy.compute(
            request(sourceWidth = 1000, sourceHeight = 1000, zoomPercent = 200)
        )
        assertEquals(800f, placement.destWidth, delta)
        assertEquals(800f, placement.destHeight, delta)
        assertEquals(-200f, placement.destLeft, delta)
        assertEquals(-200f, placement.destTop, delta)
        assertEquals(0.25f, ClusterCropPolicy.visibleFraction(placement, 400, 400), delta)
    }

    @Test
    fun `zoom is clamped to the supported range instead of being trusted`() {
        // 1% would make the picture a handful of pixels; the floor catches a mistyped settings value
        // from a backup before it can produce an invisible mirror.
        val zoomedOut = ClusterCropPolicy.compute(
            request(sourceWidth = 1000, sourceHeight = 1000, zoomPercent = 1)
        )
        assertEquals(40f, zoomedOut.destWidth, delta)
        assertEquals(180f, zoomedOut.destLeft, delta)

        // 9999% would ask for a bitmap far larger than the viewport and a huge scale factor.
        val zoomedIn = ClusterCropPolicy.compute(
            request(sourceWidth = 1000, sourceHeight = 1000, zoomPercent = 9999)
        )
        assertEquals(1600f, zoomedIn.destWidth, delta)
        assertEquals(-600f, zoomedIn.destLeft, delta)
    }

    // ------------------------------------------------------------------ pan

    @Test
    fun `pan is a fraction of the viewport, not of the frame or the destination`() {
        // This is what makes the same stored value mean the same thing on an 8" and a 12" cluster:
        // 50% is half the viewport either way, whatever the source resolution happens to be.
        val placement = ClusterCropPolicy.compute(request(panXPercent = 50, panYPercent = -25))
        // FIT_CENTER base position is (0, 100); pan adds (0.5 * 400, -0.25 * 400).
        assertEquals(200f, placement.destLeft, delta)
        assertEquals(0f, placement.destTop, delta)
    }

    @Test
    fun `pan is clamped so the picture can never be sent entirely off the viewport`() {
        val placement = ClusterCropPolicy.compute(request(panXPercent = 1000))
        assertEquals(400f, placement.destLeft, delta)
        assertEquals(0f, ClusterCropPolicy.visibleFraction(placement, 400, 400), delta)
    }

    // ------------------------------------------------------------------ source cropping

    @Test
    fun `cropping the source region suits the picture to the viewport before the fit runs`() {
        // Trimming Android Auto's own status and navigation bars off the top and bottom is the
        // stated reason this knob exists, so it has to be applied to the source pixels.
        val placement = ClusterCropPolicy.compute(
            request(
                sourceWidth = 1000,
                sourceHeight = 1000,
                sourceTopPercent = 20f,
                sourceBottomPercent = 80f,
            )
        )
        assertEquals(200f, placement.sourceTop, delta)
        assertEquals(800f, placement.sourceBottom, delta)
        assertEquals(1000f, placement.sourceWidth, delta)
        assertEquals(600f, placement.sourceHeight, delta)

        // The cropped region is 1000x600, so its own fit is width-bound and centres vertically.
        assertEquals(400f, placement.destWidth, delta)
        assertEquals(240f, placement.destHeight, delta)
        assertEquals(0f, placement.destLeft, delta)
        assertEquals(80f, placement.destTop, delta)
    }

    @Test
    fun `a crop that would collapse the region is widened to the minimum span`() {
        // A user dragging a slider to a point value must not produce a zero-width source rectangle,
        // which `Canvas.drawBitmap` rejects outright.
        val placement = ClusterCropPolicy.compute(
            request(
                sourceWidth = 1000,
                sourceHeight = 1000,
                sourceLeftPercent = 50f,
                sourceRightPercent = 50f,
            )
        )
        assertTrue(placement.isUsable)
        assertEquals(10f, placement.sourceWidth, delta)
        assertEquals(500f, placement.sourceLeft, delta)
        assertEquals(510f, placement.sourceRight, delta)
    }

    @Test
    fun `source percentages are clamped into the frame`() {
        val placement = ClusterCropPolicy.compute(
            request(
                sourceWidth = 1000,
                sourceHeight = 1000,
                sourceLeftPercent = -50f,
                sourceTopPercent = 500f,
            )
        )
        assertEquals(0f, placement.sourceLeft, delta)
        // 500% is pulled back to the last whole percent that still leaves the minimum span.
        assertEquals(990f, placement.sourceTop, delta)
        assertEquals(1000f, placement.sourceRight, delta)
        assertEquals(1000f, placement.sourceBottom, delta)
        assertTrue(placement.isUsable)
    }

    @Test
    fun `an inverted crop order is repaired rather than producing a negative region`() {
        val placement = ClusterCropPolicy.compute(
            request(
                sourceWidth = 1000,
                sourceHeight = 1000,
                sourceLeftPercent = 80f,
                sourceRightPercent = 20f,
            )
        )
        assertTrue(placement.isUsable)
        assertEquals(800f, placement.sourceLeft, delta)
        assertEquals(810f, placement.sourceRight, delta)
    }

    // ------------------------------------------------------------------ visibleFraction

    @Test
    fun `visibleFraction is zero for a degenerate viewport`() {
        val placement = ClusterCropPolicy.compute(request())
        assertEquals(0f, ClusterCropPolicy.visibleFraction(placement, 0, 400), delta)
        assertEquals(0f, ClusterCropPolicy.visibleFraction(placement, 400, 0), delta)
    }

    @Test
    fun `visibleFraction counts only the overlap area, not the placement extents`() {
        // A placement panned half off the left edge: half the width is left visible, the height is
        // entirely visible, so the fraction is exactly one half.
        val placement = ClusterCropPolicy.compute(request(panXPercent = -50))
        assertEquals(-200f, placement.destLeft, delta)
        assertEquals(0.5f, ClusterCropPolicy.visibleFraction(placement, 400, 400), delta)
    }

    // ------------------------------------------------------------------ placement type

    @Test
    fun `a real placement reports its extents and is usable`() {
        val placement = ClusterCropPolicy.compute(request())
        assertTrue(placement.isUsable)
        assertEquals(1000f, placement.sourceWidth, delta)
        assertEquals(500f, placement.sourceHeight, delta)
        assertEquals(400f, placement.destWidth, delta)
        assertEquals(200f, placement.destHeight, delta)
    }
}
