package com.dhrashta.x.ui

import android.Manifest
import android.content.Context
import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.dhrashta.x.R
import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.data.RiskScore
import com.dhrashta.x.decision.RiskEngine
import com.dhrashta.x.decision.Signal
import com.dhrashta.x.decision.SignalCatalogue
import com.dhrashta.x.decision.ThreatList
import com.dhrashta.x.sensing.A11yInspector
import com.dhrashta.x.ui.theme.Forest
import com.dhrashta.x.ui.theme.ForestSoft
import com.dhrashta.x.ui.theme.Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Static facts about one installed app, read from PackageManager. */
data class InstalledApp(
    val pkg: String,
    val label: String,
    val uid: Int,
    val versionName: String?,
    val installer: String?,
    val installerLabel: String?,
    val isSystem: Boolean,
    val firstInstallTime: Long,
    val hasLauncher: Boolean,
    val a11yEnabled: Boolean,
    val a11yIsTool: Boolean,
    val requestsOverlay: Boolean,
    val readsSms: Boolean,
    val installsPackages: Boolean,
    val inThreatList: Boolean,
    val mimicsBrand: Boolean,
) {
    val sideloaded: Boolean get() = !isSystem && installer != PLAY_STORE
}

enum class RiskLevel { Safe, Review, High, Critical }

/** One human-readable reason behind an app's score. */
data class Evidence(val title: String, val detail: String, val weight: Int)

/** An installed app joined with everything the engine and sensors know about it. */
data class AppRisk(
    val app: InstalledApp,
    val score: Int,
    val level: RiskLevel,
    val evidence: List<Evidence>,
    val engineSignals: List<Signal>,
    val fullAnalysis: Boolean,
    val lastReviewed: Long?,
    val paused: Boolean,
    val trusted: Boolean,
)

private const val PLAY_STORE = "com.android.vending"

/** Reads every installed app with the facts the UI and quick check need. Call off the main thread. */
suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val threatList = runCatching { ThreatList.load(context) }.getOrElse { ThreatList.empty() }
    val a11y = runCatching { A11yInspector(context).scan() }.getOrDefault(emptyList()).associateBy { it.packageName }
    val engine = RiskEngine()
    val launchable = pm.queryIntentActivities(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
        0,
    ).mapTo(HashSet()) { it.activityInfo.packageName }
    @Suppress("DEPRECATION")
    val packages = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrElse {
        // Very large app lists can exceed the binder limit; fall back to one package at a time.
        pm.getInstalledPackages(0).mapNotNull { runCatching { pm.getPackageInfo(it.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull() }
    }
    packages.mapNotNull { info ->
        val appInfo = info.applicationInfo ?: return@mapNotNull null
        if (info.packageName == context.packageName) return@mapNotNull null
        val label = pm.getApplicationLabel(appInfo).toString()
        val installer = installerOf(pm, info.packageName)
        val finding = a11y[info.packageName]
        InstalledApp(
            pkg = info.packageName,
            label = label,
            uid = appInfo.uid,
            versionName = info.versionName,
            installer = installer,
            installerLabel = installer?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() },
            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            firstInstallTime = info.firstInstallTime,
            hasLauncher = info.packageName in launchable,
            a11yEnabled = finding != null,
            a11yIsTool = finding?.isAccessibilityTool == true,
            requestsOverlay = info.requests(Manifest.permission.SYSTEM_ALERT_WINDOW),
            readsSms = info.granted(Manifest.permission.READ_SMS) || info.granted(Manifest.permission.RECEIVE_SMS),
            installsPackages = info.requests(Manifest.permission.REQUEST_INSTALL_PACKAGES),
            inThreatList = threatList.containsPackage(info.packageName),
            mimicsBrand = runCatching { engine.nameMimicsProtectedBrand(label, info.packageName) }.getOrDefault(false),
        )
    }.sortedBy { it.label.lowercase() }
}

@Suppress("DEPRECATION")
private fun installerOf(pm: PackageManager, pkg: String): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
} else {
    runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
}

private fun PackageInfo.requests(permission: String) = requestedPermissions?.contains(permission) == true

private fun PackageInfo.granted(permission: String): Boolean {
    val index = requestedPermissions?.indexOf(permission) ?: return false
    if (index < 0) return false
    val flags = requestedPermissionsFlags ?: return false
    return flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
}

private val SIGNALS_BY_ID = listOf(
    SignalCatalogue.A1, SignalCatalogue.A2, SignalCatalogue.A3, SignalCatalogue.A4, SignalCatalogue.A5,
    SignalCatalogue.A6, SignalCatalogue.B1, SignalCatalogue.B4, SignalCatalogue.B5, SignalCatalogue.B6,
    SignalCatalogue.C1, SignalCatalogue.C5, SignalCatalogue.D1, SignalCatalogue.D2, SignalCatalogue.E4,
    SignalCatalogue.E5, SignalCatalogue.HIGH_TAINT_MATCH, SignalCatalogue.N1, SignalCatalogue.N2, SignalCatalogue.N3,
).associateBy { it.id }

/**
 * Joins installed apps with engine scores and sensor events. Apps the engine has evaluated use its
 * score; the rest get a quick check from install source, permissions and observed behaviour, scored
 * with the same weights and 30/60/90 bands as RiskEngine.
 */
fun assessApps(
    res: Resources,
    apps: List<InstalledApp>,
    latestScores: List<RiskScore>,
    events: List<Event>,
    pausedUids: Set<Int>,
    trusted: Set<String>,
): List<AppRisk> {
    val scoreByPkg = latestScores.associateBy { it.pkg }
    val eventsByPkg = events.groupBy { it.pkg }
    return apps.map { app ->
        val appEvents = eventsByPkg[app.pkg].orEmpty()
        fun evs(title: Int, detail: String, weight: Int) = Evidence(res.getString(title), detail, weight)
        fun ev(title: Int, detail: Int, weight: Int) = evs(title, res.getString(detail), weight)
        val evidence = buildList {
            if (app.inThreatList) add(ev(R.string.ev_threat_title, R.string.ev_threat_detail, 50))
            if (appEvents.any { it.signalId == EventLogger.CANARY_READ }) add(ev(R.string.ev_canary_title, R.string.ev_canary_detail, 50))
            if (appEvents.any { it.signalId == EventLogger.BEACON_UNKNOWN_HOST }) add(ev(R.string.ev_beacon_title, R.string.ev_beacon_detail, 15))
            if (app.a11yEnabled && !app.a11yIsTool) add(ev(R.string.ev_a11y_title, R.string.ev_a11y_detail, 15))
            if (app.sideloaded) {
                val source = app.installerLabel ?: res.getString(R.string.source_unknown)
                add(evs(R.string.ev_sideload_title, res.getString(R.string.ev_sideload_detail, source), 15))
            }
            if (app.mimicsBrand) add(ev(R.string.ev_brand_title, R.string.ev_brand_detail, 20))
            if (!app.hasLauncher && !app.isSystem) add(ev(R.string.ev_hidden_title, R.string.ev_hidden_detail, 15))
            if (app.readsSms) add(ev(R.string.ev_sms_title, R.string.ev_sms_detail, 10))
            if (app.requestsOverlay) add(ev(R.string.ev_overlay_title, R.string.ev_overlay_detail, 10))
            if (app.installsPackages) add(ev(R.string.ev_install_title, R.string.ev_install_detail, 5))
        }
        val engineScore = scoreByPkg[app.pkg]
        val engineSignals = appEvents.filter { it.weight != 0 }.mapNotNull { SIGNALS_BY_ID[it.signalId] }.distinct()
        val isTrusted = app.pkg in trusted
        val quickScore = (evidence.sumOf { it.weight } + if (app.isSystem) SignalCatalogue.N3.weight else 0).coerceAtLeast(0)
        val score = when {
            isTrusted -> 0
            engineScore != null -> engineScore.score
            else -> quickScore
        }
        AppRisk(
            app = app,
            score = score,
            level = levelFor(score),
            evidence = evidence,
            engineSignals = engineSignals,
            fullAnalysis = engineScore != null,
            lastReviewed = engineScore?.timestamp,
            paused = app.uid in pausedUids,
            trusted = isTrusted,
        )
    }
}

/** Same thresholds as RiskEngine: 30 review, 60 high, 90 critical. */
fun levelFor(score: Int): RiskLevel = when {
    score >= 90 -> RiskLevel.Critical
    score >= 60 -> RiskLevel.High
    score >= 30 -> RiskLevel.Review
    else -> RiskLevel.Safe
}

private val iconCache = LruCache<String, ImageBitmap>(400)

/** The app's real launcher icon, loaded off the main thread and cached; a letter tile until loaded. */
@Composable
fun AppIcon(pkg: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState(iconCache.get(pkg), pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
                }.getOrNull()?.also { iconCache.put(pkg, it) }
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.clip(shape))
    } else {
        Box(modifier.background(ForestSoft, shape).border(1.dp, Line, shape), contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), color = Forest, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}
