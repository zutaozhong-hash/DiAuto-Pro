package com.andrerinas.openheadunit.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/** Ordinary-app IPC to the real stock receiver. No shell, local socket or permission grant. */
internal class BydStandaloneHudOutput private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("byd_standalone_hud", Context.MODE_PRIVATE)
    private val session = BydStandaloneSession(
        send = { packet ->
            app.sendBroadcast(Intent("byd.hud.NAVIGATION").setComponent(TARGET)
                .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            Log.d(TAG, "dispatch uid=${Process.myUid()} bytes=${packet.split(',').size}")
        },
        rememberPendingClear = { pending ->
            check(prefs.edit().putBoolean("pending_clear", pending).commit()) { "Cannot persist HUD cleanup" }
        },
        needsRecovery = prefs.getBoolean("pending_clear", false),
    )

    init {
        Log.i(TAG, "Standalone navigation ready uid=${Process.myUid()} helper=none")
        // Retain the journal if dispatch fails; the next scheduled tick retries.
        runCatching { session.clear() }.onFailure { Log.w(TAG, "Startup clear will retry", it) }
    }

    fun update(icon: Int, exit: Int, distanceMeters: Int, road: String) =
        session.update(icon, exit, distanceMeters, road)
    fun clear() = session.clear()

    companion object {
        private const val TAG = "BYD-Standalone-Live"
        private val TARGET = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
        private const val EXPECTED_FINGERPRINT =
            "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"
        private const val EXPECTED_VERSION = 10601004L
        private const val EXPECTED_SIGNATURE =
            "efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc"
        private val ALLOWED_PACKAGES = setOf(
            "com.andrerinas.headunitrevived", "com.shihab.diplay",
            "com.andrerinas.headunitrevived.bydhudtest", "com.shihab.diplay.hudtest",
        )
        @Volatile var syntheticHold = false

        fun create(context: Context): BydStandaloneHudOutput? =
            if (available(context)) BydStandaloneHudOutput(context) else null

        /** Enable production and diagnostic packages only on the physically tested firmware. */
        fun available(context: Context): Boolean = probe(context).none { it.ok == false }

        /**
         * Every condition [available] enforces, together with the value actually observed. The
         * diagnostics screen renders this same list, so it can never describe a rule other than the
         * one being enforced, and it reports every failure at once instead of only the first.
         */
        internal fun probe(context: Context): List<BydGateCheck> {
            val manager = context.packageManager
            // GET_SIGNING_CERTIFICATES, signingInfo and longVersionCode all arrived in API 28. The
            // gate used to dodge them by returning early; probing everything means guarding instead.
            val info = runCatching {
                manager.getPackageInfo(
                    TARGET.packageName,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0,
                )
            }.getOrNull()
            val signers = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else null
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else null
            val signature = signers?.takeIf { it.size == 1 }?.first()?.let { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 255) }
            }
            val receiver = runCatching { manager.getReceiverInfo(TARGET, 0) }.getOrNull()
            val application = info?.applicationInfo
            val isSystemApp = application != null && application.flags and ApplicationInfo.FLAG_SYSTEM != 0
            return listOf(
                BydGateCheck("sdk", Build.VERSION.SDK_INT >= 28, Build.VERSION.SDK_INT.toString()),
                BydGateCheck("package", context.packageName in ALLOWED_PACKAGES, context.packageName),
                BydGateCheck("fingerprint", Build.FINGERPRINT == EXPECTED_FINGERPRINT, Build.FINGERPRINT),
                BydGateCheck("fingerprintExpected", null, EXPECTED_FINGERPRINT),
                BydGateCheck("clusterDebugInstalled", info != null, TARGET.packageName),
                BydGateCheck(
                    "clusterDebugVersion",
                    versionCode == EXPECTED_VERSION,
                    versionCode?.toString() ?: "unknown",
                ),
                BydGateCheck("clusterDebugSystem", isSystemApp, isSystemApp.toString()),
                BydGateCheck("clusterDebugSignature", signature == EXPECTED_SIGNATURE, signature ?: "unknown"),
                BydGateCheck("receiverEnabled", receiver != null && receiver.enabled, receiver?.enabled?.toString() ?: "not found"),
                BydGateCheck("receiverExported", receiver != null && receiver.exported, receiver?.exported?.toString() ?: "not found"),
                BydGateCheck(
                    "receiverPermission",
                    receiver != null && receiver.permission.isNullOrEmpty(),
                    receiver?.permission?.ifEmpty { "declared, empty" } ?: "not found",
                ),
            )
        }
    }
}
