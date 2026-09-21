package com.dhrashta.x.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: Event): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT 100")
    suspend fun recent(): List<Event>

    /** Newest events first, for the Activity and details screens. */
    @Query("SELECT * FROM events ORDER BY timestamp DESC, id DESC LIMIT 500")
    fun observeRecent(): Flow<List<Event>>

    /** Events for [pkg] plus device-wide events ([devicePkg]) since [since], oldest first. */
    @Query(
        "SELECT * FROM events WHERE (pkg = :pkg OR pkg = :devicePkg) AND timestamp >= :since " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun forPackageSince(pkg: String, devicePkg: String, since: Long): List<Event>
}

@Dao
interface ConnectionDao {
    @Insert
    suspend fun insert(connection: Connection): Long

    @Query("SELECT * FROM connections ORDER BY timestamp DESC LIMIT 200")
    suspend fun recent(): List<Connection>

    /** Newest blocked connections first. */
    @Query("SELECT * FROM connections ORDER BY timestamp DESC LIMIT 500")
    fun observeRecent(): Flow<List<Connection>>
}

@Dao
interface RiskScoreDao {
    @Insert
    suspend fun insert(riskScore: RiskScore): Long

    @Query("SELECT * FROM risk_scores ORDER BY timestamp DESC LIMIT 50")
    suspend fun recent(): List<RiskScore>

    @Query("SELECT * FROM risk_scores ORDER BY timestamp DESC LIMIT 50")
    fun observeRecent(): Flow<List<RiskScore>>

    /** The most recent score recorded for each package. */
    @Query("SELECT * FROM risk_scores WHERE id IN (SELECT MAX(id) FROM risk_scores GROUP BY pkg)")
    fun observeLatestPerPackage(): Flow<List<RiskScore>>
}
