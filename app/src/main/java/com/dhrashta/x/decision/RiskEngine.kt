package com.dhrashta.x.decision

import android.Manifest
import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture

class RiskEngine {
    enum class Band { SAFE, REVIEW, HIGH, CRITICAL }

    data class Result(val score: Int, val band: Band, val firedSignals: List<Signal>)

    fun evaluate(
        identity: Identity,
        a11y: A11yFinding,
        posture: Posture,
        mlpResult: AccessibilityMlpClassifier.Result?,
        networkScore: Float?,
        allowList: Set<String>,
        threatList: ThreatList,
        causalBonus: Int = 0,
    ): Result {
        val signals = buildList {
            if (!a11y.isAccessibilityTool) add(SignalCatalogue.A1)
            if (a11y.canRetrieveWindowContent) add(SignalCatalogue.A2)
            if (a11y.canPerformGestures) add(SignalCatalogue.A3)
            if (a11y.canRequestFilterKeyEvents) add(SignalCatalogue.A4)
            if (a11y.listensToAllPackages) add(SignalCatalogue.A5)
            if (System.currentTimeMillis() - identity.firstInstallTime <= TEN_MINUTES) add(SignalCatalogue.A6)
            if (identity.installer != PLAY_STORE) add(SignalCatalogue.B1)
            if (threatList.contains(identity.certSha256)) add(SignalCatalogue.B4)
            if (!identity.hasLauncher) add(SignalCatalogue.B5)
            if (nameMimicsProtectedBrand(identity.appLabel, identity.pkg)) add(SignalCatalogue.B6)
            if (Manifest.permission.SYSTEM_ALERT_WINDOW in identity.permissions) add(SignalCatalogue.C1)
            if (Manifest.permission.QUERY_ALL_PACKAGES in identity.permissions) add(SignalCatalogue.C5)
            if (posture.adbWifiEnabled) add(SignalCatalogue.D1)
            if (posture.newProfileAppeared) add(SignalCatalogue.D2)
            if (networkScore != null && networkScore > 0.7f) add(SignalCatalogue.E4)
            if (mlpResult != null && mlpResult.confidence > 0.8f) add(SignalCatalogue.E5)
            if (a11y.isAccessibilityTool && identity.installer == PLAY_STORE) add(SignalCatalogue.N1)
            if (identity.pkg in allowList) add(SignalCatalogue.N2)
            if (identity.isSystemApp) add(SignalCatalogue.N3)
        }
        val score = (signals.sumOf(Signal::weight) + causalBonus).coerceAtLeast(0)
        val band = when {
            score >= 90 -> Band.CRITICAL
            score >= 60 -> Band.HIGH
            score >= 30 -> Band.REVIEW
            else -> Band.SAFE
        }
        return Result(score, band, signals)
    }

    private fun nameMimicsProtectedBrand(label: String, pkg: String): Boolean {
        val candidate = "$label $pkg".lowercase().replace(Regex("[^a-z0-9]"), "")
        return PROTECTED_NAMES.any { name ->
            candidate.contains(name) || candidate.windowed(name.length, 1, partialWindows = true)
                .any { levenshtein(it, name) <= 1 }
        }
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            right.forEachIndexed { j, rightChar ->
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        const val TEN_MINUTES = 10 * 60 * 1_000L
        val PROTECTED_NAMES = listOf("sbi", "hdfc", "icici", "axisbank", "paytm", "uidai", "aadhaar", "incometax")
    }
}
