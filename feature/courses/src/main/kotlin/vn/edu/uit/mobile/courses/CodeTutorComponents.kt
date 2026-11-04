package vn.edu.uit.mobile.courses

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

/** Numbered native text; no HTML, browser, external images or executable renderer. */
@Composable internal fun TutorCodePane(snippet: TutorSnippet, quote: String? = null) {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(snippet.code, quote, colors) {
        buildAnnotatedString {
            append(snippet.code)
            val keywords = Regex("\\b(def|return|if|else|elif|for|while|class|fun|val|var|when|public|private|static|void|int|const|let|function|import|from|try|except|throw|raise|SELECT|FROM|WHERE|NULL|None|True|False)\\b")
            keywords.findAll(snippet.code).forEach { addStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1) }
            if (!quote.isNullOrEmpty()) {
                val start = snippet.code.indexOf(quote)
                if (start >= 0) addStyle(SpanStyle(background = colors.primaryContainer), start, start + quote.length)
            }
        }
    }
    val gutterWidth = with(LocalDensity.current) { (snippet.lastLine.toString().length * 8).sp.toDp() } + 16.dp
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CampusMono, fontSize = 13.sp, lineHeight = 21.sp)
    Surface(Modifier.fillMaxWidth(), shape = TechnicalShapes.Field, color = colors.surfaceContainerLowest) {
        Row(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text((snippet.firstLine..snippet.lastLine).joinToString("\n"), Modifier.widthIn(min = maxOf(42.dp, gutterWidth)).padding(horizontal = 8.dp).clearAndSetSemantics {}, style = style, color = colors.onSurfaceVariant, softWrap = false)
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 12.dp)) {
                SelectionContainer { Text(annotated, style = style, softWrap = false) }
            }
        }
    }
}

@Composable internal fun TutorCodeEvidenceDialog(snippet: TutorSnippet, reference: TutorCodeReference?, onDismiss: () -> Unit) {
    val selected = reference?.let { ref -> TutorSnippet(snippet.lines.subList(ref.startLine - snippet.firstLine, ref.endLine - snippet.firstLine + 1).joinToString("\n"), snippet.codeLanguage, ref.startLine) } ?: snippet
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val copyLabel = stringResource(R.string.ct_selection)
    TutorDialog(stringResource(R.string.ct_selection), onDismiss, actions = {
        TextButton({ clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, selected.code)) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_copy_selection)) }
        TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_close)) }
    }) {
        Text(stringResource(R.string.ct_lines, selected.firstLine, selected.lastLine), style = MaterialTheme.typography.titleMedium)
        TutorCodePane(selected, reference?.quote)
        if (reference != null) {
            Text(stringResource(R.string.ct_quote), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(reference.quote, fontFamily = CampusMono) }
        }
    }
}

@Composable internal fun TutorProposalDialog(request: TutorRequest, result: TutorResult, onDismiss: () -> Unit) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val copyLabel = stringResource(R.string.ct_selection)
    TutorDialog(stringResource(R.string.ct_proposal_title), onDismiss, actions = {
        TextButton({ clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, result.proposedCode)) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_copy_proposal)) }
        TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_close)) }
    }) {
        Text(stringResource(R.string.ct_no_execution), style = MaterialTheme.typography.bodySmall)
        Text(result.editReason)
        Text(stringResource(R.string.ct_original), style = MaterialTheme.typography.titleMedium)
        TutorCodePane(request.snippet)
        Text(stringResource(R.string.ct_suggested), style = MaterialTheme.typography.titleMedium)
        TutorCodePane(TutorSnippet(result.proposedCode, request.snippet.codeLanguage, request.snippet.firstLine))
    }
}

@Composable internal fun TutorImportsDialog(catalog: TutorCodeCatalog, onUse: (TutorCodeSource) -> Unit, onDismiss: () -> Unit) {
    TutorDialog(stringResource(R.string.ct_imports_title), onDismiss, actions = { TextButton(onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_close)) } }) {
        if (catalog.skipped > 0) InlineNotice(stringResource(R.string.ct_skipped, catalog.skipped))
        if (catalog.sources.isEmpty()) Text(stringResource(R.string.ct_no_imports))
        catalog.sources.forEach { source ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.title, style = MaterialTheme.typography.titleMedium)
                source.cell?.let { Text(stringResource(R.string.ct_cell, it), color = MaterialTheme.colorScheme.primary) }
                Text(if (source.codeLanguage == "Other") stringResource(R.string.ct_other_language) else source.codeLanguage, style = MaterialTheme.typography.labelMedium)
                Text(source.code.lineSequence().take(2).joinToString("\n").take(240), fontFamily = CampusMono, style = MaterialTheme.typography.bodySmall)
                TextButton({ onUse(source) }, modifier = Modifier.testTag("tutor-import-${source.id}"), shape = TechnicalShapes.Control) { Text(stringResource(R.string.ct_import_source)) }
                HorizontalDivider()
            }
        }
    }
}

/** Short evidence wraps its content; long code scrolls while actions remain reachable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TutorDialog(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight * .88f), shape = TechnicalShapes.Dialog, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                }
            }
        }
    }
}
