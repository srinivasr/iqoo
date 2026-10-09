package com.dhrashta.x.ai.staticml

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dhrashta.x.data.StaticVerdict
import java.io.File

/**
 * Builds [ApkFacts] for an installed package from PackageManager plus its base APK on disk (which any
 * app may read), runs the Kotlin port of extract_v6.py and scores it with [StaticModel].
 * Fully on-device: the APK never leaves the phone.
 */
class StaticApkScanner(private val context: Context, private val model: StaticModel) {
    private val pm = context.packageManager

    fun scan(pkg: String): StaticVerdict {
        val started = System.currentTimeMillis()
        val info = packageInfo(pkg)
        val appInfo = info.applicationInfo ?: error("No application info for $pkg")
        val apk = File(appInfo.sourceDir ?: error("No APK path for $pkg"))
        require(apk.canRead()) { "Cannot read ${apk.path}" }
        val facts = ApkFacts(
            pkg = pkg,
            permissions = info.requestedPermissions.orEmpty().toList(),
            services = info.services.orEmpty().map { it.name },
            activities = info.activities.orEmpty().map { it.name },
            receivers = info.receivers.orEmpty().map { it.name },
            hasLauncher = pm.getLaunchIntentForPackage(pkg) != null,
            apkFile = apk,
        )
        val features = model.extractor.extract(facts)
        val score = model.score(features.fired)
        val verdict = StaticVerdict(
            pkg = pkg,
            apkUpdatedAt = info.lastUpdateTime,
            base = score.base,
            boost = score.boost,
            final = score.final,
            rules = score.contributors.map { it.first },
            featuresFired = features.fired.size,
            dexFiles = features.dexFiles,
            millis = System.currentTimeMillis() - started,
            scannedAt = System.currentTimeMillis(),
        )
        Log.i(TAG, "S0 $pkg base=${"%.3f".format(score.base)} boost=${"%.2f".format(score.boost)} " +
            "final=${"%.3f".format(score.final)} features=${features.fired.size} dex=${features.dexFiles} " +
            "methods=${features.methods} ${verdict.millis}ms rules=${verdict.rules}")
        return verdict
    }

    /** User-installed and updated-system apps worth scanning (pure system images are skipped). */
    fun candidates(): List<Pair<String, Long>> {
        @Suppress("DEPRECATION")
        val all = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        return all.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (p.packageName == context.packageName || (system && !updatedSystem)) null else p.packageName to p.lastUpdateTime
        }
    }

    fun lastUpdateTime(pkg: String): Long? = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo {
        val full = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        return runCatching { get(pkg, full) }.getOrElse {
            // Huge manifests can exceed the binder limit in one call: fetch the parts separately.
            val base = get(pkg, PackageManager.GET_PERMISSIONS)
            base.services = runCatching { get(pkg, PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS).services }.getOrNull()
            base.activities = runCatching { get(pkg, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).activities }.getOrNull()
            base.receivers = runCatching { get(pkg, PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS).receivers }.getOrNull()
            base
        }
    }

    @Suppress("DEPRECATION")
    private fun get(pkg: String, flags: Int): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        pm.getPackageInfo(pkg, flags)
    }

    companion object {
        private const val TAG = "DhrashtaS0"
    }
}
