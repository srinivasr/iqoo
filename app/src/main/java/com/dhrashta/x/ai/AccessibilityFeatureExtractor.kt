package com.dhrashta.x.ai

import android.Manifest
import com.dhrashta.x.decision.BrandHeuristic
import com.dhrashta.x.decision.ThreatList
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture

class AccessibilityFeatureExtractor(private val threatList: ThreatList) {
    fun extract(
        identity: Identity,
        finding: A11yFinding,
        @Suppress("UNUSED_PARAMETER") posture: Posture,
        enabledAtMillis: Long?,
    ): FloatArray {
        // Timed from when the service was FIRST seen enabled, not from when this scan ran. Unknown -> 0.
        val installToEnableRisk = if (enabledAtMillis == null) {
            0f
        } else {
            val secondsToEnable = (enabledAtMillis - identity.firstInstallTime).coerceAtLeast(0L) / 1_000f
            (1f - secondsToEnable / 600f).coerceIn(0f, 1f)
        }
        return floatArrayOf(
            finding.isAccessibilityTool.binary(),
            finding.canRetrieveWindowContent.binary(),
            finding.canPerformGestures.binary(),
            finding.canRequestFilterKeyEvents.binary(),
            finding.listensToAllPackages.binary(),
            (Manifest.permission.SYSTEM_ALERT_WINDOW in identity.permissions).binary(),
            (Manifest.permission.QUERY_ALL_PACKAGES in identity.permissions).binary(),
            (identity.installer != PLAY_STORE).binary(),
            identity.hasLauncher.binary(),
            installToEnableRisk,
            BrandHeuristic.mimics(identity.appLabel, identity.pkg, identity.installer, identity.isSystemApp).binary(),
            threatList.contains(identity.certSha256).binary(),
        ).also { require(it.size == FEATURE_COUNT) }
    }

    private fun Boolean.binary(): Float = if (this) 1f else 0f

    private companion object {
        const val FEATURE_COUNT = 12
        const val PLAY_STORE = "com.android.vending"
    }
}
