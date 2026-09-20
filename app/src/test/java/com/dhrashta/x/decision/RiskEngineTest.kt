package com.dhrashta.x.decision

import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val engine = RiskEngine()

    @Test
    fun criticalAccessibilityAbuseCrossesCriticalThreshold() {
        val result = engine.evaluate(
            identity = identity(installer = null),
            a11y = finding(),
            posture = Posture(adbEnabled = true, adbWifiEnabled = true, profileCount = 1),
            mlpResult = AccessibilityMlpClassifier.Result(0.9f, 0.05f, 0.05f),
            networkScore = 0.8f,
            allowList = emptySet(),
            threatList = ThreatList.empty(),
        )
        assertEquals(RiskEngine.Band.CRITICAL, result.band)
        assertTrue(result.firedSignals.any { it.id == "E5" })
        assertTrue(result.firedSignals.any { it.id == "E4" })
    }

    @Test
    fun allowListNeutralizesRisk() {
        val identity = identity(installer = null)
        val result = engine.evaluate(
            identity = identity,
            a11y = finding(),
            posture = Posture(false, false, 1),
            mlpResult = null,
            networkScore = null,
            allowList = setOf(identity.pkg),
            threatList = ThreatList.empty(),
        )
        assertEquals(RiskEngine.Band.SAFE, result.band)
        assertEquals(0, result.score)
    }

    private fun identity(installer: String?) = Identity(
        pkg = "com.example.access",
        appLabel = "Example Access",
        uid = 10_123,
        installer = installer,
        certSha256 = "00",
        hasLauncher = true,
        permissions = emptySet(),
        firstInstallTime = System.currentTimeMillis(),
        isSystemApp = false,
    )

    private fun finding() = A11yFinding(
        packageName = "com.example.access",
        isAccessibilityTool = false,
        canRetrieveWindowContent = true,
        canPerformGestures = true,
        canRequestFilterKeyEvents = true,
        listensToAllPackages = true,
    )
}
