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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import vn.edu.uit.mobile.designsystem.*
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
                        snack.showSnackbar("File saved. Install an app that can open this format.")
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
                if (chosen == null) snack.showSnackbar("Choose a nonempty file under 25 MB") else selectedFile = chosen
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
            title = { Text("Save file to assignment?") },
            text = { Text("$name (${bytes.size} bytes)\n${pendingAssignment?.name}\nReview the file before confirming. Moodle may require a final submit step.") },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    pendingAssignment?.let { viewModel.onIntent(CoursesIntent.Submit(it, name, bytes, true)) }
                    selectedFile = null
                    pendingAssignment = null
                }) { Text("Confirm upload & save") }
            },
            dismissButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    selectedFile = null
                    pendingAssignment = null
                }) { Text("Cancel") }
            }
        )
    }
    replyTo?.let { discussion ->
        AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text("Reply to ${discussion.name}") },
            text = { OutlinedTextField(shape = TechnicalShapes.Field, value = replyText, onValueChange = { replyText = it }, label = { Text("Your reply") }) },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, enabled = replyText.isNotBlank(), onClick = {
                    viewModel.onIntent(CoursesIntent.Reply(discussion.postId, replyText))
                    replyTo = null
                    replyText = ""
                }) { Text("Send reply") }
            },
            dismissButton = { TextButton(shape = TechnicalShapes.Control, onClick = { replyTo = null }) { Text("Cancel") } }
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
            confirmButton = { TextButton(shape = TechnicalShapes.Control, onClick = { viewModel.onIntent(CoursesIntent.DismissH5p) }) { Text("Close") } }
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
            item { PageHeading("Courses", "Your Moodle materials and submissions.") }
            if (onDownloads != null) item { OutlinedButton(onDownloads, shape = TechnicalShapes.Control) { Text("Offline downloads") } }
            item { SearchField(state.query, { onIntent(CoursesIntent.Query(it)) }, "Find a course by name or code") }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All courses", "inprogress" to "Current", "past" to "Past", "hidden" to "Hidden").forEach { (value, label) ->
                        FilterChip(shape = TechnicalShapes.Control, selected = state.filter == value, onClick = { onIntent(CoursesIntent.Filter(value)) }, label = { Text(label) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.query.isBlank()) "Course directory" else "Search results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${courses.size} ${if (courses.size == 1) "course" else "courses"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (courses.isEmpty()) item { EmptyState(if (state.query.isBlank()) "No enrolled courses" else "No matching courses", if (state.query.isBlank()) "Connect your Moodle account and sync to load your courses." else "Try another name, course code or filter.") }
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
                    Text("All courses")
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
                            StatusPill("${state.sections.size} sections", accent)
                            StatusPill("${state.sections.sumOf { section -> section.modules.sumOf { it.files.size } }} files", accent)
                            if (course.category.isNotBlank()) StatusPill(course.category, accent)
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Materials" to Icons.Outlined.FolderOpen, "Assignments" to Icons.AutoMirrored.Outlined.Assignment, "Discussions" to Icons.Outlined.Forum, "Grades & people" to Icons.Outlined.Groups).forEachIndexed { index, (label, icon) ->
                        FilterChip(shape = TechnicalShapes.Control, selected = state.tab == index, onClick = { onIntent(CoursesIntent.Tab(index)) }, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) })
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
                        Text("Ask course text")
                    }
                }
            }
            when (state.tab) {
                0 -> {
                    if (state.sections.isEmpty() && !state.loading) item { Panel { EmptyState("No materials here yet", "Your saved course materials will appear here after loading this course.") } }
                    state.sections.forEach { section ->
                        item(key = "section:${section.id}") {
                            Panel {
                                Row(Modifier.fillMaxWidth().clip(TechnicalShapes.Field).clickable { collapsed = if (section.id in collapsed) collapsed - section.id else collapsed + section.id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile(Icons.Outlined.FolderOpen, accent, 36.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(section.name.ifBlank { "Course section" }, style = MaterialTheme.typography.titleMedium)
                                        Text("${section.modules.size} activities", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(if (section.id in collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (section.id in collapsed) "Expand section" else "Collapse section", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AnimatedVisibility(section.id !in collapsed) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { section.modules.forEach { ModuleItem(it, accent, onIntent) } } }
                            }
                        }
                    }
                }
                1 -> {
                    item { SectionHeading("Assignments", "Your submissions and upcoming due dates") }
                    if (state.assignments.isEmpty() && !state.loading) item { Panel { EmptyState("No assignments available", "Assignments will appear here when available from your course.") } }
                    state.assignments.forEach { assignment ->
                        item(key = "assignment:${assignment.id}") {
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    IconTile(Icons.AutoMirrored.Outlined.Assignment, accent)
                                    StatusPill(state.status[assignment.id]?.status?.replaceFirstChar(Char::uppercase) ?: "Status unknown", if (state.status[assignment.id]?.submitted == true) WorkspaceColors.Teal else WorkspaceColors.Amber)
                                }
                                Text(assignment.name, style = MaterialTheme.typography.titleLarge)
                                if (assignment.due > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Due ${dateText(assignment.due)} at ${clockText(assignment.due)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                state.status[assignment.id]?.grade?.takeIf(String::isNotBlank)?.let { StatusPill("Grade: $it", WorkspaceColors.Teal) }
                                assignment.files.forEach { FileActions(it, { onIntent(CoursesIntent.Download(it)) }, { onIntent(CoursesIntent.Inspect(it)) }) }
                                if (onStudy != null) OutlinedButton(onClick = { onStudy(assignment) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) { Text("Analyze assignment & checklist") }
                                Button(onClick = { onPickFile(assignment) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
                                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Choose submission file")
                                }
                                Text("You'll review and confirm before anything is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                2 -> {
                    item { SectionHeading("Course conversations", "Announcements and discussions from your class") }
                    if (state.discussions.isEmpty() && !state.loading) item { Panel { EmptyState("No discussions available", "No discussions are available in this course yet.") } }
                    state.discussions.forEach { discussion ->
                        item(key = "discussion:${discussion.id}") {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Avatar(discussion.author)
                                    Column(Modifier.weight(1f)) {
                                        Text(discussion.author.ifBlank { "Course announcement" }, style = MaterialTheme.typography.titleSmall)
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
                                        Text("Write a reply")
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    item { SectionHeading("Your gradebook", "Results and feedback from Moodle") }
                    if (state.grades.isEmpty() && !state.loading) item { Panel { Text("No course grades are available yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
                        SectionHeading("People in this course", "${state.participants.size} participants")
                        Spacer(Modifier.height(14.dp))
                        SearchField(peopleQuery, { peopleQuery = it }, "Find a classmate or lecturer")
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
                Text("Read discussions")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
            }
        } else if (module.files.isEmpty() && module.url.startsWith("https://")) {
            TextButton(shape = TechnicalShapes.Control, onClick = { runCatching { uriHandler.openUri(module.url) } }) {
                Text("Open activity")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun ExpandableText(text: String, key: String) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
    if (text.length > 240) TextButton(shape = TechnicalShapes.Control, onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Show less" else "Read more") }
}

@Composable private fun FileActions(file: CourseFile, download: () -> Unit, inspect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(TechnicalShapes.Field).background(MaterialTheme.colorScheme.surface).clickable(onClick = download).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(file.name.substringAfterLast('.', "File").uppercase(), if (file.size > 0) if (file.size >= 1024 * 1024) "${"%.1f".format(file.size / (1024.0 * 1024))} MB" else "${maxOf(1, file.size / 1024)} KB" else "").filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file.name.endsWith(".h5p", true)) IconButton(onClick = inspect) { Icon(Icons.Outlined.Memory, "Read H5P summary", Modifier.size(20.dp)) }
        IconButton(onClick = download) { Icon(Icons.Outlined.Download, "Download ${file.name}", Modifier.size(20.dp)) }
    }
}
