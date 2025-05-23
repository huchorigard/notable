package com.ethran.notable.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.ethran.notable.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.livedata.observeAsState
import android.util.Log

private const val TAG = "PagePreview"

@Composable
fun PagePreview(modifier: Modifier, pageId: String) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val summaryLive = remember(pageId) { db.pageSummaryDao().getSummaryLive(pageId) }
    val summary = summaryLive.observeAsState().value?.summaryText
    
    // Get tags for the page using LiveData
    val tagsLive = remember(pageId) { db.tagDao().getTagsForPageLive(pageId) }
    val tags = tagsLive.observeAsState().value
    Log.d(TAG, "Tags for page $pageId: ${tags?.map { it.name }}")
    
    val tagsText = if (!tags.isNullOrEmpty()) {
        tags.joinToString(", ") { it.name }
    } else null
    
    var updatedAt by remember(pageId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(pageId) {
        val page = db.pageDao().getById(pageId)
        updatedAt = page?.updatedAt?.time
    }
    
    Box(modifier = modifier.background(Color.Transparent)) {
        Column {
            // Date at top
            if (updatedAt != null) {
                val now = System.currentTimeMillis()
                val millisInDay = 24 * 60 * 60 * 1000
                val daysAgo = ((now - updatedAt!!) / millisInDay).toInt()
                val dateString = remember(updatedAt) {
                    when {
                        daysAgo < 1 -> "Today"
                        daysAgo < 10 -> "${daysAgo}d ago"
                        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(updatedAt!!))
                    }
                }
                Text(
                    text = dateString,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(8.dp),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            
            // Summary in the middle
            Box(modifier = Modifier.weight(1f)) {
                if (summary != null) {
                    Text(
                        text = summary,
                        modifier = Modifier.padding(8.dp),
                        color = Color.Black,
                        maxLines = 6 // Reduced from 8 to make room for tags
                    )
                } else {
                    Text(
                        text = "Summarizing...",
                        modifier = Modifier.padding(8.dp),
                        color = Color.Gray
                    )
                }
            }
            
            // Tags at bottom
            if (!tagsText.isNullOrEmpty()) {
                Log.d(TAG, "Displaying tags for page $pageId: $tagsText")
                Text(
                    text = tagsText,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            } else {
                Log.d(TAG, "No tags to display for page $pageId")
            }
        }
    }
}