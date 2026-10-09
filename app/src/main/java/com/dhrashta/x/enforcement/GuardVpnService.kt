package com.dhrashta.x.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dhrashta.x.R
import com.dhrashta.x.data.AppLanguage
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One VPN, two modes:
 *  - Containment: when at least one UID is paused, a tunnel scoped ONLY to those apps black-holes
 *    their traffic. If none of their packages can be added to the tunnel, no tunnel is created at all
 *    (an empty allow-list would otherwise route every app into the black hole).
 *  - DNS monitor: when nothing is paused and monitoring is on, only the virtual resolver is routed.
 *
 * State shown to the user is the state that exists: a UID counts as contained only after the
 * tunnel that includes it has been established ([containedUids]). Requests are tracked separately.
 */
class GuardVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestedUids = ConcurrentHashMap.newKeySet<Int>()
    private lateinit var inspector: PacketInspector
    private var tunnel: ParcelFileDescriptor? = null
    private var drainJob: Job? = null
    private var monitorEnabled = false
    @Volatile private var generation = 0
    private val packageByUid = ConcurrentHashMap<Int, String>()
    private val lastBlockedLog = ConcurrentHashMap<String, Long>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        inspector = PacketInspector(this)
        createNotificationChannel()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        requestedUids += prefs.getStringSet(KEY_REQUESTED, emptySet()).orEmpty().mapNotNull(String::toIntOrNull)
        monitorEnabled = prefs.getBoolean(KEY_MONITOR, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val uid = intent?.getIntExtra(EXTRA_UID, Process.INVALID_UID) ?: Process.INVALID_UID
        when (intent?.action) {
            ACTION_PAUSE_UID -> if (uid != Process.INVALID_UID) requestedUids += uid
            ACTION_RESUME_UID -> if (uid != Process.INVALID_UID) requestedUids -= uid
            ACTION_START_MONITOR -> monitorEnabled = true
            ACTION_STOP_MONITOR -> monitorEnabled = false
        }
        persistRequests()
        rebuildTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        synchronized(this) {
            closeTunnel()
            setContained(emptySet())
        }
        EvaluationStateStore.update { it.copy(contained = false, message = "VPN permission was revoked") }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        synchronized(this) {
            closeTunnel()
            setContained(emptySet())
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Serialised: concurrent start commands can no longer interleave two tunnel rebuilds. */
    @Synchronized
    private fun rebuildTunnel() {
        closeTunnel()
        generation++
        if (requestedUids.isEmpty()) {
            setContained(emptySet())
            if (monitorEnabled && prepare(this) == null) {
                establishDnsMonitorTunnel(generation)
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (prepare(this) != null) {
            setContained(emptySet())
            EvaluationStateStore.update {
                it.copy(contained = false, message = "Open DHRASHTA-X and grant VPN permission to contain this app")
            }
            stopSelf()
            return
        }
        establishContainmentTunnel(generation)
    }

    private fun establishContainmentTunnel(gen: Int) {
        val builder = Builder()
            .setSession("DHRASHTA-X containment")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addAddress("fd00:d4a5::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
            .setBlocking(true)
        // Only UIDs with at least one package actually added to the tunnel are contained.
        val containedNow = mutableSetOf<Int>()
        requestedUids.forEach { uid ->
            for (pkg in packageManager.getPackagesForUid(uid).orEmpty()) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    containedNow += uid
                } catch (_: PackageManager.NameNotFoundException) {
                    // Removed between evaluation and containment.
                }
            }
        }
        if (containedNow.isEmpty()) {
            // Without a single allowed application the VPN would apply to EVERY app and black-hole
            // the whole device. Never establish in that state.
            setContained(emptySet())
            requestedUids.clear()
            persistRequests()
            EvaluationStateStore.update { it.copy(contained = false, message = "Could not map the risky app to an installed package; nothing was paused") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val descriptor = runCatching { builder.establish() }
            .onFailure { Log.w(TAG, "Containment tunnel failed", it) }
            .getOrNull()
        if (descriptor == null) {
            setContained(emptySet())
            EvaluationStateStore.update { it.copy(contained = false, message = "Could not establish containment VPN") }
            return
        }
        tunnel = descriptor
        setContained(containedNow)
        EvaluationStateStore.update { it.copy(contained = true, message = "Risky app network access is paused") }
        drainJob = serviceScope.launch {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val buffer = ByteArray(32_767)
                while (isActive && gen == generation) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: java.io.IOException) {
                        break
                    }
                    if (count <= 0) continue
                    val resolvedUid = inspector.getUid(buffer, count)
                    CanaryMatcher.scan(buffer, count)?.let { token -> onCanaryAttempt(resolvedUid, token) }
                    val blockedUid = if (resolvedUid in containedNow) resolvedUid else containedNow.first()
                    logBlockedThrottled(blockedUid, inspector.remoteAddress(buffer, count))
                    // This TUN is scoped only to contained packages. Draining without writing a
                    // response intentionally black-holes their IPv4 and IPv6 traffic.
                }
            }
        }
    }

    /**
     * DNS-only tunnel used while no app is paused: routes just [DnsForwarder.VIRTUAL_DNS] for every app
     * except this one, relays queries to the real resolver and feeds them to [BeaconDetector].
     */
    private fun establishDnsMonitorTunnel(gen: Int) {
        val upstream = upstreamDns()
        val descriptor = runCatching {
            Builder()
                .setSession("DHRASHTA-X DNS monitor")
                .setMtu(DnsForwarder.MTU)
                .addAddress("10.0.0.2", 32)
                .addRoute(DnsForwarder.VIRTUAL_DNS, 32)
                .addDnsServer(DnsForwarder.VIRTUAL_DNS)
                .addDisallowedApplication(packageName)
                // Without this, a VPN with no IPv6 address blocks every app's IPv6 traffic.
                .allowFamily(OsConstants.AF_INET6)
                .setBlocking(true)
                .establish()
        }.onFailure { Log.w(TAG, "DNS monitor tunnel failed", it) }.getOrNull()
        if (descriptor == null) {
            Log.w(TAG, "Could not establish DNS monitor tunnel")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        tunnel = descriptor
        drainJob = DnsForwarder(this, inspector, upstream, ::onDnsQuery, ::onCanaryAttempt).start(serviceScope, descriptor)
        Log.i(TAG, "DNS monitor active (gen $gen); upstream resolver ${upstream.hostAddress}")
    }

    private fun onDnsQuery(uid: Int, domain: String, ts: Long) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "DNS uid=$uid $domain")
        if (uid == Process.INVALID_UID) return
        BeaconDetector.recordDnsQuery(uid, domain, ts)
        val beacon = BeaconDetector.checkForBeacon(uid) ?: return
        val pkg = packageForUid(uid)
        Log.i(TAG, "Beacon from $pkg to ${beacon.domain} every ${beacon.intervalMillis} ms (cv=${beacon.coefficientOfVariation})")
        serviceScope.launch {
            // Logged under the owning package (not device-wide) so CC-2 only joins this app's a11y_enabled.
            // Written BEFORE the re-evaluation is requested, so the evaluation always sees it.
            EventLogger.logEvent(pkg, EventLogger.BEACON_UNKNOWN_HOST, ts)
            DhrashtaForegroundService.requestEvaluation(this@GuardVpnService, pkg)
        }
    }

    /**
     * A planted decoy appeared in an outbound packet that entered our tunnel. Both tunnels drop that
     * packet (containment black-holes it; the DNS relay refuses to forward it), so this is a BLOCKED
     * SEND ATTEMPT, not proof that data left the phone.
     */
    private fun onCanaryAttempt(uid: Int, token: String) {
        if (uid == Process.INVALID_UID) {
            Log.w(TAG, "Decoy $token seen from an unresolved UID; not attributed")
            return
        }
        val ts = System.currentTimeMillis()
        val pkg = packageForUid(uid)
        val previous = CanaryManager.lastAttemptAt(token, pkg)
        CanaryManager.markAttempt(token, pkg, ts)
        if (previous != null && ts - previous < CANARY_COOLDOWN_MILLIS) return
        Log.w(TAG, "Blocked decoy send attempt by $pkg")
        serviceScope.launch {
            EventLogger.logEvent(pkg, EventLogger.CANARY_SEND_ATTEMPT, ts)
            DhrashtaForegroundService.requestEvaluation(this@GuardVpnService, pkg)
        }
    }

    private fun logBlockedThrottled(uid: Int, remote: String) {
        val key = "$uid|$remote"
        val now = System.currentTimeMillis()
        val last = lastBlockedLog[key]
        if (last != null && now - last < BLOCKED_LOG_INTERVAL_MILLIS) return
        lastBlockedLog[key] = now
        if (lastBlockedLog.size > 2_000) lastBlockedLog.clear()
        EventLogger.recordBlocked(uid, remote)
    }

    private fun packageForUid(uid: Int): String = packageByUid.getOrPut(uid) {
        val name = packageManager.getNameForUid(uid)
        // Shared-UID apps report "sharedUserId:uid"; prefer a real package name for event attribution.
        if (name == null || ':' in name) packageManager.getPackagesForUid(uid)?.firstOrNull() ?: name ?: "uid:$uid" else name
    }

    /**
     * First IPv4 resolver of a non-VPN network with Internet access, falling back to 1.1.1.1.
     * Needs ACCESS_NETWORK_STATE; any failure falls back instead of crashing the service.
     */
    @Suppress("DEPRECATION")
    private fun upstreamDns(): InetAddress {
        val fallback = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        return runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val candidates = (listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.toList()).distinct()
            candidates.asSequence()
                .filter { network ->
                    val caps = connectivity.getNetworkCapabilities(network) ?: return@filter false
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                .flatMap { connectivity.getLinkProperties(it)?.dnsServers.orEmpty().asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.hostAddress != DnsForwarder.VIRTUAL_DNS }
        }.onFailure { Log.w(TAG, "Could not read the device resolver; using 1.1.1.1", it) }
            .getOrNull() ?: fallback
    }

    private fun closeTunnel() {
        drainJob?.cancel()
        drainJob = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun setContained(uids: Set<Int>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_CONTAINED, uids.map(Int::toString).toSet())
            .commit()
    }

    private fun persistRequests() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(KEY_REQUESTED, requestedUids.map(Int::toString).toSet())
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
        .setContentTitle(getString(if (requestedUids.isEmpty()) R.string.notif_dns_title else R.string.notif_contain_title))
        .setContentText(
            if (requestedUids.isEmpty()) {
                getString(R.string.notif_dns_text)
            } else {
                resources.getQuantityString(R.plurals.notif_contain_text, requestedUids.size, requestedUids.size)
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
        const val ACTION_START_MONITOR = "com.dhrashta.x.action.START_DNS_MONITOR"
        const val ACTION_STOP_MONITOR = "com.dhrashta.x.action.STOP_DNS_MONITOR"
        const val EXTRA_UID = "uid"

        /** UIDs whose traffic is ACTUALLY inside an established containment tunnel right now. */
        fun containedUids(context: Context): Set<Int> =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_CONTAINED, emptySet())
                .orEmpty().mapNotNull(String::toIntOrNull).toSet()

        /** UIDs the user or engine asked to pause (may not be contained yet). */
        fun requestedUids(context: Context): Set<Int> =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_REQUESTED, emptySet())
                .orEmpty().mapNotNull(String::toIntOrNull).toSet()

        /** True if DNS beacon monitoring has been switched on. */
        fun dnsMonitorEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MONITOR, false)

        private const val TAG = "DhrashtaVpn"
        private const val CHANNEL = "containment"
        private const val NOTIFICATION_ID = 2001
        private const val PREFS = "containment_state"
        private const val KEY_REQUESTED = "paused_uids"
        private const val KEY_CONTAINED = "contained_uids"
        private const val KEY_MONITOR = "dns_monitor"
        private const val CANARY_COOLDOWN_MILLIS = 60_000L
        private const val BLOCKED_LOG_INTERVAL_MILLIS = 10_000L
    }
}
