package vn.edu.uit.mobile.academic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class AcademicState(val snapshot: PortalSnapshot = PortalSnapshot(), val servicesLoading: Boolean = false, val servicesError: AppError? = null)
sealed interface AcademicIntent {
    data object Refresh : AcademicIntent
    data object OpenServices : AcademicIntent
    data object RefreshServices : AcademicIntent
}
class AcademicViewModel(private val repository: PortalRepository) : BaseMviViewModel<AcademicState, AcademicIntent, Unit>(AcademicState()) {
    private var servicesRequested = false
    private var serviceJob: kotlinx.coroutines.Job? = null
    init {
        viewModelScope.launch {
            repository.snapshot.collect {
                if (!it.portalLinked) servicesRequested = false
                state.value = state.value.copy(snapshot = it)
            }
        }
    }
    override fun onIntent(intent: AcademicIntent) {
        if (intent == AcademicIntent.Refresh) {
            viewModelScope.launch { repository.refresh() }
            return
        }
        if (!state.value.snapshot.portalLinked || serviceJob?.isActive == true || intent == AcademicIntent.OpenServices && servicesRequested) return
        servicesRequested = true
        serviceJob = viewModelScope.launch {
            state.value = state.value.copy(servicesLoading = true, servicesError = null)
            try {
                val result = when (val refresh = appResult(PortalType.UIT_PORTAL) { repository.refreshStudentServices() }) {
                    is AppResult.Success -> refresh.data
                    is AppResult.Failure -> refresh
                }
                state.value = state.value.copy(servicesError = (result as? AppResult.Failure)?.error)
            } finally {
                state.value = state.value.copy(servicesLoading = false)
            }
        }
    }
}

@Composable fun AcademicScreen(viewModel: AcademicViewModel, accounts: @Composable () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AcademicContent(state, onRefresh = { viewModel.onIntent(AcademicIntent.Refresh) }, onOpenServices = { viewModel.onIntent(AcademicIntent.OpenServices) }, onRefreshServices = { viewModel.onIntent(AcademicIntent.RefreshServices) }, accounts = accounts)
}

@Composable fun AcademicContent(state: AcademicState, onRefresh: () -> Unit = {}, onOpenServices: () -> Unit = {}, onRefreshServices: () -> Unit = {}, accounts: @Composable () -> Unit = {}) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var openAccounts by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(section, state.snapshot.portalLinked) { if (section > 0) onOpenServices() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Records", "Tuition", "Procedures", "Graduation").forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, shape = TechnicalShapes.Control, label = { Text(label) })
            }
        }
        Box(Modifier.weight(1f)) {
            if (section == 0) {
                AcademicRecordsContent(state, onRefresh, openAccounts, accounts)
            } else {
                StudentServicesContent(section, state, onRefreshServices, onAccounts = {
                    openAccounts = true
                    section = 0
                })
            }
        }
    }
}

@Composable private fun AcademicRecordsContent(state: AcademicState, onRefresh: () -> Unit, openAccounts: Boolean, accounts: @Composable () -> Unit) {
    val snapshot = state.snapshot
    val transcript = snapshot.transcript
    var selected by rememberSaveable(transcript.semesters.size) { mutableIntStateOf(maxOf(0, transcript.semesters.lastIndex)) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(openAccounts) {
        if (openAccounts) {
            showAccounts = true
            listState.scrollToItem(6)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("academic-content"), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                PageHeading("Academic record", "DAA transcript, GPA and examinations.") {
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh academic data") }
                }
            }
            item { GpaOverview(transcript) }
            item {
                Panel {
                    SectionHeading("Semester performance", transcript.semesters.getOrNull(selected)?.let { "${it.semesterName} · GPA ${it.semesterGpa?.let { gpa -> "%.2f".format(gpa) } ?: "—"} / 10" } ?: "Semester GPA · 10-point scale")
                    GpaTrend(transcript.semesters, selected) { selected = it }
                }
            }
            item {
                Panel {
                    SectionHeading("Academic transcript", "Select a semester. Tap a course for its score breakdown.")
                    if (transcript.semesters.isEmpty()) EmptyState("No transcript available", "Connect DAA to see your semester grades and earned credits.")
                    if (transcript.semesters.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            transcript.semesters.forEachIndexed { index, semester -> FilterChip(shape = TechnicalShapes.Control, selected = selected == index, onClick = { selected = index }, label = { Text(semester.semesterName) }) }
                        }
                        transcript.semesters.getOrNull(selected)?.let { semester ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${semester.creditsEarned} / ${semester.creditsAttempted} credits earned", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                StatusPill("GPA ${semester.semesterGpa?.let { "%.2f".format(it) } ?: "—"}", WorkspaceColors.Teal)
                            }
                            semester.courses.forEach { grade -> GradeBreakdown(grade, semester.semesterName) }
                        }
                    }
                }
            }
            item { GpaPlanner(transcript) }
            item { ExamPanel(snapshot.exams) }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(snapshot.profile.fullName, Modifier.size(48.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(snapshot.profile.fullName.ifBlank { "Your student profile" }, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(snapshot.profile.studentId, snapshot.profile.faculty).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Connect your account to personalize your workspace." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showAccounts = !showAccounts }) { Icon(Icons.Outlined.ManageAccounts, if (showAccounts) "Hide account settings" else "Show account settings") }
                    }
                    if (snapshot.profile.trainingProgram.isNotBlank()) Text(snapshot.profile.trainingProgram, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedVisibility(showAccounts) { Column { accounts() } }
                }
            }
        }
    }
}

@Composable private fun GpaOverview(transcript: AcademicTranscript, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cumulative GPA", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(transcript.cumulativeGpa?.let { "%.2f".format(it) } ?: "—", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("/ 10", Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Credits earned", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (transcript.semesters.isEmpty()) "—" else "${transcript.totalCreditsEarned}", style = MaterialTheme.typography.headlineMedium)
            }
        }
        transcript.graduationProgress?.let { progress ->
            Text("Degree progress · ${progress.toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { (progress.toFloat() / 100).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
        }
        Text("Academic record · ${transcript.semesters.size} semesters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun GpaTrend(semesters: List<SemesterGradeSummary>, selected: Int, onSelect: (Int) -> Unit) {
    val points = semesters.mapIndexedNotNull { index, semester -> semester.semesterGpa?.takeIf { it.isFinite() && it in 0.0..10.0 }?.let { Triple(index, semester.semesterName, it) } }
    if (points.isEmpty()) {
        EmptyState("Your trend will appear here", "Semester GPA values are needed to draw your learning curve.")
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    Row(Modifier.height(136.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) { listOf("10", "5", "0").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp).semantics { contentDescription = points.joinToString { "${it.second}: ${it.third}" } }) {
            listOf(0f, .5f, 1f).forEach { y -> drawLine(grid, Offset(0f, size.height * y), Offset(size.width, size.height * y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))) }
            val positions = points.mapIndexed { index, point -> Offset(if (points.size == 1) size.width / 2 else index.toFloat() / (points.size - 1) * (size.width - 16.dp.toPx()) + 8.dp.toPx(), size.height * (1f - point.third.toFloat() / 10)) }
            val line = Path().apply { positions.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
            val fill = Path().apply {
                addPath(line)
                lineTo(positions.last().x, size.height)
                lineTo(positions.first().x, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(primary.copy(alpha = .17f), primary.copy(alpha = .01f))))
            drawPath(line, primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            positions.forEachIndexed { index, point ->
                if (points[index].first == selected) drawCircle(primary.copy(alpha = .12f), 11.dp.toPx(), point)
                drawCircle(surface, 5.dp.toPx(), point)
                drawCircle(primary, 3.dp.toPx(), point)
            }
        }
    }
    Row(Modifier.testTag("semester-performance-selector").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        points.forEach { (index, name, _) -> FilterChip(shape = TechnicalShapes.Control, selected = index == selected, onClick = { onSelect(index) }, label = { Text(name) }) }
    }
}

@Composable private fun GradeBreakdown(grade: CourseGradeRow, semester: String) {
    var expanded by rememberSaveable(grade.courseCode, semester) { mutableStateOf(false) }
    val accent = when {
        grade.totalScore == null -> MaterialTheme.colorScheme.onSurfaceVariant
        grade.totalScore!! >= 5 -> WorkspaceColors.Teal
        else -> WorkspaceColors.Rose
    }
    Column(Modifier.fillMaxWidth().animateContentSize().clip(TechnicalShapes.Control).background(MaterialTheme.colorScheme.background).clickable { expanded = !expanded }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(courseIcon(grade.courseCode), WorkspaceColors.Ocean, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(grade.courseName, style = MaterialTheme.typography.titleSmall)
                Text("${grade.courseCode} · ${grade.credits} credits", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(grade.totalScore?.let { "%.1f".format(it) } ?: "—", accent)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse score breakdown" else "Expand score breakdown", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Process" to grade.processScore, "Midterm" to grade.midtermScore, "Practice" to grade.practiceScore, "Final" to grade.finalScore).forEach { (label, score) ->
                    Column(Modifier.weight(1f).clip(TechnicalShapes.Control).background(MaterialTheme.colorScheme.surface).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(score?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (grade.status.isNotBlank()) Text(grade.status, style = MaterialTheme.typography.bodySmall, color = accent)
        }
    }
}

@Composable private fun GpaPlanner(transcript: AcademicTranscript, modifier: Modifier = Modifier) {
    var credits by rememberSaveable { mutableStateOf("3") }
    var score by rememberSaveable { mutableFloatStateOf(8f) }
    val projection = runCatching { ProjectGpaUseCase()(transcript.cumulativeGpa ?: return@runCatching null, transcript.totalCreditsEarned, listOf(credits.toInt() to score.toDouble())) }.getOrNull()
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Outlined.Tune)
            SectionHeading("GPA simulator", "Credit-weighted projection")
        }
        Text("See how a new course could shape your cumulative GPA.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            credits,
            { credits = it.filter(Char::isDigit).take(3) },
            label = { Text("Planned credits") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            shape = TechnicalShapes.Field,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Expected score", style = MaterialTheme.typography.bodyMedium)
            StatusPill("${"%.1f".format(score)} / 10")
        }
        Slider(value = score, onValueChange = { score = it }, valueRange = 0f..10f, steps = 19)
        Surface(shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("PROJECTED GPA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("A projection, not an official result", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(projection?.let { "%.2f".format(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable private fun ExamPanel(exams: List<DaaExamSlot>, modifier: Modifier = Modifier) {
    Panel(modifier) {
        SectionHeading("Examination schedule", "Published dates, shifts and rooms")
        if (exams.isEmpty()) EmptyState("No exams scheduled", "Your exam dates will appear after syncing DAA.")
        exams.sortedBy { it.examDate }.forEach { exam ->
            val date = parsePortalDate(exam.examDate)
            val days = date?.let { it.toEpochDays() - today().toEpochDays() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(48.dp).clip(TechnicalShapes.Field).background(MaterialTheme.colorScheme.tertiaryContainer).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date?.month?.name?.take(3) ?: "EXAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(date?.dayOfMonth?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exam.courseName, style = MaterialTheme.typography.titleSmall)
                    Text("${exam.room} · ${UitLessonTimeCalculator.examStart(exam)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (exam.seatNumber.isNotBlank()) Text("Seat ${exam.seatNumber}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    when {
                        days == null -> "Scheduled"
                        days < 0 -> "Past"
                        days == 0 -> "Today"
                        else -> "${days}d"
                    },
                    WorkspaceColors.Amber
                )
            }
        }
    }
}
