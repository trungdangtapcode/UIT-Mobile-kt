package vn.edu.uit.mobile.courses

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Normalizer
import java.util.Locale
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable fun BookmarksScreen(viewModel: BookmarksViewModel, onBack: () -> Unit, onSource: (WorkspaceSearchHit) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BookmarksContent(state, onBack, { id -> viewModel.source(id)?.let(onSource) }, { id ->
        viewModel.originalUrl(id)?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                viewModel.problem(BookmarkProblem.BROWSER)
            }
        }
    }, viewModel::remove, viewModel::refresh, viewModel::addLink)
}

@Composable fun BookmarkToggle(saved: Boolean, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconToggleButton(saved, { onClick() }, enabled = enabled) {
        Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, stringResource(if (saved) R.string.bm_unpin else R.string.bm_pin, title), tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun bookmarkProblemText(problem: BookmarkProblem): String = stringResource(
    when (problem) {
        BookmarkProblem.LOAD -> R.string.bm_load_error
        BookmarkProblem.SAVE -> R.string.bm_save_error
        BookmarkProblem.LINK -> R.string.bm_link_error
        BookmarkProblem.SOURCE -> R.string.bm_source_error
        BookmarkProblem.BROWSER -> R.string.bm_browser_error
    }
)

@Composable fun bookmarkKindText(kind: BookmarkKind): String = stringResource(
    when (kind) {
        BookmarkKind.COURSE -> R.string.bm_courses
        BookmarkKind.MATERIAL -> R.string.bm_materials
        BookmarkKind.ASSIGNMENT -> R.string.bm_assignments
        BookmarkKind.NOTICE -> R.string.bm_notices
        BookmarkKind.DISCUSSION -> R.string.bm_discussions
        BookmarkKind.LINK -> R.string.bm_links
    }
)

@Composable fun BookmarksContent(state: BookmarksState, onBack: () -> Unit = {}, onSource: (String) -> Unit = {}, onOriginal: (String) -> Unit = {}, onRemove: (String) -> Unit = {}, onRefresh: () -> Unit = {}, onAddLink: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> }) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<BookmarkKind?>(null) }
    var addLink by rememberSaveable { mutableStateOf(false) }
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).lowercase(Locale.ROOT).replace('đ', 'd').replace(Regex("\\p{M}+"), "")
    val words = normalized(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val owners = state.owners.map { it.id }.toSet()
    val ownedItems = state.items.filter { it.accountId in owners }
    val items = ownedItems.filter { item -> (kind == null || item.kind == kind) && words.all { it in normalized("${state.source(item)?.title ?: item.title} ${state.source(item)?.context ?: item.context}") } }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("bookmarks-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(R.string.bm_workspace))
            }
            PageHeading(stringResource(R.string.bm_title), stringResource(R.string.bm_subtitle))
            Text(stringResource(R.string.bm_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedButton({ addLink = true }, enabled = state.ready && !state.busy && state.owners.isNotEmpty(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.bm_add_link)) }
            state.problem?.let { InlineNotice(bookmarkProblemText(it), true) }
            if (state.problem != null) TextButton(onRefresh, enabled = state.ready && !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.bm_reload)) }
            if (!state.ready || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            SearchField(query, { query = it.take(120) }, stringResource(R.string.bm_search))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text(stringResource(R.string.bm_all)) }, shape = TechnicalShapes.Control)
                BookmarkKind.entries.forEach { value -> FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(bookmarkKindText(value)) }, shape = TechnicalShapes.Control) }
            }
        }
        if (state.ready && items.isEmpty()) {
            item {
                EmptyState(
                    stringResource(
                        if (state.problem == BookmarkProblem.LOAD && ownedItems.isEmpty()) {
                            R.string.bm_list_unavailable
                        } else if (ownedItems.isEmpty()) {
                            R.string.bm_empty
                        } else {
                            R.string.bm_no_match
                        }
                    ),
                    stringResource(
                        if (state.problem == BookmarkProblem.LOAD) {
                            R.string.bm_load_error
                        } else if (state.owners.isEmpty()) {
                            R.string.bm_connect
                        } else {
                            R.string.bm_empty_body
                        }
                    )
                )
            }
        }
        items(items, key = { it.id }) { item ->
            val source = state.source(item)
            val external = item.kind in setOf(BookmarkKind.LINK, BookmarkKind.DISCUSSION)
            val title = source?.title ?: item.title
            Panel(Modifier.fillMaxWidth().testTag("bookmark:${item.id}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(bookmarkKindText(item.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    BookmarkToggle(true, title, !state.busy) { onRemove(item.id) }
                }
                Text(source?.context ?: item.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.owners.firstOrNull { it.id == item.accountId }?.let { owner -> Text(owner.displayName + " · " + runCatching { java.net.URI(owner.baseUrl).host }.getOrDefault(owner.baseUrl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!state.available(item)) Text(stringResource(R.string.bm_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (external) Text(stringResource(R.string.bm_external_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ if (external) onOriginal(item.id) else onSource(item.id) }, enabled = state.available(item) && !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(if (external) R.string.bm_browser else R.string.bm_open)) }
            }
        }
    }
    if (addLink) {
        var accountId by rememberSaveable { mutableStateOf(state.owners.firstOrNull()?.id.orEmpty()) }
        var title by rememberSaveable { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { addLink = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 600.dp), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.bm_add_link), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.bm_link_hint), style = MaterialTheme.typography.bodySmall)
                        state.owners.forEach { owner -> FilterChip(selected = accountId == owner.id, onClick = { accountId = owner.id }, enabled = !state.busy, label = { Text(owner.displayName + " · " + runCatching { java.net.URI(owner.baseUrl).host }.getOrDefault(owner.baseUrl)) }, shape = TechnicalShapes.Control) }
                        OutlinedTextField(title, { title = it.take(240) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.bm_link_title)) }, enabled = !state.busy, shape = TechnicalShapes.Field)
                        OutlinedTextField(url, { url = it.take(2048) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.bm_link_url)) }, enabled = !state.busy, shape = TechnicalShapes.Field)
                        state.problem?.let { InlineNotice(bookmarkProblemText(it), true) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ addLink = false }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.bm_cancel)) }
                        TextButton({ onAddLink(accountId, title, url) { addLink = false } }, enabled = !state.busy && title.isNotBlank() && url.isNotBlank() && state.owners.any { it.id == accountId }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.bm_save_link)) }
                    }
                }
            }
        }
    }
}
