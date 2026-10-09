package com.dhrashta.x.data

import android.content.Context
import com.dhrashta.x.decision.RiskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

object EventLogger {
    // Behavioural event types consumed by decision/CausalChains. Stored in events.signalId with weight 0,
    // alongside the rule signal IDs ("A1", "B1", ...) written by recordSignals().
    const val SIDELOAD = "sideload"
    const val A11Y_ENABLED = "a11y_enabled"
    const val BEACON_UNKNOWN_HOST = "beacon_unknown_host"
    /** The live network model scored this app's last 10 windows above its alert threshold. */
    const val NETWORK_ML_ALERT = "network_ml_alert"
    /** A watched app uploaded data while the phone was screen-off and stationary (device sensors). */
    const val COVERT_UPLOAD = "covert_upload"
    const val BANK_FOREGROUND = "bank_foreground"
    /**
     * A planted decoy was seen in an outbound packet that entered our tunnel and was dropped there.
     * It is a blocked send ATTEMPT, never proof that data left the phone.
     */
    const val CANARY_SEND_ATTEMPT = "canary_send_blocked"
    const val ADB_ENABLED = "adb_enabled"
    const val PRIVILEGE_CHANGE = "privilege_change"
    const val WORK_PROFILE_CREATED = "work_profile_created"
    const val CLONED_APP_LAUNCHED = "cloned_app_launched"

    /** Pseudo-package for device-wide events (ADB, work profile) that apply to every app's chains. */
    const val DEVICE_PKG = "_device"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var database: RoomDb? = null
    private val writes = AtomicInteger()

    /** Storage bounds: rows older than [RETENTION_MILLIS] or beyond the row caps are pruned. */
    const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
    private const val MAX_EVENTS = 20_000
    private const val MAX_CONNECTIONS = 5_000
    private const val MAX_RISK_SCORES = 5_000
    private const val PRUNE_EVERY_WRITES = 500

    fun init(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) {
                    database = RoomDb.get(context)
                    scope.launch { prune() }
                }
            }
        }
    }

    /** Deletes old rows and caps table sizes so logs can never grow without bound. */
    suspend fun prune() {
        val db = database ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        runCatching {
            db.eventDao().deleteOlderThan(cutoff)
            db.eventDao().keepNewest(MAX_EVENTS)
            db.connectionDao().deleteOlderThan(cutoff)
            db.connectionDao().keepNewest(MAX_CONNECTIONS)
            db.riskScoreDao().deleteOlderThan(cutoff)
            db.riskScoreDao().keepNewest(MAX_RISK_SCORES)
        }
    }

    private fun afterWrite() {
        if (writes.incrementAndGet() % PRUNE_EVERY_WRITES == 0) scope.launch { prune() }
    }

    fun observeRiskScores(context: Context): Flow<List<RiskScore>> {
        init(context)
        return requireNotNull(database).riskScoreDao().observeRecent()
    }

    /** Newest events first (rule signals and behavioural events). */
    fun observeEvents(context: Context): Flow<List<Event>> {
        init(context)
        return requireNotNull(database).eventDao().observeRecent()
    }

    /** Newest blocked connections first. */
    fun observeConnections(context: Context): Flow<List<Connection>> {
        init(context)
        return requireNotNull(database).connectionDao().observeRecent()
    }

    /** Latest risk score per package. */
    fun observeLatestRiskScores(context: Context): Flow<List<RiskScore>> {
        init(context)
        return requireNotNull(database).riskScoreDao().observeLatestPerPackage()
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
            afterWrite()
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
            afterWrite()
        }
    }

    /** Suspends until a behavioural [type] event for [pkg] at [ts] is written. */
    suspend fun logEvent(pkg: String, type: String, ts: Long = System.currentTimeMillis()) {
        val db = database ?: return
        db.eventDao().insert(Event(timestamp = ts, pkg = pkg, signalId = type, weight = 0))
        afterWrite()
    }

    /** Earliest [type] event logged for [pkg], or null. Used to time a11y enables from their real moment. */
    suspend fun firstEventTime(pkg: String, type: String): Long? = database?.eventDao()?.firstTime(pkg, type)

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
            afterWrite()
        }
    }
}
