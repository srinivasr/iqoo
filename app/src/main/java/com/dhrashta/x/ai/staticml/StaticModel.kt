package com.dhrashta.x.ai.staticml

import android.content.Context
import android.util.Log
import com.dhrashta.x.ai.OnnxModel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/**
 * S0: static APK model. LightGBM "drebin_hybrid_v3" (300 trees, Drebin-215 + 51 MODERN features),
 * exported to ONNX (TreeEnsembleClassifier) and run with ONNX Runtime on the CPU. Tree ensembles are
 * not NNAPI operators, so this model never claims an accelerator; the network MLP is the NPU path.
 * The probability is then fused with the stage-2 family rules ([StaticBoost]).
 */
class StaticModel private constructor(
    private val model: OnnxModel,
    val featureOrder: List<String>,
    val drebinFeatures: List<String>,
    private val probOutputIndex: Int,
    private val probColumn: Int,
    val boost: StaticBoost,
) : AutoCloseable {
    data class Score(val base: Float, val boost: Float, val final: Float, val contributors: List<Pair<String, Double>>)

    data class SelfTest(
        val passed: Boolean,
        val cases: Int,
        val matching: Int,
        val maxDifference: Float,
        val malwareFlagged: Int,
        val malwareCases: Int,
        val benignFlagged: Int,
        val benignCases: Int,
        val meanLatencyMicros: Long,
        val backend: String,
    )

    val backendDetail: String get() = model.backendDetail
    val extractor = StaticFeatureExtractor(drebinFeatures)

    fun score(fired: Set<String>): Score {
        val vector = extractor.vector(featureOrder, fired)
        val base = model.run(vector, probOutputIndex)[probColumn].coerceIn(0f, 1f)
        val b = boost.apply(fired)
        return Score(base, b.boost.toFloat(), min(base + b.boost.toFloat(), 1f), b.contributors)
    }

    /** Replays the real extracted samples shipped with the model; compares with the Python/ORT outputs. */
    fun selfTest(context: Context): SelfTest {
        val cases = JSONArray(context.assets.open(GOLDEN).bufferedReader().use { it.readText() })
        var matching = 0
        var maxDiff = 0f
        var nanos = 0L
        var mal = 0; var malHit = 0; var ben = 0; var benHit = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val fired = c.getJSONArray("present").let { a -> HashSet<String>().apply { for (k in 0 until a.length()) add(a.getString(k)) } }
            val start = System.nanoTime()
            val s = score(fired)
            nanos += System.nanoTime() - start
            val d = maxOf(
                abs(s.base - c.getDouble("base").toFloat()),
                abs(s.boost - c.getDouble("boost").toFloat()),
                abs(s.final - c.getDouble("final").toFloat()),
            )
            maxDiff = maxOf(maxDiff, d)
            if (d <= TOLERANCE) matching++
            if (c.optInt("label", -1) == 1) { mal++; if (s.final >= HIGH) malHit++ }
            if (c.optInt("label", -1) == 0) { ben++; if (s.final >= HIGH) benHit++ }
        }
        val n = cases.length()
        return SelfTest(n > 0 && matching == n, n, matching, maxDiff, malHit, mal, benHit, ben,
            if (n == 0) 0 else nanos / n / 1_000, backendDetail)
    }

    override fun close() = model.close()

    companion object {
        private const val TAG = "DhrashtaStaticModel"
        const val MODEL = "models/static_lgbm.onnx"
        private const val META = "models/static_meta.json"
        private const val RULES = "models/static_rules.json"
        private const val GOLDEN = "models/static_golden.json"
        private const val TOLERANCE = 1e-4f
        /** S0 bands used by the app (validated on 42 modern samples + 2 benign: see README). */
        const val HIGH = com.dhrashta.x.data.StaticVerdict.HIGH
        const val MEDIUM = com.dhrashta.x.data.StaticVerdict.MEDIUM

        fun load(context: Context): StaticModel? = runCatching {
            val meta = JSONObject(context.assets.open(META).bufferedReader().use { it.readText() })
            val order = meta.getJSONArray("feature_order").strings()
            val nDrebin = meta.optInt("n_drebin", 215)
            val rules = JSONObject(context.assets.open(RULES).bufferedReader().use { it.readText() })
            val boost = StaticBoost(
                strongAlone = rules.getJSONObject("STRONG_ALONE").weights(),
                v6Strong = rules.getJSONObject("V6_STRONG").weights(),
                weak = rules.getJSONObject("WEAK").weights(),
                coOccurrence = rules.getJSONObject("CO_OCCURRENCE").weights(),
                strictTriggers = rules.getJSONArray("STRICT_TRIGGERS").strings().toSet(),
            )
            // Tree ensembles have no NNAPI kernel: go straight to the CPU provider.
            val onnx = OnnxModel.load(context, MODEL, order.size, preferAccelerator = false) ?: error("static model did not load")
            StaticModel(onnx, order, order.take(nDrebin), meta.optInt("prob_output_index", 1), meta.optInt("prob_column", 1), boost)
        }.onFailure { Log.w(TAG, "Static model unavailable", it) }.getOrNull()

        private fun JSONArray.strings() = List(length()) { getString(it) }
        private fun JSONObject.weights(): Map<String, Double> = keys().asSequence().associateWith { getDouble(it) }
    }
}
