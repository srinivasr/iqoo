package com.dhrashta.x.decision

/**
 * Packages whose foreground sessions are sensitive (banking / UPI). Used by UsageWatcher to emit
 * `bank_foreground`, the first step of causal chain CC-3. Same list as the official-brand allow
 * list in [BrandHeuristic], so a real bank app is "protected", never "suspected".
 */
object ProtectedApps {
    val BANKING: Set<String> = BrandHeuristic.OFFICIAL_PACKAGES

    /** True if [pkg] is a known banking / payments app. */
    fun isBanking(pkg: String): Boolean = pkg in BANKING
}
