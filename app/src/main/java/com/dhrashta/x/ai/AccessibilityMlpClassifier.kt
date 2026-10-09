package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Optional accessibility-behaviour model (12 features from [AccessibilityFeatureExtractor]).
 *
 * It must include a BENIGN (reject) class: a classifier that can only answer "which kind of attack"
 * would call every enabled service malicious. The asset is accepted only if its metadata lists a
 * BENIGN class and the output width matches; otherwise the model stays unavailable and the rule
 * engine runs alone. No model is bundled in this build.
 */
class AccessibilityMlpClassifier private constructor(
    private val model: OnnxModel?,
    private val classes: List<String>,
) : AutoCloseable {
    data class Result(val classes: List<String>, val probabilities: List<Float>) {
        private val top: Int get() = probabilities.indices.maxByOrNull(probabilities::get) ?: 0
        val predictedClass: String get() = classes[top]
        val confidence: Float get() = probabilities[top]
        val benignProbability: Float get() = classes.indexOf(BENIGN).let { if (it >= 0) probabilities[it] else 0f }

        /** Malicious only if the top class is not BENIGN, it is confident, and BENIGN is unlikely. */
        val isMalicious: Boolean
            get() = predictedClass != BENIGN && confidence >= 0.8f && benignProbability <= 0.2f
    }

    val isAvailable: Boolean get() = model != null
    val backendDetail: String get() = model?.backendDetail ?: "not installed"

    fun classify(features: FloatArray): Result? {
        require(features.size == FEATURE_COUNT) { "Expected $FEATURE_COUNT accessibility features" }
        val output = model?.run(features) ?: return null
        if (output.size != classes.size) return null
        val probabilities = if (output.sum() in 0.99f..1.01f) output.toList() else softmax(output)
        return Result(classes, probabilities)
    }

    override fun close() {
        model?.close()
    }

    companion object {
        const val BENIGN = "BENIGN"
        const val FEATURE_COUNT = 12
        private const val TAG = "DhrashtaA11yMlp"
        const val MODEL = "models/accessibility_mlp.onnx"
        private const val META = "models/accessibility_mlp.json"

        fun load(context: Context): AccessibilityMlpClassifier {
            val classes = runCatching {
                val meta = JSONObject(context.assets.open(META).bufferedReader().use { it.readText() })
                val array = meta.getJSONArray("class_order")
                List(array.length()) { array.getString(it) }
            }.getOrNull()
            if (classes == null || BENIGN !in classes) {
                Log.i(TAG, "Accessibility model not installed (or has no BENIGN class); rules only")
                return AccessibilityMlpClassifier(null, emptyList())
            }
            return AccessibilityMlpClassifier(OnnxModel.load(context, MODEL, FEATURE_COUNT), classes)
        }

        private fun softmax(values: FloatArray): List<Float> {
            val max = values.maxOrNull() ?: 0f
            val exps = values.map { kotlin.math.exp((it - max).toDouble()).toFloat() }
            val sum = exps.sum().takeIf { it > 0f } ?: 1f
            return exps.map { it / sum }
        }
    }
}
