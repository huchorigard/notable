package com.ethran.notable.utils

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.RecognizedTextChunk
import com.ethran.notable.db.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class StrokeManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val pageId: String
) {
    private val TAG = "StrokeManager"
    private val CONVERSION_TIMEOUT_MS = 1000L

    // ML Kit components
    private var inkBuilder = Ink.builder()
    private var strokeBuilder = Ink.Stroke.builder()
    private var stateChangedSinceLastRequest = false
    private var recognitionJob: Job? = null

    // Stroke tracking
    private val strokeIdMap = mutableMapOf<Ink.Stroke, String>()
    private val persistedStrokeIds = mutableSetOf<String>() // New set to track persisted IDs

    // Database
    private val db = AppDatabase.getDatabase(context)
    private val recognizedTextDao = db.recognizedTextDao()

    // Recognition state
    private var isRecognizing = false
    private var lastRecognizedText = ""

    init {
        Log.d("InkTextSync", "StrokeManager: Initializing for page $pageId")
        // Initialize ML Kit recognizer
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        if (modelIdentifier != null) {
            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )

            // Download model if needed
            coroutineScope.launch {
                try {
                    val remoteModelManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
                    val isDownloaded = remoteModelManager.isModelDownloaded(model).await()
                    if (!isDownloaded) {
                        remoteModelManager.download(model, com.google.mlkit.common.model.DownloadConditions.Builder().build()).await()
                        Log.i("InkTextSync", "StrokeManager: Model downloaded for en-US")
                    }
                } catch (e: Exception) {
                    Log.e("InkTextSync", "StrokeManager: Failed to download/check model during init", e)
                }
            }
        } else {
            Log.e("InkTextSync", "StrokeManager: Could not get model identifier for en-US during init")
        }
    }

    fun addNewTouchEvent(event: MotionEvent, strokeId: String?) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // It's a new stroke. Create a new Ink.Stroke.Builder.
                strokeBuilder = Ink.Stroke.builder()
                strokeBuilder.addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
                // Log.d("InkTextSync", "StrokeManager: ACTION_DOWN - StrokeBuilder reset and point added.")
                stateChangedSinceLastRequest = true
                // No need to add to strokeIdMap here, wait for the stroke to be built
            }
            MotionEvent.ACTION_MOVE -> {
                strokeBuilder.addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
                // Log.d("InkTextSync", "StrokeManager: ACTION_MOVE - Point added.")
                stateChangedSinceLastRequest = true
            }
            MotionEvent.ACTION_UP -> {
                strokeBuilder.addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
                val inkStroke = strokeBuilder.build() // The actual Ink.Stroke object
                inkBuilder.addStroke(inkStroke)

                // Associate this ML Kit stroke with the original String UUID
                if (strokeId != null) {
                    strokeIdMap[inkStroke] = strokeId // Correct: Use the built inkStroke as the key
                    // Log.d("InkTextSync", "StrokeManager: ACTION_UP - ML Kit Stroke added to InkBuilder. Associated String Stroke ID $strokeId with Ink.Stroke object.")
                }
                stateChangedSinceLastRequest = true
                // Log.d("InkTextSync", "StrokeManager: ACTION_UP - State changed, calling recognize().")
                recognize()
            }
            else -> {
                // Other MotionEvent actions are not handled here.
            }
        }
    }

    private fun recognize() {
        if (isRecognizing) {
            // Log.d("InkTextSync", "StrokeManager: recognize() called, but already recognizing. Buffering.")
            return
        }
        // Log.d("InkTextSync", "StrokeManager: recognize() called. stateChangedSinceLastRequest: $stateChangedSinceLastRequest")

        if (!stateChangedSinceLastRequest) {
            // Log.d("InkTextSync", "StrokeManager: recognize() - No state change since last request, skipping.")
            return
        }

        recognitionJob?.cancel()
        // Log.d("InkTextSync", "StrokeManager: recognize() - Previous recognitionJob cancelled.")

        recognitionJob = coroutineScope.launch {
            // Log.d("InkTextSync", "StrokeManager: recognize() - Coroutine launched for recognition. Waiting ${CONVERSION_TIMEOUT_MS}ms.")
            kotlinx.coroutines.delay(CONVERSION_TIMEOUT_MS)
            isRecognizing = true
            // Log.d("InkTextSync", "StrokeManager: recognize() - Timeout finished, proceeding with recognition.")

            if (inkBuilder.isEmpty()) {
                // Log.d("InkTextSync", "StrokeManager: recognize() - InkBuilder is empty, nothing to recognize.")
                isRecognizing = false
                return@launch
            }

            val inkToRecognize = inkBuilder.build()
            // Log.d("InkTextSync", "StrokeManager: recognize() - Built Ink with ${inkToRecognize.strokes.size} strokes.")

            // Calculate writing area from strokes more accurately
            val allPoints = inkToRecognize.strokes.flatMap { it.points }
            val minX = allPoints.minOfOrNull { it.x } ?: 0f
            val minY = allPoints.minOfOrNull { it.y } ?: 0f
            val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
            val maxY = allPoints.maxOfOrNull { it.y } ?: 0f
            val width = maxX - minX
            val height = maxY - minY
            val averageY = if (allPoints.isNotEmpty()) allPoints.map { it.y }.average().toFloat() else 0f

            // Get pre-context from previously recognized text
            val previousChunks = recognizedTextDao.getChunksForPage(pageId)
            val preContext = if (previousChunks.isNotEmpty()) {
                reconstructTextFromChunks(previousChunks).takeLast(20)
            } else {
                ""
            }
            // Log.d("InkTextSync", "StrokeManager: recognize() - PreContext: '$preContext'")

            val recognitionContext = com.google.mlkit.vision.digitalink.RecognitionContext.builder()
                .setWritingArea(com.google.mlkit.vision.digitalink.WritingArea(width, height))
                .setPreContext(preContext)
                .build()

            // Log.d("InkTextSync", "StrokeManager: recognize() - Recognizer ready.")
            try {
                val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
                val model = DigitalInkRecognitionModel.builder(modelIdentifier!!).build()
                val recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                val result = recognizer.recognize(inkToRecognize, recognitionContext).await()
                val recognizedText = result.candidates.firstOrNull()?.text ?: ""
                // Log.d("InkTextSync", "StrokeManager: recognize() - Recognition result: '$recognizedText'")

                if (recognizedText.isNotBlank()) {
                    // Collect all stroke IDs that contributed to this recognized text
                    // We iterate over the inkToRecognize.strokes and look up their original UUIDs
                    // from strokeIdMap. Then, we filter these by checking against persistedStrokeIds.
                    val recognizedAndPersistedStrokeIds = mutableListOf<String>()
                    for (mlKitStroke in inkToRecognize.strokes) {
                        strokeIdMap[mlKitStroke]?.let { uuid ->
                            if (persistedStrokeIds.contains(uuid)) {
                                recognizedAndPersistedStrokeIds.add(uuid)
                            } else {
                                Log.w("InkTextSync", "StrokeManager: recognize() - Stroke $uuid was in recognized ink but not in persistedStrokeIds. Excluding from chunk.")
                            }
                        }
                    }

                    Log.d("InkTextSync", "StrokeManager: recognize() - Text recognized. Associated Persisted Stroke IDs: $recognizedAndPersistedStrokeIds")

                    val timestamp = System.currentTimeMillis() // Or derive from stroke points

                    // Create the RecognizedTextChunk
                    val chunk = RecognizedTextChunk(
                        pageId = pageId,
                        recognizedText = recognizedText,
                        minX = minX,
                        minY = minY,
                        maxX = maxX,
                        maxY = maxY,
                        averageY = averageY,
                        timestamp = timestamp,
                        strokeIds = recognizedAndPersistedStrokeIds // Use the filtered list
                    )
                    // Insert into database
                    recognizedTextDao.insertChunk(chunk)
                    Log.i("InkTextSync", "StrokeManager: recognize() - New chunk created and inserted. PageId: $pageId, Text: '$recognizedText', DB Stroke IDs: $recognizedAndPersistedStrokeIds")

                    // Clear the ink builder and the stroke ID map for the next set of strokes
                    inkBuilder = Ink.builder() // Reset for next batch
                    strokeIdMap.clear()
                    persistedStrokeIds.clear() // Clear persisted IDs for the new batch
                    // Log.d("InkTextSync", "StrokeManager: recognize() - InkBuilder, strokeIdMap, and persistedStrokeIds cleared.")
                } else {
                    // Log.d("InkTextSync", "StrokeManager: recognize() - Recognized text is blank.")
                    // If text is blank, we might not want to clear the inkBuilder yet,
                    // or we might want to handle this case differently (e.g. if user just drew a dot)
                    // For now, if nothing is recognized, we clear. This means small/unrecognized marks won't linger.
                    inkBuilder = Ink.builder()
                    strokeIdMap.clear()
                    persistedStrokeIds.clear() // Clear persisted IDs
                    // Log.d("InkTextSync", "StrokeManager: recognize() - InkBuilder, strokeIdMap, and persistedStrokeIds cleared due to blank recognition.")
                }
            } catch (e: Exception) {
                Log.e("InkTextSync", "StrokeManager: recognize() - Recognition failed.", e)
                // Consider how to handle failed recognition - clear ink? Retry?
                // For now, clear to avoid processing the same failing strokes again.
                inkBuilder = Ink.builder()
                strokeIdMap.clear()
                persistedStrokeIds.clear() // Clear persisted IDs
            } finally {
                stateChangedSinceLastRequest = false
                isRecognizing = false
                // Log.d("InkTextSync", "StrokeManager: recognize() - Recognition attempt finished.")
            }
        }
    }

    fun reset() {
        Log.d("InkTextSync", "StrokeManager: reset() called for page $pageId")
        isRecognizing = false
        inkBuilder = Ink.builder()
        strokeBuilder = Ink.Stroke.builder()
        stateChangedSinceLastRequest = false
        recognitionJob?.cancel()
        strokeIdMap.clear()
        persistedStrokeIds.clear() // Clear persisted IDs here as well
    }

    fun confirmStrokePersisted(strokeId: String) {
        Log.d("InkTextSync", "StrokeManager: Confirming stroke $strokeId as persisted.")
        persistedStrokeIds.add(strokeId)
    }
} 