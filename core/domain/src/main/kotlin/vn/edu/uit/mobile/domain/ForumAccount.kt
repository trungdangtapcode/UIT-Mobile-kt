package vn.edu.uit.mobile.domain

/** Forum sessions are independent of Moodle/Portal, even when credentials match. */
data class ForumUser(val id: Long, val username: String, val name: String, val canCreateTopic: Boolean = false, val canChat: Boolean = false)
data class ForumNotice(val id: Long, val title: String, val topicId: Long?, val postNumber: Int, val read: Boolean, val type: Int = 0, val created: String = "", val source: String = "")
data class ForumBookmark(val id: Long, val title: String, val topicId: Long, val postNumber: Int, val reminder: String = "")
data class ForumChannel(val id: Long, val title: String, val canSend: Boolean, val direct: Boolean = false)
data class ForumThread(val id: Long, val channelId: Long, val title: String, val status: String)
data class ForumChatMessage(val id: Long, val channelId: Long, val author: String, val created: String, val blocks: List<ForumBlock>, val threadId: Long? = null)
data class ForumEditable(val postId: Long, val topicId: Long, val number: Int, val raw: String, val version: Int)
data class ForumPollOption(val id: String, val blocks: List<ForumBlock>, val votes: Int? = null)
data class ForumPoll(val name: String, val type: String, val status: String, val options: List<ForumPollOption>, val votes: List<String>, val voters: Int?, val min: Int = 1, val max: Int = 1, val results: String = "always")
data class ForumReaction(val id: String, val count: Int, val selected: Boolean = false, val canUndo: Boolean = false)
data class ForumAccountPage(
    val notices: List<ForumNotice> = emptyList(),
    val bookmarks: List<ForumBookmark> = emptyList(),
    val channels: List<ForumChannel> = emptyList(),
    val messages: List<ForumChatMessage> = emptyList(),
    val editable: ForumEditable? = null,
    val thread: ForumThread? = null,
    val more: Boolean = false
)
sealed interface ForumMemberRequest {
    data class Notices(val offset: Int = 0) : ForumMemberRequest
    data class Bookmarks(val page: Int = 0) : ForumMemberRequest
    data object Channels : ForumMemberRequest
    data class Messages(val channel: ForumChannel, val before: Long? = null, val threadId: Long? = null) : ForumMemberRequest
    data class Edit(val postId: Long) : ForumMemberRequest
}
sealed interface ForumCommand {
    data class Create(val title: String, val raw: String, val categoryId: Long) : ForumCommand
    data class Reply(val topicId: Long, val raw: String, val parent: Int?) : ForumCommand
    data class Edit(val source: ForumEditable, val raw: String, val reason: String) : ForumCommand
    data class Bookmark(val postId: Long) : ForumCommand
    data class RemoveBookmark(val id: Long) : ForumCommand
    data class Reaction(val postId: Long, val reaction: String) : ForumCommand
    data class Like(val postId: Long, val remove: Boolean = false) : ForumCommand
    data class Vote(val postId: Long, val name: String, val options: List<String>) : ForumCommand
    data class Chat(val channel: ForumChannel, val text: String, val clientId: String, val threadId: Long? = null) : ForumCommand
}
data class ForumCommandResult(val topicId: Long? = null, val postNumber: Int = 1)
interface ForumAccountRepository : ForumRepository {
    suspend fun resume(): ForumUser?
    suspend fun login(username: String, password: String, secondFactor: String = ""): ForumUser
    suspend fun disconnect()
    suspend fun member(request: ForumMemberRequest): ForumAccountPage
    suspend fun command(command: ForumCommand): ForumCommandResult
}
