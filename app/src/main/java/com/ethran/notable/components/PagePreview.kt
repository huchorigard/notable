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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.ethran.notable.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

@Composable
fun PagePreview(modifier: Modifier, pageId: String) {
    val context = LocalContext.current
    var summary by remember(pageId) { mutableStateOf<String?>(null) }
    var updatedAt by remember(pageId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(pageId) {
        val db = AppDatabase.getDatabase(context)
        summary = db.pageSummaryDao().getSummary(pageId)?.summaryText
        val page = db.pageDao().getById(pageId)
        updatedAt = page?.updatedAt?.time
    }
    Box(modifier = modifier.background(Color.Transparent)) {
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
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
        if (summary != null) {
            Text(
                text = summary!!,
                modifier = Modifier.padding(8.dp),
                color = Color.Black,
                maxLines = 8 // limit to a few lines for preview
            )
        } else {
            Text(
                text = "No summary available",
                modifier = Modifier.padding(8.dp),
                color = Color.Gray
            )
        }
    }
}