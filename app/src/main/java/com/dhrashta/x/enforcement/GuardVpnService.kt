package com.dhrashta.x.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.dhrashta.x.R
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.ui.MainActivity
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuardVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pausedUids = ConcurrentHashMap.newKeySet<Int>()
    private lateinit var inspector: PacketInspector
    private var tunnel: ParcelFileDescriptor? = null
    private var drainJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        inspector = PacketInspector(this)
        createNotificationChannel()
        pausedUids += getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_UIDS, emptySet())
            .orEmpty().mapNotNull(String::toIntOrNull)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val uid = intent?.getIntExtra(EXTRA_UID, Process.INVALID_UID) ?: Process.INVALID_UID
        when (intent?.action) {
            ACTION_PAUSE_UID -> if (uid != Process.INVALID_UID) pausedUids += uid
            ACTION_RESUME_UID -> if (uid != Process.INVALID_UID) pausedUids -= uid
        }
        persistPausedUids()
        rebuildContainmentTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        closeTunnel()
        EvaluationStateStore.update { it.copy(contained = false, message = "VPN permission was revoked") }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun rebuildContainmentTunnel() {
        closeTunnel()
        if (pausedUids.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (prepare(this) != null) {
            EvaluationStateStore.update {
                it.copy(contained = false, message = "Open DHRASHTA-X and grant VPN permission to contain this app")
            }
            stopSelf()
            return
        }
        val packages = pausedUids.flatMap { uid -> packageManager.getPackagesForUid(uid).orEmpty().asList() }.distinct()
        if (packages.isEmpty()) {
            EvaluationStateStore.update { it.copy(contained = false, message = "Could not map the risky UID to an installed app") }
            stopSelf()
            return
        }
        tunnel = runCatching {
            Builder()
                .setSession("DHRASHTA-X containment")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00:d4a5::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("2606:4700:4700::1111")
                .setBlocking(true)
                .apply {
                    packages.forEach { pkg ->
                        try {
                            addAllowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            // The package may have been removed between evaluation and containment.
                        }
                    }
                }
                .establish()
        }.getOrNull()
        val descriptor = tunnel
        if (descriptor == null) {
            EvaluationStateStore.update { it.copy(contained = false, message = "Could not establish containment VPN") }
            return
        }
        EvaluationStateStore.update { it.copy(contained = true, message = "Risky app network access is paused") }
        drainJob = serviceScope.launch {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val buffer = ByteArray(32_767)
                while (isActive) {
                    val count = runCatching { input.read(buffer) }.getOrElse { break }
                    if (count <= 0) continue
                    val resolvedUid = inspector.getUid(buffer, count)
                    val blockedUid = if (resolvedUid in pausedUids) resolvedUid else pausedUids.firstOrNull() ?: resolvedUid
                    EventLogger.recordBlocked(blockedUid, inspector.remoteAddress(buffer, count))
                    // This TUN is scoped only to paused packages. Draining without writing a
                    // response intentionally black-holes their IPv4 and IPv6 traffic.
                }
            }
        }
    }

    private fun closeTunnel() {
        drainJob?.cancel()
        drainJob = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun persistPausedUids() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_UIDS, pausedUids.map(Int::toString).toSet())
            .apply()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.containment_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("DHRASHTA-X containment active")
        .setContentText("Network access is paused for ${pausedUids.size} risky app UID(s)")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        const val ACTION_PAUSE_UID = "com.dhrashta.x.action.PAUSE_UID"
        const val ACTION_RESUME_UID = "com.dhrashta.x.action.RESUME_UID"
        const val EXTRA_UID = "uid"
        private const val CHANNEL = "containment"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "containment_state"
        private const val KEY_UIDS = "paused_uids"
    }
}
