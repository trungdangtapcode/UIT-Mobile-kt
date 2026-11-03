package vn.edu.uit.mobile.forum

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.edu.uit.mobile.designsystem.TechnicalShapes
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.forum.R

/** Compact controls keep the topic title as the phone feed's visual anchor. */
@Composable internal fun ForumFeedControls(state: ForumState, onDiscover: (ForumFilter, ForumPeriod, ForumTag?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var filters by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { filters = true }, modifier = Modifier.fillMaxWidth().testTag("forum-filter"), shape = TechnicalShapes.Control, enabled = !state.loading) {
                Text(stringResource(filterLabel(state.filter)))
            }
            DropdownMenu(expanded = filters, onDismissRequest = { filters = false }) {
                state.filters.forEach { filter ->
                    DropdownMenuItem(text = { Text(stringResource(filterLabel(filter))) }, onClick = {
                        filters = false
                        onDiscover(filter, state.period, state.tag)
                    })
                }
            }
        }
        if (state.filter == ForumFilter.TOP) {
            var periods by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { periods = true }, modifier = Modifier.fillMaxWidth().testTag("forum-period"), shape = TechnicalShapes.Control, enabled = !state.loading) { Text(stringResource(periodLabel(state.period))) }
                DropdownMenu(expanded = periods, onDismissRequest = { periods = false }) {
                    ForumPeriod.entries.forEach { period ->
                        DropdownMenuItem(text = { Text(stringResource(periodLabel(period))) }, onClick = {
                            periods = false
                            onDiscover(state.filter, period, state.tag)
                        })
                    }
                }
            }
        }
    }
    state.tag?.let { tag ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tag.name, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { onDiscover(state.filter, state.period, null) }, enabled = !state.loading) { Text(stringResource(R.string.f_all_tags)) }
        }
    }
}

internal fun filterLabel(filter: ForumFilter) = when (filter) {
    ForumFilter.LATEST -> R.string.f_latest
    ForumFilter.TOP -> R.string.f_top
    ForumFilter.HOT -> R.string.f_hot
    ForumFilter.VOTES -> R.string.f_votes
    ForumFilter.NEW -> R.string.f_new
    ForumFilter.UNREAD -> R.string.f_unread
    ForumFilter.UNSEEN -> R.string.f_unseen
    ForumFilter.READ -> R.string.f_read
    ForumFilter.POSTED -> R.string.f_posted
    ForumFilter.BOOKMARKS -> R.string.f_account_bookmarks
}
internal fun periodLabel(period: ForumPeriod) = when (period) {
    ForumPeriod.DAILY -> R.string.f_daily
    ForumPeriod.WEEKLY -> R.string.f_weekly
    ForumPeriod.MONTHLY -> R.string.f_monthly
    ForumPeriod.QUARTERLY -> R.string.f_quarterly
    ForumPeriod.YEARLY -> R.string.f_yearly
    ForumPeriod.ALL -> R.string.f_all_time
}
