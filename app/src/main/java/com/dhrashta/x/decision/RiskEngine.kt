package com.dhrashta.x.decision

import android.Manifest
import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture

class RiskEngine {
    enum class Band { SAFE, REVIEW, HIGH, CRITICAL }

    data class Result(
        val score: Int,
        val band: Band,
        val firedSignals: List<Signal>,
        /** True if the user trusts this app. The score is NOT changed by trust; only [action] is. */
        val trusted: Boolean = false,
        val action: TrustPolicy.Action = TrustPolicy.Action.NONE,
    )

    /**
     * @param networkThreatProbability 1 - P(BENIGN) for a traffic window that was fully observed for this
     *   app, or null when no such window exists. Null never creates a signal.
     * @param staticScore S0 = static APK model probability fused with family rules (0..1), or null when
     *   the APK has not been scanned. Null never creates a signal.
     * @param a11yEnabledAt when this app's accessibility service was FIRST seen enabled (from the event
     *   log), or null if that moment is unknown (e.g. it was already on before monitoring started).
     */
    fun evaluate(
        identity: Identity,
        a11y: A11yFinding,
        posture: Posture,
        mlpResult: AccessibilityMlpClassifier.Result?,
        networkThreatProbability: Float?,
        trusted: Boolean,
        threatList: ThreatList,
        causalBonus: Int = 0,
        recentEvents: List<Event> = emptyList(),
        a11yEnabledAt: Long? = null,
        staticScore: Float? = null,
        networkThreshold: Float = NETWORK_THRESHOLD,
        offlineBluetoothContext: Boolean = false,
    ): Result {
        val signals = buildList {
            if (!a11y.isAccessibilityTool) add(SignalCatalogue.A1)
            if (a11y.canRetrieveWindowContent) add(SignalCatalogue.A2)
            if (a11y.canPerformGestures) add(SignalCatalogue.A3)
            if (a11y.canRequestFilterKeyEvents) add(SignalCatalogue.A4)
            if (a11y.listensToAllPackages) add(SignalCatalogue.A5)
            if (a11yEnabledAt != null && a11yEnabledAt - identity.firstInstallTime in 0..TEN_MINUTES) add(SignalCatalogue.A6)
            if (identity.installer != PLAY_STORE && !identity.isSystemApp) add(SignalCatalogue.B1)
            if (threatList.contains(identity.certSha256)) add(SignalCatalogue.B4)
            if (!identity.hasLauncher) add(SignalCatalogue.B5)
            if (BrandHeuristic.mimics(identity.appLabel, identity.pkg, identity.installer, identity.isSystemApp)) {
                add(SignalCatalogue.B6)
            }
            if (Manifest.permission.SYSTEM_ALERT_WINDOW in identity.permissions) add(SignalCatalogue.C1)
            if (identity.installer != PLAY_STORE && !identity.isSystemApp &&
                Manifest.permission.INTERNET in identity.permissions &&
                SENSOR_PERMS.any { it in identity.permissions }
            ) add(SignalCatalogue.C6)
            val declaresBluetooth = BLUETOOTH_PERMS.any { it in identity.permissions }
            if (identity.installer != PLAY_STORE && !identity.isSystemApp && declaresBluetooth) add(SignalCatalogue.C7)
            if (offlineBluetoothContext && identity.installer != PLAY_STORE && !identity.isSystemApp && declaresBluetooth) {
                add(SignalCatalogue.BT1)
            }
            if (Manifest.permission.QUERY_ALL_PACKAGES in identity.permissions) add(SignalCatalogue.C5)
            if (posture.adbWifiEnabled) add(SignalCatalogue.D1)
            if (posture.newProfileAppeared) add(SignalCatalogue.D2)
            if (networkThreatProbability != null && networkThreatProbability >= networkThreshold) add(SignalCatalogue.E4)
            if (mlpResult != null && mlpResult.isMalicious) add(SignalCatalogue.E5)
            when {
                staticScore == null -> Unit
                staticScore >= STATIC_HIGH -> add(SignalCatalogue.S0_HIGH)
                staticScore >= STATIC_MEDIUM -> add(SignalCatalogue.S0_MEDIUM)
            }
            if (recentEvents.any { it.pkg == identity.pkg && it.signalId == EventLogger.CANARY_SEND_ATTEMPT }) {
                add(SignalCatalogue.HIGH_TAINT_MATCH)
            }
            if (recentEvents.any { it.pkg == identity.pkg && it.signalId == EventLogger.COVERT_UPLOAD }) {
                add(SignalCatalogue.E6)
            }
            if (a11y.isAccessibilityTool && identity.installer == PLAY_STORE) add(SignalCatalogue.N1)
            if (identity.isSystemApp) add(SignalCatalogue.N3)
        }
        val score = (signals.sumOf(Signal::weight) + causalBonus).coerceAtLeast(0)
        val band = bandFor(score)
        return Result(score, band, signals, trusted, TrustPolicy.action(band, trusted, signals))
    }

    /** Kept for callers that only need the name test; policy lives in [BrandHeuristic]. */
    fun nameMimicsProtectedBrand(label: String, pkg: String, installer: String? = null, isSystemApp: Boolean = false): Boolean =
        BrandHeuristic.mimics(label, pkg, installer, isSystemApp)

    companion object {
        const val PLAY_STORE = "com.android.vending"
        /** Sensor / recording permissions a legit utility rarely needs alongside internet access. */
        val SENSOR_PERMS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        /** Bluetooth permissions (legacy + Android 12+). A trojan can use BT to exfiltrate while offline. */
        val BLUETOOTH_PERMS = setOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_SCAN",
        )
        const val TEN_MINUTES = 10 * 60 * 1_000L
        const val NETWORK_THRESHOLD = 0.7f
        const val STATIC_HIGH = 0.5f
        const val STATIC_MEDIUM = 0.2f

        fun bandFor(score: Int): Band = when {
            score >= 90 -> Band.CRITICAL
            score >= 60 -> Band.HIGH
            score >= 30 -> Band.REVIEW
            else -> Band.SAFE
        }
    }
}
