package vn.edu.uit.mobile.authui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.BaseMviViewModel
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class StudentProfileState(val account: Account? = null, val details: StudentProfileDetails? = null, val loading: Boolean = false, val error: AppError? = null)
sealed interface StudentProfileIntent {
    data class Open(val accountId: String? = null) : StudentProfileIntent
    data object Refresh : StudentProfileIntent
    data object Close : StudentProfileIntent
}

class StudentProfileViewModel(private val repository: PortalRepository, accounts: AccountRepository) : BaseMviViewModel<StudentProfileState, StudentProfileIntent, Unit>(StudentProfileState()) {
    private var linkedAccounts: List<Account> = emptyList()
    private var requested = false
    private var selectedId: String? = null
    private var load: Job? = null
    private var requestVersion = 0
    init {
        viewModelScope.launch {
            accounts.accounts.collect { latest ->
                linkedAccounts = latest
                if (requested) {
                    val selected = resolve()
                    if (state.value.account?.id != selected?.id) show(selected)
                }
            }
        }
    }
    override fun onIntent(intent: StudentProfileIntent) {
        when (intent) {
            is StudentProfileIntent.Open -> {
                requested = true
                selectedId = intent.accountId
                show(resolve())
            }
            StudentProfileIntent.Refresh -> if (requested) show(resolve(), preserve = true)
            StudentProfileIntent.Close -> {
                requested = false
                requestVersion++
                load?.cancel()
                state.value = StudentProfileState()
            }
        }
    }
    private fun resolve(): Account? = if (selectedId != null) linkedAccounts.firstOrNull { it.id == selectedId } else linkedAccounts.firstOrNull { it.portal == PortalType.UIT_PORTAL } ?: linkedAccounts.firstOrNull()
    private fun show(account: Account?, preserve: Boolean = false) {
        val version = ++requestVersion
        load?.cancel()
        val cached = state.value.details?.takeIf { preserve && it.accountId == account?.id }
        state.value = StudentProfileState(account, cached, loading = account != null)
        if (account == null) return
        load = viewModelScope.launch {
            val result = appResult(PortalType.UIT_PORTAL) { repository.studentProfile(account.id) }
            val response = when (result) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> result
            }
            if (!requested || version != requestVersion || resolve()?.id != account.id) return@launch
            state.value = when (response) {
                is AppResult.Success -> StudentProfileState(account, response.data, loading = false)
                is AppResult.Failure -> StudentProfileState(account, cached, loading = false, error = response.error)
            }
        }
    }
}
