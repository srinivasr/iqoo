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
import com.dhrashta.x.ai.NetworkThreatClassifier
import com.dhrashta.x.data.AllowList
import com.dhrashta.x.data.AppLanguage
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.data.ModelStatusStore
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
                val installer = runCatching { identityResolver.resolve(pkg).installer }.getOrNull()
                if (installer != PLAY_STORE) EventLogger.logEvent(pkg, EventLogger.SIDELOAD, installedAt)
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
        if (intent?.action == ACTION_SCAN) scanAllEnabledServices()
        if (intent?.action == ACTION_EVALUATE) intent.getStringExtra(EXTRA_PACKAGE)?.let(::evaluateIfAccessibilityEnabled)
        if (intent?.action == ACTION_SELF_TEST) serviceScope.launch { runNetworkSelfTest() }
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
                networkLiveInput = false,
            )
        }
        if (model != null) runNetworkSelfTest()
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
            // The network model needs complete traffic windows for this app. This build only captures
            // DNS lookups and paused apps, so there is no window to score: null, never a guessed value.
            val networkThreat: Float? = null
            val trusted = pkg in AllowList.get(this)
            val now = System.currentTimeMillis()
            val recentEvents = EventLogger.recentEvents(pkg, since = now - CausalChains.LOOKBACK_MILLIS)
            val matchedChains = CausalChains.matchedChains(recentEvents, now, pkg)
            val result = riskEngine.evaluate(
                identity = identity,
                a11y = finding,
                posture = posture,
                mlpResult = mlpResult,
                networkThreatProbability = networkThreat,
                trusted = trusted,
                threatList = threatList,
                causalBonus = CausalChains.bonus(matchedChains),
                recentEvents = recentEvents,
                a11yEnabledAt = enabledAt,
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
        const val EXTRA_PACKAGE = "package"
        private const val TAG = "DhrashtaMonitor"
        private const val MONITOR_CHANNEL = "accessibility_monitor"
        private const val ALERT_CHANNEL = "threat_alerts"
        private const val MONITOR_NOTIFICATION_ID = 1001
        private const val PLAY_STORE = "com.android.vending"

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
