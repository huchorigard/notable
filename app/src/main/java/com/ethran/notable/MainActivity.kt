package com.ethran.notable


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackBar
import com.ethran.notable.classes.SnackState
import com.ethran.notable.datastore.EditorSettingCacheManager
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.views.Router
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue


var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

var TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"


@ExperimentalAnimationApi
@ExperimentalComposeUiApi
@ExperimentalFoundationApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        requestPermissions()


        ShipBook.start(
            this.application, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )

        Log.i(TAG, "Notable started")


        if (SCREEN_WIDTH == 0) {
            SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
            SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
        }

        val snackState = SnackState()
        snackState.registerGlobalSnackObserver()
        snackState.registerCancelGlobalSnackObserver()

        // Refactor - we prob don't need this
        EditorSettingCacheManager.init(applicationContext)

        GlobalAppSettings.update(
            KvProxy(this).get(APP_SETTINGS_KEY, AppSettings.serializer())
                ?: AppSettings(version = 1)
        )

        //EpdDeviceManager.enterAnimationUpdate(true);

        val intentData = intent.data?.lastPathSegment
        setContent {
            InkaTheme {
                var showDownloadDialog by remember { mutableStateOf(false) }
                var downloadProgress by remember { mutableStateOf(0) }
                var isDownloading by remember { mutableStateOf(false) }
                var downloadFailed by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // Check for model presence at app start
                /* LaunchedEffect(Unit) {
                    if (!GemmaModelManager.isModelDownloaded(this@MainActivity)) {
                        showDownloadDialog = true
                    }
                } */

                if (showDownloadDialog) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Download AI Model") },
                        text = {
                            if (isDownloading) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Spacer(Modifier.height(16.dp))
                                    LinearProgressIndicator(progress = downloadProgress / 100f)
                                }
                            } else if (downloadFailed) {
                                Text("Download failed. Please check your connection and try again.")
                            } else {
                                Text("The AI model required for note summarization is not present. Download now? (~hundreds of MB)")
                            }
                        },
                        confirmButton = {
                            if (!isDownloading && !downloadFailed) {
                                Button(onClick = {
                                    isDownloading = true
                                    downloadFailed = false
                                    scope.launch(Dispatchers.IO) {
                                        // GemmaModelManager.downloadModel related code removed
                                    }
                                }) {
                                    Text("Yes, download")
                                }
                            } else if (downloadFailed) {
                                Button(onClick = {
                                    downloadFailed = false
                                }) {
                                    Text("Retry")
                                }
                            }
                        },
                        dismissButton = {
                            if (!isDownloading) {
                                Button(onClick = {
                                    showDownloadDialog = false
                                }) {
                                    Text("Download later")
                                }
                            }
                        }
                    )
                }

                // Main app UI (only shown if model is present or after download)
                if (!showDownloadDialog) {
                    CompositionLocalProvider(LocalSnackContext provides snackState) {
                        Box(
                            Modifier
                                .background(Color.White)
                        ) {
                            Router()
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.Black)
                        )
                        SnackBar(state = snackState)
                    }
                }
            }
        }
    }


    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            DrawCanvas.restartAfterConfChange.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")

            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            enableFullScreen()
            lifecycleScope.launch {
                if (DrawCanvas.wasDrawingBeforeFocusLost.value) {
                    DrawCanvas.refreshUi.emit(Unit)
                    DrawCanvas.isDrawing.emit(true)
                }
            }
        } else {
            lifecycleScope.launch {
                val currentDrawing = DrawCanvas.isDrawingState.value
                DrawCanvas.wasDrawingBeforeFocusLost.value = currentDrawing
                DrawCanvas.isDrawing.emit(false)
            }
        }
    }


    // when the screen orientation is changed, set new screen width  restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
//        this.lifecycleScope.launch {
//            DrawCanvas.restartAfterConfChange.emit(Unit)
//        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        } else if (!Environment.isExternalStorageManager()) {
            requestManageAllFilesPermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.fromParts("package", packageName, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    // written by GPT, but it works
    // needs to be checked if it is ok approach.
    private fun enableFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 and above
            // 'setDecorFitsSystemWindows(Boolean): Unit' is deprecated. Deprecated in Java
//            window.setDecorFitsSystemWindows(false)
            WindowCompat.setDecorFitsSystemWindows(window, false)
//            if (window.insetsController != null) {
//                window.insetsController!!.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
//                window.insetsController!!.systemBarsBehavior =
//                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//            }
            // Safely access the WindowInsetsController
            val controller = window.decorView.windowInsetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                Log.e(TAG, "WindowInsetsController is null")
            }
        } else {
            // For Android 10 and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

}