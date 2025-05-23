package com.ethran.notable.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.XoppFile
import com.ethran.notable.components.BreadCrumb
import com.ethran.notable.components.PageMenu
import com.ethran.notable.components.PagePreview
import com.ethran.notable.components.ShowConfirmationDialog
import com.ethran.notable.components.Topbar
import com.ethran.notable.db.BookRepository
import com.ethran.notable.db.Folder
import com.ethran.notable.db.Notebook
import com.ethran.notable.db.Page
import com.ethran.notable.modals.AppSettingsModal
import com.ethran.notable.modals.FolderConfigDialog
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.modals.NotebookConfigDialog
import com.ethran.notable.modals.UserInfoDialog
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import com.ethran.notable.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.clickable
import java.io.File

@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@Composable
fun Library(navController: NavController, folderId: String? = null) {
    val context = LocalContext.current
    var isSettingsOpen by remember {
        mutableStateOf(false)
    }
    val appRepository = AppRepository(context)

    val singlePagesState by appRepository.pageRepository.getSinglePagesInFolder(folderId).observeAsState()

    val allNotesData: List<Page> = remember(singlePagesState) {
        (singlePagesState ?: emptyList()).reversed() // Show most recent first (already Page objects)
    }

    val itemsPerPage = 6
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = remember(allNotesData.size) {
        (allNotesData.size + itemsPerPage - 1) / itemsPerPage
    }
    if (currentPage >= totalPages && totalPages > 0) {
        currentPage = totalPages - 1
    } else if (totalPages == 0) {
        currentPage = 0
    }

    val paginatedNotes = remember(allNotesData, currentPage) {
        if (allNotesData.isNotEmpty()) {
            allNotesData.drop(currentPage * itemsPerPage).take(itemsPerPage)
        } else {
            emptyList()
        }
    }

    val books by appRepository.bookRepository.getAllInFolder(folderId).observeAsState()
    val folders by appRepository.folderRepository.getAllInFolder(folderId).observeAsState()
    val bookRepository = BookRepository(context)

    var isLatestVersion by remember {
        mutableStateOf(true)
    }
    LaunchedEffect(key1 = Unit, block = {
        thread {
            isLatestVersion = isLatestVersion(context, true)
        }
    })

    var importInProgress = false

    var showFloatingEditor by remember { mutableStateOf(false) }
    var floatingEditorPageId by remember { mutableStateOf<String?>(null) }

    val snackManager = LocalSnackContext.current

    var showUserInfo by remember { mutableStateOf(false) }

    var contextMenuForPageId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Welcome back",
                style = MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val page = Page(
                            notebookId = null,
                            background = GlobalAppSettings.current.defaultNativeTemplate,
                            parentFolderId = folderId
                        )
                        appRepository.pageRepository.create(page)
                        navController.navigate("pages/${page.id}")
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.LightGray),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("New Note", fontSize = 16.sp)
                }
                
                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        FeatherIcons.Settings,
                        contentDescription = "Settings",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Text(
            text = "Explore what you wrote recently:",
            style = MaterialTheme.typography.subtitle1,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(paginatedNotes) { page ->
                        NoteCard(
                            pageId = page.id,
                            isContextMenuVisible = contextMenuForPageId == page.id,
                            onLongClick = {
                                contextMenuForPageId = page.id
                            },
                            onDismissContextMenu = {
                                contextMenuForPageId = null
                            },
                            onDeleteRequest = { pageIdToDelete ->
                                Log.i(TAG, "Delete requested for page: $pageIdToDelete")
                                // Delete preview files
                                val previewDir = File(context.filesDir, "pages/previews/full")
                                val previewFile = File(previewDir, pageIdToDelete)
                                if (previewFile.exists()) {
                                    previewFile.delete()
                                }
                                // Delete the page from database
                                appRepository.pageRepository.delete(pageIdToDelete)
                                // Delete the page summary
                                AppDatabase.getDatabase(context).pageSummaryDao().deleteSummary(pageIdToDelete)
                                contextMenuForPageId = null
                            },
                            onCardClick = { pageId ->
                                navController.navigate("pages/$pageId")
                            }
                        )
                    }
                }

                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Icon(
                            FeatherIcons.ChevronUp,
                            contentDescription = "Scroll Up",
                            modifier = Modifier.size(36.dp),
                            tint = if (currentPage > 0) Color.Black else Color.Gray
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    IconButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Icon(
                            FeatherIcons.ChevronDown,
                            contentDescription = "Scroll Down",
                            modifier = Modifier.size(36.dp),
                            tint = if (currentPage < totalPages - 1) Color.Black else Color.Gray
                        )
                    }
                }
            }
        }
    }

    if (isSettingsOpen) AppSettingsModal(
        onClose = { isSettingsOpen = false },
        onUserInfo = { showUserInfo = true }
    )
    if (showUserInfo) UserInfoDialog(onClose = { showUserInfo = false })

    if (showFloatingEditor && floatingEditorPageId != null) {
        FloatingEditorView(
            navController = navController,
            pageId = floatingEditorPageId!!,
            onDismissRequest = {
                showFloatingEditor = false
                floatingEditorPageId = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    pageId: String,
    isContextMenuVisible: Boolean,
    onLongClick: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onDeleteRequest: (String) -> Unit,
    onCardClick: (String) -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    val summaryLive = remember(pageId) { db.pageSummaryDao().getSummaryLive(pageId) }
    val summary = summaryLive.observeAsState().value?.summaryText

    // Get tags using LiveData
    val tagsLive = remember(pageId) { db.tagDao().getTagsForPageLive(pageId) }
    val tags = tagsLive.observeAsState().value
    val tagsText = if (!tags.isNullOrEmpty()) {
        tags.joinToString(", ") { it.name }
    } else null

    var updatedAt by remember(pageId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(pageId) {
        val page = db.pageDao().getById(pageId)
        updatedAt = page?.updatedAt?.time
    }

    val dateString = remember(updatedAt) {
        if (updatedAt == null) ""
        else {
            val now = System.currentTimeMillis()
            val millisInDay = 24 * 60 * 60 * 1000
            val daysAgo = ((now - updatedAt!!) / millisInDay).toInt()
            when {
                daysAgo < 1 -> "Today"
                daysAgo < 10 -> "${daysAgo}d ago"
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(updatedAt!!))
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onCardClick(pageId) },
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = dateString,
                style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary ?: "Summarizing...",
                style = MaterialTheme.typography.body2,
                maxLines = 4, // Reduced to make room for tags
                modifier = Modifier.weight(1f)
            )
            if (!tagsText.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tagsText,
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }

        if (isContextMenuVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
            ) {
                NoteCardContextMenu(
                    pageId = pageId,
                    onDismiss = onDismissContextMenu,
                    onDelete = onDeleteRequest
                )
            }
        }
    }
}

@Composable
fun NoteCardContextMenu(
    pageId: String,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "Delete",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        onDelete(pageId)
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.body2
            )
        }
    }
}



