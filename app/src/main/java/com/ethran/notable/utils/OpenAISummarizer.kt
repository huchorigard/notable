package com.ethran.notable.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

object OpenAISummarizer {
    private val client = OkHttpClient()
    private const val API_URL = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4.1" // Changed to a known valid model name
    private const val TAG = "OpenAISummarizer"

    suspend fun summarize(apiKey: String, prompt: String): String {
        return try {
            val json = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("max_tokens", 128)
                put("temperature", 0.7)
                put("top_p", 1.0)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "OpenAI API error: code=${response.code}, message=${response.message}, body=$errorBody")
                return "[Summary error: OpenAI API error ${response.code} - ${response.message}]"
            }
            val responseBody = response.body?.string() ?: return "[Summary error: empty response]"
            //Log.i(TAG, "OpenAI API response: $responseBody")
            val responseJson = JSONObject(responseBody)
            val summary = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            summary
        } catch (e: Exception) {
            "[Summary error: ${e.message}]"
        }
    }
} 