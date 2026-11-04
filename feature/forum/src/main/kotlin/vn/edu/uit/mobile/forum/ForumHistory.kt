package vn.edu.uit.mobile.forum

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

@Composable internal fun ForumHistoryDialog(state: ForumState, load: (ForumPost, Int) -> Unit, dismiss: () -> Unit, open: (String) -> Unit, image: suspend (String) -> ByteArray) {
    val post = state.historyPost ?: return
    val revision = state.history
    var side by rememberSaveable(post.id) { mutableIntStateOf(0) }
    val source = "${ForumLimits.ORIGIN}/t/${post.topicId}/${post.number}"
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.historyRetryAt) {
        while (now < state.historyRetryAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.f_history_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.f_post, post.number), style = MaterialTheme.typography.labelLarge)
                if (state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.historyError?.let {
                    Text(stringResource(errorLabel(it)))
                    if (state.historyRetryAt > now) Text(stringResource(R.string.f_wait, state.historyRetryAt - now))
                    TextButton(onClick = { load(post, state.historyRequestedVersion) }, enabled = !state.historyLoading && now >= state.historyRetryAt) { Text(stringResource(R.string.f_retry)) }
                }
                if (revision != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(R.string.f_diff_changes, R.string.f_diff_before, R.string.f_diff_after).forEachIndexed { index, label ->
                            FilterChip(selected = side == index, onClick = { side = index }, label = { Text(stringResource(label)) }, shape = vn.edu.uit.mobile.designsystem.TechnicalShapes.Control)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("forum-history-reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (revision != null) {
                        item {
                            Text(stringResource(R.string.f_version, revision.displayVersion), style = MaterialTheme.typography.titleMedium)
                            Text(revision.author + " · " + isoDate(revision.created), style = MaterialTheme.typography.labelMedium)
                            if (revision.reason.isNotBlank()) Text(revision.reason)
                            if (side == 0) Text(stringResource(R.string.f_diff_legend), style = MaterialTheme.typography.labelSmall)
                        }
                        if (revision.title.isNotEmpty()) item { Text(stringResource(R.string.f_title_changes), style = MaterialTheme.typography.labelLarge) }
                        itemsIndexed(revision.title, key = { i, _ -> "title-$i" }) { _, b -> ForumBlockView(revisionSide(b, side), open, image, source, open) }
                        if (revision.diffUnavailable) {
                            item { Text(stringResource(R.string.f_diff_unavailable)) }
                        } else if (revision.blocks.isEmpty()) {
                            item { Text(stringResource(R.string.f_no_body_change)) }
                        }
                        itemsIndexed(revision.blocks, key = { i, _ -> "body-$i" }) { _, b -> ForumBlockView(revisionSide(b, side), open, image, source, open) }
                    }
                }
                if (revision != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { revision.previous?.let { load(post, it) } }, enabled = revision.previous != null && !state.historyLoading) { Text(stringResource(R.string.f_previous_revision)) }
                        TextButton(onClick = { revision.next?.let { load(post, it) } }, enabled = revision.next != null && !state.historyLoading) { Text(stringResource(R.string.f_next_revision)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { open(source) }) { Text(stringResource(R.string.f_browser)) }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.f_history_close)) }
                }
            }
        }
    }
}

internal fun revisionSide(block: ForumBlock, side: Int): ForumBlock {
    if (side == 0) return block
    val runs = block.inline.filter { if (side == 1) it.change <= 0 else it.change >= 0 }.map { it.copy(change = 0, strike = if (it.change < 0) false else it.strike, underline = if (it.change > 0) false else it.underline) }
    return block.copy(
        text = if (block.inline.isEmpty()) block.text else runs.joinToString("") { it.text },
        inline = runs,
        children = block.children.map { revisionSide(it, side) },
        cells = block.cells.map { row -> row.map { cell -> cell.map { revisionSide(it, side) } } }
    )
}
