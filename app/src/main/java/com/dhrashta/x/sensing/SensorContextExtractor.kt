package com.dhrashta.x.sensing

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.dhrashta.x.data.MotionState
import com.dhrashta.x.data.SensorContext
import com.dhrashta.x.data.SensorStateStore
import kotlin.math.sqrt

/**
 * Reads THIS phone's own physical context — motion (accelerometer), screen state, charging — and
 * publishes it to [SensorStateStore]. On-device only; needs no permission and touches no network.
 *
 * Motion is the variance of the accelerometer magnitude over a short rolling window: a phone lying on
 * a table reads ~constant (low variance → STATIONARY); in a pocket while walking it oscillates
 * (WALKING); shaken or actively used it swings more (ACTIVE). Sampled at NORMAL rate (~5 Hz), so the
 * battery cost is small (~1%/hour).
 */
class SensorContextExtractor(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val bluetoothAdapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magnitudes = DoubleArray(WINDOW)
    private var count = 0
    private var head = 0
    private var lastPublish = 0L

    fun start() {
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        publish(force = true) // screen/charging even if no accelerometer is present
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val m = sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble())
        magnitudes[head] = m
        head = (head + 1) % WINDOW
        if (count < WINDOW) count++
        val now = System.currentTimeMillis()
        if (now - lastPublish >= PUBLISH_INTERVAL_MS) publish(force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun publish(force: Boolean) {
        lastPublish = System.currentTimeMillis()
        SensorStateStore.update(
            SensorContext(
                motion = motionState(),
                screenOn = runCatching { powerManager?.isInteractive == true }.getOrDefault(true),
                charging = runCatching { batteryManager?.isCharging == true }.getOrDefault(false),
                batteryLevel = runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1),
                offline = isOffline(),
                bluetoothOn = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false),
                updatedAt = lastPublish,
            ),
        )
    }

    /** True when there is no validated internet (Wi-Fi or mobile) — the setting for an offline exfil channel. */
    private fun isOffline(): Boolean = runCatching {
        val net = connectivityManager?.activeNetwork ?: return true
        val caps = connectivityManager.getNetworkCapabilities(net) ?: return true
        !(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }.getOrDefault(false)

    private fun motionState(): MotionState {
        if (count < MIN_SAMPLES) return MotionState.UNKNOWN
        var mean = 0.0
        for (i in 0 until count) mean += magnitudes[i]
        mean /= count
        var varSum = 0.0
        for (i in 0 until count) { val d = magnitudes[i] - mean; varSum += d * d }
        val variance = varSum / count
        return when {
            variance < STATIONARY_MAX -> MotionState.STATIONARY
            variance < WALKING_MAX -> MotionState.WALKING
            else -> MotionState.ACTIVE
        }
    }

    private companion object {
        const val WINDOW = 25            // ~5 s at NORMAL rate
        const val MIN_SAMPLES = 8
        const val PUBLISH_INTERVAL_MS = 2_000L
        const val STATIONARY_MAX = 0.12  // m²/s⁴ of accel-magnitude variance
        const val WALKING_MAX = 2.0
    }
}
