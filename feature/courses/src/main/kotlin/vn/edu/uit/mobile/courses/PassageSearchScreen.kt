package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R
import vn.edu.uit.mobile.model.*

@Composable fun PassageSearchScreen(vm: PassageSearchViewModel, onClose: () -> Unit, onOpenPdf: ((Course, CourseFile, String) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.stop() } }
    PassageSearchContent(state, {
        vm.close()
        onClose()
    }, vm::select, vm::toggle, vm::query, vm::mode, vm::search, vm::inspect, vm::dismissInspection, vm::clear, vm::stop, vm::refresh, vm::configure, vm::disconnect, onOpenPdf, onReset = vm::reset, onCheck = vm::check, onUpdate = vm::update, onDismissPlan = vm::dismissPlan)
}

@Composable fun PassageSearchContent(
    state: PassageSearchState,
    onClose: () -> Unit = {},
    onCourse: (Course?) -> Unit = {},
    onToggle: (String) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onMode: (PassageSearchMode) -> Unit = {},
    onSearch: () -> Unit = {},
    onInspect: (PassageHit) -> Unit = {},
    onDismissInspection: () -> Unit = {},
    onClear: () -> Unit = {},
    onStop: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onConfigure: (StudyConnectionMode, String, String, String) -> Unit = { _, _, _, _ -> },
    onDisconnect: () -> Unit = {},
    onOpenPdf: ((Course, CourseFile, String) -> Unit)? = null,
    onReset: () -> Unit = {},
    onCheck: (PassageConfig, PassageEmbeddingConfig?) -> Unit = { _, _ -> },
    onUpdate: () -> Unit = {},
    onDismissPlan: () -> Unit = {}
) {
    BackHandler(onBack = onClose)
    var manage by rememberSaveable(state.course?.id, state.course?.accountId) { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val busy = state.work != null
    val index = state.index
    if (manage && state.course != null) {
        PassageDocumentsScreen(state, { manage = false }, onToggle, onCheck, onUpdate, onDismissPlan, onStop, onRefresh, onClear, onConfigure, onDisconnect)
        return
    }
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().testTag("passage-search"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClose, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.ps_back))
                }
                IconButton({ connection = true }, enabled = !busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.ps_connection)) }
            }
            PageHeading(stringResource(R.string.ps_title), stringResource(R.string.ps_intro))
        }
        if (state.problem != null || state.library.storageFailed) {
            item {
                InlineNotice(stringResource(if (state.library.storageFailed) R.string.ps_storage_error else R.string.ps_error), true)
                if (state.library.storageFailed) TextButton({ reset = true }) { Text(stringResource(R.string.ps_reset)) }
            }
        }
        if (state.course == null) {
            item { Text(stringResource(R.string.ps_choose_course), style = MaterialTheme.typography.titleMedium) }
            if (state.courses.isEmpty()) item { EmptyState(stringResource(R.string.ps_no_courses), stringResource(R.string.ps_no_courses_help)) }
            items(state.courses, key = { "${it.accountId}:${it.id}" }) { course ->
                OutlinedButton({ onCourse(course) }, Modifier.fillMaxWidth(), shape = TechnicalShapes.Control) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(course.shortname)
                        Text(course.fullname, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ps_owner, state.courses.map { it.accountId }.distinct().indexOf(course.accountId) + 1), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            item {
                Text(state.course.shortname, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(state.course.fullname, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ onCourse(null) }, enabled = !busy) { Text(stringResource(R.string.ps_change_course)) }
                    TextButton({ manage = true }, enabled = !busy, modifier = Modifier.testTag("passage-manage")) { Text(stringResource(R.string.ps_documents)) }
                }
            }
            if (busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                when (state.work) {
                                    PassageWork.BUILD -> R.string.ps_building
                                    PassageWork.SEARCH -> R.string.ps_searching
                                    PassageWork.INSPECT -> R.string.ps_checking
                                    else -> R.string.ps_loading
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (state.work == PassageWork.BUILD && state.total > 0) Text(stringResource(R.string.ps_progress, state.completed, state.total), style = MaterialTheme.typography.bodySmall)
                        TextButton(onStop) { Text(stringResource(R.string.ps_stop)) }
                    }
                }
            }
            val invalidated = state.result?.invalidated ?: 0
            if (invalidated > 0) item { InlineNotice(stringResource(R.string.ps_invalidated, invalidated)) }
            if (index == null) {
                item {
                    EmptyState(stringResource(R.string.ps_not_indexed), stringResource(R.string.ps_not_indexed_help))
                    Button({ manage = true }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ps_documents)) }
                }
            } else {
                item {
                    Surface(shape = TechnicalShapes.Panel, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ps_search_ready, index.documents.size), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (index.vectors.isNotEmpty()) R.string.ps_ready_smart_help else R.string.ps_ready_local_help), style = MaterialTheme.typography.bodySmall)
                            TextButton({ manage = true }, enabled = !busy) { Text(stringResource(R.string.ps_check_status)) }
                            if (index.semanticFailed) InlineNotice(stringResource(R.string.ps_embedding_failed))
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PassageSearchMode.entries.forEach { mode -> FilterChip(state.mode == mode, { onMode(mode) }, enabled = !busy && (mode == PassageSearchMode.BM25 || index.vectors.isNotEmpty()), label = { Text(modeLabel(mode)) }, shape = TechnicalShapes.Control, modifier = Modifier.testTag("passage-mode-${mode.name}")) }
                    }
                    OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth().testTag("passage-query"), label = { Text(stringResource(R.string.ps_query)) }, enabled = !busy, maxLines = 3, shape = TechnicalShapes.Field)
                    Text(stringResource(if (state.mode == PassageSearchMode.BM25) R.string.ps_query_local else R.string.ps_query_remote), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onSearch, Modifier.fillMaxWidth().testTag("passage-submit"), enabled = !busy && runCatching { PassageRules.query(state.query) }.isSuccess, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ps_search)) }
                }
                state.result?.let { result ->
                    if (result.actual != result.requested) item { InlineNotice(stringResource(R.string.ps_fallback)) }
                    item(key = "passage-results-heading") { Text(stringResource(R.string.ps_results, result.hits.size, modeLabel(result.actual)), style = MaterialTheme.typography.titleMedium) }
                    if (result.hits.isEmpty()) item { EmptyState(stringResource(R.string.ps_none), stringResource(R.string.ps_none_help)) }
                    items(result.hits, key = { it.passage.id }) { hit ->
                        Panel(Modifier.fillMaxWidth()) {
                            Text(hit.document.spec.file.name, style = MaterialTheme.typography.titleMedium)
                            Text(locationLabel(hit.passage), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(hit.passage.text, style = MaterialTheme.typography.bodyMedium, fontFamily = if (hit.passage.kind == PassagePartKind.NOTEBOOK_CODE) FontFamily.Monospace else null, maxLines = 7, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            TextButton({ onInspect(hit) }, enabled = !busy, modifier = Modifier.testTag("passage-inspect-${hit.passage.id}")) { Text(stringResource(R.string.ps_inspect)) }
                        }
                    }
                }
            }
        }
    }
    if (reset) {
        AlertDialog(onDismissRequest = { reset = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.ps_reset)) }, text = { Text(stringResource(R.string.ps_reset_help)) }, confirmButton = {
            TextButton({
                reset = false
                onReset()
            }) { Text(stringResource(R.string.ps_reset)) }
        }, dismissButton = { TextButton({ reset = false }) { Text(stringResource(R.string.ps_cancel)) } })
    }
    if (connection) {
        StudyConnectionDialog(state.connection, { connection = false }, { mode, endpoint, token, model ->
            connection = false
            onConfigure(mode, endpoint, token, model)
        }, {
            onDisconnect()
            connection = false
        }, if (state.problem == PassageWork.CONNECTION) stringResource(R.string.ps_error) else null, state.work == PassageWork.CONNECTION)
    }
    state.inspection?.let { source ->
        val p = source.hit.passage
        PassageDialog(onDismissInspection, footer = {
            if (p.kind == PassagePartKind.PDF_EXTRACT && onOpenPdf != null) {
                TextButton({
                    onDismissInspection()
                    onOpenPdf(source.hit.document.spec.course, source.hit.document.spec.file, source.hit.document.version)
                }) { Text(stringResource(R.string.ps_open_pdf)) }
            }
            TextButton(onDismissInspection) { Text(stringResource(R.string.ps_close)) }
        }) {
            Text(source.hit.document.spec.file.name, style = MaterialTheme.typography.titleLarge)
            Text(locationLabel(p), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.ps_version, source.hit.document.version.take(12)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ps_surrounding), style = MaterialTheme.typography.titleSmall)
            val text = source.part.text
            val start = maxOf(0, p.start - 400).let { if (it > 0 && text[it].isLowSurrogate()) it - 1 else it }
            val end = minOf(text.length, p.end + 400).let { if (it < text.length && text[it - 1].isHighSurrogate()) it + 1 else it }
            Text(
                buildAnnotatedString {
                    append(text.substring(start, p.start))
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(p.text) }
                    append(text.substring(p.end, end))
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (p.kind == PassagePartKind.NOTEBOOK_CODE) FontFamily.Monospace else null
            )
        }
    }
}

@Composable internal fun PassageDialog(onDismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 680.dp), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
                footer()
            }
        }
    }
}

@Composable private fun modeLabel(mode: PassageSearchMode): String = stringResource(
    when (mode) {
        PassageSearchMode.BM25 -> R.string.ps_bm25
        PassageSearchMode.SEMANTIC -> R.string.ps_semantic
        PassageSearchMode.HYBRID -> R.string.ps_hybrid
    }
)

@Composable private fun locationLabel(p: CoursePassage): String = if (p.kind in setOf(PassagePartKind.NOTEBOOK_CODE, PassagePartKind.NOTEBOOK_MARKDOWN)) stringResource(R.string.ps_cell_lines, p.ordinal, p.firstLine, p.lastLine) else stringResource(if (p.kind == PassagePartKind.PDF_EXTRACT) R.string.ps_pdf_lines else R.string.ps_text_lines, p.firstLine, p.lastLine)
