package vn.edu.uit.mobile.courses

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

@Composable internal fun KnowledgeEditor(record: KnowledgeRecord, node: KnowledgeNode?, edge: KnowledgeEdge?, enabled: Boolean, dismiss: () -> Unit, save: (KnowledgeGraph) -> Unit) {
    var inspected by remember(node?.id, edge?.id) { mutableStateOf<Pair<StudySource, String?>?>(null) }
    var label by remember(node?.id, edge?.id) { mutableStateOf(node?.label ?: edge!!.relation) }
    var definition by remember(node?.id, edge?.id) { mutableStateOf(node?.definition.orEmpty()) }
    var citations by remember(node?.id, edge?.id) { mutableStateOf(node?.citations ?: edge!!.citations) }
    val changed = if (node != null) record.graph.copy(nodes = record.graph.nodes.map { if (it.id == node.id) it.copy(label = label, definition = definition, citations = citations) else it }) else record.graph.copy(edges = record.graph.edges.map { if (it.id == edge!!.id) it.copy(relation = label, citations = citations) else it })
    val valid = runCatching { KnowledgeRules.graph(changed, record.sources) }.isSuccess
    PracticeDialog(
        stringResource(
            if (inspected != null) {
                R.string.pp_source_copy
            } else if (node != null) {
                R.string.km_edit_concept
            } else {
                R.string.km_relationship
            }
        ),
        { if (inspected != null) inspected = null else dismiss() },
        actions = {
            if (inspected != null) {
                TextButton({ inspected = null }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_close)) }
            } else {
                TextButton(dismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.st_cancel)) }
                TextButton({ save(changed) }, enabled = enabled && valid, shape = TechnicalShapes.Control, modifier = Modifier.testTag("knowledge-apply")) { Text(stringResource(R.string.km_apply)) }
            }
        }
    ) {
        if (inspected != null) {
            val source = inspected!!.first
            Text(source.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pp_evidence_help), style = MaterialTheme.typography.bodySmall)
            inspected!!.second?.let { quote -> SelectionContainer { Text(quote) } }
            SelectionContainer { Text(source.text) }
        } else {
            if (edge != null) {
                Text(record.graph.nodes.first { it.id == edge.from }.label + " → " + record.graph.nodes.first { it.id == edge.to }.label, style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.km_edit_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(label, { label = it.take(100) }, Modifier.fillMaxWidth().testTag("knowledge-edit-label"), enabled = enabled, label = { Text(stringResource(if (node != null) R.string.km_label else R.string.km_relation)) }, shape = TechnicalShapes.Field)
            if (node != null) OutlinedTextField(definition, { definition = it.take(800) }, Modifier.fillMaxWidth().testTag("knowledge-edit-definition"), enabled = enabled, label = { Text(stringResource(R.string.km_definition)) }, minLines = 3, shape = TechnicalShapes.Field)
            Text(stringResource(R.string.km_evidence), style = MaterialTheme.typography.titleMedium)
            citations.forEachIndexed { index, citation ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.sources.forEach { source -> FilterChip(citation.sourceId == source.id, { citations = citations.mapIndexed { i, c -> if (i == index) StudyCitation(source.id, "") else c } }, enabled = enabled, shape = TechnicalShapes.Control, label = { Text(source.title, Modifier.widthIn(max = 220.dp)) }) }
                }
                OutlinedTextField(citation.quote, { quote -> citations = citations.mapIndexed { i, c -> if (i == index) c.copy(quote = quote.take(500)) else c } }, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(stringResource(R.string.pp_quote_field, index + 1)) }, shape = TechnicalShapes.Field)
                record.sources.firstOrNull { it.id == citation.sourceId }?.let { source -> TextButton({ inspected = source to citation.quote }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_inspect_source)) } }
                if (citations.size > 1) TextButton({ citations = citations.filterIndexed { i, _ -> i != index } }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_remove_citation)) }
            }
            if (citations.size < 3) TextButton({ citations = citations + StudyCitation(record.sources.first().id, "") }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.pp_add_citation)) }
            if (!valid) InlineNotice(stringResource(R.string.km_input_error), true)
        }
    }
}
