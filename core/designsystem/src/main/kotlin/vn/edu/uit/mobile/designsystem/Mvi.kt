package vn.edu.uit.mobile.designsystem

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import vn.edu.uit.mobile.common.*

abstract class BaseMviViewModel<S, I, E>(initial: S) :
    ViewModel(),
    MviViewModel<S, I, E> {
    protected val state = MutableStateFlow(initial)
    protected val effects = Channel<E>(Channel.BUFFERED)
    override val uiState = state.asStateFlow()
    override val uiEffect = effects.receiveAsFlow()
}
fun AppError.text(): String = when (this) {
    AppError.SessionExpired -> "Your UIT session expired. Sign in again in Accounts."
    AppError.NetworkOffline -> "You're offline. Your saved data is still available."
    is AppError.ValidationError -> message
    is AppError.PortalUnavailable -> "${portal.name} is unavailable${code?.let { " ($it)" }.orEmpty()}. Try refreshing later."
}
