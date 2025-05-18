import androidx.compose.runtime.Composable
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.foundation.layout.Column

@Composable
fun AppSettingsModal(onClose: () -> Unit, onUserInfo: () -> Unit = {}) {
    Column(
        // ... existing code ...
    ) {
        // ... existing code ...
        Button(onClick = { onUserInfo() }) {
            Text("User Information")
        }
        // ... existing code ...
    }
    // ... existing code ...
} 