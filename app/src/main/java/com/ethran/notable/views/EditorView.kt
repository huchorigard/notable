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
import com.ethran.notable.db.RecognizedTextChunk
import com.ethran.notable.utils.reconstructTextFromChunks
import com.ethran.notable.db.PageSummary
import com.ethran.notable.db.PageSummaryDao
import java.util.Date
import kotlinx.coroutines.runBlocking
import com.ethran.notable.utils.GemmaModelManager
import com.ethran.notable.utils.GemmaMediaPipeSummarizer
import androidx.compose.runtime.rememberUpdatedState
import android.content.Context
import kotlinx.coroutines.GlobalScope
import com.ethran.notable.utils.OpenAISummarizer
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.USER_INFO_KEY
import kotlinx.serialization.builtins.serializer


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

        var shouldSummarize by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onDispose {
                Log.i(TAG, "EditorView: onDispose called, triggering summary for page $_pageId")
                val currentContext = context
                val currentPageId = _pageId
                GlobalScope.launch {
                    try {
                        Log.i(TAG, "EditorView: Starting summary for page $currentPageId (onDispose)")
                        val db = AppDatabase.getDatabase(currentContext)
                        val recognizedTextChunks = db.recognizedTextDao().getChunksForPage(currentPageId)
                        val recognizedText = reconstructTextFromChunks(recognizedTextChunks)
                        val summary = summarizeWithLLM(currentContext, currentPageId, recognizedText)
                        val pageSummary = PageSummary(
                            pageId = currentPageId,
                            summaryText = summary,
                            timestamp = System.currentTimeMillis()
                        )
                        db.pageSummaryDao().insert(pageSummary)
                        Log.i(TAG, "EditorView: Summary for page $currentPageId saved to database (onDispose). Summary: $summary")
                    } catch (e: Exception) {
                        Log.e(TAG, "EditorView: Summary failed for page $currentPageId (onDispose): ${e.message}", e)
                    }
                }
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
            var showRecognizedTextDialog by remember { mutableStateOf(false) }
            var recognizedTextCorpus by remember { mutableStateOf("") }
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
        }
    }
}

suspend fun summarizeWithLLM(context: Context, pageId: String, text: String): String {
    // Retrieve the current summary from the database if it exists
    val db = com.ethran.notable.db.AppDatabase.getDatabase(context)
    val currentSummary = db.pageSummaryDao().getSummary(pageId)?.summaryText
    val currentSummaryPrompt = if (!currentSummary.isNullOrBlank()) "Current summary: $currentSummary\n" else ""

    // Retrieve user info from settings
    val kv = KvProxy(context)
    val userInfo = kv.get(USER_INFO_KEY, String.serializer())
    val userInfoPrompt = if (!userInfo.isNullOrBlank()) "User info: $userInfo\n" else ""

    val prompt = """
<system>
You are an AI assistant for a note-taking application. Your task is to generate a concise and descriptive summary for each user's note. This summary will be displayed on a small card in the app's interface, allowing users to quickly understand the content of the note without opening it.
</system>

<instructions>
Analyze the Content: Read the provided note text carefully to identify the main topics, key ideas, events, or a brief emotional sentiment if prominent.
Careful, the text that you are receiving is recognised from handwritten characters. There might be letters missing, words looking weird, you'll have to make some guess sometimes.
Be Descriptive: The summary must accurately reflect the core essence of the note. It should give the user a clear indication of what the note is about.
Be Concise: The summary should be very short, ideally a single phrase or a very short sentence, suitable for a small display area. Think of it as a "glanceable" preview.
Focus on Key Information: Extract the most important information that would help a user recall or identify the note's purpose.
Neutral Tone (Generally): Unless the note's content is explicitly and overwhelmingly emotional, maintain a relatively neutral and informative tone. If strong emotion is the core of the note, a hint of it can be included if done concisely.
</instructions>

<output>
Generate a single, brief, and descriptive summary of the note (10-18 words). Do not use " before/after the answer, just plain text.
If a summary already exists, only edit it if it does not fit the note content or lacks important information. Otherwise, keep the summary as is.
</output>


If a summary already exists, only edit it if it does not fit the note content or lacks important information. Otherwise, keep the summary as is.
<current_summary>
$currentSummaryPrompt</current_summary>


This is background information that the user filled so that you can better understand them.
<user_info>
$userInfoPrompt</user_info>

Here is the note that the user wrote. Careful, the text is generated automatically from a handwritten algorithm, so the words might be wrongly spelled, or some words might be missing.
<note>
$text
</note>
"""

    // Hardcoded API key (replace with your real key)
    val openAiApiKey = "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"
    Log.i("EditorView", "Sending prompt to OpenAI: $prompt")
    return OpenAISummarizer.summarize(openAiApiKey, prompt)
}


