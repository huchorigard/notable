package com.ethran.notable.utils

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Model parameters (hardcoded)
private const val MODEL_FILENAME = "gemma-3-1b-it-q4_0.task"
private val PREFERRED_BACKEND = Backend.CPU
private const val TEMPERATURE = 0.8f
private const val TOP_K = 40
private const val TOP_P = 0.9f

object GemmaMediaPipeSummarizer {
    private var llmInference: LlmInference? = null
    private val inferenceMutex = Mutex()

    private fun loadModel(context: Context): Boolean {
        android.util.Log.i("GemmaMediaPipeSummarizer", "Attempting to load LLM model...")
        if (llmInference != null) {
            android.util.Log.i("GemmaMediaPipeSummarizer", "LLM model already loaded.")
            return true
        }
        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "Model file does not exist: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val builder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTopK(TOP_K)
            // Set preferred backend if available
            try {
                val method = builder.javaClass.getMethod("setPreferredBackend", Backend::class.java)
                method.invoke(builder, PREFERRED_BACKEND)
            } catch (e: Exception) {
                android.util.Log.w("GemmaMediaPipeSummarizer", "setPreferredBackend not available in this API version.")
            }
            // Set temperature if available
            try {
                val method = builder.javaClass.getMethod("setTemperature", Float::class.java)
                method.invoke(builder, TEMPERATURE)
            } catch (e: Exception) {
                android.util.Log.w("GemmaMediaPipeSummarizer", "setTemperature not available in this API version.")
            }
            // Set topP if available
            try {
                val method = builder.javaClass.getMethod("setTopP", Float::class.java)
                method.invoke(builder, TOP_P)
            } catch (e: Exception) {
                android.util.Log.w("GemmaMediaPipeSummarizer", "setTopP not available in this API version.")
            }
            val options = builder.build()
            llmInference = LlmInference.createFromOptions(context, options)
            android.util.Log.i("GemmaMediaPipeSummarizer", "LLM model loaded successfully from: ${modelFile.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "Failed to load LLM model: ${e.message}", e)
            false
        }
    }

    suspend fun summarize(context: Context, text: String): String {
        android.util.Log.i("GemmaMediaPipeSummarizer", "Starting summarization. Input text: $text")
        if (!loadModel(context)) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "[Summary unavailable: model not loaded]")
            return "[Summary unavailable: model not loaded]"
        }
        return inferenceMutex.withLock {
            try {
                val summary = llmInference?.generateResponse(text) ?: "[Summary error: no response]"
                android.util.Log.i("GemmaMediaPipeSummarizer", "Summary output: $summary")
                summary
            } catch (e: Exception) {
                android.util.Log.e("GemmaMediaPipeSummarizer", "[Summary error: ${e.message}]", e)
                "[Summary error: ${e.message}]"
            }
        }
    }
} 