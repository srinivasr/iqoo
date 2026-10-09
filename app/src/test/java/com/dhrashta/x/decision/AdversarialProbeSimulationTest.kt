package com.dhrashta.x.decision

import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.sensing.A11yFinding
import com.dhrashta.x.sensing.Identity
import com.dhrashta.x.sensing.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end behavioral simulation harness testing DHRASHTA-X detection logic
 * against simulated Android trojan profiles and benign control baselines.
 */
class AdversarialProbeSimulationTest {
    private val engine = RiskEngine()

    @Test
    fun simulatesTeaBotOverlayAndA11yTrojan() {
        // Simulates TeaBot / SharkBot pattern:
        // 1. Sideloaded APK
        // 2. High-privilege Accessibility Service (window reading + gestures + key filtering)
        // 3. System alert overlay permission
        // 4. Mimics banking brand (SBI)
        // 5. Sequence: Sideload -> A11y enabled within 10 minutes (CC-1)
        val now = System.currentTimeMillis()
        val pkg = "com.fake.sbi.banking"
        val identity = Identity(
            pkg = pkg,
            appLabel = "SBI Quick Services",
            uid = 10_999,
            installer = null, // Sideloaded outside Play Store (B1 = 15)
            certSha256 = "11:22:33:44",
            hasLauncher = true,
            permissions = setOf(android.Manifest.permission.SYSTEM_ALERT_WINDOW), // Overlay (C1 = 10)
            firstInstallTime = now - 60_000L, // Installed 1 min ago
            isSystemApp = false,
        )
        val finding = A11yFinding(
            packageName = pkg,
            isAccessibilityTool = false, // Not declared a11y tool (A1 = 10)
            canRetrieveWindowContent = true, // A2 = 10
            canPerformGestures = true, // A3 = 10
            canRequestFilterKeyEvents = true, // A4 = 10
            listensToAllPackages = true, // A5 = 5
        )
        val posture = Posture(adbEnabled = false, adbWifiEnabled = false, profileCount = 1)
        val events = listOf(
            Event(id = 1, timestamp = now - 50_000L, pkg = pkg, signalId = EventLogger.SIDELOAD, weight = 0),
            Event(id = 2, timestamp = now - 10_000L, pkg = pkg, signalId = EventLogger.A11Y_ENABLED, weight = 0),
        )
        val causalBonus = CausalChains.match(events, now, pkg) // CC-1 bonus = 15

        val result = engine.evaluate(
            identity = identity,
            a11y = finding,
            posture = posture,
            mlpResult = null,
            networkThreatProbability = null,
            trusted = false,
            threatList = ThreatList.empty(),
            causalBonus = causalBonus,
            recentEvents = events,
            a11yEnabledAt = now - 10_000L,
        )

        assertTrue("Score should be >= 90 (got ${result.score})", result.score >= 90)
        assertEquals(RiskEngine.Band.CRITICAL, result.band)
        assertTrue(result.firedSignals.any { it.id == "B1" })
        assertTrue(result.firedSignals.any { it.id == "B6" })
        assertTrue(result.firedSignals.any { it.id == "C1" })
    }

    @Test
    fun simulatesCanaryTokenExfiltration() {
        // Simulates trojan attempting to exfiltrate planted canary credential DHRX-*
        val now = System.currentTimeMillis()
        val pkg = "com.sample.trojan"
        val identity = Identity(
            pkg = pkg,
            appLabel = "File Manager Pro",
            uid = 10_888,
            installer = null,
            certSha256 = "55:66:77",
            hasLauncher = true,
            permissions = emptySet(),
            firstInstallTime = now - 300_000L,
            isSystemApp = false,
        )
        val finding = A11yFinding(
            packageName = pkg,
            isAccessibilityTool = false,
            canRetrieveWindowContent = true,
            canPerformGestures = false,
            canRequestFilterKeyEvents = false,
            listensToAllPackages = false,
        )
        // Network monitor caught canary token being attempted to send
        val events = listOf(
            Event(id = 1, timestamp = now - 5_000L, pkg = pkg, signalId = EventLogger.CANARY_SEND_ATTEMPT, weight = 0),
        )

        val result = engine.evaluate(
            identity = identity,
            a11y = finding,
            posture = Posture(false, false, 1),
            mlpResult = null,
            networkThreatProbability = null,
            trusted = false,
            threatList = ThreatList.empty(),
            causalBonus = 0,
            recentEvents = events,
        )

        assertTrue(result.firedSignals.any { it.id == "HIGH_TAINT_MATCH" })
        // HIGH_TAINT_MATCH contributes 50 points directly
        assertTrue(result.score >= 80)
    }

    @Test
    fun simulatesBeaconingAccessibilityTrojan() {
        // Fresh a11y service beacons to unknown C2 within 5 minutes (CC-2)
        val now = System.currentTimeMillis()
        val pkg = "com.stealth.beacon"
        val events = listOf(
            Event(id = 1, timestamp = now - 180_000L, pkg = pkg, signalId = EventLogger.A11Y_ENABLED, weight = 0),
            Event(id = 2, timestamp = now - 60_000L, pkg = pkg, signalId = EventLogger.BEACON_UNKNOWN_HOST, weight = 0),
        )
        val matched = CausalChains.matchedChains(events, now, pkg)
        assertTrue(matched.any { it.id == "CC-2" })
        assertEquals(15, CausalChains.match(events, now, pkg))
    }

    @Test
    fun negativeControlBenignAccessibilityAppNeverTriggersCriticalContainment() {
        // Clean utility installed from F-Droid (sideloaded, enabled within 10m, no brand mimicry, no overlay, no taint)
        val now = System.currentTimeMillis()
        val pkg = "org.fdroid.openutility"
        val identity = Identity(
            pkg = pkg,
            appLabel = "Open Source Quick Bar",
            uid = 10_777,
            installer = null,
            certSha256 = "88:99:00",
            hasLauncher = true,
            permissions = emptySet(),
            firstInstallTime = now - 120_000L,
            isSystemApp = false,
        )
        val finding = A11yFinding(
            packageName = pkg,
            isAccessibilityTool = false,
            canRetrieveWindowContent = true,
            canPerformGestures = true,
            canRequestFilterKeyEvents = false,
            listensToAllPackages = true,
        )
        val result = engine.evaluate(
            identity = identity,
            a11y = finding,
            posture = Posture(false, false, 1),
            mlpResult = null,
            networkThreatProbability = null,
            trusted = false,
            threatList = ThreatList.empty(),
            causalBonus = 0,
            recentEvents = emptyList(),
        )

        // Must stay under CRITICAL (score < 90) to prevent false-positive network black-holing
        assertTrue("Benign app score must be < 90 (got ${result.score})", result.score < 90)
        assertFalse(result.band == RiskEngine.Band.CRITICAL)
    }
}
