package vn.edu.uit.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject
import vn.edu.uit.mobile.academic.*
import vn.edu.uit.mobile.auth.*
import vn.edu.uit.mobile.authui.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.courses.*
import vn.edu.uit.mobile.dashboard.*
import vn.edu.uit.mobile.data.LocalKnowledgeRepository
import vn.edu.uit.mobile.data.LocalReadingRepository
import vn.edu.uit.mobile.data.OfflineDownloadWorker
import vn.edu.uit.mobile.data.StudyPrivateStorage
import vn.edu.uit.mobile.data.createForumRepository
import vn.edu.uit.mobile.data.createFreeTimeBlockStorage
import vn.edu.uit.mobile.data.createKnowledgeSourceLoader
import vn.edu.uit.mobile.data.createOfflineCourseSourceLoader
import vn.edu.uit.mobile.data.createOfflineDownloadStorage
import vn.edu.uit.mobile.data.createOfflineTutorCodeLoader
import vn.edu.uit.mobile.data.createReadingDocumentLoader
import vn.edu.uit.mobile.data.observeDownloadWifi
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.*
import vn.edu.uit.mobile.schedule.*
import vn.edu.uit.mobile.widgets.*

class MainActivity : ComponentActivity() {
    private var announcementRoute by mutableStateOf<String?>(null)
    private val assistantRepository: AssistantRepository by lazy { getKoin().getOrNull<AssistantRepository>() ?: vn.edu.uit.mobile.data.UitGraph.get(this).assistant }
    private val announcementRepository: AnnouncementRepository by lazy { getKoin().getOrNull<AnnouncementRepository>() ?: vn.edu.uit.mobile.data.createAnnouncementRepository(this) }
    private var widgetRoute by mutableStateOf<WidgetRoute?>(null)
    private val repository: PortalRepository by inject()
    private val accounts: AccountRepository by inject()
    private val bookmarkRepository: BookmarkRepository by inject()
    private val workloadRepository: WorkloadRepository by inject()
    private val revisionRepository: RevisionRepository by inject()
    private val practiceRepository: PracticeRepository by inject()
    private val knowledgeRepository: KnowledgeRepository by lazy { getKoin().getOrNull<KnowledgeRepository>() ?: LocalKnowledgeRepository(StudyPrivateStorage(this, "knowledge", "uit.knowledge.v1"), accounts) }
    private val forumRepository: ForumRepository by lazy { getKoin().getOrNull<ForumRepository>() ?: createForumRepository(this) }
    private val readingRepository: ReadingRepository by lazy { getKoin().getOrNull<ReadingRepository>() ?: LocalReadingRepository(StudyPrivateStorage(this, "reading", "uit.reading.v1"), accounts) }
    private val studyRepository: StudyAiRepository by lazy { getKoin().getOrNull<StudyAiRepository>() ?: vn.edu.uit.mobile.data.UitGraph.get(this).studyAi }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage(LanguagePreference.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetRoute = WidgetRoute.read(if (savedInstanceState == null) intent else BundleCompat.getParcelable(savedInstanceState, "pending_widget_route", Intent::class.java))
        announcementRoute = savedInstanceState?.getString("announcement_route") ?: intent.getStringExtra(vn.edu.uit.mobile.data.AnnouncementSyncWorker.EXTRA_ANNOUNCEMENT)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", true)) }
            var dynamic by rememberSaveable { mutableStateOf(preferences.getBoolean("dynamic", true)) }
            var showWidgets by rememberSaveable { mutableStateOf(false) }
            val language = LanguagePreference.read(this)
            CompositionLocalProvider(LocalChatGptConnector provides studyRepository.chatGpt) {
                UitTheme(dark, dynamic) {
                    SideEffect {
                        val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        bars.isAppearanceLightStatusBars = !dark
                        bars.isAppearanceLightNavigationBars = !dark
                    }
                    val dashboard = ownedViewModel { DashboardViewModel(repository) }
                    val schedule = ownedViewModel { ScheduleViewModel(repository, accounts, createFreeTimeBlockStorage(this)) }
                    val workload = ownedViewModel { WorkloadViewModel(workloadRepository, accounts, createFreeTimeBlockStorage(this), sync = repository.syncState) }
                    val revision = ownedViewModel { RevisionViewModel(revisionRepository, workload) }
                    val downloads = ownedViewModel { OfflineDownloadsViewModel(repository, accounts, createOfflineDownloadStorage(this), observeDownloadWifi(applicationContext)) { wifiOnly -> OfflineDownloadWorker.enqueue(applicationContext, wifiOnly) } }
                    val passages = ownedViewModel { PassageSearchViewModel(vn.edu.uit.mobile.data.createPassageSearchRepository(this, accounts, repository.snapshot, studyRepository), repository.snapshot, accounts, studyRepository) }
                    val search = ownedViewModel { WorkspaceSearchViewModel(repository, accounts) }
                    val bookmarks = ownedViewModel { BookmarksViewModel(bookmarkRepository, repository, accounts) }
                    val courses = ownedViewModel { CoursesViewModel(repository) }
                    val study = ownedViewModel { StudyViewModel(studyRepository, repository, createOfflineCourseSourceLoader(createOfflineDownloadStorage(this))) }
                    val practice = ownedViewModel { PracticeViewModel(studyRepository, practiceRepository, accounts, repository, createOfflineCourseSourceLoader(createOfflineDownloadStorage(this))) }
                    val tutor = ownedViewModel { CodeTutorViewModel(studyRepository, accounts, repository, createOfflineCourseSourceLoader(createOfflineDownloadStorage(this)), createOfflineTutorCodeLoader(createOfflineDownloadStorage(this))) }
                    val knowledge = ownedViewModel { KnowledgeViewModel(studyRepository, knowledgeRepository, accounts, repository, createKnowledgeSourceLoader(createOfflineDownloadStorage(this))) }
                    val reading = ownedViewModel { CourseReadingViewModel(readingRepository, accounts, repository, createOfflineDownloadStorage(this), createReadingDocumentLoader(createOfflineDownloadStorage(this)), nativeReadingPdfFactory(cacheDir)) }
                    val uma = ownedViewModel { UmaViewModel(vn.edu.uit.mobile.data.UitGraph.get(this).uma) }
                    val assistant = ownedViewModel { AssistantViewModel(assistantRepository, studyRepository, announcementRepository) }
                    val announcements = ownedViewModel { AnnouncementsViewModel(announcementRepository) }
                    val forum = ownedViewModel { ForumViewModel(forumRepository) }
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
                    UitShell(repository, dashboard, schedule, workload, revision, courses, search, passages, downloads, bookmarks, study, practice, tutor, knowledge, reading, forum, inbox, academic, studentProfile, announcements, assistant, uma, {
                        UmaSsoLoginController(this).launch(onResult = uma::connect, onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() })
                    }, announcementRoute, {
                        announcementRoute = null
                        intent.removeExtra(vn.edu.uit.mobile.data.AnnouncementSyncWorker.EXTRA_ANNOUNCEMENT)
                    }, { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, widgetRoute, {
                        showWidgets = false
                        widgetRoute = null
                        setIntent(
                            Intent(intent).apply {
                                data = null
                                action = Intent.ACTION_MAIN
                            }
                        )
                    }) { onProfile ->
                        AccountsScreen(
                            auth, login, dark, dynamic, {
                                dark = it
                                preferences.edit().putBoolean("dark", it).apply()
                                WidgetRuntime.requestRender(this)
                            }, {
                                dynamic = it
                                preferences.edit().putBoolean("dynamic", it).apply()
                                WidgetRuntime.requestRender(this)
                            }, onProfile = onProfile, onWidgets = { showWidgets = true },
                            language = language,
                            onLanguage = {
                                LanguagePreference.write(this, it)
                                WidgetRuntime.requestRender(this)
                                recreate()
                            }
                        )
                    }
                    if (showWidgets) HomeWidgetsDialog { showWidgets = false }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data?.scheme == "uitmobile-chatgpt") return
        setIntent(intent)
        announcementRoute = intent.getStringExtra(vn.edu.uit.mobile.data.AnnouncementSyncWorker.EXTRA_ANNOUNCEMENT)
        widgetRoute = WidgetRoute.read(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgetRoute?.let { outState.putParcelable("pending_widget_route", it.intent(this)) }
        outState.putString("announcement_route", announcementRoute)
        super.onSaveInstanceState(outState)
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
    workload: WorkloadViewModel,
    revision: RevisionViewModel,
    courses: CoursesViewModel,
    search: WorkspaceSearchViewModel,
    passages: PassageSearchViewModel,
    downloads: OfflineDownloadsViewModel,
    bookmarks: BookmarksViewModel,
    study: StudyViewModel,
    practice: PracticeViewModel,
    tutor: CodeTutorViewModel,
    knowledge: KnowledgeViewModel,
    reading: CourseReadingViewModel,
    forum: ForumViewModel,
    inbox: InboxViewModel,
    academic: AcademicViewModel,
    studentProfile: StudentProfileViewModel,
    announcements: AnnouncementsViewModel,
    assistant: AssistantViewModel,
    uma: UmaViewModel,
    onUmaLogin: () -> Unit,
    announcementRoute: String?,
    onAnnouncementConsumed: () -> Unit,
    onAnnouncementPermission: () -> Unit,
    widgetRoute: WidgetRoute?,
    onWidgetRouteConsumed: () -> Unit,
    accounts: @Composable ((vn.edu.uit.mobile.model.Account) -> Unit) -> Unit
) {
    var showAssistant by rememberSaveable { mutableStateOf(false) }
    var returnToAssistant by rememberSaveable { mutableStateOf(false) }
    var assistantSeed by rememberSaveable { mutableStateOf<String?>(null) }
    var showAnnouncements by rememberSaveable { mutableStateOf(false) }
    var announcementTarget by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(announcementRoute) {
        if (announcementRoute != null) {
            announcementTarget = announcementRoute
            showAssistant = false
            showAnnouncements = true
            onAnnouncementConsumed()
        }
    }
    val forumState by forum.uiState.collectAsStateWithLifecycle()
    val readingState by reading.uiState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var returnToSearch by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var bookmarksFromSearch by rememberSaveable { mutableStateOf(false) }
    var returnToBookmarks by rememberSaveable { mutableStateOf(false) }
    val bookmarkScreenState = rememberSaveableStateHolder()
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var profileAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    fun openProfile(account: vn.edu.uit.mobile.model.Account? = null) {
        val navigate = {
            returnToAssistant = false
            showAssistant = false
            assistant.stop()
            showAnnouncements = false
            showBookmarks = false
            returnToBookmarks = false
            returnToSearch = false
            search.close()
            showSearch = false
            showAccounts = false
            forum.close()
            showProfile = true
            profileAccountId = account?.id
            studentProfile.onIntent(StudentProfileIntent.Open(account?.id))
        }
        if (reading.uiState.value.open) reading.leave(navigate) else navigate()
    }
    fun closeProfile() {
        showProfile = false
        profileAccountId = null
        studentProfile.onIntent(StudentProfileIntent.Close)
    }
    LaunchedEffect(Unit) { if (showProfile) studentProfile.onIntent(StudentProfileIntent.Open(profileAccountId)) }
    LaunchedEffect(widgetRoute) {
        val route = widgetRoute ?: return@LaunchedEffect
        forum.close()
        reading.preempt()
        showBookmarks = false
        returnToBookmarks = false
        returnToSearch = false
        search.close()
        showSearch = false
        closeProfile()
        showAccounts = false
        when (route.destination) {
            "schedule" -> {
                selected = 1
                schedule.onIntent(ScheduleIntent.FocusDate(route.date?.let(kotlinx.datetime.LocalDate::parse) ?: today()))
            }
            "deadlines", "overdue" -> {
                selected = 3
                inbox.onIntent(InboxIntent.ShowDeadlines(route.destination == "overdue"))
            }
            "profile" -> openProfile()
        }
        onWidgetRouteConsumed()
    }
    val accountContent: @Composable () -> Unit = { accounts { openProfile(it) } }
    val sync by repository.syncState.collectAsStateWithLifecycle(SyncState())
    val snapshot by repository.snapshot.collectAsStateWithLifecycle(vn.edu.uit.mobile.model.PortalSnapshot())
    val now = kotlinx.datetime.Clock.System.now().epochSeconds
    WorkspaceFrame(
        selected, { destination ->
            val navigate = {
                returnToAssistant = false
                showAssistant = false
                assistant.stop()
                showAnnouncements = false
                forum.close()
                selected = destination
                showBookmarks = false
                returnToBookmarks = false
                returnToSearch = false
                search.close()
                showSearch = false
                closeProfile()
            }
            if (reading.uiState.value.open) reading.leave(navigate) else navigate()
        }, { openProfile() }, snapshot.profile.fullName,
        connected = snapshot.updatedAt > 0, syncing = sync.syncing, lastSync = snapshot.updatedAt,
        deadlineCount = snapshot.events.count { it.deadline && it.needsAction && it.start in now..(now + 7 * 86400) },
        errorMessage = sync.error?.text(),
        onSearch = {
            val navigate = {
                returnToAssistant = false
                showAssistant = false
                assistant.stop()
                showAnnouncements = false
                showBookmarks = false
                returnToBookmarks = false
                returnToSearch = false
                closeProfile()
                forum.close()
                showSearch = true
            }
            if (reading.uiState.value.open) reading.leave(navigate) else navigate()
        }
    ) {
        if (showAssistant) {
            key(assistantSeed) {
                val forumSeed = assistantSeed?.takeIf { it.startsWith("forum:") }?.split(':')?.let { parts ->
                    if (parts.size == 4) AssistantForumScope(parts[1].toLong(), parts[2].toInt(), parts[3].toInt()) else null
                }
                AssistantScreen(assistant, {
                    showAssistant = false
                    assistantSeed = null
                }, initialUrl = assistantSeed.takeIf { forumSeed == null }, initialForum = forumSeed, uma = uma, onUmaLogin = onUmaLogin) { url ->
                    showAssistant = false
                    assistantSeed = null
                    returnToAssistant = true
                    val target = ForumUrls.target(url)
                    if (target != null) {
                        showAnnouncements = false
                        forum.openPublicTopic(target.topicId, target.postNumber)
                    } else {
                        announcementTarget = url
                        showAnnouncements = true
                    }
                }
            }
        } else if (showAnnouncements) {
            key(announcementTarget) {
                AnnouncementsScreen(announcements, {
                    showAnnouncements = false
                    announcementTarget = null
                    if (returnToAssistant) {
                        returnToAssistant = false
                        showAssistant = true
                    }
                }, announcementTarget, onAnnouncementPermission, onAssistant = { url ->
                    assistantSeed = url
                    showAssistant = true
                })
            }
        } else if (readingState.open) {
            CourseReadingScreen(reading)
        } else if (forumState.open) {
            ForumScreen(
                forum,
                onAssistant = { scope ->
                    returnToAssistant = false
                    assistantSeed = "forum:${scope.topicId}:${scope.startNumber}:${scope.limit}"
                    showAssistant = true
                },
                onReturn = if (returnToAssistant) {
                    (
                        {
                            forum.close()
                            returnToAssistant = false
                            showAssistant = true
                        }
                        )
                } else {
                    null
                }
            )
        } else if (showBookmarks) {
            // Retain filters and list position while source detail replaces the collection.
            bookmarkScreenState.SaveableStateProvider("saved-items") {
                BookmarksScreen(bookmarks, {
                    showBookmarks = false
                    showSearch = bookmarksFromSearch
                }) { hit ->
                    search.back()
                    search.open(hit.key)
                    if (search.uiState.value.selected == hit.key) {
                        showBookmarks = false
                        showSearch = true
                        returnToBookmarks = true
                    } else {
                        bookmarks.problem(BookmarkProblem.SOURCE)
                    }
                }
            }
        } else if (showSearch) {
            WorkspaceSearchScreen(
                search,
                {
                    search.close()
                    showSearch = false
                },
                { course ->
                    courses.onIntent(CoursesIntent.Select(course))
                    selected = 2
                    showSearch = false
                    returnToSearch = true
                },
                bookmarks,
                {
                    showSearch = false
                    showBookmarks = true
                    bookmarksFromSearch = true
                },
                if (returnToBookmarks) {
                    (
                        {
                            search.back()
                            showSearch = false
                            showBookmarks = true
                            returnToBookmarks = false
                        }
                        )
                } else {
                    null
                },
                onOpenPdf = reading::open,
                passages = passages,
                onOpenPassagePdf = reading::openExpected
            )
        } else if (showProfile) {
            StudentProfileScreen(studentProfile, onBack = { closeProfile() }, onManageAccounts = { showAccounts = true })
        } else {
            when (selected) {
                0 -> DashboardScreen(dashboard, onCourse = {
                    courses.onIntent(CoursesIntent.Select(it))
                    selected = 2
                }, onSchedule = { selected = 1 }, onInbox = { selected = 3 }, onConnect = { showAccounts = true }, onAnnouncements = {
                    announcementTarget = null
                    showAnnouncements = true
                }, onAssistant = {
                    assistantSeed = null
                    showAssistant = true
                })
                1 -> ScheduleScreen(schedule, workload, revision) {
                    courses.onIntent(CoursesIntent.Select(it))
                    selected = 2
                }
                2 -> CoursesScreen(
                    courses,
                    study,
                    if (returnToSearch) {
                        (
                            {
                                courses.onIntent(CoursesIntent.Back)
                                returnToSearch = false
                                showSearch = true
                            }
                            )
                    } else {
                        null
                    },
                    downloads,
                    bookmarks,
                    {
                        showBookmarks = true
                        bookmarksFromSearch = false
                        returnToBookmarks = false
                    },
                    practice = practice,
                    tutor = tutor,
                    knowledge = knowledge,
                    onReading = { reading.library() },
                    onOpenPdf = reading::open
                )
                3 -> InboxScreen(inbox, onForum = { forum.open() }, onAnnouncements = {
                    announcementTarget = null
                    showAnnouncements = true
                })
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
