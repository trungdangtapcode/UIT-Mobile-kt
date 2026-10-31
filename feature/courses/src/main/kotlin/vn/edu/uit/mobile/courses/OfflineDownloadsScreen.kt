package vn.edu.uit.mobile.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R
import vn.edu.uit.mobile.model.*

@Composable fun OfflineDownloadsScreen(viewModel: OfflineDownloadsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    DisposableEffect(viewModel) { onDispose { viewModel.dismissOpenRequests() } }
    LaunchedEffect(viewModel) { viewModel.files.collect { if (viewModel.canOpen(it) && !openSavedCourseFile(context, it.path)) snack.showSnackbar("Install an app that can open this saved format.") } }
    Box {
        OfflineDownloadsContent(state, {
            viewModel.dismissOpenRequests()
            onBack()
        }, viewModel::wifiOnly, viewModel::enqueue, viewModel::cancel, viewModel::retry, viewModel::remove, viewModel::open, viewModel::loadCourse, { viewModel.refreshSaved() })
        SnackbarHost(snack)
    }
}

@Composable fun OfflineDownloadsContent(state: OfflineDownloadsState, onBack: () -> Unit = {}, onWifi: (Boolean) -> Unit = {}, onEnqueue: (List<OfflineDownloadSpec>) -> Unit = {}, onCancel: (String) -> Unit = {}, onRetry: (String) -> Unit = {}, onRemove: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onLoadCourse: (Course) -> Unit = {}, onVerify: () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFiles by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var choosingFiles by rememberSaveable { mutableStateOf(false) }
    val courses = (state.snapshot.courses + state.items.map { it.spec.course }).filter { it.accountId in state.owners }.distinctBy { "${it.accountId}:${it.id}" }
    val course = courses.firstOrNull { "${it.accountId}:${it.id}" == selected }
    val specs = savedDownloadSpecs(state.snapshot, state.owners).filter { it.course.accountId == course?.accountId && it.course.id == course.id }
    val pack = specs.filter { section == null || it.section == section }.distinctBy { it.id }
    val chosen = pack.filter { selectedFiles == null || it.id in selectedFiles.orEmpty() }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("offline-downloads-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onBack, shape = TechnicalShapes.Control) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Text(stringResource(R.string.dl_back))
            }
            PageHeading(stringResource(R.string.dl_title), stringResource(R.string.dl_subtitle))
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dl_summary, state.items.count { it.status == OfflineDownloadStatus.SAVED }, state.items.size), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.dl_storage, offlineSize(state.usedBytes)), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.dl_wifi_only), Modifier.weight(1f))
                    Switch(state.wifiOnly, onWifi, enabled = state.ready)
                }
                Text(stringResource(if (state.wifi) R.string.dl_wifi_yes else R.string.dl_wifi_no), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dl_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onVerify, enabled = state.ready, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_verify)) }
                state.message?.let { InlineNotice(it) }
            }
        }
        if (!state.ready) {
            item {
                CircularProgressIndicator()
                Text(stringResource(R.string.dl_reading))
            }
        }
        if (course == null) {
            item { SectionHeading(stringResource(R.string.dl_choose_course), stringResource(R.string.dl_choose_course_hint)) }
            if (courses.isEmpty()) item { EmptyState(stringResource(R.string.dl_no_courses_title), stringResource(R.string.dl_no_courses_body)) }
            courses.forEach { candidate ->
                item(key = "course:${candidate.accountId}:${candidate.id}") {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(candidate.fullname, style = MaterialTheme.typography.titleMedium)
                        Text(candidate.shortname, style = MaterialTheme.typography.bodySmall)
                        TextButton({
                            selected = "${candidate.accountId}:${candidate.id}"
                            section = null
                            selectedFiles = null
                            choosingFiles = false
                        }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_choose_pack)) }
                    }
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(course.fullname, style = MaterialTheme.typography.titleMedium)
                    TextButton({
                        selected = null
                        section = null
                    }, shape = TechnicalShapes.Control) { Text("Change course") }
                    OutlinedButton({ onLoadCourse(course) }, enabled = state.ready, shape = TechnicalShapes.Control) { Text("Load course file metadata") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = section == null, onClick = {
                            section = null
                            selectedFiles = null
                        }, label = { Text("All saved sections") }, shape = TechnicalShapes.Control)
                        specs.distinctBy { it.section }.forEach { source ->
                            FilterChip(selected = section == source.section, onClick = {
                                section = source.section
                                selectedFiles = null
                            }, label = { Text(source.sectionName) }, shape = TechnicalShapes.Control)
                        }
                    }
                    Text("${chosen.size} of ${pack.size} files selected · sizes may be unreported", style = MaterialTheme.typography.bodyMedium)
                    Button({ onEnqueue(chosen) }, enabled = state.ready && chosen.size in 1..100, shape = TechnicalShapes.Control) { Text("Queue this pack") }
                    if (chosen.size > 100) Text("Select at most 100 files before queuing.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ selectedFiles = null }, shape = TechnicalShapes.Control) { Text("Select all") }
                        TextButton({
                            selectedFiles = emptyList()
                            choosingFiles = true
                        }, shape = TechnicalShapes.Control) { Text("Clear selection") }
                    }
                    TextButton({ choosingFiles = !choosingFiles }, shape = TechnicalShapes.Control) { Text(if (choosingFiles) "Hide file selection" else "Choose individual files") }
                }
            }
            if (choosingFiles || pack.size > 100) {
                pack.forEach { spec ->
                    item(key = "catalog:${spec.id}") {
                        Panel(Modifier.fillMaxWidth()) {
                            Row {
                                val checked = selectedFiles == null || spec.id in selectedFiles.orEmpty()
                                Checkbox(checked, { enabled ->
                                    val current = selectedFiles ?: pack.map { it.id }
                                    selectedFiles = if (enabled) (current + spec.id).distinct() else current - spec.id
                                }, enabled = checked || chosen.size < 100, modifier = Modifier.semantics { contentDescription = "Select ${spec.file.name}" })
                                Column(Modifier.weight(1f)) {
                                    Text(spec.file.name, style = MaterialTheme.typography.titleSmall)
                                    Text(spec.sectionName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (specs.isEmpty()) item { EmptyState("No file metadata saved", "Load the course above to discover downloadable files. No files are downloaded until you queue them.") }
        }
        item { SectionHeading(stringResource(R.string.dl_queue_heading), stringResource(R.string.dl_queue_hint)) }
        if (state.items.isEmpty()) item { EmptyState(stringResource(R.string.dl_empty_title), stringResource(R.string.dl_empty_body)) }
        state.items.forEach { item ->
            item(key = item.id) {
                Panel(Modifier.fillMaxWidth()) {
                    Text(item.spec.file.name, style = MaterialTheme.typography.titleMedium)
                    Text("${item.spec.course.shortname} · ${item.spec.sectionName}", style = MaterialTheme.typography.bodySmall)
                    Text(offlineStatusLabel(item.status), style = MaterialTheme.typography.labelLarge)
                    if (item.status == OfflineDownloadStatus.SAVED) Text(stringResource(R.string.dl_verified, offlineSize(item.bytes)), style = MaterialTheme.typography.bodySmall)
                    if (item.status == OfflineDownloadStatus.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (item.status) {
                            OfflineDownloadStatus.SAVED -> TextButton({ onOpen(item.id) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_open)) }
                            OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.WAITING_WIFI -> TextButton({ onCancel(item.id) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_cancel)) }
                            OfflineDownloadStatus.FAILED, OfflineDownloadStatus.CANCELED -> TextButton({ onRetry(item.id) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_retry)) }
                            else -> Unit
                        }
                        TextButton({ onRemove(item.id) }, enabled = item.status != OfflineDownloadStatus.REMOVING, shape = TechnicalShapes.Control) { Text(stringResource(R.string.dl_remove)) }
                    }
                }
            }
        }
    }
}
fun offlineStatusLabel(status: OfflineDownloadStatus) = when (status) {
    OfflineDownloadStatus.QUEUED -> "Queued"
    OfflineDownloadStatus.DOWNLOADING -> "Downloading"
    OfflineDownloadStatus.WAITING_WIFI -> "Waiting for Wi-Fi"
    OfflineDownloadStatus.SAVED -> "Verified saved copy"
    OfflineDownloadStatus.FAILED -> "Needs retry"
    OfflineDownloadStatus.CANCELED -> "Canceled"
    OfflineDownloadStatus.REMOVING -> "Removing"
}

private fun offlineSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(java.util.Locale.ROOT, bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    else -> "%.1f GiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
}
