package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.*

/** Real account reads only. Authenticated captures are private ignored artifacts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ForumAccountLiveTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualNativeLoginAndReadOnlyAccountScreens() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.forum.account.live"))
        val username = requireNotNull(System.getenv("FORUM_TEST_USERNAME"))
        val password = requireNotNull(System.getenv("CPASSWORD"))
        val writes = mutableListOf<String>()
        val http = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 25000 }
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                }
            }
        }
        http.plugin(HttpSend).intercept { request ->
            check(request.url.host == "forum.uit.edu.vn")
            check(request.headers["Discourse-Present"] == null)
            check(request.url.parameters["track_visit"] == null)
            if (request.method != HttpMethod.Get) {
                check(request.method == HttpMethod.Post && request.url.encodedPath == "/session") { "Non-login write blocked by account research guard" }
                writes += request.url.encodedPath
            }
            execute(request)
        }
        fun memory() = object : StudyStorage {
            val data = mutableMapOf<String, String>()
            override fun read(key: String) = data[key]
            override fun write(key: String, value: String) {
                data[key] = value
            }
            override fun remove(key: String) {
                data.remove(key)
            }
            override fun removeAccount(accountId: String) = error("Unexpected portal account operation")
        }
        val client = ForumSessionClient(http)
        val vm = ForumViewModel(ConnectedForumRepository(LocalForumRepository(AnonymousForumClient(), memory()), client, memory()))
        val models = ViewModelStore().apply { put("forum", vm) }
        val output = File(requireNotNull(System.getProperty("uit.forum.account.dir"))).apply { mkdirs() }
        Files.setPosixFilePermissions(output.toPath(), PosixFilePermissions.fromString("rwx------"))
        fun settled() {
            compose.waitUntil(120000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                !vm.uiState.value.loading && !vm.uiState.value.accountBusy
            }
            assertNull("Native Forum read failed", vm.uiState.value.error)
            assertNull("Native Forum account operation failed", vm.uiState.value.accountError)
            println("Native account state settled; signed in: ${vm.uiState.value.user != null}")
            compose.waitForIdle()
        }
        fun capture(name: String) {
            compose.mainClock.advanceTimeBy(64)
            val file = File(output, "$name.png")
            captureScreenRoboImage(file.absolutePath)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
            compose.runOnIdle { vm.open() }
            settled()
            compose.runOnIdle { vm.account() }
            capture("forum-native-login")
            // Never put the real password into test action descriptions/semantics.
            compose.runOnUiThread { vm.login(username, password, "") }
            settled()
            assertEquals(username, vm.uiState.value.user?.username)
            assertTrue(vm.uiState.value.topics.isNotEmpty())
            capture("forum-native-authenticated-feed-private")
            if (vm.uiState.value.user?.canCreateTopic == true) {
                assertTrue("No permitted category for native composer", vm.uiState.value.categories.any { it.canCreate })
                compose.runOnIdle { vm.compose(create = true) }
                compose.onNodeWithTag("forum-composer-raw").assertExists()
                capture("forum-native-composer")
                compose.runOnIdle { vm.dismissComposer() }
            }
            compose.runOnIdle { vm.member(ForumMemberTab.NOTICES) }
            settled()
            assertTrue(vm.uiState.value.memberPage.notices.all { it.title.isNotBlank() || it.type > 0 })
            capture("forum-native-notifications-private")
            compose.runOnIdle { vm.member(ForumMemberTab.BOOKMARKS) }
            settled()
            capture("forum-native-bookmarks-private")
            compose.runOnIdle { vm.disconnect() }
            settled()
            assertNull(vm.uiState.value.user)
            assertTrue(vm.uiState.value.memberPage.notices.isEmpty())
            assertEquals(listOf("/session"), writes)
            assertEquals("[]", client.snapshot())
            File(output, "aggregate.txt").writeText("Native production Kotlin login verified on live UIT; notifications and bookmarks read; one login POST; all other writes blocked; local disconnect cleared credentials. Robolectric with memory storage, not installed-device/Keystore acceptance. Private captures are not publication evidence.\n")
        } finally {
            compose.runOnUiThread { models.clear() }
            client.clear()
            http.close()
        }
    }
}
