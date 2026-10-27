package vn.edu.uit.mobile.authui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.BaseMviViewModel
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*

@Immutable data class ProfileScanState(val document: ProfileDocument, val bytes: ByteArray? = null, val loading: Boolean = true, val error: AppError? = null)

@Immutable data class StudentProfileState(val account: Account? = null, val details: StudentProfileDetails? = null, val loading: Boolean = false, val error: AppError? = null, val scan: ProfileScanState? = null)
sealed interface StudentProfileIntent {
    data class Open(val accountId: String? = null) : StudentProfileIntent
    data object Refresh : StudentProfileIntent
    data object Close : StudentProfileIntent
    data class OpenScan(val documentId: String) : StudentProfileIntent
    data object CloseScan : StudentProfileIntent
    data object RetryScan : StudentProfileIntent
}

class StudentProfileViewModel(private val repository: PortalRepository, accounts: AccountRepository) : BaseMviViewModel<StudentProfileState, StudentProfileIntent, Unit>(StudentProfileState()) {
    private var linkedAccounts: List<Account> = emptyList()
    private var requested = false
    private var selectedId: String? = null
    private var load: Job? = null
    private var requestVersion = 0
    private var scanLoad: Job? = null
    private var scanVersion = 0
    init {
        viewModelScope.launch {
            accounts.accounts.collect { latest ->
                val previous = linkedAccounts
                linkedAccounts = latest
                if (requested) {
                    val selected = resolve()
                    val portalRemoved = previous.any { it.portal == PortalType.UIT_PORTAL && it.userId == selected?.userId && it !in latest }
                    if (state.value.account?.id != selected?.id || portalRemoved) show(selected)
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
                closeScan()
                state.value = StudentProfileState()
            }
            is StudentProfileIntent.OpenScan -> openScan(intent.documentId)
            StudentProfileIntent.CloseScan -> closeScan()
            StudentProfileIntent.RetryScan -> state.value.scan?.document?.id?.let(::openScan)
        }
    }
    private fun resolve(): Account? = if (selectedId != null) linkedAccounts.firstOrNull { it.id == selectedId } else linkedAccounts.firstOrNull { it.portal == PortalType.UIT_PORTAL } ?: linkedAccounts.firstOrNull()
    private fun show(account: Account?, preserve: Boolean = false) {
        closeScan()
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

    private fun closeScan() {
        scanVersion++
        scanLoad?.cancel()
        state.value = state.value.copy(scan = null)
    }
    private fun openScan(documentId: String) {
        if (!requested) return
        val account = state.value.account ?: return
        val document = state.value.details?.sections?.flatMap { it.groups }?.mapNotNull { it.document }?.singleOrNull { it.id == documentId && it.available } ?: return
        closeScan()
        val version = scanVersion
        state.value = state.value.copy(scan = ProfileScanState(document))
        scanLoad = viewModelScope.launch {
            val result = appResult(PortalType.UIT_PORTAL) { repository.studentProfileScan(account.id, document.id) }
            val response = when (result) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> result
            }
            if (!requested || version != scanVersion || resolve()?.id != account.id) return@launch
            val scan = when (response) {
                is AppResult.Success -> if (response.data.documentId == document.id) ProfileScanState(document, response.data.bytes, loading = false) else ProfileScanState(document, loading = false, error = AppError.PortalUnavailable(PortalType.UIT_PORTAL, "invalidresponse"))
                is AppResult.Failure -> ProfileScanState(document, loading = false, error = response.error)
            }
            state.value = state.value.copy(scan = scan)
        }
    }
}
