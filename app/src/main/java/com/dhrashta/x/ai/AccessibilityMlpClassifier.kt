package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import kotlin.math.exp

class AccessibilityMlpClassifier(context: Context) : AutoCloseable {
    data class Result(
        val blockAccess: Float,
        val manipulateUi: Float,
        val contentEavesdrop: Float,
    ) {
        val probabilities: List<Float> get() = listOf(blockAccess, manipulateUi, contentEavesdrop)
        val predictedClass: String
            get() = CLASS_NAMES[probabilities.indices.maxByOrNull(probabilities::get) ?: 0]
        val confidence: Float get() = probabilities.maxOrNull() ?: 0f

        private companion object {
            val CLASS_NAMES = listOf("BlockAccess", "ManipulateUI", "ContentEavesdrop")
        }
    }

    private val delegate: NnApiDelegate?
    private val interpreter: Interpreter?
    val isAvailable: Boolean get() = interpreter != null

    init {
        var createdDelegate: NnApiDelegate? = null
        interpreter = runCatching {
            require(MODEL_NAME in context.assets.list("").orEmpty()) { "$MODEL_NAME is missing" }
            createdDelegate = runCatching { NnApiDelegate() }.getOrNull()
            val options = Interpreter.Options().setNumThreads(4).apply {
                createdDelegate?.let(::addDelegate)
            }
            Interpreter(FileUtil.loadMappedFile(context, MODEL_NAME), options)
        }.onFailure { Log.w(TAG, "Accessibility model unavailable; rule engine remains active", it) }
            .getOrNull()
        delegate = createdDelegate
    }

    @Synchronized
    fun classify(features: FloatArray): Result? {
        require(features.size == 12) { "Expected 12 accessibility features" }
        val model = interpreter ?: return null
        val output = Array(1) { FloatArray(3) }
        model.run(arrayOf(features), output)
        val probabilities = softmax(output[0])
        return Result(probabilities[0], probabilities[1], probabilities[2])
    }

    override fun close() {
        interpreter?.close()
        delegate?.close()
    }

    private fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: 0f
        val exponentials = values.map { exp((it - max).toDouble()).toFloat() }
        val sum = exponentials.sum().takeIf { it > 0f } ?: 1f
        return exponentials.map { it / sum }.toFloatArray()
    }

    private companion object {
        const val TAG = "DhrashtaA11yMlp"
        const val MODEL_NAME = "accessibility_mlp.tflite"
    }
}
