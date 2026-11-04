package vn.edu.uit.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

/** Production Compose/VM and real Android Keystore, with authored text and no university transport. */
@RunWith(AndroidJUnit4::class)
class KnowledgeNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun graphSavesEncryptedReopensOfflineAndClearsOnOwnerRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "knowledge-native-$suffix"
        val alias = "uit.knowledge.native.$suffix"
        val connected = MutableStateFlow(listOf(Account("owner", "https://example.invalid", 1, "Synthetic student", PortalType.MOODLE)))
        val owners = object : AccountRepository {
            override val accounts = connected
            override suspend fun remove(accountId: String) = error("University mutation forbidden")
            override suspend fun saveSessions(sessions: List<PortalSession>) = error("University mutation forbidden")
        }
        val snapshot = MutableStateFlow(PortalSnapshot(courses = listOf(KnowledgeFixtures.course)))
        val portal = Proxy.newProxyInstance(PortalRepository::class.java.classLoader, arrayOf(PortalRepository::class.java)) { _, method, _ -> if (method.name == "getSnapshot") snapshot else error("University operation forbidden") } as PortalRepository
        val calls = AtomicInteger()
        val ai = Proxy.newProxyInstance(StudyAiRepository::class.java.classLoader, arrayOf(StudyAiRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "connection" -> StudyConnection(configured = true, model = "synthetic-map-model")
                "knowledge" -> {
                    calls.incrementAndGet()
                    KnowledgeFixtures.generation
                }
                else -> error("Unexpected AI operation")
            }
        } as StudyAiRepository
        val storage = StudyPrivateStorage(context, name, alias)
        val library = LocalKnowledgeRepository(storage, owners)
        val vm = KnowledgeViewModel(ai, library, owners, portal, OfflineCourseSourceLoader { _, _, _ -> KnowledgeFixtures.sources })
        val models = ViewModelStore().apply { put("knowledge", vm) }
        try {
            compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "Synthetic student", true, false, 1793586000) { KnowledgeScreen(vm) {} } } }
            compose.waitUntil(15000) { vm.uiState.value.courses.isNotEmpty() }
            compose.runOnIdle { vm.open(KnowledgeFixtures.course) }
            compose.waitUntil(15000) { vm.uiState.value.loaded && !vm.uiState.value.busy }
            compose.runOnIdle {
                vm.select(KnowledgeFixtures.sources.single().id)
                vm.focus(KnowledgeFixtures.graph.focus)
            }
            compose.onNodeWithTag("knowledge-generate").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.uiState.value.record != null && !vm.uiState.value.busy }
            compose.onNodeWithTag("knowledge-neighbor-bfs").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.uiState.value.focused == "bfs" }
            compose.onNodeWithText("Inspect evidence").performScrollTo().performClick()
            compose.onNodeWithText("BFS uses a FIFO queue and visits one distance layer at a time.").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("knowledge-save").performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.uiState.value.dirty && !vm.uiState.value.pendingSave && !vm.uiState.value.busy }
            val saved = vm.uiState.value.record!!
            assertFalse(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.any { it.toString().contains("Graph traversal") || it.toString().contains(KnowledgeFixtures.TEXT) })
            val restored = runBlocking { LocalKnowledgeRepository(StudyPrivateStorage(context, name, alias), owners).collections.first().single().records.single() }
            assertEquals(saved, restored)
            compose.runOnIdle { vm.bank() }
            compose.waitUntil(15000) { !vm.uiState.value.busy && vm.uiState.value.collections.any { restored in it.records } }
            compose.runOnIdle {
                vm.show(restored)
                vm.choose("bfs")
            }
            compose.onNodeWithTag("knowledge-selected").performScrollTo().assertIsDisplayed()
            val destination = File(context.filesDir, "auth-smoke/knowledge-native-map.png").apply { parentFile!!.mkdirs() }
            destination.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(1, calls.get())
            compose.runOnIdle { connected.value = emptyList() }
            compose.waitUntil(15000) { vm.uiState.value.record == null && vm.uiState.value.sources.isEmpty() }
            storage.removeAccount("owner")
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
