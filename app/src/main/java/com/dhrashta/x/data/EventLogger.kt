package com.dhrashta.x.data

import android.content.Context
import com.dhrashta.x.decision.RiskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object EventLogger {
    // Behavioural event types consumed by decision/CausalChains. Stored in events.signalId with weight 0,
    // alongside the rule signal IDs ("A1", "B1", ...) written by recordSignals().
    const val SIDELOAD = "sideload"
    const val A11Y_ENABLED = "a11y_enabled"
    const val BEACON_UNKNOWN_HOST = "beacon_unknown_host"
    const val BANK_FOREGROUND = "bank_foreground"
    const val CANARY_READ = "canary_read"
    const val UPLOAD = "upload"
    const val ADB_ENABLED = "adb_enabled"
    const val PRIVILEGE_CHANGE = "privilege_change"
    const val WORK_PROFILE_CREATED = "work_profile_created"
    const val CLONED_APP_LAUNCHED = "cloned_app_launched"

    /** Pseudo-package for device-wide events (ADB, work profile) that apply to every app's chains. */
    const val DEVICE_PKG = "_device"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var database: RoomDb? = null

    fun init(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) database = RoomDb.get(context)
            }
        }
    }

    fun observeRiskScores(context: Context): Flow<List<RiskScore>> {
        init(context)
        return requireNotNull(database).riskScoreDao().observeRecent()
    }

    fun recordBlocked(uid: Int, remote: String = "unknown") {
        val db = database ?: return
        scope.launch {
            db.connectionDao().insert(
                Connection(
                    timestamp = System.currentTimeMillis(),
                    uid = uid,
                    remote = remote,
                    blocked = true,
                ),
            )
        }
    }

    fun recordRisk(pkg: String, score: Int, band: String) {
        val db = database ?: return
        scope.launch {
            db.riskScoreDao().insert(
                RiskScore(
                    timestamp = System.currentTimeMillis(),
                    pkg = pkg,
                    score = score,
                    band = band,
                ),
            )
        }
    }

    /** Suspends until a behavioural [type] event for [pkg] at [ts] is written. */
    suspend fun logEvent(pkg: String, type: String, ts: Long = System.currentTimeMillis()) {
        val db = database ?: return
        db.eventDao().insert(Event(timestamp = ts, pkg = pkg, signalId = type, weight = 0))
    }

    /** Fire-and-forget variant of [logEvent] for callers outside a coroutine. */
    fun recordEvent(pkg: String, type: String, ts: Long = System.currentTimeMillis()) {
        scope.launch { logEvent(pkg, type, ts) }
    }

    /** Events for [pkg] plus device-wide events since [since], oldest first. */
    suspend fun recentEvents(pkg: String, since: Long): List<Event> {
        val db = database ?: return emptyList()
        return db.eventDao().forPackageSince(pkg, DEVICE_PKG, since)
    }

    fun recordSignals(pkg: String, result: RiskEngine.Result) {
        val db = database ?: return
        scope.launch {
            result.firedSignals.forEach { signal ->
                db.eventDao().insert(
                    Event(
                        timestamp = System.currentTimeMillis(),
                        pkg = pkg,
                        signalId = signal.id,
                        weight = signal.weight,
                    ),
                )
            }
        }
    }
}
