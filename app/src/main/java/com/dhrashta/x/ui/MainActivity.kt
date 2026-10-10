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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.dhrashta.x.ui.theme.Canvas
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
import com.dhrashta.x.data.ModelStatus
import com.dhrashta.x.data.ModelStatusStore
import com.dhrashta.x.data.MotionState
import com.dhrashta.x.data.SensorStateStore
import com.dhrashta.x.data.StaticScanStore
import com.dhrashta.x.data.LiveNetworkStore
import com.dhrashta.x.decision.ThreatList
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
import com.dhrashta.x.ui.theme.AppThemeMode
import com.dhrashta.x.ui.theme.ThemePreference
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf<Screen>(Screen.Home)
    private var themeMode by mutableStateOf(AppThemeMode.Dark)
    private var refreshTick by mutableIntStateOf(0)
    private var resumedOnce = false
    private var pendingVpnAction: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    // Decoys are opt-in: this launcher runs only after the user taps "Turn on" for decoys. If the
    // contacts permission is denied, the non-contact decoys are still planted.
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        CanaryManager.setEnabled(this, true)
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
        themeMode = ThemePreference.read(this)
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
        // Target SDK 35+ draws edge-to-edge; pad content by the system bars so nothing sits under them.
        enableEdgeToEdge()
        setContent {
            DhrashtaTheme(themeMode) {
                Box(Modifier.fillMaxSize().background(Canvas).safeDrawingPadding()) { AppContent() }
            }
        }
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
        val modelStatus by ModelStatusStore.state.collectAsStateWithLifecycle()
        val sensorCtx by SensorStateStore.state.collectAsStateWithLifecycle()
        val staticVerdicts by remember { StaticScanStore.load(applicationContext); StaticScanStore.state }.collectAsStateWithLifecycle()
        val liveScores by LiveNetworkStore.state.collectAsStateWithLifecycle()
        val threatInfo by produceState<ThreatList.Info?>(null, tick) {
            value = withContext(Dispatchers.IO) { runCatching { ThreatList.info(applicationContext) }.getOrNull() }
        }
        // Only UIDs inside an ESTABLISHED tunnel are shown as paused; a request is not containment.
        val pausedUids = remember(tick) { GuardVpnService.containedUids(applicationContext) }
        val inspectingUids = remember(tick, liveScores) { GuardVpnService.inspectedUids(applicationContext) }
        val trusted = remember(tick) { AllowList.get(applicationContext) }
        val language = remember(tick) { AppLanguage.current(this) }

        val apps = remember(installed, scores, events, pausedUids, trusted, staticVerdicts, inspectingUids, liveScores) {
            installed?.let {
                assessApps(
                    resources, it, scores, events, pausedUids, trusted, staticVerdicts,
                    inspectingUids, liveScores, LiveNetworkStore.threshold,
                )
            }
        }
        val labels = remember(installed) { installed.orEmpty().associate { it.pkg to it.label } }
        val packagesByUid = remember(installed) { installed.orEmpty().associate { it.uid to it.pkg } }
        val activity = remember(scores, events, connections, installed) {
            buildActivity(resources, scores, events, connections, labels, packagesByUid, installed.orEmpty())
        }
        val status = remember(tick, evaluation.monitoring, modelStatus, threatInfo, inspectingUids, sensorCtx) {
            val dnsOn = VpnService.prepare(this) == null && GuardVpnService.dnsMonitorEnabled(applicationContext)
            SettingsStatus(
                monitoring = evaluation.monitoring,
                vpnConsent = VpnService.prepare(this) == null,
                dnsMonitor = GuardVpnService.dnsMonitorEnabled(applicationContext),
                usageAccess = UsageWatcher.hasUsageAccess(applicationContext),
                notifications = notificationsEnabled(),
                decoys = CanaryManager.isEnabled(applicationContext),
                coverage = coverage(evaluation.monitoring, dnsOn, modelStatus, threatInfo, inspectingUids.size, sensorCtx),
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
                themeMode = themeMode,
                onThemeChange = { mode ->
                    ThemePreference.save(this, mode)
                    themeMode = mode
                },
                onHome = { destination = Screen.Home },
                onActivity = { destination = Screen.Activity },
                onScanNow = ::scanNow,
                onEnableDnsMonitor = ::toggleDnsMonitor,
                onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                onNotifications = ::toggleNotifications,
                onDecoys = ::toggleDecoys,
                onSelfTest = ::runSelfTest,
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
                    onInspect = { on -> withVpnConsent { inspect(risk, on) } },
                    liveThreshold = LiveNetworkStore.threshold,
                    onAnalyze = {
                        DhrashtaForegroundService.requestStaticScan(this, risk.app.pkg)
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

    /** Navigates to the "paused" screen only after the tunnel containing the app really exists. */
    private fun pause(risk: AppRisk) {
        toast(getString(R.string.toast_pausing, risk.app.label))
        lifecycleScope.launch {
            val contained = NetworkPause.pauseAndConfirm(this@MainActivity, risk.app.uid)
            refresh()
            if (contained) {
                destination = Screen.Paused(risk.app.pkg)
            } else {
                toast(getString(R.string.toast_pause_failed, risk.app.label))
            }
        }
    }

    /** Starts or stops relaying this app's traffic into the live network model. */
    private fun inspect(risk: AppRisk, on: Boolean) {
        if (on) {
            if (NetworkPause.requestInspect(this, risk.app.uid)) toast(getString(R.string.toast_inspect_on, risk.app.label))
        } else {
            NetworkPause.stopInspect(this, risk.app.uid)
            toast(getString(R.string.toast_inspect_off, risk.app.label))
        }
        refreshSoon()
    }

    private fun toggleDecoys() {
        if (CanaryManager.isEnabled(this)) {
            CanaryManager.setEnabled(this, false)
            toast(getString(R.string.toast_decoys_off))
            refresh()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            CanaryManager.setEnabled(this, true)
            refresh()
        } else {
            contactsPermission.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    private fun toggleDnsMonitor() {
        if (GuardVpnService.dnsMonitorEnabled(this)) {
            NetworkPause.stopDnsMonitor(this)
            refresh()
        } else {
            withVpnConsent {
                NetworkPause.startDnsMonitor(this)
                refresh()
            }
        }
    }

    private fun toggleNotifications() {
        if (notificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            requestNotifications()
        }
    }

    private fun runSelfTest() {
        runCatching {
            startService(Intent(this, DhrashtaForegroundService::class.java).setAction(DhrashtaForegroundService.ACTION_SELF_TEST))
        }
        toast(getString(R.string.toast_selftest))
    }

    /** Every row is derived from live state; nothing is listed as watched unless it is. */
    private fun coverage(
        monitoring: Boolean,
        dnsOn: Boolean,
        model: ModelStatus,
        threats: ThreatList.Info?,
        watched: Int,
        sensor: com.dhrashta.x.data.SensorContext,
    ): List<CoverageItem> = listOf(
        CoverageItem(
            getString(R.string.cov_sensor),
            if (!SensorStateStore.isSensorMonitoringEnabled(this)) {
                "Sensor monitoring turned off by user"
            } else if (SensorStateStore.available) {
                getString(
                    R.string.cov_sensor_detail,
                    getString(motionLabel(sensor.motion)),
                    getString(if (sensor.screenOn) R.string.screen_on else R.string.screen_off),
                    if (sensor.charging) getString(R.string.charging) else "${sensor.batteryLevel}%",
                )
            } else {
                getString(R.string.cov_sensor_waiting)
            },
            if (SensorStateStore.isSensorMonitoringEnabled(this) && SensorStateStore.available) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !SensorStateStore.isSensorMonitoringEnabled(this)
                SensorStateStore.setSensorMonitoringEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_btexfil),
            if (!SensorStateStore.isBtWatchEnabled(this)) {
                "Offline exfiltration watch turned off by user"
            } else if (!SensorStateStore.available) {
                getString(R.string.cov_btexfil_idle)
            } else if (sensor.offline && sensor.bluetoothOn) {
                getString(R.string.cov_btexfil_risk)
            } else {
                getString(R.string.cov_btexfil_ok)
            },
            if (SensorStateStore.isBtWatchEnabled(this) && !(sensor.offline && sensor.bluetoothOn)) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !SensorStateStore.isBtWatchEnabled(this)
                SensorStateStore.setBtWatchEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_a11y),
            getString(R.string.cov_a11y_detail),
            if (monitoring) CoverageState.On else CoverageState.Off,
            onToggle = { GuidedRecovery.openA11ySettings(this) },
        ),
        CoverageItem(
            getString(R.string.cov_dns),
            getString(R.string.cov_dns_detail),
            if (dnsOn) CoverageState.On else CoverageState.Off,
            onToggle = ::toggleDnsMonitor,
        ),
        CoverageItem(
            getString(R.string.cov_traffic),
            getString(if (watched > 0) R.string.cov_traffic_detail_live else R.string.cov_traffic_detail),
            if (watched > 0) CoverageState.On else CoverageState.NotMonitored,
        ),
        CoverageItem(
            getString(R.string.cov_bank),
            getString(R.string.cov_bank_detail),
            if (UsageWatcher.hasUsageAccess(applicationContext)) CoverageState.On else CoverageState.Off,
            onToggle = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        ),
        CoverageItem(
            getString(R.string.cov_decoys),
            getString(R.string.cov_decoys_detail),
            if (CanaryManager.isEnabled(applicationContext)) CoverageState.On else CoverageState.Off,
            onToggle = ::toggleDecoys,
        ),
        CoverageItem(
            getString(R.string.cov_netmodel),
            if (!ModelStatusStore.isNetModelEnabled(this)) {
                "Model inference turned off by user"
            } else if (model.networkLoaded) {
                getString(
                    R.string.cov_netmodel_detail,
                    model.networkBackend.orEmpty(),
                    model.networkSelfTestSummary ?: getString(R.string.selftest_pending),
                )
            } else {
                getString(R.string.cov_model_missing)
            },
            if (ModelStatusStore.isNetModelEnabled(this) && model.networkLoaded && model.networkSelfTestPassed == true) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !ModelStatusStore.isNetModelEnabled(this)
                ModelStatusStore.setNetModelEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_netmodel_live),
            if (!ModelStatusStore.isLiveModelEnabled(this)) {
                "Live network analysis turned off by user"
            } else if (model.liveLoaded) {
                getString(
                    R.string.cov_netmodel_live_detail_on,
                    watched,
                    model.liveBackend.orEmpty(),
                    model.liveSelfTestSummary ?: getString(R.string.selftest_pending),
                )
            } else {
                getString(R.string.cov_model_missing)
            },
            if (ModelStatusStore.isLiveModelEnabled(this) && model.liveLoaded) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !ModelStatusStore.isLiveModelEnabled(this)
                ModelStatusStore.setLiveModelEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_static),
            if (!ModelStatusStore.isStaticModelEnabled(this)) {
                "Static APK scan turned off by user"
            } else if (model.staticLoaded) {
                val progress = model.staticProgress?.let { (done, total) -> getString(R.string.static_scanning, done, total) }
                getString(
                    R.string.cov_static_detail,
                    model.staticBackend.orEmpty(),
                    progress ?: model.staticSelfTestSummary ?: getString(R.string.selftest_pending),
                    model.staticScanned,
                )
            } else {
                getString(R.string.cov_static_missing)
            },
            if (ModelStatusStore.isStaticModelEnabled(this) && model.staticLoaded && model.staticSelfTestPassed == true) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !ModelStatusStore.isStaticModelEnabled(this)
                ModelStatusStore.setStaticModelEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_a11ymodel),
            if (!ModelStatusStore.isA11yModelEnabled(this)) {
                "Accessibility model turned off by user"
            } else if (model.a11yModelLoaded) {
                model.a11yBackend.orEmpty()
            } else {
                getString(R.string.cov_a11ymodel_missing)
            },
            if (ModelStatusStore.isA11yModelEnabled(this)) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !ModelStatusStore.isA11yModelEnabled(this)
                ModelStatusStore.setA11yModelEnabled(this, next)
                refresh()
            },
        ),
        CoverageItem(
            getString(R.string.cov_threats),
            if (!ModelStatusStore.isThreatListEnabled(this)) {
                "Threat intelligence feed turned off by user"
            } else {
                threats?.takeIf { it.entries > 0 }?.let { getString(R.string.cov_threats_detail, it.entries, it.source) }
                    ?: getString(R.string.cov_threats_none)
            },
            if (ModelStatusStore.isThreatListEnabled(this) && (threats?.entries ?: 0) > 0) CoverageState.On else CoverageState.Off,
            onToggle = {
                val next = !ModelStatusStore.isThreatListEnabled(this)
                ModelStatusStore.setThreatListEnabled(this, next)
                refresh()
            },
        ),
    )

    private fun motionLabel(m: MotionState): Int = when (m) {
        MotionState.STATIONARY -> R.string.motion_stationary
        MotionState.WALKING -> R.string.motion_walking
        MotionState.ACTIVE -> R.string.motion_active
        MotionState.UNKNOWN -> R.string.motion_unknown
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
