package com.dhrashta.x.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val pkg: String,
    val signalId: String,
    val weight: Int,
)

@Entity(tableName = "connections")
data class Connection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val uid: Int,
    val remote: String,
    val blocked: Boolean,
)

@Entity(tableName = "risk_scores")
data class RiskScore(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val pkg: String,
    val score: Int,
    val band: String,
)
