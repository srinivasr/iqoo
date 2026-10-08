package com.dhrashta.x.ai

import android.content.Context
import android.util.Log
import com.dhrashta.x.R
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class LlmExplainer(private val context: Context) : AutoCloseable {
    @Volatile private var inference: LlmInference? = null

    fun explain(
        appName: String,
        score: Int,
        signals: List<String>,
        language: String,
    ): String {
        val fallback = fallback(appName, language)
        return runCatching {
            val engine = inference ?: createInference().also { inference = it } ?: return fallback
            val safeLanguage = when (language.lowercase()) {
                "hindi", "hi" -> "Hindi"
                "bengali", "bangla", "bn" -> "Bengali"
                else -> "English"
            }
            val prompt = """
                You are a security assistant. Explain in $safeLanguage in under 60 words, for a non-technical person.
                Do not say 'confirmed malware'. Say what was observed and what to do.
                App: $appName. Risk score: $score. Signals: ${signals.joinToString()}.
            """.trimIndent()
            engine.generateResponse(prompt).takeIf(String::isNotBlank) ?: fallback
        }.onFailure { Log.w(TAG, "Local explanation failed", it) }.getOrDefault(fallback)
    }

    private fun createInference(): LlmInference? {
        if (MODEL_NAME !in context.assets.list("").orEmpty()) return null
        val modelFile = File(context.filesDir, MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            val temporary = File(context.filesDir, "$MODEL_NAME.tmp")
            context.assets.open(MODEL_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            check(temporary.renameTo(modelFile)) { "Could not install local LLM asset" }
        }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(64)
            .setTemperature(0.3f)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    /** Localised rule-based explanation; [context] is already set to the app language. */
    @Suppress("UNUSED_PARAMETER")
    private fun fallback(appName: String, language: String): String = context.getString(R.string.explainer_fallback, appName)

    override fun close() {
        inference?.close()
        inference = null
    }

    private companion object {
        const val TAG = "DhrashtaExplainer"
        const val MODEL_NAME = "smollm2_360m.gguf"
    }
}
