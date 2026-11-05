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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    }, vm::select, vm::toggle, vm::query, vm::mode, vm::build, vm::search, vm::inspect, vm::dismissInspection, vm::clear, vm::stop, vm::refresh, vm::configure, vm::disconnect, onOpenPdf, onReset = vm::reset)
}

@Composable fun PassageSearchContent(
    state: PassageSearchState,
    onClose: () -> Unit = {},
    onCourse: (Course?) -> Unit = {},
    onToggle: (String) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onMode: (PassageSearchMode) -> Unit = {},
    onBuild: (PassageConfig, PassageEmbeddingConfig?) -> Unit = { _, _ -> },
    onSearch: () -> Unit = {},
    onInspect: (PassageHit) -> Unit = {},
    onDismissInspection: () -> Unit = {},
    onClear: () -> Unit = {},
    onStop: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onConfigure: (StudyConnectionMode, String, String, String) -> Unit = { _, _, _, _ -> },
    onDisconnect: () -> Unit = {},
    onOpenPdf: ((Course, CourseFile, String) -> Unit)? = null,
    onReset: () -> Unit = {}
) {
    BackHandler(onBack = onClose)
    var manage by rememberSaveable(state.course?.id, state.course?.accountId) { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val busy = state.work != null
    val index = state.index
    val scroll = rememberLazyListState()
    LaunchedEffect(state.result) {
        state.result?.let { result ->
            val before = 4 + (if (state.problem != null || state.library.storageFailed) 1 else 0) + (if (busy) 1 else 0) + (if (result.actual != result.requested) 1 else 0) + (if (result.invalidated > 0) 1 else 0)
            scroll.animateScrollToItem(if (index != null) before else 2 + (if (state.problem != null || state.library.storageFailed) 1 else 0) + (if (busy) 1 else 0))
        }
    }
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
                            Text(stringResource(R.string.ps_coverage, index.documents.size, index.passages.size), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.ps_local), style = MaterialTheme.typography.bodySmall)
                            if (index.vectors.isNotEmpty()) Text(stringResource(R.string.ps_embedding_info, index.embedding?.model.orEmpty(), index.embedding?.dimensions ?: 0, index.embeddingTokens), style = MaterialTheme.typography.bodySmall)
                            if (index.limited || index.documents.any { it.partial }) Text(stringResource(R.string.ps_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            if (index.semanticFailed) InlineNotice(stringResource(R.string.ps_embedding_failed))
                            TextButton({ deletion = true }, enabled = !busy) { Text(stringResource(R.string.ps_delete)) }
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
    if (manage) {
        PassageManageDialog(state, onToggle, { manage = false }, { config, embedding ->
            manage = false
            onBuild(config, embedding)
        }, onRefresh)
    }
    if (deletion) {
        AlertDialog(onDismissRequest = { deletion = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.ps_delete)) }, text = { Text(stringResource(R.string.ps_delete_help)) }, confirmButton = {
            TextButton({
                deletion = false
                onClear()
            }) { Text(stringResource(R.string.ps_delete)) }
        }, dismissButton = { TextButton({ deletion = false }) { Text(stringResource(R.string.ps_cancel)) } })
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

@Composable private fun PassageManageDialog(state: PassageSearchState, onToggle: (String) -> Unit, onDismiss: () -> Unit, onBuild: (PassageConfig, PassageEmbeddingConfig?) -> Unit, onRefresh: () -> Unit) {
    val initial = state.index?.config ?: PassageConfig()
    var semantic by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(initial.characters.toString()) }
    var overlap by remember { mutableStateOf(initial.overlap.toString()) }
    var k1 by remember { mutableStateOf(initial.k1.toString()) }
    var b by remember { mutableStateOf(initial.b.toString()) }
    var candidates by remember { mutableStateOf(initial.candidates.toString()) }
    var fusion by remember { mutableStateOf(initial.fusionK.toString()) }
    var model by remember { mutableStateOf(state.index?.embedding?.model ?: PassageEmbeddingConfig().model) }
    var dimensions by remember { mutableStateOf((state.index?.embedding?.dimensions ?: 512).toString()) }
    val config = runCatching { PassageConfig(size.toInt(), overlap.toInt(), k1.toDouble(), b.toDouble(), candidates.toInt(), fusion.toInt()).also { it.validate() } }.getOrNull()
    val embedding = runCatching { PassageEmbeddingConfig(model, dimensions.toInt()).also { it.validate() } }.getOrNull()
    PassageDialog(onDismiss, footer = {
        Button({ onBuild(requireNotNull(config), if (semantic) requireNotNull(embedding) else null) }, Modifier.fillMaxWidth().testTag("passage-build"), enabled = config != null && state.selected.isNotEmpty() && (!semantic || embedding != null && state.connection.configured && state.connection.mode == StudyConnectionMode.DIRECT), shape = TechnicalShapes.Control) { Text(stringResource(if (semantic) R.string.ps_build_remote else R.string.ps_build_local)) }
        TextButton(onDismiss) { Text(stringResource(R.string.ps_cancel)) }
    }) {
        Text(stringResource(R.string.ps_documents), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ps_selection_help, PassageRules.DOCUMENTS, PassageRules.CATALOG), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.ps_selected, state.selected.size), style = MaterialTheme.typography.labelLarge)
        state.candidates.forEach { candidate ->
            Row {
                Checkbox(candidate.spec.id in state.selected, { onToggle(candidate.spec.id) }, enabled = candidate.downloaded && (candidate.spec.id in state.selected || state.selected.size < PassageRules.DOCUMENTS), modifier = Modifier.testTag("passage-source-${candidate.spec.id}").semantics { contentDescription = candidate.spec.file.name })
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(candidate.spec.file.name, style = MaterialTheme.typography.bodyMedium)
                    Text(if (candidate.downloaded) candidate.spec.sectionName else stringResource(R.string.ps_download_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.candidates.isEmpty()) Text(stringResource(R.string.ps_no_documents))
        TextButton({
            onDismiss()
            onRefresh()
        }) { Text(stringResource(R.string.ps_refresh)) }
        val semanticLabel = stringResource(R.string.ps_enable_semantic)
        Row {
            Checkbox(semantic, { semantic = it }, Modifier.testTag("passage-enable-semantic").semantics { contentDescription = semanticLabel })
            Text(stringResource(R.string.ps_enable_semantic), Modifier.padding(top = 12.dp).weight(1f))
        }
        if (semantic) {
            Text(stringResource(R.string.ps_transfer, PassageRules.PASSAGES), color = MaterialTheme.colorScheme.tertiary)
            if (!state.connection.configured || state.connection.mode != StudyConnectionMode.DIRECT) Text(stringResource(R.string.ps_direct_required), color = MaterialTheme.colorScheme.error)
        }
        TextButton({ advanced = !advanced }) { Text(stringResource(R.string.ps_advanced)) }
        if (advanced) {
            Text(stringResource(R.string.ps_parameters_help), style = MaterialTheme.typography.bodySmall)
            listOf(Triple(R.string.ps_chunk, size, { v: String -> size = v }), Triple(R.string.ps_overlap, overlap, { v: String -> overlap = v }), Triple(R.string.ps_k1, k1, { v: String -> k1 = v }), Triple(R.string.ps_b, b, { v: String -> b = v }), Triple(R.string.ps_candidates, candidates, { v: String -> candidates = v }), Triple(R.string.ps_rrf, fusion, { v: String -> fusion = v })).forEach { (label, value, change) -> OutlinedTextField(value, { change(it.take(10)) }, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = TechnicalShapes.Field) }
            if (semantic) {
                OutlinedTextField(model, { model = it.take(100) }, label = { Text(stringResource(R.string.ps_embedding_model)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = TechnicalShapes.Field)
                OutlinedTextField(dimensions, { dimensions = it.take(4) }, label = { Text(stringResource(R.string.ps_dimensions)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = TechnicalShapes.Field)
            }
        }
        if (config == null || semantic && embedding == null) Text(stringResource(R.string.ps_invalid_parameters), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun PassageDialog(onDismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
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
