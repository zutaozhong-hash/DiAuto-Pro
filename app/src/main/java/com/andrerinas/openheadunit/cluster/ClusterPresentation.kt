package com.andrerinas.openheadunit.cluster

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Carries the mirror on the cluster display via a `Presentation`.
 *
 * This is the preferred route: no manifest entry, no task to keep alive, and lifetime tied to the
 * window itself. It works because BYD publishes the cluster area as a **public** presentation
 * display, so the window manager accepts an app window on it without any BYD permission.
 *
 * The window is deliberately transparent. On firmwares where the projection layer also carries the
 * stock instrument panels (speed, gear, range), an opaque window would black them out; a transparent
 * one leaves every pixel this app does not draw exactly as the cluster drew it.
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
class ClusterPresentation(
    context: Context,
    display: Display,
    viewport: ClusterDisplayPolicy.Viewport,
) : Presentation(context, display) {

    val mirrorView = ClusterMirrorView(context)

    init {
        mirrorView.setViewport(viewport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                mirrorView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        setContentView(root)
    }

    /**
     * Shows the window, reporting whether the window manager accepted it.
     *
     * `show()` throws when the display stopped being usable between discovery and this call (the
     * cluster switching away, the display being removed, a firmware that only exposes the layer to
     * system apps). The caller treats false as "try the fallback carrier" rather than as a crash.
     */
    fun tryShow(): Boolean = runCatching {
        show()
        true
    }.getOrElse { error ->
        AppLog.w("Cluster: presentation rejected on display ${display.displayId} (${error.javaClass.simpleName}: ${error.message})")
        false
    }

    /**
     * The cluster can take the layer back at any time (the stock map starting, the vehicle leaving
     * the projection screen). Forwarding that lets the controller release the pump and the surface
     * instead of discovering it only through an endless run of failed `PixelCopy` requests.
     */
    override fun onStop() {
        ClusterProjectionController.onSinkLost(mirrorView)
        super.onStop()
    }
}
