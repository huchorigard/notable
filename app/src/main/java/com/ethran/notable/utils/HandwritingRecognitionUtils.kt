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
import com.ethran.notable.db.RecognizedTextChunk
import com.ethran.notable.db.AppDatabase
import com.google.android.gms.tasks.Tasks

/**
 * Splits the bounding box of all strokes into 1024x1024 px tiles,
 * and groups the strokes into each tile as a chunk.
 * Returns a list of Pair(chunkIndex, List<Stroke>).
 */
fun chunkStrokesForDigitalInk(
    strokes: List<Stroke>,
    chunkSize: Int = 1024
): List<Pair<Int, List<Stroke>>> {
    val filteredStrokes = filterHandwritingStrokes(strokes)
    if (filteredStrokes.isEmpty()) return emptyList()

    // Compute the bounding box of all strokes
    val allPoints = filteredStrokes.flatMap { it.points }
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
            val strokesInTile = filteredStrokes.filter { stroke ->
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
    val filteredStrokes = filterHandwritingStrokes(strokes)
    if (filteredStrokes.isEmpty()) return ""
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
        val ink = strokesToInk(filteredStrokes)
        
        // Calculate writing area from strokes
        val allPoints = filteredStrokes.flatMap { it.points }
        val minX = allPoints.minOf { it.x }
        val minY = allPoints.minOf { it.y }
        val maxX = allPoints.maxOf { it.x }
        val maxY = allPoints.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        
        // Get pre-context from previously recognized text
        val db = AppDatabase.getDatabase(context)
        val previousChunks = db.recognizedTextDao().getChunksForPage(strokes.firstOrNull()?.pageId ?: "")
        val preContext = if (previousChunks.isNotEmpty()) {
            // Get the last 20 characters of previously recognized text
            reconstructTextFromChunks(previousChunks).takeLast(20)
        } else {
            ""
        }
        
        // Create recognition context with writing area and pre-context
        val recognitionContext = com.google.mlkit.vision.digitalink.RecognitionContext.builder()
            .setWritingArea(com.google.mlkit.vision.digitalink.WritingArea(width, height))
            .setPreContext(preContext)
            .build()
            
        val result = recognizer.recognize(ink, recognitionContext).await()
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

suspend fun recognizeChunkAndExtractMetadata(
    context: Context,
    strokes: List<Stroke>,
    pageId: String,
    logTag: String = "HandwritingRecognition"
): RecognizedTextChunk {
    val filteredStrokes = filterHandwritingStrokes(strokes)
    val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    val model = DigitalInkRecognitionModel.builder(modelIdentifier!!).build()
    val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(model).build()
    )
    // Download model if needed
    val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
    val isDownloaded = remoteModelManager.isModelDownloaded(model).await()
    if (!isDownloaded) {
        remoteModelManager.download(model, com.google.mlkit.common.model.DownloadConditions.Builder().build()).await()
    }
    val ink = strokesToInk(filteredStrokes)
    
    // Calculate writing area from strokes
    val allPoints = filteredStrokes.flatMap { it.points }
    val minX = allPoints.minOfOrNull { it.x } ?: 0f
    val minY = allPoints.minOfOrNull { it.y } ?: 0f
    val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
    val maxY = allPoints.maxOfOrNull { it.y } ?: 0f
    val width = maxX - minX
    val height = maxY - minY
    val averageY = if (allPoints.isNotEmpty()) allPoints.map { it.y }.average().toFloat() else 0f
    
    // Get pre-context from previously recognized text
    val db = AppDatabase.getDatabase(context)
    val previousChunks = db.recognizedTextDao().getChunksForPage(pageId)
    val preContext = if (previousChunks.isNotEmpty()) {
        // Get the last 20 characters of previously recognized text
        reconstructTextFromChunks(previousChunks).takeLast(20)
    } else {
        ""
    }
    
    // Create recognition context with writing area and pre-context
    val recognitionContext = com.google.mlkit.vision.digitalink.RecognitionContext.builder()
        .setWritingArea(com.google.mlkit.vision.digitalink.WritingArea(width, height))
        .setPreContext(preContext)
        .build()
        
    val result = recognizer.recognize(ink, recognitionContext).await()
    val text = result.candidates.firstOrNull()?.text ?: ""
    val timestamp = allPoints.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
    val strokeIds = filteredStrokes.map { it.id }
    // Log.d("InkTextSync", "recognizeChunkAndExtractMetadata: Creating chunk for page $pageId. Text: '$text', StrokeIDs: $strokeIds")
    return RecognizedTextChunk(
        pageId = pageId,
        recognizedText = text,
        minX = minX,
        minY = minY,
        maxX = maxX,
        maxY = maxY,
        averageY = averageY,
        timestamp = timestamp,
        strokeIds = strokeIds
    )
}

fun reconstructTextFromChunks(chunks: List<RecognizedTextChunk>, lineThreshold: Float = 60f): String {
    if (chunks.isEmpty()) return ""
    // Debug print chunk info
    chunks.forEach { chunk ->
        println("Chunk id=${chunk.id} text='${chunk.recognizedText}' minY=${chunk.minY} averageY=${chunk.averageY}")
    }
    // Group by line using averageY, then sort by minX within each line
    val sortedChunks = chunks.sortedWith(compareBy({ it.averageY }, { it.minX }))
    val lines = mutableListOf<MutableList<RecognizedTextChunk>>()
    for (chunk in sortedChunks) {
        val line = lines.lastOrNull()
        if (line == null || kotlin.math.abs(chunk.averageY - line[0].averageY) > lineThreshold) {
            lines.add(mutableListOf(chunk))
        } else {
            line.add(chunk)
        }
    }
    return lines.joinToString("\n") { lineChunks ->
        lineChunks.sortedBy { it.minX }.joinToString(" ") { it.recognizedText }
    }
}

fun filterHandwritingStrokes(strokes: List<Stroke>): List<Stroke> {
    return strokes.filter { it.pen != Pen.MARKER }
}

suspend fun updateRecognizedChunkAfterErasure(
    context: Context,
    recognizedTextDao: RecognizedTextDao,
    strokeDao: com.ethran.notable.db.StrokeDao, // Added StrokeDao to fetch strokes
    pageId: String,
    chunkToUpdateId: String,
    remainingStrokeIdsInChunk: List<String>
) {
    // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: Called for chunk $chunkToUpdateId. Page: $pageId. Remaining stroke IDs: (${remainingStrokeIdsInChunk.size}) $remainingStrokeIdsInChunk")
    if (remainingStrokeIdsInChunk.isEmpty()) {
        recognizedTextDao.deleteChunkById(chunkToUpdateId)
        Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Chunk $chunkToUpdateId deleted as no strokes remain.") // Keep: Important action
        return
    }

    // 1. Fetch the full Stroke objects for the remaining IDs
    val remainingStrokes = strokeDao.getStrokesByIds(remainingStrokeIdsInChunk)
    // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: Requested ${remainingStrokeIdsInChunk.size} IDs from DB. Fetched ${remainingStrokes.size} full stroke objects for chunk $chunkToUpdateId.")
    val fetchedStrokeIds = remainingStrokes.map { it.id }
    val missingStrokeIds = remainingStrokeIdsInChunk.filterNot { it in fetchedStrokeIds }
    if (missingStrokeIds.isNotEmpty()) {
        Log.w("InkTextSync", "updateRecognizedChunkAfterErasure: Missing ${missingStrokeIds.size} stroke IDs from DB for chunk $chunkToUpdateId: $missingStrokeIds. This can happen if original strokes were filtered by pressure.") // Keep: Useful warning with explanation
    }

    if (remainingStrokes.isEmpty()) {
        recognizedTextDao.deleteChunkById(chunkToUpdateId)
        Log.w("InkTextSync", "updateRecognizedChunkAfterErasure: Chunk $chunkToUpdateId deleted as no actual strokes were found for the given remaining IDs.") // Keep: Important warning
        return
    }

    val filteredRemainingStrokes = filterHandwritingStrokes(remainingStrokes)
    if (filteredRemainingStrokes.isEmpty()) {
        recognizedTextDao.deleteChunkById(chunkToUpdateId)
        Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Chunk $chunkToUpdateId deleted as all remaining strokes were filtered out (e.g. markers).") // Keep: Important action
        return
    }

    // 2. Re-recognize with ML Kit
    // Create an Ink object from the remaining strokes
    val inkBuilder = Ink.builder()
    filteredRemainingStrokes.forEach { stroke -> // Iterate over filtered strokes
        val strokeBuilder = Ink.Stroke.builder() // Create a new stroke builder for each stroke
        stroke.points.forEach { sp -> // Iterate over points in the stroke
            strokeBuilder.addPoint(Ink.Point.create(sp.x, sp.y, sp.timestamp)) // Add points individually
        }
        inkBuilder.addStroke(strokeBuilder.build()) // Add the built stroke to the ink
    }
    val newInk = inkBuilder.build()
    // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: Built new Ink object with ${newInk.strokes.size} strokes for chunk $chunkToUpdateId.")

    if (newInk.strokes.isEmpty()) {
        recognizedTextDao.deleteChunkById(chunkToUpdateId)
        Log.w("InkTextSync", "updateRecognizedChunkAfterErasure: Chunk $chunkToUpdateId deleted as the new Ink object had no strokes after processing remainingStrokeIds.") // Keep: Important warning
        return
    }

    // Setup ML Kit Model
    val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
    if (modelIdentifier == null) {
        Log.e("InkTextSync", "updateRecognizedChunkAfterErasure: Failed to get model identifier for re-recognition of chunk $chunkToUpdateId. Language tag: en-US") // Keep: Important error
        return
    }
    val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()

    // Download model if needed
    try {
        val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
        val isDownloaded = Tasks.await(remoteModelManager.isModelDownloaded(model))
        if (!isDownloaded) {
            Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Model for en-US not downloaded for chunk $chunkToUpdateId. Attempting download.") // Keep: Important one-time event
            Tasks.await(remoteModelManager.download(model, com.google.mlkit.common.model.DownloadConditions.Builder().build()))
            Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Model downloaded for en-US for chunk $chunkToUpdateId.") // Keep: Important one-time event
        }
    } catch (e: Exception) {
        Log.e("InkTextSync", "updateRecognizedChunkAfterErasure: Failed to download/check model for chunk $chunkToUpdateId", e) // Keep: Important error
        return
    }

    val recognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(model).build()
    )

    try {
        // Calculate writing area from filtered remaining strokes
        val allPointsForContext = filteredRemainingStrokes.flatMap { it.points }
        val minXForContext = allPointsForContext.minOfOrNull { it.x } ?: 0f
        val minYForContext = allPointsForContext.minOfOrNull { it.y } ?: 0f
        val maxXForContext = allPointsForContext.maxOfOrNull { it.x } ?: 0f
        val maxYForContext = allPointsForContext.maxOfOrNull { it.y } ?: 0f
        val widthForContext = maxXForContext - minXForContext
        val heightForContext = maxYForContext - minYForContext

        val db = AppDatabase.getDatabase(context)
        val originalChunk = db.recognizedTextDao().getChunkById(chunkToUpdateId)
        var preContext = ""
        val lineThreshold = 60f // Same threshold as in reconstructTextFromChunks

        if (originalChunk != null) {
            val allChunksOnPage = db.recognizedTextDao().getChunksForPage(pageId)

            // 1. Try to get pre-context from the same line
            val precedingChunksOnSameLine = allChunksOnPage.filter {
                it.id != chunkToUpdateId &&
                kotlin.math.abs(it.averageY - originalChunk.averageY) <= lineThreshold &&
                it.maxX < originalChunk.minX
            }.sortedByDescending { it.maxX } // Get the closest one first

            if (precedingChunksOnSameLine.isNotEmpty()) {
                preContext = precedingChunksOnSameLine.first().recognizedText.takeLast(20)
                // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: PreContext from same line for chunk $chunkToUpdateId: '$preContext'")
            } else {
                // 2. If no preceding on same line, try lines above
                val chunksAbove = allChunksOnPage.filter {
                    it.id != chunkToUpdateId &&
                    it.averageY < originalChunk.averageY - lineThreshold // Significantly above
                }
                if (chunksAbove.isNotEmpty()) {
                    // reconstructTextFromChunks sorts by averageY then minX, effectively creating reading order
                    preContext = reconstructTextFromChunks(chunksAbove).takeLast(20)
                    // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: PreContext from lines above for chunk $chunkToUpdateId: '$preContext'")
                }
                // If still empty, preContext remains "" (first content on page)
            }
        } else {
            // Log.w("InkTextSync", "updateRecognizedChunkAfterErasure: Could not find original chunk $chunkToUpdateId. Using page-wide fallback for pre-context.") // Potentially useful warning
            // 3. Fallback if original chunk isn't found (should be rare)
            val allOtherChunksOnPageForFallback = db.recognizedTextDao().getChunksForPage(pageId).filterNot { it.id == chunkToUpdateId }
            if (allOtherChunksOnPageForFallback.isNotEmpty()) {
                preContext = reconstructTextFromChunks(allOtherChunksOnPageForFallback).takeLast(20)
                // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: PreContext from page-wide fallback for chunk $chunkToUpdateId: '$preContext'")
            }
        }
        Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Final PreContext for chunk $chunkToUpdateId: '$preContext'") // Keep: Informative for debugging context issues

        val recognitionContext = com.google.mlkit.vision.digitalink.RecognitionContext.builder()
            .setWritingArea(com.google.mlkit.vision.digitalink.WritingArea(widthForContext, heightForContext))
            .setPreContext(preContext)
            .build()

        val result = Tasks.await(recognizer.recognize(newInk, recognitionContext)) // Pass context
        val newRecognizedText = result.candidates.firstOrNull()?.text ?: ""
        // Log.d("InkTextSync", "updateRecognizedChunkAfterErasure: ML Kit re-recognized for chunk $chunkToUpdateId. New text: '$newRecognizedText'")

        // 3. Calculate new bounding box and averageY for the updated chunk using filtered strokes
        val allPointsForUpdate = filteredRemainingStrokes.flatMap { it.points } // Use filtered strokes for bounds
        val newMinX = allPointsForUpdate.minOfOrNull { it.x } ?: 0f
        val newMinY = allPointsForUpdate.minOfOrNull { it.y } ?: 0f
        val newMaxX = allPointsForUpdate.maxOfOrNull { it.x } ?: 0f
        val newMaxY = allPointsForUpdate.maxOfOrNull { it.y } ?: 0f
        val newAverageY = if (allPointsForUpdate.isNotEmpty()) allPointsForUpdate.map { it.y }.average().toFloat() else 0f

        // 4. Update the existing chunk in the database
        val updatedChunk = RecognizedTextChunk(
            id = chunkToUpdateId, // Keep the same ID
            pageId = pageId,
            recognizedText = newRecognizedText,
            minX = newMinX,
            minY = newMinY,
            maxX = newMaxX,
            maxY = newMaxY,
            averageY = newAverageY,
            timestamp = System.currentTimeMillis(), // Update timestamp
            strokeIds = filteredRemainingStrokes.map { it.id } // Use IDs of filtered remaining strokes
        )
        recognizedTextDao.insertChunk(updatedChunk)
        Log.i("InkTextSync", "updateRecognizedChunkAfterErasure: Chunk $chunkToUpdateId updated in DB. New text: '$newRecognizedText', new avgY: $newAverageY") // Keep: Important success log

    } catch (e: Exception) {
        Log.e("InkTextSync", "updateRecognizedChunkAfterErasure: Error during ML Kit recognition or DB update for chunk $chunkToUpdateId: ", e) // Keep: Important error
    }
} 