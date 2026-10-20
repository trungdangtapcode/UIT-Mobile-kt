package vn.edu.uit.mobile.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.uit.mobile.model.*

sealed interface AppError {
    data object SessionExpired : AppError
    data object NetworkOffline : AppError
    data class PortalUnavailable(val portal: PortalType, val code: String? = null) : AppError
    data class ValidationError(val message: String) : AppError
}
sealed interface AppResult<out D, out E : AppError> {
    data class Success<out D>(val data: D) : AppResult<D, Nothing>
    data class Failure<out E : AppError>(val error: E) : AppResult<Nothing, E>
}
open class PortalException(val errorcode: String, message: String, cause: Throwable? = null) : Exception(message, cause)
class SessionExpiredException : PortalException("sessionexpired", "Sign in again to refresh your UIT session")
class InvalidResponseException(message: String) : PortalException("invalidresponse", message)
class OriginRejectedException : PortalException("originrejected", "Request destination is not permitted")
object SessionHealthClassifier {
    private val codes = setOf(
        "requireloginerror", "servicerequireslogin", "invalidsesskey", "notloggedin", "sessionexpired",
        "sessionnotauthenticated", "notauthenticated", "authenticationfailed", "loginrequired", "invalidsession"
    )
    private val message = Regex("(?:session|sesskey|authentication)\\s+(?:is\\s+|has\\s+)?(?:expired|invalid|failed)|not\\s+(?:authenticated|logged\\s+in)|(?:sign|log)\\s+in\\s+again|(?:requires?|needs?)\\s+(?:a\\s+)?login|invalid\\s+(?:session|sesskey)|phiên\\s+đăng\\s+nhập\\s+đã\\s+(?:hết\\s+hạn|đăng\\s+xuất)", RegexOption.IGNORE_CASE)
    fun classifySessionError(error: Throwable): SessionHealthState {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = error
        repeat(8) {
            val item = current ?: return SessionHealthState.UNAVAILABLE
            if (!visited.add(item)) return SessionHealthState.UNAVAILABLE
            val code = (item as? PortalException)?.errorcode?.lowercase()?.replace(Regex("[\\s_-]+"), "")
            if (code in codes || message.containsMatchIn(item.message.orEmpty())) return SessionHealthState.EXPIRED
            current = item.cause
        }
        return SessionHealthState.UNAVAILABLE
    }
}
suspend fun <T> appResult(portal: PortalType, block: suspend () -> T): AppResult<T, AppError> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(
        when {
            SessionHealthClassifier.classifySessionError(e) == SessionHealthState.EXPIRED -> AppError.SessionExpired
            e is java.io.IOException -> AppError.NetworkOffline
            e is IllegalArgumentException -> AppError.ValidationError(e.message ?: "Invalid input")
            else -> AppError.PortalUnavailable(portal, (e as? PortalException)?.errorcode)
        }
    )
}
data class AppDispatchers(val io: CoroutineDispatcher = Dispatchers.IO, val default: CoroutineDispatcher = Dispatchers.Default)
interface MviViewModel<State, Intent, Effect> {
    val uiState: StateFlow<State>
    val uiEffect: Flow<Effect>
    fun onIntent(intent: Intent)
}

interface SessionStore {
    val sessions: kotlinx.coroutines.flow.Flow<List<PortalSession>>
    suspend fun save(sessions: List<PortalSession>)
    suspend fun remove(accountId: String)
}
