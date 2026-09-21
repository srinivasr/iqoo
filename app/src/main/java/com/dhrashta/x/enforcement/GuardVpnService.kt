package com.dhrashta.x.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dhrashta.x.R
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.sensing.DhrashtaForegroundService
import com.dhrashta.x.ui.MainActivity
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuardVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pausedUids = ConcurrentHashMap.newKeySet<Int>()
    private lateinit var inspector: PacketInspector
    private var tunnel: ParcelFileDescriptor? = null
    private var drainJob: Job? = null
    private var monitorEnabled = false
    private val packageByUid = ConcurrentHashMap<Int, String>()

    override fun onCreate() {
        super.onCreate()
        inspector = PacketInspector(this)
        createNotificationChannel()
        pausedUids += getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_UIDS, emptySet())
            .orEmpty().mapNotNull(String::toIntOrNull)
        monitorEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MONITOR, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val uid = intent?.getIntExtra(EXTRA_UID, Process.INVALID_UID) ?: Process.INVALID_UID
        when (intent?.action) {
            ACTION_PAUSE_UID -> if (uid != Process.INVALID_UID) pausedUids += uid
            ACTION_RESUME_UID -> if (uid != Process.INVALID_UID) pausedUids -= uid
            ACTION_START_MONITOR -> monitorEnabled = true
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
            if (monitorEnabled && prepare(this) == null) {
                establishDnsMonitorTunnel()
                return
            }
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
                    val count = try {
                        input.read(buffer)
                    } catch (_: java.io.IOException) {
                        break
                    }
                    if (count <= 0) continue
                    val resolvedUid = inspector.getUid(buffer, count)
                    CanaryMatcher.scan(buffer, count)?.let { token -> onCanaryMatch(resolvedUid, token) }
                    val blockedUid = if (resolvedUid in pausedUids) resolvedUid else pausedUids.firstOrNull() ?: resolvedUid
                    EventLogger.recordBlocked(blockedUid, inspector.remoteAddress(buffer, count))
                    // This TUN is scoped only to paused packages. Draining without writing a
                    // response intentionally black-holes their IPv4 and IPv6 traffic.
                }
            }
        }
    }

    /**
     * DNS-only tunnel used while no app is paused: routes just [DnsForwarder.VIRTUAL_DNS] for every app
     * except this one, relays queries to the real resolver and feeds them to [BeaconDetector].
     */
    private fun establishDnsMonitorTunnel() {
        val upstream = upstreamDns()
        val descriptor = runCatching {
            Builder()
                .setSession("DHRASHTA-X DNS monitor")
                .setMtu(DnsForwarder.MTU)
                .addAddress("10.0.0.2", 32)
                .addRoute(DnsForwarder.VIRTUAL_DNS, 32)
                .addDnsServer(DnsForwarder.VIRTUAL_DNS)
                .addDisallowedApplication(packageName)
                .setBlocking(true)
                .establish()
        }.getOrNull()
        if (descriptor == null) {
            Log.w(TAG, "Could not establish DNS monitor tunnel")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        tunnel = descriptor
        drainJob = DnsForwarder(this, inspector, upstream, ::onDnsQuery, ::onCanaryMatch).start(serviceScope, descriptor)
        Log.i(TAG, "DNS monitor active; upstream resolver ${upstream.hostAddress}")
    }

    private fun onDnsQuery(uid: Int, domain: String, ts: Long) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "DNS uid=$uid $domain")
        if (uid == Process.INVALID_UID) return
        BeaconDetector.recordDnsQuery(uid, domain, ts)
        val beacon = BeaconDetector.checkForBeacon(uid) ?: return
        val pkg = packageForUid(uid)
        // Logged under the owning package (not device-wide) so CC-2 only joins this app's a11y_enabled.
        EventLogger.recordEvent(pkg, EventLogger.BEACON_UNKNOWN_HOST, ts)
        Log.i(TAG, "Beacon from $pkg to ${beacon.domain} every ${beacon.intervalMillis} ms (cv=${beacon.coefficientOfVariation})")
        DhrashtaForegroundService.requestEvaluation(this, pkg)
    }

    /** Logs canary_read then upload for the sending package and asks for its re-evaluation. */
    private fun onCanaryMatch(uid: Int, token: String) {
        if (uid == Process.INVALID_UID) {
            Log.w(TAG, "Canary $token left the device from an unresolved UID")
            return
        }
        val ts = System.currentTimeMillis()
        val pkg = packageForUid(uid)
        val previous = CanaryManager.lastReadAt(token, pkg)
        CanaryManager.markRead(token, pkg, ts)
        if (previous != null && ts - previous < CANARY_COOLDOWN_MILLIS) return
        Log.w(TAG, "Canary $token sent by $pkg")
        serviceScope.launch {
            // One coroutine keeps canary_read before upload (same ts, ascending row id), as CC-3 requires.
            EventLogger.logEvent(pkg, EventLogger.CANARY_READ, ts)
            EventLogger.logEvent(pkg, EventLogger.UPLOAD, ts)
            DhrashtaForegroundService.requestEvaluation(this@GuardVpnService, pkg)
            // UsageWatcher writes bank_foreground up to 15 s late; re-check once it has landed.
            delay(BANK_EVENT_LAG_MILLIS)
            DhrashtaForegroundService.requestEvaluation(this@GuardVpnService, pkg)
        }
    }

    private fun packageForUid(uid: Int): String = packageByUid.getOrPut(uid) {
        val name = packageManager.getNameForUid(uid)
        // Shared-UID apps report "sharedUserId:uid"; prefer a real package name for event attribution.
        if (name == null || ':' in name) packageManager.getPackagesForUid(uid)?.firstOrNull() ?: name ?: "uid:$uid" else name
    }

    /** First IPv4 resolver of the current underlying network, falling back to 1.1.1.1. */
    private fun upstreamDns(): InetAddress {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.activeNetwork?.let(connectivity::getLinkProperties)?.dnsServers.orEmpty()
        return servers.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
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
            .putBoolean(KEY_MONITOR, monitorEnabled)
            .apply()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.containment_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(if (pausedUids.isEmpty()) "DHRASHTA-X DNS monitor active" else "DHRASHTA-X containment active")
        .setContentText(
            if (pausedUids.isEmpty()) {
                "Watching DNS lookups for beaconing apps"
            } else {
                "Network access is paused for ${pausedUids.size} risky app UID(s)"
            },
        )
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
        /** UIDs currently paused, as persisted by the service. */
        fun pausedUids(context: android.content.Context): Set<Int> =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_UIDS, emptySet())
                .orEmpty().mapNotNull(String::toIntOrNull).toSet()

        /** True if DNS beacon monitoring has been switched on. */
        fun dnsMonitorEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MONITOR, false)

        const val ACTION_START_MONITOR = "com.dhrashta.x.action.START_DNS_MONITOR"
        const val EXTRA_UID = "uid"
        private const val TAG = "DhrashtaVpn"
        private const val CHANNEL = "containment"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "containment_state"
        private const val KEY_UIDS = "paused_uids"
        private const val KEY_MONITOR = "dns_monitor"
        private const val CANARY_COOLDOWN_MILLIS = 60_000L
        private const val BANK_EVENT_LAG_MILLIS = 20_000L
    }
}
