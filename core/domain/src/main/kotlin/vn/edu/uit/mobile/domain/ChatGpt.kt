package vn.edu.uit.mobile.domain

import kotlinx.coroutines.flow.StateFlow

data class ChatGptAccount(val id: String, val label: String, val signedIn: Boolean, val planEnabled: Boolean)
data class ChatGptModel(val id: String, val name: String)
enum class ChatGptProblem { CANCELLED, TIMEOUT, NETWORK, VALIDATION, ACCESS, NO_MODELS, REAUTH, QUOTA, UNSUPPORTED, UNAVAILABLE, REVOCATION }
data class ChatGptDiagnostic(val status: Int, val code: String = "", val requestId: String = "", val bodyShape: String = "")
data class ChatGptState(
    val accounts: List<ChatGptAccount> = emptyList(),
    val selected: String? = null,
    val models: List<ChatGptModel> = emptyList(),
    val busy: Boolean = false,
    val problem: ChatGptProblem? = null,
    val welcome: Boolean = false,
    val diagnostic: ChatGptDiagnostic? = null
)

/** Public UI state never contains OAuth codes, tokens, nonce or PKCE material. */
interface ChatGptConnector {
    val state: StateFlow<ChatGptState>
    suspend fun initialize()
    suspend fun signIn(openBrowser: suspend (String) -> Unit, accountId: String? = null, enablePlan: Boolean = false)
    fun cancelSignIn()
    suspend fun selectAccount(id: String)
    suspend fun reloadModels()
    suspend fun signOut()
    suspend fun acknowledgeWelcome()
}

class ChatGptException(val problem: ChatGptProblem, val diagnostic: ChatGptDiagnostic? = null) : IllegalStateException("ChatGPT connection: ${problem.name}")
