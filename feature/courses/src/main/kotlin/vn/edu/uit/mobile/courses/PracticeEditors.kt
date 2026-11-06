package vn.edu.uit.mobile.courses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable internal fun PracticeGenerationForm(state: PracticeState, actions: PracticeActions, onSettings: () -> Unit) {
    var count by remember { mutableStateOf(state.options.count.toString()) }
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pp_options), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PracticeKind.entries.forEach { kind ->
                FilterChip(state.options.kind == kind, { actions.options(state.options.copy(kind = kind)) }, enabled = !state.busy, shape = TechnicalShapes.Control, label = {
                    Text(
                        stringResource(
                            when (kind) {
                                PracticeKind.MCQ -> R.string.pp_mcq
                                PracticeKind.FLASHCARD -> R.string.pp_flashcard
                                PracticeKind.MIXED -> R.string.pp_mixed
                            }
                        )
                    )
                })
            }
        }
        OutlinedTextField(count, {
            count = it.take(2)
            actions.options(state.options.copy(count = count.toIntOrNull() ?: 0))
        }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(stringResource(R.string.pp_count)) }, supportingText = { Text(stringResource(R.string.pp_count_hint)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = TechnicalShapes.Field)
        OutlinedTextField(state.options.topic, { actions.options(state.options.copy(topic = it.take(200))) }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(stringResource(R.string.pp_topic)) }, shape = TechnicalShapes.Field)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("English" to R.string.st_language_en, "Vietnamese" to R.string.st_language_vi).forEach { (language, label) -> FilterChip(state.options.language == language, { actions.options(state.options.copy(language = language)) }, enabled = !state.busy, shape = TechnicalShapes.Control, label = { Text(stringResource(label)) }) }
        }
        if (!state.connection.configured) {
            Text(stringResource(R.string.pp_connection_needed), style = MaterialTheme.typography.bodyMedium)
        } else {
            ChatGptUsage(state.connection)
            Text(
                if (state.connection.mode == StudyConnectionMode.CHATGPT) {
                    "${chatGptLabel()} · ${state.connection.model}"
                } else if (state.connection.mode == StudyConnectionMode.DIRECT) {
                    stringResource(R.string.st_direct, state.connection.model)
                } else {
                    stringResource(R.string.st_gateway, state.connection.endpoint)
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(stringResource(R.string.pp_send_help), style = MaterialTheme.typography.bodySmall)
        TextButton(onSettings, enabled = !state.busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_configure)) }
        Button(actions::generate, enabled = !state.busy && state.connection.configured && !state.sourceChanged && !state.storageFailed && runCatching { PracticeRules.request(state.sources.filter { it.id in state.selected }, state.options) }.isSuccess, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_generate)) }
        if (state.busy)TextButton(actions::cancel, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel_request)) }
    }
}

@Composable internal fun PracticeDialog(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight(.88f).testTag("practice-dialog"), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("practice-dialog-body"), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

@Composable internal fun PracticeEvidenceDialog(value: PracticeEvidence, onDismiss: () -> Unit) {
    val text = value.source.text.take(36000)
    val position = value.quote?.let { text.indexOf(it) } ?: -1
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val annotated = remember(text, value.quote, highlight) {
        buildAnnotatedString {
            append(text)
            if (position >= 0)addStyle(SpanStyle(background = highlight), position, position + value.quote!!.length)
        }
    }
    PracticeDialog(stringResource(R.string.pp_source_copy), onDismiss, actions = { TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_close)) } }) {
        Text(value.source.title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pp_version, value.version.take(12)), style = MaterialTheme.typography.labelMedium)
        if (value.quote != null) {
            Text(stringResource(R.string.pp_quote), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(value.quote) }
        }
        Text(stringResource(R.string.pp_evidence_help), style = MaterialTheme.typography.bodySmall)
        if (value.source.text.length > text.length)Text(stringResource(R.string.pp_preview_limit), style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(annotated, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable internal fun PracticeQuestionDialog(question: PracticeQuestion, sources: List<StudySource>, busy: Boolean, problem: PracticeProblem?, onDismiss: () -> Unit, onEvidence: (StudySource, String?) -> Unit, onSave: (PracticeQuestion) -> Unit) {
    var prompt by remember(question.id) { mutableStateOf(question.prompt) }
    var choices by remember(question.id) { mutableStateOf(question.choices) }
    var correct by remember(question.id) { mutableIntStateOf(question.correctIndex) }
    var answer by remember(question.id) { mutableStateOf(question.answer) }
    var explanation by remember(question.id) { mutableStateOf(question.explanation) }
    var citations by remember(question.id) { mutableStateOf(question.citations) }
    PracticeDialog(stringResource(R.string.pp_edit_title), onDismiss, actions = {
        TextButton(onDismiss, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel)) }
        TextButton({ onSave(question.copy(prompt = prompt, choices = choices, correctIndex = correct, answer = if (question.kind == PracticeKind.MCQ)choices[correct]else answer, explanation = explanation, citations = citations)) }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_apply_edit)) }
    }) {
        OutlinedTextField(prompt, { prompt = it.take(1000) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.pp_prompt)) }, shape = TechnicalShapes.Field)
        if (question.kind == PracticeKind.MCQ) {
            choices.forEachIndexed { index, choice ->
                OutlinedTextField(choice, { value -> choices = choices.mapIndexed { i, old -> if (i == index)value.take(500)else old } }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.pp_choice, index + 1)) }, shape = TechnicalShapes.Field)
                Row {
                    RadioButton(correct == index, { correct = index }, enabled = !busy)
                    TextButton({ correct = index }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_correct_choice, index + 1)) }
                }
            }
        } else {
            OutlinedTextField(answer, { answer = it.take(1000) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.pp_answer_field)) }, shape = TechnicalShapes.Field)
        }
        OutlinedTextField(explanation, { explanation = it.take(1500) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.pp_explanation)) }, shape = TechnicalShapes.Field)
        Text(stringResource(R.string.pp_edit_evidence), style = MaterialTheme.typography.titleMedium)
        citations.forEachIndexed { index, citation ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { sources.forEach { s -> FilterChip(citation.sourceId == s.id, { citations = citations.mapIndexed { i, c -> if (i == index)StudyCitation(s.id, "")else c } }, enabled = !busy, shape = TechnicalShapes.Control, label = { Text(s.title, Modifier.widthIn(max = 220.dp)) }) } }
            OutlinedTextField(citation.quote, { quote -> citations = citations.mapIndexed { i, c -> if (i == index)c.copy(quote = quote.take(500))else c } }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.pp_quote_field, index + 1)) }, shape = TechnicalShapes.Field)
            sources.firstOrNull { it.id == citation.sourceId }?.let { s -> TextButton({ onEvidence(s, citation.quote) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_inspect_source)) } }
            if (citations.size > 1)TextButton({ citations = citations.filterIndexed { i, _ -> i != index } }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_remove_citation)) }
        }
        if (citations.size < 3)TextButton({ citations = citations + StudyCitation(sources.first().id, "") }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_add_citation)) }
        problem?.let { InlineNotice(practiceProblem(it), true) }
    }
}
