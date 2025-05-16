package com.ethran.notable.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavController
import android.util.Log
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.classes.PageView
import com.ethran.notable.components.EditorGestureReceiver
import com.ethran.notable.components.EditorSurface
import com.ethran.notable.components.ScrollIndicator
import com.ethran.notable.components.SelectedBitmap
import com.ethran.notable.components.Toolbar
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.convertDpToPixel
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.utils.storeRecognizedTextResults
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.AlertDialog
import androidx.compose.material.Surface
import androidx.compose.foundation.Image
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ethran.notable.utils.chunkStrokesForDigitalInk
import com.ethran.notable.utils.recognizeDigitalInkInChunks
import com.ethran.notable.utils.recognizeDigitalInkOnPage
import com.ethran.notable.utils.storeRecognizedTextResult


@OptIn(ExperimentalComposeUiApi::class)
@Composable
@ExperimentalFoundationApi
fun EditorView(
    navController: NavController, _bookId: String?, _pageId: String
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // control if we do have a page
    if (AppRepository(context).pageRepository.getById(_pageId) == null) {
        if (_bookId != null) {
            // clean the book
            Log.i(TAG, "Cleaning book")
            AppRepository(context).bookRepository.removePage(_bookId, _pageId)
        }
        navController.navigate("library")
        return
    }

    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()


        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                id = _pageId,
                width = width,
                viewWidth = width,
                viewHeight = height
            )
        }


        // Dynamically update the page width when the Box constraints change
        LaunchedEffect(width, height) {
            if (page.width != width || page.viewHeight != height) {
                page.updateDimensions(width, height)
                DrawCanvas.refreshUi.emit(Unit)
            }
        }

        val editorState =
            remember { EditorState(bookId = _bookId, pageId = _pageId, pageView = page) }

        val history = remember {
            History(scope, page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState)
        }

        val appRepository = AppRepository(context)

        // update opened page
        LaunchedEffect(Unit) {
            if (_bookId != null) {
                appRepository.bookRepository.setOpenPageId(_bookId, _pageId)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                page.onDispose()
            }
        }

        // TODO put in editorSetting class
        LaunchedEffect(
            editorState.isToolbarOpen,
            editorState.pen,
            editorState.penSettings,
            editorState.mode
        ) {
            Log.i(TAG, "EditorView: saving")
            EditorSettingCacheManager.setEditorSettings(
                context,
                EditorSettingCacheManager.EditorSettings(
                    isToolbarOpen = editorState.isToolbarOpen,
                    mode = editorState.mode,
                    pen = editorState.pen,
                    eraser = editorState.eraser,
                    penSettings = editorState.penSettings
                )
            )
        }

        val lastRoute = navController.previousBackStackEntry

        fun goToNextPage() {
            if (_bookId != null) {
                val newPageId = appRepository.getNextPageIdFromBookAndPage(
                    pageId = _pageId, notebookId = _bookId
                )
                navController.navigate("books/${_bookId}/pages/${newPageId}") {
                    popUpTo(lastRoute!!.destination.id) {
                        inclusive = false
                    }
                }
            }
        }

        fun goToPreviousPage() {
            if (_bookId != null) {
                val newPageId = appRepository.getPreviousPageIdFromBookAndPage(
                    pageId = _pageId, notebookId = _bookId
                )
                if (newPageId != null) navController.navigate("books/${_bookId}/pages/${newPageId}")
            }
        }

        val toolbarPosition = GlobalAppSettings.current.toolbarPosition

        InkaTheme {
            var showBitmapDialog by remember { mutableStateOf(false) }
            var bitmapToShow by remember { mutableStateOf<ImageBitmap?>(null) }
            EditorSurface(
                state = editorState, page = page, history = history
            )
            EditorGestureReceiver(
                goToNextPage = ::goToNextPage,
                goToPreviousPage = ::goToPreviousPage,
                controlTower = editorControlTower,
                state = editorState
            )
            SelectedBitmap(
                context = context,
                editorState = editorState,
                controlTower = editorControlTower
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(context = context, state = editorState)
            }
            // Toolbar at Top or Bottom
            when (toolbarPosition) {
                AppSettings.Position.Top -> {
                    Toolbar(
                        navController = navController,
                        state = editorState,
                        controlTower = editorControlTower
                    )
                }

                AppSettings.Position.Bottom -> {
                    Column(Modifier.fillMaxWidth().fillMaxHeight()) { //this fixes this
                        Spacer(modifier = Modifier.weight(1f))
                        // Top/center content here
                        Toolbar(
                            navController = navController,
                            state = editorState,
                            controlTower = editorControlTower
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    System.out.println("[HandwritingRecognition] FAB onClick triggered")
                    scope.launch {
                        try {
                            val noteId = _bookId ?: _pageId
                            val pageId = _pageId
                            val strokes = page.strokes
                            Log.i("HandwritingRecognition", "FAB clicked: noteId=$noteId, pageId=$pageId, strokes=${strokes.size}")
                            System.out.println("[HandwritingRecognition] FAB clicked: noteId=$noteId, pageId=$pageId, strokes=${strokes.size}")
                            if (strokes.isEmpty()) {
                                Toast.makeText(context, "No strokes to recognize", Toast.LENGTH_SHORT).show()
                                Log.i("HandwritingRecognition", "No strokes to recognize on FAB click.")
                                System.out.println("[HandwritingRecognition] No strokes to recognize on FAB click.")
                                return@launch
                            }
                            val recognizedText = recognizeDigitalInkOnPage(context, strokes)
                            Log.i("HandwritingRecognition", "Recognized text: $recognizedText")
                            System.out.println("[HandwritingRecognition] Recognized text: $recognizedText")
                            val db = AppDatabase.getDatabase(context)
                            storeRecognizedTextResult(db.recognizedTextDao(), noteId, pageId, recognizedText)
                            if (recognizedText == "[Recognition failed]") {
                                Toast.makeText(context, "Recognition failed", Toast.LENGTH_LONG).show()
                                Log.i("HandwritingRecognition", "Recognition failed.")
                                System.out.println("[HandwritingRecognition] Recognition failed.")
                            } else {
                                Toast.makeText(context, "Recognition complete!", Toast.LENGTH_SHORT).show()
                                Log.i("HandwritingRecognition", "Recognition complete and successful.")
                                System.out.println("[HandwritingRecognition] Recognition complete and successful.")
                            }
                        } catch (e: Exception) {
                            Log.e("HandwritingRecognition", "Exception in FAB handler", e)
                            System.out.println("[HandwritingRecognition] Exception in FAB handler: ${e.message}")
                        }
                    }
                },
                backgroundColor = Color.Black,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
            ) {
                Icon(Icons.Default.TextFields, contentDescription = "Recognize Handwriting")
            }
        }
    }
}


