package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable fun CourseReadingScreen(model: CourseReadingViewModel, onExit: () -> Unit = {}) {
    val state by model.uiState.collectAsStateWithLifecycle()
    // Note drafts and private export bodies must not enter saved-instance-state bundles.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { key(state.session) { CourseReadingContent(state, model, onExit) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseReadingContent(state: CourseReadingState, model: CourseReadingViewModel, onExit: () -> Unit) {
    var jump by remember { mutableStateOf(false) }
    var fitWidthRequest by remember { mutableIntStateOf(0) }
    var placing by remember { mutableStateOf(false) }
    var deletingNote by remember { mutableStateOf<ReadingNote?>(null) }
    var discard by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var deletingRecord by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf(if (!model.exportPickerPending) model.pendingExport else null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exportDone = stringResource(R.string.rd_export_done)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            model.cancelExport()
        } else {
            scope.launch {
                val ok = model.exportTo { body -> context.contentResolver.openOutputStream(uri, "wt")!!.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) } }
                if (ok) snackbar.showSnackbar(exportDone)
            }
        }
    }

    val back: () -> Unit = {
        if (placing) {
            placing = false
        } else if (state.record != null || state.candidate != null) {
            model.libraryFromDocument()
        } else {
            model.leave(onExit)
        }
    }
    BackHandler(onBack = back)
    val scroll = rememberLazyListState()
    LaunchedEffect(state.position.page, state.tab, state.session) {
        scroll.scrollToItem(0)
        placing = false
    }
    Box {
        LazyColumn(Modifier.fillMaxSize().testTag("reading-content"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(back, shape = TechnicalShapes.Control) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Text(stringResource(R.string.rd_back))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.record != null || state.candidate != null) TextButton(model::libraryFromDocument, enabled = !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_library)) }
                }
                val title = state.candidate?.file?.name ?: state.record?.title ?: stringResource(R.string.rd_title)
                Text(title, style = if (state.record == null && state.candidate == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
                Text(state.candidate?.course?.shortname ?: stringResource(R.string.rd_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.image == null) R.string.rd_loading else R.string.rd_rendering))
                }
                state.problem?.let {
                    InlineNotice(readingProblemText(it), true)
                    TextButton(model::retry, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_retry)) }
                }
            }
            if (state.problem in setOf(ReadingProblem.SAVE, ReadingProblem.STALE, ReadingProblem.STORE) && state.candidate != null) item { TextButton({ reload = true }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_reload)) } }
            if (state.problem in setOf(ReadingProblem.SAVE, ReadingProblem.STALE, ReadingProblem.LIMIT) && state.candidate != null) item { TextButton({ discard = true }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_discard_to_library)) } }
            if (state.record == null && state.candidate == null) {
                item {
                    Text(stringResource(R.string.rd_available), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.rd_available_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(model::refresh, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_refresh)) }
                    if (state.catalogLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.rd_verifying))
                    } else if (state.candidates.isEmpty()) {
                        Text(stringResource(R.string.rd_empty))
                    }
                }
                state.candidates.forEach { candidate ->
                    item(key = candidate.id) {
                        Panel(Modifier.fillMaxWidth()) {
                            Text(candidate.file.name, style = MaterialTheme.typography.titleMedium)
                            Text(candidate.course.shortname, fontFamily = CampusMono)
                            Text(stringResource(R.string.rd_owner, state.ownerNames[candidate.course.accountId].orEmpty()), style = MaterialTheme.typography.bodySmall)
                            TextButton({ model.open(candidate.course, candidate.file) }, shape = TechnicalShapes.Control, modifier = Modifier.testTag("reading-open-${candidate.id}")) { Text(stringResource(R.string.rd_open)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.rd_records), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.rd_records_help), style = MaterialTheme.typography.bodySmall)
                    if (state.collections.any { it.failed }) InlineNotice(stringResource(R.string.rd_unreadable_account), true)
                    if (state.collections.flatMap { it.records }.isEmpty()) Text(stringResource(R.string.rd_no_records))
                }
                state.collections.flatMap { it.records }.forEach { record ->
                    item(key = "${record.identity.accountId}:${record.identity.documentId}:${record.identity.version}") {
                        Panel(Modifier.fillMaxWidth()) {
                            Text(record.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.rd_owner, state.ownerNames[record.identity.accountId].orEmpty()), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.rd_version, record.identity.version.take(12)), fontFamily = CampusMono, style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.rd_record_summary, record.position.page, record.pageCount, record.bookmarks.size, record.notes.size))
                            TextButton({ model.review(record) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_review)) }
                        }
                    }
                }
            } else {
                val record = state.record
                if (record != null) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReadingTab.entries.forEach { tab ->
                                val label = when (tab) {
                                    ReadingTab.PAGE -> R.string.rd_page
                                    ReadingTab.BOOKMARKS -> R.string.rd_bookmarks
                                    ReadingTab.NOTES -> R.string.rd_notes
                                }
                                if (!state.archived || tab != ReadingTab.PAGE) FilterChip(state.tab == tab, { model.tab(tab) }, label = { Text(stringResource(label)) }, enabled = !state.loading, shape = TechnicalShapes.Control)
                            }
                        }
                        if (state.archived) InlineNotice(stringResource(R.string.rd_archived))
                        if (state.otherVersions > 0) InlineNotice(stringResource(R.string.rd_other_versions, state.otherVersions))
                    }
                }
                if (state.tab == ReadingTab.PAGE && !state.archived && state.pageCount > 0) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ model.page(state.position.page - 1) }, enabled = !state.loading && !state.saving && state.position.page > 1) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, stringResource(R.string.rd_previous)) }
                            TextButton({ jump = true }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control, modifier = Modifier.weight(1f).testTag("reading-page-number")) { Text(stringResource(R.string.rd_page_count, state.position.page, state.pageCount), fontFamily = CampusMono) }
                            IconButton({ model.page(state.position.page + 1) }, enabled = !state.loading && !state.saving && state.position.page < state.pageCount) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.rd_next)) }
                            IconButton(model::bookmark, enabled = state.editable) { Icon(if (state.position.page in record?.bookmarks.orEmpty()) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, stringResource(if (state.position.page in record?.bookmarks.orEmpty()) R.string.rd_remove_bookmark else R.string.rd_add_bookmark)) }
                        }
                    }
                    state.image?.let { image ->
                        item {
                            val viewportHeight = (LocalConfiguration.current.screenHeightDp * .48f).coerceIn(280f, 560f).dp
                            ReadingPageViewport(image, state.pageCount, state.position, state.currentNotes, placing, !state.loading, fitWidthRequest, { fitWidthRequest = 0 }, model::viewport, { x, y ->
                                model.editNote(x = x, y = y)
                                placing = false
                            }, {
                                model.editNote(it)
                            }, Modifier.height(viewportHeight))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            IconButton({ model.viewport(state.position.copy(zoom = (state.position.zoom - .5f).coerceAtLeast(1f))) }, enabled = !state.loading && state.position.zoom > 1f) { Icon(Icons.Outlined.ZoomOut, stringResource(R.string.rd_zoom_out)) }
                            Text(stringResource(R.string.rd_zoom, (state.position.zoom * 100).toInt()), fontFamily = CampusMono)
                            IconButton({ model.viewport(state.position.copy(zoom = (state.position.zoom + .5f).coerceAtMost(4f))) }, enabled = !state.loading && state.position.zoom < 4f) { Icon(Icons.Outlined.ZoomIn, stringResource(R.string.rd_zoom_in)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({
                                fitWidthRequest++
                                scope.launch { scroll.animateScrollToItem(3) }
                            }, enabled = !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_fit_width)) }
                            TextButton({ model.viewport(ReadingPosition(state.position.page)) }, enabled = !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_fit)) }
                        }
                        Text(stringResource(R.string.rd_gesture_help), style = MaterialTheme.typography.bodySmall)
                        if (placing) InlineNotice(stringResource(R.string.rd_place_pin))
                        OutlinedButton({
                            placing = !placing
                            if (placing) scope.launch { scroll.animateScrollToItem(3) }
                        }, enabled = state.editable, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (placing) R.string.rd_cancel_pin else R.string.rd_add_note)) }
                    }
                }
                if (record != null && state.tab == ReadingTab.BOOKMARKS) {
                    if (record.bookmarks.isEmpty()) item { Text(stringResource(R.string.rd_no_bookmarks)) }
                    record.bookmarks.sorted().forEach { page -> item(key = "bookmark-$page") { OutlinedButton({ model.page(page) }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rd_bookmark_page, page)) } } }
                }
                if (record != null && state.tab == ReadingTab.NOTES) {
                    if (record.notes.isEmpty()) item { Text(stringResource(R.string.rd_no_notes)) }
                    record.notes.sortedWith(compareBy({ it.page }, { it.createdAt })).forEach { value ->
                        item(key = value.id) {
                            Panel(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.rd_note_title, value.page), style = MaterialTheme.typography.titleMedium)
                                Text(value.text, style = MaterialTheme.typography.bodyLarge)
                                FlowRow {
                                    if (!state.archived) TextButton({ model.page(value.page) }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_open_note_page, value.page)) }
                                    if (!state.archived) {
                                        TextButton({
                                            model.editNote(value)
                                        }, enabled = state.editable, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_edit_note)) }
                                    }
                                    TextButton({ deletingNote = value }, enabled = !state.loading && !state.saving, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_delete_note)) }
                                }
                            }
                        }
                    }
                }
                if (record != null) {
                    item {
                        Text(stringResource(R.string.rd_version, record.identity.version.take(12)), style = MaterialTheme.typography.labelMedium, fontFamily = CampusMono)
                        if (!state.archived) {
                            Text(
                                stringResource(
                                    if (state.saving) {
                                        R.string.rd_saving
                                    } else if (state.dirty) {
                                        R.string.rd_unsaved
                                    } else {
                                        R.string.rd_saved
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton({ model.export { record, text -> exportPreview = record to text } }, enabled = !state.saving && !state.loading, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rd_export)) }
                        TextButton({ deletingRecord = true }, enabled = !state.saving && !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_delete_record)) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.rd_discard_to_library)) }, text = { Text(stringResource(R.string.rd_discard_leave_body)) }, dismissButton = { TextButton({ discard = false }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) } }, confirmButton = { TextButton({ model.discardToLibrary() }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_go)) } })
    if (reload) {
        AlertDialog(onDismissRequest = { reload = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.rd_reload)) }, text = { Text(stringResource(R.string.rd_discard_body)) }, dismissButton = { TextButton({ reload = false }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) } }, confirmButton = {
            TextButton({
                reload = false
                model.reopen()
            }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_reload)) }
        })
    }
    if (jump) {
        ReadingPageDialog(state.position.page, state.pageCount, { jump = false }, {
            model.page(it)
            jump = false
        })
    }
    state.draft?.let { draft -> ReadingNoteDialog(draft, state.saving, state.pendingSave, state.problem, model::dismissDraft, model::draftText, model::saveDraft) }
    deletingNote?.let { target -> ReadingConfirm(stringResource(R.string.rd_delete_note), stringResource(R.string.rd_delete_note_body), state.saving, { deletingNote = null }) { model.removeNote(target) { deletingNote = null } } }
    if (deletingRecord) ReadingConfirm(stringResource(R.string.rd_delete_record), stringResource(R.string.rd_delete_record_body), state.saving, { deletingRecord = false }) { model.removeRecord { deletingRecord = false } }
    exportPreview?.let { captured ->
        ReadingDialog(stringResource(R.string.rd_export_title), { exportPreview = null }, actions = {
            TextButton({
                exportPreview = null
                model.cancelExport()
            }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) }
            TextButton({
                if (model.canExport(captured.first)) {
                    model.beginExportPicker()
                    exportPreview = null
                    picker.launch("reading-notes-${captured.first.identity.version.take(12)}.json")
                } else {
                    exportPreview = null
                    model.exportFailed()
                }
            }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_export_file)) }
        }) {
            Text(captured.first.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rd_version, captured.first.identity.version.take(12)), fontFamily = CampusMono)
            Text(stringResource(R.string.rd_record_summary, captured.first.position.page, captured.first.pageCount, captured.first.bookmarks.size, captured.first.notes.size))
            Text(stringResource(R.string.rd_export_help))
        }
    }
}
