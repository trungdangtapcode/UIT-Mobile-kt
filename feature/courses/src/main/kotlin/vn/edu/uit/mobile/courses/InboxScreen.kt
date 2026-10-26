package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class InboxState(
    val events: List<CalendarEvent> = emptyList(),
    val notifications: List<Message> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList(),
    val selected: Conversation? = null,
    val tab: Int = 0,
    val error: AppError? = null,
    val sending: Boolean = false
)
sealed interface InboxIntent {
    data class Tab(val index: Int) : InboxIntent
    data object Refresh : InboxIntent
    data class Read(val id: Long?) : InboxIntent
    data class Open(val conversation: Conversation) : InboxIntent
    data class Send(val text: String) : InboxIntent
    data object Back : InboxIntent
}
sealed interface InboxEffect {
    data class Notice(val text: String) : InboxEffect
    data class MessageSent(val conversationId: Long) : InboxEffect
}
class InboxViewModel(private val repository: PortalRepository) : BaseMviViewModel<InboxState, InboxIntent, InboxEffect>(InboxState()) {
    init {
        viewModelScope.launch { repository.snapshot.collect { state.value = state.value.copy(events = it.events.filter { e -> e.deadline }.sortedBy { e -> e.start }) } }
    }
    override fun onIntent(intent: InboxIntent) {
        if (intent is InboxIntent.Tab) state.value = state.value.copy(tab = intent.index, error = null)
        if (intent == InboxIntent.Back) {
            state.value = state.value.copy(selected = null, messages = emptyList())
            return
        }
        viewModelScope.launch {
            suspend fun <T> result(value: AppResult<T, AppError>, success: (T) -> Unit) {
                when (value) {
                    is AppResult.Success -> success(value.data)
                    is AppResult.Failure -> {
                        state.value = state.value.copy(error = value.error)
                        effects.send(InboxEffect.Notice(value.error.text()))
                    }
                }
            }
            when (intent) {
                is InboxIntent.Open -> {
                    state.value = state.value.copy(selected = intent.conversation, messages = emptyList(), error = null)
                    val messages = repository.messages(intent.conversation.id)
                    if (state.value.selected?.id == intent.conversation.id) result(messages) { state.value = state.value.copy(messages = it) }
                }
                is InboxIntent.Read -> {
                    result(repository.markRead(intent.id)) {}
                    result(repository.notifications()) { state.value = state.value.copy(notifications = it) }
                }
                is InboxIntent.Send -> {
                    val conversation = state.value.selected ?: return@launch
                    if (state.value.sending) return@launch
                    state.value = state.value.copy(sending = true)
                    result(repository.sendMessage(conversation.id, intent.text)) { effects.trySend(InboxEffect.MessageSent(conversation.id)) }
                    state.value = state.value.copy(sending = false)
                    val messages = repository.messages(conversation.id)
                    if (state.value.selected?.id == conversation.id) result(messages) { state.value = state.value.copy(messages = it) }
                }
                else -> when (state.value.tab) {
                    0 -> repository.refresh()
                    1 -> result(repository.notifications()) { state.value = state.value.copy(notifications = it) }
                    2 -> result(repository.conversations()) { state.value = state.value.copy(conversations = it) }
                }
            }
        }
    }
}

@Composable fun InboxScreen(viewModel: InboxViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var drafts by rememberSaveable { mutableStateOf(mapOf<Long, String>()) }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is InboxEffect.Notice -> snack.showSnackbar(effect.text)
                is InboxEffect.MessageSent -> {
                    drafts = drafts - effect.conversationId
                    snack.showSnackbar("Message sent")
                }
            }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(Modifier.padding(padding)) { InboxContent(state, viewModel::onIntent, draft = drafts[state.selected?.id].orEmpty(), onDraftChange = { text -> state.selected?.id?.let { drafts = drafts + (it to text) } }) }
    }
}

@Composable fun InboxContent(state: InboxState, onIntent: (InboxIntent) -> Unit = {}, now: Long = Clock.System.now().epochSeconds, draft: String = "", onDraftChange: (String) -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf("Upcoming") }
    BackHandler(enabled = state.tab == 2 && state.selected != null) { onIntent(InboxIntent.Back) }
    if (state.tab == 2 && state.selected != null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { ChatPanel(state, draft, onDraftChange, onIntent) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(6.dp))
            PageHeading("Deadlines & inbox", "Submission queue, notifications and course messages.") {
                IconButton(onClick = { onIntent(InboxIntent.Refresh) }) { Icon(Icons.Outlined.Refresh, "Refresh deadlines and inbox") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Deadlines" to Icons.Outlined.TaskAlt, "Notifications" to Icons.Outlined.NotificationsNone, "Messages" to Icons.Outlined.ChatBubbleOutline).forEachIndexed { index, (label, icon) ->
                    FilterChip(shape = TechnicalShapes.Control, selected = state.tab == index, onClick = { onIntent(InboxIntent.Tab(index)) }, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
                }
            }
            state.error?.let { InlineNotice(it.text(), true) }
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    0 -> {
                        val groups = mapOf("Upcoming" to state.events.filter { it.needsAction && it.start >= now }, "Submitted" to state.events.filter { it.submitted == true }, "Overdue" to state.events.filter { it.needsAction && it.start < now })
                        val events = groups[filter].orEmpty()
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            item {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { groups.forEach { (label, values) -> FilterChip(shape = TechnicalShapes.Control, selected = label == filter, onClick = { filter = label }, label = { Text("$label (${values.size})") }) } }
                                Spacer(Modifier.height(12.dp))
                            }
                            if (events.isEmpty()) item { EmptyState("Nothing in ${filter.lowercase()}", if (filter == "Submitted") "Only assignments with confirmed submission status appear here." else "No submissions in this category. Refresh to check for changes.") }
                            if (events.isNotEmpty()) item { Panel(Modifier.fillMaxWidth(), spacing = 0.dp) { events.forEach { event -> DeadlineCard(event, now) } } }
                        }
                    }
                    1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { SectionHeading("Your updates", "${state.notifications.count { !it.read }} unread notifications", "Mark all read") { onIntent(InboxIntent.Read(null)) } }
                        if (state.notifications.isEmpty()) item { Panel { EmptyState("You're up to date", "Your Moodle notifications will appear here.") } }
                        items(state.notifications, key = { it.id }) { notification ->
                            var expanded by rememberSaveable(notification.id) { mutableStateOf(false) }
                            Panel {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile(if (notification.read) Icons.Outlined.Drafts else Icons.Outlined.MarkEmailUnread, if (notification.read) MaterialTheme.colorScheme.onSurfaceVariant else WorkspaceColors.Ocean, 36.dp)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(notification.author.ifBlank { "Moodle notification" }, style = MaterialTheme.typography.titleSmall)
                                        Text(android.text.Html.fromHtml(notification.text, 0).toString(), style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                                        if (notification.created > 0) Text("${dateText(notification.created)} · ${clockText(notification.created)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!notification.read) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (notification.text.length > 180) TextButton(shape = TechnicalShapes.Control, onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read more") }
                                    if (!notification.read) TextButton(shape = TechnicalShapes.Control, onClick = { onIntent(InboxIntent.Read(notification.id)) }) { Text("Mark read") }
                                }
                            }
                        }
                    }
                    2 -> ConversationList(state, onIntent)
                }
            }
        }
    }
}

@Composable private fun ConversationList(state: InboxState, onIntent: (InboxIntent) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeading("Conversations", "${state.conversations.size} in your inbox") }
        item { SearchField(query, { query = it }, "Search conversations") }
        if (state.conversations.isEmpty()) item { Panel { EmptyState("Start with a conversation", "Your Moodle conversations will appear after refreshing your inbox.") } }
        items(state.conversations.filter { it.name.contains(query, true) }, key = { it.id }) { conversation ->
            val active = state.selected?.id == conversation.id
            Surface(
                onClick = { onIntent(InboxIntent.Open(conversation)) },
                shape = TechnicalShapes.Field,
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conversation.name, color = WorkspaceColors.course(conversation.id))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conversation.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (conversation.unread > 0) "${conversation.unread} unread messages" else "Open conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (conversation.unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("${conversation.unread}") }
                }
            }
        }
    }
}

@Composable private fun ChatPanel(state: InboxState, draft: String, onDraftChange: (String) -> Unit, onIntent: (InboxIntent) -> Unit) {
    Surface(Modifier.fillMaxSize(), shape = TechnicalShapes.Panel, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        if (state.selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState("Select a conversation", "Select a conversation to read and reply.") }
        } else {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = { onIntent(InboxIntent.Back) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to conversations") }
                    Avatar(state.selected.name)
                    Column {
                        Text(state.selected.name, style = MaterialTheme.typography.titleSmall)
                        Text("Moodle conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.messages.isEmpty()) item { EmptyState("No messages to show", "Refresh this conversation to load its messages.") }
                    items(state.messages, key = { it.id }) { message ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message.author.ifBlank { "Message" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = TechnicalShapes.Field, color = MaterialTheme.colorScheme.background) { Text(android.text.Html.fromHtml(message.text, 0).toString(), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
                            if (message.created > 0) Text("${dateText(message.created)} · ${clockText(message.created)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft,
                        onDraftChange,
                        Modifier.weight(1f),
                        placeholder = { Text("Write a message…") },
                        maxLines = 4,
                        shape = TechnicalShapes.Control,
                        supportingText = { if (draft.toByteArray().size > 3500) Text("${draft.toByteArray().size} / 4096 bytes") },
                        isError = draft.toByteArray().size > 4096
                    )
                    Button(onClick = { onIntent(InboxIntent.Send(draft)) }, shape = TechnicalShapes.Control, contentPadding = PaddingValues(0.dp), enabled = draft.isNotBlank() && draft.toByteArray().size <= 4096 && !state.sending, modifier = Modifier.padding(bottom = if (draft.toByteArray().size > 3500) 24.dp else 4.dp).size(48.dp)) {
                        if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                    }
                }
            }
        }
    }
}
