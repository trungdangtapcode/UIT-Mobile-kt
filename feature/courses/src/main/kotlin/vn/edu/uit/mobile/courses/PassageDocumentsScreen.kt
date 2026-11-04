package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

/** Saved-file readiness and reviewed transfers. There is deliberately no remote-sync claim. */
@Composable internal fun PassageDocumentsScreen(
    state: PassageSearchState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onCheck: (PassageConfig, PassageEmbeddingConfig?) -> Unit,
    onUpdate: () -> Unit,
    onDismissPlan: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onConfigure: (StudyConnectionMode, String, String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    BackHandler(onBack = onBack)
    val index = state.index
    var semantic by rememberSaveable { mutableStateOf(index?.embedding != null) }
    var config by remember { mutableStateOf(index?.config ?: PassageConfig()) }
    var embedding by remember { mutableStateOf(index?.embedding ?: PassageEmbeddingConfig()) }
    var settings by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf(false) }
    val busy = state.work != null
    val activeEmbedding = if (semantic) embedding else null
    val plan = state.plan?.takeIf { it.config == config && it.embedding == activeEmbedding && it.files.map { file -> file.spec.id }.toSet() == state.selected }
    val direct = state.connection.configured && state.connection.mode == StudyConnectionMode.DIRECT
    Column(Modifier.fillMaxSize().testTag("passage-documents")) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onBack, shape = TechnicalShapes.Control) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Text(stringResource(R.string.ps_back_to_search))
                    }
                    IconButton({ details = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.ps_details)) }
                }
                Text(stringResource(R.string.ps_materials_title), style = MaterialTheme.typography.headlineMedium)
                Text(state.course?.shortname.orEmpty(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            item {
                Text(stringResource(R.string.ps_saved_scope), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ps_remote_unknown), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (plan != null) Text(stringResource(R.string.ps_last_checked, timeLabel(plan.checkedAt)), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                index?.let { Text(stringResource(R.string.ps_last_update, timeLabel(it.builtAt)), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.problem != null) item { InlineNotice(stringResource(if (state.problem == PassageWork.BUILD) R.string.ps_update_error else R.string.ps_error), true) }
            if (state.stopped) item { InlineNotice(stringResource(R.string.ps_update_stopped)) }
            if (index?.semanticFailed == true) item { InlineNotice(stringResource(R.string.ps_embedding_failed)) }
            if (busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.work == PassageWork.BUILD && state.total > 0) {
                            LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.ps_progress, state.completed, state.total))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(stringResource(if (state.work == PassageWork.BUILD) R.string.ps_update_stage else R.string.ps_local_check_stage), style = MaterialTheme.typography.bodySmall)
                        TextButton(onStop) { Text(stringResource(R.string.ps_stop)) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ps_selected_limit, state.selected.size, PassageRules.DOCUMENTS), style = MaterialTheme.typography.titleMedium)
                    TextButton(onRefresh, enabled = !busy) { Text(stringResource(R.string.ps_reload)) }
                }
            }
            if (state.candidates.isEmpty()) item { Text(stringResource(R.string.ps_no_documents)) }
            items(state.candidates, key = { it.spec.id }) { candidate ->
                val checked = plan?.files?.firstOrNull { it.spec.id == candidate.spec.id }
                val prepared = index?.documents?.any { it.spec.id == candidate.spec.id } == true
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(candidate.spec.id in state.selected, { onToggle(candidate.spec.id) }, enabled = !busy && (candidate.spec.id in state.selected || candidate.downloaded && state.selected.size < PassageRules.DOCUMENTS), modifier = Modifier.testTag("passage-source-${candidate.spec.id}").semantics { contentDescription = candidate.spec.file.name })
                    Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.spec.file.name, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.ps_file_format, candidate.spec.file.name.substringAfterLast('.', "").uppercase(java.util.Locale.ROOT)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val label = when {
                            !candidate.downloaded -> R.string.ps_download_first
                            checked != null -> when (checked.status) {
                                PassageFileStatus.NEW -> R.string.ps_file_new
                                PassageFileStatus.CHANGED -> R.string.ps_file_changed
                                PassageFileStatus.KEYWORDS_READY -> R.string.ps_file_keywords
                                PassageFileStatus.SMART_READY -> R.string.ps_file_smart
                                PassageFileStatus.UNAVAILABLE -> R.string.ps_file_unavailable
                            }
                            prepared -> R.string.ps_file_unchecked
                            else -> R.string.ps_file_saved
                        }
                        Text(stringResource(label), color = if (checked?.status in setOf(PassageFileStatus.UNAVAILABLE, PassageFileStatus.CHANGED)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        if (checked?.partial == true) Text(stringResource(R.string.ps_partial), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ps_smart_option), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.ps_smart_option_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val label = stringResource(R.string.ps_smart_option)
                    Switch(semantic, {
                        semantic = it
                        onDismissPlan()
                    }, enabled = !busy, modifier = Modifier.testTag("passage-enable-semantic").semantics { contentDescription = label })
                }
                if (semantic && !direct) TextButton({ connection = true }, enabled = !busy) { Text(stringResource(R.string.ps_connect_action)) }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan == null) {
                    Text(stringResource(R.string.ps_check_no_api), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.ps_plan_summary, plan.reused, plan.transfers), style = MaterialTheme.typography.bodySmall)
                }
                if (plan != null && plan.canUpdate) {
                    Button({ review = true }, Modifier.fillMaxWidth().testTag("passage-build"), enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ps_review_update)) }
                    TextButton({ onCheck(config, activeEmbedding) }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.ps_check_again)) }
                } else {
                    Button({ onCheck(config, activeEmbedding) }, Modifier.fillMaxWidth().testTag("passage-check"), enabled = !busy && state.selected.isNotEmpty(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.ps_check_files)) }
                    if (plan != null && !plan.canUpdate) Text(stringResource(R.string.ps_unavailable_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (review && plan != null) {
        PassageDialog({ review = false }, footer = {
            Button({
                review = false
                onUpdate()
            }, Modifier.fillMaxWidth().testTag("passage-confirm-update"), enabled = !busy && (plan.transfers == 0 || direct), shape = TechnicalShapes.Control) { Text(stringResource(if (plan.transfers > 0) R.string.ps_confirm_transfer else R.string.ps_confirm_local)) }
            TextButton({ review = false }) { Text(stringResource(R.string.ps_cancel)) }
        }) {
            Text(stringResource(R.string.ps_review_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.ps_review_files, plan.files.size, plan.passages))
            if (plan.embedding != null) {
                Text(stringResource(R.string.ps_reuse_detail, plan.reused), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ps_new_transfer, plan.transfers, plan.characters))
            }
            if (plan.transfers > 0) {
                Text(stringResource(R.string.ps_transfer_detail), style = MaterialTheme.typography.bodySmall)
                Text("${plan.embedding?.model} · ${plan.embedding?.dimensions}", style = MaterialTheme.typography.labelMedium)
                if (!direct) Text(stringResource(R.string.ps_direct_required), color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.ps_zero_transfer), color = MaterialTheme.colorScheme.primary)
            }
            if (plan.embedding == null && index?.vectors?.isNotEmpty() == true) Text(stringResource(R.string.ps_remove_smart_warning), color = MaterialTheme.colorScheme.tertiary)
            if (plan.removed > 0) Text(stringResource(R.string.ps_removed, plan.removed))
            Text(stringResource(R.string.ps_atomic_update), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (details) {
        PassageDialog({ details = false }, footer = { TextButton({ details = false }) { Text(stringResource(R.string.ps_close)) } }) {
            Text(stringResource(R.string.ps_details), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.ps_local_check_explained), style = MaterialTheme.typography.bodyMedium)
            index?.let {
                Text(stringResource(R.string.ps_coverage, it.documents.size, it.passages.size))
                it.embedding?.let { e -> Text(stringResource(R.string.ps_embedding_info, e.model, e.dimensions, it.embeddingTokens), style = MaterialTheme.typography.bodySmall) }
                it.update?.let { receipt ->
                    Text(stringResource(R.string.ps_receipt, receipt.reused, receipt.embedded, receipt.requests), style = MaterialTheme.typography.bodySmall)
                    if (receipt.removed > 0) Text(stringResource(R.string.ps_removed_receipt, receipt.removed), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({
                details = false
                settings = true
            }) { Text(stringResource(R.string.ps_advanced)) }
            TextButton({
                details = false
                connection = true
            }) { Text(stringResource(R.string.ps_connection)) }
            TextButton({
                details = false
                deletion = true
            }, enabled = index != null) { Text(stringResource(R.string.ps_delete)) }
        }
    }
    if (deletion) {
        AlertDialog(onDismissRequest = { deletion = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.ps_delete)) }, text = { Text(stringResource(R.string.ps_delete_help)) }, confirmButton = {
            TextButton({
                deletion = false
                onClear()
            }) { Text(stringResource(R.string.ps_delete)) }
        }, dismissButton = { TextButton({ deletion = false }) { Text(stringResource(R.string.ps_cancel)) } })
    }
    if (settings) {
        PassageSettingsDialog(config, embedding, { settings = false }) { c, e ->
            config = c
            embedding = e
            settings = false
            onDismissPlan()
        }
    }
    if (connection) {
        StudyConnectionDialog(state.connection, { connection = false }, { mode, endpoint, token, model ->
            connection = false
            onConfigure(mode, endpoint, token, model)
        }, {
            connection = false
            onDisconnect()
        }, null, busy)
    }
}

private fun timeLabel(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

@Composable private fun PassageSettingsDialog(initial: PassageConfig, initialEmbedding: PassageEmbeddingConfig, onDismiss: () -> Unit, onSave: (PassageConfig, PassageEmbeddingConfig) -> Unit) {
    var size by remember { mutableStateOf(initial.characters.toString()) }
    var overlap by remember { mutableStateOf(initial.overlap.toString()) }
    var k1 by remember { mutableStateOf(initial.k1.toString()) }
    var b by remember { mutableStateOf(initial.b.toString()) }
    var candidates by remember { mutableStateOf(initial.candidates.toString()) }
    var fusion by remember { mutableStateOf(initial.fusionK.toString()) }
    var model by remember { mutableStateOf(initialEmbedding.model) }
    var dimensions by remember { mutableStateOf(initialEmbedding.dimensions.toString()) }
    val config = runCatching { PassageConfig(size.toInt(), overlap.toInt(), k1.toDouble(), b.toDouble(), candidates.toInt(), fusion.toInt()).also { it.validate() } }.getOrNull()
    val embedding = runCatching { PassageEmbeddingConfig(model, dimensions.toInt()).also { it.validate() } }.getOrNull()
    PassageDialog(onDismiss, footer = {
        Button({ onSave(requireNotNull(config), requireNotNull(embedding)) }, Modifier.fillMaxWidth(), enabled = config != null && embedding != null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ps_save_settings)) }
        TextButton(onDismiss) { Text(stringResource(R.string.ps_cancel)) }
    }) {
        Text(stringResource(R.string.ps_advanced), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ps_parameters_help), style = MaterialTheme.typography.bodySmall)
        listOf(Triple(R.string.ps_chunk, size, { v: String -> size = v }), Triple(R.string.ps_overlap, overlap, { v: String -> overlap = v }), Triple(R.string.ps_k1, k1, { v: String -> k1 = v }), Triple(R.string.ps_b, b, { v: String -> b = v }), Triple(R.string.ps_candidates, candidates, { v: String -> candidates = v }), Triple(R.string.ps_rrf, fusion, { v: String -> fusion = v }), Triple(R.string.ps_embedding_model, model, { v: String -> model = v }), Triple(R.string.ps_dimensions, dimensions, { v: String -> dimensions = v })).forEach { (label, value, change) ->
            OutlinedTextField(value, { change(it.take(100)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true, shape = TechnicalShapes.Field)
        }
        if (config == null || embedding == null) Text(stringResource(R.string.ps_invalid_parameters), color = MaterialTheme.colorScheme.error)
    }
}
