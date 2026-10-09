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
    /** Live traffic windows are fed to the network model only when full-flow capture exists. */
    val networkLiveInput: Boolean = false,
    val a11yModelLoaded: Boolean = false,
    val a11yBackend: String? = null,
)

object ModelStatusStore {
    private val mutable = MutableStateFlow(ModelStatus())
    val state = mutable.asStateFlow()

    fun update(block: (ModelStatus) -> ModelStatus) {
        synchronized(this) { mutable.value = block(mutable.value) }
    }
}
