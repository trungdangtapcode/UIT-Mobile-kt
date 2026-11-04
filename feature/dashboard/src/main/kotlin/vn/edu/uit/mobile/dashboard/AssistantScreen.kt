package vn.edu.uit.mobile.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.dashboard.R

@Composable
fun AssistantScreen(vm: AssistantViewModel, onBack: () -> Unit, initialUrl: String? = null, onOriginal: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var restored by rememberSaveable { mutableStateOf<String?>(null) }
    var seeded by rememberSaveable(initialUrl) { mutableStateOf(false) }
    LaunchedEffect(initialUrl) {
        if (initialUrl != null && !seeded) {
            seeded = true
            vm.createFor(initialUrl)
        }
    }
    LaunchedEffect(state.selected) { if (state.selected != null) restored = state.selected }
    LaunchedEffect(state.library.workspaces.isNotEmpty()) { if (vm.state.value.selected == null && restored != null && state.library.workspaces.any { it.id == restored }) vm.select(restored) }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    val language = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "vi") "Vietnamese" else "English"
    fun back() {
        if (state.selected != null) {
            restored = null
            vm.select(null)
        } else {
            onBack()
        }
    }
    BackHandler { back() }
    AssistantContent(
        state, onBack = ::back, onSelect = vm::select, onCreate = vm::create, onRename = vm::rename,
        onDelete = {
            if (restored == it) restored = null
            vm.delete(it)
        }, onClear = {
            restored = null
            vm.clear()
        },
        onAsk = { vm.ask(it, language) }, onStop = vm::stop, onDismiss = vm::dismiss,
        onRefresh = vm::refresh, onConfigure = vm::configure, onDisconnect = vm::disconnect,
        onChecklist = vm::checklist, onOriginal = onOriginal
    )
}

@Composable
fun AssistantContent(
    state: AssistantUiState,
    onBack: () -> Unit = {},
    onSelect: (String?) -> Unit = {},
    onCreate: (String, AssistantScope) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onClear: () -> Unit = {},
    onAsk: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onConfigure: (StudyConnectionMode, String, String, String) -> Unit = { _, _, _, _ -> },
    onDisconnect: () -> Unit = {},
    onChecklist: (String, String, List<AssistantChecklistItem>) -> Unit = { _, _, _ -> },
    onOriginal: (String) -> Unit = {}
) {
    val workspace = state.library.workspaces.find { it.id == state.selected }
    var create by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var context by rememberSaveable(workspace?.id) { mutableStateOf(false) }
    var rename by rememberSaveable(workspace?.id) { mutableStateOf(false) }
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var question by rememberSaveable(workspace?.id) { mutableStateOf("") }
    var reference by remember { mutableStateOf<Pair<AssistantEvidence, AssistantReference>?>(null) }
    var checklistTurn by remember { mutableStateOf<AssistantTurn?>(null) }
    val scroll = rememberLazyListState()
    val frozenNow = remember { kotlinx.datetime.Clock.System.now().epochSeconds }
    LaunchedEffect(workspace?.id, workspace?.turns?.size) { if (workspace != null && workspace.turns.isNotEmpty()) scroll.animateScrollToItem(workspace.turns.size) }
    Column(Modifier.fillMaxSize().testTag("assistant-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.as_back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.as_title), style = MaterialTheme.typography.titleLarge)
                if (workspace != null) Text(workspace.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (workspace != null) IconButton({ context = true }) { Icon(Icons.Outlined.Source, stringResource(R.string.as_context)) }
            IconButton({ settings = true }, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.as_connection)) }
        }
        if (state.problem != null || state.library.storageFailed) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(
                        if (state.library.storageFailed) {
                            R.string.as_storage_error
                        } else if (state.problem == AssistantProblem.CONNECTION) {
                            R.string.as_connection_error
                        } else if (state.problem == AssistantProblem.GENERATION) {
                            R.string.as_generation_error
                        } else {
                            R.string.as_action_error
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    TextButton(onDismiss) { Text(stringResource(R.string.as_dismiss)) }
                    if (state.library.storageFailed) TextButton({ reset = true }) { Text(stringResource(R.string.as_reset)) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("assistant-content"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (workspace == null) {
                item {
                    Text(stringResource(R.string.as_intro), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.as_public_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ create = true }, Modifier.fillMaxWidth(), enabled = !state.library.storageFailed && state.library.workspaces.size < AssistantRules.WORKSPACES, shape = TechnicalShapes.Control) { Text(stringResource(R.string.as_create)) }
                    if (!state.connection.configured) TextButton({ settings = true }) { Text(stringResource(R.string.as_setup)) }
                    Text(stringResource(R.string.as_limits, AssistantRules.WORKSPACES, AssistantRules.TURNS), style = MaterialTheme.typography.bodySmall)
                }
                items(state.library.workspaces, key = { it.id }) { saved ->
                    Panel(Modifier.fillMaxWidth()) {
                        Text(saved.name, style = MaterialTheme.typography.titleMedium)
                        Text(saved.scope.sources.joinToString { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.as_turn_count, saved.turns.size), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton({ onSelect(saved.id) }) { Text(stringResource(R.string.as_open)) }
                            TextButton({ deletion = saved.id }) { Text(stringResource(R.string.as_delete)) }
                        }
                    }
                }
                if (state.library.workspaces.isNotEmpty()) item { TextButton({ reset = true }) { Text(stringResource(R.string.as_reset)) } }
            } else {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.as_scope), style = MaterialTheme.typography.labelLarge)
                        Text(workspace.scope.sources.joinToString { it.label }, color = MaterialTheme.colorScheme.primary)
                        if (workspace.scope.urls.isNotEmpty()) Text(stringResource(R.string.as_pinned, workspace.scope.urls.size), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.as_send_disclosure), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton({ context = true }) { Text(stringResource(R.string.as_context)) }
                            TextButton({ rename = true }, enabled = !state.busy) { Text(stringResource(R.string.as_rename)) }
                            TextButton({ deletion = workspace.id }) { Text(stringResource(R.string.as_delete)) }
                        }
                    }
                }
                if (workspace.turns.isEmpty()) {
                    item {
                        Text(stringResource(R.string.as_empty_chat), style = MaterialTheme.typography.bodyLarge)
                        TextButton({
                            question = ""
                            onRefresh()
                        }) { Text(stringResource(R.string.as_refresh_sources)) }
                    }
                }
                items(workspace.turns, key = { it.id }) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.as_you), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(turn.question, style = MaterialTheme.typography.titleMedium)
                        if (turn.status == AssistantTurnStatus.COMPLETE) {
                            Text(stringResource(R.string.as_answer), style = MaterialTheme.typography.labelLarge)
                            turn.facts.forEach { fact ->
                                Text(
                                    stringResource(
                                        when (fact.kind) {
                                            AssistantFactKind.FACT -> R.string.as_fact
                                            AssistantFactKind.SUGGESTION -> R.string.as_suggestion
                                            AssistantFactKind.UNKNOWN -> R.string.as_unknown
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (fact.kind == AssistantFactKind.UNKNOWN) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(fact.text, style = MaterialTheme.typography.bodyLarge)
                                fact.references.forEachIndexed { index, ref ->
                                    val source = turn.evidence.find { it.id == ref.evidenceId }
                                    if (source != null) TextButton({ reference = source to ref }, Modifier.fillMaxWidth(), shape = TechnicalShapes.Control) { Text("[${index + 1}] ${source.source.label} · ${source.publishedDate}", style = MaterialTheme.typography.labelLarge) }
                                }
                            }
                            Text(stringResource(R.string.as_historical, asTime(turn.at)), style = MaterialTheme.typography.bodySmall)
                            if (turn.evidence.any { it.offline || it.abbreviated }) Text(stringResource(R.string.as_partial_evidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            val changed = turn.evidence.any { e -> state.notices.items.find { it.url == e.url && it.bodyHtml.isNotEmpty() }?.let { assistantSourceVersion(it) != e.version } == true }
                            if (changed || frozenNow - turn.at > 86400) Text(stringResource(R.string.as_stale), color = MaterialTheme.colorScheme.tertiary)
                            TextButton({ context = true }) { Text(stringResource(R.string.as_coverage)) }
                            TextButton({ checklistTurn = turn }, enabled = !state.busy) { Text(stringResource(if (turn.checklist.isEmpty()) R.string.as_checklist_create else R.string.as_checklist_edit)) }
                            turn.checklist.forEach { task ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(task.checked, { checked -> onChecklist(workspace.id, turn.id, turn.checklist.map { if (it.id == task.id) it.copy(checked = checked) else it }) }, enabled = !state.busy)
                                    Text(task.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            Text(
                                stringResource(
                                    when (turn.status) {
                                        AssistantTurnStatus.RUNNING -> R.string.as_running
                                        AssistantTurnStatus.CANCELLED -> R.string.as_cancelled
                                        AssistantTurnStatus.INTERRUPTED -> R.string.as_interrupted
                                        else -> R.string.as_failed
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (turn.status != AssistantTurnStatus.RUNNING) TextButton({ question = turn.question }) { Text(stringResource(R.string.as_reuse)) }
                        }
                        HorizontalDivider()
                    }
                }
                if (state.busy) {
                    item {
                        Text(
                            stringResource(
                                when (state.progress?.tool) {
                                    "search_announcements" -> R.string.as_searching
                                    "read_announcement" -> R.string.as_reading
                                    else -> R.string.as_thinking
                                }
                            ),
                            Modifier.testTag("assistant-progress")
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onStop) { Text(stringResource(R.string.as_stop)) }
                    }
                }
            }
        }
        if (workspace != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(question, { question = it.take(1200) }, Modifier.fillMaxWidth().testTag("assistant-question"), enabled = !state.busy, label = { Text(stringResource(R.string.as_question)) }, shape = TechnicalShapes.Field, maxLines = 4)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!state.connection.configured) TextButton({ settings = true }) { Text(stringResource(R.string.as_setup)) }
                    Button({
                        onAsk(question)
                        question = ""
                    }, enabled = !state.busy && state.connection.configured && question.isNotBlank() && workspace.turns.size < AssistantRules.TURNS, shape = TechnicalShapes.Control) { Text(stringResource(R.string.as_send)) }
                }
            }
        }
    }
    if (create) {
        WorkspaceDialog(state.notices, { create = false }, onCreate = { name, scope ->
            create = false
            onCreate(name, scope)
        })
    }
    if (settings) {
        AssistantConnectionDialog(state.connection, { settings = false }, { mode, endpoint, secret, model ->
            settings = false
            onConfigure(mode, endpoint, secret, model)
        }, {
            settings = false
            onDisconnect()
        })
    }
    if (rename && workspace != null) {
        var name by remember(workspace.id) { mutableStateOf(workspace.name) }
        AssistantDialog({ rename = false }) {
            Text(stringResource(R.string.as_rename), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_name)) }, shape = TechnicalShapes.Field)
            DialogActions({ rename = false }, {
                onRename(workspace.id, name)
                rename = false
            }, name.isNotBlank())
        }
    }
    if (context && workspace != null) {
        AssistantDialog({ context = false }) {
            Text(stringResource(R.string.as_context), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.as_public_only))
            Text(stringResource(R.string.as_send_disclosure))
            workspace.scope.sources.forEach { source ->
                val value = state.notices.sources.getValue(source)
                Text(source.label, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.as_source_state, asTime(value.checkedAt), if (value.enabled && !value.failed) stringResource(R.string.as_source_available) else stringResource(R.string.as_source_unavailable)), style = MaterialTheme.typography.bodySmall)
            }
            val items = state.notices.items.filter { it.source in workspace.scope.sources && (workspace.scope.urls.isEmpty() || it.url in workspace.scope.urls) }
            Text(stringResource(R.string.as_cache_count, items.size), style = MaterialTheme.typography.bodyMedium)
            items.sortedByDescending { it.publishedDate }.take(12).forEach { item -> Text(item.title, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.as_coverage_help))
            TextButton(onRefresh, enabled = !state.busy) { Text(stringResource(R.string.as_refresh_sources)) }
            TextButton({ context = false }) { Text(stringResource(R.string.as_close)) }
        }
    }
    reference?.let { (source, ref) ->
        AssistantDialog({ reference = null }) {
            Text(source.title, style = MaterialTheme.typography.titleLarge)
            Text("${source.source.label} · ${source.publishedDate}", color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.as_evidence_version, source.version.take(12), asTime(source.fetchedAt), ref.paragraph + 1), style = MaterialTheme.typography.bodySmall)
            Text(ref.quote, style = MaterialTheme.typography.titleMedium)
            Text(source.paragraphs.getOrNull(ref.paragraph).orEmpty(), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.as_quote_limits), style = MaterialTheme.typography.bodySmall)
            TextButton({
                reference = null
                onOriginal(source.url)
            }) { Text(stringResource(R.string.as_original)) }
            TextButton({ reference = null }) { Text(stringResource(R.string.as_close)) }
        }
    }
    checklistTurn?.let { turn ->
        var text by remember(turn.id) { mutableStateOf(if (turn.checklist.isNotEmpty()) turn.checklist.joinToString("\n") { it.text } else turn.facts.filter { it.kind != AssistantFactKind.UNKNOWN }.joinToString("\n") { it.text.take(1200) }) }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        AssistantDialog({ checklistTurn = null }) {
            Text(stringResource(R.string.as_checklist_edit), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.as_checklist_help))
            OutlinedTextField(text, { text = it.take(24000) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_checklist_lines)) }, shape = TechnicalShapes.Field, minLines = 3, maxLines = 10)
            DialogActions({ checklistTurn = null }, {
                workspace?.let { w ->
                    onChecklist(
                        w.id,
                        turn.id,
                        lines.mapIndexed { index, line ->
                            val old = turn.checklist.getOrNull(index)
                            AssistantChecklistItem(old?.id ?: "task-$index", line, old?.checked == true && old.text == line)
                        }
                    )
                }
                checklistTurn = null
            }, lines.size <= 20 && lines.all { it.length <= 1200 })
        }
    }
    if (deletion != null || reset) {
        AlertDialog(onDismissRequest = {
            deletion = null
            reset = false
        }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(if (reset) R.string.as_reset else R.string.as_delete)) }, text = { Text(stringResource(R.string.as_delete_help)) }, confirmButton = {
            TextButton({
                if (reset) onClear() else deletion?.let(onDelete)
                deletion = null
                reset = false
            }) { Text(stringResource(R.string.as_delete)) }
        }, dismissButton = {
            TextButton({
                deletion = null
                reset = false
            }) { Text(stringResource(R.string.as_cancel)) }
        })
    }
}

@Composable private fun WorkspaceDialog(inbox: AnnouncementInbox, onDismiss: () -> Unit, onCreate: (String, AssistantScope) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var sources by rememberSaveable { mutableStateOf(inbox.sources.filterValues { it.enabled }.keys.sortedBy { if (it == AnnouncementSource.PORTAL) 0 else it.ordinal + 1 }.take(1).map { it.name }) }
    AssistantDialog(onDismiss) {
        Text(stringResource(R.string.as_create), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_name)) }, shape = TechnicalShapes.Field, singleLine = true)
        Text(stringResource(R.string.as_scope))
        AnnouncementSource.entries.forEach { source ->
            val enabled = inbox.sources.getValue(source).enabled
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(source.name in sources, { checked -> sources = if (checked) sources + source.name else sources - source.name }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(source.label)
                    if (!enabled) Text(stringResource(R.string.as_enable_source_first), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(R.string.as_send_disclosure), style = MaterialTheme.typography.bodySmall)
        DialogActions(onDismiss, { onCreate(name, AssistantScope(sources.map(AnnouncementSource::valueOf).toSet())) }, name.isNotBlank() && sources.isNotEmpty() && sources.all { inbox.sources.getValue(AnnouncementSource.valueOf(it)).enabled })
    }
}

@Composable private fun AssistantConnectionDialog(connection: StudyConnection, dismiss: () -> Unit, save: (StudyConnectionMode, String, String, String) -> Unit, disconnect: () -> Unit) {
    var mode by remember { mutableStateOf(connection.mode) }
    var endpoint by remember { mutableStateOf(if (connection.mode == StudyConnectionMode.GATEWAY) connection.endpoint else "") }
    var model by remember { mutableStateOf(connection.model) }
    // Credential stays only in dialog memory and is never saveable state.
    var secret by remember { mutableStateOf("") }
    val valid = if (mode == StudyConnectionMode.DIRECT) secret.length in 24..1024 && model.trim().matches(Regex("[A-Za-z0-9._-]{1,100}")) else secret.length in 32..512 && !secret.startsWith("sk-") && runCatching { StudyAiRules.endpoint(endpoint) }.isSuccess
    AssistantDialog(dismiss) {
        Text(stringResource(R.string.as_connection), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.as_connection_help))
        StudyConnectionMode.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == option, {
                    mode = option
                    secret = ""
                })
                Text(stringResource(if (option == StudyConnectionMode.DIRECT) R.string.as_direct else R.string.as_gateway))
            }
        }
        if (mode == StudyConnectionMode.DIRECT) {
            OutlinedTextField(model, { model = it.take(100) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_model)) }, shape = TechnicalShapes.Field, singleLine = true)
        } else {
            OutlinedTextField(endpoint, { endpoint = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_endpoint)) }, shape = TechnicalShapes.Field, singleLine = true)
        }
        OutlinedTextField(secret, { secret = it.take(1024) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.as_secret)) }, visualTransformation = PasswordVisualTransformation(), shape = TechnicalShapes.Field, singleLine = true)
        if (connection.configured) TextButton(disconnect) { Text(stringResource(R.string.as_disconnect)) }
        DialogActions(dismiss, {
            save(mode, endpoint.trim(), secret, model.trim())
            secret = ""
        }, valid)
    }
}

@Composable private fun AssistantDialog(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 680.dp), shape = TechnicalShapes.Dialog) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable private fun DialogActions(cancel: () -> Unit, save: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(cancel) { Text(stringResource(R.string.as_cancel)) }
        TextButton(save, enabled = enabled) { Text(stringResource(R.string.as_save)) }
    }
}
private fun asTime(seconds: Long): String = if (seconds <= 0) "—" else Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.of("Asia/Ho_Chi_Minh")).toString().replace('T', ' ').take(16) + " GMT+07"
