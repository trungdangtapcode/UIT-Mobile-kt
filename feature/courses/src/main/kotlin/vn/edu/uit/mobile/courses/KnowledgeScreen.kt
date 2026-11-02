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
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable fun KnowledgeScreen(model: KnowledgeViewModel, onExit: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { KnowledgeContent(state, model, onExit) }
}

@Composable fun KnowledgeContent(state: KnowledgeState, model: KnowledgeViewModel, onExit: () -> Unit) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val choose: (String) -> Unit = { id ->
        model.choose(id)
        scope.launch { scroll.scrollToItem(2) }
    }
    var settings by remember { mutableStateOf(false) }
    var excerpt by remember { mutableStateOf(false) }
    var evidence by remember { mutableStateOf<Pair<StudySource, String?>?>(null) }
    var editNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    var editEdge by remember { mutableStateOf<KnowledgeEdge?>(null) }
    var exit by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KnowledgeRecord?>(null) }
    val close = {
        model.close()
        onExit()
    }
    val back: () -> Unit = {
        if (!state.busy) {
            if (state.dirty) {
                exit = true
            } else if (state.stage == KnowledgeStage.LIBRARY) {
                close()
            } else {
                model.bank()
            }
        }
    }
    BackHandler(onBack = back)
    // Removed owners must also clear private dialog copies, not only the ViewModel.
    LaunchedEffect(state.record?.accountId, state.course?.accountId, state.problem) {
        if (state.problem == KnowledgeProblem.REMOVED) {
            evidence = null
            editNode = null
            editEdge = null
            excerpt = false
            deleting = null
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("knowledge-content"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(back, enabled = !state.busy, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.st_back))
                }
                Spacer(Modifier.weight(1f))
                if (state.stage == KnowledgeStage.SELECT) IconButton({ settings = true }, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.st_settings_desc)) }
            }
            PageHeading(state.record?.graph?.title ?: stringResource(R.string.km_title), state.course?.shortname ?: stringResource(R.string.km_subtitle))
        }
        if (state.busy) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.km_working))
            }
        }
        state.problem?.let { problem ->
            item {
                InlineNotice(
                    stringResource(
                        when (problem) {
                            KnowledgeProblem.INPUT -> R.string.km_input_error
                            KnowledgeProblem.GENERATE -> R.string.km_generate_error
                            KnowledgeProblem.SAVE -> R.string.km_save_error
                            KnowledgeProblem.REMOVED -> R.string.km_removed
                            KnowledgeProblem.CHANGED -> R.string.km_changed
                            KnowledgeProblem.CANCELED -> R.string.km_canceled
                            else -> R.string.km_load_error
                        }
                    ),
                    true
                )
            }
        }
        if (state.message.isNotBlank()) item { InlineNotice(state.message) }
        when (state.stage) {
            KnowledgeStage.LIBRARY -> {
                item { SectionHeading(stringResource(R.string.km_create), stringResource(R.string.km_choose_course)) }
                if (state.courses.isEmpty()) item { EmptyState(stringResource(R.string.km_no_course), stringResource(R.string.km_no_course_help)) }
                items(state.courses, key = { "course:${it.accountId}:${it.id}" }) { course ->
                    OutlinedButton({ model.start(course) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Panel) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(course.fullname)
                            Text(listOf(course.shortname, state.ownerNames[course.accountId].orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { SectionHeading(stringResource(R.string.km_saved), stringResource(R.string.km_offline)) }
                if (state.collections.any { it.failed }) item { InlineNotice(stringResource(R.string.km_load_error), true) }
                if (state.collections.all { it.records.isEmpty() }) item { Text(stringResource(R.string.km_no_saved)) }
                items(state.collections.flatMap { it.records }, key = { "record:${it.accountId}:${it.id}" }) { record ->
                    Panel {
                        Text(record.graph.title, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(record.courseName, state.ownerNames[record.accountId].orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.km_summary, record.graph.nodes.size, record.graph.edges.size), style = MaterialTheme.typography.bodySmall)
                        TextButton({ model.show(record) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.km_open)) }
                        TextButton({ deleting = record }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.km_delete)) }
                    }
                }
            }
            KnowledgeStage.SELECT -> {
                item {
                    Text(stringResource(R.string.km_selection_help), style = MaterialTheme.typography.bodyMedium)
                    TextButton(model::reload, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.km_reload)) }
                    OutlinedButton({ excerpt = true }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_add_excerpt)) }
                }
                items(state.sources, key = { it.id }) { source ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(source.id in state.selected, { model.select(source.id) }, enabled = !state.busy && source.text.length <= 12000, modifier = Modifier.semantics { contentDescription = source.title })
                            Text(source.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(R.string.st_chars, source.text.length), style = MaterialTheme.typography.bodySmall)
                        TextButton({ evidence = source to null }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_review)) }
                    }
                }
                item {
                    OutlinedTextField(state.focus, model::focus, Modifier.fillMaxWidth().testTag("knowledge-focus"), enabled = !state.busy, label = { Text(stringResource(R.string.km_focus)) }, minLines = 2, shape = TechnicalShapes.Field)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("English" to R.string.st_language_en, "Vietnamese" to R.string.st_language_vi).forEach { (value, label) -> FilterChip(state.language == value, { model.language(value) }, enabled = !state.busy, shape = TechnicalShapes.Control, label = { Text(stringResource(label)) }) }
                    }
                    Text(stringResource(R.string.km_send_help), style = MaterialTheme.typography.bodySmall)
                    TextButton({ settings = true }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_configure)) }
                    Button(model::generate, enabled = !state.busy && state.loaded && !state.sourceChanged && state.connection.configured && runCatching { KnowledgeRules.request(state.sources.filter { it.id in state.selected }, state.focus, state.language) }.isSuccess, modifier = Modifier.fillMaxWidth().testTag("knowledge-generate"), shape = TechnicalShapes.Control) { Text(stringResource(R.string.km_generate)) }
                    if (state.busy) TextButton(model::cancel, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel_request)) }
                }
            }
            KnowledgeStage.MAP -> state.record?.let { record ->
                item {
                    Text(record.graph.focus, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (state.dirty) R.string.km_draft else R.string.km_saved_state), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Button(model::save, enabled = !state.busy && state.dirty, modifier = Modifier.fillMaxWidth().testTag("knowledge-save"), shape = TechnicalShapes.Control) { Text(stringResource(if (state.pendingSave) R.string.km_retry_save else R.string.km_save)) }
                }
                item {
                    SectionHeading(stringResource(R.string.km_concepts), stringResource(R.string.km_expand_help))
                    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("knowledge-index"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        record.graph.nodes.forEach { node -> FilterChip(state.focused == node.id, { choose(node.id) }, shape = TechnicalShapes.Control, label = { Text(node.label) }, modifier = Modifier.testTag("knowledge-index-${node.id}")) }
                    }
                }
                record.graph.nodes.firstOrNull { it.id == state.focused }?.let { node ->
                    item {
                        Panel(Modifier.testTag("knowledge-selected")) {
                            Text(node.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text(node.definition, style = MaterialTheme.typography.bodyLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ evidence = record.sources.first { it.id == node.citations.first().sourceId } to node.citations.first().quote }, shape = TechnicalShapes.Control, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.km_evidence)) }
                                TextButton({ editNode = node }, enabled = !state.busy && !state.pendingSave, shape = TechnicalShapes.Control, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.km_edit_concept)) }
                            }
                        }
                    }
                    items(record.graph.edges.filter { it.from == node.id || it.to == node.id }, key = { it.id }) { edge ->
                        val from = record.graph.nodes.first { it.id == edge.from }
                        val to = record.graph.nodes.first { it.id == edge.to }
                        val neighbor = if (edge.from == node.id) to else from
                        val color = MaterialTheme.colorScheme.primary
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("knowledge-edge-${edge.id}")) {
                            Canvas(Modifier.width(28.dp).fillMaxHeight().clearAndSetSemantics {}) {
                                val x = 8.dp.toPx()
                                val y = size.height / 2
                                drawLine(color.copy(alpha = .5f), Offset(x, -16.dp.toPx()), Offset(x, size.height), 2.dp.toPx())
                                drawLine(color, Offset(x, y), Offset(size.width, y), 2.dp.toPx())
                                val tip = if (edge.from == node.id) size.width else x
                                val tail = tip + if (edge.from == node.id) -5.dp.toPx() else 5.dp.toPx()
                                drawLine(color, Offset(tail, y - 4.dp.toPx()), Offset(tip, y), 2.dp.toPx())
                                drawLine(color, Offset(tail, y + 4.dp.toPx()), Offset(tip, y), 2.dp.toPx())
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(if (edge.from == node.id) R.string.km_outgoing else R.string.km_incoming), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton({ editEdge = edge }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text("${from.label} → ${edge.relation} → ${to.label}", style = MaterialTheme.typography.bodyMedium) }
                                OutlinedButton({ choose(neighbor.id) }, modifier = Modifier.fillMaxWidth().testTag("knowledge-neighbor-${neighbor.id}"), shape = TechnicalShapes.Panel) { Text(neighbor.label, Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.km_review_help), style = MaterialTheme.typography.bodySmall)
                    SectionHeading(stringResource(R.string.km_all_relations))
                    record.graph.edges.forEach { edge ->
                        val from = record.graph.nodes.first { it.id == edge.from }.label
                        val to = record.graph.nodes.first { it.id == edge.to }.label
                        TextButton({ editEdge = edge }, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text("$from → ${edge.relation} → $to") }
                    }
                    Text(stringResource(R.string.km_provenance, record.model, record.sourceVersion.take(12)), style = MaterialTheme.typography.bodySmall)
                    if (!state.dirty) TextButton({ deleting = record }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.km_delete)) }
                }
            }
        }
    }
    if (settings) StudyConnectionDialog(state.connection, { if (!state.busy) settings = false }, { mode, endpoint, secret, name -> model.configure(mode, endpoint, secret, name) { settings = false } }, { model.disconnect { settings = false } }, state.problem?.let { stringResource(R.string.km_connection_error) }, state.busy)
    if (excerpt) {
        StudyExcerptDialog({ excerpt = false }, { title, text ->
            model.excerpt(title, text)
            excerpt = false
        })
    }
    evidence?.let { (source, quote) -> PracticeEvidenceDialog(PracticeEvidence(source, quote, practiceDigest(listOf(source.id, source.title, source.text)))) { evidence = null } }
    state.record?.let { record ->
        editNode?.let { node -> KnowledgeEditor(record, node, null, !state.busy && !state.pendingSave, { editNode = null }, { graph -> if (model.edit(graph)) editNode = null }) }
        editEdge?.let { edge -> KnowledgeEditor(record, null, edge, !state.busy && !state.pendingSave, { editEdge = null }, { graph -> if (model.edit(graph)) editEdge = null }) }
    }
    if (exit) {
        AlertDialog(onDismissRequest = { exit = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.km_leave)) }, text = { Text(stringResource(R.string.km_leave_help)) }, confirmButton = {
            TextButton({
                exit = false
                model.bank()
            }) { Text(stringResource(R.string.st_discard)) }
        }, dismissButton = { TextButton({ exit = false }) { Text(stringResource(R.string.st_keep_editing)) } })
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.km_delete)) }, text = { Text(stringResource(R.string.km_delete_help)) }, confirmButton = { TextButton({ model.remove(record) { deleting = null } }, enabled = !state.busy) { Text(stringResource(R.string.km_delete_confirm)) } }, dismissButton = { TextButton({ deleting = null }, enabled = !state.busy) { Text(stringResource(R.string.st_cancel)) } }) }
}
