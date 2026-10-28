package vn.edu.uit.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import vn.edu.uit.mobile.academic.*
import vn.edu.uit.mobile.auth.*
import vn.edu.uit.mobile.authui.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.schedule.*

class MainActivity : ComponentActivity() {
    private val repository: PortalRepository by inject()
    private val accounts: AccountRepository by inject()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", true)) }
            var dynamic by rememberSaveable { mutableStateOf(preferences.getBoolean("dynamic", true)) }
            UitTheme(dark, dynamic) {
                SideEffect {
                    val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = !dark
                    bars.isAppearanceLightNavigationBars = !dark
                }
                val dashboard = ownedViewModel { DashboardViewModel(repository) }
                val schedule = ownedViewModel { ScheduleViewModel(repository) }
                val courses = ownedViewModel { CoursesViewModel(repository) }
                val inbox = ownedViewModel { InboxViewModel(repository) }
                val academic = ownedViewModel { AcademicViewModel(repository) }
                val auth = ownedViewModel { AccountsViewModel(accounts) }
                val studentProfile = ownedViewModel { StudentProfileViewModel(repository, accounts) }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                val accountState by auth.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(accountState.accounts) { if (accountState.accounts.isNotEmpty()) repository.refresh() }
                val login: (String) -> Unit = { base ->
                    UnifiedSsoLoginController(this).launch(base, onResult = { sessions ->
                        lifecycleScope.launch {
                            try {
                                accounts.saveSessions(sessions)
                                repository.refresh()
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Unable to save the session. Please try again.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() })
                }
                UitShell(repository, dashboard, schedule, courses, inbox, academic, studentProfile) { onProfile ->
                    AccountsScreen(auth, login, dark, dynamic, {
                        dark = it
                        preferences.edit().putBoolean("dark", it).apply()
                    }, {
                        dynamic = it
                        preferences.edit().putBoolean("dynamic", it).apply()
                    }, onProfile = onProfile)
                }
            }
        }
    }
}

@Composable private inline fun <reified T : ViewModel> ownedViewModel(crossinline create: () -> T): T = viewModel(
    factory = object : ViewModelProvider.Factory {
        override fun <V : ViewModel> create(modelClass: Class<V>): V {
            @Suppress("UNCHECKED_CAST")
            return create() as V
        }
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UitShell(
    repository: PortalRepository,
    dashboard: DashboardViewModel,
    schedule: ScheduleViewModel,
    courses: CoursesViewModel,
    inbox: InboxViewModel,
    academic: AcademicViewModel,
    studentProfile: StudentProfileViewModel,
    accounts: @Composable ((vn.edu.uit.mobile.model.Account) -> Unit) -> Unit
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var profileAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    fun openProfile(account: vn.edu.uit.mobile.model.Account? = null) {
        showAccounts = false
        showProfile = true
        profileAccountId = account?.id
        studentProfile.onIntent(StudentProfileIntent.Open(account?.id))
    }
    fun closeProfile() {
        showProfile = false
        profileAccountId = null
        studentProfile.onIntent(StudentProfileIntent.Close)
    }
    LaunchedEffect(Unit) { if (showProfile) studentProfile.onIntent(StudentProfileIntent.Open(profileAccountId)) }
    val accountContent: @Composable () -> Unit = { accounts { openProfile(it) } }
    val sync by repository.syncState.collectAsStateWithLifecycle(SyncState())
    val snapshot by repository.snapshot.collectAsStateWithLifecycle(vn.edu.uit.mobile.model.PortalSnapshot())
    val now = kotlinx.datetime.Clock.System.now().epochSeconds
    WorkspaceFrame(
        selected, {
            selected = it
            closeProfile()
        }, { openProfile() }, snapshot.profile.fullName,
        connected = snapshot.updatedAt > 0, syncing = sync.syncing, lastSync = snapshot.updatedAt,
        deadlineCount = snapshot.events.count { it.deadline && it.needsAction && it.start in now..(now + 7 * 86400) },
        errorMessage = sync.error?.text()
    ) {
        if (showProfile) {
            StudentProfileScreen(studentProfile, onBack = { closeProfile() }, onManageAccounts = { showAccounts = true })
        } else {
            when (selected) {
                0 -> DashboardScreen(dashboard, onCourse = {
                    courses.onIntent(CoursesIntent.Select(it))
                    selected = 2
                }, onSchedule = { selected = 1 }, onInbox = { selected = 3 }, onConnect = { showAccounts = true })
                1 -> ScheduleScreen(schedule) {
                    courses.onIntent(CoursesIntent.Select(it))
                    selected = 2
                }
                2 -> CoursesScreen(courses)
                3 -> InboxScreen(inbox)
                else -> AcademicScreen(academic, accountContent)
            }
        }
    }
    if (showAccounts) {
        ModalBottomSheet(shape = TechnicalShapes.Dialog, onDismissRequest = { showAccounts = false }) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionHeading("Accounts & appearance", "Your connected portals and workspace preferences")
                accountContent()
            }
        }
    }
}
