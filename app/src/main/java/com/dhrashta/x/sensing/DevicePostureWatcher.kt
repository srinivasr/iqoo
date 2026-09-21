package com.dhrashta.x.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.dhrashta.x.data.EventLogger

/**
 * Logs device-wide causal-chain events as they happen, so their timestamps precede later app events:
 * [EventLogger.ADB_ENABLED] on an off→on ADB/wireless-debugging transition and
 * [EventLogger.WORK_PROFILE_CREATED] when a managed profile is added.
 */
class DevicePostureWatcher(private val context: Context) {
    private var adbOn = readAdbOn()

    private val adbObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = readAdbOn()
            if (now && !adbOn) EventLogger.recordEvent(EventLogger.DEVICE_PKG, EventLogger.ADB_ENABLED)
            adbOn = now
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_MANAGED_PROFILE_ADDED) {
                EventLogger.recordEvent(EventLogger.DEVICE_PKG, EventLogger.WORK_PROFILE_CREATED)
            }
        }
    }

    /** Registers the ADB settings observer and the managed-profile receiver. */
    fun start() {
        adbOn = readAdbOn()
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        resolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, adbObserver)
        ContextCompat.registerReceiver(
            context,
            profileReceiver,
            IntentFilter(Intent.ACTION_MANAGED_PROFILE_ADDED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Unregisters everything registered in [start]. */
    fun stop() {
        context.contentResolver.unregisterContentObserver(adbObserver)
        runCatching { context.unregisterReceiver(profileReceiver) }
    }

    private fun readAdbOn(): Boolean {
        val resolver = context.contentResolver
        return Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1 ||
            Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1
    }

    private companion object {
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
