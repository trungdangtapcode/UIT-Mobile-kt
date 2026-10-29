package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*

@Composable fun StudyScreen(viewModel: StudyViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    BackHandler { viewModel.close() }
    StudyContent(
        state, viewModel::close, { settings = true }, viewModel::select, viewModel::question,
        viewModel::language, viewModel::generate, viewModel::cancel, viewModel::addExcerpt,
        viewModel::addTask, viewModel::task, viewModel::removeTask, viewModel::moveTask, viewModel::saveTasks
    )
    if (settings) {
        StudyConnectionDialog(state.connection, { settings = false }, { endpoint, token ->
            viewModel.configure(endpoint, token)
            settings = false
        }, {
            viewModel.disconnect()
            settings = false
        })
    }
}

@Composable fun StudyContent(
    state: StudyState,
    onBack: () -> Unit = {},
    onSettings: () -> Unit = {},
    onSelect: (String) -> Unit = {},
    onQuestion: (String) -> Unit = {},
    onLanguage: (String) -> Unit = {},
    onGenerate: () -> Unit = {},
    onCancel: () -> Unit = {},
    onExcerpt: (String, String) -> Unit = { _, _ -> },
    onAddTask: (String) -> Unit = {},
    onTask: (Int, String?, Boolean?) -> Unit = { _, _, _ -> },
    onRemoveTask: (Int) -> Unit = {},
    onMoveTask: (Int, Int) -> Unit = { _, _ -> },
    onSave: () -> Unit = {}
) {
    var preview by remember(state.course, state.assignment) { mutableStateOf<Pair<StudySource, String?>?>(null) }
    var excerpt by remember { mutableStateOf(false) }
    var exit by remember { mutableStateOf(false) }
    val back = { if (state.dirty) exit = true else onBack() }
    BackHandler(onBack = back)
    LazyColumn(Modifier.fillMaxSize().testTag("study-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text("Course")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, enabled = !state.busy) { Icon(Icons.Outlined.Settings, "Study gateway settings") }
            }
            PageHeading(if (state.assignment == null) "Ask course text" else "Analyze assignment", state.course?.shortname.orEmpty())
        }
        state.assignment?.let { assignment ->
            item {
                Panel {
                    Text(assignment.name, style = MaterialTheme.typography.titleLarge)
                    if (assignment.due > 0) Text("Moodle due · ${dateText(assignment.due)} · ${clockText(assignment.due)}", style = MaterialTheme.typography.bodyMedium)
                    Text("AI suggestions are personal study aids. Your official dates and submission status stay in Moodle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionHeading("Selected sources", "Choose up to 5 texts. Review each source before sending.")
            Text("This version reads saved descriptions and pasted excerpts. PDF files and scans are not included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.sources.isEmpty()) item { EmptyState("No readable text saved", "Add a course excerpt below, or load a Moodle activity with a description.") }
        itemsIndexed(state.sources, key = { _, source -> source.id }) { _, source ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(source.id in state.selected, { onSelect(source.id) }, modifier = Modifier.semantics { contentDescription = "Select source ${source.title}" }, enabled = !state.busy && source.text.length <= 12000 && (source.id in state.selected || state.selected.size < 5))
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        Text(if (source.text.length > 12000) "Too long · add a shorter excerpt" else "${source.text.length} characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { preview = source to null }, shape = TechnicalShapes.Control) { Text("Review source") }
            }
        }
        item {
            OutlinedButton(onClick = { excerpt = true }, enabled = !state.busy, shape = TechnicalShapes.Field) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Add text excerpt")
            }
        }
        item {
            Panel {
                Text("${state.selected.size} selected · ${state.sources.filter { it.id in state.selected }.sumOf { it.text.length }} characters", style = MaterialTheme.typography.labelMedium)
                if (!state.connection.configured) {
                    Text("Connect your study gateway", style = MaterialTheme.typography.titleMedium)
                    Text("An OpenAI-backed gateway is required. Configure its HTTPS address and gateway token; keep the OpenAI API key on the server.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onSettings, enabled = !state.busy, shape = TechnicalShapes.Field) { Text("Configure gateway") }
                } else {
                    Text("Processor", style = MaterialTheme.typography.labelMedium)
                    Text(state.connection.endpoint, style = MaterialTheme.typography.bodySmall)
                    Text("Only the checked texts and your question are sent to this gateway and OpenAI. Gateway source text is held for the request; OpenAI processing follows your provider account settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.assignment == null) OutlinedTextField(value = state.question, onValueChange = onQuestion, enabled = !state.busy, label = { Text("Question about the selected text") }, supportingText = { Text("${state.question.length}/1200") }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "Vietnamese").forEach { language -> FilterChip(selected = state.language == language, onClick = { onLanguage(language) }, enabled = !state.busy, label = { Text(language) }, shape = TechnicalShapes.Control) }
                }
                Button(onClick = onGenerate, enabled = state.connection.configured && !state.busy && state.selected.isNotEmpty() && (state.assignment != null || state.question.isNotBlank()), modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
                    Text(if (state.answer == null) "Send selected text" else "Generate again")
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel, shape = TechnicalShapes.Control) { Text("Cancel request") }
                }
            }
        }
        state.message?.let { message -> item { InlineNotice(message) } }
        state.answer?.let { answer ->
            item {
                SectionHeading("Study result", "${answer.model} · ${dateText(answer.generatedAt)} ${clockText(answer.generatedAt)}")
                if (state.answeredQuestion.isNotBlank()) Text(state.answeredQuestion, style = MaterialTheme.typography.bodyMedium)
                Text("Source quotes can be inspected below. A matching quote does not guarantee the explanation is correct.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(answer.items) { _, item ->
                Panel {
                    Text(
                        when (item.kind) {
                            "requirement" -> "Stated requirement"
                            "suggestion" -> "Suggested step"
                            "unknown" -> "Not established by the selected text"
                            else -> "Source-based answer"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
                    item.citations.forEach { citation ->
                        val source = state.sources.firstOrNull { it.id == citation.sourceId }
                        if (source != null) {
                            TextButton(onClick = { preview = source to citation.quote }, shape = TechnicalShapes.Control) {
                                Icon(Icons.Outlined.FormatQuote, null)
                                Spacer(Modifier.width(6.dp))
                                Text(source.title)
                            }
                        }
                    }
                    if (state.assignment != null && item.kind in setOf("requirement", "suggestion")) TextButton(onClick = { onAddTask(item.text) }, enabled = !state.busy && state.tasks.none { it.text == item.text }, shape = TechnicalShapes.Control) { Text(if (state.tasks.any { it.text == item.text }) "Added to checklist" else "Add to checklist") }
                }
            }
        }
        if (state.assignment != null) {
            item { SectionHeading("Personal checklist", "Edit suggestions before saving. This does not submit your assignment.") }
            if (state.tasks.isEmpty()) item { Text("Add a result above, or write your own step below.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(state.tasks) { index, task ->
                Panel {
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(task.checked, { onTask(index, null, it) }, modifier = Modifier.semantics { contentDescription = "Complete step ${index + 1}" }, enabled = !state.busy)
                        OutlinedTextField(task.text, { onTask(index, it, null) }, enabled = !state.busy, modifier = Modifier.weight(1f), label = { Text("Step ${index + 1}") }, shape = TechnicalShapes.Field)
                    }
                    Row {
                        IconButton({ onMoveTask(index, -1) }, enabled = !state.busy && index > 0) { Icon(Icons.Outlined.ArrowUpward, "Move step ${index + 1} up") }
                        IconButton({ onMoveTask(index, 1) }, enabled = !state.busy && index < state.tasks.lastIndex) { Icon(Icons.Outlined.ArrowDownward, "Move step ${index + 1} down") }
                        Spacer(Modifier.weight(1f))
                        IconButton({ onRemoveTask(index) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "Remove step ${index + 1}") }
                    }
                }
            }
            item {
                var step by remember { mutableStateOf("") }
                OutlinedTextField(step, { step = it.take(1500) }, label = { Text("Your own step") }, enabled = !state.busy, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth())
                TextButton({
                    onAddTask(step)
                    step = ""
                }, enabled = !state.busy && step.isNotBlank() && state.tasks.size < 30, shape = TechnicalShapes.Control) { Text("Add step") }
                Button(onSave, enabled = !state.busy && state.dirty && state.tasks.all { it.text.isNotBlank() }, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) { Text("Save checklist on this device") }
                Text(if (state.dirty) "Unsaved changes" else "Saved steps stay on this device and are removed with the course account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    preview?.let { (source, quote) ->
        AlertDialog(onDismissRequest = { preview = null }, shape = TechnicalShapes.Dialog, title = { Text(source.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                quote?.let {
                    Text("Cited passage", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(it) }
                    HorizontalDivider()
                }
                Text("Selected source text", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(source.text) }
            }
        }, confirmButton = { TextButton({ preview = null }) { Text("Close source") } })
    }
    if (excerpt) {
        StudyExcerptDialog({ excerpt = false }) { title, text ->
            onExcerpt(title, text)
            excerpt = false
        }
    }
    if (exit) {
        AlertDialog(onDismissRequest = { exit = false }, shape = TechnicalShapes.Dialog, title = { Text("Leave unsaved changes?") }, text = { Text("Save your checklist before leaving, or discard these edits.") }, confirmButton = {
            TextButton({
                exit = false
                onBack()
            }) { Text("Discard edits") }
        }, dismissButton = { TextButton({ exit = false }) { Text("Keep editing") } })
    }
}

@Composable private fun StudyExcerptDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, shape = TechnicalShapes.Dialog, title = { Text("Add a text excerpt") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste only the course text you want to use. It stays in this screen until you choose to send it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Source title") }, shape = TechnicalShapes.Field)
            OutlinedTextField(text, { text = it.take(12000) }, label = { Text("Source text") }, minLines = 4, maxLines = 8, supportingText = { Text("${text.length}/12000") }, shape = TechnicalShapes.Field)
        }
    }, confirmButton = { TextButton({ onAdd(title, text) }, enabled = title.isNotBlank() && text.isNotBlank()) { Text("Add source") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable private fun StudyConnectionDialog(connection: StudyConnection, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDisconnect: () -> Unit) {
    var endpoint by remember { mutableStateOf(connection.endpoint) }
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, shape = TechnicalShapes.Dialog, title = { Text("Study gateway") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use your HTTPS study gateway and its access token. The OpenAI API key belongs in the server process, not in this form.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS gateway address") }, singleLine = true, shape = TechnicalShapes.Field)
            OutlinedTextField(token, { token = it }, label = { Text("Gateway access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = TechnicalShapes.Field)
            if (connection.configured) TextButton(onDisconnect) { Text("Clear connection") }
        }
    }, confirmButton = { TextButton({ onSave(endpoint, token) }, enabled = endpoint.isNotBlank() && token.length >= 32) { Text("Save connection") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
