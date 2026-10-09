package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

/**
 * Live network model: scores a suspicious app's real traffic (10 windows x 12 features, 10 s each)
 * fed by the VPN relay ([com.dhrashta.x.enforcement.relay.LiveWindowFeatures]).
 *
 * Config-driven from `network_live.json`, so we can ship either:
 *  - the **Hummingbird-GEMM LightGBM** (FP16): tensor ops (Gemm/MatMul), NNAPI/NPU-capable, output is
 *    already a probability; preprocessing is signed-log1p with NO scaler; it needs a batch of 2 rows
 *    because the export squeezes a size-1 batch (we tile and read row 0), or
 *  - the **MLP**: Linear/ReLU, output is a logit (we apply sigmoid), preprocessing is signed-log1p
 *    plus a StandardScaler (mean/scale in the config).
 *
 * [backendDetail] reports whether it really ran on an accelerator (NPU) or fell back to CPU.
 */
class LiveNetworkModel private constructor(
    private val model: OnnxModel,
    private val mean: FloatArray?,     // null => no scaler (GEMM model)
    private val scale: FloatArray?,
    val threshold: Float,
    val windows: Int,
    val features: Int,
    private val batch: Int,
    private val outputIndex: Int,
    private val probColumn: Int,
    private val outputIsProbability: Boolean,
) {
    data class Score(val logit: Float, val probability: Float, val alert: Boolean)

    data class SelfTest(
        val passed: Boolean, val cases: Int, val matching: Int, val maxDifference: Float,
        val alertsOnRat: Int, val ratCases: Int, val alertsOnBenign: Int, val benignCases: Int,
        val backend: String, val meanLatencyMicros: Long,
    )

    val backendDetail: String get() = model.backendDetail
    val onAccelerator: Boolean get() = model.backend == OnnxModel.Backend.ACCELERATOR_NNAPI

    fun score(sequence: Array<FloatArray>): Score =
        scoreFlat(FloatArray(windows * features) { sequence[it / features][it % features] })

    @Synchronized
    fun scoreFlat(raw: FloatArray): Score {
        require(raw.size == windows * features) { "Expected ${windows * features} values" }
        val x = FloatArray(raw.size) { i ->
            val logged = (sign(raw[i].toDouble()) * ln(1.0 + abs(raw[i].toDouble()))).toFloat()
            if (mean != null && scale != null) {
                val s = if (scale[i] == 0f) 1f else scale[i]
                (logged - mean[i]) / s
            } else {
                logged
            }
        }
        val out = if (batch > 1) model.runTiled(x, batch, outputIndex) else model.run(x, outputIndex)
        val raw0 = out[probColumn]
        val p = if (outputIsProbability) {
            raw0.coerceIn(0f, 1f)
        } else {
            (1.0 / (1.0 + exp(-raw0.toDouble().coerceIn(-80.0, 80.0)))).toFloat()
        }
        val logit = if (outputIsProbability) ln((p + 1e-6f) / (1f - p + 1e-6f)) else raw0
        return Score(logit, p, p >= threshold)
    }

    fun selfTest(context: Context): SelfTest {
        val root = JSONObject(context.assets.open(SELFTEST).bufferedReader().use { it.readText() })
        val tolerance = root.optDouble("tolerance", 0.02).toFloat()
        val cases = root.getJSONArray("cases")
        var matching = 0; var maxDiff = 0f; var nanos = 0L
        var rat = 0; var ratHit = 0; var benign = 0; var benignHit = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.getJSONArray("raw").floats()
            val expected = c.getDouble("expected_prob").toFloat()
            val start = System.nanoTime()
            val s = scoreFlat(raw)
            nanos += System.nanoTime() - start
            val d = abs(s.probability - expected)
            maxDiff = maxOf(maxDiff, d)
            if (d <= tolerance && s.alert == (expected >= threshold)) matching++
            if (c.optInt("label") == 1) { rat++; if (s.alert) ratHit++ } else { benign++; if (s.alert) benignHit++ }
        }
        val n = cases.length()
        return SelfTest(n > 0 && matching == n, n, matching, maxDiff, ratHit, rat, benignHit, benign, backendDetail,
            if (n == 0) 0 else nanos / n / 1_000)
    }

    companion object {
        private const val TAG = "DhrashtaLiveModel"
        private const val META = "models/network_live.json"
        private const val SELFTEST = "models/network_live_selftest.json"

        @Volatile private var shared: LiveNetworkModel? = null
        @Volatile private var failed = false

        fun shared(context: Context): LiveNetworkModel? {
            shared?.let { return it }
            if (failed) return null
            synchronized(this) {
                shared?.let { return it }
                return load(context.applicationContext).also { shared = it; failed = it == null }
            }
        }

        private fun load(context: Context): LiveNetworkModel? = runCatching {
            val meta = JSONObject(context.assets.open(META).bufferedReader().use { it.readText() })
            val windows = meta.getInt("sequence_length")
            val features = meta.getJSONArray("features").length()
            val width = windows * features
            val modelFile = "models/" + meta.getString("model_file")
            val mean = meta.optJSONArray("mean")?.floats()
            val scale = meta.optJSONArray("scale")?.floats()
            if (mean != null) require(mean.size == width && scale?.size == width) { "Scaler does not match $windows x $features" }
            val onnx = OnnxModel.load(context, modelFile, width) ?: error("live network model did not load")
            LiveNetworkModel(
                model = onnx, mean = mean, scale = scale,
                threshold = meta.getDouble("threshold").toFloat(),
                windows = windows, features = features,
                batch = meta.optInt("batch", 1),
                outputIndex = meta.optInt("output_index", 0),
                probColumn = meta.optInt("prob_column", 0),
                outputIsProbability = meta.optBoolean("output_is_probability", false),
            )
        }.onFailure { Log.w(TAG, "Live network model unavailable", it) }.getOrNull()

        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    }
}
