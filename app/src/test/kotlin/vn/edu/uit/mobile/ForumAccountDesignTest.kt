package vn.edu.uit.mobile

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*

/** Behavioral fixtures only. No synthetic Forum screenshot exports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForumAccountDesignTest {
    @get:Rule val compose = createComposeRule()
    private class Repo : ForumAccountRepository {
        val commands = mutableListOf<ForumCommand>()
        override suspend fun resume(): ForumUser? = null
        override suspend fun login(username: String, password: String, secondFactor: String): ForumUser {
            check(password == "fixture-password")
            return ForumUser(7, username, "Fixture", true, true)
        }
        override suspend fun disconnect() = Unit
        override suspend fun member(request: ForumMemberRequest) = when (request) {
            is ForumMemberRequest.Notices -> ForumAccountPage(notices = listOf(ForumNotice(7, "Fixture notice", 9, 1, false)))
            is ForumMemberRequest.Bookmarks -> ForumAccountPage(bookmarks = listOf(ForumBookmark(8, "Fixture bookmark", 9, 1)))
            ForumMemberRequest.Channels -> ForumAccountPage(channels = listOf(ForumChannel(3, "Fixture channel", true)))
            is ForumMemberRequest.Messages -> ForumAccountPage(messages = listOf(ForumChatMessage(2, 3, "Fixture", "", listOf(ForumBlock(ForumBlockKind.TEXT, "Fixture message")), threadId = 8)), thread = request.threadId?.let { ForumThread(it, 3, "Fixture thread", "open") })
            is ForumMemberRequest.Edit -> ForumAccountPage(editable = ForumEditable(2, 9, 1, "Existing **source**", 1))
        }
        override suspend fun command(command: ForumCommand): ForumCommandResult {
            commands += command
            return ForumCommandResult()
        }
        override suspend fun load(request: ForumRequest) = ForumLoaded(ForumDocument(categories = listOf(ForumCategory(1, "Allowed category", canCreate = true)), topics = listOf(ForumTopic(9, "Fixture topic", 1, 1, ""))), 1)
        override suspend fun local() = ForumLocalState()
        override suspend fun save(topic: ForumTopic, saved: Boolean) = ForumLocalState()
        override suspend fun position(position: ForumPosition) = Unit
        override suspend fun image(url: String) = byteArrayOf()
        override suspend fun clear() = Unit
    }
    private val repo = Repo()
    private val vm = ForumViewModel(repo)
    private val store = ViewModelStore().apply { put("forum", vm) }

    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
    }
    private fun show() {
        compose.setContent { UitTheme(dark = true, dynamic = false) { WorkspaceFrame(3, {}, {}, "", false, false, 0) { ForumScreen(vm) } } }
        compose.runOnIdle { vm.open() }
        compose.waitForIdle()
    }
    private fun login() {
        compose.runOnIdle { vm.login("fixture-user", "fixture-password", "") }
        compose.waitForIdle()
        assertNotNull(vm.uiState.value.user)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun nativeLoginAndNewTopicRequireExplicitSubmit() {
        show()
        compose.onNodeWithContentDescription("Forum account").performClick()
        compose.onNodeWithTag("forum-login-username").performTextInput("fixture-user")
        compose.onNodeWithTag("forum-login-password").performTextInput("fixture-password")
        compose.onAllNodesWithText("Sign in to Forum").onLast().performClick()
        compose.waitForIdle()
        assertNotNull(vm.uiState.value.user)
        compose.onNodeWithText("New topic").performClick()
        compose.onNodeWithText("Topic title").performTextInput("Fixture title")
        compose.onNodeWithText("Choose category").performClick()
        compose.onNodeWithTag("forum-category-1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("forum-composer-raw").performTextInput("Fixture draft body")
        assertTrue(repo.commands.isEmpty())
        compose.onNodeWithText("Publish").performClick()
        compose.waitForIdle()
        assertEquals(ForumCommand.Create("Fixture title", "Fixture draft body", 1), repo.commands.single())
    }

    @Test fun viewingNotificationsNeverMarksReadAndBookmarkRemovalRequiresAction() {
        show()
        login()
        compose.onNodeWithContentDescription("Forum account").performClick()
        compose.onNodeWithText("Fixture notice").assertExists()
        assertTrue(repo.commands.isEmpty())
        compose.onNodeWithText("Forum bookmarks").performClick()
        compose.onNodeWithText("Remove Forum bookmark").performClick()
        assertTrue(repo.commands.isEmpty())
        compose.onAllNodesWithText("Remove Forum bookmark").onLast().performClick()
        compose.waitForIdle()
        assertEquals(ForumCommand.RemoveBookmark(8), repo.commands.single())
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-mdpi")
    fun chatSendRequiresExplicitActionAndDisconnectClearsMemberUi() {
        show()
        login()
        compose.onNodeWithContentDescription("Forum account").performClick()
        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Fixture channel").performClick()
        compose.onNodeWithText("Message").performTextInput("Fixture outgoing text")
        assertTrue(repo.commands.isEmpty())
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        val sent = repo.commands.single() as ForumCommand.Chat
        assertEquals(3L, sent.channel.id)
        assertEquals("Fixture outgoing text", sent.text)
        compose.onNodeWithText("Disconnect here").performClick()
        compose.waitForIdle()
        assertNull(vm.uiState.value.user)
        compose.onNodeWithText("Fixture message").assertDoesNotExist()
    }

    @Test fun openingThreadKeepsItsDestinationWhenSendingAndReturning() {
        show()
        login()
        compose.onNodeWithContentDescription("Forum account").performClick()
        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Fixture channel").performClick()
        compose.onNodeWithText("Open thread").performClick()
        compose.onNodeWithText("Fixture thread").assertExists()
        compose.onNodeWithText("Message").performTextInput("Fixture thread reply")
        assertTrue(repo.commands.isEmpty())
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(8L, (repo.commands.single() as ForumCommand.Chat).threadId)
        assertEquals(8L, vm.uiState.value.threadId)
        compose.onNodeWithText("Back to channel").performClick()
        compose.waitForIdle()
        assertNull(vm.uiState.value.threadId)
    }
}
