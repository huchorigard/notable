package com.ethran.notable.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import com.ethran.notable.db.RecognizedText
import com.ethran.notable.db.RecognizedTextDao
import java.util.Date

/**
 * Splits the bounding box of all strokes into 1024x1024 px tiles,
 * and renders the strokes into each tile as a bitmap.
 * Returns a list of Pair(chunkIndex, Bitmap).
 */
fun renderStrokesToChunks(
    strokes: List<Stroke>,
    chunkSize: Int = 1024
): List<Pair<Int, Bitmap>> {
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

    val chunks = mutableListOf<Pair<Int, Bitmap>>()
    var chunkIndex = 0
    for (yChunk in 0 until numChunksY) {
        for (xChunk in 0 until numChunksX) {
            val left = minX + xChunk * chunkSize
            val top = minY + yChunk * chunkSize
            val right = left + chunkSize
            val bottom = top + chunkSize
            val tileRect = RectF(left, top, right, bottom)

            // Create a blank bitmap for this chunk
            val bitmap = Bitmap.createBitmap(chunkSize, chunkSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw strokes that intersect this tile
            for (stroke in strokes) {
                val pointsInTile = stroke.points.filter { p ->
                    p.x >= tileRect.left && p.x < tileRect.right &&
                    p.y >= tileRect.top && p.y < tileRect.bottom
                }
                if (pointsInTile.size >= 2) {
                    for (i in 0 until pointsInTile.size - 1) {
                        val p1 = pointsInTile[i]
                        val p2 = pointsInTile[i + 1]
                        canvas.drawLine(
                            p1.x - tileRect.left,
                            p1.y - tileRect.top,
                            p2.x - tileRect.left,
                            p2.y - tileRect.top,
                            stroke.toPaint()
                        )
                    }
                }
            }
            chunks.add(chunkIndex to bitmap)
            chunkIndex++
        }
    }
    return chunks
}

// Helper to convert a Stroke to a Paint object for drawing
fun Stroke.toPaint(): Paint {
    val paint = Paint()
    paint.color = this.color
    paint.strokeWidth = this.size
    paint.style = Paint.Style.STROKE
    paint.isAntiAlias = true
    paint.strokeCap = Paint.Cap.ROUND
    return paint
}

/**
 * Runs ML Kit text recognition on each bitmap chunk and returns a list of recognized texts.
 * Each result is a Pair(chunkIndex, recognizedText).
 */
suspend fun recognizeTextInChunks(
    context: Context,
    chunks: List<Pair<Int, Bitmap>>,
    logTag: String = "HandwritingRecognition"
): List<Pair<Int, String>> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    val results = mutableListOf<Pair<Int, String>>()
    for ((chunkIndex, bitmap) in chunks) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text
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