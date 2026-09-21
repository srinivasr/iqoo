package com.dhrashta.x.sensing

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.decision.ProtectedApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls UsageStatsManager for foreground changes and logs `bank_foreground` when a
 * [ProtectedApps.BANKING] app comes to the foreground. Requires the Usage Access special permission;
 * without it the watcher idles and logs once, then starts working as soon as access is granted.
 */
class UsageWatcher(context: Context) {
    private val context = context.applicationContext
    private val usageStats = this.context.getSystemService(UsageStatsManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastQueryEnd = 0L
    private var lastForeground: String? = null
    private var warnedNoAccess = false

    /** Begins polling every [POLL_MILLIS] on a background dispatcher. Safe to call twice. */
    fun start() {
        if (pollJob?.isActive == true) return
        lastQueryEnd = System.currentTimeMillis() - POLL_MILLIS
        pollJob = scope.launch {
            while (isActive) {
                runCatching { poll() }.onFailure { Log.w(TAG, "Usage poll failed", it) }
                delay(POLL_MILLIS)
            }
        }
    }

    /** Stops polling. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** True if the user granted Usage Access (OPSTR_GET_USAGE_STATS) to this app. */
    fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    private fun poll() {
        val end = System.currentTimeMillis()
        if (!hasUsageAccess()) {
            if (!warnedNoAccess) Log.w(TAG, "Usage Access not granted; bank_foreground events are disabled")
            warnedNoAccess = true
            lastQueryEnd = end
            return
        }
        warnedNoAccess = false
        val events = usageStats.queryEvents(lastQueryEnd, end)
        lastQueryEnd = end
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != FOREGROUND_EVENT) continue
            val pkg = event.packageName ?: continue
            if (pkg == lastForeground) continue
            lastForeground = pkg
            if (ProtectedApps.isBanking(pkg)) {
                // Device-wide so CC-3 can join it with canary_read/upload logged under the suspect app.
                EventLogger.recordEvent(EventLogger.DEVICE_PKG, EventLogger.BANK_FOREGROUND, event.timeStamp)
                Log.i(TAG, "Banking app in foreground: $pkg")
            }
        }
    }

    companion object {
        private const val TAG = "DhrashtaUsage"
        private const val POLL_MILLIS = 15_000L

        /** True if the user granted Usage Access (OPSTR_GET_USAGE_STATS) to this app. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        @Suppress("DEPRECATION")
        private val FOREGROUND_EVENT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
    }
}
