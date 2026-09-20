package com.dhrashta.x.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EvaluationState(
    val monitoring: Boolean = false,
    val evaluating: Boolean = false,
    val packageName: String? = null,
    val score: Int? = null,
    val band: String? = null,
    val explanation: String? = null,
    val firedSignals: List<String> = emptyList(),
    val contained: Boolean = false,
    val message: String = "Monitoring is starting",
)

object EvaluationStateStore {
    private val mutableState = MutableStateFlow(EvaluationState())
    val state = mutableState.asStateFlow()

    fun update(block: (EvaluationState) -> EvaluationState) {
        mutableState.value = block(mutableState.value)
    }
}
