package vn.edu.uit.mobile.courses

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingDialog(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight * .88f), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("reading-dialog-body"), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                }
            }
        }
    }
}

@Composable internal fun ReadingNoteDialog(draft: ReadingNoteDraft, busy: Boolean, pending: Boolean, problem: ReadingProblem?, onDismiss: () -> Unit, onText: (String) -> Unit, onSave: () -> Unit) {
    ReadingDialog(stringResource(R.string.rd_note_title, draft.page), { if (!busy) onDismiss() }, actions = {
        TextButton(onDismiss, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) }
        TextButton(onSave, enabled = !busy && draft.text.isNotBlank() && draft.text.length <= 4000, shape = TechnicalShapes.Control, modifier = Modifier.testTag("reading-save-note")) { Text(stringResource(if (pending) R.string.rd_retry else R.string.rd_save_note)) }
    }) {
        Text(stringResource(R.string.rd_note_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.text, onText, Modifier.fillMaxWidth().testTag("reading-note-editor"), enabled = !busy && !pending, label = { Text(stringResource(R.string.rd_note_label)) }, minLines = 4, maxLines = 10, shape = TechnicalShapes.Field)
        problem?.let { InlineNotice(readingProblemText(it), true) }
    }
}

@Composable internal fun ReadingPageDialog(page: Int, pages: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var number by remember { mutableStateOf(page.toString()) }
    val selected = number.toIntOrNull()?.takeIf { it in 1..pages }
    ReadingDialog(stringResource(R.string.rd_jump), onDismiss, actions = {
        TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) }
        TextButton({ selected?.let(onGo) }, enabled = selected != null, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_go)) }
    }) {
        OutlinedTextField(number, { number = it.take(5) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rd_jump_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = TechnicalShapes.Field)
        Text(stringResource(R.string.rd_page_count, page, pages))
    }
}

@Composable internal fun ReadingConfirm(title: String, body: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = TechnicalShapes.Dialog,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { TextButton(onDismiss, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_cancel)) } },
        confirmButton = { TextButton(onConfirm, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.rd_delete)) } }
    )
}

@Composable internal fun readingProblemText(problem: ReadingProblem) = stringResource(
    when (problem) {
        ReadingProblem.FILE -> R.string.rd_file_error
        ReadingProblem.PDF -> R.string.rd_pdf_error
        ReadingProblem.PAGE -> R.string.rd_page_error
        ReadingProblem.STORE -> R.string.rd_store_error
        ReadingProblem.SAVE, ReadingProblem.STALE -> R.string.rd_save_error
        ReadingProblem.REMOVED -> R.string.rd_removed
        ReadingProblem.EXPORT -> R.string.rd_export_error
        ReadingProblem.LIMIT -> R.string.rd_limit_error
    }
)
