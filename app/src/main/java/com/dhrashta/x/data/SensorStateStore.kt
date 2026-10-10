package com.dhrashta.x.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much the phone is physically moving, from the accelerometer. */
enum class MotionState { STATIONARY, WALKING, ACTIVE, UNKNOWN }

/**
 * The phone's own physical context right now — motion, screen, charging. Read from this device's
 * sensors only (no other app, no network). Used to spot "covert" activity: a watched app uploading
 * while the phone is face-down on a table with the screen off is not something you did.
 */
data class SensorContext(
    val motion: MotionState = MotionState.UNKNOWN,
    val screenOn: Boolean = true,
    val charging: Boolean = false,
    val batteryLevel: Int = -1,
    /** No validated internet right now (the setting for a Bluetooth covert channel). */
    val offline: Boolean = false,
    /** Bluetooth radio is on (best-effort; false if unreadable). */
    val bluetoothOn: Boolean = false,
    val updatedAt: Long = 0L,
)

object SensorStateStore {
    private const val PREFS = "dhrashta_sensors"
    private const val KEY_SENSORS_ENABLED = "sensors_enabled"
    private const val KEY_BT_WATCH_ENABLED = "bt_watch_enabled"

    private val mutable = MutableStateFlow(SensorContext())
    val state = mutable.asStateFlow()

    @Volatile var available: Boolean = false
        private set

    fun isSensorMonitoringEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SENSORS_ENABLED, true)

    fun setSensorMonitoringEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SENSORS_ENABLED, enabled).apply()
        if (!enabled) {
            available = false
            mutable.value = mutable.value.copy(motion = MotionState.UNKNOWN)
        }
    }

    fun isBtWatchEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_BT_WATCH_ENABLED, true)

    fun setBtWatchEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BT_WATCH_ENABLED, enabled).apply()
    }

    fun update(context: SensorContext) {
        mutable.value = context
        available = true
    }

    val current: SensorContext get() = mutable.value
}
