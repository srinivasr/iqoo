package com.dhrashta.x.ai.staticml

/**
 * Stage-2 rules from predict_v6_family.py, loaded from static_rules.json (copied from the script by
 * the export, never retyped). Same logic:
 *   boost = sum(STRONG_ALONE) + sum(V6_STRONG) + sum(WEAK)
 *         + sum(CO_OCCURRENCE)   only if any STRICT_TRIGGERS feature fired
 *   S0 = min(base + boost, 1)
 */
class StaticBoost(
    private val strongAlone: Map<String, Double>,
    private val v6Strong: Map<String, Double>,
    private val weak: Map<String, Double>,
    private val coOccurrence: Map<String, Double>,
    private val strictTriggers: Set<String>,
) {
    data class Result(val boost: Double, val contributors: List<Pair<String, Double>>)

    fun apply(fired: Set<String>): Result {
        val parts = ArrayList<Pair<String, Double>>()
        for (group in listOf(strongAlone, v6Strong, weak)) {
            for ((feature, weight) in group) if (feature in fired) parts += feature to weight
        }
        if (strictTriggers.any { it in fired }) {
            for ((feature, weight) in coOccurrence) if (feature in fired) parts += feature to weight
        }
        return Result(parts.sumOf { it.second }, parts.sortedByDescending { it.second })
    }
}
