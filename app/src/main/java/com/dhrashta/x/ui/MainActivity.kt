package com.dhrashta.x.ui

import android.Manifest
import android.content.Context
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
import com.dhrashta.x.R
import com.dhrashta.x.data.AllowList
import com.dhrashta.x.data.AppLanguage
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
            toast(getString(R.string.toast_vpn_needed))
        }
        refresh()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLogger.init(applicationContext)
        ContextCompat.startForegroundService(this, Intent(this, DhrashtaForegroundService::class.java))
        NetworkPause.startDnsMonitor(this)
        val firstRun = !AppLanguage.isChosen(this)
        // Changing language recreates the Activity; stay on the picker when that happens.
        if (firstRun || savedInstanceState?.getBoolean(STATE_ON_LANGUAGE) == true) {
            destination = Screen.Language(firstRun)
        } else {
            requestNotificationsOnce()
        }
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        setContent { DhrashtaTheme { AppContent() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ON_LANGUAGE, destination is Screen.Language)
    }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        val models by produceState(emptyMap<Int, Boolean>()) { value = withContext(Dispatchers.IO) { modelStatus() } }
        val pausedUids = remember(tick) { GuardVpnService.pausedUids(applicationContext) }
        val trusted = remember(tick) { AllowList.get(applicationContext) }
        val language = remember(tick) { AppLanguage.current(this) }

        val apps = remember(installed, scores, events, pausedUids, trusted) {
            installed?.let { assessApps(resources, it, scores, events, pausedUids, trusted) }
        }
        val labels = remember(installed) { installed.orEmpty().associate { it.pkg to it.label } }
        val packagesByUid = remember(installed) { installed.orEmpty().associate { it.uid to it.pkg } }
        val activity = remember(scores, events, connections, installed) {
            buildActivity(resources, scores, events, connections, labels, packagesByUid, installed.orEmpty())
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
            evaluation.evaluating && evaluation.packageName != null ->
                getString(R.string.home_status_checking, labels[evaluation.packageName] ?: evaluation.packageName)
            else -> getString(R.string.home_status_private)
        }

        val screen = destination
        val selected = (screen as? Screen.Details)?.pkg ?: (screen as? Screen.Paused)?.pkg
        val risk = selected?.let { pkg -> apps?.firstOrNull { it.app.pkg == pkg } }
        LaunchedEffect(apps, selected) {
            // The app was uninstalled (or is not visible to us); leave its screen.
            if (selected != null && apps != null && risk == null) destination = Screen.Home
        }

        when (screen) {
            is Screen.Language -> LanguagePickerScreen(
                selectedTag = language.tag,
                onSelect = { tag -> if (tag != language.tag) AppLanguage.apply(this, tag) },
                onContinue = {
                    if (screen.firstRun) {
                        AppLanguage.markChosen(this)
                        requestNotificationsOnce()
                    }
                    destination = if (screen.firstRun) Screen.Home else Screen.Settings
                },
                onBack = if (screen.firstRun) null else ({ destination = Screen.Settings }),
            )
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
                onChangeLanguage = { destination = Screen.Language(firstRun = false) },
                onUntrust = { pkg -> setTrusted(pkg, false) },
                onOpenApp = { destination = Screen.Details(it) },
            )
            is Screen.Details -> if (risk == null) {
                LoadingCard(getString(R.string.loading_app))
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
                        toast(getString(R.string.toast_analyzing, risk.app.label))
                    },
                )
            }
            is Screen.Paused -> if (risk == null) {
                LoadingCard(getString(R.string.loading_app))
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
            toast(getString(R.string.toast_pause_failed, risk.app.label))
        }
    }

    private fun resume(risk: AppRisk) {
        NetworkPause.resume(this, risk.app.uid)
        toast(getString(R.string.toast_resumed, risk.app.label))
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
        toast(getString(R.string.toast_scanning))
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
            .onFailure { toast(getString(R.string.toast_setting_unavailable)) }
    }

    private fun speak(text: String) {
        val engine = tts
        val locale = Locale(AppLanguage.currentTag(this), "IN")
        if (engine == null || !ttsReady || engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            toast(getString(R.string.toast_tts_unavailable))
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "explanation")
    }

    private fun modelStatus(): Map<Int, Boolean> {
        val assets = assets.list("").orEmpty().toSet()
        return linkedMapOf(
            R.string.model_a11y to ("accessibility_mlp.tflite" in assets),
            R.string.model_network to ("network_anomaly.tflite" in assets),
            R.string.model_llm to ("smollm2_360m.gguf" in assets),
        )
    }

    private fun notificationsEnabled() = NotificationManagerCompat.from(this).areNotificationsEnabled()

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
        data class Language(val firstRun: Boolean) : Screen
    }

    private companion object {
        const val STATE_ON_LANGUAGE = "on_language_screen"
    }
}
