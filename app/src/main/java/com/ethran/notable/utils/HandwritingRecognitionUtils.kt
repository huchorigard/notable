package com.ethran.notable.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.tasks.await
import com.ethran.notable.db.RecognizedText
import com.ethran.notable.db.RecognizedTextDao
import java.util.Date
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.Ink

/**
 * Splits the bounding box of all strokes into 1024x1024 px tiles,
 * and groups the strokes into each tile as a chunk.
 * Returns a list of Pair(chunkIndex, List<Stroke>).
 */
fun chunkStrokesForDigitalInk(
    strokes: List<Stroke>,
    chunkSize: Int = 1024
): List<Pair<Int, List<Stroke>>> {
    if (strokes.isEmpty()) return emptyList()

    // Compute the bounding box of all strokes
    val allPoints = strokes.flatMap { it.points }
    val minX = allPoints.minOf { it.x }
    val minY = allPoints.minOf { it.y }
    val maxX = allPoints.maxOf { it.x }
    val maxY = allPoints.maxOf { it.y }

    val width = maxX - minX
    val height = maxY - minY

    val numChunksX = Math.ceil(width / chunkSize.toDouble()).toInt().coerceAtLeast(1)
    val numChunksY = Math.ceil(height / chunkSize.toDouble()).toInt().coerceAtLeast(1)

    val chunks = mutableListOf<Pair<Int, List<Stroke>>>()
    var chunkIndex = 0
    for (yChunk in 0 until numChunksY) {
        for (xChunk in 0 until numChunksX) {
            val left = minX + xChunk * chunkSize
            val top = minY + yChunk * chunkSize
            val right = left + chunkSize
            val bottom = top + chunkSize
            val tileRect = RectF(left, top, right, bottom)

            // Select strokes that intersect this tile
            val strokesInTile = strokes.filter { stroke ->
                stroke.points.any { p ->
                    p.x >= tileRect.left && p.x < tileRect.right &&
                    p.y >= tileRect.top && p.y < tileRect.bottom
                }
            }
            if (strokesInTile.isNotEmpty()) {
                chunks.add(chunkIndex to strokesInTile)
            }
            chunkIndex++
        }
    }
    return chunks
}

/**
 * Converts a list of Stroke to an ML Kit Ink object.
 */
fun strokesToInk(strokes: List<Stroke>): Ink {
    val inkBuilder = Ink.builder()
    for (stroke in strokes) {
        val strokeBuilder = Ink.Stroke.builder()
        for (point in stroke.points) {
            // Use x, y, and timestamp (if available)
            strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestamp))
        }
        inkBuilder.addStroke(strokeBuilder.build())
    }
    return inkBuilder.build()
}

/**
 * Runs ML Kit Digital Ink recognition on each chunk and returns a list of recognized texts.
 * Each result is a Pair(chunkIndex, recognizedText).
 */
suspend fun recognizeDigitalInkInChunks(
    context: Context,
    chunks: List<Pair<Int, List<Stroke>>>,
    logTag: String = "HandwritingRecognition"
): List<Pair<Int, String>> {
    val results = mutableListOf<Pair<Int, String>>()
    val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    if (modelIdentifier == null) {
        Log.e(logTag, "Could not get model identifier for en-US")
        return chunks.map { it.first to "[Model error]" }
    }
    val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
    val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(model).build()
    )
    // Download model if needed
    try {
        val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
        val isDownloaded = remoteModelManager.isModelDownloaded(model).await()
        if (!isDownloaded) {
            remoteModelManager.download(model, com.google.mlkit.common.model.DownloadConditions.Builder().build()).await()
            Log.i(logTag, "Model downloaded for en-US")
        }
    } catch (e: Exception) {
        Log.e(logTag, "Failed to download/check model", e)
        return chunks.map { it.first to "[Model download error]" }
    }
    for ((chunkIndex, strokes) in chunks) {
        try {
            val ink = strokesToInk(strokes)
            val result = recognizer.recognize(ink).await()
            val text = result.candidates.firstOrNull()?.text ?: ""
            Log.i(logTag, "Chunk $chunkIndex: $text")
            results.add(chunkIndex to text)
        } catch (e: Exception) {
            Log.e(logTag, "Recognition failed for chunk $chunkIndex", e)
            results.add(chunkIndex to "[Recognition failed]")
        }
    }
    return results
}

/**
 * Stores recognized text results in the database, overwriting previous results for the same note and page.
 */
suspend fun storeRecognizedTextResults(
    recognizedTextDao: RecognizedTextDao,
    noteId: String,
    pageId: String,
    recognizedChunks: List<Pair<Int, String>>
) {
    // Delete previous results for this note and page
    recognizedTextDao.deleteByNoteAndPage(noteId, pageId)
    // Insert new results
    for ((chunkIndex, text) in recognizedChunks) {
        val recognizedText = RecognizedText(
            noteId = noteId,
            pageId = pageId,
            chunkIndex = chunkIndex,
            recognizedText = text,
            updatedAt = Date()
        )
        recognizedTextDao.insert(recognizedText)
    }
}

/**
 * Recognize all strokes on a page as a single Ink using Digital Ink Recognition.
 * Returns the recognized text or an error string.
 */
suspend fun recognizeDigitalInkOnPage(
    context: Context,
    strokes: List<Stroke>,
    logTag: String = "HandwritingRecognition"
): String {
    val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    if (modelIdentifier == null) {
        Log.e(logTag, "Could not get model identifier for en-US")
        return "[Model error]"
    }
    val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
    val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(model).build()
    )
    // Download model if needed
    try {
        val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
        val isDownloaded = remoteModelManager.isModelDownloaded(model).await()
        if (!isDownloaded) {
            remoteModelManager.download(model, com.google.mlkit.common.model.DownloadConditions.Builder().build()).await()
            Log.i(logTag, "Model downloaded for en-US")
        }
    } catch (e: Exception) {
        Log.e(logTag, "Failed to download/check model", e)
        return "[Model download error]"
    }
    return try {
        val ink = strokesToInk(strokes)
        val result = recognizer.recognize(ink).await()
        val text = result.candidates.firstOrNull()?.text ?: ""
        Log.i(logTag, "Recognized text: $text")
        text
    } catch (e: Exception) {
        Log.e(logTag, "Recognition failed", e)
        "[Recognition failed]"
    }
}

/**
 * Stores recognized text result in the database, overwriting previous results for the same note and page.
 * Only stores a single result (chunkIndex = 0).
 */
suspend fun storeRecognizedTextResult(
    recognizedTextDao: RecognizedTextDao,
    noteId: String,
    pageId: String,
    recognizedText: String
) {
    // Delete previous results for this note and page
    recognizedTextDao.deleteByNoteAndPage(noteId, pageId)
    // Insert new result
    val result = RecognizedText(
        noteId = noteId,
        pageId = pageId,
        chunkIndex = 0,
        recognizedText = recognizedText,
        updatedAt = Date()
    )
    recognizedTextDao.insert(result)
} 