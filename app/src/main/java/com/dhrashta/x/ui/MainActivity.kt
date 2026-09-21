package com.dhrashta.x.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dhrashta.x.data.AllowList
import com.dhrashta.x.data.EvaluationStateStore
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.enforcement.CanaryManager
import com.dhrashta.x.enforcement.GuardVpnService
import com.dhrashta.x.enforcement.GuidedRecovery
import com.dhrashta.x.enforcement.NetworkPause
import com.dhrashta.x.sensing.DhrashtaForegroundService
import com.dhrashta.x.sensing.UsageWatcher
import com.dhrashta.x.ui.theme.DhrashtaTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf<Screen>(Screen.Home)
    private var refreshTick by mutableIntStateOf(0)
    private var resumedOnce = false
    private var pendingVpnAction: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CanaryManager.plantCanaries(this)
        refresh()
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingVpnAction
        pendingVpnAction = null
        if (result.resultCode == RESULT_OK) {
            NetworkPause.startDnsMonitor(this)
            action?.invoke()
        } else {
            toast("VPN permission is needed for network protection.")
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLogger.init(applicationContext)
        ContextCompat.startForegroundService(this, Intent(this, DhrashtaForegroundService::class.java))
        NetworkPause.startDnsMonitor(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        setContent { DhrashtaTheme { AppContent() } }
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce) refresh()
        resumedOnce = true
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    @Composable
    private fun AppContent() {
        val tick = refreshTick
        val evaluation by EvaluationStateStore.state.collectAsStateWithLifecycle()
        val scores by remember { EventLogger.observeLatestRiskScores(applicationContext) }.collectAsStateWithLifecycle(emptyList())
        val events by remember { EventLogger.observeEvents(applicationContext) }.collectAsStateWithLifecycle(emptyList())
        val connections by remember { EventLogger.observeConnections(applicationContext) }.collectAsStateWithLifecycle(emptyList())
        val installed by produceState<List<InstalledApp>?>(null, tick) { value = loadInstalledApps(applicationContext) }
        val models by produceState(emptyMap<String, Boolean>()) { value = withContext(Dispatchers.IO) { modelStatus() } }
        val pausedUids = remember(tick) { GuardVpnService.pausedUids(applicationContext) }
        val trusted = remember(tick) { AllowList.get(applicationContext) }
        val language = remember(tick) { prefs().getString(LANGUAGE_KEY, "English") ?: "English" }

        val apps = remember(installed, scores, events, pausedUids, trusted) {
            installed?.let { assessApps(it, scores, events, pausedUids, trusted) }
        }
        val labels = remember(installed) { installed.orEmpty().associate { it.pkg to it.label } }
        val packagesByUid = remember(installed) { installed.orEmpty().associate { it.uid to it.pkg } }
        val activity = remember(scores, events, connections, installed) {
            buildActivity(scores, events, connections, labels, packagesByUid, installed.orEmpty())
        }
        val status = remember(tick, evaluation.monitoring, models) {
            SettingsStatus(
                monitoring = evaluation.monitoring,
                vpnConsent = VpnService.prepare(this) == null,
                dnsMonitor = GuardVpnService.dnsMonitorEnabled(applicationContext),
                usageAccess = UsageWatcher.hasUsageAccess(applicationContext),
                notifications = notificationsEnabled(),
                contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED,
                models = models,
            )
        }
        val statusMessage = when {
            evaluation.evaluating -> "Checking ${evaluation.packageName?.let { labels[it] ?: it } ?: "apps"}…"
            else -> "Analysis stays on your phone."
        }

        val screen = destination
        val selected = (screen as? Screen.Details)?.pkg ?: (screen as? Screen.Paused)?.pkg
        val risk = selected?.let { pkg -> apps?.firstOrNull { it.app.pkg == pkg } }
        LaunchedEffect(apps, selected) {
            // The app was uninstalled (or is not visible to us); leave its screen.
            if (selected != null && apps != null && risk == null) destination = Screen.Home
        }

        when (screen) {
            Screen.Home -> DashboardScreen(
                apps = apps,
                monitoring = evaluation.monitoring,
                statusMessage = statusMessage,
                recent = activity,
                labels = labels,
                onOpenApp = { destination = Screen.Details(it) },
                onActivity = { destination = Screen.Activity },
                onSettings = { destination = Screen.Settings },
            )
            Screen.Activity -> ActivityScreen(
                items = activity,
                labels = labels,
                onOpenApp = { destination = Screen.Details(it) },
                onHome = { destination = Screen.Home },
                onSettings = { destination = Screen.Settings },
            )
            Screen.Settings -> SettingsScreen(
                status = status,
                trustedApps = installed.orEmpty().filter { it.pkg in trusted },
                language = language,
                onHome = { destination = Screen.Home },
                onActivity = { destination = Screen.Activity },
                onScanNow = ::scanNow,
                onEnableDnsMonitor = { withVpnConsent { NetworkPause.startDnsMonitor(this) } },
                onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                onNotifications = ::requestNotifications,
                onContacts = { contactsPermission.launch(Manifest.permission.WRITE_CONTACTS) },
                onAccessibilitySettings = { GuidedRecovery.openA11ySettings(this) },
                onLanguage = { value -> prefs().edit().putString(LANGUAGE_KEY, value).apply(); refresh() },
                onUntrust = { pkg -> setTrusted(pkg, false) },
                onOpenApp = { destination = Screen.Details(it) },
            )
            is Screen.Details -> if (risk == null) {
                LoadingCard("Loading app…")
            } else {
                RiskDetailsScreen(
                    risk = risk,
                    explanation = evaluation.explanation?.takeIf { evaluation.packageName == risk.app.pkg },
                    onBack = { destination = Screen.Home },
                    onPauseInternet = { withVpnConsent { pause(risk) } },
                    onOpenPaused = { destination = Screen.Paused(risk.app.pkg) },
                    onReviewPermissions = { GuidedRecovery.openAppInfo(this, risk.app.pkg) },
                    onUninstall = { GuidedRecovery.requestUninstall(this, risk.app.pkg) },
                    onTrust = { trust -> setTrusted(risk.app.pkg, trust) },
                    onListen = ::speak,
                    onAnalyze = {
                        DhrashtaForegroundService.requestEvaluation(this, risk.app.pkg)
                        toast("Analyzing ${risk.app.label}…")
                    },
                )
            }
            is Screen.Paused -> if (risk == null) {
                LoadingCard("Loading app…")
            } else {
                val blocked = connections.filter { it.uid == risk.app.uid }
                InternetPausedScreen(
                    risk = risk,
                    blockedCount = blocked.size,
                    lastBlocked = blocked.maxOfOrNull { it.timestamp },
                    onBack = { destination = Screen.Details(risk.app.pkg) },
                    onResumeInternet = { resume(risk) },
                    onReviewPermissions = { GuidedRecovery.openAppInfo(this, risk.app.pkg) },
                    onUninstall = { GuidedRecovery.requestUninstall(this, risk.app.pkg) },
                )
            }
        }
    }

    private fun pause(risk: AppRisk) {
        if (NetworkPause.pause(this, risk.app.uid)) {
            destination = Screen.Paused(risk.app.pkg)
            refreshSoon()
        } else {
            toast("Could not pause ${risk.app.label}. Grant VPN permission and try again.")
        }
    }

    private fun resume(risk: AppRisk) {
        NetworkPause.resume(this, risk.app.uid)
        toast("Internet access resumed for ${risk.app.label}")
        destination = Screen.Details(risk.app.pkg)
        refreshSoon()
    }

    private fun setTrusted(pkg: String, trusted: Boolean) {
        AllowList.set(this, pkg, trusted)
        DhrashtaForegroundService.requestEvaluation(this, pkg)
        refresh()
    }

    /** Runs [action] once VPN consent exists, asking for it first if needed. */
    private fun withVpnConsent(action: () -> Unit) {
        val consent = VpnService.prepare(this)
        if (consent == null) {
            action()
        } else {
            pendingVpnAction = action
            vpnPermission.launch(consent)
        }
    }

    private fun scanNow() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, DhrashtaForegroundService::class.java).setAction(DhrashtaForegroundService.ACTION_SCAN),
        )
        refresh()
        toast("Scanning apps…")
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
            .recoverCatching { startActivity(Intent(action)) }
            .onFailure { toast("This setting is not available on this phone.") }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            toast("Text-to-speech is not ready on this phone.")
            return
        }
        engine.language = when (prefs().getString(LANGUAGE_KEY, "English")) {
            "Hindi" -> Locale("hi", "IN")
            "Bengali" -> Locale("bn", "IN")
            else -> Locale("en", "IN")
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "explanation")
    }

    private fun modelStatus(): Map<String, Boolean> {
        val assets = assets.list("").orEmpty().toSet()
        return linkedMapOf(
            "Accessibility behaviour model" to ("accessibility_mlp.tflite" in assets),
            "Network anomaly model" to ("network_anomaly.tflite" in assets),
            "Explanation model (SmolLM2)" to ("smollm2_360m.gguf" in assets),
        )
    }

    private fun notificationsEnabled() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun prefs() = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

    private fun refresh() {
        refreshTick++
    }

    /** Services persist pause state asynchronously; re-read shortly after. */
    private fun refreshSoon() {
        lifecycleScope.launch {
            delay(600)
            refresh()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private sealed interface Screen {
        data object Home : Screen
        data object Activity : Screen
        data object Settings : Screen
        data class Details(val pkg: String) : Screen
        data class Paused(val pkg: String) : Screen
    }

    private companion object {
        const val UI_PREFS = "ui_preferences"
        const val LANGUAGE_KEY = "language"
    }
}
