package vn.edu.uit.mobile.forum

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

@Composable fun ForumScreen(viewModel: ForumViewModel, onAssistant: (AssistantForumScope) -> Unit = {}, onReturn: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = ForumAccountActions({
        val topic = state.topic
        if (state.user == null && topic != null) viewModel.accountForTopic(topic.id, state.anchorNumber) else viewModel.account()
    }, { viewModel.compose(create = true) }, { viewModel.compose(it) }, viewModel::edit, viewModel::submit, viewModel::resumeDraft, { number ->
        state.topic?.let { viewModel.accountForTopic(it.id, number) } ?: viewModel.account()
    })
    var link by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    fun back() {
        if (onReturn != null) {
            onReturn()
        } else if (state.topic != null) {
            viewModel.back()
        } else {
            viewModel.close()
        }
    }
    BackHandler { back() }
    ForumContent(
        state, viewModel::tab, viewModel::latest, viewModel::search, viewModel::topic, viewModel::save, viewModel::window, viewModel::position, viewModel::retry,
        { back() }, { clear = true },
        { raw ->
            val target = ForumUrls.target(raw)
            if (target != null) viewModel.topic(target.topicId, target.postNumber) else ForumUrls.safe(raw)?.let { link = it }
        }, viewModel::image, { url -> ForumUrls.safe(url)?.let { link = it } }, { post -> viewModel.history(post) }, viewModel::discover, if (viewModel.supportsAccount && !state.publicCitation) actions else null, onAssistant
    )
    if (state.loginVisible) ForumLoginDialog(state, viewModel::login, viewModel::dismissAccount)
    if (state.memberVisible) {
        ForumMemberDialog(state, { tab, append, channel ->
            viewModel.member(tab, append, channel, state.threadId.takeIf { channel?.id == state.channel?.id && channel != null })
        }, viewModel::submit, { id, number ->
            viewModel.dismissAccount()
            viewModel.topic(id, number)
        }, viewModel::disconnect, viewModel::dismissAccount, { url -> ForumUrls.safe(url)?.let { link = it } }, viewModel::image, { id -> viewModel.member(ForumMemberTab.CHAT, channel = state.channel, threadId = id) }, { viewModel.member(ForumMemberTab.CHAT, append = true, channel = state.channel, threadId = state.threadId, newer = true) }, viewModel::openBookmark)
    }
    state.composer?.let { ForumComposerDialog(state, viewModel::submit, viewModel::dismissComposer, viewModel::updateDraft, viewModel::discardDraft) }
    if (state.draftChoiceVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDraftChoice,
            shape = TechnicalShapes.Dialog,
            title = { Text(stringResource(R.string.f_existing_draft)) },
            text = { Text(stringResource(R.string.f_existing_draft_note)) },
            confirmButton = { TextButton(onClick = viewModel::resumeDraft) { Text(stringResource(R.string.f_resume_draft)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDraftChoice) { Text(stringResource(R.string.f_cancel)) } }
        )
    }
    if (state.historyPost != null) ForumHistoryDialog(state, viewModel::history, viewModel::closeHistory, { url -> ForumUrls.safe(url)?.let { link = it } }, viewModel::image)
    link?.let { url ->
        val handler = LocalUriHandler.current
        var failed by remember(url) { mutableStateOf(false) }
        AlertDialog(shape = TechnicalShapes.Dialog, onDismissRequest = { link = null }, title = { Text(stringResource(R.string.f_open_link)) }, text = {
            Column {
                Text(url)
                if (failed) Text(stringResource(R.string.f_link_failed))
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    handler.openUri(url)
                    link = null
                } catch (_: Exception) {
                    failed = true
                }
            }) { Text(stringResource(R.string.f_open)) }
        }, dismissButton = { TextButton(onClick = { link = null }) { Text(stringResource(R.string.f_cancel)) } })
    }
    if (clear) {
        AlertDialog(shape = TechnicalShapes.Dialog, onDismissRequest = { clear = false }, title = { Text(stringResource(R.string.f_clear)) }, text = { Text(stringResource(R.string.f_clear_body)) }, confirmButton = {
            TextButton(onClick = {
                clear = false
                viewModel.clear()
            }) { Text(stringResource(R.string.f_clear)) }
        }, dismissButton = { TextButton(onClick = { clear = false }) { Text(stringResource(R.string.f_cancel)) } })
    }
}

@Composable fun ForumContent(
    state: ForumState,
    onTab: (ForumTab) -> Unit = {},
    onLatest: (ForumCategory?, Boolean) -> Unit = { _, _ -> },
    onSearch: (String, Boolean) -> Unit = { _, _ -> },
    onTopic: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onSave: (ForumTopic) -> Unit = {},
    onWindow: (Boolean) -> Unit = {},
    onPosition: (ForumPost, Int) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onBack: () -> Unit = {},
    onClear: () -> Unit = {},
    onLink: (String) -> Unit = {},
    imageLoader: suspend (String) -> ByteArray = { throw ForumException(ForumFailure.NETWORK) },
    onBrowser: (String) -> Unit = onLink,
    onHistory: (ForumPost) -> Unit = {},
    onDiscover: (ForumFilter, ForumPeriod, ForumTag?) -> Unit = { _, _, _ -> },
    accountActions: ForumAccountActions? = null,
    onAssistant: (AssistantForumScope) -> Unit = {}
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.tab, state.filter, state.period, state.tag?.id, state.category?.id, state.query) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(if (state.topic == null) R.string.f_close else R.string.f_back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.f_title), style = MaterialTheme.typography.titleLarge)
                Text("forum.uit.edu.vn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            accountActions?.let { actions -> IconButton(onClick = actions.account, enabled = !state.accountBusy && !state.commandBusy) { Icon(Icons.Outlined.Person, stringResource(R.string.f_account)) } }
            IconButton(enabled = !state.loading && !state.commandBusy, onClick = {
                val t = state.topic
                if (t != null) {
                    onTopic(t.id, state.anchorNumber, 0)
                } else if (state.tab == ForumTab.SEARCH) {
                    onSearch(state.query, false)
                } else if (state.tab == ForumTab.LATEST) {
                    onLatest(state.category, false)
                } else {
                    onTab(state.tab)
                }
            }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.f_refresh)) }
        }
        if (state.topic == null) {
            ScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 0.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                ForumTab.entries.forEach { tab -> Tab(selected = state.tab == tab, onClick = { onTab(tab) }, text = { Text(stringResource(tabLabel(tab))) }) }
            }
        }
        if (state.publicCitation) Text(stringResource(R.string.f_public_ai_source), style = MaterialTheme.typography.labelSmall)
        if (state.topic == null && state.user != null && !state.publicCitation) Text(state.user.username, style = MaterialTheme.typography.labelSmall)
        if (accountActions != null && state.user?.canCreateTopic == true && state.topic == null && !state.memberVisible) TextButton(onClick = accountActions.create, enabled = !state.commandBusy) { Text(stringResource(R.string.f_new_topic)) }
        if (accountActions != null && state.user != null && state.draft != null) TextButton(onClick = accountActions.draft, enabled = !state.commandBusy) { Text(stringResource(R.string.f_resume_draft)) }
        if (state.accountError != null && !state.loginVisible && !state.memberVisible && state.composer == null) ForumAccountError(state)
        if (state.commandBusy || state.accountBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("forum-loading"))
        if (state.error != null && (state.topic == null || state.posts.isNotEmpty())) ForumReadFailure(state, onRetry)
        if (state.offline && state.fetchedAt > 0) Text(stringResource(R.string.f_cached, date(state.fetchedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        val topic = state.topic
        if (topic != null) {
            ForumReader(state, onTopic, onSave, onWindow, onPosition, onLink, imageLoader, onBrowser, onHistory, accountActions, onAssistant)
        } else {
            var query by rememberSaveable { mutableStateOf(state.query) }
            var tagQuery by rememberSaveable { mutableStateOf("") }
            if (state.tab == ForumTab.LATEST && state.category == null) ForumFeedControls(state, onDiscover)
            if (state.tab == ForumTab.TAGS) {
                OutlinedTextField(tagQuery, { tagQuery = it.take(200) }, Modifier.fillMaxWidth().testTag("forum-tag-query"), label = { Text(stringResource(R.string.f_find_tag)) }, singleLine = true, shape = TechnicalShapes.Control)
                if (state.tagsTruncated) Text(stringResource(R.string.f_tags_truncated), style = MaterialTheme.typography.labelSmall)
            }
            if (state.tab == ForumTab.SEARCH) {
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().testTag("forum-query"), label = { Text(stringResource(R.string.f_search_hint)) }, maxLines = 3, shape = TechnicalShapes.Control)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.f_search_note), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { onSearch(query, false) }, enabled = query.isNotBlank() && !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_search)) }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("forum-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                when (state.tab) {
                    ForumTab.TAGS -> {
                        val tags = state.tags.filter { it.name.contains(tagQuery.trim(), ignoreCase = true) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        if (tags.isEmpty() && !state.loading) item { Text(stringResource(R.string.f_no_tags), Modifier.padding(vertical = 24.dp)) }
                        items(tags, key = { it.id }) { tag ->
                            Column(Modifier.fillMaxWidth().clickable { onDiscover(ForumFilter.LATEST, state.period, tag) }.padding(vertical = 12.dp).testTag("forum-tag-${tag.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tag.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.f_tag_topics, tag.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (tag.description.isNotBlank()) Text(tag.description, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                    ForumTab.CATEGORIES -> {
                        val sorted = state.categories.filter { it.parentId == null }.flatMap { parent -> listOf(parent) + state.categories.filter { it.parentId == parent.id }.sortedBy { it.name } } + state.categories.filter { it.parentId != null && state.categories.none { p -> p.id == it.parentId } }
                        items(sorted, key = { it.id }) { category ->
                            Column(Modifier.fillMaxWidth().clickable { onLatest(category, false) }.padding(vertical = 16.dp).padding(start = if (category.parentId == null) 0.dp else 16.dp)) {
                                Text(category.name, style = if (category.parentId == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                                if (category.description.isNotBlank()) Text(category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                    ForumTab.SAVED -> {
                        item { Text(stringResource(if (state.user == null) R.string.f_local_note else R.string.f_member_local_note), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                        if (state.local.saved.isEmpty()) item { EmptyForum() }
                        items(state.local.saved, key = { it.id }) { t ->
                            val position = state.local.positions.firstOrNull { it.topic.id == t.id }
                            TopicRow(t, state.categories, true, { onTopic(t.id, position?.postNumber ?: 1, position?.offsetDp ?: 0) }, { onSave(t) })
                            state.local.savedAt[t.id]?.let { at -> Text(stringResource(R.string.f_saved_on, date(at)), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        item { TextButton(onClick = onClear, Modifier.testTag("forum-clear")) { Text(stringResource(R.string.f_clear)) } }
                    }
                    ForumTab.SEARCH -> {
                        if (state.hits.isEmpty() && state.query.isNotBlank() && !state.loading) item { EmptyForum() }
                        items(state.hits, key = { it.id }) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { onTopic(hit.topic.id, hit.postNumber, 0) }.padding(vertical = 16.dp)) {
                                Text(hit.topic.title, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.f_post, hit.postNumber), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                    ForumTab.LATEST -> {
                        state.category?.let { category ->
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { onLatest(null, false) }) { Text(stringResource(R.string.f_all)) }
                                }
                            }
                        }
                        if (state.topics.isEmpty() && !state.loading) item { EmptyForum() }
                        items(state.topics, key = { it.id }) { t -> TopicRow(t, state.categories, state.local.saved.any { it.id == t.id }, { onTopic(t.id, 1, 0) }, { onSave(t) }) }
                    }
                }
                if (state.more && state.tab in listOf(ForumTab.LATEST, ForumTab.SEARCH)) item { OutlinedButton(onClick = { if (state.tab == ForumTab.SEARCH) onSearch(state.query, true) else onLatest(state.category, true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_more)) } }
            }
        }
    }
}

@Composable private fun TopicRow(topic: ForumTopic, categories: List<ForumCategory>, saved: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.firstOrNull { it.id == topic.categoryId }?.let { Text(it.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
            Text(topic.title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.f_posts, topic.posts), style = MaterialTheme.typography.labelMedium)
                if (topic.pinned) Text(stringResource(R.string.f_pinned), style = MaterialTheme.typography.labelMedium)
                if (topic.closed) Text(stringResource(R.string.f_closed), style = MaterialTheme.typography.labelMedium)
            }
            if (topic.lastPosted.isNotBlank()) Text(isoDate(topic.lastPosted), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.f_unsave else R.string.f_save)) }
    }
    HorizontalDivider()
}

@Composable private fun ColumnScope.ForumReader(state: ForumState, onTopic: (Long, Int, Int) -> Unit, onSave: (ForumTopic) -> Unit, onWindow: (Boolean) -> Unit, onPosition: (ForumPost, Int) -> Unit, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray, onBrowser: (String) -> Unit, onHistory: (ForumPost) -> Unit, accountActions: ForumAccountActions?, onAssistant: (AssistantForumScope) -> Unit) {
    val topic = state.topic ?: return
    if (state.posts.isEmpty()) {
        Column(Modifier.weight(1f).fillMaxWidth().testTag("forum-topic-empty"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(topic.title.ifBlank { stringResource(R.string.f_opening_topic) }, style = MaterialTheme.typography.headlineSmall)
            if (state.loading) {
                Text(stringResource(R.string.f_loading_topic), style = MaterialTheme.typography.bodyMedium)
            } else if (state.error != null) {
                ForumReadFailure(state, { onTopic(topic.id, state.anchorNumber, 0) })
            } else {
                Text(stringResource(R.string.f_no_posts), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onBrowser(topic.url) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_browser)) }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var navigation by rememberSaveable(topic.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    var appliedJump by rememberSaveable { mutableStateOf("") }
    val jumpKey = "${state.readerSession}:${topic.id}:${state.scrollVersion}"
    var number by rememberSaveable(topic.id) { mutableStateOf("") }
    var digest by rememberSaveable(topic.id) { mutableStateOf(false) }
    if (digest) {
        ForumAiRangeDialog(topic, state.anchorNumber, { digest = false }) { scope ->
            digest = false
            onAssistant(scope)
        }
    }
    val position = state.local.positions.firstOrNull { it.topic.id == topic.id }
    LaunchedEffect(jumpKey) {
        if (appliedJump == jumpKey) return@LaunchedEffect
        val index = state.posts.indexOfFirst { it.number >= state.anchorNumber }
        list.scrollToItem(if (index < 0 || state.anchorNumber == 1 && state.start == 0) 0 else index + 1, with(density) { state.anchorOffsetDp.dp.roundToPx() })
        appliedJump = jumpKey
    }
    LaunchedEffect(topic.id, state.posts) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            state.posts.getOrNull(index - 1)?.let { onPosition(it, with(density) { offset.toDp().value.toInt() }) }
        }
    }
    val visible = state.posts.getOrNull((list.firstVisibleItemIndex - 1).coerceAtLeast(0))?.number ?: state.anchorNumber
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ navigation = true }, Modifier.weight(1f).testTag("forum-position"), shape = TechnicalShapes.Control) {
            Icon(Icons.Outlined.FormatListNumbered, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.f_position, visible))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        IconButton(onClick = { onSave(topic) }, enabled = !state.publicCitation) {
            val saved = state.local.saved.any { it.id == topic.id }
            Icon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.f_unsave else R.string.f_save))
        }
        Box {
            IconButton({ menu = true }, Modifier.testTag("forum-topic-menu")) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.f_topic_actions)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.f_copy_topic)) }, onClick = {
                    clipboard.setText(AnnotatedString(topic.url))
                    menu = false
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.f_browser)) }, onClick = {
                    menu = false
                    onBrowser(topic.url)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.f_ai_digest)) }, onClick = {
                    menu = false
                    digest = true
                }, enabled = !state.loading && !state.commandBusy && !state.accountBusy, modifier = Modifier.testTag("forum-ai-entry"))
                val resume = position?.postNumber ?: state.remoteReadNumber
                if (resume != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.f_resume, resume)) }, onClick = {
                        menu = false
                        onTopic(topic.id, resume, position?.offsetDp ?: 0)
                    }, enabled = !state.loading)
                }
                if (state.fetchedAt > 0) DropdownMenuItem(text = { Text(stringResource(R.string.f_retrieved, date(state.fetchedAt)), style = MaterialTheme.typography.bodySmall) }, onClick = {}, enabled = false)
            }
        }
    }
    if (navigation) {
        Dialog({ navigation = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 480.dp), shape = TechnicalShapes.Dialog) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.f_jump), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(number, { number = it.filter(Char::isDigit).take(7) }, Modifier.fillMaxWidth().testTag("forum-jump"), label = { Text(stringResource(R.string.f_number)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Field)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ navigation = false }) { Text(stringResource(R.string.f_cancel)) }
                        TextButton({
                            number.toIntOrNull()?.takeIf { it > 0 }?.let {
                                navigation = false
                                onTopic(topic.id, it, 0)
                            }
                        }, enabled = !state.loading && (number.toIntOrNull() ?: 0) > 0) { Text(stringResource(R.string.f_go)) }
                    }
                }
            }
        }
    }
    LazyColumn(Modifier.weight(1f).testTag("forum-reader"), state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(topic.title, style = MaterialTheme.typography.headlineSmall)
                state.categories.firstOrNull { it.id == topic.categoryId }?.let { Text(it.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                if (topic.tags.isNotEmpty()) Text(topic.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                if (topic.closed) Text(stringResource(R.string.f_closed), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.f_posts, topic.posts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.unavailable > 0) Text(stringResource(R.string.f_missing, state.unavailable), style = MaterialTheme.typography.bodySmall)
                if (state.posts.isNotEmpty() && state.posts.none { it.number == state.anchorNumber }) Text(stringResource(R.string.f_jump_missing), style = MaterialTheme.typography.bodySmall)
                if (state.start > 0) OutlinedButton(onClick = { onWindow(false) }, enabled = !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_previous)) }
            }
        }
        items(state.posts, key = { it.id }) { post ->
            Column(Modifier.fillMaxWidth().testTag("forum-post-${post.number}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(post.author, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.f_post, post.number), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(isoDate(post.created), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                post.replyTo?.let { parent -> TextButton(onClick = { onTopic(topic.id, parent, 0) }) { Text(stringResource(R.string.f_reply_to, parent)) } }
                if (post.version > 1) {
                    if (post.canViewHistory) {
                        TextButton(onClick = { onHistory(post) }, modifier = Modifier.testTag("forum-history-${post.id}")) { Text(stringResource(R.string.f_history, post.version)) }
                    } else {
                        Text(stringResource(R.string.f_edited), style = MaterialTheme.typography.labelSmall)
                    }
                }
                SelectionContainerCompat {
                    post.blocks.forEach { block ->
                        ForumBlockView(block, onLink, imageLoader, topic.url + "/${post.number}", onBrowser) { poll ->
                            ForumPollView(
                                poll,
                                state.user != null && accountActions != null && !state.commandBusy && !state.commandUncertain && forumAccountReady(state),
                                post.id,
                                { options -> accountActions?.submit?.invoke(ForumCommand.Vote(post.id, poll.name, options)) },
                                { accountActions?.submit?.invoke(ForumCommand.RemoveVote(post.id, poll.name)) },
                                onLink,
                                imageLoader,
                                topic.url + "/${post.number}"
                            )
                        }
                    }
                }
                ForumPostActions(state, post, accountActions, onBrowser)
            }
        }
        if (state.posts.isEmpty() && !state.loading) item { Text(stringResource(R.string.f_no_posts)) }
        if (state.end < state.stream.size) item { Button(onClick = { onWindow(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("forum-next"), shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_next)) } }
    }
}

@Composable private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable private fun EmptyForum() {
    Text(stringResource(R.string.f_empty), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
}
private fun tabLabel(tab: ForumTab) = when (tab) {
    ForumTab.LATEST -> R.string.f_topics
    ForumTab.CATEGORIES -> R.string.f_categories
    ForumTab.TAGS -> R.string.f_tags
    ForumTab.SEARCH -> R.string.f_search
    ForumTab.SAVED -> R.string.f_saved
}
internal fun errorLabel(error: ForumFailure) = when (error) {
    ForumFailure.AUTHENTICATION -> R.string.f_auth_error
    ForumFailure.SECOND_FACTOR -> R.string.f_second_factor_required
    ForumFailure.FORBIDDEN -> R.string.f_forbidden
    ForumFailure.REJECTED -> R.string.f_rejected
    ForumFailure.CONFLICT -> R.string.f_conflict
    ForumFailure.UNCERTAIN -> R.string.f_uncertain
    ForumFailure.NETWORK -> R.string.f_network
    ForumFailure.RATE_LIMIT -> R.string.f_rate
    ForumFailure.UNAVAILABLE -> R.string.f_unavailable
    ForumFailure.INVALID -> R.string.f_invalid
    ForumFailure.STORAGE -> R.string.f_storage
    ForumFailure.CAPACITY -> R.string.f_capacity
}
private fun date(epoch: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch * 1000))
internal fun isoDate(raw: String): String = runCatching { date(Instant.parse(raw).epochSecond) }.getOrDefault(raw)

@Composable
private fun ForumAiRangeDialog(topic: ForumTopic, anchor: Int, dismiss: () -> Unit, open: (AssistantForumScope) -> Unit) {
    var first by rememberSaveable { mutableStateOf(anchor.coerceAtLeast(1).toString()) }
    var count by rememberSaveable { mutableStateOf("20") }
    val scope = AssistantForumScope(topic.id, first.toIntOrNull() ?: 0, count.toIntOrNull() ?: 0)
    val valid = runCatching { AssistantRules.forum(scope) }.isSuccess
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 680.dp), shape = TechnicalShapes.Dialog) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.f_ai_digest), style = MaterialTheme.typography.titleLarge)
                Text(topic.title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.f_ai_disclosure, AssistantRules.FORUM_POSTS), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(first, { first = it.filter(Char::isDigit).take(7) }, Modifier.fillMaxWidth().testTag("forum-ai-first"), label = { Text(stringResource(R.string.f_ai_first)) }, singleLine = true, shape = TechnicalShapes.Field, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth().testTag("forum-ai-count"), label = { Text(stringResource(R.string.f_ai_count)) }, singleLine = true, shape = TechnicalShapes.Field, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss) { Text(stringResource(R.string.f_cancel)) }
                    TextButton({ open(scope) }, enabled = valid) { Text(stringResource(R.string.f_ai_open)) }
                }
            }
        }
    }
}

@Composable private fun ForumReadFailure(state: ForumState, retry: () -> Unit) {
    val error = state.error ?: return
    val clipboard = LocalClipboardManager.current
    var detail by remember(error, state.networkIssue) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.retryAt) {
        while (now < state.retryAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    Surface(shape = TechnicalShapes.Panel, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(errorLabel(error)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (state.retryAt > now) Text(stringResource(R.string.f_wait, state.retryAt - now), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(retry, enabled = !state.loading && now >= state.retryAt, modifier = Modifier.testTag("forum-read-retry")) { Text(stringResource(R.string.f_retry)) }
                TextButton({ detail = !detail }, Modifier.testTag("forum-error-details")) { Text(stringResource(R.string.f_error_details)) }
            }
            if (detail) {
                Text(stringResource(networkIssueLabel(state.networkIssue)), style = MaterialTheme.typography.bodySmall)
                TextButton({
                    val diagnostic = "UIT Forum\ntopicId=${state.topic?.id ?: 0}\nanchorPostNumber=${state.anchorNumber}\nfailure=${error.name}\nnetwork=${state.networkIssue?.name ?: "UNRECORDED"}\nmode=${if (state.user == null) "guest" else "member"}\nrecordedAt=${Instant.now()}"
                    clipboard.setText(AnnotatedString(diagnostic))
                }) { Text(stringResource(R.string.f_copy_diagnostic)) }
            }
        }
    }
}
private fun networkIssueLabel(issue: ForumNetworkIssue?) = when (issue) {
    ForumNetworkIssue.DNS -> R.string.f_issue_dns
    ForumNetworkIssue.TLS -> R.string.f_issue_tls
    ForumNetworkIssue.TIMEOUT -> R.string.f_issue_timeout
    ForumNetworkIssue.CONNECT -> R.string.f_issue_connect
    ForumNetworkIssue.SERVER -> R.string.f_issue_server
    ForumNetworkIssue.IO -> R.string.f_issue_io
    else -> R.string.f_issue_unknown
}
