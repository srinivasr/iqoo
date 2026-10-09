package com.dhrashta.x.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest live-traffic scores per app, produced by the VPN's inspection relay. In memory only. */
data class LiveScore(
    val pkg: String,
    val uid: Int,
    val lastProbability: Float,
    val maxProbability: Float,
    val windowsScored: Int,
    val activeWindows: Int,
    val lastScoredAt: Long,
    val alerts: Int,
    val lastAlertAt: Long?,
)

object LiveNetworkStore {
    private val mutable = MutableStateFlow<Map<String, LiveScore>>(emptyMap())
    val state = mutable.asStateFlow()
    private val history = HashMap<String, ArrayDeque<Pair<Long, Float>>>()

    /** Alert threshold of the loaded model (probability at 1% FPR in leave-one-RAT-out). */
    @Volatile var threshold: Float = 0.9457f

    @Synchronized
    fun record(pkg: String, uid: Int, probability: Float, activeWindows: Int, at: Long, alert: Boolean): LiveScore {
        val h = history.getOrPut(pkg) { ArrayDeque() }
        h.addLast(at to probability)
        while (h.size > MAX_HISTORY) h.removeFirst()
        val old = mutable.value[pkg]
        val score = LiveScore(
            pkg = pkg,
            uid = uid,
            lastProbability = probability,
            maxProbability = maxOf(old?.maxProbability ?: 0f, probability),
            windowsScored = (old?.windowsScored ?: 0) + 1,
            activeWindows = activeWindows,
            lastScoredAt = at,
            alerts = (old?.alerts ?: 0) + if (alert) 1 else 0,
            lastAlertAt = if (alert) at else old?.lastAlertAt,
        )
        mutable.value = mutable.value + (pkg to score)
        return score
    }

    /** Highest probability for [pkg] scored at or after [since], or null if nothing was scored. */
    @Synchronized
    fun recentMax(pkg: String, since: Long): Float? =
        history[pkg]?.filter { it.first >= since }?.maxOfOrNull { it.second }

    private const val MAX_HISTORY = 120
}
