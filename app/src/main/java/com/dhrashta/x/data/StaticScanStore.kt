package com.dhrashta.x.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Result of the S0 static scan of one installed APK. */
data class StaticVerdict(
    val pkg: String,
    /** PackageInfo.lastUpdateTime of the APK that was scanned; a newer install invalidates the verdict. */
    val apkUpdatedAt: Long,
    val base: Float,
    val boost: Float,
    val final: Float,
    /** Rule features that added to the boost, strongest first (e.g. MODERN_SMS_TRIAD). */
    val rules: List<String>,
    val featuresFired: Int,
    val dexFiles: Int,
    val millis: Long,
    val scannedAt: Long,
) {
    val level: Level get() = when {
        final >= HIGH -> Level.HIGH
        final >= MEDIUM -> Level.MEDIUM
        else -> Level.LOW
    }

    enum class Level { LOW, MEDIUM, HIGH }

    companion object {
        const val HIGH = 0.5f
        const val MEDIUM = 0.2f
    }
}

/** Static verdicts, kept in memory for the UI and in SharedPreferences across restarts. */
object StaticScanStore {
    private const val PREFS = "static_scan"
    private val mutable = MutableStateFlow<Map<String, StaticVerdict>>(emptyMap())
    val state = mutable.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutable.value = prefs.all.mapNotNull { (pkg, raw) -> runCatching { decode(pkg, raw as String) }.getOrNull() }
                .associateBy { it.pkg }
            loaded = true
        }
    }

    fun get(context: Context, pkg: String): StaticVerdict? {
        load(context)
        return mutable.value[pkg]
    }

    fun put(context: Context, verdict: StaticVerdict) {
        load(context)
        synchronized(this) { mutable.value = mutable.value + (verdict.pkg to verdict) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(verdict.pkg, encode(verdict)).apply()
    }

    fun remove(context: Context, pkg: String) {
        load(context)
        synchronized(this) { mutable.value = mutable.value - pkg }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pkg).apply()
    }

    private fun encode(v: StaticVerdict) = JSONObject()
        .put("u", v.apkUpdatedAt).put("b", v.base.toDouble()).put("o", v.boost.toDouble()).put("f", v.final.toDouble())
        .put("r", JSONArray(v.rules)).put("n", v.featuresFired).put("d", v.dexFiles).put("m", v.millis).put("t", v.scannedAt)
        .toString()

    private fun decode(pkg: String, raw: String): StaticVerdict {
        val o = JSONObject(raw)
        val rules = o.getJSONArray("r")
        return StaticVerdict(
            pkg, o.getLong("u"), o.getDouble("b").toFloat(), o.getDouble("o").toFloat(), o.getDouble("f").toFloat(),
            List(rules.length()) { rules.getString(it) }, o.getInt("n"), o.getInt("d"), o.getLong("m"), o.getLong("t"),
        )
    }
}
