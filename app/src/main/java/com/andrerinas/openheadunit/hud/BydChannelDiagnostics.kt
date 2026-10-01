package com.andrerinas.openheadunit.hud

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.cluster.ClusterProjectionController
import com.andrerinas.openheadunit.utils.Settings

/**
 * One gate check: a stable [id] the UI turns into a label, whether it passed, and the value that was
 * actually observed.
 *
 * [ok] is nullable so a check can carry pure context - an expected value, say - without dragging its
 * group's verdict down with it. The observed value matters as much as the verdict: "signature
 * mismatch" is only actionable once you can read the signature the unit actually reports.
 */
data class BydGateCheck(val id: String, val ok: Boolean?, val detail: String)

/** A named bundle of checks rendered as one block. [ok] is true unless a check actually failed. */
data class BydGateGroup(val id: String, val checks: List<BydGateCheck>) {
    val ok: Boolean get() = checks.none { it.ok == false }
}

/** Pure text layout, so the report shape can be unit tested without an Android runtime. */
object BydReportFormatter {
    const val PASS = "\u2713"
    const val FAIL = "\u2717"
    const val INFO = "\u00b7"

    fun mark(ok: Boolean?): String = when (ok) {
        null -> INFO
        true -> PASS
        false -> FAIL
    }

    fun format(
        groups: List<BydGateGroup>,
        title: (String) -> String,
        label: (String) -> String,
    ): String = groups.joinToString("\n\n") { group ->
        val lines = group.checks.joinToString("\n") { check ->
            "${mark(check.ok)} ${label(check.id)}: ${check.detail}"
        }
        "${title(group.id)}\n$lines"
    }
}

private fun List<BydGateGroup>.usable(id: String): Boolean =
    firstOrNull { it.id == id }?.ok ?: false

/**
 * Renders the BYD output gates as text so an owner can see why the HUD switch is hidden without a
 * rebuild. Every value shown comes from the same probe the gate itself reads, so the screen can
 * never describe a rule other than the one being enforced.
 */
object BydChannelDiagnostics {
    const val GROUP_FIRMWARE = "firmware"
    const val GROUP_STANDALONE = "standaloneReceiver"
    const val GROUP_SOME_IP = "windshieldHud"
    const val GROUP_AMAP = "amapBroadcast"
    const val GROUP_CLUSTER = "clusterScreen"

    /** Checks that describe the unit and the install rather than one transport. */
    private val ENVIRONMENT_IDS = setOf("sdk", "package", "fingerprint", "fingerprintExpected")

    fun collect(context: Context): List<BydGateGroup> {
        val app = context.applicationContext
        val standalone = BydStandaloneHudOutput.probe(app)
        return listOf(
            BydGateGroup(GROUP_FIRMWARE, standalone.filter { it.id in ENVIRONMENT_IDS }),
            BydGateGroup(GROUP_STANDALONE, standalone.filter { it.id !in ENVIRONMENT_IDS }),
            BydGateGroup(GROUP_SOME_IP, listOf(BydNavigationOutputs.probeSomeIp(app))),
            BydGateGroup(GROUP_AMAP, listOf(BydNavigationOutputs.probeAmap(app))),
            BydGateGroup(GROUP_CLUSTER, listOf(clusterScreen(app))),
        )
    }

    /**
     * Channels that would actually transmit, in the order [BydNavigationOutputs.start] selects them.
     * Deliberately kept in step with that method: a diagnostic reporting a channel the runtime never
     * picks is worse than no diagnostic at all.
     */
    fun activeChannels(groups: List<BydGateGroup>): List<String> {
        if (groups.usable(GROUP_FIRMWARE) && groups.usable(GROUP_STANDALONE)) return listOf(GROUP_STANDALONE)
        return listOf(GROUP_AMAP, GROUP_SOME_IP).filter { groups.usable(it) }
    }

    /** The whole report as one copyable block: a verdict header, then every gate with its value. */
    fun render(context: Context): String {
        val groups = collect(context)
        val enabled = BydNavigationOutputs.available(context)
        val channels = activeChannels(groups)
        val header = listOf(
            context.getString(
                R.string.byd_diag_toggle,
                context.getString(if (enabled) R.string.byd_diag_visible else R.string.byd_diag_hidden),
            ),
            context.getString(
                R.string.byd_diag_channels,
                if (channels.isEmpty()) context.getString(R.string.byd_diag_channel_none)
                else channels.joinToString(", ") { context.getString(groupTitle(it)) },
            ),
            context.getString(
                R.string.byd_diag_setting,
                context.getString(if (Settings(context).bydNavigationEnabled) R.string.byd_diag_on else R.string.byd_diag_off),
            ),
        ).joinToString("\n")
        val body = BydReportFormatter.format(
            groups,
            title = { context.getString(groupTitle(it)) },
            label = { context.getString(checkLabel(it)) },
        )
        return "$header\n\n$body"
    }

    private fun clusterScreen(context: Context): BydGateCheck {
        val described = ClusterProjectionController.describeDisplays(context).ifEmpty { "none" }
        return BydGateCheck("clusterDisplays", described != "none", described)
    }

    private fun groupTitle(id: String): Int = when (id) {
        GROUP_FIRMWARE -> R.string.byd_diag_group_firmware
        GROUP_STANDALONE -> R.string.byd_diag_group_standalone
        GROUP_SOME_IP -> R.string.byd_diag_group_someip
        GROUP_AMAP -> R.string.byd_diag_group_amap
        GROUP_CLUSTER -> R.string.byd_diag_group_cluster
        else -> R.string.byd_diag_group_other
    }

    private fun checkLabel(id: String): Int = when (id) {
        "sdk" -> R.string.byd_diag_sdk
        "package" -> R.string.byd_diag_package
        "fingerprint" -> R.string.byd_diag_fingerprint
        "fingerprintExpected" -> R.string.byd_diag_fingerprint_expected
        "clusterDebugInstalled" -> R.string.byd_diag_clusterdebug_installed
        "clusterDebugVersion" -> R.string.byd_diag_clusterdebug_version
        "clusterDebugSystem" -> R.string.byd_diag_clusterdebug_system
        "clusterDebugSignature" -> R.string.byd_diag_clusterdebug_signature
        "receiverEnabled" -> R.string.byd_diag_receiver_enabled
        "receiverExported" -> R.string.byd_diag_receiver_exported
        "receiverPermission" -> R.string.byd_diag_receiver_permission
        "someIpService" -> R.string.byd_diag_someip
        "amapService" -> R.string.byd_diag_amap
        "clusterDisplays" -> R.string.byd_diag_cluster_displays
        else -> R.string.byd_diag_group_other
    }
}
