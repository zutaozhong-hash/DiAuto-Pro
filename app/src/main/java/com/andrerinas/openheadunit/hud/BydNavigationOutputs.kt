package com.andrerinas.openheadunit.hud

import android.content.Context
import android.os.Build
import android.util.Log
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BydNavigationOutputs {
    private const val AMAP_PACKAGE = "com.byd.amapservice"
    private const val SOMEIP_PACKAGE = "com.ts.car.someip.service"

    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) { if (BydStandaloneHudOutput.available(context)) start(context) }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    private val latest = LatestNavigationOutput()
    @Volatile private var active = false
    // While a debug synthetic sequence runs, the scheduled outputs must not overwrite it.
    @Volatile internal var syntheticHold = false
    private var initialized = false

    fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
        (BydStandaloneHudOutput.available(context) || installed(context, AMAP_PACKAGE) || installed(context, SOMEIP_PACKAGE))

    /** The SOME/IP gateway that drives the windshield HUD, probed for the diagnostics screen. */
    internal fun probeSomeIp(context: Context): BydGateCheck =
        BydGateCheck("someIpService", installed(context, SOMEIP_PACKAGE), SOMEIP_PACKAGE)

    /** BYD's own map service, which accepts the instrument-cluster navigation broadcast. */
    internal fun probeAmap(context: Context): BydGateCheck =
        BydGateCheck("amapService", installed(context, AMAP_PACKAGE), AMAP_PACKAGE)

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    @Synchronized fun start(context: Context) {
        Log.i("DiAuto-BYD", "start enabled=${Settings(context).bydNavigationEnabled} cluster=${installed(context, AMAP_PACKAGE)} hud=${installed(context, SOMEIP_PACKAGE)}")
        active = true
        if (initialized || !available(context)) return
        initialized = true
        val app = context.applicationContext
        if (BydStandaloneHudOutput.available(app)) {
            var standalone: BydStandaloneHudOutput? = null
            schedule("diauto-standalone-navi", app, repeatNs = 1_000_000_000L) { value ->
                val output = standalone ?: BydStandaloneHudOutput.create(app)?.also { standalone = it }
                if (value == null) output?.clear()
                else output?.update(value.clusterIcon, value.roundaboutExit, value.distanceMeters, value.road)
            }
            return // This firmware has one validated windshield transport.
        }
        if (installed(app, AMAP_PACKAGE)) {
            val cluster = BydClusterOutput(app)
            schedule("diauto-cluster", app, cluster::update)
        }
        if (installed(app, SOMEIP_PACKAGE)) {
            var hudInitialized = false
            schedule("diauto-hud", app) { value ->
                if (value != null && !hudInitialized) {
                    BydHudBridge.initialize(app)
                    hudInitialized = true
                }
                if (hudInitialized) BydHudBridge.update(value)
            }
        }
    }

    @Synchronized internal fun currentForDiagnostic(context: Context): BydGuidance? =
        if (context.packageName.endsWith(".bydhudtest") && active && Settings(context).bydNavigationEnabled) latest.current() else null

    @Synchronized internal fun update(value: BydGuidance?) { if (active) latest.update(value) }
    @Synchronized fun stop() { active = false; latest.update(null) }

    private fun schedule(name: String, context: Context, send: (BydGuidance?) -> Unit) =
        schedule(name, context, 5_000_000_000L, send)

    private fun schedule(name: String, context: Context, repeatNs: Long, send: (BydGuidance?) -> Unit) {
        var previous: BydGuidance? = null
        var lastSendNs = 0L
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, name).apply { isDaemon = true } }
            .scheduleWithFixedDelay({
                try {
                    if (syntheticHold || BydStandaloneHudOutput.syntheticHold) return@scheduleWithFixedDelay
                    val value = if (active && Settings(context).bydNavigationEnabled) latest.current() else null
                    val now = System.nanoTime()
                    if (value != previous || now - lastSendNs >= repeatNs) {
                        send(value)
                        previous = value
                        lastSendNs = now
                    }
                } catch (error: Exception) { Log.w("DiAuto-BYD", "$name output failed", error) }
            }, 0, 500, TimeUnit.MILLISECONDS)
    }
}
