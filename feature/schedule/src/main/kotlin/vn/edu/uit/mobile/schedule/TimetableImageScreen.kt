package vn.edu.uit.mobile.schedule

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.*
import kotlinx.datetime.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.*

@Composable fun TimetableImageScreen(snapshot: PortalSnapshot, anchor: LocalDate, onBack: () -> Unit, isCurrent: (TimetableImagePlan) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TimetableImageShares.cleanup(context) } }
    TimetableImageContent(snapshot, anchor, busy, failed, onBack) { plan, bitmap ->
        if (!busy) {
            busy = true
            failed = false
            scope.launch {
                var file: File? = null
                var launched = false
                try {
                    check(current(plan))
                    withContext(Dispatchers.IO) { file = TimetableImageShares.write(context, bitmap) }
                    ensureActive()
                    check(current(plan))
                    val send = TimetableImageShares.intent(context, requireNotNull(file))
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.ti_chooser)))
                    launched = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                } finally {
                    if (!launched) withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                    busy = false
                }
            }
        }
    }
}

@Composable fun TimetableImageContent(snapshot: PortalSnapshot, anchor: LocalDate, busy: Boolean = false, shareFailed: Boolean = false, onBack: () -> Unit = {}, onShare: (TimetableImagePlan, Bitmap) -> Unit = { _, _ -> }) {
    var from by rememberSaveable { mutableStateOf(anchor.toString()) }
    var through by rememberSaveable { mutableStateOf(anchor.toString()) }
    var names by rememberSaveable { mutableStateOf(true) }
    var rooms by rememberSaveable { mutableStateOf(false) }
    var identity by rememberSaveable { mutableStateOf(false) }
    var asText by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val fontScale = configuration.fontScale
    val locale = configuration.locales.toLanguageTags()
    val plan = remember(snapshot, from, through, names, rooms, identity) {
        runCatching { TimetableImageExport().plan(snapshot, TimetableImageOptions(LocalDate.parse(from), LocalDate.parse(through), names, rooms, identity)) }.getOrNull()
    }
    var bitmap by remember(plan, fontScale, locale) { mutableStateOf<Bitmap?>(null) }
    var renderFailed by remember(plan, fontScale, locale) { mutableStateOf(false) }
    LaunchedEffect(plan, fontScale, locale) {
        if (plan != null) {
            try {
                bitmap = withContext(Dispatchers.Default) { TimetableImageRenderer.render(context, plan, fontScale) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                renderFailed = true
            }
        }
    }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("timetable-image-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onBack, enabled = !busy, shape = TechnicalShapes.Control) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(stringResource(R.string.ti_back))
                }
                PageHeading(stringResource(R.string.ti_title), stringResource(R.string.ti_subtitle))
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ti_range), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ti_range_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(from, { from = it.take(10) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.ti_from)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    OutlinedTextField(through, { through = it.take(10) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.ti_through)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), shape = TechnicalShapes.Field)
                    Row {
                        TextButton({
                            from = anchor.toString()
                            through = anchor.toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ti_day)) }
                        TextButton({
                            from = anchor.toString()
                            through = anchor.plus(6, DateTimeUnit.DAY).toString()
                        }, enabled = !busy, shape = TechnicalShapes.Control) { Text(stringResource(R.string.ti_week)) }
                    }
                }
            }
            item {
                SectionHeading(stringResource(R.string.ti_include), stringResource(R.string.ti_privacy))
                ImageOption(stringResource(R.string.ti_names), names, !busy) { names = it }
                ImageOption(stringResource(R.string.ti_rooms), rooms, !busy) { rooms = it }
                ImageOption(stringResource(R.string.ti_identity), identity, !busy && (snapshot.profile.fullName.isNotBlank() || snapshot.profile.studentId.isNotBlank())) { identity = it }
            }
            item {
                SectionHeading(stringResource(R.string.ti_preview), stringResource(R.string.ti_scope))
                when {
                    plan == null -> InlineNotice(stringResource(if (snapshot.updatedAt <= 0) R.string.ti_no_data else R.string.ti_invalid), true)
                    renderFailed -> InlineNotice(stringResource(R.string.ti_render_error), true)
                    bitmap == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        TextButton({ asText = !asText }, shape = TechnicalShapes.Control) { Text(stringResource(if (asText) R.string.ti_view_image else R.string.ti_read_text)) }
                        if (asText) {
                            Panel {
                                if (plan.identity.isNotBlank()) Text(plan.identity)
                                plan.days.forEach { day ->
                                    Text(day.date.toString(), style = MaterialTheme.typography.titleMedium)
                                    if (day.rows.isEmpty()) Text(stringResource(R.string.ti_image_empty))
                                    day.rows.forEach { row ->
                                        Text(imageRowLabel(row.kind) + " · " + clockText(row.start) + if (row.end > row.start) "–${clockText(row.end)}" else "")
                                        if (row.title.isNotBlank()) Text(row.title)
                                        if (row.classCode.isNotBlank()) Text(row.classCode)
                                        if (row.room.isNotBlank()) Text(stringResource(R.string.ti_image_room, row.room))
                                    }
                                }
                                Text(stringResource(R.string.ti_image_saved, dateText(plan.updatedAt), clockText(plan.updatedAt)))
                            }
                        } else {
                            val image = requireNotNull(bitmap)
                            Image(image.asImageBitmap(), contentDescription = stringResource(R.string.ti_preview_description, from, through), modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height).testTag("timetable-image-preview"))
                        }
                        Text(stringResource(R.string.ti_share_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (shareFailed) InlineNotice(stringResource(R.string.ti_share_error), true)
            }
        }
        Button({
            val image = bitmap
            if (plan != null && image != null) onShare(plan, image)
        }, enabled = !busy && plan != null && bitmap != null && !renderFailed, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = TechnicalShapes.Control) {
            Icon(Icons.Outlined.Share, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (busy) R.string.ti_sharing else R.string.ti_share))
        }
    }
}

@Composable private fun ImageOption(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun imageRowLabel(kind: ScheduleKind): String = stringResource(
    when (kind) {
        ScheduleKind.CANCELED -> R.string.ti_canceled
        ScheduleKind.MAKE_UP -> R.string.ti_makeup
        else -> R.string.ti_class
    }
)
