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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.auth.R
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel, onLogin: (String) -> Unit, dark: Boolean, dynamic: Boolean, onDark: (Boolean) -> Unit, onDynamic: (Boolean) -> Unit, onProfile: ((Account) -> Unit)? = null, onWidgets: (() -> Unit)? = null, language: AppLanguage = AppLanguage.VIETNAMESE, onLanguage: (AppLanguage) -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Account?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.accounts.isEmpty()) {
            Surface(shape = TechnicalShapes.Field, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.Hub)
                    Text(stringResource(R.string.auth_connect_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.auth_connect_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.accounts.forEach { account ->
            Panel {
                Row(modifier = if (onProfile != null) Modifier.fillMaxWidth().clickable(onClickLabel = "View student profile") { onProfile(account) } else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(if (account.portal != PortalType.MOODLE) Icons.Outlined.School else Icons.AutoMirrored.Outlined.MenuBook, if (account.portal != PortalType.MOODLE) WorkspaceColors.Teal else WorkspaceColors.Ocean)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(accountTypeLabel(account), style = MaterialTheme.typography.titleSmall)
                        Text(account.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(stringResource(R.string.auth_linked), WorkspaceColors.Teal, true)
                }
                Text(account.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onProfile != null) TextButton(onClick = { onProfile(account) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.auth_view_profile)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onLogin(if (account.portal != PortalType.MOODLE) "https://courses.uit.edu.vn" else account.baseUrl) }, shape = TechnicalShapes.Field) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.auth_sign_in_again))
                    }
                    IconButton(onClick = { removing = account }) { Icon(Icons.Outlined.LinkOff, stringResource(R.string.auth_remove_description, account.baseUrl), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Button(onClick = { onLogin("https://courses.uit.edu.vn") }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
            Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auth_sign_in))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(shape = TechnicalShapes.Control, onClick = { onLogin("https://coursesold.uit.edu.vn") }) { Text(stringResource(R.string.auth_link_legacy)) }
            TextButton(shape = TechnicalShapes.Control, onClick = { onLogin("https://coursesold.uit.edu.vn/sdh") }) { Text(stringResource(R.string.auth_link_graduate)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.auth_appearance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppearanceSetting(Icons.Outlined.DarkMode, stringResource(R.string.auth_dark_title), stringResource(R.string.auth_dark_detail), dark, onDark)
        AppearanceSetting(Icons.Outlined.Palette, stringResource(R.string.auth_dynamic_title), stringResource(R.string.auth_dynamic_detail), dynamic, onDynamic)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.accounts_language_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { option ->
                FilterChip(
                    selected = language == option,
                    onClick = { onLanguage(option) },
                    shape = TechnicalShapes.Control,
                    label = { Text(stringResource(if (option == AppLanguage.VIETNAMESE) R.string.language_vietnamese else R.string.language_english)) }
                )
            }
        }
        onWidgets?.let {
            OutlinedButton(onClick = it, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Widgets, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.auth_widgets))
            }
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.auth_remove_title)) },
            text = { Text(stringResource(R.string.auth_remove_body, account.displayName)) },
            confirmButton = {
                TextButton(shape = TechnicalShapes.Control, onClick = {
                    viewModel.onIntent(AccountsIntent.Remove(account.id))
                    removing = null
                }) { Text(stringResource(R.string.auth_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(shape = TechnicalShapes.Control, onClick = { removing = null }) { Text(stringResource(R.string.auth_remove_keep)) } }
        )
    }
}

@Composable private fun accountTypeLabel(account: Account): String = stringResource(
    when {
        account.portal != PortalType.MOODLE -> R.string.auth_account_academic
        account.baseUrl.endsWith("/sdh") -> R.string.auth_account_graduate
        account.baseUrl.contains("coursesold") -> R.string.auth_account_legacy
        else -> R.string.auth_account_moodle
    }
)

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
