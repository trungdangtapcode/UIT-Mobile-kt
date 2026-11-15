package vn.edu.uit.mobile.dashboard

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.dashboard.R

@Composable fun UmaScreen(vm: UmaViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.initialize() }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    val uri = LocalUriHandler.current
    UmaContent(state, onBack, onLogin, vm::ask, vm::stop, vm::clear, vm::disconnect, vm::sources) { url ->
        UmaUrls.link(url)?.let { runCatching { uri.openUri(it) } }
    }
}

/** Intent: a student asks UMA; the answer leads, account and source scope remain inspectable.
 * Existing Plex type, UIT palette, quiet canvas, 4dp grid and asymmetric frames define depth.
 * User turns use the shared 88% frame; server text, tables and citations keep one phone column.
 */
@Composable
fun UmaContent(
    state: UmaState,
    onBack: () -> Unit = {},
    onLogin: () -> Unit = {},
    onAsk: (String, Long?) -> Unit = { _, _ -> },
    onStop: () -> Unit = {},
    onClear: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSources: (Set<UmaSource>) -> Unit = {},
    onLink: (String) -> Unit = {}
) {
    var question by remember(state.revision) { mutableStateOf("") }
    var suggestionId by remember(state.revision) { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var selected by remember(state.selected) { mutableStateOf(state.selected) }
    val sourceLabels = state.selected.map { sourceName(it) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    LaunchedEffect(state.turns.lastOrNull()?.id) { if (state.turns.isNotEmpty()) scroll.animateScrollToItem(state.turns.lastIndex) }
    Column(Modifier.fillMaxSize().testTag("uma-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.uma_back)) }
            UitEmblem(Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.uma_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.uma_official), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.uma_options)) }
                DropdownMenu(menu, { menu = false }, shape = TechnicalShapes.Control) {
                    DropdownMenuItem({ Text(stringResource(R.string.uma_new)) }, {
                        menu = false
                        dialog = "clear"
                    }, enabled = state.turns.isNotEmpty())
                    DropdownMenuItem({ Text(stringResource(R.string.uma_privacy_title)) }, {
                        menu = false
                        dialog = "privacy"
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.uma_web)) }, {
                        menu = false
                        onLink(UmaUrls.EMBED)
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.uma_disconnect)) }, {
                        menu = false
                        dialog = "disconnect"
                    }, enabled = state.identity != null || state.error != null)
                }
            }
        }
        if (state.identity != null) {
            TextButton({
                selected = state.selected
                dialog = "sources"
            }, Modifier.fillMaxWidth().testTag("uma-sources"), enabled = !state.busy && !state.loading, shape = TechnicalShapes.Control, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(requireNotNull(state.identity).name, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.uma_account, requireNotNull(state.identity).id), style = MaterialTheme.typography.bodySmall)
                    Text(sourceLabels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.ExpandMore, stringResource(R.string.uma_sources_title))
            }
        }
        if (state.error != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(errorText(requireNotNull(state.error))), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("uma-error"))
                if (state.error in setOf(UmaFailure.EXPIRED, UmaFailure.CSRF, UmaFailure.STORAGE)) TextButton(onLogin, enabled = !state.loading) { Text(stringResource(R.string.uma_reconnect)) }
            }
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.uma_connecting), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("uma-conversation"), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state.turns.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.uma_welcome), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.uma_intro), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.uma_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.identity == null) {
                            Button(onLogin, Modifier.fillMaxWidth().testTag("uma-login"), enabled = !state.loading, shape = TechnicalShapes.Control) { Text(stringResource(R.string.uma_sign_in)) }
                            Text(stringResource(R.string.uma_separate_session), style = MaterialTheme.typography.bodySmall)
                        } else {
                            listOf(R.string.uma_prompt_rules, R.string.uma_prompt_personal, R.string.uma_prompt_notices).forEach { resource ->
                                val prompt = stringResource(resource)
                                OutlinedButton({
                                    question = prompt
                                    suggestionId = null
                                }, Modifier.fillMaxWidth(), shape = TechnicalShapes.Control, enabled = !state.busy) { Text(prompt) }
                            }
                            Text(stringResource(R.string.uma_choose_personal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(state.turns, key = { it.id }) { turn ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(Modifier.fillMaxWidth(.88f), shape = TechnicalShapes.Panel, color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.uma_you), style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(turn.question, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UitEmblem(Modifier.size(24.dp))
                        Text(stringResource(R.string.uma_title), style = MaterialTheme.typography.titleSmall)
                    }
                    when (turn.status) {
                        UmaTurnStatus.RUNNING -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("uma-pending"))
                            Text(stringResource(R.string.uma_waiting), style = MaterialTheme.typography.bodyMedium)
                        }
                        UmaTurnStatus.COMPLETE -> turn.answer?.let { answer ->
                            UmaAnswerContent(answer, onLink) { suggestion ->
                                question = suggestion.question
                                suggestionId = suggestion.id
                            }
                            TextButton({ scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("UMA", answer.text))) } }) { Text(stringResource(R.string.uma_copy)) }
                        }
                        else -> {
                            Text(stringResource(if (turn.status == UmaTurnStatus.CANCELLED) R.string.uma_cancelled else R.string.uma_failed), style = MaterialTheme.typography.bodyMedium)
                            TextButton({
                                question = turn.question
                                suggestionId = null
                            }) { Text(stringResource(R.string.uma_reuse)) }
                        }
                    }
                }
            }
            if (state.turns.isNotEmpty()) item(key = "uma-end") { Spacer(Modifier.height(1.dp)) }
        }
        if (state.identity != null && state.turns.isNotEmpty() && scroll.canScrollForward) {
            TextButton({ scope.launch { scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) } }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.uma_latest)) }
        }
        if (state.identity != null || state.turns.isNotEmpty()) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(question, {
                        question = it.take(1000)
                        suggestionId = null
                    }, Modifier.weight(1f).testTag("uma-question"), placeholder = { Text(stringResource(R.string.uma_question)) }, shape = TechnicalShapes.Field, maxLines = 4, enabled = !state.loading)
                    FilledIconButton({
                        if (state.busy) {
                            onStop()
                        } else if (question.isNotBlank()) {
                            onAsk(question.trim(), suggestionId)
                            question = ""
                            suggestionId = null
                        }
                    }, Modifier.size(56.dp).testTag("uma-send"), enabled = state.busy || (!state.loading && state.identity != null && question.isNotBlank()), shape = TechnicalShapes.Control) {
                        Icon(if (state.busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, stringResource(if (state.busy) R.string.uma_stop else R.string.uma_send))
                    }
                }
                Text(stringResource(R.string.uma_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            shape = TechnicalShapes.Dialog,
            title = {
                Text(
                    stringResource(
                        when (dialog) {
                            "sources" -> R.string.uma_sources_title
                            "clear" -> R.string.uma_new
                            "disconnect" -> R.string.uma_disconnect
                            else -> R.string.uma_privacy_title
                        }
                    )
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dialog == "sources") {
                        Text(stringResource(R.string.uma_source_reset))
                        UmaSource.entries.filter { it in state.available }.forEach { source ->
                            Row(Modifier.fillMaxWidth().clickable { selected = if (source in selected) selected - source else selected + source }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(source in selected, { selected = if (it) selected + source else selected - source })
                                Text(sourceName(source))
                            }
                        }
                        Text(stringResource(R.string.uma_personal_notice), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(if (dialog == "privacy") R.string.uma_privacy_detail else R.string.uma_clear_notice))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    when (dialog) {
                        "clear" -> onClear()
                        "disconnect" -> onDisconnect()
                        "sources" -> if (selected != state.selected) onSources(selected)
                    }
                    dialog = null
                }, enabled = dialog != "sources" || selected.isNotEmpty()) { Text(stringResource(if (dialog == "privacy") R.string.uma_close else R.string.uma_confirm)) }
            },
            dismissButton = { if (dialog != "privacy") TextButton({ dialog = null }) { Text(stringResource(R.string.uma_cancel)) } }
        )
    }
}

@Composable private fun UmaAnswerContent(answer: UmaAnswer, onLink: (String) -> Unit, onSuggestion: (UmaSuggestion) -> Unit) {
    var sources by remember(answer) { mutableStateOf(false) }
    var plain by remember(answer) { mutableStateOf(false) }
    if (answer.result == "no_source") Text(stringResource(R.string.uma_no_source), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (answer.followUp && answer.understood.isNotBlank()) Text(stringResource(R.string.uma_understood, answer.understood), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    answer.week?.let { week ->
        Text(week.title, style = MaterialTheme.typography.titleMedium)
        week.lessons.groupBy { it.date }.forEach { (date, lessons) ->
            Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            lessons.forEach { lesson ->
                Panel(Modifier.fillMaxWidth()) {
                    Text(lesson.subject, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(lesson.code, lesson.periods, lesson.time, lesson.room, lesson.lecturer, lesson.note).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (week.lessons.isEmpty()) Text(stringResource(R.string.uma_no_lessons))
        week.navigation.forEach { suggestion -> OutlinedButton({ onSuggestion(suggestion) }, shape = TechnicalShapes.Control) { Text(suggestion.question) } }
    }
    answer.display.forEach { UmaDisplayContent(it) }
    if (answer.week != null || answer.display.isNotEmpty()) TextButton({ plain = !plain }) { Text(stringResource(if (plain) R.string.uma_hide_text else R.string.uma_show_text)) }
    if (plain || answer.week == null && answer.display.isEmpty()) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                answer.blocks.forEach { block -> SemanticArticleBlock(block, onLink, { error("UMA does not fetch remote media") }, UmaUrls.EMBED, onLink, canLoadImage = { false }) }
            }
        }
    }
    answer.warnings.forEach { warning -> Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    answer.links.forEach { link ->
        Column {
            if (link.url != null) TextButton({ onLink(requireNotNull(link.url)) }) { Text(link.title) } else Text(link.title, style = MaterialTheme.typography.labelLarge)
            if (link.detail.isNotBlank()) Text(link.detail, style = MaterialTheme.typography.bodyMedium)
            if (link.summary.isNotBlank()) Text(link.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (answer.citations.isNotEmpty()) {
        TextButton({ sources = !sources }, Modifier.testTag("uma-citations")) {
            Text(stringResource(R.string.uma_citations, answer.citations.size))
            Icon(if (sources) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (sources) {
            answer.citations.forEach { cite ->
                Panel(Modifier.fillMaxWidth().testTag("uma-citation-${cite.number}")) {
                    Text("[${cite.number}] ${cite.title}", style = MaterialTheme.typography.titleSmall)
                    listOf(cite.location, cite.appliesTo).filter(String::isNotBlank).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (cite.lowConfidence) Text(stringResource(R.string.uma_low_confidence), color = MaterialTheme.colorScheme.error)
                    if (cite.scanned) Text(stringResource(R.string.uma_scanned), style = MaterialTheme.typography.bodySmall)
                    Row {
                        cite.url?.let { url -> TextButton({ onLink(url) }) { Text(stringResource(R.string.uma_view_source)) } }
                        cite.original?.let { url -> TextButton({ onLink(url) }) { Text(stringResource(R.string.uma_original)) } }
                    }
                }
            }
        }
    }
    if (answer.unsupported) {
        Text(stringResource(R.string.uma_extra_web), style = MaterialTheme.typography.bodySmall)
        TextButton({ onLink(UmaUrls.EMBED) }) { Text(stringResource(R.string.uma_web)) }
    }
    answer.suggestions.forEach { suggestion ->
        OutlinedButton({ onSuggestion(suggestion) }, shape = TechnicalShapes.Control) {
            Column {
                Text(suggestion.question)
                if (!suggestion.approved) Text(stringResource(R.string.uma_unapproved), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun UmaDisplayContent(display: UmaDisplay) {
    var expanded by remember(display) { mutableStateOf(display.expanded) }
    if (display.kind == "nhom") {
        if (display.title.isNotBlank()) {
            TextButton({ expanded = !expanded }) {
                Text(display.title, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
        }
        if (expanded || display.title.isBlank()) {
            if (display.note.isNotBlank()) Text(display.note, style = MaterialTheme.typography.bodyMedium)
            display.children.forEach { UmaDisplayContent(it) }
        }
    } else {
        if (display.title.isNotBlank()) Text(display.title, style = MaterialTheme.typography.titleMedium)
        if (display.kind == "bang") {
            Column(Modifier.horizontalScroll(rememberScrollState()).testTag("uma-table")) {
                if (display.columns.isNotEmpty()) Row { display.columns.forEach { Text(it, Modifier.width(180.dp).padding(8.dp), style = MaterialTheme.typography.labelLarge) } }
                display.rows.forEach { row ->
                    Row { row.forEach { cell -> Text(cell.text, Modifier.width(180.dp).padding(8.dp), fontWeight = if (cell.bold) FontWeight.SemiBold else FontWeight.Normal, fontFamily = if (cell.code) CampusMono else null, style = MaterialTheme.typography.bodyMedium) } }
                    HorizontalDivider()
                }
            }
        } else {
            display.rows.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { index, cell ->
                        Text(
                            cell.text,
                            style = if (index == 0) {
                                MaterialTheme.typography.labelMedium
                            } else if (display.kind == "the" && index == 1) {
                                MaterialTheme.typography.headlineSmall
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = if (index == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        if (display.note.isNotBlank()) Text(display.note, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun sourceName(source: UmaSource): String = stringResource(
    when (source) {
        UmaSource.GENERAL -> R.string.uma_source_general
        UmaSource.REGULATIONS -> R.string.uma_source_rules
        UmaSource.PERSONAL -> R.string.uma_source_personal
        UmaSource.ANNOUNCEMENTS -> R.string.uma_source_notices
    }
)
private fun errorText(reason: UmaFailure) = when (reason) {
    UmaFailure.NETWORK -> R.string.uma_error_network
    UmaFailure.EXPIRED -> R.string.uma_error_expired
    UmaFailure.CSRF -> R.string.uma_error_csrf
    UmaFailure.RATE_LIMIT -> R.string.uma_error_limit
    UmaFailure.SERVER -> R.string.uma_error_server
    UmaFailure.RESPONSE -> R.string.uma_error_response
    UmaFailure.STORAGE -> R.string.uma_error_storage
}
