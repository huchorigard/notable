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

    // Database
    private val db = AppDatabase.getDatabase(context)
    private val recognizedTextDao = db.recognizedTextDao()

    // Recognition state
    private var isRecognizing = false
    private var lastRecognizedText = ""

    init {
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
                        Log.i(TAG, "Model downloaded for en-US")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download/check model", e)
                }
            }
        } else {
            Log.e(TAG, "Could not get model identifier for en-US")
        }
    }

    fun addNewTouchEvent(event: MotionEvent, strokeId: String? = null) {
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        val t = System.currentTimeMillis()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                strokeBuilder = Ink.Stroke.builder()
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
            }
            MotionEvent.ACTION_MOVE -> {
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
            }
            MotionEvent.ACTION_UP -> {
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
                val stroke = strokeBuilder.build()
                inkBuilder.addStroke(stroke)
                if (strokeId != null) {
                    strokeIdMap[stroke] = strokeId
                }
                stateChangedSinceLastRequest = true
                recognize()
            }
        }
    }

    private fun recognize() {
        if (!stateChangedSinceLastRequest) {
            return
        }

        // Cancel any pending recognition
        recognitionJob?.cancel()

        // Start new recognition after timeout
        recognitionJob = coroutineScope.launch {
            kotlinx.coroutines.delay(CONVERSION_TIMEOUT_MS)
            if (stateChangedSinceLastRequest) {
                stateChangedSinceLastRequest = false
                isRecognizing = true

                try {
                    val ink = inkBuilder.build()
                    
                    // Get pre-context from previously recognized text
                    val previousChunks = recognizedTextDao.getChunksForPage(pageId)
                    val preContext = if (previousChunks.isNotEmpty()) {
                        reconstructTextFromChunks(previousChunks).takeLast(20)
                    } else {
                        ""
                    }

                    // Calculate writing area
                    val allPoints = ink.strokes.flatMap { it.points }
                    val minX = allPoints.minOfOrNull { it.x } ?: 0f
                    val minY = allPoints.minOfOrNull { it.y } ?: 0f
                    val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
                    val maxY = allPoints.maxOfOrNull { it.y } ?: 0f
                    val width = maxX - minX
                    val height = maxY - minY

                    // Create recognition context
                    val recognitionContext = com.google.mlkit.vision.digitalink.RecognitionContext.builder()
                        .setWritingArea(com.google.mlkit.vision.digitalink.WritingArea(width, height))
                        .setPreContext(preContext)
                        .build()

                    val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
                    val model = DigitalInkRecognitionModel.builder(modelIdentifier!!).build()
                    val recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                    )

                    val result = recognizer.recognize(ink, recognitionContext).await()
                    val text = result.candidates.firstOrNull()?.text ?: ""

                    if (text.isNotEmpty()) {
                        // Create a chunk with the recognized text
                        val chunk = RecognizedTextChunk(
                            pageId = pageId,
                            recognizedText = text,
                            minX = minX,
                            minY = minY,
                            maxX = maxX,
                            maxY = maxY,
                            timestamp = System.currentTimeMillis(),
                            strokeIds = ink.strokes.mapNotNull { strokeIdMap[it] }
                        )
                        recognizedTextDao.insertChunk(chunk)
                        lastRecognizedText = text
                    }

                    // Clear the ink builder after successful recognition
                    inkBuilder = Ink.builder()
                    strokeIdMap.clear()
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition failed", e)
                } finally {
                    isRecognizing = false
                }
            }
        }
    }

    fun reset() {
        inkBuilder = Ink.builder()
        strokeBuilder = Ink.Stroke.builder()
        stateChangedSinceLastRequest = false
        recognitionJob?.cancel()
        strokeIdMap.clear()
    }
} 