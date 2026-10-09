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
    private val mutable = MutableStateFlow(ModelStatus())
    val state = mutable.asStateFlow()

    fun update(block: (ModelStatus) -> ModelStatus) {
        synchronized(this) { mutable.value = block(mutable.value) }
    }
}
