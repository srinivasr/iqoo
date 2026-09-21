package com.dhrashta.x.decision

import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger

/**
 * Order-aware scoring: rewards *sequences* of behavioural events rather than the current snapshot.
 * Events come from the `events` table (type in [Event.signalId]) for one package plus device-wide events.
 */
object CausalChains {
    data class Chain(val id: String, val steps: List<String>, val windowMillis: Long, val bonus: Int)

    /** CC-1: sideloaded app gets accessibility within 10 min. */
    val CC1 = Chain("CC-1", listOf(EventLogger.SIDELOAD, EventLogger.A11Y_ENABLED), minutes(10), 15)

    /** CC-2: freshly enabled accessibility service beacons to an unknown host within 5 min. */
    val CC2 = Chain("CC-2", listOf(EventLogger.A11Y_ENABLED, EventLogger.BEACON_UNKNOWN_HOST), minutes(5), 15)

    /** CC-3: bank app in foreground, canary read, then upload — all within 60 s. */
    val CC3 = Chain(
        "CC-3",
        listOf(EventLogger.BANK_FOREGROUND, EventLogger.CANARY_READ, EventLogger.UPLOAD),
        seconds(60),
        25,
    )

    /** CC-4: ADB switched on, then a privilege grant within 10 min (ADB-assisted escalation). */
    val CC4 = Chain("CC-4", listOf(EventLogger.ADB_ENABLED, EventLogger.PRIVILEGE_CHANGE), minutes(10), 15)

    /** CC-5: work profile created, then a cloned app launched inside it within 30 min. */
    val CC5 = Chain(
        "CC-5",
        listOf(EventLogger.WORK_PROFILE_CREATED, EventLogger.CLONED_APP_LAUNCHED),
        minutes(30),
        20,
    )

    val ALL = listOf(CC1, CC2, CC3, CC4, CC5)

    /** Maximum total bonus contributed by causal chains. */
    const val MAX_BONUS = 30

    /** How far back callers should query events; covers the longest chain window with margin. */
    val LOOKBACK_MILLIS = minutes(60)

    /** Returns the causal bonus (0..[MAX_BONUS]) for [events] observed up to [now]. */
    fun match(events: List<Event>, now: Long): Int =
        matchedChains(events, now).sumOf(Chain::bonus).coerceAtMost(MAX_BONUS)

    /** Returns every chain in [ALL] that [events] complete, considering only the lookback before [now]. */
    fun matchedChains(events: List<Event>, now: Long): List<Chain> {
        val window = events
            .filter { it.timestamp in (now - LOOKBACK_MILLIS)..now }
            .sortedWith(compareBy(Event::timestamp, Event::id))
        return ALL.filter { hasSequence(window, it.steps, it.windowMillis) }
    }

    /**
     * True if [steps] occur in order in [sorted] (ascending by time), with the last step no more
     * than [windowMillis] after the first. Other events may be interleaved between steps.
     */
    fun hasSequence(sorted: List<Event>, steps: List<String>, windowMillis: Long): Boolean {
        if (steps.isEmpty()) return false
        sorted.forEachIndexed { startIndex, start ->
            if (start.signalId != steps[0]) return@forEachIndexed
            val deadline = start.timestamp + windowMillis
            var next = 1
            var i = startIndex + 1
            while (next < steps.size && i < sorted.size && sorted[i].timestamp <= deadline) {
                if (sorted[i].signalId == steps[next]) next++
                i++
            }
            if (next == steps.size) return true
        }
        return false
    }

    private fun seconds(value: Long) = value * 1_000L
    private fun minutes(value: Long) = value * 60_000L
}

// TODO(causal-chains): event types below are declared in EventLogger but nothing emits them yet,
// so the chains that need them cannot fire until a sensor is added:
//   - beacon_unknown_host  (CC-2): needs per-app DNS/SNI visibility; GuardVpnService only sees paused apps.
//   - bank_foreground      (CC-3): needs UsageStatsManager (PACKAGE_USAGE_STATS) foreground tracking.
//   - canary_read / upload (CC-3): planned for Phase 2 (CanaryManager / CanaryMatcher).
//   - cloned_app_launched  (CC-5): needs cross-profile launch visibility (UsageStats per profile).
// Emitted today: sideload (PackageWatcher), a11y_enabled + privilege_change (A11yObserver),
// adb_enabled + work_profile_created (DevicePostureWatcher, logged under EventLogger.DEVICE_PKG).
