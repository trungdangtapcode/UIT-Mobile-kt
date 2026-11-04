package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

/** Replay actual captured source and production rankings; no synthetic course screenshot content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class PassageRetrievalLiveDesignTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    private class Memory(private val raw: String) : StudyStorage {
        override fun read(key: String): String? = if (key == "owners") "[\"read-only-material-capture\"]" else raw
        override fun write(key: String, value: String) = Unit
        override fun remove(key: String) = error("Replay never deletes")
        override fun removeAccount(accountId: String) = error("Replay never mutates accounts")
    }
    private fun input(): File {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("uit.retrieval.preview"))
        val input = File(requireNotNull(System.getProperty("uit.retrieval.input")))
        Assume.assumeTrue("Run actual material retrieval evaluation first", File(input, "report.json").isFile && File(input, "index.json").isFile)
        return input
    }
    private fun state(queryId: String, localOnly: Boolean = false): PassageSearchState = runBlocking {
        val input = input()
        // Restore the real index through the public repository, keeping the codec and scope checks in production.
        val report = Json.parseToJsonElement(File(input, "report.json").readText()).jsonObject
        val raw = File(input, "sync-index.json").takeIf { it.isFile }?.readText() ?: File(input, "index.json").readText()
        val root = Json.parseToJsonElement(raw).jsonObject
        val owner = root.getValue("owner").jsonPrimitive.content
        val courseId = root.getValue("course").jsonPrimitive.long
        val first = root.getValue("documents").jsonArray.first().jsonObject
        val course = vn.edu.uit.mobile.model.Course(courseId, owner, first.getValue("shortname").jsonPrimitive.content, first.getValue("fullname").jsonPrimitive.content)
        val owners = object : AccountRepository {
            override val accounts = kotlinx.coroutines.flow.MutableStateFlow(listOf(vn.edu.uit.mobile.model.Account(owner, "https://courses.uit.edu.vn", 1, "Anonymized capture owner", vn.edu.uit.mobile.model.PortalType.MOODLE)))
            override suspend fun remove(accountId: String) = error("No account mutation")
            override suspend fun saveSessions(sessions: List<vn.edu.uit.mobile.model.PortalSession>) = error("No university session")
        }
        val downloads = PrivateOfflineDownloadStorage(File(input, "verified-materials"), Memory(""))
        val section = first.getValue("section").jsonPrimitive.content.removePrefix("section:").toLong()
        val file = vn.edu.uit.mobile.model.CourseFile(first.getValue("name").jsonPrimitive.content, first.getValue("url").jsonPrimitive.content, first.getValue("bytes").jsonPrimitive.long)
        val portal = FakePortalRepository().apply {
            snapshot.value = vn.edu.uit.mobile.model.PortalSnapshot(courses = listOf(course), sections = mapOf("$owner:$courseId" to listOf(vn.edu.uit.mobile.model.CourseSection(section, first.getValue("sectionName").jsonPrimitive.content, listOf(vn.edu.uit.mobile.model.CourseModule(1, courseId, file.name, "resource", "", files = listOf(file)))))))
        }
        val repository = LocalPassageSearchRepository(Memory(raw), owners, portal.snapshot, createPassageDocumentLoader(downloads), downloads, object : StudyAiRepository by ConnectedStudyAiRepository(Memory(""), owners) {})
        try {
            repository.initialize()
            if (localOnly) repository.build(course, setOf(vn.edu.uit.mobile.domain.offlineDownloadId(course, file)), PassageConfig(), null)
            val index = repository.library.value.indexes.single()
            check(index.documents.single().version == report.getValue("sourceHash").jsonPrimitive.content)
            val replay = Json.parseToJsonElement(File(input, "replay.json").readText()).jsonObject.getValue(queryId).jsonObject
            val ids = replay.getValue("passageIds").jsonArray.map { it.jsonPrimitive.content }
            val hits = ids.map { id ->
                val passage = index.passages.first { it.id == id }
                PassageHit(passage, index.documents.first { it.spec.id == passage.documentId }, 0.0)
            }
            PassageSearchState(courses = listOf(course), course = course, candidates = index.documents.map { PassageCandidate(it.spec, true) }, selected = index.documents.map { it.spec.id }.toSet(), query = replay.getValue("query").jsonPrimitive.content, mode = if (localOnly) PassageSearchMode.BM25 else PassageSearchMode.HYBRID, library = repository.library.value, result = if (localOnly) null else PassageSearchResult(hits, PassageSearchMode.HYBRID, PassageSearchMode.HYBRID), plan = repository.check(course, index.documents.map { it.spec.id }.toSet(), index.config, if (localOnly) PassageEmbeddingConfig() else index.embedding))
        } finally {
            repository.close()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File(requireNotNull(System.getProperty("uit.retrieval.output"))).apply { mkdirs() }
        captureScreenRoboImage(File(output, "$name.png").absolutePath)
    }
    private fun phone(width: Int, large: Boolean = false, dark: Boolean = true) {
        val initial = state("hidden-phase-vietnamese")
        if (large) RuntimeEnvironment.setFontScale(1.3f)
        val suffix = "$width${if (large) "-large-text" else ""}${if (!dark) "-light" else ""}"
        compose.setContent { UitTheme(dark = dark, dynamic = false) { WorkspaceFrame(2, {}, {}, "", false, false, 0) { PassageSearchContent(initial) } } }
        compose.onNodeWithTag("passage-search").performScrollToIndex(0)
        capture("retrieval-scope-$suffix")
        compose.onNodeWithTag("passage-search").performScrollToIndex(4)
        capture("retrieval-results-$suffix")
        compose.onNodeWithTag("passage-search").performScrollToNode(hasTestTag("passage-manage"))
        compose.onNodeWithTag("passage-manage").performClick()
        capture("updates-files-$suffix")
        compose.onNodeWithTag("passage-build").performClick()
        capture("updates-review-$suffix")
        if (width == 390 && dark) {
            compose.onNodeWithText("Hủy").performClick()
            compose.onNodeWithContentDescription("Chi tiết tìm kiếm").performClick()
            capture("updates-details-390")
            compose.onNodeWithText("Đóng").assertIsDisplayed().performClick()
        }
    }

    @Test fun actualResults390() = phone(390)

    @Test
    @Config(qualifiers = "vi-rVN-w360dp-h800dp-mdpi")
    fun actualResults360() = phone(360)

    @Test
    @Config(qualifiers = "vi-rVN-w412dp-h915dp-mdpi")
    fun actualResults412LargeText() = phone(412, true)

    @Test fun actualResults390Light() = phone(390, dark = false)

    @Test fun actualSelectionAndSource390() = runBlocking {
        val initial = state("hidden-phase-vietnamese")
        val hit = initial.result!!.hits.first()
        val storage = PrivateOfflineDownloadStorage(File(input(), "verified-materials"), Memory(""))
        val document = createPassageDocumentLoader(storage).load(hit.document.spec)
        val part = document.parts.first { it.ordinal == hit.passage.ordinal }
        check(part.text.substring(hit.passage.start, hit.passage.end) == hit.passage.text)
        var inspect by mutableStateOf<PassageInspection?>(null)
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "", false, false, 0) { PassageSearchContent(initial.copy(inspection = inspect), onInspect = { inspect = PassageInspection(it, part) }, onDismissInspection = { inspect = null }) } } }
        compose.onNodeWithTag("passage-search").performScrollToNode(hasTestTag("passage-inspect-${hit.passage.id}"))
        compose.onNodeWithTag("passage-inspect-${hit.passage.id}").performClick()
        capture("retrieval-source-390")
        compose.onNodeWithText("Đóng").performClick()
        compose.onNodeWithTag("passage-search").performScrollToNode(hasTestTag("passage-manage"))
        compose.onNodeWithTag("passage-manage").performClick()
        capture("retrieval-selection-390")
    }

    @Test fun actualNewTransferReview390() {
        val initial = state("hidden-phase-vietnamese", localOnly = true)
        check(initial.plan!!.transfers > 0 && initial.plan!!.reused == 0)
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "", false, false, 0) { PassageSearchContent(initial) } } }
        compose.onNodeWithTag("passage-manage").performScrollTo().performClick()
        compose.onNodeWithTag("passage-enable-semantic").performScrollTo().performClick()
        compose.onNodeWithTag("passage-build").performClick()
        compose.onNodeWithTag("passage-confirm-update").assertIsNotEnabled()
        capture("updates-new-transfer-390")
    }

    @Test fun actualIdentifierResult390() {
        val initial = state("identifier-success")
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(2, {}, {}, "", false, false, 0) { PassageSearchContent(initial) } } }
        compose.onNodeWithTag("passage-search").performScrollToIndex(4)
        capture("retrieval-code-390")
    }
}
