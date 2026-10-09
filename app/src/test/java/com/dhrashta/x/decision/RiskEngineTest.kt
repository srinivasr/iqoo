package com.dhrashta.x.decision

import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val engine = RiskEngine()
    private val classes = listOf("BENIGN", "BlockAccess", "ManipulateUI", "ContentEavesdrop")

    @Test
    fun criticalAccessibilityAbuseCrossesCriticalThreshold() {
        val result = engine.evaluate(
            identity = identity(installer = null),
            a11y = finding(),
            posture = Posture(adbEnabled = true, adbWifiEnabled = true, profileCount = 1),
            mlpResult = AccessibilityMlpClassifier.Result(classes, listOf(0.05f, 0.9f, 0.03f, 0.02f)),
            networkThreatProbability = 0.8f,
            trusted = false,
            threatList = ThreatList.empty(),
        )
        assertEquals(RiskEngine.Band.CRITICAL, result.band)
        assertEquals(TrustPolicy.Action.CONTAIN, result.action)
        assertTrue(result.firedSignals.any { it.id == "E5" })
        assertTrue(result.firedSignals.any { it.id == "E4" })
    }

    @Test
    fun benignModelOutputNeverFiresE5() {
        val result = engine.evaluate(
            identity = identity(installer = null),
            a11y = finding(),
            posture = Posture(false, false, 1),
            mlpResult = AccessibilityMlpClassifier.Result(classes, listOf(0.95f, 0.03f, 0.01f, 0.01f)),
            networkThreatProbability = null,
            trusted = false,
            threatList = ThreatList.empty(),
        )
        assertFalse(result.firedSignals.any { it.id == "E5" })
        assertFalse(result.firedSignals.any { it.id == "E4" })
    }

    @Test
    fun trustKeepsTheRealScoreButMutesActions() {
        val identity = identity(installer = null)
        val untrusted = engine.evaluate(identity, finding(), Posture(false, false, 1), null, null, false, ThreatList.empty())
        val trusted = engine.evaluate(identity, finding(), Posture(false, false, 1), null, null, true, ThreatList.empty())
        assertEquals(untrusted.score, trusted.score)
        assertEquals(untrusted.band, trusted.band)
        assertEquals(TrustPolicy.Action.NONE, trusted.action)
        assertTrue(trusted.trusted)
    }

    @Test
    fun staticScoreAddsS0SignalOnlyWhenScanned() {
        val id = identity(installer = null)
        fun run(score: Float?) = engine.evaluate(id, finding(), Posture(false, false, 1), null, null, false, ThreatList.empty(), staticScore = score)
        assertTrue(run(0.8f).firedSignals.any { it.id == "S0_HIGH" })
        assertTrue(run(0.3f).firedSignals.any { it.id == "S0_MEDIUM" })
        assertFalse(run(0.1f).firedSignals.any { it.id.startsWith("S0") })
        assertFalse(run(null).firedSignals.any { it.id.startsWith("S0") })
        assertEquals(run(null).score + SignalCatalogue.S0_HIGH.weight, run(0.8f).score)
    }

    @Test
    fun accessibilityTimingUsesFirstEnableNotScanTime() {
        val installed = System.currentTimeMillis() - 60 * 60_000L // installed an hour ago
        val id = identity(installer = null).copy(firstInstallTime = installed)
        val early = engine.evaluate(id, finding(), Posture(false, false, 1), null, null, false, ThreatList.empty(), a11yEnabledAt = installed + 2 * 60_000L)
        val late = engine.evaluate(id, finding(), Posture(false, false, 1), null, null, false, ThreatList.empty(), a11yEnabledAt = installed + 30 * 60_000L)
        val unknown = engine.evaluate(id, finding(), Posture(false, false, 1), null, null, false, ThreatList.empty(), a11yEnabledAt = null)
        assertTrue(early.firedSignals.any { it.id == "A6" })
        assertFalse(late.firedSignals.any { it.id == "A6" })
        assertFalse(unknown.firedSignals.any { it.id == "A6" })
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
