package vn.edu.uit.mobile.forum

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

@Composable fun ForumScreen(viewModel: ForumViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    BackHandler { if (state.topic != null) viewModel.back() else viewModel.close() }
    ForumContent(
        state, viewModel::tab, viewModel::latest, viewModel::search, viewModel::topic, viewModel::save, viewModel::window, viewModel::position, viewModel::retry,
        { if (state.topic != null) viewModel.back() else viewModel.close() }, { clear = true },
        { raw ->
            val target = ForumUrls.target(raw)
            if (target != null) viewModel.topic(target.topicId, target.postNumber) else ForumUrls.safe(raw)?.let { link = it }
        }, viewModel::image, { url -> ForumUrls.safe(url)?.let { link = it } }
    )
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
    onBrowser: (String) -> Unit = onLink
) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(if (state.topic == null) R.string.f_close else R.string.f_back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.f_title), style = MaterialTheme.typography.titleLarge)
                Text("forum.uit.edu.vn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(enabled = !state.loading, onClick = {
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
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("forum-loading"))
        state.error?.let { error ->
            Surface(shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(errorLabel(error)), style = MaterialTheme.typography.bodyMedium)
                    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
                    LaunchedEffect(state.retryAt) {
                        while (now < state.retryAt) {
                            delay(1000)
                            now = System.currentTimeMillis() / 1000
                        }
                    }
                    if (state.retryAt > now) Text(stringResource(R.string.f_wait, state.retryAt - now))
                    TextButton(onClick = onRetry, enabled = !state.loading && now >= state.retryAt) { Text(stringResource(R.string.f_retry)) }
                }
            }
        }
        if (state.fetchedAt > 0) Text(stringResource(if (state.offline) R.string.f_cached else R.string.f_retrieved, date(state.fetchedAt)), style = MaterialTheme.typography.labelSmall, color = if (state.offline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        val topic = state.topic
        if (topic != null) {
            ForumReader(state, onTopic, onSave, onWindow, onPosition, onLink, imageLoader, onBrowser)
        } else {
            var query by rememberSaveable { mutableStateOf(state.query) }
            if (state.tab == ForumTab.SEARCH) {
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().testTag("forum-query"), label = { Text(stringResource(R.string.f_search_hint)) }, maxLines = 3, shape = TechnicalShapes.Control)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.f_search_note), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { onSearch(query, false) }, enabled = query.isNotBlank() && !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_search)) }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("forum-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                when (state.tab) {
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
                        item { Text(stringResource(R.string.f_local_note), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
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

@Composable private fun ColumnScope.ForumReader(state: ForumState, onTopic: (Long, Int, Int) -> Unit, onSave: (ForumTopic) -> Unit, onWindow: (Boolean) -> Unit, onPosition: (ForumPost, Int) -> Unit, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray, onBrowser: (String) -> Unit) {
    val topic = state.topic ?: return
    val list = rememberLazyListState()
    val density = LocalDensity.current
    var appliedJump by rememberSaveable { mutableStateOf("") }
    val jumpKey = "${state.readerSession}:${topic.id}:${state.scrollVersion}"
    var number by rememberSaveable(topic.id) { mutableStateOf("") }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f).testTag("forum-jump"), label = { Text(stringResource(R.string.f_number)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Control)
        TextButton(onClick = { number.toIntOrNull()?.takeIf { it > 0 }?.let { onTopic(topic.id, it, 0) } }, enabled = !state.loading && (number.toIntOrNull() ?: 0) > 0) { Text(stringResource(R.string.f_go)) }
        IconButton(onClick = { onSave(topic) }) {
            val saved = state.local.saved.any { it.id == topic.id }
            Icon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.f_unsave else R.string.f_save))
        }
    }
    if (position != null) TextButton(onClick = { onTopic(topic.id, position.postNumber, position.offsetDp) }, enabled = !state.loading) { Text(stringResource(R.string.f_resume, position.postNumber)) }
    LazyColumn(Modifier.weight(1f).testTag("forum-reader"), state = list, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(topic.title, style = MaterialTheme.typography.headlineSmall)
                state.categories.firstOrNull { it.id == topic.categoryId }?.let { Text(it.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                if (topic.tags.isNotEmpty()) Text(topic.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                if (topic.closed) Text(stringResource(R.string.f_closed), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.f_window, if (state.end == 0) 0 else state.start + 1, state.end, state.stream.size, state.posts.size), style = MaterialTheme.typography.labelSmall)
                if (state.unavailable > 0) Text(stringResource(R.string.f_missing, state.unavailable), style = MaterialTheme.typography.bodySmall)
                if (state.posts.isNotEmpty() && state.posts.none { it.number == state.anchorNumber }) Text(stringResource(R.string.f_jump_missing), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onBrowser(topic.url) }) { Text(stringResource(R.string.f_browser)) }
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
                SelectionContainerCompat { post.blocks.forEach { block -> ForumBlockView(block, onLink, imageLoader, topic.url + "/${post.number}", onBrowser) } }
            }
        }
        if (state.posts.isEmpty() && !state.loading) item { Text(stringResource(R.string.f_no_posts)) }
        if (state.end < state.stream.size) item { Button(onClick = { onWindow(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("forum-next"), shape = TechnicalShapes.Control) { Text(stringResource(R.string.f_next)) } }
    }
}

@Composable private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable private fun ForumBlockView(block: ForumBlock, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray, source: String, onBrowser: (String) -> Unit) {
    when (block.kind) {
        ForumBlockKind.CODE -> Surface(modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.surfaceContainerHighest) { Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
        ForumBlockKind.QUOTE -> Surface(shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(block.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ForumBlockKind.HEADING -> Text(block.text, style = MaterialTheme.typography.titleMedium)
        ForumBlockKind.DETAILS -> {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.f_details)) }
            if (expanded) Text(block.text)
        }
        ForumBlockKind.TABLE -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            block.rows.forEach { row ->
                Row { row.forEach { cell -> Text(cell, Modifier.width(160.dp).padding(8.dp), style = MaterialTheme.typography.bodyMedium) } }
                HorizontalDivider()
            }
        }
        ForumBlockKind.IMAGE -> ForumImage(block, imageLoader, onLink)
        ForumBlockKind.OMITTED -> TextButton(onClick = { onBrowser(source) }) { Text(stringResource(R.string.f_abbreviated)) }
        ForumBlockKind.UNSUPPORTED -> TextButton(onClick = { onBrowser(source) }) { Text(stringResource(R.string.f_unsupported)) }
        else -> if (block.text.isNotBlank()) Text(block.text, style = MaterialTheme.typography.bodyLarge)
    }
    if (block.kind != ForumBlockKind.IMAGE) block.links.forEach { link -> TextButton(onClick = { onLink(link.url) }, contentPadding = PaddingValues(0.dp)) { Text(link.label + " ↗", style = MaterialTheme.typography.bodyMedium) } }
}

@Composable private fun ForumImage(block: ForumBlock, loader: suspend (String) -> ByteArray, onLink: (String) -> Unit) {
    val url = block.links.firstOrNull()?.url ?: return
    var requested by remember(url) { mutableStateOf(false) }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    val supported = ForumUrls.image(url) != null
    LaunchedEffect(url, requested) {
        if (requested && supported) {
            try {
                bitmap = withContext(Dispatchers.IO) {
                    val bytes = loader(url)
                    require(bytes.size <= 3 * 1024 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8000000)
                    val options = BitmapFactory.Options().apply { inSampleSize = if (maxOf(bounds.outWidth, bounds.outHeight) > 2048) 2 else 1 }
                    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)).asImageBitmap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    bitmap?.let { Image(it, block.text, Modifier.fillMaxWidth().heightIn(max = 480.dp)) }
    if (bitmap == null) OutlinedButton(onClick = { if (supported) requested = true else onLink(url) }, enabled = !requested, shape = TechnicalShapes.Control) { Text(stringResource(if (supported) R.string.f_image else R.string.f_external_image)) }
    if (failed) Text(stringResource(R.string.f_image_failed), style = MaterialTheme.typography.bodySmall)
    if (block.text.isNotBlank()) Text(block.text, style = MaterialTheme.typography.labelSmall)
}

@Composable private fun EmptyForum() {
    Text(stringResource(R.string.f_empty), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
}
private fun tabLabel(tab: ForumTab) = when (tab) {
    ForumTab.LATEST -> R.string.f_latest
    ForumTab.CATEGORIES -> R.string.f_categories
    ForumTab.SEARCH -> R.string.f_search
    ForumTab.SAVED -> R.string.f_saved
}
private fun errorLabel(error: ForumFailure) = when (error) {
    ForumFailure.NETWORK -> R.string.f_network
    ForumFailure.RATE_LIMIT -> R.string.f_rate
    ForumFailure.UNAVAILABLE -> R.string.f_unavailable
    ForumFailure.INVALID -> R.string.f_invalid
    ForumFailure.STORAGE -> R.string.f_storage
    ForumFailure.CAPACITY -> R.string.f_capacity
}
private fun date(epoch: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch * 1000))
private fun isoDate(raw: String): String = runCatching { date(Instant.parse(raw).epochSecond) }.getOrDefault(raw)
