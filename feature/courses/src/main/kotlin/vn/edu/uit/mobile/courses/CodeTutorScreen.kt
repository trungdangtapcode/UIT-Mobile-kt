package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable fun CodeTutorScreen(model: CodeTutorViewModel, onExit: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        key(state.session) {
            CodeTutorContent(
                state,
                object : CodeTutorActions by model {
                    override fun close() {
                        model.close()
                        onExit()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeTutorContent(state: CodeTutorState, actions: CodeTutorActions = object : CodeTutorActions {}) {
    var settings by remember { mutableStateOf(false) }
    var imports by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var codeReference by remember { mutableStateOf<TutorCodeReference?>(null) }
    var evidence by remember { mutableStateOf<PracticeEvidence?>(null) }
    var proposal by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(state.result?.selectionVersion, state.problem, state.busy) { if (state.result != null || state.problem != null || state.busy) list.scrollToItem(0) }
    val sourceTitles = state.submitted?.sources?.associate { it.id to it.title }.orEmpty()
    val focus = LocalFocusManager.current
    val back: () -> Unit = {
        if (state.busy) {
            actions.cancel()
        } else if (state.code.isNotEmpty() || state.result != null) {
            leaving = true
        } else {
            actions.close()
        }
    }
    BackHandler(onBack = back)
    fun inspect(citation: StudyCitation) {
        state.submitted?.sources?.firstOrNull { it.id == citation.sourceId }?.let { evidence = PracticeEvidence(it, citation.quote, practiceDigest(listOf(it.id, it.title, it.text))) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("tutor-content"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(back, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.ct_back))
                }
                Spacer(Modifier.weight(1f))
                IconButton({ settings = true }, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.st_settings_desc)) }
            }
            PageHeading(stringResource(R.string.ct_title), state.course?.shortname ?: stringResource(R.string.ct_standalone))
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(actions::cancel, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel_request)) }
            }
            state.problem?.let { InlineNotice(tutorProblem(it), true) }
        }
        item { TutorTabs(state.tab, actions::tab, !state.busy) }
        val result = state.result
        val submitted = state.submitted
        if (result == null || submitted == null) {
            item { TutorEditor(state, actions, { imports = true }, { preview = true }) }
            if (state.course != null) {
                item {
                    Text(stringResource(R.string.ct_context), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.ct_context_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(actions::reload, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_reload)) }
                    if (state.sources.isEmpty()) Text(stringResource(R.string.ct_context_empty))
                }
                state.sources.forEach { source ->
                    item(key = source.id) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(source.id in state.selected, { actions.select(source.id) }, Modifier.testTag("tutor-source-${source.id}"), enabled = !state.busy && source.text.length <= 12_000 && (source.id in state.selected || state.selected.size < 4))
                            Column(Modifier.weight(1f)) {
                                Text(source.title, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.st_chars, source.text.length), style = MaterialTheme.typography.bodySmall)
                                TextButton({ evidence = PracticeEvidence(source, version = practiceDigest(listOf(source.id, source.title, source.text))) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_inspect_context)) }
                            }
                        }
                    }
                }
            }
            item {
                if (!state.connection.configured) Text(stringResource(R.string.ct_unconfigured))
                TextButton({ settings = true }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_configure)) }
                Button({
                    focus.clearFocus()
                    actions.analyze()
                }, Modifier.fillMaxWidth().testTag("tutor-analyze"), enabled = state.canAnalyze, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_analyze)) }
                Text(stringResource(R.string.ct_send_help), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ct_no_execution), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ct_lines, submitted.snippet.firstLine, submitted.snippet.lastLine), style = MaterialTheme.typography.titleMedium)
                    TutorCodePane(submitted.snippet)
                    Text(stringResource(R.string.ct_generated, result.model), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ct_version, result.selectionVersion.take(12)), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ct_review), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton({ preview = true }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_preview)) }
                        TextButton(actions::edit, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_edit)) }
                    }
                    if (result.status == "insufficient_context") Text(stringResource(R.string.ct_need_context), style = MaterialTheme.typography.titleLarge)
                    if (state.tab != TutorTab.HINTS || result.status != "ready") Text(result.summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (result.status == "ready") {
                when (state.tab) {
                    TutorTab.EXPLAIN -> {
                        result.observations.forEachIndexed { index, point -> item(key = "point-$index") { TutorPointPanel(point, { codeReference = it }, ::inspect, sourceTitles) } }
                        if (result.proposedCode.isNotEmpty()) item { OutlinedButton({ proposal = true }, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ct_proposal)) } }
                    }
                    TutorTab.HINTS -> {
                        item { Text(stringResource(R.string.ct_hint_help)) }
                        result.hints.take(state.revealedHints).forEachIndexed { index, hint ->
                            item(key = "hint-$index") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.ct_hint_number, index + 1), style = MaterialTheme.typography.titleMedium)
                                    TutorPointPanel(hint, { codeReference = it }, ::inspect, sourceTitles)
                                }
                            }
                        }
                        item {
                            if (state.revealedHints < 3) {
                                Button(actions::reveal, enabled = !state.busy, shape = TechnicalShapes.Control, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ct_reveal_hint, state.revealedHints + 1)) }
                            } else {
                                Text(stringResource(R.string.ct_hints_done))
                            }
                        }
                    }
                    TutorTab.TESTS -> {
                        item { Text(stringResource(R.string.ct_tests_help)) }
                        result.tests.forEachIndexed { index, test ->
                            item(key = "test-$index") {
                                Panel(Modifier.fillMaxWidth()) {
                                    Text(test.name, style = MaterialTheme.typography.titleLarge)
                                    Text(stringResource(R.string.ct_input), style = MaterialTheme.typography.labelLarge)
                                    Text(test.input, fontFamily = CampusMono)
                                    Text(stringResource(R.string.ct_expected), style = MaterialTheme.typography.labelLarge)
                                    Text(test.expected, fontFamily = CampusMono)
                                    Text(stringResource(R.string.ct_reason), style = MaterialTheme.typography.labelLarge)
                                    Text(test.reason)
                                    TutorReferenceActions(test.codeReferences) { codeReference = it }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings) StudyConnectionDialog(state.connection, { settings = false }, { mode, endpoint, secret, model -> actions.configure(mode, endpoint, secret, model) { settings = false } }, { actions.disconnect { settings = false } }, state.problem?.let { tutorProblem(it) }, state.busy)
    if (imports) {
        TutorImportsDialog(state.catalog, {
            actions.use(it)
            imports = false
        }, { imports = false })
    }
    val selected = state.submitted?.snippet ?: state.request?.snippet
    if (preview && selected != null) TutorCodeEvidenceDialog(selected, null) { preview = false }
    codeReference?.let { reference -> if (selected != null) TutorCodeEvidenceDialog(selected, reference) { codeReference = null } }
    evidence?.let { PracticeEvidenceDialog(it) { evidence = null } }
    if (proposal && state.result != null && state.submitted != null) TutorProposalDialog(state.submitted, state.result) { proposal = false }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            shape = TechnicalShapes.Dialog,
            title = { Text(stringResource(R.string.ct_leave_title)) },
            text = { Text(stringResource(R.string.ct_leave_body)) },
            dismissButton = { TextButton({ leaving = false }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_keep)) } },
            confirmButton = { TextButton(actions::close, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_leave)) } }
        )
    }
}

@Composable private fun TutorTabs(selected: TutorTab, onSelect: (TutorTab) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TutorTab.entries.forEach { tab ->
            val label = when (tab) {
                TutorTab.EXPLAIN -> R.string.ct_explain
                TutorTab.HINTS -> R.string.ct_hints
                TutorTab.TESTS -> R.string.ct_tests
            }
            FilterChip(selected == tab, { onSelect(tab) }, label = { Text(stringResource(label)) }, enabled = enabled, shape = TechnicalShapes.Control)
        }
    }
}

@Composable private fun TutorEditor(state: CodeTutorState, actions: CodeTutorActions, onImport: () -> Unit, onPreview: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ct_editor), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ct_editor_help), style = MaterialTheme.typography.bodySmall)
        if (state.course != null) OutlinedButton(onImport, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_import)) }
        state.imported?.let { source ->
            Text(source.title, style = MaterialTheme.typography.bodySmall)
            source.cell?.let { Text(stringResource(R.string.ct_cell, it), style = MaterialTheme.typography.labelLarge) }
            Text(stringResource(R.string.ct_import_copy, source.fileVersion.take(12)), style = MaterialTheme.typography.labelMedium)
        }
        Text(stringResource(R.string.ct_code_language), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TutorRules.codeLanguages.forEach { language -> FilterChip(state.codeLanguage == language, { actions.codeLanguage(language) }, label = { Text(if (language == "Other") stringResource(R.string.ct_other_language) else language) }, enabled = !state.busy, shape = TechnicalShapes.Control) }
        }
        OutlinedTextField(state.code, actions::code, Modifier.fillMaxWidth().testTag("tutor-editor"), enabled = !state.busy, label = { Text(stringResource(R.string.ct_code_label)) }, minLines = 6, maxLines = 12, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = CampusMono), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), shape = TechnicalShapes.Field)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.from, { actions.range(it, state.to) }, Modifier.weight(1f), enabled = !state.busy, singleLine = true, label = { Text(stringResource(R.string.ct_from)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Field)
            OutlinedTextField(state.to, { actions.range(state.from, it) }, Modifier.weight(1f), enabled = !state.busy, singleLine = true, label = { Text(stringResource(R.string.ct_to)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TechnicalShapes.Field)
        }
        Text(stringResource(R.string.ct_limits), style = MaterialTheme.typography.bodySmall)
        if (state.code.isNotBlank() && state.request == null) Text(stringResource(R.string.ct_input_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onPreview, enabled = state.request != null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_preview)) }
        OutlinedTextField(state.question, actions::question, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(stringResource(R.string.ct_question)) }, shape = TechnicalShapes.Field)
        OutlinedTextField(state.environment, actions::environment, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(stringResource(R.string.ct_environment)) }, shape = TechnicalShapes.Field)
        Text(stringResource(R.string.ct_output_language), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("English" to R.string.st_language_en, "Vietnamese" to R.string.st_language_vi).forEach { (language, label) -> FilterChip(state.language == language, { actions.language(language) }, label = { Text(stringResource(label)) }, enabled = !state.busy, shape = TechnicalShapes.Control) }
        }
    }
}

@Composable private fun TutorPointPanel(point: TutorPoint, onCode: (TutorCodeReference) -> Unit, onSource: (StudyCitation) -> Unit, sourceTitles: Map<String, String>) {
    Panel(Modifier.fillMaxWidth()) {
        val kind = when (point.kind) {
            "code" -> R.string.ct_kind_code
            "course" -> R.string.ct_kind_course
            "suggestion" -> R.string.ct_kind_suggestion
            else -> R.string.ct_kind_unknown
        }
        Text(stringResource(kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(point.text, style = MaterialTheme.typography.bodyLarge)
        TutorReferenceActions(point.codeReferences, onCode)
        point.citations.forEach { c -> TextButton({ onSource(c) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_course_reference, sourceTitles[c.sourceId] ?: c.sourceId)) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TutorReferenceActions(references: List<TutorCodeReference>, onSelect: (TutorCodeReference) -> Unit) {
    FlowRow {
        references.forEach { ref -> TextButton({ onSelect(ref) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_reference, ref.startLine, ref.endLine)) } }
    }
}

@Composable private fun tutorProblem(problem: TutorProblem) = stringResource(
    when (problem) {
        TutorProblem.INPUT -> R.string.ct_input_error
        TutorProblem.LOAD -> R.string.ct_load_error
        TutorProblem.GENERATE -> R.string.ct_generate_error
        TutorProblem.CONNECTION -> R.string.ct_connection_error
        TutorProblem.CHANGED -> R.string.ct_changed
        TutorProblem.REMOVED -> R.string.ct_removed
        TutorProblem.CANCELED -> R.string.ct_canceled
    }
)
