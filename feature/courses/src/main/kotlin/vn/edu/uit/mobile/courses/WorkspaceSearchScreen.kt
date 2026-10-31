package vn.edu.uit.mobile.courses

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R
import vn.edu.uit.mobile.model.*

@Composable fun WorkspaceSearchScreen(viewModel: WorkspaceSearchViewModel, onClose: () -> Unit, onCourse: (Course) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val fileError = stringResource(R.string.srch_file_error)
    LaunchedEffect(viewModel) { viewModel.files.collect { if (viewModel.canOpen(it) && !openSavedCourseFile(context, it.path)) snack.showSnackbar(fileError) } }
    Box {
        WorkspaceSearchContent(state, { if (state.selected != null) viewModel.back() else onClose() }, viewModel::query, viewModel::open, viewModel::download, onCourse)
        SnackbarHost(snack)
    }
}

@Composable fun WorkspaceSearchContent(state: WorkspaceSearchState, onBack: () -> Unit = {}, onQuery: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onDownload: (CourseFile) -> Unit = {}, onCourse: ((Course) -> Unit)? = null) {
    val selected = state.hits.firstOrNull { it.key == state.selected }
    var filter by rememberSaveable { mutableStateOf<WorkspaceSearchKind?>(null) }
    val results = remember(state.hits, state.query) { SearchWorkspaceUseCase()(state.hits, state.query) }
    val resultsScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("workspace-search-content"), state = if (selected == null) resultsScroll else detailScroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(if (selected == null) R.string.srch_title else R.string.srch_results))
            }
            PageHeading(if (selected == null) stringResource(R.string.srch_page_title) else selected.title, if (selected == null) stringResource(R.string.srch_page_subtitle) else selected.context)
        }
        if (selected == null) {
            item {
                SearchField(state.query, onQuery, stringResource(R.string.srch_field_hint))
                Text(stringResource(R.string.srch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.unownedNotices) InlineNotice(stringResource(R.string.srch_unowned))
                state.message?.let { InlineNotice(it) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.srch_all)) }, shape = TechnicalShapes.Control)
                    WorkspaceSearchKind.entries.forEach { kind -> FilterChip(selected = filter == kind, onClick = { filter = kind }, label = { Text(searchKindLabel(kind)) }, shape = TechnicalShapes.Control) }
                }
            }
            when {
                state.hits.isEmpty() -> item { EmptyState(stringResource(R.string.srch_empty_title), stringResource(R.string.srch_empty_body)) }
                state.query.isBlank() -> item { EmptyState(stringResource(R.string.srch_prompt_title), stringResource(R.string.srch_prompt_body)) }
                results.none { filter == null || it.kind == filter } -> item { EmptyState(stringResource(R.string.srch_none_title), stringResource(R.string.srch_none_body)) }
            }
            WorkspaceSearchKind.entries.filter { filter == null || it == filter }.forEach { kind ->
                val group = results.filter { it.kind == kind }
                if (group.isNotEmpty()) {
                    item(key = "group:$kind") { SectionHeading("${searchKindLabel(kind)} · ${group.size}", if (group.size > 20) stringResource(R.string.srch_group_more) else null) }
                    group.take(20).forEach { hit ->
                        item(key = hit.key) {
                            Panel(Modifier.fillMaxWidth()) {
                                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                                Text(hit.context, style = MaterialTheme.typography.bodySmall)
                                TextButton({ onOpen(hit.key) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.srch_open_result)) }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(searchKindLabel(selected.kind), style = MaterialTheme.typography.labelMedium)
                    val body = selected.assignment?.intro ?: selected.module?.description ?: selected.announcement?.bodyHtml.orEmpty()
                    if (body.isNotBlank()) Text(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString(), style = MaterialTheme.typography.bodyMedium)
                    selected.course?.let { course ->
                        Text("${course.shortname} · ${course.fullname}", style = MaterialTheme.typography.bodyMedium)
                        if (onCourse != null) OutlinedButton({ onCourse(course) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.srch_open_course)) }
                    }
                    selected.assignment?.let { if (it.due > 0) Text(stringResource(R.string.srch_due, dateText(it.due), clockText(it.due))) }
                    selected.notice?.let {
                        Text(stringResource(R.string.srch_periods, it.targetDate, it.lessonStart, it.lessonEnd))
                        if (it.room.isNotBlank()) Text(stringResource(R.string.srch_room, it.room))
                    }
                    Text(stringResource(R.string.srch_saved_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.message?.let { InlineNotice(it, true) }
                }
            }
            val files = (listOfNotNull(selected.file) + if (selected.file == null) selected.module?.files.orEmpty() + selected.assignment?.files.orEmpty() else emptyList()).distinctBy { it.url }
            files.forEach { file ->
                item(key = "download:${file.url}") {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (file.size > 0) stringResource(R.string.srch_size_reported, file.size) else stringResource(R.string.srch_size_missing), style = MaterialTheme.typography.bodySmall)
                        Button({ onDownload(file) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(if (state.busy) R.string.srch_opening else R.string.srch_open_file)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun searchKindLabel(kind: WorkspaceSearchKind): String = stringResource(
    when (kind) {
        WorkspaceSearchKind.COURSES -> R.string.srch_kind_courses
        WorkspaceSearchKind.MATERIALS -> R.string.srch_kind_materials
        WorkspaceSearchKind.ASSIGNMENTS -> R.string.srch_kind_assignments
        WorkspaceSearchKind.NOTICES -> R.string.srch_kind_notices
    }
)
