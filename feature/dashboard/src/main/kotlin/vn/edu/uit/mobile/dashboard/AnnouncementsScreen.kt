package vn.edu.uit.mobile.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.dashboard.R

class AnnouncementsViewModel(val repository: AnnouncementRepository) : ViewModel() {
    val inbox = repository.state
    val busy = MutableStateFlow(false)
    val problem = MutableStateFlow(false)
    val document = MutableStateFlow<AnnouncementDocument?>(null)
    private var detailJob: Job? = null
    private var detailGeneration = 0
    fun refresh() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                repository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem.value = true
            } finally {
                busy.value = false
            }
        }
    }
    fun act(action: suspend AnnouncementRepository.() -> Unit) {
        viewModelScope.launch {
            try {
                repository.action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem.value = true
            }
        }
    }
    fun open(url: String) {
        detailJob?.cancel()
        val epoch = ++detailGeneration
        if (document.value?.item?.url != url) document.value = null
        detailJob = viewModelScope.launch {
            try {
                val result = repository.open(url)
                if (epoch == detailGeneration) {
                    document.value = result
                    repository.markRead(url, result.item.revision)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem.value = true
            }
        }
    }
    fun close() {
        detailGeneration++
        detailJob?.cancel()
        document.value = null
    }
}

@Composable
fun AnnouncementsScreen(vm: AnnouncementsViewModel, onBack: () -> Unit, initialUrl: String? = null, onRequestPermission: () -> Unit = {}, onAssistant: (String?) -> Unit = {}) {
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(initialUrl) }
    val uri = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(selected, inbox.items.any { it.url == selected }) { selected?.takeIf { key -> inbox.items.any { it.url == key } }?.let(vm::open) }
    DisposableEffect(Unit) { onDispose { vm.close() } }
    fun back() {
        if (selected != null) {
            selected = null
            vm.close()
        } else {
            onBack()
        }
    }
    BackHandler { back() }
    AnnouncementsContent(
        inbox, busy, problem, selected, document,
        onBack = ::back, onRefresh = { if (selected == null) vm.refresh() else selected?.let(vm::open) },
        onSelect = {
            vm.problem.value = false
            selected = it
        },
        onSource = { source, enabled ->
            vm.act {
                configure(source, enabled)
                refresh()
            }
        },
        onAlerts = { enabled ->
            vm.act { alerts(enabled) }
            if (enabled) onRequestPermission()
        },
        onSave = { url, saved -> vm.act { save(url, saved) } },
        onReminder = { url, at ->
            vm.act { remind(url, at) }
            if (at > 0) onRequestPermission()
        },
        onMore = { source -> vm.act { more(source) } },
        onClear = {
            selected = null
            vm.close()
            vm.act { clear() }
        },
        onDismissError = { vm.problem.value = false },
        onAndroidSettings = {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }.onFailure { vm.problem.value = true }
        },
        onLink = { target -> runCatching { uri.openUri(requireNotNull(AnnouncementUrls.safe(target, "https://portal.uit.edu.vn"))) }.onFailure { vm.problem.value = true } },
        onAssistant = onAssistant,
        imageLoader = vm.repository::image
    )
}

@Composable
fun AnnouncementsContent(
    inbox: AnnouncementInbox,
    busy: Boolean = false,
    problem: Boolean = false,
    selected: String? = null,
    document: AnnouncementDocument? = null,
    onAssistant: (String?) -> Unit = {},
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSelect: (String) -> Unit = {},
    onSource: (AnnouncementSource, Boolean) -> Unit = { _, _ -> },
    onAlerts: (Boolean) -> Unit = {},
    onSave: (String, Boolean) -> Unit = { _, _ -> },
    onReminder: (String, Long) -> Unit = { _, _ -> },
    onMore: (AnnouncementSource) -> Unit = {},
    onClear: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onAndroidSettings: () -> Unit = {},
    onLink: (String) -> Unit = {},
    imageLoader: suspend (String) -> ByteArray = { error("Image unavailable") }
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var reminder by rememberSaveable { mutableStateOf(false) }
    var reminderText by rememberSaveable { mutableStateOf("") }
    val listScroll = rememberLazyListState()
    val articleScroll = rememberSaveable(selected, saver = LazyListState.Saver) { LazyListState() }
    val item = inbox.items.firstOrNull { it.url == selected }
    val groups = remember(inbox, query, filter) {
        inbox.items.filter { inbox.sources.getValue(it.source).enabled || filter in setOf("SAVED", "REMINDERS") }
            .filter { announcementText(query) in announcementText(it.title + " " + it.excerpt) }
            .filter {
                when (filter) {
                    "UNREAD" -> it.unread
                    "SAVED" -> it.saved
                    "REMINDERS" -> it.reminderAt > 0
                    "ALL" -> true
                    else -> it.topic.name == filter
                }
            }
            .sortedByDescending { it.publishedDate }.groupBy { it.groupKey }.values.toList()
    }
    LazyColumn(Modifier.fillMaxSize().testTag("announcements-list"), state = if (selected == null) listScroll else articleScroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ann_back)) }
                Text(stringResource(R.string.ann_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.ann_refresh)) }
                if (selected == null) IconButton({ settings = !settings }) { Icon(Icons.Outlined.Tune, stringResource(R.string.ann_sources)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (problem || inbox.storageFailed) {
            item {
                Panel {
                    Text(stringResource(if (inbox.storageFailed) R.string.ann_storage_error else R.string.ann_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onDismissError) { Text(stringResource(R.string.ann_dismiss)) }
                }
            }
        }
        if (selected != null) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(item?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    Text("${item?.source?.label.orEmpty()} · ${item?.publishedDate.orEmpty()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (document?.offline == true) Text(stringResource(R.string.ann_offline, savedTime(item?.bodySavedAt ?: 0)), color = MaterialTheme.colorScheme.tertiary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ item?.let { onSave(it.url, !it.saved) } }, shape = TechnicalShapes.Control, enabled = item != null) { Text(stringResource(if (item?.saved == true) R.string.ann_unsave else R.string.ann_save)) }
                        OutlinedButton({ reminder = true }, shape = TechnicalShapes.Control, enabled = item != null) { Text(stringResource(R.string.ann_remind)) }
                    }
                    if ((item?.reminderAt ?: 0) > 0) {
                        Text(stringResource(R.string.ann_reminder_set, savedTime(item!!.reminderAt)))
                        TextButton({ onReminder(item.url, 0) }) { Text(stringResource(R.string.ann_remove_reminder)) }
                    }
                    TextButton({ onAssistant(selected) }) { Text(stringResource(R.string.as_ask_article)) }
                    TextButton({ onLink(selected) }) { Text(stringResource(R.string.ann_original)) }
                }
            }
            if (document == null || document.item.url != selected) {
                item {
                    Text(stringResource(if (problem || item == null && !busy) R.string.ann_detail_failed else R.string.ann_loading))
                    if (problem || item == null && !busy) Button(onRefresh, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ann_refresh)) }
                }
            } else {
                items(document.blocks) { block -> PublicArticleBlock(block, selected, onLink, imageLoader) }
            }
        } else {
            if (settings) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ann_sources), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ann_device_scope), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ann_alerts), Modifier.weight(1f))
                            Switch(inbox.alertsEnabled, onAlerts, Modifier.testTag("ann-alerts"))
                        }
                        Text(stringResource(R.string.ann_alerts_help), style = MaterialTheme.typography.bodySmall)
                        TextButton(onAndroidSettings) { Text(stringResource(R.string.ann_android_settings)) }
                        AnnouncementSource.entries.forEach { source ->
                            val status = inbox.sources.getValue(source)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.label, style = MaterialTheme.typography.titleSmall)
                                    Text(source.origin, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(status.enabled, { onSource(source, it) }, Modifier.testTag("ann-source-${source.name}"))
                            }
                            Text(stringResource(if (status.failed) R.string.ann_source_failed else R.string.ann_source_saved, savedTime(status.checkedAt)), style = MaterialTheme.typography.bodySmall, color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ clear = true }) { Text(stringResource(R.string.ann_clear)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.ann_checked, "Portal UIT", savedTime(inbox.sources.getValue(AnnouncementSource.PORTAL).checkedAt)), style = MaterialTheme.typography.bodySmall)
                TextButton({ onAssistant(null) }) { Text(stringResource(R.string.as_entry)) }
                Text(stringResource(R.string.ann_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!inbox.alertsEnabled) TextButton({ onAlerts(true) }) { Text(stringResource(R.string.ann_enable_alerts)) }
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ann_search)) }, shape = TechnicalShapes.Field, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to R.string.ann_all, "UNREAD" to R.string.ann_unread, "SAVED" to R.string.ann_saved, "REMINDERS" to R.string.ann_my_reminders, "SCHOLARSHIP" to R.string.ann_scholarship, "TUITION" to R.string.ann_tuition, "REGISTRATION" to R.string.ann_registration).forEach { (key, label) ->
                        FilterChip(filter == key, { filter = key }, { Text(stringResource(label)) }, shape = TechnicalShapes.Control)
                    }
                }
            }
            val failed = inbox.sources.filterValues { it.enabled && it.failed }.keys
            if (failed.isNotEmpty()) item { Text(stringResource(R.string.ann_partial, failed.joinToString { it.label }), color = MaterialTheme.colorScheme.error) }
            if (groups.isEmpty() && !busy) {
                item {
                    Text(stringResource(R.string.ann_empty))
                    TextButton(onRefresh) { Text(stringResource(R.string.ann_refresh)) }
                }
            }
            items(groups, key = { it.first().groupKey }) { variants ->
                val first = variants.firstOrNull { it.source == AnnouncementSource.PORTAL } ?: variants.first()
                Panel(Modifier.fillMaxWidth().testTag("ann-row-${announcementHash(first.url).take(12)}")) {
                    Text(first.publishedDate.ifBlank { stringResource(R.string.ann_date_unknown) }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(first.title, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(first.url) }.padding(vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = if (variants.any { it.unread }) FontWeight.SemiBold else FontWeight.Normal)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (variants.any { it.unread }) Text(stringResource(R.string.ann_unread), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (variants.any { it.changed }) Text(stringResource(R.string.ann_updated), style = MaterialTheme.typography.labelMedium)
                        if (variants.any { it.saved }) Text(stringResource(R.string.ann_saved), style = MaterialTheme.typography.labelMedium)
                    }
                    if (first.excerpt.isNotBlank()) Text(first.excerpt.take(250), style = MaterialTheme.typography.bodyMedium)
                    variants.forEach { variant ->
                        TextButton({ onSelect(variant.url) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.ann_read_source, variant.source.label)) }
                    }
                    if (variants.size > 1) Text(stringResource(R.string.ann_variants), style = MaterialTheme.typography.bodySmall)
                }
            }
            inbox.sources.filterValues { it.enabled }.forEach { (source, status) ->
                item {
                    Text(stringResource(R.string.ann_checked, source.label, savedTime(status.checkedAt)), style = MaterialTheme.typography.bodySmall)
                    if (status.more && status.nextPage > 0) OutlinedButton({ onMore(source) }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ann_more, source.label)) }
                }
            }
        }
    }
    if (clear) {
        AlertDialog(onDismissRequest = { clear = false }, shape = TechnicalShapes.Dialog, title = { Text(stringResource(R.string.ann_clear)) }, text = { Text(stringResource(R.string.ann_clear_help)) }, confirmButton = {
            TextButton({
                clear = false
                onClear()
            }) { Text(stringResource(R.string.ann_clear)) }
        }, dismissButton = { TextButton({ clear = false }) { Text(stringResource(R.string.ann_cancel)) } })
    }
    if (reminder && item != null) {
        val at = runCatching { LocalDateTime.parse(reminderText.replace(' ', 'T')).toInstant(TimeZone.of("Asia/Ho_Chi_Minh")).epochSeconds }.getOrNull()
        val now = Clock.System.now().epochSeconds
        androidx.compose.ui.window.Dialog(onDismissRequest = { reminder = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 640.dp), shape = TechnicalShapes.Dialog) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.ann_remind), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.ann_reminder_help))
                    OutlinedTextField(reminderText, { reminderText = it.take(16) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ann_reminder_format)) }, shape = TechnicalShapes.Field, singleLine = true, isError = reminderText.isNotBlank() && (at == null || at !in (now + 1)..(now + 366L * 86400)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ reminder = false }) { Text(stringResource(R.string.ann_cancel)) }
                        TextButton({
                            at?.let { onReminder(item.url, it) }
                            reminder = false
                        }, enabled = at != null && at in (now + 1)..(now + 366L * 86400)) { Text(stringResource(R.string.ann_save)) }
                    }
                }
            }
        }
    }
}

private fun savedTime(seconds: Long): String = if (seconds <= 0) "—" else Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.of("Asia/Ho_Chi_Minh")).toString().replace('T', ' ').take(16)
