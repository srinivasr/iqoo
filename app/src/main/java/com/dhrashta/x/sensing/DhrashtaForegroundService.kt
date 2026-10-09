package com.dhrashta.x.sensing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dhrashta.x.R
import com.dhrashta.x.ai.AccessibilityFeatureExtractor
import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.ai.LiveNetworkModel
import com.dhrashta.x.data.LiveNetworkStore
import com.dhrashta.x.ai.NetworkThreatClassifier
import com.dhrashta.x.ai.staticml.StaticApkScanner
import com.dhrashta.x.ai.staticml.StaticModel
import com.dhrashta.x.data.AllowList
import com.dhrashta.x.data.AppLanguage
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.data.ModelStatusStore
import com.dhrashta.x.data.StaticScanStore
import com.dhrashta.x.data.StaticVerdict
import com.dhrashta.x.decision.CausalChains
import com.dhrashta.x.decision.Explanations
import com.dhrashta.x.decision.RiskEngine
import com.dhrashta.x.decision.ThreatList
import com.dhrashta.x.decision.TrustPolicy
import com.dhrashta.x.enforcement.NetworkPause
import com.dhrashta.x.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DhrashtaForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var observer: A11yObserver
    private lateinit var packageWatcher: PackageWatcher
    private lateinit var devicePostureWatcher: DevicePostureWatcher
    private lateinit var usageWatcher: UsageWatcher
    private lateinit var profileAppWatcher: ProfileAppWatcher
    private lateinit var identityResolver: IdentityResolver
    private lateinit var inspector: A11yInspector
    private lateinit var postureChecker: PostureChecker
    private lateinit var threatList: ThreatList
    private lateinit var featureExtractor: AccessibilityFeatureExtractor
    private lateinit var accessibilityModel: AccessibilityMlpClassifier
    @Volatile private var networkModel: NetworkThreatClassifier? = null
    @Volatile private var staticModel: StaticModel? = null
    @Volatile private var staticScanner: StaticApkScanner? = null
    /** Static scans read whole dex files; run one at a time to bound memory. */
    private val staticLock = Mutex()
    private val riskEngine = RiskEngine()

    /** One evaluation per package at a time, so overlapping triggers cannot race each other. */
    private val evaluationLocks = ConcurrentHashMap<String, Mutex>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(MONITOR_NOTIFICATION_ID, monitorNotification())
        EventLogger.init(this)
        threatList = runCatching { ThreatList.load(this) }.getOrElse {
            Log.e(TAG, "Could not load threats.json; continuing with an empty local threat list", it)
            ThreatList.empty()
        }
        identityResolver = IdentityResolver(this)
        inspector = A11yInspector(this)
        postureChecker = PostureChecker(this)
        featureExtractor = AccessibilityFeatureExtractor(threatList)
        accessibilityModel = AccessibilityMlpClassifier.load(this)
        ModelStatusStore.update { it.copy(a11yModelLoaded = accessibilityModel.isAvailable, a11yBackend = accessibilityModel.backendDetail) }
        serviceScope.launch { loadNetworkModel() }
        serviceScope.launch { loadLiveModel() }
        StaticScanStore.load(this)
        serviceScope.launch {
            loadStaticModel()
            scanInstalledApps(force = false)
        }
        observer = A11yObserver(this) { packages ->
            val enabledAt = System.currentTimeMillis()
            packages.forEach { pkg ->
                serviceScope.launch {
                    // Enabling an accessibility service is both the a11y step and a privilege grant (CC-1, CC-2, CC-4).
                    EventLogger.logEvent(pkg, EventLogger.A11Y_ENABLED, enabledAt)
                    EventLogger.logEvent(pkg, EventLogger.PRIVILEGE_CHANGE, enabledAt)
                    evaluate(pkg)
                }
            }
        }
        packageWatcher = PackageWatcher(this) { pkg ->
            EvaluationStateStore.update { it.copy(message = "New app installed: $pkg") }
            val installedAt = System.currentTimeMillis()
            serviceScope.launch {
                val identity = runCatching { identityResolver.resolve(pkg) }.getOrNull()
                val installer = identity?.installer
                if (installer != PLAY_STORE) EventLogger.logEvent(pkg, EventLogger.SIDELOAD, installedAt)
                // S0: scan the new APK right away, before it can ask for accessibility.
                val verdict = staticVerdict(pkg, force = true)
                val sideloadedAndUntrusted = identity != null && installer != PLAY_STORE && !identity.isSystemApp &&
                    pkg !in AllowList.get(this@DhrashtaForegroundService)
                if (verdict != null && sideloadedAndUntrusted && verdict.level == StaticVerdict.Level.HIGH) {
                    showStaticNotification(identity!!.appLabel, pkg, verdict)
                }
                // Suspicious new sideloaded app: watch its live traffic with the network model (needs VPN consent).
                if (verdict != null && sideloadedAndUntrusted && verdict.level != StaticVerdict.Level.LOW) {
                    NetworkPause.requestInspect(this@DhrashtaForegroundService, identity!!.uid)
                }
            }
        }
        devicePostureWatcher = DevicePostureWatcher(this)
        usageWatcher = UsageWatcher(this)
        profileAppWatcher = ProfileAppWatcher(this)
        observer.start()
        packageWatcher.start()
        devicePostureWatcher.start()
        usageWatcher.start()
        profileAppWatcher.start()
        NetworkPause.startDnsMonitor(this)
        EvaluationStateStore.update {
            it.copy(
                monitoring = true,
                message = if (accessibilityModel.isAvailable) {
                    "Monitoring accessibility services with local ML and rules"
                } else {
                    "Monitoring accessibility services with rules (accessibility model not installed)"
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) {
            scanAllEnabledServices()
            serviceScope.launch { scanInstalledApps(force = false) }
        }
        if (intent?.action == ACTION_STATIC_SCAN) intent.getStringExtra(EXTRA_PACKAGE)?.let { pkg ->
            serviceScope.launch { staticVerdict(pkg, force = true) }
        }
        if (intent?.action == ACTION_EVALUATE) intent.getStringExtra(EXTRA_PACKAGE)?.let(::evaluateIfAccessibilityEnabled)
        if (intent?.action == ACTION_SELF_TEST) serviceScope.launch {
            runNetworkSelfTest()
            runStaticSelfTest()
            runLiveSelfTest()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer.stop()
        packageWatcher.stop()
        devicePostureWatcher.stop()
        usageWatcher.stop()
        profileAppWatcher.stop()
        accessibilityModel.close()
        networkModel?.close()
        staticModel?.close()
        serviceScope.cancel()
        EvaluationStateStore.update { it.copy(monitoring = false, evaluating = false, message = "Monitoring stopped") }
        super.onDestroy()
    }

    private fun scanAllEnabledServices() {
        val findings = runCatching { inspector.scan() }.getOrElse {
            EvaluationStateStore.update { state -> state.copy(evaluating = false, message = "Could not inspect enabled services") }
            return
        }
        if (findings.isEmpty()) {
            EvaluationStateStore.update {
                it.copy(evaluating = false, packageName = null, message = "No enabled third-party accessibility services found")
            }
        } else {
            findings.map(A11yFinding::packageName).distinct().forEach(::evaluateAsync)
        }
    }

    /** Re-evaluates [pkg] on request from enforcement code, but only while it has an enabled a11y service. */
    private fun evaluateIfAccessibilityEnabled(pkg: String) {
        serviceScope.launch {
            val enabled = runCatching { inspector.scan().any { it.packageName == pkg } }.getOrDefault(false)
            if (enabled) evaluate(pkg) else Log.i(TAG, "Skipped re-evaluation of $pkg: no enabled accessibility service")
        }
    }

    private fun evaluateAsync(pkg: String) {
        serviceScope.launch { evaluate(pkg) }
    }

    private suspend fun loadNetworkModel() {
        val model = NetworkThreatClassifier.load(this)
        networkModel = model
        ModelStatusStore.update {
            it.copy(
                networkLoaded = model != null,
                networkModelFile = model?.modelFile,
                networkBackend = model?.backendDetail,
            )
        }
        if (model != null) runNetworkSelfTest()
    }

    private fun loadLiveModel() {
        val model = LiveNetworkModel.shared(this)
        model?.let { LiveNetworkStore.threshold = it.threshold }
        ModelStatusStore.update { it.copy(liveLoaded = model != null, liveBackend = model?.backendDetail) }
        if (model != null) runLiveSelfTest()
    }

    /** Replays the 12 real Mischief sequences bundled with the live model. */
    private fun runLiveSelfTest() {
        val model = LiveNetworkModel.shared(this) ?: return
        val report = runCatching { model.selfTest(this) }
            .onFailure { Log.e(TAG, "Live self-test failed to run", it) }
            .getOrNull()
        ModelStatusStore.update {
            it.copy(
                liveSelfTestPassed = report?.passed ?: false,
                liveSelfTestSummary = report?.let { r ->
                    getString(R.string.live_selftest_result, r.matching, r.cases, "%.1e".format(r.maxDifference), r.backend, r.meanLatencyMicros)
                } ?: getString(R.string.selftest_error),
            )
        }
        report?.let { Log.i(TAG, "Live self-test: $it") }
    }

    private fun loadStaticModel() {
        val model = StaticModel.load(this)
        staticModel = model
        staticScanner = model?.let { StaticApkScanner(this, it) }
        ModelStatusStore.update {
            it.copy(
                staticLoaded = model != null,
                staticBackend = model?.backendDetail,
                staticScanned = StaticScanStore.state.value.size,
            )
        }
        if (model != null) runStaticSelfTest()
    }

    /** Replays the real extracted samples bundled with S0 (model + family rules) against Python/ORT outputs. */
    private fun runStaticSelfTest() {
        val model = staticModel ?: return
        val report = runCatching { model.selfTest(this) }
            .onFailure { Log.e(TAG, "Static self-test failed to run", it) }
            .getOrNull()
        ModelStatusStore.update {
            it.copy(
                staticSelfTestPassed = report?.passed ?: false,
                staticSelfTestSummary = report?.let { r ->
                    getString(R.string.static_selftest_result, r.matching, r.cases, "%.1e".format(r.maxDifference), r.meanLatencyMicros)
                } ?: getString(R.string.selftest_error),
            )
        }
        report?.let { Log.i(TAG, "Static self-test: $it") }
    }

    /**
     * The S0 verdict for [pkg]: the stored one if it was computed for the APK that is installed now,
     * otherwise a fresh scan. Null if the model is missing or the APK cannot be read.
     */
    private suspend fun staticVerdict(pkg: String, force: Boolean): StaticVerdict? {
        val scanner = staticScanner ?: return null
        return staticLock.withLock {
            val current = scanner.lastUpdateTime(pkg) ?: return@withLock null
            val stored = StaticScanStore.get(this, pkg)
            if (!force && stored != null && stored.apkUpdatedAt == current) return@withLock stored
            runCatching { scanner.scan(pkg) }
                .onFailure { Log.w(TAG, "Static scan of $pkg failed", it) }
                .getOrNull()
                ?.also { verdict ->
                    StaticScanStore.put(this, verdict)
                    ModelStatusStore.update { it.copy(staticScanned = StaticScanStore.state.value.size) }
                }
        }
    }

    /** Scans every user-installed app whose APK has no up-to-date verdict (all of them if [force]). */
    private suspend fun scanInstalledApps(force: Boolean) {
        val scanner = staticScanner ?: return
        val todo = scanner.candidates().filter { (pkg, updatedAt) ->
            force || StaticScanStore.get(this, pkg)?.apkUpdatedAt != updatedAt
        }
        if (todo.isEmpty()) return
        todo.forEachIndexed { index, (pkg, _) ->
            ModelStatusStore.update { it.copy(staticProgress = index to todo.size) }
            staticVerdict(pkg, force = true)
        }
        ModelStatusStore.update { it.copy(staticProgress = null, staticScanned = StaticScanStore.state.value.size) }
        Log.i(TAG, "Static scan finished: ${todo.size} apps")
    }

    /** Replays the golden windows bundled with the model and publishes the honest result. */
    private fun runNetworkSelfTest() {
        val model = networkModel ?: return
        val report = runCatching { model.selfTest(this) }
            .onFailure { Log.e(TAG, "Network self-test failed to run", it) }
            .getOrNull()
        ModelStatusStore.update {
            it.copy(
                networkSelfTestPassed = report?.passed ?: false,
                networkSelfTestSummary = report?.let { r ->
                    getString(
                        R.string.selftest_result,
                        r.matchingTopClass,
                        r.cases,
                        "%.3f".format(r.maxDifference),
                        r.backend,
                        r.meanLatencyMicros,
                    )
                } ?: getString(R.string.selftest_error),
            )
        }
        report?.let { Log.i(TAG, "Network self-test: $it") }
    }

    private suspend fun evaluate(pkg: String) {
        evaluationLocks.getOrPut(pkg) { Mutex() }.withLock { evaluateLocked(pkg) }
    }

    private suspend fun evaluateLocked(pkg: String) {
        EvaluationStateStore.update { it.copy(evaluating = true, packageName = pkg, message = "Evaluating $pkg") }
        runCatching {
            val identity = identityResolver.resolve(pkg)
            val finding = inspector.scan().firstOrNull { it.packageName == pkg }
                ?: error("Accessibility service configuration is no longer enabled")
            val posture = postureChecker.check()
            // The moment the service was FIRST seen enabled, not the moment of this (re)scan.
            val enabledAt = EventLogger.firstEventTime(pkg, EventLogger.A11Y_ENABLED)
            val features = featureExtractor.extract(identity, finding, posture, enabledAt)
            val mlpResult = accessibilityModel.classify(features)
            // Highest live-traffic probability for this app in the last 10 minutes, if it is being
            // watched by the VPN relay; null (no signal) when no window was scored.
            val networkThreat: Float? = LiveNetworkStore.recentMax(pkg, System.currentTimeMillis() - LIVE_LOOKBACK_MILLIS)
            val trusted = pkg in AllowList.get(this)
            val staticScore = staticVerdict(pkg, force = false)?.final
            val now = System.currentTimeMillis()
            val recentEvents = EventLogger.recentEvents(pkg, since = now - CausalChains.LOOKBACK_MILLIS)
            val matchedChains = CausalChains.matchedChains(recentEvents, now, pkg)
            val result = riskEngine.evaluate(
                identity = identity,
                a11y = finding,
                posture = posture,
                mlpResult = mlpResult,
                networkThreatProbability = networkThreat,
                networkThreshold = LiveNetworkStore.threshold,
                trusted = trusted,
                threatList = threatList,
                causalBonus = CausalChains.bonus(matchedChains),
                recentEvents = recentEvents,
                a11yEnabledAt = enabledAt,
                staticScore = staticScore,
            )
            EventLogger.recordRisk(pkg, result.score, result.band.name)
            EventLogger.recordSignals(pkg, result)
            val contained = result.action == TrustPolicy.Action.CONTAIN &&
                NetworkPause.pauseAndConfirm(this, identity.uid)
            val explanation = Explanations.build(this, identity.appLabel, result, matchedChains)
            EvaluationStateStore.update {
                it.copy(
                    monitoring = true,
                    evaluating = false,
                    packageName = pkg,
                    score = result.score,
                    band = result.band.name,
                    explanation = explanation,
                    firedSignals = result.firedSignals.map { signal -> "${signal.id}: ${signal.description}" } +
                        matchedChains.map { chain -> "${chain.id}: ${chain.steps.joinToString(" → ")} (+${chain.bonus})" },
                    contained = contained,
                    trusted = trusted,
                    message = when {
                        contained -> "Critical risk contained; network access paused"
                        result.action == TrustPolicy.Action.CONTAIN -> "Critical risk found; containment could not be confirmed (VPN permission or package mapping)"
                        trusted -> "Evaluation complete (trusted app: no automatic action)"
                        else -> "Evaluation complete"
                    },
                )
            }
            if (result.action != TrustPolicy.Action.NONE) showRiskNotification(identity.appLabel, pkg, result, explanation)
            // Graduated response: suspicious but not critical -> watch its live traffic with the network model.
            if (result.action == TrustPolicy.Action.NOTIFY && !trusted && !identity.isSystemApp) {
                NetworkPause.requestInspect(this, identity.uid)
            }
            Log.i(
                TAG,
                "Evaluated $pkg score=${result.score} band=${result.band} action=${result.action} " +
                    "signals=${result.firedSignals.map { it.id }} chains=${matchedChains.map { it.id }}",
            )
        }.onFailure { error ->
            Log.e(TAG, "Evaluation failed for $pkg", error)
            EvaluationStateStore.update {
                it.copy(evaluating = false, packageName = pkg, message = error.message ?: "Evaluation failed")
            }
        }
    }

    private suspend fun showRiskNotification(
        appLabel: String,
        pkg: String,
        result: RiskEngine.Result,
        explanation: String,
    ) = withContext(Dispatchers.Main) {
        val notification = NotificationCompat.Builder(this@DhrashtaForegroundService, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_risk_title, getString(bandLabel(result.band)), appLabel))
            .setContentText(explanation)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this@DhrashtaForegroundService,
                    pkg.hashCode(),
                    Intent(this@DhrashtaForegroundService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(pkg.hashCode(), notification)
    }

    private suspend fun showStaticNotification(appLabel: String, pkg: String, verdict: StaticVerdict) =
        withContext(Dispatchers.Main) {
            val text = getString(R.string.notif_static_text, "%.2f".format(verdict.final))
            val notification = NotificationCompat.Builder(this@DhrashtaForegroundService, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.notif_static_title, appLabel))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this@DhrashtaForegroundService,
                        pkg.hashCode(),
                        Intent(this@DhrashtaForegroundService, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
            getSystemService(NotificationManager::class.java).notify(pkg.hashCode(), notification)
        }

    private fun bandLabel(band: RiskEngine.Band): Int = when (band) {
        RiskEngine.Band.CRITICAL -> R.string.badge_critical
        RiskEngine.Band.HIGH -> R.string.badge_high
        RiskEngine.Band.REVIEW -> R.string.badge_review
        RiskEngine.Band.SAFE -> R.string.badge_low
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun monitorNotification() = NotificationCompat.Builder(this, MONITOR_CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(getString(R.string.notif_monitor_title))
        .setContentText(getString(R.string.notif_monitor_text))
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
        const val ACTION_SCAN = "com.dhrashta.x.action.SCAN"
        const val ACTION_EVALUATE = "com.dhrashta.x.action.EVALUATE"
        const val ACTION_SELF_TEST = "com.dhrashta.x.action.SELF_TEST"
        const val ACTION_STATIC_SCAN = "com.dhrashta.x.action.STATIC_SCAN"
        const val EXTRA_PACKAGE = "package"
        private const val TAG = "DhrashtaMonitor"
        private const val MONITOR_CHANNEL = "accessibility_monitor"
        const val ALERT_CHANNEL = "threat_alerts"
        private const val MONITOR_NOTIFICATION_ID = 1001
        private const val PLAY_STORE = "com.android.vending"
        private const val LIVE_LOOKBACK_MILLIS = 10 * 60_000L

        /** Asks the running monitor to re-scan [pkg]'s APK with the static model. */
        fun requestStaticScan(context: Context, pkg: String) {
            runCatching {
                context.startService(
                    Intent(context, DhrashtaForegroundService::class.java)
                        .setAction(ACTION_STATIC_SCAN)
                        .putExtra(EXTRA_PACKAGE, pkg),
                )
            }.onFailure { Log.w(TAG, "Could not request static scan of $pkg", it) }
        }

        /** Asks the running monitor to re-evaluate [pkg] (skipped if it has no enabled accessibility service). */
        fun requestEvaluation(context: Context, pkg: String) {
            runCatching {
                context.startService(
                    Intent(context, DhrashtaForegroundService::class.java)
                        .setAction(ACTION_EVALUATE)
                        .putExtra(EXTRA_PACKAGE, pkg),
                )
            }.onFailure { Log.w(TAG, "Could not request re-evaluation of $pkg", it) }
        }
    }
}
