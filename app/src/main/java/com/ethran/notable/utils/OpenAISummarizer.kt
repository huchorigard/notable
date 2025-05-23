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
    private const val MODEL = "gpt-4.1"
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

            return withContext(Dispatchers.IO) {
                val response = try {
                    client.newCall(request).execute()
                } catch (e: Exception) {
                    throw e
                }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    return@withContext "[Summary error: OpenAI API error ${response.code} - ${response.message}]"
                }
                val responseBody = response.body?.string()
                if (responseBody == null) return@withContext "[Summary error: empty response]"
                try {
                    val responseJson = JSONObject(responseBody)
                    responseJson
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } catch (e: Exception) {
                    "[Summary error: ${e.message ?: "unknown error"}]"
                }
            }
        } catch (e: Exception) {
            "[Summary error: ${e.message ?: "unknown error"}]"
        }
    }

    suspend fun suggestTags(
        apiKey: String,
        recognizedText: String,
        userInfo: String,
        availableTags: List<Tag>
    ): List<Tag> {
        Log.i(TAG, "Starting tag suggestion process")
        try {
            val tagNames = availableTags.map { it.name }
            Log.i(TAG, "Available tags for suggestion: ${tagNames.joinToString(", ")}")
            
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

            Log.i(TAG, "Sending request to OpenAI for tag suggestions")
            val json = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }))
                put("max_tokens", 128)
                put("temperature", 0.7)
            }

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
                Log.i(TAG, "Received raw response from OpenAI: $responseBody")
                
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
                    
                    Log.i(TAG, "OpenAI suggested tags string: $suggestedTagsStr")

                    // Split the comma-separated response and trim each tag
                    val suggestedTagNames = suggestedTagsStr
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    
                    Log.i(TAG, "Parsed suggested tag names: ${suggestedTagNames.joinToString(", ")}")

                    // Match suggested tags with available tags using fuzzy matching
                    val matchedTags = suggestedTagNames.mapNotNull { suggestedTag ->
                        availableTags.find { availableTag ->
                            StringUtils.fuzzyMatch(suggestedTag, availableTag.name)
                        }
                    }

                    Log.i(TAG, "Final matched tags: ${matchedTags.map { it.name }.joinToString(", ")}")
                    return@withContext matchedTags
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing OpenAI response: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in suggestTags: ${e.message}", e)
            return emptyList()
        }
    }
} 