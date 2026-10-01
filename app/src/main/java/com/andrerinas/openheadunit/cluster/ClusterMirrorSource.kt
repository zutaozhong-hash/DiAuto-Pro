package com.andrerinas.openheadunit.cluster

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.view.IProjectionView

/**
 * Produces the frames the cluster mirror draws.
 *
 * Android Auto hands a head unit exactly **one** video stream, so there is nothing to decode a second
 * time: the cluster copy is sampled from the view that is already showing that stream. The two
 * backends need different mechanisms, and this is the whole reason the grabber is an abstraction.
 *
 * `grab()` is asynchronous. It hands back the [Bitmap] the caller may draw, or null when no frame was
 * available (surface not up yet, projection gone, a device that refuses the copy). Callers must not
 * reuse a returned bitmap for anything else.
 */
sealed class ClusterFrameGrabber {

    /** Short backend name for logs. */
    abstract val label: String

    /** Width in pixels of the bitmaps [grab] returns, or 0 before the first successful grab. */
    abstract val frameWidth: Int

    /** Height in pixels of the bitmaps [grab] returns, or 0 before the first successful grab. */
    abstract val frameHeight: Int

    abstract fun grab(onDone: (Bitmap?) -> Unit)

    open fun release() {}

    companion object {
        /**
         * Builds the grabber for a projection backend, or null when the backend cannot be sampled.
         *
         * Null is a legitimate outcome (e.g. SurfaceView below API 24, where `PixelCopy` does not
         * exist); the controller then reports the feature as unsupported instead of failing.
         */
        fun forView(view: IProjectionView): ClusterFrameGrabber? = when {
            view is TextureView -> TextureViewGrabber(view)
            view is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> SurfaceViewGrabber(view)
            else -> null
        }
    }
}

/**
 * `TextureView` keeps the last frame in its `SurfaceTexture`, so it can hand it back directly.
 *
 * This allocates one bitmap per grab, and that is deliberate: `getBitmap` has no into-bitmap form,
 * and reusing a single bitmap here would let the compositor read a frame while the next one is
 * being sampled. The size is the view's own, which on a DiAuto projection view is the negotiated
 * video size, so the allocation is bounded by what is already on screen.
 */
class TextureViewGrabber(private val view: TextureView) : ClusterFrameGrabber() {

    override val label: String = "texture"

    override val frameWidth: Int get() = view.width

    override val frameHeight: Int get() = view.height

    override fun grab(onDone: (Bitmap?) -> Unit) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0 || !view.isAvailable) {
            onDone(null)
            return
        }
        // getBitmap can throw if the texture is torn down between the check above and the call.
        val bitmap = runCatching { view.getBitmap(width, height) }.getOrElse { error ->
            AppLog.w("Cluster: TextureView.getBitmap failed (${error.message})")
            null
        }
        onDone(bitmap)
    }
}

/**
 * `SurfaceView` (and `GLSurfaceView`, which extends it) exposes no frame getter, so the picture is
 * copied out with `PixelCopy`.
 *
 * The destination bitmap must be exactly the surface's size or `PixelCopy` rejects the request, so
 * two bitmaps are kept and alternated: a frame being written is never the one the view is currently
 * drawing, which is what stops tearing without a per-frame allocation.
 */
class SurfaceViewGrabber(private val view: SurfaceView) : ClusterFrameGrabber() {

    override val label: String = "pixelcopy"

    private val handler = Handler(Looper.getMainLooper())
    private var slots = arrayOfNulls<Bitmap>(SLOT_COUNT)
    private var nextSlot = 0
    private var width = 0
    private var height = 0

    override val frameWidth: Int get() = width

    override val frameHeight: Int get() = height

    override fun grab(onDone: (Bitmap?) -> Unit) {
        val holder = view.holder
        val surface = holder?.surface
        if (surface == null || !surface.isValid) {
            onDone(null)
            return
        }
        val frame = holder.surfaceFrame
        val frameWidth = frame.width()
        val frameHeight = frame.height()
        if (frameWidth <= 0 || frameHeight <= 0) {
            onDone(null)
            return
        }
        if (frameWidth != width || frameHeight != height) {
            // The negotiated video size changed; the old bitmaps can no longer be destinations.
            width = frameWidth
            height = frameHeight
            slots = arrayOfNulls(SLOT_COUNT)
            nextSlot = 0
        }
        val slot = nextSlot
        nextSlot = (nextSlot + 1) % SLOT_COUNT
        val target = slots[slot] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            slots[slot] = it
        }
        val request = runCatching {
            PixelCopy.request(view, target, { result ->
                if (result == PixelCopy.SUCCESS) {
                    onDone(target)
                } else {
                    AppLog.w("Cluster: PixelCopy returned $result")
                    onDone(null)
                }
            }, handler)
        }
        request.onFailure { error ->
            AppLog.w("Cluster: PixelCopy request rejected (${error.message})")
            onDone(null)
        }
    }

    override fun release() {
        slots.fill(null)
        width = 0
        height = 0
    }

    private companion object {
        const val SLOT_COUNT = 2
    }
}
