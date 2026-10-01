package com.andrerinas.openheadunit.cluster

/**
 * Maps a decoded Android Auto frame onto the cluster viewport.
 *
 * Pure arithmetic, deliberately free of `android.graphics` so it can be unit-tested on the JVM and
 * so the same numbers can be logged and reasoned about without a device. The view applies the
 * result with one `Canvas.drawBitmap(bitmap, srcRect, dstRect, paint)`.
 *
 * Two independent knobs decide what ends up on the cluster:
 *
 *  1. **which part of the phone's picture to take** ([Request.sourceLeftPercent] … `sourceBottomPercent`),
 *     which is how "only the map, not Android Auto's status and navigation bars" is expressed;
 *  2. **how that piece is placed in the viewport** ([Request.fit], [Request.zoomPercent],
 *     [Request.panXPercent]/[Request.panYPercent]).
 */
object ClusterCropPolicy {

    enum class Fit {
        /** Whole source region visible; bars are added on the short axis. */
        FIT_CENTER,

        /** Viewport fully covered; the overflowing part of the source region is clipped away. */
        CENTER_CROP,
    }

    /**
     * A source rectangle in bitmap pixels and the destination rectangle it is drawn into, both in
     * the viewport's own coordinate space.
     */
    data class Placement(
        val sourceLeft: Float,
        val sourceTop: Float,
        val sourceRight: Float,
        val sourceBottom: Float,
        val destLeft: Float,
        val destTop: Float,
        val destRight: Float,
        val destBottom: Float,
    ) {
        val sourceWidth: Float get() = sourceRight - sourceLeft
        val sourceHeight: Float get() = sourceBottom - sourceTop
        val destWidth: Float get() = destRight - destLeft
        val destHeight: Float get() = destBottom - destTop

        val isUsable: Boolean
            get() = sourceWidth > 0f && sourceHeight > 0f && destWidth > 0f && destHeight > 0f

        companion object {
            val EMPTY = Placement(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    data class Request(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val sourceLeftPercent: Float = 0f,
        val sourceTopPercent: Float = 0f,
        val sourceRightPercent: Float = 100f,
        val sourceBottomPercent: Float = 100f,
        val zoomPercent: Int = 100,
        val panXPercent: Int = 0,
        val panYPercent: Int = 0,
        val fit: Fit = Fit.FIT_CENTER,
    )

    /** Smallest source region we accept, as a fraction of the frame, to keep the maths invertible. */
    private const val MIN_SPAN_PERCENT = 1f
    private const val MIN_ZOOM_PERCENT = 10
    private const val MAX_ZOOM_PERCENT = 400

    fun compute(request: Request): Placement {
        if (request.sourceWidth <= 0 || request.sourceHeight <= 0) return Placement.EMPTY
        if (request.viewportWidth <= 0 || request.viewportHeight <= 0) return Placement.EMPTY

        val sourceWidth = request.sourceWidth.toFloat()
        val sourceHeight = request.sourceHeight.toFloat()
        val viewportWidth = request.viewportWidth.toFloat()
        val viewportHeight = request.viewportHeight.toFloat()

        val left = clamp(request.sourceLeftPercent, 0f, 100f - MIN_SPAN_PERCENT)
        val top = clamp(request.sourceTopPercent, 0f, 100f - MIN_SPAN_PERCENT)
        val right = clamp(request.sourceRightPercent, left + MIN_SPAN_PERCENT, 100f)
        val bottom = clamp(request.sourceBottomPercent, top + MIN_SPAN_PERCENT, 100f)

        val sourceLeft = left / 100f * sourceWidth
        val sourceTop = top / 100f * sourceHeight
        val sourceRight = right / 100f * sourceWidth
        val sourceBottom = bottom / 100f * sourceHeight
        val regionWidth = sourceRight - sourceLeft
        val regionHeight = sourceBottom - sourceTop
        if (regionWidth <= 0f || regionHeight <= 0f) return Placement.EMPTY

        val zoom = clampZoom(request.zoomPercent)

        val fitScale = when (request.fit) {
            Fit.FIT_CENTER -> minOf(viewportWidth / regionWidth, viewportHeight / regionHeight)
            Fit.CENTER_CROP -> maxOf(viewportWidth / regionWidth, viewportHeight / regionHeight)
        }
        val scale = fitScale * (zoom / 100f)
        val destWidth = regionWidth * scale
        val destHeight = regionHeight * scale
        if (destWidth <= 0f || destHeight <= 0f) return Placement.EMPTY

        // Centre the region in the viewport, then pan. Pan is a fraction of the viewport so the same
        // setting means the same thing on an 8" and a 12" cluster.
        val panX = clamp(request.panXPercent.toFloat(), -100f, 100f) / 100f * viewportWidth
        val panY = clamp(request.panYPercent.toFloat(), -100f, 100f) / 100f * viewportHeight
        val destLeft = (viewportWidth - destWidth) / 2f + panX
        val destTop = (viewportHeight - destHeight) / 2f + panY

        return Placement(
            sourceLeft = sourceLeft,
            sourceTop = sourceTop,
            sourceRight = sourceRight,
            sourceBottom = sourceBottom,
            destLeft = destLeft,
            destTop = destTop,
            destRight = destLeft + destWidth,
            destBottom = destTop + destHeight,
        )
    }

    /**
     * Fraction of the destination rectangle that actually falls inside the viewport.
     *
     * Used only for logging: a [Fit.CENTER_CROP] placement with a high zoom can legitimately be far
     * below 1.0, but a value near zero means the pan sliders are pointing at empty space.
     */
    fun visibleFraction(placement: Placement, viewportWidth: Int, viewportHeight: Int): Float {
        if (!placement.isUsable || viewportWidth <= 0 || viewportHeight <= 0) return 0f
        val overlapWidth = minOf(placement.destRight, viewportWidth.toFloat()) -
            maxOf(placement.destLeft, 0f)
        val overlapHeight = minOf(placement.destBottom, viewportHeight.toFloat()) -
            maxOf(placement.destTop, 0f)
        if (overlapWidth <= 0f || overlapHeight <= 0f) return 0f
        val destArea = placement.destWidth * placement.destHeight
        if (destArea <= 0f) return 0f
        return (overlapWidth * overlapHeight) / destArea
    }

    private fun clampZoom(zoomPercent: Int): Int =
        zoomPercent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

    private fun clamp(value: Float, min: Float, max: Float): Float =
        if (value.isNaN()) min else value.coerceIn(min, max)
}
