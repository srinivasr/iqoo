package com.dhrashta.x.data

import android.content.Context
import com.dhrashta.x.decision.RiskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object EventLogger {
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
