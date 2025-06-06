package com.ethran.notable.modals

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.BuildConfig
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.Tag
import com.ethran.notable.utils.isNext
import com.ethran.notable.utils.noRippleClickable
import kotlinx.serialization.Serializable
import java.util.UUID

// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    fun update(settings: AppSettings) {
        _current.value = settings
    }
}


@Serializable
data class AppSettings(
    val version: Int,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val debugMode: Boolean = false,
    val neoTools: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val tags: List<String> = listOf("Work", "Personal", "Ideas", "To-Do", "Important", "Learning", "Meeting"),

    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
    val holdAction: GestureAction? = defaultHoldAction,

) {
    companion object {
        val defaultDoubleTapAction get() = GestureAction.Undo
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction get() = GestureAction.ToggleZen
        val defaultHoldAction get() = GestureAction.Select
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }

}

@Composable
fun AppSettingsModal(onClose: () -> Unit, onUserInfo: () -> Unit = {}) {
    val context = LocalContext.current
    val kv = KvProxy(context)
    val settings = GlobalAppSettings.current ?: return
    
    // State for tag input
    var tagInput by remember { mutableStateOf(settings.tags.joinToString(", ")) }
    var isEditingTags by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(40.dp)
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(Modifier.padding(20.dp, 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App setting - v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}",
                        style = MaterialTheme.typography.h5,
                    )
                }
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(Modifier.padding(20.dp, 10.dp)) {
                GeneralSettings(kv, settings)
                EditGestures(kv, settings)
                
                // Tags section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Tags")
                        if (isEditingTags) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = tagInput,
                                    onValueChange = { tagInput = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(230, 230, 230, 255))
                                        .padding(8.dp),
                                    textStyle = TextStyle(
                                        fontSize = 14.sp
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        // Process and save tags
                                        val newTags = tagInput
                                            .split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .distinct()
                                        
                                        // Update settings
                                        kv.setAppSettings(settings.copy(tags = newTags))
                                        
                                        // Update database
                                        val db = AppDatabase.getDatabase(context)
                                        val tagDao = db.tagDao()
                                        
                                        // Remove tags that are no longer in the list
                                        val existingTags = tagDao.getAllTags()
                                        existingTags.forEach { tag ->
                                            if (!newTags.contains(tag.name)) {
                                                tagDao.deleteTag(tag)
                                            }
                                        }
                                        
                                        // Add new tags
                                        newTags.forEach { tagName ->
                                            if (existingTags.none { it.name == tagName }) {
                                                tagDao.insertTag(Tag(id = UUID.randomUUID().toString(), name = tagName))
                                            }
                                        }
                                        
                                        isEditingTags = false
                                    }
                                ) {
                                    Text("Save")
                                }
                            }
                        } else {
                            Text(
                                text = settings.tags.joinToString(", "),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isEditingTags = true }
                                    .background(Color(230, 230, 230, 255))
                                    .padding(8.dp),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))
                Button(onClick = { onUserInfo() }) {
                    Text("User Information")
                }
            }
        }
    }
}

@Composable
fun GeneralSettings(kv: KvProxy, settings: AppSettings) {
    Row {
        Text(text = "Default Page Background Template")
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                "blank" to "Blank page",
                "dotted" to "Dot grid",
                "lined" to "Lines",
                "squared" to "Small squares grid",
                "hexed" to "Hexagon grid",
            ),
            onChange = {
                kv.setAppSettings(settings.copy(defaultNativeTemplate = it))
            },
            value = settings.defaultNativeTemplate
        )
    }
    Spacer(Modifier.height(10.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Debug Mode (show changed area)")
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.debugMode,
            onCheckedChange = { isChecked ->
                kv.setAppSettings(settings.copy(debugMode = isChecked))
            }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Use Onyx NeoTools (may cause crashes)")
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.neoTools,
            onCheckedChange = { isChecked ->
                kv.setAppSettings(settings.copy(neoTools = isChecked))
            }
        )
    }
    Spacer(Modifier.height(10.dp))

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Toolbar Position (Work in progress)")
            Spacer(modifier = Modifier.width(10.dp))

            SelectMenu(
                options = listOf(
                    AppSettings.Position.Top to "Top",
                    AppSettings.Position.Bottom to "Bottom"
                ),
                value = settings.toolbarPosition,
                onChange = { newPosition ->
                    settings.let {
                        kv.setAppSettings(it.copy(toolbarPosition = newPosition))
                    }
                }
            )
        }
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    kv: KvProxy,
    settings: AppSettings?,
    update: AppSettings.(AppSettings.GestureAction?) -> AppSettings,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
) {
    Row {
        Text(text = title)
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                null to "None",
                AppSettings.GestureAction.Undo to "Undo",
                AppSettings.GestureAction.Redo to "Redo",
                AppSettings.GestureAction.PreviousPage to "Previous Page",
                AppSettings.GestureAction.NextPage to "Next Page",
                AppSettings.GestureAction.ChangeTool to "Toggle Pen / Eraser",
                AppSettings.GestureAction.ToggleZen to "Toggle Zen Mode",
            ),
            value = if (settings != null) settings.override() else default,
            onChange = {
                if (settings != null) {
                    kv.setAppSettings(settings.update(it))
                }
            },
        )
    }
}


@Composable
fun EditGestures(kv: KvProxy, settings: AppSettings?) {
    var gestureExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { gestureExpanded = !gestureExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gesture Settings",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (gestureExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (gestureExpanded) "Collapse" else "Expand"
            )
        }

        if (gestureExpanded) {
            Divider(
                color = Color.LightGray,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            GestureSelectorRow(
                title = "Double Tap Action",
                kv = kv,
                settings = settings,
                update = { copy(doubleTapAction = it) },
                default = AppSettings.defaultDoubleTapAction,
                override = { doubleTapAction }
            )

            GestureSelectorRow(
                title = "Two Finger Tap Action",
                kv = kv,
                settings = settings,
                update = { copy(twoFingerTapAction = it) },
                default = AppSettings.defaultTwoFingerTapAction,
                override = { twoFingerTapAction }
            )

            GestureSelectorRow(
                title = "Swipe Left Action",
                kv = kv,
                settings = settings,
                update = { copy(swipeLeftAction = it) },
                default = AppSettings.defaultSwipeLeftAction,
                override = { swipeLeftAction }
            )

            GestureSelectorRow(
                title = "Swipe Right Action",
                kv = kv,
                settings = settings,
                update = { copy(swipeRightAction = it) },
                default = AppSettings.defaultSwipeRightAction,
                override = { swipeRightAction }
            )

            GestureSelectorRow(
                title = "Two Finger Swipe Left Action",
                kv = kv,
                settings = settings,
                update = { copy(twoFingerSwipeLeftAction = it) },
                default = AppSettings.defaultTwoFingerSwipeLeftAction,
                override = { twoFingerSwipeLeftAction }
            )

            GestureSelectorRow(
                title = "Two Finger Swipe Right Action",
                kv = kv,
                settings = settings,
                update = { copy(twoFingerSwipeRightAction = it) },
                default = AppSettings.defaultTwoFingerSwipeRightAction,
                override = { twoFingerSwipeRightAction }
            )
        }
    }
}

