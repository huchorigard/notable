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

@Composable
fun PagePreview(modifier: Modifier, pageId: String) {
    val context = LocalContext.current
    var summary by remember(pageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pageId) {
        val db = AppDatabase.getDatabase(context)
        summary = db.pageSummaryDao().getSummary(pageId)?.summaryText
    }
    Box(modifier = modifier.background(Color.LightGray)) {
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