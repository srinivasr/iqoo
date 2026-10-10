package com.dhrashta.x.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the on-device models really are doing right now, for the coverage card. */
data class ModelStatus(
    val networkLoaded: Boolean = false,
    val networkModelFile: String? = null,
    val networkBackend: String? = null,
    val networkSelfTestPassed: Boolean? = null,
    val networkSelfTestSummary: String? = null,
    /** True while at least one app's traffic is relayed and scored by the live network model. */
    val networkLiveInput: Boolean = false,
    val liveLoaded: Boolean = false,
    val liveBackend: String? = null,
    val liveSelfTestPassed: Boolean? = null,
    val liveSelfTestSummary: String? = null,
    val a11yModelLoaded: Boolean = false,
    val a11yBackend: String? = null,
    /** S0 static APK model (LightGBM -> ONNX, CPU). */
    val staticLoaded: Boolean = false,
    val staticBackend: String? = null,
    val staticSelfTestPassed: Boolean? = null,
    val staticSelfTestSummary: String? = null,
    val staticScanned: Int = 0,
    /** Non-null while a scan of installed apps is running, e.g. "12/40". */
    val staticProgress: Pair<Int, Int>? = null,
)

object ModelStatusStore {
    private const val PREFS = "dhrashta_model_toggles"
    private const val KEY_NET_MODEL = "net_model_enabled"
    private const val KEY_LIVE_MODEL = "live_model_enabled"
    private const val KEY_STATIC_MODEL = "static_model_enabled"
    private const val KEY_A11Y_MODEL = "a11y_model_enabled"
    private const val KEY_THREAT_LIST = "threat_list_enabled"

    private val mutable = MutableStateFlow(ModelStatus())
    val state = mutable.asStateFlow()

    fun isNetModelEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_NET_MODEL, true)

    fun setNetModelEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_NET_MODEL, enabled).apply()
    }

    fun isLiveModelEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_LIVE_MODEL, true)

    fun setLiveModelEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE_MODEL, enabled).apply()
    }

    fun isStaticModelEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_STATIC_MODEL, true)

    fun setStaticModelEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_STATIC_MODEL, enabled).apply()
    }

    fun isA11yModelEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_A11Y_MODEL, false)

    fun setA11yModelEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_A11Y_MODEL, enabled).apply()
    }

    fun isThreatListEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_THREAT_LIST, true)

    fun setThreatListEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_THREAT_LIST, enabled).apply()
    }

    fun update(block: (ModelStatus) -> ModelStatus) {
        synchronized(this) { mutable.value = block(mutable.value) }
    }
}
