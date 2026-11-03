package vn.edu.uit.mobile.forum

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import vn.edu.uit.mobile.designsystem.TechnicalShapes
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

class ForumAccountActions(val account: () -> Unit, val create: () -> Unit, val reply: (Int) -> Unit, val edit: (ForumPost) -> Unit, val submit: (ForumCommand) -> Unit)

@Composable internal fun forumAccountReady(state: ForumState): Boolean {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.accountRetryAt) {
        now = System.currentTimeMillis() / 1000
        while (now < state.accountRetryAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now >= state.accountRetryAt
}

@Composable internal fun ForumAccountError(state: ForumState) {
    if (!forumAccountReady(state)) Text(stringResource(R.string.f_wait, (state.accountRetryAt - System.currentTimeMillis() / 1000).coerceAtLeast(1)), style = MaterialTheme.typography.labelSmall)
    state.accountError?.let {
        Text(stringResource(errorLabel(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (state.accountDetail.isNotBlank()) Text(state.accountDetail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun ForumLoginDialog(state: ForumState, onLogin: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    val ready = forumAccountReady(state)
    // Passwords deliberately never enter SavedState, ViewModel state or disk.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding(), shape = TechnicalShapes.Dialog) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.f_sign_in), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.f_login_note))
                    OutlinedTextField(username, { username = it.take(200) }, label = { Text(stringResource(R.string.f_username)) }, singleLine = true, enabled = !state.accountBusy, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth().testTag("forum-login-username"))
                    OutlinedTextField(password, { password = it.take(1000) }, label = { Text(stringResource(R.string.f_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.accountBusy, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth().testTag("forum-login-password"))
                    if (state.accountError == ForumFailure.SECOND_FACTOR || code.isNotEmpty()) OutlinedTextField(code, { code = it.take(100) }, label = { Text(stringResource(R.string.f_second_factor)) }, singleLine = true, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth())
                    ForumAccountError(state)
                }
                if (state.accountBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        password = ""
                        onDismiss()
                    }, enabled = !state.accountBusy) { Text(stringResource(R.string.f_cancel)) }
                    Button(onClick = {
                        val value = password
                        password = ""
                        onLogin(username, value, code)
                    }, enabled = ready && username.isNotBlank() && password.isNotEmpty() && !state.accountBusy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_sign_in)) }
                }
            }
        }
    }
}

@Composable internal fun ForumComposerDialog(state: ForumState, onSubmit: (ForumCommand) -> Unit, onDismiss: () -> Unit) {
    val composer = state.composer ?: return
    val ready = forumAccountReady(state)
    var raw by remember(composer) { mutableStateOf(composer.edit?.raw.orEmpty()) }
    var title by remember(composer) { mutableStateOf("") }
    var reason by remember(composer) { mutableStateOf("") }
    var category by remember(composer) { mutableStateOf<ForumCategory?>(null) }
    val creating = composer.topicId == null && composer.edit == null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding(), shape = TechnicalShapes.Dialog) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (composer.edit != null) {
                            R.string.f_edit_post
                        } else if (creating) {
                            R.string.f_new_topic
                        } else {
                            R.string.f_reply
                        }
                    ),
                    style = MaterialTheme.typography.titleLarge
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (creating) {
                        OutlinedTextField(title, { title = it.take(1000) }, label = { Text(stringResource(R.string.f_topic_title)) }, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth())
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, shape = TechnicalShapes.Control) { Text(category?.name ?: stringResource(R.string.f_choose_category)) }
                            DropdownMenu(expanded, { expanded = false }) {
                                state.categories.filter { it.canCreate }.forEach { c ->
                                    DropdownMenuItem(modifier = Modifier.testTag("forum-category-${c.id}"), text = { Text(c.name) }, onClick = {
                                        category = c
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }
                    OutlinedTextField(raw, { raw = it.take(64000) }, label = { Text(stringResource(R.string.f_markdown)) }, minLines = 10, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth().testTag("forum-composer-raw"), enabled = !state.commandBusy)
                    if (composer.edit != null) OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text(stringResource(R.string.f_edit_reason)) }, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.f_submit_note), style = MaterialTheme.typography.bodySmall)
                    ForumAccountError(state)
                }
                if (state.commandBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !state.commandBusy) { Text(stringResource(R.string.f_cancel)) }
                    Button(onClick = {
                        onSubmit(composer.edit?.let { ForumCommand.Edit(it, raw, reason) } ?: composer.topicId?.let { ForumCommand.Reply(it, raw, composer.parent) } ?: ForumCommand.Create(title, raw, requireNotNull(category).id))
                    }, enabled = ready && raw.isNotBlank() && (!creating || title.isNotBlank() && category != null) && !state.commandBusy && !state.commandUncertain, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_publish)) }
                }
            }
        }
    }
}

@Composable internal fun ForumPostActions(state: ForumState, post: ForumPost, actions: ForumAccountActions, onLink: (String) -> Unit, image: suspend (String) -> ByteArray) {
    val enabled = forumAccountReady(state) && !state.commandBusy && !state.commandUncertain && !state.accountBusy
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canReply) TextButton(onClick = { actions.reply(post.number) }, enabled = enabled) { Text(stringResource(R.string.f_reply)) }
        if (post.canEdit) TextButton(onClick = { actions.edit(post) }, enabled = enabled) { Text(stringResource(R.string.f_edit_post)) }
        TextButton(onClick = { actions.submit(ForumCommand.Bookmark(post.id)) }, enabled = enabled) { Text(stringResource(R.string.f_bookmark_account)) }
        if (post.canLike) TextButton(onClick = { actions.submit(ForumCommand.Like(post.id, post.liked)) }, enabled = enabled) { Text(stringResource(if (post.liked) R.string.f_unlike else R.string.f_like, post.likeCount)) }
    }
    if (state.reactionChoices.isNotEmpty() && (post.canLike || post.reactions.any { it.selected && it.canUndo })) {
        var expanded by remember(post.id) { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_react)) }
            DropdownMenu(expanded, { expanded = false }) {
                state.reactionChoices.forEach { id ->
                    DropdownMenuItem(text = { Text(id) }, onClick = {
                        expanded = false
                        actions.submit(ForumCommand.Reaction(post.id, id))
                    })
                }
            }
        }
    }
    if (post.reactions.isNotEmpty()) Text(post.reactions.joinToString(" · ") { "${it.id} ${it.count}${if (it.selected) " ✓" else ""}" }, style = MaterialTheme.typography.labelMedium)
    post.polls.forEach { poll ->
        var selected by remember(post.id, poll) { mutableStateOf(poll.votes.toSet()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(poll.name, style = MaterialTheme.typography.titleMedium)
            poll.options.forEachIndexed { index, option ->
                val choiceLabel = option.blocks.joinToString(" ") { it.text }.ifBlank { stringResource(R.string.f_poll_choice, index + 1) }
                Row {
                    Checkbox(
                        modifier = Modifier.semantics { contentDescription = choiceLabel },
                        checked = option.id in selected,
                        onCheckedChange = if (poll.status == "open" && poll.type in listOf("regular", "multiple") && enabled) {
                            { checked ->
                                selected = if (poll.type == "regular") {
                                    setOf(option.id)
                                } else if (checked) {
                                    selected + option.id
                                } else {
                                    selected - option.id
                                }
                            }
                        } else {
                            null
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        option.blocks.forEach { ForumBlockView(it, onLink, image, "${ForumLimits.ORIGIN}/t/${post.topicId}/${post.number}", onLink) }
                        option.votes?.let { Text(stringResource(R.string.f_vote_count, it), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            if (poll.type in listOf("regular", "multiple")) {
                OutlinedButton(onClick = { actions.submit(ForumCommand.Vote(post.id, poll.name, selected.toList())) }, enabled = enabled && poll.status == "open" && selected.size in poll.min..poll.max, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_vote)) }
            } else {
                Text(stringResource(R.string.f_poll_unsupported))
            }
            if (poll.status != "open") Text(stringResource(R.string.f_closed))
        }
    }
}
