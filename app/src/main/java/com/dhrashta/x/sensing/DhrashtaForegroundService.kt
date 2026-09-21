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
import com.dhrashta.x.ai.LlmExplainer
import com.dhrashta.x.ai.NetworkAnomalyModel
import com.dhrashta.x.data.AppLanguage
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.decision.CausalChains
import com.dhrashta.x.decision.RiskEngine
import com.dhrashta.x.decision.ThreatList
import com.dhrashta.x.enforcement.NetworkPause
import com.dhrashta.x.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private lateinit var networkModel: NetworkAnomalyModel
    private lateinit var explainer: LlmExplainer
    private val riskEngine = RiskEngine()

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
        accessibilityModel = AccessibilityMlpClassifier(this)
        networkModel = NetworkAnomalyModel(this)
        explainer = LlmExplainer(this)
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
                    "Monitoring accessibility services with rules; model asset is not installed"
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) scanAllEnabledServices()
        if (intent?.action == ACTION_EVALUATE) intent.getStringExtra(EXTRA_PACKAGE)?.let(::evaluateIfAccessibilityEnabled)
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
        networkModel.close()
        explainer.close()
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

    private suspend fun evaluate(pkg: String) {
        EvaluationStateStore.update { it.copy(evaluating = true, packageName = pkg, message = "Evaluating $pkg") }
        runCatching {
            val identity = identityResolver.resolve(pkg)
            val finding = inspector.scan().firstOrNull { it.packageName == pkg }
                ?: error("Accessibility service configuration is no longer enabled")
            val posture = postureChecker.check()
            val features = featureExtractor.extract(identity, finding, posture)
            val mlpResult = accessibilityModel.classify(features)
            // Network windows are supplied by the future 60-second network feature collector.
            // A null score is explicit and does not create a synthetic anomaly signal.
            val networkScore: Float? = null
            val allowList = getSharedPreferences(ALLOW_LIST_PREFS, MODE_PRIVATE)
                .getStringSet(ALLOW_LIST_KEY, emptySet()).orEmpty()
            val now = System.currentTimeMillis()
            val recentEvents = EventLogger.recentEvents(pkg, since = now - CausalChains.LOOKBACK_MILLIS)
            val matchedChains = CausalChains.matchedChains(recentEvents, now)
            val causalBonus = CausalChains.match(recentEvents, now)
            val result = riskEngine.evaluate(
                identity = identity,
                a11y = finding,
                posture = posture,
                mlpResult = mlpResult,
                networkScore = networkScore,
                allowList = allowList,
                threatList = threatList,
                causalBonus = causalBonus,
                recentEvents = recentEvents,
            )
            EventLogger.recordRisk(pkg, result.score, result.band.name)
            EventLogger.recordSignals(pkg, result)
            val contained = result.band == RiskEngine.Band.CRITICAL && NetworkPause.pause(this, identity.uid)
            val language = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(LANGUAGE_KEY, "English") ?: "English"
            val explanation = explainer.explain(
                appName = identity.appLabel,
                score = result.score,
                signals = result.firedSignals.map { it.id },
                language = language,
            )
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
                    message = when {
                        contained -> "Critical risk contained; network access paused"
                        result.band == RiskEngine.Band.CRITICAL -> "Critical risk found; VPN permission is required to contain it"
                        else -> "Evaluation complete"
                    },
                )
            }
            showRiskNotification(identity.appLabel, pkg, result, explanation)
            Log.i(
                TAG,
                "Evaluated $pkg score=${result.score} band=${result.band} signals=${result.firedSignals.map { it.id }} " +
                    "chains=${matchedChains.map { it.id }} causalBonus=$causalBonus",
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
        const val EXTRA_PACKAGE = "package"
        private const val TAG = "DhrashtaMonitor"
        private const val MONITOR_CHANNEL = "accessibility_monitor"
        private const val ALERT_CHANNEL = "threat_alerts"
        private const val MONITOR_NOTIFICATION_ID = 1001
        private const val ALLOW_LIST_PREFS = "allow_list"
        private const val ALLOW_LIST_KEY = "packages"
        private const val UI_PREFS = "ui_preferences"
        private const val LANGUAGE_KEY = "language"
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
