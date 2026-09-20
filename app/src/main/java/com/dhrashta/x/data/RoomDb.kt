package com.dhrashta.x.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Event::class, Connection::class, RiskScore::class],
    version = 1,
    exportSchema = true,
)
abstract class RoomDb : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun riskScoreDao(): RiskScoreDao

    companion object {
        @Volatile private var instance: RoomDb? = null

        fun get(context: Context): RoomDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RoomDb::class.java,
                "dhrashta-x.db",
            ).build().also { instance = it }
        }
    }
}
