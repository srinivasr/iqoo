package com.dhrashta.x.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * One ONNX model with an honest record of WHERE it runs.
 *
 * It first tries Android NNAPI with NNAPI's own CPU path disabled AND ONNX Runtime's CPU fallback
 * disabled, so a session only exists in that mode if every node is placed on an accelerator driver
 * (on Snapdragon phones that is usually the Hexagon NPU). If that fails, it uses the ONNX Runtime CPU
 * provider and says so in [backend]. Nothing in the app ever claims "NPU" unless the strict session
 * was created.
 */
class OnnxModel private constructor(
    private val session: OrtSession,
    val backend: Backend,
    val backendDetail: String,
    private val inputName: String,
    private val inputWidth: Int,
) : AutoCloseable {
    enum class Backend { ACCELERATOR_NNAPI, CPU }

    @Synchronized
    fun run(input: FloatArray): FloatArray {
        require(input.size == inputWidth) { "Expected $inputWidth inputs, got ${input.size}" }
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, inputWidth.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val value = result.get(0).value as Array<FloatArray>
                return value[0].copyOf()
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "DhrashtaOnnx"

        /** Loads [assetPath]; returns null (with a log line) if the asset is missing or unloadable. */
        fun load(context: Context, assetPath: String, inputWidth: Int, preferAccelerator: Boolean = true): OnnxModel? {
            val bytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }
                .onFailure { Log.w(TAG, "Model asset $assetPath is missing", it) }
                .getOrNull() ?: return null
            val env = OrtEnvironment.getEnvironment()
            if (preferAccelerator) {
                val strict = runCatching {
                    val options = OrtSession.SessionOptions().apply {
                        addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    }
                    env.createSession(bytes, options)
                }
                strict.getOrNull()?.let { session ->
                    return OnnxModel(session, Backend.ACCELERATOR_NNAPI, "NNAPI accelerator (no CPU fallback)", session.inputNames.first(), inputWidth)
                }
                Log.i(TAG, "$assetPath cannot run fully on an NNAPI accelerator here: ${strict.exceptionOrNull()?.message}")
            }
            return runCatching {
                val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val session = env.createSession(bytes, options)
                OnnxModel(session, Backend.CPU, "ONNX Runtime CPU", session.inputNames.first(), inputWidth)
            }.onFailure { Log.e(TAG, "Could not load $assetPath", it) }.getOrNull()
        }
    }
}
