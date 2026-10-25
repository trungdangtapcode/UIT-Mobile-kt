package vn.edu.uit.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import vn.edu.uit.mobile.designsystem.UitTheme
import vn.edu.uit.mobile.designsystem.WorkspaceFrame

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UitTheme {
                WorkspaceFrame(0, {}, {}, "", false, false, 0) { Text("UIT Mobile") }
            }
        }
    }
}
