package com.andrerinas.openheadunit.cluster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Draws the mirrored Android Auto frame inside one rectangle of a cluster window.
 *
 * The view always fills the whole cluster window; [viewport] is the sub-rectangle the mirror is
 * allowed to occupy. Keeping the two separate is what lets the window be transparent, so on a
 * firmware whose projection layer also carries the stock instrument panels those panels stay
 * visible — a full-screen window with a full-screen picture would black them out.
 *
 * Everything outside [viewport] is clipped away, so a low zoom or an aggressive pan shows as letter
 * boxing rather than as picture spilling over the instruments.
 */
class ClusterMirrorView(context: Context) : View(context) {

    private var viewport = ClusterDisplayPolicy.Viewport(0, 0, 0, 0)
    private var placement = ClusterCropPolicy.Placement.EMPTY
    private var frame: Bitmap? = null

    // FILTER_BITMAP_FLAG matters: the source is nearly always a different size from the viewport, so
    // the draw is a scaling draw and without filtering the map text turns to aliased mush.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val sourceRect = Rect()
    private val destRect = RectF()

    /** Number of frames actually put on the cluster; read by the controller for its rate log. */
    @Volatile
    var drawnFrames: Long = 0L
        private set

    fun setViewport(value: ClusterDisplayPolicy.Viewport) {
        if (viewport == value) return
        viewport = value
        invalidate()
    }

    /** Hands over the next frame. The bitmap is owned by the caller's grabber and may be reused. */
    fun submit(bitmap: Bitmap, crop: ClusterCropPolicy.Placement) {
        frame = bitmap
        placement = crop
        invalidate()
    }

    /** Drops the picture without destroying the view, so the window can stay alpha-hidden and cheap. */
    fun clear() {
        if (frame == null && placement === ClusterCropPolicy.Placement.EMPTY) return
        frame = null
        placement = ClusterCropPolicy.Placement.EMPTY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        val crop = placement
        if (!crop.isUsable || viewport.isEmpty || bitmap.isRecycled) return
        // A zero-extent bitmap would make the clamps below empty ranges, and both `coerceIn` and
        // `drawBitmap` throw on those. The grabbers never hand one back, but this is a draw path.
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        // drawBitmap throws unless the source rectangle is a non-empty subset of the bitmap, and the
        // crop maths works in floats, so the rectangle is clamped rather than trusted.
        val maxX = bitmap.width.toFloat()
        val maxY = bitmap.height.toFloat()
        val left = crop.sourceLeft.coerceIn(0f, maxX - 1f).toInt()
        val top = crop.sourceTop.coerceIn(0f, maxY - 1f).toInt()
        val right = crop.sourceRight.coerceIn(left + 1f, maxX).toInt()
        val bottom = crop.sourceBottom.coerceIn(top + 1f, maxY).toInt()
        sourceRect.set(left, top, right, bottom)

        destRect.set(
            viewport.left + crop.destLeft,
            viewport.top + crop.destTop,
            viewport.left + crop.destRight,
            viewport.top + crop.destBottom,
        )

        val save = canvas.save()
        canvas.clipRect(
            viewport.left.toFloat(),
            viewport.top.toFloat(),
            viewport.right.toFloat(),
            viewport.bottom.toFloat(),
        )
        canvas.drawBitmap(bitmap, sourceRect, destRect, paint)
        canvas.restoreToCount(save)
        drawnFrames++
    }
}
