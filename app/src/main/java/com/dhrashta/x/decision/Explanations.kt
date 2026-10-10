package com.dhrashta.x.decision

import android.content.Context
import com.dhrashta.x.R

/**
 * Deterministic, evidence-keyed explanations. Every sentence maps to a signal that actually fired
 * or a chain that actually matched; nothing is asserted that the engine did not observe.
 */
object Explanations {
    fun build(context: Context, appLabel: String, result: RiskEngine.Result, chains: List<CausalChains.Chain>): String {
        return build(
            resolver = { id, args ->
                if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)
            },
            appLabel = appLabel,
            result = result,
            chains = chains,
        )
    }

    fun build(
        resolver: (Int, Array<out Any>) -> String,
        appLabel: String,
        result: RiskEngine.Result,
        chains: List<CausalChains.Chain>,
    ): String {
        val sigIds = result.firedSignals.map { it.id }.toSet()
        val chainIds = chains.map { it.id }.toSet()
        val parts = mutableListOf<String>()

        if (sigIds.isEmpty() && chainIds.isEmpty()) {
            parts += resolver(R.string.expl_nothing, arrayOf(appLabel))
            if (result.trusted) parts += resolver(R.string.expl_trusted_suffix, emptyArray())
            return parts.joinToString(" ")
        }

        // 1. Threat Feed Signature
        if ("B4" in sigIds) {
            parts += resolver(R.string.narrative_threat_list, emptyArray())
        }

        // 2. Provenance & Temporal Causal Chains
        if ("CC-1" in chainIds) {
            parts += resolver(R.string.narrative_chain_cc1, arrayOf(appLabel))
        } else if ("B1" in sigIds) {
            parts += resolver(R.string.narrative_sideload_generic, arrayOf(appLabel))
        }

        if ("CC-2" in chainIds) {
            parts += resolver(R.string.narrative_chain_cc2, emptyArray())
        }
        if ("CC-3" in chainIds) {
            parts += resolver(R.string.narrative_chain_cc3, emptyArray())
        }
        if ("CC-4" in chainIds) {
            parts += resolver(R.string.narrative_chain_cc4, emptyArray())
        }
        if ("CC-5" in chainIds) {
            parts += resolver(R.string.narrative_chain_cc5, emptyArray())
        }

        if ("CC-1" !in chainIds && "A6" in sigIds) {
            parts += resolver(R.string.narrative_timing_a6, emptyArray())
        }

        // 3. Combined Sensitive Capabilities
        val hasA11y = "A1" in sigIds || "A2" in sigIds || "A3" in sigIds || "A4" in sigIds
        val hasOverlay = "C1" in sigIds
        val readsSms = "C6" in sigIds

        when {
            hasA11y && readsSms -> parts += resolver(R.string.narrative_cap_a11y_sms, emptyArray())
            hasA11y && hasOverlay -> parts += resolver(R.string.narrative_cap_a11y_overlay, emptyArray())
            hasA11y && ("A2" in sigIds || "A3" in sigIds) -> parts += resolver(R.string.narrative_cap_a11y_full, emptyArray())
            hasA11y -> parts += resolver(R.string.expl_sig_a1, emptyArray())
            readsSms -> parts += resolver(R.string.narrative_cap_sms, emptyArray())
            hasOverlay -> parts += resolver(R.string.narrative_cap_overlay, emptyArray())
        }

        // 4. Deception & Covert Network Activity
        if ("HIGH_TAINT_MATCH" in sigIds && "CC-3" !in chainIds) {
            parts += resolver(R.string.narrative_canary_leak, emptyArray())
        }
        if (("E4" in sigIds || "BT1" in sigIds) && "CC-2" !in chainIds) {
            parts += resolver(R.string.narrative_covert_beacon, emptyArray())
        }
        if ("E6" in sigIds) {
            parts += resolver(R.string.expl_sig_e6, emptyArray())
        }
        if ("B5" in sigIds) {
            parts += resolver(R.string.narrative_hidden_icon, emptyArray())
        }
        if ("B6" in sigIds) {
            parts += resolver(R.string.narrative_mimics_brand, emptyArray())
        }

        // Fallback to legacy sentence mapping if no structured narrative triggers were matched
        if (parts.isEmpty()) {
            val observed = result.firedSignals.filter { it.weight > 0 }.mapNotNull { sentenceFor(it.id) }.map { resolver(it, emptyArray()) }
            if (observed.isNotEmpty()) {
                parts += resolver(R.string.expl_intro, arrayOf(appLabel)) + " " + observed.joinToString("; ") + "."
            }
        }

        // 5. Actionable Advice
        parts += resolver(
            when (result.band) {
                RiskEngine.Band.CRITICAL -> R.string.narrative_advice_critical
                RiskEngine.Band.HIGH, RiskEngine.Band.REVIEW -> R.string.narrative_advice_review
                RiskEngine.Band.SAFE -> R.string.narrative_advice_low
            },
            emptyArray(),
        )
        if (result.trusted) parts += resolver(R.string.expl_trusted_suffix, emptyArray())

        return parts.joinToString(" ")
    }

    private fun sentenceFor(id: String): Int? = when (id) {
        "A1" -> R.string.expl_sig_a1
        "A2" -> R.string.expl_sig_a2
        "A3" -> R.string.expl_sig_a3
        "A4" -> R.string.expl_sig_a4
        "A5" -> R.string.expl_sig_a5
        "A6" -> R.string.expl_sig_a6
        "B1" -> R.string.expl_sig_b1
        "B4" -> R.string.expl_sig_b4
        "B5" -> R.string.expl_sig_b5
        "B6" -> R.string.expl_sig_b6
        "C1" -> R.string.expl_sig_c1
        "C5" -> R.string.expl_sig_c5
        "D1" -> R.string.expl_sig_d1
        "D2" -> R.string.expl_sig_d2
        "E4" -> R.string.expl_sig_e4
        "E5" -> R.string.expl_sig_e5
        "E6" -> R.string.expl_sig_e6
        "C6" -> R.string.expl_sig_c6
        "C7" -> R.string.expl_sig_c7
        "BT1" -> R.string.expl_sig_bt1
        "HIGH_TAINT_MATCH" -> R.string.expl_sig_taint
        "S0_HIGH" -> R.string.expl_sig_s0_high
        "S0_MEDIUM" -> R.string.expl_sig_s0_medium
        else -> null
    }
}
