package vn.edu.uit.mobile.dashboard

import android.content.ClipData
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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
    var menu by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val frozenNow = remember { kotlinx.datetime.Clock.System.now().epochSeconds }
    // Follow a newly submitted question, not every token or the end of a long answer.
    LaunchedEffect(workspace?.id, workspace?.turns?.size) {
        if (workspace != null && workspace.turns.isNotEmpty()) scroll.animateScrollToItem(assistantQuestionIndex(workspace))
    }
    Column(Modifier.fillMaxSize().testTag("assistant-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.as_back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.as_title), style = MaterialTheme.typography.titleLarge)
                if (workspace != null) Text(workspace.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (workspace != null) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.as_chat_options)) }
                    DropdownMenu(menu, { menu = false }, shape = TechnicalShapes.Control) {
                        DropdownMenuItem({ Text(stringResource(R.string.as_rename)) }, {
                            menu = false
                            rename = true
                        }, enabled = !state.busy)
                        DropdownMenuItem({ Text(stringResource(R.string.as_connection)) }, {
                            menu = false
                            settings = true
                        }, enabled = !state.busy)
                        DropdownMenuItem({ Text(stringResource(R.string.as_delete)) }, {
                            menu = false
                            deletion = workspace.id
                        })
                    }
                }
            } else {
                IconButton({ settings = true }, enabled = !state.busy) { Icon(Icons.Outlined.Settings, stringResource(R.string.as_connection)) }
            }
        }
        if (state.problem != null || state.library.storageFailed) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(
                        if (state.library.storageFailed) {
                            R.string.as_storage_error
                        } else if (state.problem == AssistantProblem.CONNECTION) {
                            R.string.as_connection_error
                        } else if (state.problem == AssistantProblem.ACCESS) {
                            R.string.as_access_error
                        } else if (state.problem == AssistantProblem.QUOTA) {
                            R.string.as_quota_error
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
        if (workspace != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Source, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ context = true }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.as_chat_context, workspace.scope.sources.size), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Outlined.ExpandMore, stringResource(R.string.as_context), Modifier.size(18.dp))
                    }
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
                if (workspace.turns.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            UitEmblem(Modifier.size(40.dp))
                            Text(stringResource(R.string.as_chat_welcome), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.as_empty_chat), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.as_chat_scope_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onRefresh) { Text(stringResource(R.string.as_refresh_sources)) }
                        }
                    }
                }
                workspace.turns.forEach { turn ->
                    item(key = "${turn.id}-question") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            Surface(Modifier.fillMaxWidth(.88f).testTag("assistant-user-${turn.id}"), shape = TechnicalShapes.Panel, color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.as_you), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                                    Text(turn.question, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    item(key = "${turn.id}-identity") {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UitEmblem(Modifier.size(28.dp))
                            Text(stringResource(R.string.as_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            if (turn.status == AssistantTurnStatus.COMPLETE && turn.model.isNotBlank()) Text(turn.model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (turn.status == AssistantTurnStatus.COMPLETE) {
                        val refs = turn.facts.flatMap { it.references }.distinct()
                        turn.facts.forEachIndexed { index, fact ->
                            item(key = "${turn.id}-fact-$index") {
                                Column(Modifier.fillMaxWidth().testTag("assistant-answer-${turn.id}-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (fact.kind != AssistantFactKind.FACT) Text(stringResource(if (fact.kind == AssistantFactKind.SUGGESTION) R.string.as_suggestion else R.string.as_unknown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                                    val numbers = fact.references.map { refs.indexOf(it) + 1 }.joinToString(", ")
                                    Text(fact.text + if (numbers.isEmpty()) "" else "  [$numbers]", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        item(key = "${turn.id}-actions") {
                            AssistantReplyActions(turn, state, frozenNow, refs, onReference = { reference = it }, onCoverage = { context = true }, onCopy = { coroutine.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("UIT Assistant", turn.facts.joinToString("\n\n") { it.text }))) } }, onChecklist = { checklistTurn = turn }, onCheck = { items -> onChecklist(workspace.id, turn.id, items) })
                        }
                    } else {
                        item(key = "${turn.id}-status") {
                            if (turn.status == AssistantTurnStatus.RUNNING && state.busy) {
                                AssistantTyping(state.progress)
                            } else {
                                Text(
                                    stringResource(
                                        when (turn.status) {
                                            AssistantTurnStatus.CANCELLED -> R.string.as_cancelled
                                            AssistantTurnStatus.INTERRUPTED -> R.string.as_interrupted
                                            AssistantTurnStatus.RUNNING -> R.string.as_running
                                            else -> R.string.as_failed
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (turn.status != AssistantTurnStatus.RUNNING) TextButton({ question = turn.question }) { Text(stringResource(R.string.as_reuse)) }
                            }
                        }
                    }
                }
            }
        }
        if (workspace != null) {
            val latestVisible by remember { derivedStateOf { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
            if (workspace.turns.isNotEmpty() && latestVisible < scroll.layoutInfo.totalItemsCount - 1) {
                TextButton({ coroutine.launch { scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) } }, Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.as_latest))
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!state.connection.configured) TextButton({ settings = true }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.as_setup)) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(question, { question = it.take(1200) }, Modifier.weight(1f).testTag("assistant-question"), placeholder = { Text(stringResource(R.string.as_question)) }, shape = TechnicalShapes.Field, maxLines = 4)
                        FilledIconButton({
                            if (state.busy) {
                                onStop()
                            } else {
                                onAsk(question)
                                question = ""
                            }
                        }, Modifier.size(56.dp).testTag("assistant-send"), enabled = state.busy || (state.connection.configured && question.isNotBlank() && workspace.turns.size < AssistantRules.TURNS), shape = TechnicalShapes.Control) {
                            Icon(if (state.busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, stringResource(if (state.busy) R.string.as_stop else R.string.as_send))
                        }
                    }
                    Text(stringResource(R.string.as_composer_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Number of lazy items preceding the most recent user message. */
private fun assistantQuestionIndex(workspace: AssistantWorkspace): Int = workspace.turns.dropLast(1).sumOf { turn ->
    if (turn.status == AssistantTurnStatus.COMPLETE) 3 + turn.facts.size else 3
}

@Composable
private fun AssistantTyping(progress: AssistantProgress?) {
    Column(Modifier.fillMaxWidth().testTag("assistant-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    when (progress?.tool) {
                        "search_announcements" -> R.string.as_searching
                        "read_announcement" -> R.string.as_reading
                        "writing" -> R.string.as_writing
                        "verifying" -> R.string.as_verifying
                        else -> R.string.as_thinking
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!progress?.draft.isNullOrBlank()) {
            Text(stringResource(R.string.as_draft), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Text(progress!!.draft, Modifier.testTag("assistant-draft"), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantReplyActions(
    turn: AssistantTurn,
    state: AssistantUiState,
    now: Long,
    refs: List<AssistantReference>,
    onReference: (Pair<AssistantEvidence, AssistantReference>) -> Unit,
    onCoverage: () -> Unit,
    onCopy: () -> Unit,
    onChecklist: () -> Unit,
    onCheck: (List<AssistantChecklistItem>) -> Unit
) {
    var sources by rememberSaveable(turn.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ sources = !sources }, Modifier.testTag("assistant-sources-${turn.id}"), shape = TechnicalShapes.Control, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Source, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.as_sources_count, turn.evidence.size))
                Icon(if (sources) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.as_copy), Modifier.size(20.dp)) }
            IconButton(onChecklist, enabled = !state.busy) { Icon(Icons.Outlined.Checklist, stringResource(if (turn.checklist.isEmpty()) R.string.as_checklist_create else R.string.as_checklist_edit), Modifier.size(20.dp)) }
        }
        if (sources) {
            Panel(Modifier.fillMaxWidth(), spacing = 8.dp) {
                Text(stringResource(R.string.as_historical, asTime(turn.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                turn.evidence.filter { e -> refs.any { it.evidenceId == e.id } }.forEach { evidence ->
                    Text("${evidence.source.label} · ${evidence.publishedDate}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(evidence.title, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        refs.forEachIndexed { index, ref ->
                            if (ref.evidenceId == evidence.id) {
                                TextButton({ onReference(evidence to ref) }, Modifier.testTag("assistant-reference-${turn.id}-$index"), shape = TechnicalShapes.Control, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("[${index + 1}]")
                                }
                            }
                        }
                    }
                }
                TextButton(onCoverage) { Text(stringResource(R.string.as_coverage)) }
            }
        }
        if (turn.evidence.any { it.offline || it.abbreviated }) Text(stringResource(R.string.as_partial_evidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        val changed = turn.evidence.any { e -> state.notices.items.find { it.url == e.url && it.bodyHtml.isNotEmpty() }?.let { assistantSourceVersion(it) != e.version } == true }
        if (changed || now - turn.at > 86400) Text(stringResource(R.string.as_stale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        turn.checklist.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(task.checked, { checked -> onCheck(turn.checklist.map { if (it.id == task.id) it.copy(checked = checked) else it }) }, enabled = !state.busy)
                Text(task.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
