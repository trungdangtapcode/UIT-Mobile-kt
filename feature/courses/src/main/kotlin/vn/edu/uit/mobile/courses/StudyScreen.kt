package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

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
        StudyConnectionDialog(state.connection, { settings = false }, { mode, endpoint, secret, model ->
            viewModel.configure(mode, endpoint, secret, model)
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
                    Text(stringResource(R.string.st_back))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.st_settings_desc)) }
            }
            PageHeading(if (state.assignment == null) stringResource(R.string.st_title_ask) else stringResource(R.string.st_title_analyze), state.course?.shortname.orEmpty())
        }
        state.assignment?.let { assignment ->
            item {
                Panel {
                    Text(assignment.name, style = MaterialTheme.typography.titleLarge)
                    if (assignment.due > 0) Text(stringResource(R.string.st_due, dateText(assignment.due), clockText(assignment.due)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.st_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionHeading(stringResource(R.string.st_sources), stringResource(R.string.st_sources_hint))
            Text(stringResource(R.string.st_version_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.sources.isEmpty()) item { EmptyState(stringResource(R.string.st_no_sources_title), stringResource(R.string.st_no_sources_body)) }
        itemsIndexed(state.sources, key = { _, source -> source.id }) { _, source ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val selectSource = stringResource(R.string.st_select_source, source.title)
                    Checkbox(source.id in state.selected, { onSelect(source.id) }, modifier = Modifier.semantics { contentDescription = selectSource }, enabled = !state.busy && (source.id in state.selected || (source.text.length <= 12000 && state.selected.size < 5)))
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        Text(if (source.text.length > 12000) stringResource(R.string.st_too_long) else stringResource(R.string.st_chars, source.text.length), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { preview = source to null }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_review)) }
            }
        }
        item {
            OutlinedButton(onClick = { excerpt = true }, enabled = !state.busy, shape = TechnicalShapes.Field) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.st_add_excerpt))
            }
        }
        item {
            Panel {
                Text(stringResource(R.string.st_selected, state.selected.size, state.sources.filter { it.id in state.selected }.sumOf { it.text.length }), style = MaterialTheme.typography.labelMedium)
                if (!state.connection.configured) {
                    Text(stringResource(R.string.st_connect_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.st_connect_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onSettings, enabled = !state.busy, shape = TechnicalShapes.Field) { Text(stringResource(R.string.st_configure)) }
                } else {
                    Text(stringResource(R.string.st_processor), style = MaterialTheme.typography.labelMedium)
                    Text(if (state.connection.mode == StudyConnectionMode.DIRECT) stringResource(R.string.st_direct, state.connection.model) else stringResource(R.string.st_gateway, state.connection.endpoint), style = MaterialTheme.typography.bodySmall)
                    Text(if (state.connection.mode == StudyConnectionMode.DIRECT) stringResource(R.string.st_direct_note) else stringResource(R.string.st_gateway_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.assignment == null) OutlinedTextField(value = state.question, onValueChange = onQuestion, enabled = !state.busy, label = { Text(stringResource(R.string.st_question)) }, supportingText = { Text("${state.question.length}/1200") }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("English", "Vietnamese").forEach { language -> FilterChip(selected = state.language == language, onClick = { onLanguage(language) }, enabled = !state.busy, label = { Text(language) }, shape = TechnicalShapes.Control) }
                }
                Button(onClick = onGenerate, enabled = state.connection.configured && !state.busy && state.selected.isNotEmpty() && (state.assignment != null || state.question.isNotBlank()), modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
                    Text(stringResource(if (state.answer == null) R.string.st_send else R.string.st_generate_again))
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel_request)) }
                }
            }
        }
        state.message?.let { message -> item { InlineNotice(message) } }
        state.answer?.let { answer ->
            item {
                SectionHeading(stringResource(R.string.st_result), stringResource(R.string.st_result_meta, answer.model, dateText(answer.generatedAt), clockText(answer.generatedAt)))
                if (state.answeredQuestion.isNotBlank()) Text(state.answeredQuestion, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.st_quote_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(answer.items) { _, item ->
                Panel {
                    Text(
                        when (item.kind) {
                            "requirement" -> stringResource(R.string.st_kind_requirement)
                            "suggestion" -> stringResource(R.string.st_kind_suggestion)
                            "unknown" -> stringResource(R.string.st_kind_unknown)
                            else -> stringResource(R.string.st_kind_answer)
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
                    if (state.assignment != null && item.kind in setOf("requirement", "suggestion")) TextButton(onClick = { onAddTask(item.text) }, enabled = !state.busy && state.tasks.none { it.text == item.text }, shape = TechnicalShapes.Control) { Text(stringResource(if (state.tasks.any { it.text == item.text }) R.string.st_added else R.string.st_add_to_checklist)) }
                }
            }
        }
        if (state.assignment != null) {
            item { SectionHeading(stringResource(R.string.st_checklist), stringResource(R.string.st_checklist_hint)) }
            if (state.tasks.isEmpty()) item { Text(stringResource(R.string.st_checklist_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(state.tasks) { index, task ->
                Panel {
                    val completeLabel = stringResource(R.string.st_complete_step, index + 1)
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(task.checked, { onTask(index, null, it) }, modifier = Modifier.semantics { contentDescription = completeLabel }, enabled = !state.busy)
                        OutlinedTextField(task.text, { onTask(index, it, null) }, enabled = !state.busy, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.st_step, index + 1)) }, shape = TechnicalShapes.Field)
                    }
                    Row {
                        IconButton({ onMoveTask(index, -1) }, enabled = !state.busy && index > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.st_move_up, index + 1)) }
                        IconButton({ onMoveTask(index, 1) }, enabled = !state.busy && index < state.tasks.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.st_move_down, index + 1)) }
                        Spacer(Modifier.weight(1f))
                        IconButton({ onRemoveTask(index) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.st_remove_step, index + 1)) }
                    }
                }
            }
            item {
                var step by remember { mutableStateOf("") }
                OutlinedTextField(step, { step = it.take(1500) }, label = { Text(stringResource(R.string.st_own_step)) }, enabled = !state.busy, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth())
                TextButton({
                    onAddTask(step)
                    step = ""
                }, enabled = !state.busy && step.isNotBlank() && state.tasks.size < 30, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_add_step)) }
                Button(onSave, enabled = !state.busy && state.dirty && state.tasks.all { it.text.isNotBlank() }, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_save_checklist)) }
                Text(stringResource(if (state.dirty) R.string.st_unsaved else R.string.st_saved_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    preview?.let { (source, quote) ->
        AlertDialog(onDismissRequest = { preview = null }, shape = TechnicalShapes.Dialog, title = { Text(source.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                quote?.let {
                    Text(stringResource(R.string.st_cited), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(it) }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.st_source_text), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(source.text) }
            }
        }, confirmButton = { TextButton({ preview = null }) { Text(stringResource(R.string.st_close_source)) } })
    }
    if (excerpt) {
        StudyExcerptDialog({ excerpt = false }) { title, text ->
            onExcerpt(title, text)
            excerpt = false
        }
    }
    if (exit) {
        AlertDialog(onDismissRequest = { exit = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.st_leave_title)) }, text = { Text(stringResource(R.string.st_leave_body)) }, confirmButton = {
            TextButton({
                exit = false
                onBack()
            }) { Text(stringResource(R.string.st_discard)) }
        }, dismissButton = { TextButton({ exit = false }) { Text(stringResource(R.string.st_keep_editing)) } })
    }
}

@Composable private fun StudyExcerptDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.st_excerpt_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.st_excerpt_body), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(R.string.st_source_title)) }, shape = TechnicalShapes.Field)
            OutlinedTextField(text, { text = it.take(12000) }, label = { Text(stringResource(R.string.st_source_body)) }, minLines = 4, maxLines = 8, supportingText = { Text("${text.length}/12000") }, shape = TechnicalShapes.Field)
        }
    }, confirmButton = { TextButton({ onAdd(title, text) }, enabled = title.isNotBlank() && text.isNotBlank()) { Text(stringResource(R.string.st_add_source)) } }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.st_cancel)) } })
}

@Composable fun StudyConnectionDialog(connection: StudyConnection, onDismiss: () -> Unit, onSave: (StudyConnectionMode, String, String, String) -> Unit, onDisconnect: () -> Unit) {
    var mode by remember { mutableStateOf(connection.mode) }
    var endpoint by remember { mutableStateOf(if (connection.mode == StudyConnectionMode.GATEWAY) connection.endpoint else "") }
    var secret by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(connection.model) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        val credential = secret.trim()
        val modelId = model.trim()
        error = when {
            mode == StudyConnectionMode.DIRECT && (credential.length !in 24..1024 || credential.any { it.code !in 33..126 }) -> "Enter your personal OpenAI API key without spaces."
            mode == StudyConnectionMode.DIRECT && !modelId.matches(Regex("[A-Za-z0-9._-]{1,100}")) -> "Enter an available model ID supporting Responses and structured outputs."
            mode == StudyConnectionMode.GATEWAY && (credential.length !in 32..512 || credential.any { it.code !in 33..126 } || credential.startsWith("sk-")) -> "Enter the gateway access token, not an OpenAI API key."
            mode == StudyConnectionMode.GATEWAY && runCatching { StudyAiRules.endpoint(endpoint) }.isFailure -> "Enter a valid HTTPS gateway origin without a path or query."
            else -> null
        }
        if (error == null) onSave(mode, endpoint.trim(), credential, modelId)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight(0.9f), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("AI connection", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ai-connection-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudyConnectionMode.entries.forEach { option ->
                        OutlinedButton(onClick = {
                            mode = option
                            secret = ""
                            error = null
                        }, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = mode == option, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (option == StudyConnectionMode.DIRECT) "Direct OpenAI" else "Gateway")
                        }
                    }
                    if (mode == StudyConnectionMode.DIRECT) {
                        Text("Connect from this phone to OpenAI. No backend to host. Use your own API key and API billing account; this is separate from ChatGPT sign-in.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(model, { model = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI model ID") }, supportingText = { Text("Use a model available to your API project with structured outputs.") }, singleLine = true, shape = TechnicalShapes.Field)
                    } else {
                        Text("Use an HTTPS gateway to keep the OpenAI key on your server. Enter its separate gateway token here.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTPS gateway address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, shape = TechnicalShapes.Field)
                    }
                    OutlinedTextField(secret, { secret = it.take(1024) }, modifier = Modifier.fillMaxWidth(), label = { Text(if (mode == StudyConnectionMode.DIRECT) "Your OpenAI API key" else "Gateway access token") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = TechnicalShapes.Field)
                    Text("Credentials are encrypted on this phone. Use a personal key for direct mode; never distribute a shared key in the APK. A compromised device can still expose stored credentials.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (connection.configured) TextButton(onDisconnect, shape = TechnicalShapes.Control) { Text("Clear connection") }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, shape = TechnicalShapes.Control) { Text("Cancel") }
                    TextButton(::save, enabled = secret.isNotBlank(), shape = TechnicalShapes.Control) { Text("Save connection") }
                }
            }
        }
    }
}
