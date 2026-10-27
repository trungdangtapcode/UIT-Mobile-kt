package vn.edu.uit.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.authui.*
import vn.edu.uit.mobile.designsystem.UitTheme
import vn.edu.uit.mobile.model.ProfileDocument

/** Native gestures and Android Back, using only an in-memory synthetic image. */
@RunWith(AndroidJUnit4::class)
class ProfileScanViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeScanDialogSupportsPinchDoubleTapAndAndroidBack() {
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "SYNTHETIC SCAN TEST",
                40f,
                100f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLUE
                    textSize = 30f
                }
            )
        }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            UitTheme {
                Button(onClick = { open = true }) { Text("Open test scan") }
                if (open) ProfileScanViewer(ProfileScanState(ProfileDocument("test", "Test transcript", true), bytes, loading = false), onClose = { open = false }, onRetry = {})
            }
        }
        compose.onNodeWithText("Open test scan").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Scan image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Zoom in scan").performClick()
        compose.onNodeWithText("150%").assertIsDisplayed()
        compose.onNodeWithText("Fit", substring = false).performClick()
        compose.onNodeWithTag("profile-scan-image").performTouchInput {
            pinch(center - Offset(40f, 0f), center + Offset(40f, 0f), center - Offset(120f, 0f), center + Offset(120f, 0f))
        }
        compose.onNodeWithContentDescription("Zoom out scan").assertIsEnabled()
        compose.onNodeWithText("Fit", substring = false).performClick()
        compose.onNodeWithTag("profile-scan-image").performTouchInput { doubleClick(center) }
        compose.onNodeWithText("300%").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("profile-scan-viewer").assertDoesNotExist()
        compose.onNodeWithText("Open test scan").assertIsDisplayed()
    }
}
