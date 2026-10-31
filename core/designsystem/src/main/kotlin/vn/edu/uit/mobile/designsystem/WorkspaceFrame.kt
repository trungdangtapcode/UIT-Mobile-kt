package vn.edu.uit.mobile.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class WorkspaceDestination(val label: String, val icon: ImageVector)
private val WorkspaceDestinations = listOf(
    WorkspaceDestination("Today", Icons.Outlined.Today),
    WorkspaceDestination("Schedule", Icons.Outlined.CalendarMonth),
    WorkspaceDestination("Courses", Icons.Outlined.DataObject),
    WorkspaceDestination("Inbox", Icons.Outlined.Forum),
    WorkspaceDestination("Academic", Icons.Outlined.AccountTree)
)

/** Phone navigation and a single reading column at every window size. */
@Composable
fun WorkspaceFrame(
    selected: Int,
    onSelect: (Int) -> Unit,
    onAccounts: () -> Unit,
    displayName: String,
    connected: Boolean,
    syncing: Boolean,
    lastSync: Long,
    deadlineCount: Int = 0,
    errorMessage: String? = null,
    onSearch: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier.widthIn(max = 600.dp).fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            UitEmblem(Modifier.size(40.dp).testTag("uit-brand-mark"))
                            Column(Modifier.weight(1f)) {
                                Text("UIT Mobile", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        syncing -> "Syncing…"
                                        !connected -> "Connect your account"
                                        errorMessage != null -> "Sync needs attention"
                                        lastSync > 0 -> "Saved ${clockText(lastSync)}"
                                        else -> "Moodle · DAA"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (onSearch != null) IconButton(onSearch) { Icon(Icons.Outlined.Search, "Search saved workspace") }
                            IconButton(onClick = { onSelect(3) }) {
                                BadgedBox(badge = { if (deadlineCount > 0) Badge { Text(if (deadlineCount > 9) "9+" else "$deadlineCount") } }) {
                                    Icon(Icons.Outlined.NotificationsNone, "Open deadlines and inbox", Modifier.size(22.dp))
                                }
                            }
                            Surface(
                                onClick = onAccounts,
                                shape = TechnicalShapes.Tile,
                                color = Color.Transparent,
                                modifier = Modifier.testTag("workspace-account").semantics { stateDescription = displayName.ifBlank { "Student account" } }
                            ) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.PersonOutline, "Open student profile", Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                }
            }
        },
        bottomBar = {
            if (!keyboardVisible) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            NavigationBar(
                                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("mobile-bottom-navigation"),
                                containerColor = MaterialTheme.colorScheme.background,
                                tonalElevation = 0.dp
                            ) {
                                WorkspaceDestinations.forEachIndexed { index, destination ->
                                    NavigationBarItem(
                                        selected = selected == index,
                                        onClick = { onSelect(index) },
                                        icon = { Icon(destination.icon, null, Modifier.size(21.dp)) },
                                        label = { Text(destination.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            indicatorColor = Color.Transparent
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                AnimatedVisibility(syncing) { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
                if (errorMessage != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { InlineNotice(errorMessage, true) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CampusBackdrop(Modifier.align(Alignment.TopEnd).fillMaxWidth().height(180.dp))
                    content()
                }
            }
        }
    }
}
