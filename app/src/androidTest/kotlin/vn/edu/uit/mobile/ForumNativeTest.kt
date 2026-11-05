package vn.edu.uit.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.forum.network.ForumTransport

/** Real Compose, parser, cache and Android Keystore; no university client or credentials. */
@RunWith(AndroidJUnit4::class)
class ForumNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun publicReaderSavesReopensOfflineAndClearsEncryptedLocalData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "forum-native-$suffix"
        val alias = "uit.forum.native.$suffix"
        val imageCalls = AtomicInteger()
        val picture = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(17, 28, 40)) }
        val ink = Paint().apply {
            color = android.graphics.Color.rgb(101, 190, 255)
            strokeWidth = 3f
        }
        Canvas(picture).apply {
            drawLine(40f, 48f, 200f, 48f, ink)
            listOf(40f, 120f, 200f).forEach { drawCircle(it, 48f, 16f, ink) }
        }
        val png = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        picture.recycle()
        val offline = AtomicBoolean(false)
        val requests = mutableListOf<ForumRequest>()
        val remote = object : ForumTransport {
            override suspend fun get(request: ForumRequest): String {
                synchronized(requests) { requests += request }
                if (offline.get()) throw ForumException(ForumFailure.NETWORK)
                return ForumFixtures.response(request)
            }
            override suspend fun image(url: String): ByteArray {
                check(ForumUrls.image(url) != null)
                imageCalls.incrementAndGet()
                return png
            }
        }
        val repository = LocalForumRepository(remote, StudyPrivateStorage(context, name, alias)) { ForumFixtures.AT }
        val vm = ForumViewModel(repository)
        val models = ViewModelStore().apply { put("forum", vm) }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
            compose.runOnIdle { vm.open() }
            compose.waitUntil(15000) { !vm.uiState.value.loading && vm.uiState.value.topics.isNotEmpty() }
            compose.onNodeWithText(ForumFixtures.TITLE).performClick()
            compose.waitUntil(15000) { !vm.uiState.value.loading && vm.uiState.value.posts.isNotEmpty() }
            compose.onNodeWithContentDescription("Save on device").performClick()
            compose.waitUntil(15000) { vm.uiState.value.local.saved.size == 1 && !vm.uiState.value.saving }
            compose.onNodeWithTag("forum-reader").performScrollToNode(hasText("Prepare your workspace"))
            compose.runOnIdle { vm.position(vm.uiState.value.posts.first(), 16) }
            compose.waitUntil(15000) { vm.uiState.value.local.positions.isNotEmpty() }
            val restored = runBlocking { LocalForumRepository(remote, StudyPrivateStorage(context, name, alias)).local() }
            assertEquals(901L, restored.saved.single().id)
            assertEquals(1, restored.positions.single().postNumber)
            assertFalse(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.any { it.toString().contains(ForumFixtures.TITLE) || it.toString().contains("lab_helper") })
            compose.onNodeWithTag("forum-reader").performScrollToNode(hasTestTag("forum-image-preview"))
            compose.onAllNodesWithTag("forum-image-preview").onFirst().performScrollTo()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Authored lab diagram").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Authored lab diagram").performScrollTo().assertIsDisplayed()
            assertEquals(1, imageCalls.get())
            offline.set(true)
            compose.runOnIdle { vm.topic(901) }
            compose.waitUntil(15000) { !vm.uiState.value.loading && vm.uiState.value.offline }
            compose.onNodeWithTag("forum-reader").performScrollToNode(hasText("Prepare your workspace"))
            compose.onNodeWithContentDescription("Authored lab diagram").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                vm.back()
                vm.tab(ForumTab.SAVED)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("forum-clear").performScrollTo().performClick()
            compose.onNode(hasText("Clear Forum data") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
            compose.waitUntil(15000) { vm.uiState.value.local.saved.isEmpty() && !vm.uiState.value.loading }
            assertTrue(runBlocking { repository.local() }.positions.isEmpty())
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(synchronized(requests) { requests.all { it is ForumRequest.Categories || it is ForumRequest.Latest || it is ForumRequest.Topic } })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
