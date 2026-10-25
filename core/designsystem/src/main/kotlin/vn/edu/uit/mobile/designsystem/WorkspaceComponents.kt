package vn.edu.uit.mobile.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.UitTimeZone
import vn.edu.uit.mobile.model.*

@Composable fun Panel(modifier: Modifier = Modifier, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = TechnicalShapes.Panel,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable fun SectionHeading(title: String, subtitle: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (action != null) {
            TextButton(shape = TechnicalShapes.Control, onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(action)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp))
            }
        }
    }
}

@Composable fun SectionTitle(title: String) {
    SectionHeading(title)
}

@Composable fun readableAccent(color: Color): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.2f) lerp(color, Color.White, 0.38f) else color

@Composable fun IconTile(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 42.dp) {
    val ink = readableAccent(tint)
    Box(Modifier.size(size).clip(TechnicalShapes.Control).background(ink.copy(alpha = 0.11f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.49f), tint = ink)
    }
}

@Composable fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.primary, dot: Boolean = false) {
    val ink = readableAccent(color)
    Row(
        Modifier.clip(TechnicalShapes.Control).background(ink.copy(alpha = 0.10f)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dot) Box(Modifier.size(5.dp).background(ink, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = ink)
    }
}

@Composable fun Avatar(name: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "U" }
    Box(modifier.size(40.dp).clip(TechnicalShapes.Tile).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Text(initials, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), TechnicalShapes.Panel), contentAlignment = Alignment.Center) {
            IconTile(Icons.Outlined.Terminal, size = 52.dp)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) FilledTonalButton(shape = TechnicalShapes.Control, onClick = onAction) { Text(action) }
    }
}

@Composable fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) } },
        shape = TechnicalShapes.Control,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable fun InlineNotice(text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(TechnicalShapes.Field).background(color.copy(alpha = 0.08f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (error) Icons.Outlined.Info else Icons.Outlined.CloudDone, null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable fun DeadlineBadge(needsAction: Boolean) {
    StatusPill(if (needsAction) "Needs action" else "No action required", if (needsAction) WorkspaceColors.Amber else WorkspaceColors.Teal, true)
}

@Composable fun CourseCard(course: Course, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = TechnicalShapes.Panel, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(courseIcon(course.shortname), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(course.shortname.ifBlank { "Course ${course.id}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(course.fullname, style = MaterialTheme.typography.titleMedium)
            if (course.category.isNotBlank()) Text(course.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
fun clockText(epoch: Long): String = Instant.fromEpochSeconds(epoch).toLocalDateTime(UitTimeZone).time.toString().take(5)
fun dateText(epoch: Long): String = Instant.fromEpochSeconds(epoch).toLocalDateTime(UitTimeZone).date.toString()
fun friendlyDate(date: LocalDate): String = "${date.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${date.dayOfMonth}, ${date.year}"
fun relativeDue(start: Long, now: Long): String {
    val remaining = start - now
    return when {
        remaining <= 0 -> "Overdue"
        remaining < 3600 -> "${maxOf(1, remaining / 60)}m left"
        remaining < 86400 -> "${remaining / 3600}h left"
        else -> "${remaining / 86400}d ${(remaining % 86400) / 3600}h left"
    }
}
fun scheduleLabel(kind: ScheduleKind): String = when (kind) {
    ScheduleKind.CLASS -> "Class"
    ScheduleKind.CANCELED -> "Canceled"
    ScheduleKind.MAKE_UP -> "Make-up"
    ScheduleKind.EXAM -> "Exam"
    ScheduleKind.DEADLINE -> "Deadline"
    ScheduleKind.EVENT -> "Event"
}
fun scheduleColor(kind: ScheduleKind): Color = when (kind) {
    ScheduleKind.CANCELED -> WorkspaceColors.Rose
    ScheduleKind.MAKE_UP -> WorkspaceColors.Teal
    ScheduleKind.EXAM -> WorkspaceColors.Amber
    ScheduleKind.DEADLINE -> WorkspaceColors.Rose
    else -> WorkspaceColors.Ocean
}

@Composable fun ScheduleCard(entry: ScheduleEntry, onClick: () -> Unit = {}) {
    val accent = readableAccent(scheduleColor(entry.kind))
    val timeWidth = 48.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(timeWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(clockText(entry.start), style = MaterialTheme.typography.titleSmall.copy(fontFamily = CampusMono), maxLines = 1, softWrap = false)
                    if (entry.end > entry.start) Text(clockText(entry.end), style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.kind != ScheduleKind.CLASS) Text(scheduleLabel(entry.kind), style = MaterialTheme.typography.labelMedium, color = accent)
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (entry.kind == ScheduleKind.CANCELED) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    val metadata = listOf(entry.classCode, entry.room).filter(String::isNotBlank).joinToString(" · ")
                    if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
    }
}

@Composable fun DeadlineCard(event: CalendarEvent, now: Long, courseName: String = "", modifier: Modifier = Modifier) {
    val due = Instant.fromEpochSeconds(event.start).toLocalDateTime(UitTimeZone).date
    val urgent = event.needsAction && event.start - now < 86400
    val status = if (event.submitted == true) "Submitted" else relativeDue(event.start, now)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${due.dayOfMonth}", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = CampusMono))
                Text(due.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (courseName.isNotBlank()) Text(courseName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = CampusMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(event.name, style = MaterialTheme.typography.titleSmall)
                Text("${dateText(event.start)} · ${clockText(event.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelMedium, color = if (urgent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    }
}
