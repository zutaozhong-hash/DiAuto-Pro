package com.andrerinas.openheadunit.cluster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Fallback carrier for the mirror: a real activity launched onto the cluster display.
 *
 * Needed because `Presentation` is not guaranteed to composite on every firmware. DiPlay's working
 * DiLink 4.0 build does not use a presentation at all — it starts its own activity with
 * `setLaunchDisplayId` — so both carriers are kept and the controller tries them in order.
 *
 * The viewport travels in the intent rather than in a process-global so a relaunched activity still
 * lays itself out correctly, and so a stale global can never put the picture in the wrong band.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ClusterHostActivity : Activity() {

    private var mirror: ClusterMirrorView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same reasoning as ClusterPresentation: the stock instrument panels must survive underneath.
        window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val view = ClusterMirrorView(this)
        view.setViewport(
            ClusterDisplayPolicy.Viewport(
                left = intent.getIntExtra(EXTRA_LEFT, 0),
                top = intent.getIntExtra(EXTRA_TOP, 0),
                width = intent.getIntExtra(EXTRA_WIDTH, 0),
                height = intent.getIntExtra(EXTRA_HEIGHT, 0),
            )
        )
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        mirror = view
        ClusterProjectionController.onHostActivityCreated(this)
        ClusterProjectionController.onSinkAvailable(view)
        AppLog.i("Cluster: host activity up on display ${display?.displayId}")
    }

    override fun onDestroy() {
        mirror?.let { ClusterProjectionController.onSinkLost(it) }
        mirror = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LEFT = "cluster.viewport.left"
        private const val EXTRA_TOP = "cluster.viewport.top"
        private const val EXTRA_WIDTH = "cluster.viewport.width"
        private const val EXTRA_HEIGHT = "cluster.viewport.height"

        fun newIntent(context: Context, viewport: ClusterDisplayPolicy.Viewport): Intent =
            Intent(context, ClusterHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EXTRA_LEFT, viewport.left)
                putExtra(EXTRA_TOP, viewport.top)
                putExtra(EXTRA_WIDTH, viewport.width)
                putExtra(EXTRA_HEIGHT, viewport.height)
            }
    }
}
