package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil

class NetworkAnomalyModel(context: Context) : AutoCloseable {
    data class Features(
        val connectionCount: Float,
        val uniqueDestinations: Float,
        val bytesUp: Float,
        val bytesDown: Float,
        val uploadDownloadRatio: Float,
        val intervalVariance: Float,
        val rawIpPercentage: Float,
        val timeSinceBankingForeground: Float,
        val screenOn: Float,
    ) {
        fun toFloatArray() = floatArrayOf(
            connectionCount,
            uniqueDestinations,
            bytesUp,
            bytesDown,
            uploadDownloadRatio,
            intervalVariance,
            rawIpPercentage,
            timeSinceBankingForeground,
            screenOn,
        )
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
        }.onFailure { Log.w(TAG, "Network model unavailable", it) }.getOrNull()
        delegate = createdDelegate
    }

    @Synchronized
    fun score(features: Features): Float? {
        val model = interpreter ?: return null
        val output = Array(1) { FloatArray(1) }
        model.run(arrayOf(features.toFloatArray()), output)
        return output[0][0].coerceIn(0f, 1f)
    }

    override fun close() {
        interpreter?.close()
        delegate?.close()
    }

    private companion object {
        const val TAG = "DhrashtaNetworkMlp"
        const val MODEL_NAME = "network_anomaly.tflite"
    }
}
