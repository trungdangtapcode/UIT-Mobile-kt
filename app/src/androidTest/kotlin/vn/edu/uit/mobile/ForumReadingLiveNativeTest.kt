package vn.edu.uit.mobile

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.*

/** Explicit opt-in installed Android check. Public production GETs only; no account or credentials. */
@RunWith(AndroidJUnit4::class)
class ForumReadingLiveNativeTest {
    @get:Rule val compose = createComposeRule()
    private class Memory : StudyStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun removeAccount(accountId: String) = error("No account mutation")
    }

    @Test fun actualPublicIcpcAndInlineImageOnAndroid() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("forumPublicLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "forum-reading-native").apply { mkdirs() }
        val requests = mutableListOf<String>()
        val http = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 25000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 20000
            }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
        http.plugin(HttpSend).intercept { request ->
            check(request.method == HttpMethod.Get && request.url.host == "forum.uit.edu.vn")
            check(request.headers["Cookie"] == null && request.headers["Authorization"] == null)
            synchronized(requests) { requests += request.url.encodedPath }
            execute(request)
        }
        val vm = ForumViewModel(ConnectedForumRepository(LocalForumRepository(AnonymousForumClient(http), Memory()), ForumSessionClient(http), Memory()))
        val models = ViewModelStore().apply { put("forum", vm) }
        fun settled() {
            compose.waitUntil(90000) { !vm.uiState.value.loading }
            compose.waitForIdle()
        }
        fun evidence(name: String) {
            val state = vm.uiState.value
            File(output, "$name.json").writeText(
                buildJsonObject {
                    put("capturedAt", java.time.Instant.now().toString())
                    put("runtime", "Installed native Android Compose/OkHttp; public reads; in-memory repository storage")
                    put("sdk", Build.VERSION.SDK_INT)
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("topicId", state.topic?.id)
                    put("success", state.error == null && state.posts.isNotEmpty())
                    put("failure", state.error?.name ?: "NONE")
                    put("networkIssue", state.networkIssue?.name ?: "NONE")
                    put("postNumbers", JsonArray(state.posts.map { JsonPrimitive(it.number) }))
                    put("writes", 0)
                    put("credentialsUsed", false)
                    put("requests", JsonArray(synchronized(requests) { requests.toList() }.map(::JsonPrimitive)))
                }.toString()
            )
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
            compose.runOnIdle { vm.topic(163707) }
            settled()
            evidence("icpc")
            assertNull("Actual Android ICPC read failed; inspect the native evidence JSON", vm.uiState.value.error)
            assertEquals(163707L, vm.uiState.value.topic?.id)
            assertTrue(vm.uiState.value.posts.isNotEmpty())
            compose.runOnIdle { vm.topic(164929) }
            settled()
            evidence("announcement")
            assertNull(vm.uiState.value.error)
            compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-image-preview"))
            compose.onAllNodesWithTag("forum-image-preview").onFirst().performScrollTo()
            compose.waitUntil(60000) { compose.onAllNodesWithTag("forum-image-open").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("forum-image-open").onFirst().performScrollTo()
            compose.onNodeWithText("Load image").assertDoesNotExist()
            evidence("inline-image")
            compose.onAllNodesWithTag("forum-image-open").onFirst().performClick()
            compose.onNodeWithTag("forum-image-viewer").assertIsDisplayed()
            compose.waitUntil(60000) { compose.onAllNodesWithTag("forum-image-loading").fetchSemanticsNodes().isEmpty() }
            evidence("image-viewer")
        } finally {
            compose.runOnIdle {
                vm.close()
                models.clear()
            }
            http.close()
        }
    }
}
