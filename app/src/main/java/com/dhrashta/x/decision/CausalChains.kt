package com.dhrashta.x.decision

import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger

/**
 * Order-aware scoring: rewards *sequences* of behavioural events rather than the current snapshot.
 * Events come from the `events` table (type in [Event.signalId]) for one package plus device-wide events.
 *
 * Attribution rule: a chain only counts for an app if at least one of its matched steps was logged
 * under THAT app. Purely device-wide sequences (e.g. "work profile created" then "something appeared
 * in it") can no longer hand every app on the phone a bonus.
 */
object CausalChains {
    data class Chain(val id: String, val steps: List<String>, val windowMillis: Long, val bonus: Int)

    /** CC-1: sideloaded app gets accessibility within 10 min. */
    val CC1 = Chain("CC-1", listOf(EventLogger.SIDELOAD, EventLogger.A11Y_ENABLED), minutes(10), 15)

    /** CC-2: freshly enabled accessibility service beacons to an unknown host within 5 min. */
    val CC2 = Chain("CC-2", listOf(EventLogger.A11Y_ENABLED, EventLogger.BEACON_UNKNOWN_HOST), minutes(5), 15)

    /** CC-3: bank app in foreground, then THIS app tries to send a planted decoy within 60 s (blocked). */
    val CC3 = Chain("CC-3", listOf(EventLogger.BANK_FOREGROUND, EventLogger.CANARY_SEND_ATTEMPT), seconds(60), 25)

    /** CC-4: ADB switched on, then a privilege grant to this app within 10 min (ADB-assisted escalation). */
    val CC4 = Chain("CC-4", listOf(EventLogger.ADB_ENABLED, EventLogger.PRIVILEGE_CHANGE), minutes(10), 15)

    /** CC-5: work profile created, then THIS app appeared inside a profile within 30 min. */
    val CC5 = Chain("CC-5", listOf(EventLogger.WORK_PROFILE_CREATED, EventLogger.CLONED_APP_LAUNCHED), minutes(30), 20)

    val ALL = listOf(CC1, CC2, CC3, CC4, CC5)

    /** Maximum total bonus contributed by causal chains. */
    const val MAX_BONUS = 30

    /** How far back callers should query events; covers the longest chain window with margin. */
    val LOOKBACK_MILLIS = minutes(60)

    /** Causal bonus (0..[MAX_BONUS]) of [chains]. */
    fun bonus(chains: List<Chain>): Int = chains.sumOf(Chain::bonus).coerceAtMost(MAX_BONUS)

    /** Bonus for [pkg] from [events] observed up to [now]. */
    fun match(events: List<Event>, now: Long, pkg: String): Int = bonus(matchedChains(events, now, pkg))

    /** Every chain in [ALL] that [events] complete for [pkg], considering only the lookback before [now]. */
    fun matchedChains(events: List<Event>, now: Long, pkg: String): List<Chain> {
        val window = events
            .filter { it.timestamp in (now - LOOKBACK_MILLIS)..now }
            .filter { it.pkg == pkg || it.pkg == EventLogger.DEVICE_PKG }
            .sortedWith(compareBy(Event::timestamp, Event::id))
        return ALL.filter { hasSequence(window, it.steps, it.windowMillis, pkg) }
    }

    /**
     * True if [steps] occur in order in [sorted] (ascending by time), with the last step no more than
     * [windowMillis] after the first, and at least one matched step attributed to [pkg] (when given).
     * Other events may be interleaved between steps.
     */
    fun hasSequence(sorted: List<Event>, steps: List<String>, windowMillis: Long, pkg: String? = null): Boolean {
        if (steps.isEmpty()) return false
        sorted.forEachIndexed { startIndex, start ->
            if (start.signalId != steps[0]) return@forEachIndexed
            val deadline = start.timestamp + windowMillis
            var next = 1
            var attributed = pkg == null || start.pkg == pkg
            var i = startIndex + 1
            while (next < steps.size && i < sorted.size && sorted[i].timestamp <= deadline) {
                if (sorted[i].signalId == steps[next]) {
                    if (sorted[i].pkg == pkg) attributed = true
                    next++
                }
                i++
            }
            if (next == steps.size && attributed) return true
        }
        return false
    }

    private fun seconds(value: Long) = value * 1_000L
    private fun minutes(value: Long) = value * 60_000L
}

// Event sources:
// Per app: sideload (PackageWatcher), a11y_enabled + privilege_change (A11yObserver),
// beacon_unknown_host (GuardVpnService DNS monitor + BeaconDetector, needs VPN consent),
// canary_send_blocked (GuardVpnService; DNS lookups and contained apps only, opt-in decoys),
// cloned_app_launched (ProfileAppWatcher, logged under the package that appeared in the profile).
// Device-wide under EventLogger.DEVICE_PKG: adb_enabled + work_profile_created (DevicePostureWatcher),
// bank_foreground (UsageWatcher, needs Usage Access).
