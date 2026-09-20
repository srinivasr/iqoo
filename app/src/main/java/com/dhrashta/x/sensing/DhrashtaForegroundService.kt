package com.dhrashta.x.sensing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dhrashta.x.R
import com.dhrashta.x.ai.AccessibilityFeatureExtractor
import com.dhrashta.x.ai.AccessibilityMlpClassifier
import com.dhrashta.x.ai.LlmExplainer
import com.dhrashta.x.ai.NetworkAnomalyModel
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
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
    private lateinit var identityResolver: IdentityResolver
    private lateinit var inspector: A11yInspector
    private lateinit var postureChecker: PostureChecker
    private lateinit var threatList: ThreatList
    private lateinit var featureExtractor: AccessibilityFeatureExtractor
    private lateinit var accessibilityModel: AccessibilityMlpClassifier
    private lateinit var networkModel: NetworkAnomalyModel
    private lateinit var explainer: LlmExplainer
    private val riskEngine = RiskEngine()

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
        observer = A11yObserver(this) { packages -> packages.forEach(::evaluateAsync) }
        packageWatcher = PackageWatcher(this) { pkg ->
            EvaluationStateStore.update { it.copy(message = "New app installed: $pkg") }
        }
        observer.start()
        packageWatcher.start()
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer.stop()
        packageWatcher.stop()
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
            val result = riskEngine.evaluate(
                identity = identity,
                a11y = finding,
                posture = posture,
                mlpResult = mlpResult,
                networkScore = networkScore,
                allowList = allowList,
                threatList = threatList,
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
                    firedSignals = result.firedSignals.map { signal -> "${signal.id}: ${signal.description}" },
                    contained = contained,
                    message = when {
                        contained -> "Critical risk contained; network access paused"
                        result.band == RiskEngine.Band.CRITICAL -> "Critical risk found; VPN permission is required to contain it"
                        else -> "Evaluation complete"
                    },
                )
            }
            showRiskNotification(identity.appLabel, pkg, result, explanation)
            Log.i(TAG, "Evaluated $pkg score=${result.score} band=${result.band} signals=${result.firedSignals.map { it.id }}")
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
            .setContentTitle("${result.band.name.lowercase().replaceFirstChar(Char::uppercase)} accessibility risk: $appLabel")
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
        .setContentTitle("DHRASHTA-X is monitoring")
        .setContentText("Watching for newly enabled accessibility services")
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
        private const val TAG = "DhrashtaMonitor"
        private const val MONITOR_CHANNEL = "accessibility_monitor"
        private const val ALERT_CHANNEL = "threat_alerts"
        private const val MONITOR_NOTIFICATION_ID = 1001
        private const val ALLOW_LIST_PREFS = "allow_list"
        private const val ALLOW_LIST_KEY = "packages"
        private const val UI_PREFS = "ui_preferences"
        private const val LANGUAGE_KEY = "language"
    }
}
