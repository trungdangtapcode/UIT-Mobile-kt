package vn.edu.uit.mobile.courses

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Grading
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.feature.courses.R
import vn.edu.uit.mobile.model.*

@Composable fun CoursesScreen(viewModel: CoursesViewModel, study: StudyViewModel? = null, onReturnToSearch: (() -> Unit)? = null, downloads: OfflineDownloadsViewModel? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    if (showDownloads && downloads != null) {
        OfflineDownloadsScreen(downloads) { showDownloads = false }
        return
    }
    val studyState = study?.uiState?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(state.selected?.accountId, state.selected?.id) {
        if (studyState?.course != null && studyState.course != state.selected) study?.close()
    }
    if (study != null && studyState?.course != null && studyState.course == state.selected) {
        StudyScreen(study)
        return
    }
    val navigate: (CoursesIntent) -> Unit = { intent -> if (intent == CoursesIntent.Back && onReturnToSearch != null) onReturnToSearch() else viewModel.onIntent(intent) }
    BackHandler(enabled = state.selected != null || onReturnToSearch != null) { navigate(CoursesIntent.Back) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CoursesEffect.Message -> snack.showSnackbar(effect.text)
                is CoursesEffect.OpenFile -> {
                    val file = java.io.File(effect.path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        snack.showSnackbar(context.getString(R.string.courses_file_saved_error))
                    }
                }
            }
        }
    }
    var pendingAssignment by remember { mutableStateOf<Assignment?>(null) }
    var selectedFile by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var replyTo by remember { mutableStateOf<Discussion?>(null) }
    var replyText by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val chosen = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "submission" } ?: "submission"
                        val data = context.contentResolver.openInputStream(uri)!!.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(32768)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 25 * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(data.isNotEmpty() && data.size <= 25 * 1024 * 1024)
                        name to data
                    }.getOrNull()
                }
                if (chosen == null) snack.showSnackbar(context.getString(R.string.courses_bad_file)) else selectedFile = chosen
            }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(Modifier.padding(padding)) {
            CoursesContent(
                state,
                navigate,
                onPickFile = { assignment ->
                    pendingAssignment = assignment
                    picker.launch(arrayOf("*/*"))
                },
                onReply = { replyTo = it },
                onDownloads = if (downloads == null) null else ({ showDownloads = true }),
                onStudy = if (study == null) {
                    null
                } else {
                    { assignment ->
                        state.selected?.let { study.open(it, state.sections, state.assignments, assignment) }
                    }
                }
            )
        }
    }
    selectedFile?.let { (name, bytes) ->
        AlertDialog(
            onDismissRequest = {
                selectedFile = null
                pendingAssignment = null
            },
            title = { Text(stringResource(R.string.courses_upload_title)) },
            text = { Text(stringResource(R.string.courses_upload_body, name, bytes.size, pendingAssignment?.name.orEmpty())) },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    pendingAssignment?.let { viewModel.onIntent(CoursesIntent.Submit(it, name, bytes, true)) }
                    selectedFile = null
                    pendingAssignment = null
                }) { Text(stringResource(R.string.courses_upload_confirm)) }
            },
            dismissButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    selectedFile = null
                    pendingAssignment = null
                }) { Text(stringResource(R.string.courses_cancel)) }
            }
        )
    }
    replyTo?.let { discussion ->
        AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text(stringResource(R.string.courses_reply_title, discussion.name)) },
            text = { OutlinedTextField(shape = TechnicalShapes.Field, value = replyText, onValueChange = { replyText = it }, label = { Text(stringResource(R.string.courses_reply_label)) }) },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, enabled = replyText.isNotBlank(), onClick = {
                    viewModel.onIntent(CoursesIntent.Reply(discussion.postId, replyText))
                    replyTo = null
                    replyText = ""
                }) { Text(stringResource(R.string.courses_reply_send)) }
            },
            dismissButton = { TextButton(shape = TechnicalShapes.Control, onClick = { replyTo = null }) { Text(stringResource(R.string.courses_cancel)) } }
        )
    }
    state.h5p?.let { summary ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(CoursesIntent.DismissH5p) },
            title = { Text(summary.title) },
            text = {
                LazyColumn {
                    summary.entries.forEach { entry ->
                        item {
                            SectionTitle(entry.title)
                            entry.text.forEach { Text(it) }
                            entry.media.forEach { Text(it, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(shape = TechnicalShapes.Control, onClick = { viewModel.onIntent(CoursesIntent.DismissH5p) }) { Text(stringResource(R.string.courses_close)) } }
        )
    }
}

@Composable
fun CoursesContent(state: CoursesState, onIntent: (CoursesIntent) -> Unit = {}, onPickFile: (Assignment) -> Unit = {}, onReply: (Discussion) -> Unit = {}, onStudy: ((Assignment?) -> Unit)? = null, onDownloads: (() -> Unit)? = null) {
    val course = state.selected
    if (course == null) CourseLibrary(state, onIntent, onDownloads) else CourseWorkspace(state, course, onIntent, onPickFile, onReply, onStudy)
}

@Composable private fun CourseLibrary(state: CoursesState, onIntent: (CoursesIntent) -> Unit, onDownloads: (() -> Unit)?) {
    val courses = state.snapshot.courses.filter { (state.filter == "all" || it.classification == state.filter || state.filter == "hidden" && it.hidden) && "${it.shortname} ${it.fullname}".contains(state.query, true) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { PageHeading(stringResource(R.string.courses_title), stringResource(R.string.courses_subtitle)) }
            if (onDownloads != null) item { OutlinedButton(onDownloads, shape = TechnicalShapes.Control) { Text(stringResource(R.string.courses_offline)) } }
            item { SearchField(state.query, { onIntent(CoursesIntent.Query(it)) }, stringResource(R.string.courses_search_hint)) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All courses", "inprogress" to "Current", "past" to "Past", "hidden" to "Hidden").forEach { (value, label) ->
                        FilterChip(shape = TechnicalShapes.Control, selected = state.filter == value, onClick = { onIntent(CoursesIntent.Filter(value)) }, label = { Text(courseFilterLabel(value, label)) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.query.isBlank()) stringResource(R.string.courses_directory) else stringResource(R.string.courses_search_results), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(pluralStringResource(R.plurals.courses_count, courses.size, courses.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (courses.isEmpty()) item { EmptyState(if (state.query.isBlank()) stringResource(R.string.courses_empty_title) else stringResource(R.string.courses_no_match_title), if (state.query.isBlank()) stringResource(R.string.courses_empty_body) else stringResource(R.string.courses_no_match_body)) }
            items(courses, key = { "${it.accountId}:${it.id}" }) { item -> CourseCard(item) { onIntent(CoursesIntent.Select(item)) } }
        }
    }
}

@Composable private fun CourseWorkspace(state: CoursesState, course: Course, onIntent: (CoursesIntent) -> Unit, onPickFile: (Assignment) -> Unit, onReply: (Discussion) -> Unit, onStudy: ((Assignment?) -> Unit)?) {
    var collapsed by rememberSaveable(course.accountId, course.id) { mutableStateOf(listOf<Long>()) }
    var peopleQuery by rememberSaveable(course.accountId, course.id) { mutableStateOf("") }
    val accent = WorkspaceColors.course(course.id)
    val matching = state.snapshot.timetable.filter { vn.edu.uit.mobile.domain.CourseCrossLinker().match(it.classCode, listOf(course)) != null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                TextButton(shape = TechnicalShapes.Control, onClick = { onIntent(CoursesIntent.Back) }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.courses_back))
                }
                Surface(shape = TechnicalShapes.Panel, color = accent.copy(alpha = .09f), border = BorderStroke(1.dp, accent.copy(alpha = .14f))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconTile(Icons.AutoMirrored.Outlined.MenuBook, accent, 48.dp)
                            StatusPill(course.shortname, accent)
                        }
                        Text(course.fullname, style = MaterialTheme.typography.headlineMedium)
                        if (matching.isNotEmpty()) Text(matching.map { "${it.room} · ${it.lecturerName}" }.distinct().joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(stringResource(R.string.courses_sections, state.sections.size), accent)
                            StatusPill(stringResource(R.string.courses_files, state.sections.sumOf { section -> section.modules.sumOf { it.files.size } }), accent)
                            if (course.category.isNotBlank()) StatusPill(course.category, accent)
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Materials" to Icons.Outlined.FolderOpen, "Assignments" to Icons.AutoMirrored.Outlined.Assignment, "Discussions" to Icons.Outlined.Forum, "Grades & people" to Icons.Outlined.Groups).forEachIndexed { index, (label, icon) ->
                        FilterChip(shape = TechnicalShapes.Control, selected = state.tab == index, onClick = { onIntent(CoursesIntent.Tab(index)) }, label = { Text(courseTabLabel(index, label)) }, leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) })
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp))
            }
            state.error?.let { error -> item { InlineNotice(error.text(), true) } }
            if (onStudy != null) {
                item {
                    OutlinedButton(onClick = { onStudy(null) }, enabled = !state.loading, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.QuestionAnswer, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.courses_ask))
                    }
                }
            }
            when (state.tab) {
                0 -> {
                    if (state.sections.isEmpty() && !state.loading) item { Panel { EmptyState(stringResource(R.string.courses_materials_empty_title), stringResource(R.string.courses_materials_empty_body)) } }
                    state.sections.forEach { section ->
                        item(key = "section:${section.id}") {
                            Panel {
                                Row(Modifier.fillMaxWidth().clip(TechnicalShapes.Field).clickable { collapsed = if (section.id in collapsed) collapsed - section.id else collapsed + section.id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile(Icons.Outlined.FolderOpen, accent, 36.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(section.name.ifBlank { stringResource(R.string.courses_section_fallback) }, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.courses_activities, section.modules.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(if (section.id in collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, stringResource(if (section.id in collapsed) R.string.courses_expand else R.string.courses_collapse), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AnimatedVisibility(section.id !in collapsed) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { section.modules.forEach { ModuleItem(it, accent, onIntent) } } }
                            }
                        }
                    }
                }
                1 -> {
                    item { SectionHeading(stringResource(R.string.courses_assignments_heading), stringResource(R.string.courses_assignments_subtitle)) }
                    if (state.assignments.isEmpty() && !state.loading) item { Panel { EmptyState(stringResource(R.string.courses_assignments_empty_title), stringResource(R.string.courses_assignments_empty_body)) } }
                    state.assignments.forEach { assignment ->
                        item(key = "assignment:${assignment.id}") {
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    IconTile(Icons.AutoMirrored.Outlined.Assignment, accent)
                                    StatusPill(state.status[assignment.id]?.status?.replaceFirstChar(Char::uppercase) ?: stringResource(R.string.courses_status_unknown), if (state.status[assignment.id]?.submitted == true) WorkspaceColors.Teal else WorkspaceColors.Amber)
                                }
                                Text(assignment.name, style = MaterialTheme.typography.titleLarge)
                                if (assignment.due > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(stringResource(R.string.courses_due_at, dateText(assignment.due), clockText(assignment.due)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                state.status[assignment.id]?.grade?.takeIf(String::isNotBlank)?.let { StatusPill(stringResource(R.string.courses_grade, it), WorkspaceColors.Teal) }
                                assignment.files.forEach { FileActions(it, { onIntent(CoursesIntent.Download(it)) }, { onIntent(CoursesIntent.Inspect(it)) }) }
                                if (onStudy != null) OutlinedButton(onClick = { onStudy(assignment) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) { Text(stringResource(R.string.courses_analyze)) }
                                Button(onClick = { onPickFile(assignment) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
                                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.courses_choose_file))
                                }
                                Text(stringResource(R.string.courses_upload_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                2 -> {
                    item { SectionHeading(stringResource(R.string.courses_discussions_heading), stringResource(R.string.courses_discussions_subtitle)) }
                    if (state.discussions.isEmpty() && !state.loading) item { Panel { EmptyState(stringResource(R.string.courses_discussions_empty_title), stringResource(R.string.courses_discussions_empty_body)) } }
                    state.discussions.forEach { discussion ->
                        item(key = "discussion:${discussion.id}") {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Avatar(discussion.author)
                                    Column(Modifier.weight(1f)) {
                                        Text(discussion.author.ifBlank { stringResource(R.string.courses_announcement_fallback) }, style = MaterialTheme.typography.titleSmall)
                                        if (discussion.created > 0) Text(dateText(discussion.created), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(discussion.name, style = MaterialTheme.typography.titleLarge)
                                ExpandableText(android.text.Html.fromHtml(discussion.bodyHtml, 0).toString(), "discussion:${discussion.id}")
                                discussion.files.forEach { FileActions(it, { onIntent(CoursesIntent.Download(it)) }, { onIntent(CoursesIntent.Inspect(it)) }) }
                                if (discussion.postId > 0) {
                                    OutlinedButton(onClick = { onReply(discussion) }, shape = TechnicalShapes.Field) {
                                        Icon(Icons.AutoMirrored.Outlined.Reply, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.courses_reply_write))
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    item { SectionHeading(stringResource(R.string.courses_grades_heading), stringResource(R.string.courses_grades_subtitle)) }
                    if (state.grades.isEmpty() && !state.loading) item { Panel { Text(stringResource(R.string.courses_grades_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    state.grades.forEach { grade ->
                        item {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile(Icons.AutoMirrored.Outlined.Grading, accent)
                                    Column(Modifier.weight(1f)) {
                                        Text(grade.name, style = MaterialTheme.typography.titleSmall)
                                        if (grade.range.isNotBlank()) Text(grade.range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusPill(android.text.Html.fromHtml(grade.grade, 0).toString(), WorkspaceColors.Teal)
                                }
                                if (grade.feedback.isNotBlank()) Text(android.text.Html.fromHtml(grade.feedback, 0).toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        SectionHeading(stringResource(R.string.courses_people_heading), stringResource(R.string.courses_people_count, state.participants.size))
                        Spacer(Modifier.height(14.dp))
                        SearchField(peopleQuery, { peopleQuery = it }, stringResource(R.string.courses_people_search))
                    }
                    state.participants.filter { it.name.contains(peopleQuery, true) }.forEach { person ->
                        item(key = "person:${person.id}") {
                            Row(Modifier.fillMaxWidth().clip(TechnicalShapes.Field).background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(person.name, color = WorkspaceColors.course(person.id))
                                Column(Modifier.weight(1f)) {
                                    Text(person.name, style = MaterialTheme.typography.titleSmall)
                                    Text(listOf(person.roles, person.groups).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun courseFilterLabel(value: String, @Suppress("UNUSED_PARAMETER") fallback: String): String = stringResource(
    when (value) {
        "inprogress" -> R.string.courses_filter_current
        "past" -> R.string.courses_filter_past
        "hidden" -> R.string.courses_filter_hidden
        else -> R.string.courses_filter_all
    }
)

@Composable private fun courseTabLabel(index: Int, @Suppress("UNUSED_PARAMETER") fallback: String): String = stringResource(
    when (index) {
        1 -> R.string.courses_tab_assignments
        2 -> R.string.courses_tab_discussions
        3 -> R.string.courses_tab_grades
        else -> R.string.courses_tab_materials
    }
)

@Composable private fun ModuleItem(module: CourseModule, accent: Color, onIntent: (CoursesIntent) -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth().clip(TechnicalShapes.Control).background(MaterialTheme.colorScheme.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                when (module.type) {
                    "forum" -> Icons.Outlined.Forum
                    "folder" -> Icons.Outlined.FolderOpen
                    "h5pactivity" -> Icons.Outlined.Memory
                    "url" -> Icons.Outlined.Link
                    else -> Icons.Outlined.Description
                },
                null,
                Modifier.size(20.dp),
                tint = accent
            )
            Column(Modifier.weight(1f)) {
                Text(module.name, style = MaterialTheme.typography.titleSmall)
                Text(module.type.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (module.description.isNotBlank()) ExpandableText(android.text.Html.fromHtml(module.description, 0).toString(), "module:${module.id}")
        module.files.forEach { FileActions(it, { onIntent(CoursesIntent.Download(it)) }, { onIntent(CoursesIntent.Inspect(it)) }) }
        if (module.type == "forum") {
            TextButton(shape = TechnicalShapes.Control, onClick = { onIntent(CoursesIntent.Forum(module)) }) {
                Text(stringResource(R.string.courses_read_discussions))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
            }
        } else if (module.files.isEmpty() && module.url.startsWith("https://")) {
            TextButton(shape = TechnicalShapes.Control, onClick = { runCatching { uriHandler.openUri(module.url) } }) {
                Text(stringResource(R.string.courses_open_activity))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun ExpandableText(text: String, key: String) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
    if (text.length > 240) TextButton(shape = TechnicalShapes.Control, onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(if (expanded) R.string.courses_show_less else R.string.courses_read_more)) }
}

@Composable private fun FileActions(file: CourseFile, download: () -> Unit, inspect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(TechnicalShapes.Field).background(MaterialTheme.colorScheme.surface).clickable(onClick = download).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(file.name.substringAfterLast('.', "File").uppercase(), if (file.size > 0) if (file.size >= 1024 * 1024) "${"%.1f".format(file.size / (1024.0 * 1024))} MB" else "${maxOf(1, file.size / 1024)} KB" else "").filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file.name.endsWith(".h5p", true)) IconButton(onClick = inspect) { Icon(Icons.Outlined.Memory, stringResource(R.string.courses_h5p_summary), Modifier.size(20.dp)) }
        IconButton(onClick = download) { Icon(Icons.Outlined.Download, stringResource(R.string.courses_download, file.name), Modifier.size(20.dp)) }
    }
}
