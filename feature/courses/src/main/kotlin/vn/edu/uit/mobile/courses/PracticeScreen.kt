package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable fun PracticeScreen(model: PracticeViewModel, onExit: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    key(state.session) {
        PracticeContent(
            state,
            object : PracticeActions by model {
                override fun close() {
                    model.close()
                    onExit()
                }
            }
        )
    }
}
internal data class PracticeEvidence(val source: StudySource, val quote: String? = null, val version: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeContent(state: PracticeState, actions: PracticeActions = object : PracticeActions {}) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.stage, state.run?.index) { listState.scrollToItem(0) }
    LaunchedEffect(state.problem) { if (state.problem != null) listState.scrollToItem(0) }
    var settings by remember { mutableStateOf(false) }
    var excerpt by remember { mutableStateOf(false) }
    var evidence by remember { mutableStateOf<PracticeEvidence?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<PracticeRecord?>(null) }
    var discarding by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        when {
            state.busy && state.stage == PracticeStage.SELECT -> actions.cancel()
            state.busy -> Unit
            state.stage == PracticeStage.PREVIEW -> discarding = true
            state.stage != PracticeStage.LIBRARY -> actions.bank()
            else -> actions.close()
        }
    }
    fun inspect(set: PracticeSet, c: StudyCitation) {
        set.sources.firstOrNull { it.id == c.sourceId }?.let { evidence = PracticeEvidence(it, c.quote, set.sourceVersion) }
    }
    BackHandler(onBack = back)
    LazyColumn(Modifier.fillMaxSize().testTag("practice-content"), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(back, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.pp_back))
                }
                Spacer(Modifier.weight(1f))
                IconButton({ settings = true }, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.st_settings_desc)) }
            }
            PageHeading(stringResource(R.string.pp_title), state.course?.shortname ?: stringResource(R.string.pp_library))
            if (state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { InlineNotice(practiceProblem(it), true) }
            if (state.storageFailed)InlineNotice(stringResource(R.string.pp_storage_error), true)
        }
        when (state.stage) {
            PracticeStage.LIBRARY -> {
                item {
                    FlowRow {
                        if (state.course != null)Button(actions::selection, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_create)) }
                        TextButton(actions::reload, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_reload)) }
                    }
                    Text(stringResource(R.string.pp_offline), style = MaterialTheme.typography.bodySmall)
                }
                if (state.records.isEmpty())item { EmptyState(stringResource(R.string.pp_empty), stringResource(R.string.pp_empty_hint)) }
                state.records.forEach { record ->
                    item(key = "${record.set.accountId}:${record.set.id}") {
                        val set = record.set
                        Panel(Modifier.fillMaxWidth().testTag("practice-set-${set.accountId}-${set.id}")) {
                            Text(set.title, style = MaterialTheme.typography.titleLarge)
                            Text(set.courseName, style = MaterialTheme.typography.bodyMedium)
                            state.owners.firstOrNull { it.id == set.accountId }?.let { owner -> Text(stringResource(R.string.pp_owner, owner.displayName.ifBlank { owner.baseUrl }), style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.pp_counts, set.questions.count { it.kind == PracticeKind.MCQ }, set.questions.count { it.kind == PracticeKind.FLASHCARD }), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.pp_model, set.model), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.pp_version, set.sourceVersion.take(12)), style = MaterialTheme.typography.labelMedium)
                            Text(sourceStatus(state, set), style = MaterialTheme.typography.bodySmall)
                            PracticeStats(record)
                            FlowRow {
                                Button({ actions.start(record, false) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_practice)) }
                                TextButton({ actions.start(record, true) }, enabled = !state.busy && record.progress.any { !it.lastOutcome }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_retry_missed)) }
                                TextButton({ deleting = record }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_delete)) }
                            }
                        }
                    }
                }
            }
            PracticeStage.SELECT -> {
                item {
                    Text(stringResource(R.string.pp_select), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.pp_selected, state.selected.size, state.sources.filter { it.id in state.selected }.sumOf { it.text.length }), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pp_limits), style = MaterialTheme.typography.bodySmall)
                    TextButton(actions::reload, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_reload_sources)) }
                }
                if (state.sources.isEmpty() && !state.busy)item { EmptyState(stringResource(R.string.pp_no_sources), stringResource(R.string.pp_no_sources_hint)) }
                state.sources.forEach { source ->
                    item(key = source.id) {
                        Panel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(source.id in state.selected, { actions.select(source.id) }, Modifier.testTag("practice-source-${source.id}"), enabled = !state.busy && (source.id in state.selected || state.selected.size < 5 && source.text.length <= 12000))
                                Column(Modifier.weight(1f)) {
                                    Text(source.title, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.st_chars, source.text.length), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (source.text.length > 12000)Text(stringResource(R.string.st_too_long), style = MaterialTheme.typography.bodySmall)
                            TextButton({ evidence = PracticeEvidence(source, version = practiceDigest(listOf(source.id, source.title, source.text))) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_inspect_source)) }
                        }
                    }
                }
                item {
                    OutlinedButton({ excerpt = true }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_add_excerpt)) }
                    PracticeGenerationForm(state, actions, { settings = true })
                    if (state.generationMessage.isNotBlank())InlineNotice(state.generationMessage)
                }
            }
            PracticeStage.PREVIEW -> state.draft?.let { set ->
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pp_draft), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(set.title, actions::title, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(stringResource(R.string.pp_set_title)) }, shape = TechnicalShapes.Field)
                        Text(stringResource(R.string.pp_review_help), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.pp_model, set.model), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.pp_version, set.sourceVersion.take(12)), style = MaterialTheme.typography.labelMedium)
                        FlowRow {
                            Button(actions::save, enabled = !state.busy && !state.sourceChanged && !state.storageFailed && runCatching { PracticeRules.set(set) }.isSuccess, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_save)) }
                            TextButton({ discarding = true }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_discard)) }
                        }
                    }
                }
                set.questions.forEachIndexed { index, q ->
                    item(key = q.id) {
                        Panel(Modifier.fillMaxWidth().testTag("practice-draft-${q.id}")) {
                            Text(stringResource(R.string.pp_number, index + 1, set.questions.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(q.prompt, style = MaterialTheme.typography.titleLarge)
                            if (q.kind == PracticeKind.MCQ)q.choices.forEachIndexed { i, choice -> Text(stringResource(R.string.pp_choice_value, i + 1, choice), color = if (i == q.correctIndex)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                            Text(stringResource(R.string.pp_answer, q.answer), style = MaterialTheme.typography.titleMedium)
                            Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
                            q.citations.forEach { c -> TextButton({ inspect(set, c) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_evidence, set.sources.first { it.id == c.sourceId }.title)) } }
                            FlowRow {
                                TextButton({ editing = q.id }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_edit)) }
                                if (index > 0)TextButton({ actions.reorder(q.id, -1) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_up)) }
                                if (index < set.questions.lastIndex)TextButton({ actions.reorder(q.id, 1) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_down)) }
                                TextButton({ actions.removeQuestion(q.id) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_remove_question)) }
                            }
                        }
                    }
                }
                if (set.questions.isEmpty())item { EmptyState(stringResource(R.string.pp_no_questions), stringResource(R.string.pp_no_questions_hint)) }
            }
            PracticeStage.PRACTICE -> {
                val record = state.record
                val q = state.question
                val run = state.run
                if (record != null && q != null && run != null) {
                    item {
                        Panel(Modifier.fillMaxWidth().testTag("practice-current")) {
                            Text(stringResource(R.string.pp_number, run.index + 1, run.ids.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(q.prompt, style = MaterialTheme.typography.headlineSmall)
                            if (q.kind == PracticeKind.MCQ) {
                                q.choices.forEachIndexed { index, choice ->
                                    Row(Modifier.fillMaxWidth().selectable(run.selected == index, enabled = !state.busy && !run.revealed, role = Role.RadioButton, onClick = { actions.choose(index) }).heightIn(min = 48.dp).testTag("practice-choice-$index"), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(run.selected == index, null, enabled = !state.busy && !run.revealed)
                                        Spacer(Modifier.width(8.dp))
                                        Text(choice, Modifier.weight(1f))
                                    }
                                }
                            }
                            if (!run.revealed)Button({ if (q.kind == PracticeKind.MCQ)actions.answer(null)else actions.reveal() }, enabled = !state.busy && (q.kind == PracticeKind.FLASHCARD || run.selected != null), shape = TechnicalShapes.Control) { Text(stringResource(if (q.kind == PracticeKind.MCQ)R.string.pp_check else R.string.pp_reveal)) }
                            if (run.revealed) {
                                if (q.kind == PracticeKind.MCQ)Text(stringResource(if (run.pending?.selected == q.correctIndex)R.string.pp_correct else R.string.pp_incorrect), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.pp_answer, q.answer), style = MaterialTheme.typography.titleMedium)
                                Text(q.explanation, style = MaterialTheme.typography.bodyLarge)
                                q.citations.forEach { c -> TextButton({ inspect(record.set, c) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_evidence, record.set.sources.first { it.id == c.sourceId }.title)) } }
                                if (q.kind == PracticeKind.FLASHCARD && run.pending == null) {
                                    Text(stringResource(R.string.pp_self_report), style = MaterialTheme.typography.bodySmall)
                                    FlowRow {
                                        Button({ actions.answer(true) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_remembered)) }
                                        OutlinedButton({ actions.answer(false) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_again)) }
                                    }
                                }
                                if (run.pending != null && !run.recorded && !state.busy)TextButton(actions::retryAttempt, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_retry_save)) }
                                if (run.recorded)Text(stringResource(R.string.pp_recorded), style = MaterialTheme.typography.bodySmall)
                                Button(actions::next, enabled = !state.busy && run.recorded, shape = TechnicalShapes.Control) { Text(stringResource(if (run.index + 1 == run.ids.size)R.string.pp_finish else R.string.pp_next)) }
                            }
                        }
                    }
                }
            }
            PracticeStage.RESULTS -> state.record?.let { record ->
                item {
                    val outcomes = state.run?.outcomes.orEmpty()
                    Panel(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pp_results), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.pp_session_count, outcomes.size, state.run?.ids?.size ?: 0))
                        val quiz = record.set.questions.filter { it.kind == PracticeKind.MCQ && it.id in outcomes }
                        val cards = record.set.questions.filter { it.kind == PracticeKind.FLASHCARD && it.id in outcomes }
                        Text(stringResource(R.string.pp_quiz_stats, quiz.count { outcomes[it.id] == true }, quiz.size))
                        Text(stringResource(R.string.pp_flash_stats, cards.count { outcomes[it.id] == true }, cards.size))
                        Text(stringResource(R.string.pp_personal), style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            Button(actions::bank, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_library)) }
                            TextButton({ actions.start(record, true) }, enabled = !state.busy && record.progress.any { !it.lastOutcome }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_retry_missed)) }
                            TextButton({ actions.start(record, false) }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_practice_all)) }
                        }
                    }
                }
            }
        }
    }
    if (settings)StudyConnectionDialog(state.connection, { settings = false }, { mode, endpoint, secret, model -> actions.configure(mode, endpoint, secret, model) { settings = false } }, { actions.disconnect { settings = false } }, state.problem?.let { practiceProblem(it) }, state.busy)
    if (excerpt)StudyExcerptDialog({ excerpt = false }) { title, text -> actions.excerpt(title, text) { excerpt = false } }
    evidence?.let { PracticeEvidenceDialog(it) { evidence = null } }
    state.draft?.questions?.firstOrNull { it.id == editing }?.let { q -> PracticeQuestionDialog(q, state.draft.sources, state.busy, state.problem, { editing = null }, { source, citation -> evidence = PracticeEvidence(source, citation, state.draft.sourceVersion) }) { actions.edit(it) { editing = null } } }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            shape = TechnicalShapes.Dialog,
            title = { Text(stringResource(R.string.pp_discard_title)) },
            text = { Text(stringResource(R.string.pp_discard_help)) },
            dismissButton = { TextButton({ discarding = false }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_keep)) } },
            confirmButton = {
                TextButton({
                    actions.discard()
                    discarding = false
                }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_discard)) }
            }
        )
    }
    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) deleting = null },
            shape = TechnicalShapes.Dialog,
            title = { Text(stringResource(R.string.pp_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(record.set.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pp_delete_help))
                    state.problem?.let { InlineNotice(practiceProblem(it), true) }
                }
            },
            dismissButton = { TextButton({ deleting = null }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel)) } },
            confirmButton = { TextButton({ actions.remove(record) { deleting = null } }, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_delete)) } }
        )
    }
}

@Composable private fun PracticeStats(record: PracticeRecord) {
    val quizIds = record.set.questions.filter { it.kind == PracticeKind.MCQ }.map { it.id }.toSet()
    val quiz = record.progress.filter { it.questionId in quizIds }
    val flash = record.progress.filter { it.questionId !in quizIds }
    Text(stringResource(R.string.pp_quiz_stats, quiz.sumOf { it.successful }, quiz.sumOf { it.attempts }), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.pp_flash_stats, flash.sumOf { it.successful }, flash.sumOf { it.attempts }), style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun sourceStatus(state: PracticeState, set: PracticeSet): String {
    val message = if (!state.catalogChecked || state.sourceChanged || state.course?.accountId != set.accountId || state.course.id != set.courseId) {
        R.string.pp_version_unchecked
    } else if (set.sources.any { s -> state.sources.none { it.id == s.id } }) {
        R.string.pp_version_missing
    } else if (set.sources.any { s -> state.sources.first { it.id == s.id } != s }) {
        R.string.pp_version_changed
    } else {
        R.string.pp_version_match
    }
    return stringResource(message)
}

@Composable internal fun practiceProblem(problem: PracticeProblem) = stringResource(
    when (problem) {
        PracticeProblem.LOAD -> R.string.pp_load_error
        PracticeProblem.INPUT -> R.string.pp_input_error
        PracticeProblem.GENERATE -> R.string.pp_generate_error
        PracticeProblem.SAVE -> R.string.pp_save_error
        PracticeProblem.CHANGED -> R.string.pp_changed
        PracticeProblem.REMOVED -> R.string.pp_removed
        PracticeProblem.CANCELED -> R.string.pp_canceled
        PracticeProblem.SOURCE -> R.string.pp_source_error
    }
)
