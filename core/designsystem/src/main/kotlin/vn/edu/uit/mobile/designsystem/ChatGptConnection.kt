package vn.edu.uit.mobile.designsystem

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import vn.edu.uit.mobile.core.designsystem.R
import vn.edu.uit.mobile.domain.*

val LocalChatGptConnector = staticCompositionLocalOf<ChatGptConnector?> { null }

@Composable fun chatGptLabel(): String = stringResource(R.string.cg_mode)

@Composable fun ChatGptUsage(connection: StudyConnection) {
    if (connection.mode != StudyConnectionMode.CHATGPT || !connection.configured) return
    val context = LocalContext.current
    Column {
        Text(stringResource(R.string.cg_using), style = MaterialTheme.typography.labelMedium)
        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) { Text(stringResource(R.string.cg_usage)) }
    }
}

/** The shared settings surface never receives or displays an OAuth credential. */
@Composable fun ChatGptConnectionPanel(onUseModel: (String) -> Unit, enabled: Boolean = true) {
    val connector = LocalChatGptConnector.current
    if (connector == null) {
        Text(stringResource(R.string.cg_unavailable))
        return
    }
    val state by connector.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var work by remember { mutableStateOf<Job?>(null) }
    var localError by remember { mutableStateOf(false) }
    var model by remember(state.selected, state.models) { mutableStateOf(state.models.firstOrNull()?.id.orEmpty()) }
    var modelMenu by remember { mutableStateOf(false) }
    fun action(body: suspend () -> Unit) {
        work?.cancel()
        work = scope.launch {
            localError = false
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                localError = true
            }
        }
    }
    fun signIn(id: String? = null, plan: Boolean = false) = action {
        connector.signIn({ url -> withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } }, id, plan)
    }
    LaunchedEffect(connector) {
        try {
            connector.initialize()
        } catch (_: Exception) {
            localError = true
        }
    }
    DisposableEffect(connector) {
        onDispose {
            connector.cancelSignIn()
            work?.cancel()
        }
    }
    val selected = state.accounts.firstOrNull { it.id == state.selected }
    Column(Modifier.fillMaxWidth().testTag("chatgpt-connection"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.cg_description), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.cg_eligibility), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.accounts.forEach { account ->
            OutlinedButton({ action { connector.selectAccount(account.id) } }, Modifier.fillMaxWidth(), enabled = enabled && !state.busy, shape = TechnicalShapes.Field) {
                RadioButton(state.selected == account.id, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(account.label, Modifier.weight(1f))
            }
        }
        ChatGptConnectionProblem(state, localError)
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(if (state.loadingModels) R.string.cg_loading_models else R.string.cg_waiting), style = MaterialTheme.typography.bodySmall)
            TextButton({
                connector.cancelSignIn()
                work?.cancel()
            }) { Text(stringResource(R.string.cg_cancel)) }
        } else if (selected?.signedIn != true || state.problem == ChatGptProblem.REAUTH) {
            Button({ signIn(selected?.id) }, Modifier.fillMaxWidth().testTag("chatgpt-continue"), enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.cg_continue)) }
        } else if (!selected.planEnabled) {
            Text(stringResource(R.string.cg_plan_disabled), color = MaterialTheme.colorScheme.error)
            Button({ signIn(selected.id, true) }, enabled = enabled, shape = TechnicalShapes.Control) { Text(stringResource(R.string.cg_enable)) }
        } else {
            Text(stringResource(R.string.cg_connected), style = MaterialTheme.typography.titleSmall)
            if (state.models.isEmpty()) Text(stringResource(R.string.cg_models_pending), style = MaterialTheme.typography.bodyMedium)
            if (state.models.isNotEmpty()) {
                Box {
                    OutlinedButton({ modelMenu = true }, Modifier.fillMaxWidth().testTag("chatgpt-model"), enabled = enabled, shape = TechnicalShapes.Field) { Text(state.models.firstOrNull { it.id == model }?.name.orEmpty()) }
                    DropdownMenu(modelMenu, { modelMenu = false }) {
                        state.models.forEach { choice ->
                            DropdownMenuItem({ Text(choice.name) }, {
                                model = choice.id
                                modelMenu = false
                            })
                        }
                    }
                }
                Button({ onUseModel(model) }, Modifier.fillMaxWidth().testTag("chatgpt-use"), enabled = enabled && model.isNotEmpty(), shape = TechnicalShapes.Control) { Text(stringResource(R.string.cg_use)) }
            }
            TextButton({ action { connector.reloadModels() } }, Modifier.testTag("chatgpt-reload-models"), enabled = enabled) { Text(stringResource(R.string.cg_refresh_models)) }
        }
        if (state.accounts.isNotEmpty() && !state.busy) {
            TextButton({ signIn() }, enabled = enabled) { Text(stringResource(R.string.cg_add_account)) }
            if (selected?.signedIn == true) TextButton({ action { connector.signOut() } }, enabled = enabled) { Text(stringResource(R.string.cg_sign_out)) }
        }
        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }, Modifier.testTag("chatgpt-usage")) { Text(stringResource(R.string.cg_usage)) }
        Text(stringResource(R.string.cg_privacy), style = MaterialTheme.typography.bodySmall)
    }
    if (state.welcome && selected?.planEnabled == true && !state.busy && state.models.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { action { connector.acknowledgeWelcome() } },
            shape = TechnicalShapes.Dialog,
            title = { Text(stringResource(R.string.cg_welcome)) },
            text = { Text(stringResource(R.string.cg_welcome_body)) },
            confirmButton = { TextButton({ action { connector.acknowledgeWelcome() } }) { Text(stringResource(R.string.cg_got_it)) } }
        )
    }
}

@Composable private fun ChatGptConnectionProblem(state: ChatGptState, localError: Boolean) {
    val clipboard = LocalClipboardManager.current
    val problem = state.problem
    if (problem != null || localError) {
        val message = when (problem) {
            ChatGptProblem.CANCELLED -> R.string.cg_cancelled
            ChatGptProblem.TIMEOUT -> if (state.diagnostic?.operation == "models") R.string.cg_models_timeout else R.string.cg_timeout
            ChatGptProblem.VALIDATION -> R.string.cg_validation
            ChatGptProblem.ACCESS -> R.string.cg_access
            ChatGptProblem.REAUTH -> R.string.cg_reauth
            ChatGptProblem.QUOTA -> R.string.cg_quota
            ChatGptProblem.NO_MODELS -> R.string.cg_no_models
            ChatGptProblem.REVOCATION -> R.string.cg_revocation
            ChatGptProblem.UNSUPPORTED -> R.string.cg_unsupported
            ChatGptProblem.RESPONSE -> R.string.cg_response
            ChatGptProblem.UNAVAILABLE -> R.string.cg_service_unavailable
            else -> R.string.cg_network
        }
        Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.diagnostic?.let { details ->
        TextButton({ clipboard.setText(AnnotatedString("ChatGPT connection\noperation=${details.operation}\nstatus=${details.status}\ncode=${details.code}\nrequestId=${details.requestId}\nbodyShape=${details.bodyShape}\nresponseBytes=${details.responseBytes ?: "unknown"}\nphase=${details.phase}\nexceptionTypes=${details.exceptionTypes}\nprotocol=${details.protocol}\nattempt=${details.attempt}")) }, Modifier.testTag("chatgpt-copy-diagnostics")) { Text(stringResource(R.string.cg_copy_details)) }
    }
}
