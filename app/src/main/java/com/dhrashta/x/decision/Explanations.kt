package com.dhrashta.x.decision

import android.content.Context
import com.dhrashta.x.R

/**
 * Deterministic, evidence-keyed explanations. Every sentence maps to a signal that actually fired
 * or a chain that actually matched; nothing is asserted that the engine did not observe.
 */
object Explanations {
    fun build(context: Context, appLabel: String, result: RiskEngine.Result, chains: List<CausalChains.Chain>): String {
        val res = context.resources
        val observed = result.firedSignals.filter { it.weight > 0 }.mapNotNull { sentenceFor(it.id) }.map(res::getString)
        val parts = mutableListOf<String>()
        if (observed.isEmpty()) {
            parts += res.getString(R.string.expl_nothing, appLabel)
        } else {
            parts += res.getString(R.string.expl_intro, appLabel) + " " + observed.joinToString("; ") + "."
        }
        chains.forEach { chain -> parts += res.getString(R.string.expl_chain, chain.id, chain.steps.joinToString(" → ")) }
        parts += res.getString(
            when (result.band) {
                RiskEngine.Band.CRITICAL -> R.string.expl_advice_critical
                RiskEngine.Band.HIGH, RiskEngine.Band.REVIEW -> R.string.expl_advice_review
                RiskEngine.Band.SAFE -> R.string.expl_advice_low
            },
        )
        if (result.trusted) parts += res.getString(R.string.expl_trusted_suffix)
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
        "HIGH_TAINT_MATCH" -> R.string.expl_sig_taint
        "S0_HIGH" -> R.string.expl_sig_s0_high
        "S0_MEDIUM" -> R.string.expl_sig_s0_medium
        else -> null
    }
}
