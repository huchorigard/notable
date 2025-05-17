package com.ethran.notable.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

object GemmaModelManager {
    private const val MODEL_FILENAME = "gemma-3-1b-it-q4_0.task"
    private const val MODEL_URL = "https://boox-app.s3.us-east-1.amazonaws.com/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task"

    fun getModelFile(context: Context): File {
        val modelDir = File(context.filesDir, "llm_models")
        if (!modelDir.exists()) modelDir.mkdirs()
        return File(modelDir, MODEL_FILENAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > 0
    }

    fun downloadModel(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        val file = getModelFile(context)
        if (file.exists() && file.length() > 0) return true
        val client = OkHttpClient()
        val request = Request.Builder().url(MODEL_URL).build()
        try {
            client.newCall(request).execute().use { response ->
                android.util.Log.i("GemmaModelManager", "Model download HTTP response: ${response.code} ${response.message}")
                if (!response.isSuccessful) return false
                val body: ResponseBody = response.body ?: return false
                val total = body.contentLength()
                val input = body.byteStream()
                val output = FileOutputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress?.invoke(((downloaded * 100) / total).toInt())
                }
                output.flush()
                output.close()
                input.close()
                return true
            }
        } catch (e: IOException) {
            file.delete()
            return false
        }
    }
} 