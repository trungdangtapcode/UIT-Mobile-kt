package vn.edu.uit.mobile.authui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*

@Composable fun StudentProfileScreen(viewModel: StudentProfileViewModel, onBack: () -> Unit, onManageAccounts: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    StudentProfileContent(state, onBack, onManageAccounts, onRefresh = { viewModel.onIntent(StudentProfileIntent.Refresh) }, onOpenScan = { viewModel.onIntent(StudentProfileIntent.OpenScan(it)) })
    state.scan?.let { scan ->
        ProfileScanViewer(scan, onClose = { viewModel.onIntent(StudentProfileIntent.CloseScan) }, onRetry = { viewModel.onIntent(StudentProfileIntent.RetryScan) })
    }
}

@Composable fun StudentProfileContent(state: StudentProfileState, onBack: () -> Unit = {}, onManageAccounts: () -> Unit = {}, onRefresh: () -> Unit = {}, onOpenScan: (String) -> Unit = {}) {
    val details = state.details
    var selected by rememberSaveable(state.account?.id) { mutableStateOf("personal") }
    val section = details?.sections?.firstOrNull { it.id == selected } ?: details?.sections?.firstOrNull()
    val portrait = remember(details?.portrait) { details?.portrait?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } }
    LazyColumn(Modifier.fillMaxSize().testTag("student-profile-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back from student profile") }
                Text("Student profile", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onRefresh, enabled = state.account != null && !state.loading) { Icon(Icons.Outlined.Refresh, "Refresh student profile") }
                IconButton(onClick = onManageAccounts) { Icon(Icons.Outlined.Settings, "Manage accounts and appearance") }
            }
        }
        if (state.account == null) {
            item {
                Panel {
                    SectionHeading("Connect your student account")
                    Text("Sign in with UIT SSO to view your student profile.")
                    Button(onClick = onManageAccounts, shape = TechnicalShapes.Field) { Text("Sign in with UIT SSO") }
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (portrait == null) {
                            Avatar(details?.fullName ?: state.account.displayName, Modifier.size(64.dp))
                        } else {
                            Image(portrait, "Student portrait", Modifier.size(width = 64.dp, height = 84.dp).clip(TechnicalShapes.Field), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(details?.fullName ?: state.account.displayName, style = MaterialTheme.typography.headlineSmall)
                            details?.studentCode?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                            details?.className?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    details?.faculty?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    details?.major?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    details?.enrollmentStatus?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (state.loading) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Loading your UIT Portal profile…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.error != null) {
                item {
                    Surface(shape = TechnicalShapes.Field, color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when (val error = state.error) {
                                    AppError.SessionExpired -> "Sign in again to load this student profile."
                                    AppError.NetworkOffline -> "Cannot reach UIT Portal. Check your connection and try again."
                                    is AppError.ValidationError -> error.message
                                    else -> "The student profile could not be loaded. Try again."
                                },
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (details != null) Text("Showing the profile already loaded in this session.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onRefresh, enabled = !state.loading, shape = TechnicalShapes.Control) { Text("Try again") }
                                TextButton(onClick = onManageAccounts, shape = TechnicalShapes.Control) { Text("Account settings") }
                            }
                        }
                    }
                }
            }
            if (details != null) {
                item {
                    Text("Loaded ${dateText(details.fetchedAt)} at ${clockText(details.fetchedAt)} · GMT+07", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().testTag("student-profile-sections").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.sections.forEach { tab -> FilterChip(selected = tab.id == section?.id, onClick = { selected = tab.id }, shape = TechnicalShapes.Control, label = { Text(tab.title) }) }
                    }
                }
                if (section?.failure != null) {
                    item {
                        Panel {
                            Text(if (section.failure == vn.edu.uit.mobile.model.ServiceFailureKind.SIGN_IN) "Sign in again to update insurance information." else "Insurance information could not be loaded.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRefresh, enabled = !state.loading, shape = TechnicalShapes.Control) { Text("Try again") }
                        }
                    }
                } else if (section?.groups.isNullOrEmpty()) {
                    item {
                        EmptyState(section?.title ?: "Profile", "No details were supplied for this section.")
                    }
                }
                items(section?.groups.orEmpty()) { group ->
                    Panel(Modifier.fillMaxWidth()) {
                        SectionHeading(group.title)
                        group.fields.forEach { field ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(field.value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        group.document?.let { document ->
                            OutlinedButton(onClick = { onOpenScan(document.id) }, enabled = document.available && !state.loading, shape = TechnicalShapes.Field) {
                                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (document.available) "View scan" else "No scan uploaded")
                            }
                        }
                    }
                }
            }
        }
    }
}
