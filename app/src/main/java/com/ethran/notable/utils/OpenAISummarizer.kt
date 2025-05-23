package com.ethran.notable.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import com.ethran.notable.db.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OpenAISummarizer {
    private val client = OkHttpClient()
    private const val API_URL = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4.1" // Changed to a known valid model name
    private const val TAG = "OpenAISummarizer"

    suspend fun summarize(apiKey: String, prompt: String): String {
        Log.i(TAG, "In the summarize function")
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
            Log.i(TAG, "json defined $json")
            val body = json.toString().toRequestBody("application/json".toMediaType())
            Log.i(TAG, "body defined $body")
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            Log.i(TAG, "request defined $request")
            return withContext(Dispatchers.IO) {
                val response = try {
                    client.newCall(request).execute()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing request", e)
                    throw e
                }
                Log.i(TAG, "response defined $response")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "OpenAI API error: code=${response.code}, message=${response.message}, body=$errorBody")
                    return@withContext "[Summary error: OpenAI API error ${response.code} - ${response.message}]"
                }
                val responseBody = response.body?.string()
                Log.i(TAG, "OpenAI API raw response body: $responseBody")
                if (responseBody == null) return@withContext "[Summary error: empty response]"
                try {
                    Log.i(TAG, "Parsing OpenAI response JSON")
                    val responseJson = JSONObject(responseBody)
                    val summary = responseJson
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    Log.i(TAG, "Parsed summary: $summary")
                    summary
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing OpenAI response: $responseBody", e)
                    "[Summary error: ${e.message ?: "unknown error"}]"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in summarize: ${e::class.java.simpleName}: ${e.message}", e)
            e.printStackTrace()
            "[Summary error: ${e.message ?: "unknown error"}]"
        }
    }

    suspend fun suggestTags(
        apiKey: String,
        recognizedText: String,
        userInfo: String,
        availableTags: List<Tag>
    ): List<Tag> {
        Log.i(TAG, "In the suggestTags function")
        try {
            val tagNames = availableTags.map { it.name }
            val prompt = """
                Based on the following note content and user information, suggest appropriate tags from the available list.
                Only respond with a comma-separated list of tags, no quotes, no explanations.
                Example response format: work, personal, important

                Note content:
                $recognizedText

                User information:
                $userInfo

                Available tags:
                ${tagNames.joinToString(", ")}

                Selected tags (comma separated, no quotes):
            """.trimIndent()

            val json = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("max_tokens", 128)
                put("temperature", 0.7)
            }

            Log.i(TAG, "json defined $json")
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            return withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenAI API error: ${response.code} - ${response.message}")
                    return@withContext emptyList()
                }

                val responseBody = response.body?.string()
                Log.i(TAG, "OpenAI API raw response body: $responseBody")
                
                if (responseBody == null) {
                    Log.e(TAG, "Empty response from OpenAI")
                    return@withContext emptyList()
                }

                try {
                    val responseJson = JSONObject(responseBody)
                    val suggestedTagsStr = responseJson
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    // Split the comma-separated response and trim each tag
                    val suggestedTags = suggestedTagsStr
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    // Match suggested tags with available tags using fuzzy matching
                    return@withContext suggestedTags.mapNotNull { suggestedTag ->
                        availableTags.find { availableTag ->
                            StringUtils.fuzzyMatch(suggestedTag, availableTag.name)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing OpenAI response: $responseBody", e)
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in suggestTags: ${e::class.java.simpleName}: ${e.message}", e)
            return emptyList()
        }
    }
} 