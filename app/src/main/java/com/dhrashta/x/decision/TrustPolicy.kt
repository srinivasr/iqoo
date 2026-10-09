package com.dhrashta.x.decision

/**
 * The ONE place that decides what "trusted" means, used by the engine, notifications and the UI.
 *
 * Trust never rewrites evidence: a trusted app keeps its real score and band, so the engine and the
 * screen always show the same number. Trust only changes the ACTION:
 *  - no automatic network pause and no alert,
 *  - except for hard evidence the user could not have meant to trust (the signing certificate is on
 *    the threat list, or a planted decoy was caught in its outbound traffic), which still alerts.
 */
object TrustPolicy {
    enum class Action { NONE, NOTIFY, CONTAIN }

    private val HARD_EVIDENCE = setOf(SignalCatalogue.B4.id, SignalCatalogue.HIGH_TAINT_MATCH.id)

    fun hasHardEvidence(signals: List<Signal>): Boolean = signals.any { it.id in HARD_EVIDENCE }

    fun action(band: RiskEngine.Band, trusted: Boolean, signals: List<Signal>): Action = when {
        trusted && hasHardEvidence(signals) -> Action.NOTIFY
        trusted -> Action.NONE
        band == RiskEngine.Band.CRITICAL -> Action.CONTAIN
        band == RiskEngine.Band.HIGH || band == RiskEngine.Band.REVIEW -> Action.NOTIFY
        else -> Action.NONE
    }
}
