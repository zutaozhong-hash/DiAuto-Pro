package com.andrerinas.openheadunit.cluster

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.view.IProjectionView
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Owns the instrument-cluster mirror end to end.
 *
 * Android Auto gives a head unit one video stream, so there is nothing to decode twice: the mirror
 * is a sampled copy of the picture already on the main screen. That sets the whole shape of this
 * class — it needs three things alive at once and does nothing until all three are:
 *
 *  1. a **session** (`onSessionStarted`), so a disabled or disconnected unit costs nothing;
 *  2. a **source**, the projection view showing that stream (`onProjectionViewAvailable`);
 *  3. a **sink**, a window on the cluster display (`onSinkAvailable`).
 *
 * A fourth precondition, the cluster display itself, is re-checked on every display change, because
 * the projection layer comes and goes with the stock cluster UI.
 *
 * On top of those, `Settings.clusterMapTrigger` may withhold the mirror entirely while a map app is
 * idle or absent ([ClusterTriggerPolicy]). Because the mirror samples whatever the main picture
 * happens to be, it has no way of knowing that on its own. The gate is re-checked by [watcher] while
 * the mirror is off — the frame pump cannot do it, since it stops when there is no window to draw
 * into — and closing the window is what hands the stock cluster UI its layer back.
 *
 * All public entry points are safe to call from any thread and from any lifecycle state; the work is
 * marshalled onto the main looper, every step is idempotent, and every failure path tears down to
 * "feature off" rather than leaving a half-built window behind.
 */
object ClusterProjectionController {

    private const val TAG_PREFIX = "Cluster: "

    /** How long without a usable frame before the failure counter is reported. */
    private const val FAILURE_LOG_THRESHOLD = 30

    /** Interval of the throughput log while the mirror is live. */
    private const val RATE_LOG_INTERVAL_MS = 10_000L

    /**
     * How often the trigger is re-checked while the mirror is withheld.
     *
     * Half a second is far below the time it takes a driver to notice the cluster coming back, and
     * the cost of a tick is a volatile read plus a comparison, so this can be cheap enough to run for
     * as long as the session lasts.
     */
    private const val WATCH_INTERVAL_MS = 500L

    /** Where the mirror ended up being carried; also a diagnostic to report in the settings UI. */
    enum class Route { NONE, PRESENTATION, ACTIVITY }

    private val handler = Handler(Looper.getMainLooper())

    // ---- inputs, all written on the main looper ----
    private var enabled = false
    private var applicationContext: Context? = null
    private var hostContext = WeakReference<Context>(null)
    private var sourceView: IProjectionView? = null
    private var sink: ClusterMirrorView? = null

    // ---- state derived from the inputs ----
    private var display: Display? = null
    private var viewport = ClusterDisplayPolicy.Viewport(0, 0, 0, 0)
    private var route = Route.NONE
    private var presentation: ClusterPresentation? = null
    private var hostActivity = WeakReference<ClusterHostActivity>(null)
    private var grabber: ClusterFrameGrabber? = null
    private var grabberSource: IProjectionView? = null
    private var inFlight = false
    private var consecutiveFailures = 0
    private var failureReported = false
    private var unavailableReported = false
    private var framesSinceLog = 0L
    private var lastRateLogMs = 0L
    private var displayListenerRegistered = false
    private var watching = false
    private var lastAllowed = true

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reevaluate("display $displayId added")

        override fun onDisplayRemoved(displayId: Int) = reevaluate("display $displayId removed")

        override fun onDisplayChanged(displayId: Int) = reevaluate("display $displayId changed")
    }

    /**
     * Re-checks the trigger while the mirror is withheld, and only then.
     *
     * The frame pump would be the obvious place, but it stops re-arming itself when there is no
     * window to draw into, so it is structurally unable to notice navigation starting. This loop is
     * independent of it, and stops itself the moment the feature is switched off, so it cannot
     * outlive a session.
     */
    private val watcher = object : Runnable {
        override fun run() {
            if (!enabled) {
                watching = false
                return
            }
            val allowed = mirrorAllowed()
            if (allowed != lastAllowed) {
                lastAllowed = allowed
                reevaluate("navigation ${activityLabel()}")
            }
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            if (!enabled) return
            val view = sink
            if (view == null) return
            val activeGrabber = grabber
            if (activeGrabber != null && !inFlight) {
                inFlight = true
                activeGrabber.grab { bitmap ->
                    inFlight = false
                    if (bitmap != null) {
                        consecutiveFailures = 0
                        failureReported = false
                        val target = sink
                        if (target != null) {
                            target.submit(bitmap, placementFor(bitmap))
                            framesSinceLog++
                        }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures == FAILURE_LOG_THRESHOLD && !failureReported) {
                            failureReported = true
                            AppLog.w(
                                "${TAG_PREFIX}no frame from ${activeGrabber.label} after " +
                                    "$FAILURE_LOG_THRESHOLD attempts; the mirror is idle"
                            )
                        }
                    }
                    logRateIfDue()
                }
            }
            handler.postDelayed(this, intervalMs())
        }
    }

    // ------------------------------------------------------------------ public API

    /** An Android Auto session came up. Does nothing unless the feature is switched on. */
    fun onSessionStarted(context: Context) = post {
        applicationContext = context.applicationContext
        // A latch left over from the previous session must not outlive it, or the mirror would come
        // up on the strength of a map app that walked away an hour ago. Reset before the early
        // return below: the signal is also what the diagnostics read.
        NavigationActivitySignal.reset()
        enabled = settingsOf(context)?.clusterMapEnabled == true
        if (!enabled) {
            AppLog.i("${TAG_PREFIX}session started but the mirror is disabled")
            return@post
        }
        registerDisplayListener(context)
        evaluate(context, "session started")
        startWatcher()
    }

    /** The session went away (disconnect, task removed, service destroyed). Always tears down. */
    fun onSessionStopped() = post {
        enabled = false
        stopWatcher()
        NavigationActivitySignal.reset()
        teardown("session stopped")
    }

    /** The activity that shows the phone's picture is up; remember it as the frame source. */
    fun onProjectionViewAvailable(activity: Activity, view: IProjectionView) = post {
        hostContext = WeakReference(activity)
        val changed = sourceView !== view
        sourceView = view
        if (changed) releaseGrabber()
        if (applicationContext == null) applicationContext = activity.applicationContext
        if (enabled) evaluate(activity, "projection view available")
    }

    /** The projection view is going away; its surface is about to stop producing frames. */
    fun onProjectionViewLost(view: IProjectionView) = post {
        if (sourceView !== view) return@post
        sourceView = null
        releaseGrabber()
        teardown("projection view lost")
    }

    /** A cluster window came up. */
    fun onSinkAvailable(view: ClusterMirrorView) = post {
        sink = view
        if (enabled) evaluate(view.context, "sink available")
    }

    /**
     * A cluster window went away (presentation dismissed, host activity finished, the stock cluster
     * UI taking its layer back).
     *
     * The sink is dropped and the pump stopped, but the display listener stays registered and no
     * window is reopened here. Reopening would race a carrier that is still tearing down, and on a
     * firmware that grants this layer and then revokes it repeatedly it would relaunch a window per
     * event. The same display change that takes the layer away is what brings it back, so
     * [evaluate] is left to reopen from there — one decision point, not two.
     */
    fun onSinkLost(view: ClusterMirrorView) = post {
        if (sink !== view) return@post
        sink = null
        route = Route.NONE
        handler.removeCallbacks(pump)
        releaseGrabber()
    }

    /** The host activity hands itself over so [teardown] can finish it. */
    fun onHostActivityCreated(activity: ClusterHostActivity) = post {
        hostActivity = WeakReference(activity)
    }

    /** Settings changed while running; re-reads everything and rebuilds if needed. */
    fun refresh(context: Context) = post {
        applicationContext = context.applicationContext
        if (settingsOf(context)?.clusterMapEnabled != true) {
            enabled = false
            stopWatcher()
            teardown("disabled")
            return@post
        }
        registerDisplayListener(context)
        enabled = true
        evaluate(context, "settings changed")
        startWatcher()
    }

    /** Last route used, for diagnostics. */
    fun currentRoute(): Route = route

    /** Set of cluster display names currently visible, for the settings screen's availability text. */
    fun describeDisplays(context: Context): String =
        ClusterDisplayLocator.describeDetailed(context)

    // ------------------------------------------------------------------ evaluation

    private fun evaluate(context: Context, reason: String) {
        if (!enabled) return
        val settings = settingsOf(context) ?: return

        // Withhold the mirror when the selected tier says so, and tear down rather than idle: a
        // window left open would keep the stock cluster UI from getting its layer back, and a
        // transparent window showing the last frame is worse than no mirror at all.
        if (!mirrorAllowed()) {
            teardown(
                "mirror idle, trigger=${triggerOf(settings)} activity=${activityLabel()}",
                keepListener = true,
            )
            return
        }

        val candidate = ClusterDisplayLocator.find(
            context,
            preferredName = settings.clusterMapDisplayName,
            explicitDisplayId = settings.clusterMapDisplayId,
        )
        if (candidate == null) {
            if (!unavailableReported) {
                unavailableReported = true
                AppLog.i(
                    "${TAG_PREFIX}no cluster display yet ($reason); all displays=" +
                        ClusterDisplayLocator.describeAll(context)
                )
            }
            teardown("cluster display unavailable", keepListener = true)
            return
        }
        unavailableReported = false

        val (width, height) = ClusterDisplayLocator.sizeOf(candidate.display)
        val mode = viewportModeOf(settings)
        val resolved = ClusterDisplayPolicy.viewport(candidate.name, width, height, mode)

        // Rebuild the window only when something it depends on actually changed; the display list is
        // polled on every display event and rebuilding on each one would flicker.
        val reuse = display?.displayId == candidate.display.displayId &&
            viewport == resolved &&
            sink != null &&
            route != Route.NONE
        display = candidate.display
        viewport = resolved

        if (!reuse) {
            AppLog.i(
                "${TAG_PREFIX}$reason -> display ${candidate.display.displayId}:${candidate.name} " +
                    "${width}x${height} mode=$mode viewport=${resolved.left},${resolved.top}," +
                    "${resolved.width}x${resolved.height}" +
                    " trigger=${triggerOf(settings)} nav=${activityLabel()}"
            )
            openWindow(context, candidate.display, resolved, settings)
        }

        ensureGrabber()
        startPump()
    }

    private fun openWindow(
        context: Context,
        target: Display,
        targetViewport: ClusterDisplayPolicy.Viewport,
        settings: Settings,
    ) {
        closeWindow()
        // A zero-width viewport means the resolution is not one the band offsets are known for; the
        // policy already falls back to the whole display in that case, so this is a real guard.
        if (targetViewport.isEmpty) {
            AppLog.w("${TAG_PREFIX}viewport is empty; not opening a window")
            return
        }

        if (!settings.clusterMapUseHostActivity &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
        ) {
            // Prefer a themed activity context: a service context cannot always create a window on a
            // secondary display even with SYSTEM_ALERT_WINDOW, and the projection activity is alive
            // exactly whenever there is something to mirror.
            val outer = hostContext.get() ?: context
            val created = runCatching {
                ClusterPresentation(outer, target, targetViewport)
            }.getOrElse { error ->
                AppLog.w("${TAG_PREFIX}presentation construction failed (${error.message})")
                null
            }
            if (created != null && created.tryShow()) {
                presentation = created
                route = Route.PRESENTATION
                // Register the presentation's own view as the sink here rather than waiting for a
                // callback: the window is already up, so the next pump tick can draw immediately.
                sink = created.mirrorView
                return
            }
            runCatching { created?.dismiss() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(target.displayId)
            val intent = ClusterHostActivity.newIntent(context.applicationContext, targetViewport)
            val started = runCatching {
                context.applicationContext.startActivity(intent, options.toBundle())
                true
            }.getOrElse { error ->
                AppLog.w("${TAG_PREFIX}host activity launch failed (${error.message})")
                false
            }
            if (started) {
                route = Route.ACTIVITY
                return
            }
        }

        AppLog.w("${TAG_PREFIX}could not open any cluster window on display ${target.displayId}")
        route = Route.NONE
    }

    private fun closeWindow() {
        // Detach the state *before* tearing the window down. `Presentation.dismiss()` calls `onStop()`
        // synchronously, and that forwards here through [onSinkLost]; if the state still pointed at
        // the dying window, that callback would re-enter this class and could reopen a window from
        // inside the one being closed.
        val dyingPresentation = presentation
        val dyingActivity = hostActivity.get()
        presentation = null
        hostActivity = WeakReference(null)
        sink = null
        route = Route.NONE

        dyingPresentation?.let { runCatching { it.dismiss() } }
        dyingActivity?.let { activity -> runCatching { activity.finish() } }
    }

    // ------------------------------------------------------------------ frame pump

    private fun ensureGrabber() {
        val view = sourceView
        if (view == null) {
            releaseGrabber()
            return
        }
        if (grabber != null && grabberSource === view) return
        releaseGrabber()
        val created = ClusterFrameGrabber.forView(view)
        if (created == null) {
            if (!failureReported) {
                failureReported = true
                AppLog.w(
                    "${TAG_PREFIX}the ${view.javaClass.simpleName} backend cannot be sampled on API " +
                        "${Build.VERSION.SDK_INT}; the mirror stays off"
                )
            }
            return
        }
        grabber = created
        grabberSource = view
        AppLog.i("${TAG_PREFIX}sampling the projection view with the ${created.label} backend")
    }

    private fun releaseGrabber() {
        grabber?.release()
        grabber = null
        grabberSource = null
        inFlight = false
        consecutiveFailures = 0
    }

    private fun startPump() {
        handler.removeCallbacks(pump)
        if (sink == null || grabber == null) return
        framesSinceLog = 0
        lastRateLogMs = SystemClock.elapsedRealtime()
        handler.post(pump)
    }

    private fun placementFor(bitmap: android.graphics.Bitmap): ClusterCropPolicy.Placement {
        val settings = applicationContext?.let { settingsOf(it) }
        val request = ClusterCropPolicy.Request(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            sourceLeftPercent = settings?.clusterMapSourceLeftPercent ?: 0f,
            sourceTopPercent = settings?.clusterMapSourceTopPercent ?: 0f,
            sourceRightPercent = settings?.clusterMapSourceRightPercent ?: 100f,
            sourceBottomPercent = settings?.clusterMapSourceBottomPercent ?: 100f,
            zoomPercent = settings?.clusterMapZoomPercent ?: 100,
            panXPercent = settings?.clusterMapPanXPercent ?: 0,
            panYPercent = settings?.clusterMapPanYPercent ?: 0,
            fit = if (settings?.clusterMapCenterCrop == true) {
                ClusterCropPolicy.Fit.CENTER_CROP
            } else {
                ClusterCropPolicy.Fit.FIT_CENTER
            },
        )
        return ClusterCropPolicy.compute(request)
    }

    private fun intervalMs(): Long {
        val fps = applicationContext?.let { settingsOf(it)?.clusterMapFps } ?: DEFAULT_FPS
        return (1000L / fps.coerceIn(MIN_FPS, MAX_FPS))
    }

    private fun logRateIfDue() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastRateLogMs
        if (elapsed < RATE_LOG_INTERVAL_MS) return
        val fps = framesSinceLog * 1000f / elapsed
        AppLog.i(
            "${TAG_PREFIX}${String.format(Locale.US, "%.1f", fps)} fps over ${elapsed}ms " +
                "route=$route viewport=${viewport.width}x${viewport.height} " +
                "nav=${activityLabel()} sinkDrawn=${sink?.drawnFrames ?: 0}"
        )
        framesSinceLog = 0
        lastRateLogMs = now
    }

    // ------------------------------------------------------------------ teardown

    private fun teardown(reason: String, keepListener: Boolean = false) {
        handler.removeCallbacks(pump)
        closeWindow()
        releaseGrabber()
        if (!keepListener) {
            unregisterDisplayListener()
            display = null
            viewport = ClusterDisplayPolicy.Viewport(0, 0, 0, 0)
        }
        AppLog.i("${TAG_PREFIX}stopped ($reason)")
    }

    // ------------------------------------------------------------------ plumbing

    private fun reevaluate(reason: String) {
        val context = applicationContext ?: return
        if (!enabled) return
        evaluate(context, reason)
    }

    private fun registerDisplayListener(context: Context) {
        if (displayListenerRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { manager.registerDisplayListener(displayListener, handler) }
            .onSuccess { displayListenerRegistered = true }
            .onFailure { AppLog.w("${TAG_PREFIX}display listener not registered (${it.message})") }
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        val context = applicationContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
        displayListenerRegistered = false
    }

    /**
     * Settings are backed by credential-encrypted storage, which is unreadable while the device is
     * locked; a mirror is meaningless then anyway, so a failure is reported as "off".
     */
    private fun settingsOf(context: Context): Settings? = runCatching { Settings(context) }
        .getOrElse { error ->
            AppLog.w("${TAG_PREFIX}settings unavailable (${error.message})")
            null
        }

    private fun viewportModeOf(settings: Settings): ClusterDisplayPolicy.ViewportMode =
        when (settings.clusterMapViewport) {
            Settings.ClusterViewportMode.MAP_BAND -> ClusterDisplayPolicy.ViewportMode.MAP_BAND
            Settings.ClusterViewportMode.SIDE_CARD -> ClusterDisplayPolicy.ViewportMode.SIDE_CARD
            Settings.ClusterViewportMode.FULL_BLEED -> ClusterDisplayPolicy.ViewportMode.FULL_BLEED
            Settings.ClusterViewportMode.AUTO -> ClusterDisplayPolicy.ViewportMode.AUTO
        }

    private fun triggerOf(settings: Settings): ClusterTriggerPolicy.Trigger =
        when (settings.clusterMapTrigger) {
            Settings.ClusterTrigger.CRUISE_AND_NAV -> ClusterTriggerPolicy.Trigger.CRUISE_AND_NAV
            Settings.ClusterTrigger.NAV_ONLY -> ClusterTriggerPolicy.Trigger.NAV_ONLY
            Settings.ClusterTrigger.ALWAYS -> ClusterTriggerPolicy.Trigger.ALWAYS
        }

    /** Whether the tier selected right now lets the mirror be on. */
    private fun mirrorAllowed(): Boolean {
        val settings = applicationContext?.let { settingsOf(it) } ?: return false
        return ClusterTriggerPolicy.shouldMirror(
            triggerOf(settings),
            NavigationActivitySignal.snapshot(),
            SystemClock.elapsedRealtime(),
        )
    }

    /** The navigation state, as a log word rather than an enum ordinal. */
    private fun activityLabel(): String = when (
        ClusterTriggerPolicy.activity(
            NavigationActivitySignal.snapshot(),
            SystemClock.elapsedRealtime(),
        )
    ) {
        NavigationActivity.NONE -> "absent"
        NavigationActivity.CRUISE -> "cruising"
        NavigationActivity.NAVIGATION -> "navigating"
    }

    /**
     * Runs [watcher] only when it can change anything: with `ALWAYS` the answer is a constant yes, so
     * the loop would be pure overhead. The tier can be switched at runtime, so this is re-decided on
     * every settings change rather than only at startup.
     */
    private fun startWatcher() {
        val settings = applicationContext?.let { settingsOf(it) } ?: return
        if (triggerOf(settings) == ClusterTriggerPolicy.Trigger.ALWAYS) {
            stopWatcher()
            return
        }
        if (watching) return
        // Seed the comparison from reality so the first tick does not fire a redundant evaluation.
        // Deliberately *not* done when the loop is already running: a caller re-seeding it could
        // swallow a change that lands between two ticks, and the watcher would then have nothing left
        // to notice, leaving the mirror closed with the trigger saying it may be open.
        lastAllowed = mirrorAllowed()
        watching = true
        handler.post(watcher)
    }

    private fun stopWatcher() {
        watching = false
        handler.removeCallbacks(watcher)
    }

    private inline fun post(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            block()
        } else {
            handler.post { block() }
        }
    }

    private const val DEFAULT_FPS = 10
    private const val MIN_FPS = 1
    private const val MAX_FPS = 30
}
