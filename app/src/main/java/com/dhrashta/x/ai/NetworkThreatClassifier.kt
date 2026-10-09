package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The v3 network model (13 classes, 82 features per 5-second traffic window) from dhrashta-x-v3.
 *
 * Input is the RAW window feature vector in `network_model.json` feature order; this class applies
 * the bundled StandardScaler. [selfTest] replays the golden vectors shipped with the model and
 * compares against outputs recorded from ONNX Runtime on the same files.
 *
 * Live input: building these 82 features needs every packet of an app's flows (both directions).
 * This build's VPN only sees DNS lookups and the traffic of already-paused apps, so the service does
 * NOT feed live windows yet; [SensorCoverage] reports that instead of inventing a score.
 */
class NetworkThreatClassifier private constructor(
    private val model: OnnxModel,
    val modelFile: String,
    val featureOrder: List<String>,
    val classOrder: List<String>,
    private val mean: FloatArray,
    private val scale: FloatArray,
) : AutoCloseable {
    data class Verdict(val label: String, val confidence: Float, val threatProbability: Float, val probabilities: FloatArray)

    data class SelfTest(
        val passed: Boolean,
        val cases: Int,
        val matchingTopClass: Int,
        val maxDifference: Float,
        val correctAgainstLabels: Int,
        val backend: String,
        val meanLatencyMicros: Long,
    )

    val backend: OnnxModel.Backend get() = model.backend
    val backendDetail: String get() = model.backendDetail
    private val benignIndex = classOrder.indexOf(BENIGN)

    fun classify(rawFeatures: FloatArray): Verdict {
        require(rawFeatures.size == featureOrder.size) { "Expected ${featureOrder.size} features" }
        val scaled = FloatArray(rawFeatures.size) { i ->
            val s = if (scale[i] == 0f) 1f else scale[i]
            (rawFeatures[i] - mean[i]) / s
        }
        val probs = model.run(scaled)
        val top = probs.indices.maxByOrNull { probs[it] } ?: 0
        val threat = if (benignIndex >= 0) 1f - probs[benignIndex] else probs[top]
        return Verdict(classOrder[top], probs[top], threat.coerceIn(0f, 1f), probs)
    }

    fun selfTest(context: Context): SelfTest {
        val root = JSONObject(context.assets.open(SELFTEST).bufferedReader().use { it.readText() })
        val tolerance = root.optDouble("tolerance", 0.05).toFloat()
        val cases = root.getJSONArray("cases")
        var matches = 0
        var correct = 0
        var maxDiff = 0f
        var totalNanos = 0L
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val raw = case.getJSONArray("raw").toFloatArray()
            val expected = case.getJSONArray(modelFile.substringAfterLast('/')).toFloatArray()
            val start = System.nanoTime()
            val verdict = classify(raw)
            totalNanos += System.nanoTime() - start
            val expectedTop = expected.indices.maxByOrNull { expected[it] } ?: 0
            if (classOrder[expectedTop] == verdict.label) matches++
            if (case.getString("label") == verdict.label) correct++
            for (k in expected.indices) maxDiff = maxOf(maxDiff, abs(expected[k] - verdict.probabilities[k]))
        }
        val n = cases.length()
        return SelfTest(
            passed = n > 0 && matches == n && maxDiff <= tolerance,
            cases = n,
            matchingTopClass = matches,
            maxDifference = maxDiff,
            correctAgainstLabels = correct,
            backend = backendDetail,
            meanLatencyMicros = if (n == 0) 0 else totalNanos / n / 1_000,
        )
    }

    override fun close() = model.close()

    companion object {
        private const val TAG = "DhrashtaNetModel"
        const val BENIGN = "BENIGN"
        private const val META = "models/network_model.json"
        private const val SELFTEST = "models/network_selftest.json"
        const val QDQ = "models/network_mlp_qdq.onnx"
        const val FLOAT = "models/network_mlp_float.onnx"

        /** Loads the quantized model (accelerator first); falls back to the float model if needed. */
        fun load(context: Context): NetworkThreatClassifier? = runCatching {
            val meta = JSONObject(context.assets.open(META).bufferedReader().use { it.readText() })
            val features = meta.getJSONArray("feature_order").toStringList()
            val classes = meta.getJSONArray("class_order").toStringList()
            val scaler = meta.getJSONObject("scaler")
            val mean = scaler.getJSONArray("mean").toFloatArray()
            val scale = scaler.getJSONArray("scale").toFloatArray()
            require(mean.size == features.size && scale.size == features.size) { "Scaler does not match features" }
            val (file, model) = OnnxModel.load(context, QDQ, features.size)?.let { QDQ to it }
                ?: OnnxModel.load(context, FLOAT, features.size)?.let { FLOAT to it }
                ?: error("No network model could be loaded")
            NetworkThreatClassifier(model, file, features, classes, mean, scale)
        }.onFailure { Log.w(TAG, "Network model unavailable", it) }.getOrNull()

        private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
        private fun JSONArray.toStringList() = List(length()) { getString(it) }
    }
}
