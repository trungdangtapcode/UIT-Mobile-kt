package vn.edu.uit.mobile.forum

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import vn.edu.uit.mobile.designsystem.TechnicalShapes
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

@Composable internal fun ForumMemberDialog(
    state: ForumState,
    onLoad: (ForumMemberTab, Boolean, ForumChannel?) -> Unit,
    onSubmit: (ForumCommand) -> Unit,
    onTopic: (Long, Int) -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    onLink: (String) -> Unit,
    imageLoader: suspend (String) -> ByteArray,
    onThread: (Long?) -> Unit
) {
    val ready = forumAccountReady(state)
    var remove by remember { mutableStateOf<ForumBookmark?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding(), shape = TechnicalShapes.Dialog) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.user?.username.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, enabled = !state.commandBusy) { Text(stringResource(R.string.f_close_account)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForumMemberTab.entries.filter { it != ForumMemberTab.CHAT || state.user?.canChat == true }.forEach { tab ->
                        FilterChip(selected = state.memberTab == tab, onClick = { onLoad(tab, false, null) }, label = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        ForumMemberTab.NOTICES -> R.string.f_notifications
                                        ForumMemberTab.BOOKMARKS -> R.string.f_account_bookmarks
                                        ForumMemberTab.CHAT -> R.string.f_chat
                                    }
                                )
                            )
                        }, shape = TechnicalShapes.Control, enabled = !state.commandBusy)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onLoad(state.memberTab, false, state.channel) }, enabled = ready && !state.accountBusy && !state.commandBusy) { Text(stringResource(R.string.f_refresh)) }
                    TextButton(onClick = onDisconnect, enabled = !state.commandBusy) { Text(stringResource(R.string.f_disconnect)) }
                }
                Text(stringResource(R.string.f_account_read_note), style = MaterialTheme.typography.labelSmall)
                if (state.accountBusy || state.commandBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ForumAccountError(state)
                state.channel?.let { channel ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(channel.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onLoad(ForumMemberTab.CHAT, false, null) }, enabled = !state.commandBusy) { Text(stringResource(R.string.f_channels)) }
                    }
                }
                state.threadId?.let { id ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.memberPage.thread?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.f_thread_title, id), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { onThread(null) }, enabled = !state.commandBusy) { Text(stringResource(R.string.f_back_to_channel)) }
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("forum-member-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val page = state.memberPage
                    when (state.memberTab) {
                        ForumMemberTab.NOTICES -> {
                            items(page.notices, key = { it.id }) { notice ->
                                Column(Modifier.fillMaxWidth().clickable(enabled = (notice.topicId != null || notice.source.isNotBlank()) && !state.commandBusy) { notice.topicId?.let { onTopic(it, notice.postNumber) } ?: onLink(notice.source) }.padding(vertical = 8.dp)) {
                                    Text(notice.title.ifBlank { stringResource(R.string.f_notification_kind, notice.type) }, style = MaterialTheme.typography.titleMedium)
                                    if (notice.created.isNotBlank()) Text(isoDate(notice.created), style = MaterialTheme.typography.labelSmall)
                                    if (notice.type == 12) Text(stringResource(R.string.f_badge_awarded), style = MaterialTheme.typography.labelSmall)
                                    Text(stringResource(if (notice.read) R.string.f_read else R.string.f_unread), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                HorizontalDivider()
                            }
                            if (page.notices.isEmpty() && !state.accountBusy) item { Text(stringResource(R.string.f_no_notifications)) }
                        }
                        ForumMemberTab.BOOKMARKS -> {
                            items(page.bookmarks, key = { it.id }) { bookmark ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    TextButton(onClick = { onTopic(bookmark.topicId, bookmark.postNumber) }, enabled = !state.commandBusy) { Text(bookmark.title, style = MaterialTheme.typography.titleMedium) }
                                    if (bookmark.reminder.isNotBlank()) Text(isoDate(bookmark.reminder), style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { remove = bookmark }, enabled = !state.commandBusy && !state.commandUncertain) { Text(stringResource(R.string.f_remove_bookmark)) }
                                }
                                HorizontalDivider()
                            }
                            if (page.bookmarks.isEmpty() && !state.accountBusy) item { Text(stringResource(R.string.f_no_bookmarks)) }
                        }
                        ForumMemberTab.CHAT -> {
                            if (state.channel == null) {
                                items(page.channels, key = { it.id }) { channel ->
                                    Column(Modifier.fillMaxWidth().clickable { onLoad(ForumMemberTab.CHAT, false, channel) }.padding(vertical = 12.dp)) {
                                        Text(channel.title, style = MaterialTheme.typography.titleMedium)
                                        if (channel.direct) Text(stringResource(R.string.f_direct_message), style = MaterialTheme.typography.labelSmall)
                                    }
                                    HorizontalDivider()
                                }
                                if (page.channels.isEmpty() && !state.accountBusy) item { Text(stringResource(R.string.f_no_channels)) }
                            } else {
                                if (page.more) item { OutlinedButton(onClick = { onLoad(ForumMemberTab.CHAT, true, state.channel) }, enabled = ready && !state.accountBusy && !state.commandBusy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_older_messages)) } }
                                items(page.messages, key = { it.id }) { message ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(message.author, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(isoDate(message.created), style = MaterialTheme.typography.labelSmall)
                                        message.blocks.forEach { ForumBlockView(it, onLink, imageLoader, "${ForumLimits.ORIGIN}/chat/c/-/${message.channelId}/${message.id}", onLink) }
                                        if (state.threadId == null) message.threadId?.let { id -> TextButton(onClick = { onThread(id) }, enabled = !state.commandBusy) { Text(stringResource(R.string.f_open_thread)) } }
                                    }
                                    HorizontalDivider()
                                }
                                if (page.messages.isEmpty() && !state.accountBusy) item { Text(stringResource(R.string.f_no_messages)) }
                            }
                        }
                    }
                    if (page.more && state.memberTab != ForumMemberTab.CHAT) item { OutlinedButton(onClick = { onLoad(state.memberTab, true, null) }, enabled = ready && !state.accountBusy && !state.commandBusy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_more)) } }
                }
                state.channel?.takeIf { it.canSend && (state.threadId == null || state.memberPage.thread?.status == "open") }?.let { channel ->
                    var message by remember(channel.id, state.threadId) { mutableStateOf("") }
                    var pending by remember(channel.id, state.threadId) { mutableStateOf(false) }
                    val clientId = remember(message) { UUID.randomUUID().toString() }
                    LaunchedEffect(state.commandBusy, state.accountError) {
                        if (pending && !state.commandBusy) {
                            if (state.accountError == null) message = ""
                            pending = false
                        }
                    }
                    OutlinedTextField(message, { message = it.take(64000) }, label = { Text(stringResource(R.string.f_chat_message)) }, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Control, enabled = !state.commandBusy)
                    Button(onClick = {
                        pending = true
                        onSubmit(ForumCommand.Chat(channel, message, clientId, state.threadId))
                    }, enabled = ready && message.isNotBlank() && !state.commandBusy && !state.commandUncertain && !state.accountBusy, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.f_send)) }
                }
            }
        }
    }
    remove?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(stringResource(R.string.f_remove_bookmark)) },
            text = { Text(bookmark.title) },
            shape = TechnicalShapes.Dialog,
            confirmButton = {
                TextButton(onClick = {
                    remove = null
                    onSubmit(ForumCommand.RemoveBookmark(bookmark.id))
                }) { Text(stringResource(R.string.f_remove_bookmark)) }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(R.string.f_cancel)) } }
        )
    }
}
