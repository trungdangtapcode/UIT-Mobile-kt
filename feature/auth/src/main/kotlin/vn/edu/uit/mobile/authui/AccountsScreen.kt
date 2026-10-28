package vn.edu.uit.mobile.authui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class AccountsState(val accounts: List<Account> = emptyList())
sealed interface AccountsIntent {
    data class Remove(val id: String) : AccountsIntent
    data class Save(val sessions: List<PortalSession>) : AccountsIntent
}
class AccountsViewModel(private val repository: AccountRepository) : BaseMviViewModel<AccountsState, AccountsIntent, String>(AccountsState()) {
    init {
        viewModelScope.launch { repository.accounts.collect { state.value = AccountsState(it) } }
    }
    override fun onIntent(intent: AccountsIntent) {
        viewModelScope.launch {
            when (intent) {
                is AccountsIntent.Remove -> repository.remove(intent.id)
                is AccountsIntent.Save -> repository.saveSessions(intent.sessions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel, onLogin: (String) -> Unit, dark: Boolean, dynamic: Boolean, onDark: (Boolean) -> Unit, onDynamic: (Boolean) -> Unit, onProfile: ((Account) -> Unit)? = null, onWidgets: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Account?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.accounts.isEmpty()) {
            Surface(shape = TechnicalShapes.Field, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.Hub)
                    Text("Connect UIT SSO", style = MaterialTheme.typography.titleLarge)
                    Text("Bring Moodle courses and UIT Portal academic records into one workspace.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.accounts.forEach { account ->
            Panel {
                Row(modifier = if (onProfile != null) Modifier.fillMaxWidth().clickable(onClickLabel = "View student profile") { onProfile(account) } else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(if (account.portal != PortalType.MOODLE) Icons.Outlined.School else Icons.AutoMirrored.Outlined.MenuBook, if (account.portal != PortalType.MOODLE) WorkspaceColors.Teal else WorkspaceColors.Ocean)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (account.portal != PortalType.MOODLE) {
                                "Academic records"
                            } else if (account.baseUrl.endsWith("/sdh")) {
                                "Graduate Moodle"
                            } else if (account.baseUrl.contains("coursesold")) {
                                "Legacy Moodle"
                            } else {
                                "UIT Moodle"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(account.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill("Linked", WorkspaceColors.Teal, true)
                }
                Text(account.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onProfile != null) TextButton(onClick = { onProfile(account) }, shape = TechnicalShapes.Control) { Text("View student profile") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onLogin(if (account.portal != PortalType.MOODLE) "https://courses.uit.edu.vn" else account.baseUrl) }, shape = TechnicalShapes.Field) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign in again")
                    }
                    IconButton(onClick = { removing = account }) { Icon(Icons.Outlined.LinkOff, "Remove ${account.baseUrl}", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Button(onClick = { onLogin("https://courses.uit.edu.vn") }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
            Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign in with UIT SSO")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(shape = TechnicalShapes.Control, onClick = { onLogin("https://coursesold.uit.edu.vn") }) { Text("Link legacy Moodle") }
            TextButton(shape = TechnicalShapes.Control, onClick = { onLogin("https://coursesold.uit.edu.vn/sdh") }) { Text("Link graduate Moodle") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("APPEARANCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppearanceSetting(Icons.Outlined.DarkMode, "Dark appearance", "Ocean dark theme", dark, onDark)
        AppearanceSetting(Icons.Outlined.Palette, "Device colors", "Match your wallpaper's color palette", dynamic, onDynamic)
        onWidgets?.let {
            OutlinedButton(onClick = it, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Widgets, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Home screen widgets")
            }
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this account?") },
            text = { Text("Remove ${account.displayName} and its saved course data from this device?") },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    viewModel.onIntent(AccountsIntent.Remove(account.id))
                    removing = null
                }) { Text("Remove account", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(shape = TechnicalShapes.Control, onClick = { removing = null }) { Text("Keep account") } }
        )
    }
}

@Composable private fun AppearanceSetting(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
