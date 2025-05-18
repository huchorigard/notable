package com.ethran.notable.components


import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.BUTTON_SIZE
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.PenSetting
import com.ethran.notable.utils.UndoRedoType
import com.ethran.notable.utils.createFileFromContentUri
import com.ethran.notable.utils.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.utils.recognizeDigitalInkOnPage
import com.ethran.notable.utils.storeRecognizedTextResult
import android.widget.Toast
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import com.ethran.notable.db.RecognizedTextChunk
import com.ethran.notable.utils.reconstructTextFromChunks
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.window.Dialog
import com.ethran.notable.utils.OpenAISummarizer
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.USER_INFO_KEY
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

fun presentlyUsedToolIcon(mode: Mode, pen: Pen): Int {
    return when (mode) {
        Mode.Draw -> {
            when (pen) {
                Pen.BALLPEN -> R.drawable.ballpen
                Pen.REDBALLPEN -> R.drawable.ballpenred
                Pen.BLUEBALLPEN -> R.drawable.ballpenblue
                Pen.GREENBALLPEN -> R.drawable.ballpengreen
                Pen.FOUNTAIN -> R.drawable.fountain
                Pen.BRUSH -> R.drawable.brush
                Pen.MARKER -> R.drawable.marker
                Pen.PENCIL -> R.drawable.pencil
            }
        }

        Mode.Erase -> R.drawable.eraser
        Mode.Select -> R.drawable.lasso
        Mode.Line -> R.drawable.line
    }
}

fun isSelected(state: EditorState, penType: Pen): Boolean {
    return if (state.mode == Mode.Draw && state.pen == penType) {
        true
    } else if (state.mode == Mode.Line && state.pen == penType) {
        true
    } else {
        false
    }
}

@Composable
@ExperimentalComposeUiApi
fun Toolbar(
    navController: NavController, state: EditorState, controlTower: EditorControlTower
) {
    val scope = rememberCoroutineScope()
    var isStrokeSelectionOpen by remember { mutableStateOf(false) }
    var isMenuOpen by remember { mutableStateOf(false) }
    var isTemplateMenuOpen by remember { mutableStateOf(false) }
    var showRecognizedTextDialog by remember { mutableStateOf(false) }
    var recognizedTextCorpus by remember { mutableStateOf("") }
    var showAIDialog by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiResponse by remember { mutableStateOf("") }
    var isAILoading by remember { mutableStateOf(false) }

    val context = LocalContext.current


    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            uri?.let {
                // Grant read URI permission to access the selected URI
                val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flag)

                //  copy image to documents/notabledb/images/filename
                val copiedFile = createFileFromContentUri(context, uri)

                // Set isImageLoaded to true
                android.util.Log.i(
                    "InsertImage",
                    "Image was received and copied, it is now at:${copiedFile.toUri()}"
                )
                DrawCanvas.addImageByUri.value = copiedFile.toUri()

            }
        }

    LaunchedEffect(isMenuOpen) {
        state.isDrawing = !isMenuOpen
    }

    fun handleChangePen(pen: Pen) {
        if (state.mode == Mode.Draw && state.pen == pen) {
            isStrokeSelectionOpen = true
        } else {
            state.mode = Mode.Draw
            state.pen = pen
        }
    }

    fun handleEraser() {
        state.mode = Mode.Erase
    }

    fun handleSelection() {
        state.mode = Mode.Select
    }

    fun handleLine() {
        state.mode = Mode.Line
    }

    fun onChangeStrokeSetting(penName: String, setting: PenSetting) {
        val settings = state.penSettings.toMutableMap()
        settings[penName] = setting.copy()
        state.penSettings = settings
    }

    if (state.isToolbarOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp) // TODO: fix this
        ) {
            if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Black)
                )
            }
            Row(
                Modifier
                    .background(Color.White)
                    .height(BUTTON_SIZE.dp)
                    .fillMaxWidth()
            ) {
                ToolbarButton(
                    onSelect = {
                        state.isToolbarOpen = !state.isToolbarOpen
                    }, vectorIcon = FeatherIcons.EyeOff, contentDescription = "close toolbar"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.BALLPEN,
                    icon = R.drawable.ballpen,
                    isSelected = isSelected(state, Pen.BALLPEN),
                    onSelect = { handleChangePen(Pen.BALLPEN) },
                    sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                    penSetting = state.penSettings[Pen.BALLPEN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.BALLPEN.penName, it) })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.FOUNTAIN,
                    icon = R.drawable.fountain,
                    isSelected = state.mode == Mode.Draw && state.pen == Pen.FOUNTAIN,
                    onSelect = { handleChangePen(Pen.FOUNTAIN) },
                    sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                    penSetting = state.penSettings[Pen.FOUNTAIN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.FOUNTAIN.penName, it) },
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                LineToolbarButton(
                    unSelect = { state.mode = Mode.Draw },
                    icon = R.drawable.line,
                    isSelected = state.mode == Mode.Line,
                    onSelect = { handleLine() },
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.MARKER,
                    icon = R.drawable.marker,
                    isSelected = state.mode == Mode.Draw && state.pen == Pen.MARKER,
                    onSelect = { handleChangePen(Pen.MARKER) },
                    sizes = listOf("L" to 40f, "XL" to 60f),
                    penSetting = state.penSettings[Pen.MARKER.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.MARKER.penName, it) })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                EraserToolbarButton(
                    isSelected = state.mode == Mode.Erase,
                    onSelect = {
                        handleEraser()
                    },
                    onMenuOpenChange = { isStrokeSelectionOpen = it },
                    value = state.eraser,
                    onChange = { state.eraser = it })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                ToolbarButton(
                    isSelected = state.mode == Mode.Select,
                    onSelect = { handleSelection() },
                    iconId = R.drawable.lasso,
                    contentDescription = "lasso"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    iconId = R.drawable.image,
                    contentDescription = "library",
                    onSelect = {
                        android.util.Log.i("InsertImage", "Launching image picker...")
                        pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    }
                )
                
                if (state.clipboard != null) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        contentDescription = "paste",
                        onSelect = {
                            controlTower.pasteFromClipboard()
                        }
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                // Anchor grid icon and menu
                ToolbarButton(
                    vectorIcon = Icons.Default.GridOn,
                    contentDescription = "Page Template",
                    onSelect = { isTemplateMenuOpen = true }
                )
                DropdownMenu(
                    expanded = isTemplateMenuOpen,
                    onDismissRequest = { isTemplateMenuOpen = false },
                    offset = androidx.compose.ui.unit.DpOffset(0.dp, 40.dp) // Show below the icon
                ) {
                    val pageView = state.pageView
                    val currentTemplate = pageView.pageFromDb?.background ?: "blank"
                    val templateOptions = listOf(
                        "blank" to "Blank page",
                        "dotted" to "Dot grid",
                        "lined" to "Lines",
                        "squared" to "Small squares grid",
                        "hexed" to "Hexagon grid"
                    )
                    templateOptions.forEach { (value, label) ->
                        DropdownMenuItem(onClick = {
                            val updatedPage = pageView.pageFromDb!!.copy(
                                background = value,
                                backgroundType = "native"
                            )
                            pageView.updatePageSettings(updatedPage)
                            scope.launch { DrawCanvas.refreshUi.emit(Unit) }
                            isTemplateMenuOpen = false
                        }) {
                            Text(label + if (currentTemplate == value) "  ✓" else "")
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                ToolbarButton(
                    vectorIcon = Icons.Default.TextFields,
                    contentDescription = "Show Recognized Text",
                    onSelect = {
                        val db = AppDatabase.getDatabase(context)
                        val chunks = db.recognizedTextDao().getChunksForPage(state.pageId)
                        recognizedTextCorpus = reconstructTextFromChunks(chunks)
                        showRecognizedTextDialog = true
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Undo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.undo,
                    contentDescription = "undo"
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Redo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.redo,
                    contentDescription = "redo"
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (state.bookId != null) {
                    val book = AppRepository(context).bookRepository.getById(state.bookId)

                    // TODO maybe have generic utils for this ?
                    val pageNumber = book!!.pageIds.indexOf(state.pageId) + 1
                    val totalPageNumber = book.pageIds.size

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(35.dp)
                            .padding(10.dp, 0.dp)
                    ) {
                        Text(
                            text = "${pageNumber}/${totalPageNumber}",
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.noRippleClickable {
                                navController.navigate("books/${state.bookId}/pages")
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }
                // Add Library Button
                ToolbarButton(
                    iconId = R.drawable.home, // Replace with your library icon resource
                    contentDescription = "library",
                    onSelect = {
                        scope.launch {
                            val noteId = state.bookId
                            val pageId = state.pageId
                            val strokes = state.pageView.strokes
                            if (noteId != null && strokes.isNotEmpty()) {
                                val recognizedText = recognizeDigitalInkOnPage(context, strokes)
                                android.util.Log.i("HandwritingRecognition", "Recognized text: $recognizedText")
                                storeRecognizedTextResult(AppDatabase.getDatabase(context).recognizedTextDao(), noteId, pageId, recognizedText)
                                if (recognizedText == "[Recognition failed]") {
                                    Toast.makeText(context, "Recognition failed", Toast.LENGTH_LONG).show()
                                }
                            }
                            navController.navigate("library") // Navigate to main library
                        }
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                Column {
                    ToolbarButton(
                        onSelect = {
                            isMenuOpen = !isMenuOpen
                        }, iconId = R.drawable.menu, contentDescription = "menu"
                    )
                    if (isMenuOpen) ToolbarMenu(
                        navController = navController,
                        state = state,
                        onClose = { isMenuOpen = false })
                }

                // Add AI button to the toolbar
                IconButton(onClick = { showAIDialog = true }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI Assistant")
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black)
            )
        }

        if (showRecognizedTextDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showRecognizedTextDialog = false
                    state.isDrawing = true // Restore drawing mode
                },
                title = { Text("Recognized Text Corpus") },
                text = { Text(recognizedTextCorpus) },
                confirmButton = {
                    Button(onClick = { 
                        showRecognizedTextDialog = false
                        state.isDrawing = true // Restore drawing mode
                    }) {
                        Text("Close")
                    }
                }
            )
        }

        // AI Dialog
        if (showAIDialog) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = { showAIDialog = false },
                properties = PopupProperties(focusable = true) // Allow focus for scrolling
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                ) {
                    Column(
                        Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("AI Assistant", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        // --- Place for developer prompt customization ---
                        val customPrompt = "You are a thoughtful and insightful assistant designed to help users reflect deeply on their thoughts and experiences.\n\nBelow is a note written by the user. Read the note carefully and generate 1 to 3 thought-provoking questions that encourage deeper reflection. These questions should help the user explore their emotions, motivations, assumptions, or possible next steps.\n\nAim for depth and clarity, not length. Your questions should feel natural, empathetic, and open-ended—like those of a skilled coach or therapist.\n\nUser's note:"
                        // ------------------------------------------------
                        Button(
                            onClick = {
                                isAILoading = true
                                aiResponse = ""
                                scope.launch {
                                    try {
                                        val db = AppDatabase.getDatabase(context)
                                        val chunks = db.recognizedTextDao().getChunksForPage(state.pageId)
                                        val recognizedText = reconstructTextFromChunks(chunks)
                                        val kv = KvProxy(context)
                                        val userInfo = kv.get(USER_INFO_KEY, String.serializer()) ?: ""
                                        val prompt = buildString {
                                            append(customPrompt)
                                            append("\n")
                                            append("<userInfo>\n$userInfo\n</userInfo>\n")
                                            append("This is the note text, careful the text is automatically generated from handwritten note, so some words might be misplaced or incomplete. <note>\n$recognizedText\n</note>\n")
                                        }
                                        android.util.Log.i("OpenAI-AIButton", "Prompt sent to OpenAI: $prompt")
                                        val apiKey = "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"
                                        val result = OpenAISummarizer.summarize(apiKey, prompt)
                                        withContext(Dispatchers.Main) {
                                            aiResponse = result
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.util.Log.e("OpenAI-AIButton", "Error during OpenAI call", e)
                                            aiResponse = "[AI error: ${e.message}]"
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isAILoading = false
                                        }
                                    }
                                }
                            },
                            enabled = !isAILoading
                        ) {
                            Text(if (isAILoading) "Loading..." else "Ask AI")
                        }
                        if (isAILoading) {
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material.CircularProgressIndicator()
                        }
                        if (aiResponse.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFE3F2FD))
                                    .padding(8.dp)
                            ) {
                                Text(aiResponse)
                            }
                        }
                    }
                }
            }
        }
    } else {
        ToolbarButton(
            onSelect = { state.isToolbarOpen = true },
            iconId = presentlyUsedToolIcon(state.mode, state.pen),
            penColor = if (state.mode != Mode.Erase) state.penSettings[state.pen.penName]?.color?.let {
                Color(
                    it
                )
            } else null,
            contentDescription = "open toolbar",
            modifier = Modifier
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp)
        )
    }
}