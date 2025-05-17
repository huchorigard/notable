package com.ethran.notable.utils

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
object GemmaMediaPipeSummarizer {
    private var llmInference: LlmInference? = null

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
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTopK(64)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            android.util.Log.i("GemmaMediaPipeSummarizer", "LLM model loaded successfully from: ${modelFile.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "Failed to load LLM model: ${e.message}", e)
            false
        }
    }

    fun summarize(context: Context, text: String): String {
        android.util.Log.i("GemmaMediaPipeSummarizer", "Starting summarization. Input text: $text")
        if (!loadModel(context)) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "[Summary unavailable: model not loaded]")
            return "[Summary unavailable: model not loaded]"
        }
        return try {
            val summary = llmInference?.generateResponse(text) ?: "[Summary error: no response]"
            android.util.Log.i("GemmaMediaPipeSummarizer", "Summary output: $summary")
            summary
        } catch (e: Exception) {
            android.util.Log.e("GemmaMediaPipeSummarizer", "[Summary error: ${e.message}]", e)
            "[Summary error: ${e.message}]"
        }
    }
} 