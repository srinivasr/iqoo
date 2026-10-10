package com.dhrashta.x.decision

import com.dhrashta.x.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationsTest {

    private val mockResolver: (Int, Array<out Any>) -> String = { id, args ->
        when (id) {
            R.string.expl_nothing -> "${args.firstOrNull()} shows no warning signs"
            R.string.narrative_threat_list -> "matches known malicious threat"
            R.string.narrative_chain_cc1 -> "${args.firstOrNull()} was downloaded outside Google Play and granted screen control within 10 minutes"
            R.string.narrative_sideload_generic -> "${args.firstOrNull()} was installed from the internet outside Google Play"
            R.string.narrative_chain_cc2 -> "contacting unknown remote server on periodic timer"
            R.string.narrative_chain_cc3 -> "attempted to copy and exfiltrate private decoy security data"
            R.string.narrative_chain_cc4 -> "developer debugging enabled immediately before elevated permissions"
            R.string.narrative_chain_cc5 -> "added to managed work profile"
            R.string.narrative_cap_a11y_sms -> "can observe screen and read one-time SMS verification passwords"
            R.string.narrative_cap_a11y_overlay -> "can observe screen and draw fake login windows"
            R.string.narrative_cap_a11y_full -> "deep control to read screen and tap buttons"
            R.string.narrative_cap_sms -> "can read text messages sent to your phone"
            R.string.narrative_cap_overlay -> "can draw screens on top of other applications"
            R.string.narrative_canary_leak -> "caught attempting to copy and upload private decoy security tokens"
            R.string.narrative_covert_beacon -> "caught secretly contacting unknown remote server on fixed schedule"
            R.string.narrative_hidden_icon -> "hides launcher icon"
            R.string.narrative_mimics_brand -> "name mimics bank or government brand"
            R.string.narrative_advice_critical -> "Check permissions immediately and uninstall if not trusted."
            R.string.narrative_advice_review -> "Review permissions and remove if unrecognised."
            R.string.narrative_advice_low -> "Common and low risk on their own."
            R.string.expl_trusted_suffix -> "(Marked as trusted)"
            else -> "ID_$id"
        }
    }

    @Test
    fun cleanAppProducesCleanAdvice() {
        val result = RiskEngine.Result(
            score = 0,
            band = RiskEngine.Band.SAFE,
            firedSignals = emptyList(),
            action = TrustPolicy.Action.NONE,
            trusted = false,
        )
        val text = Explanations.build(mockResolver, "Calculator", result, emptyList())
        assertTrue(text.contains("Calculator shows no warning signs"))
        assertFalse(text.contains("Check permissions"))
    }

    @Test
    fun cc1CausalChainProducesHumanNarrative() {
        val result = RiskEngine.Result(
            score = 55,
            band = RiskEngine.Band.REVIEW,
            firedSignals = listOf(SignalCatalogue.B1, SignalCatalogue.A1, SignalCatalogue.A2),
            action = TrustPolicy.Action.NOTIFY,
            trusted = false,
        )
        val chain = CausalChains.CC1
        val text = Explanations.build(mockResolver, "FastCleaner", result, listOf(chain))

        assertTrue(text.contains("FastCleaner was downloaded outside Google Play and granted screen control within 10 minutes"))
        assertTrue(text.contains("deep control to read screen and tap buttons"))
        assertTrue(text.contains("Review permissions and remove if unrecognised"))
    }

    @Test
    fun a11yPlusSmsProducesCombinedPlainLanguageRisk() {
        val result = RiskEngine.Result(
            score = 75,
            band = RiskEngine.Band.HIGH,
            firedSignals = listOf(SignalCatalogue.A2, SignalCatalogue.C6),
            action = TrustPolicy.Action.NOTIFY,
            trusted = false,
        )
        val text = Explanations.build(mockResolver, "BankingHelper", result, emptyList())

        assertTrue(text.contains("can observe screen and read one-time SMS verification passwords"))
        assertTrue(text.contains("Review permissions and remove if unrecognised"))
    }

    @Test
    fun criticalCanaryLeakTriggersImmediateUninstallAdvice() {
        val result = RiskEngine.Result(
            score = 95,
            band = RiskEngine.Band.CRITICAL,
            firedSignals = listOf(SignalCatalogue.HIGH_TAINT_MATCH, SignalCatalogue.B4),
            action = TrustPolicy.Action.CONTAIN,
            trusted = false,
        )
        val text = Explanations.build(mockResolver, "MalwareDropper", result, emptyList())

        assertTrue(text.contains("matches known malicious threat"))
        assertTrue(text.contains("caught attempting to copy and upload private decoy security tokens"))
        assertTrue(text.contains("Check permissions immediately and uninstall if not trusted"))
    }

    @Test
    fun trustedAppAppendsTrustedSuffix() {
        val result = RiskEngine.Result(
            score = 40,
            band = RiskEngine.Band.REVIEW,
            firedSignals = listOf(SignalCatalogue.A2),
            action = TrustPolicy.Action.NONE,
            trusted = true,
        )
        val text = Explanations.build(mockResolver, "WorkTool", result, emptyList())

        assertTrue(text.contains("(Marked as trusted)"))
    }
}
