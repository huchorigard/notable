package com.ethran.notable.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.USER_INFO_KEY
import kotlinx.serialization.builtins.serializer

@Composable
fun UserInfoDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val kv = remember { KvProxy(context) }
    var userInfo by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    // Load user info once
    LaunchedEffect(Unit) {
        if (!loaded) {
            userInfo = kv.get(USER_INFO_KEY, String.serializer()) ?: ""
            loaded = true
        }
    }

    Dialog(onDismissRequest = onClose) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(24.dp)
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("User Information", color = Color.Black)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Examples: \n- What do you want to write about?\n- What would you like to accomplish through this app?\n- What motivates you to take notes?",
                    color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = userInfo,
                    onValueChange = { userInfo = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = { Text("Tell us about yourself...") },
                    maxLines = 20
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = onClose) { Text("Cancel") }
                    Button(onClick = {
                        kv.setKv(USER_INFO_KEY, userInfo, String.serializer())
                        onClose()
                    }) { Text("Save") }
                }
            }
        }
    }
} 