package vn.edu.uit.mobile

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.schedule.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarExportFlowTest {
    private class Accounts : AccountRepository {
        override val accounts = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Account>())
        override suspend fun remove(accountId: String) = Unit
        override suspend fun saveSessions(sessions: List<PortalSession>) = Unit
    }
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate(2026, 10, 26)
    private val snapshot = PortalSnapshot(events = listOf(CalendarEvent("synthetic:1", 1, "synthetic", null, "Database project", dateAt(day, LocalTime(23, 0)), deadline = true)))
    private val options = CalendarExportOptions(day, day)
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        androidx.core.content.FileProvider().attachInfo(context, context.packageManager.resolveContentProvider("${context.packageName}.files", android.content.pm.PackageManager.GET_META_DATA)!!)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun selectedDocumentReceivesTheReviewedCalendarAndNoProviderWrite() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        val file = File(context.cacheDir, "synthetic-export.ics")
        try {
            assertEquals(plan.fileName, vm.prepareCalendarExport(plan))
            vm.completeCalendarExport(context, Uri.fromFile(file)).join()
            assertTrue(file.readText().contains("SUMMARY:Database project"))
            assertFalse(vm.uiState.value.calendarExportBusy)
            assertEquals(0, portal.submits)
            assertEquals(CalendarExportMessage.SAVED, vm.uiState.value.calendarExportMessage)
        } finally {
            file.delete()
        }
    }

    @Test fun canceledPickerWritesNothingAndChangedSnapshotCannotExportTheOldPreview() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        vm.prepareCalendarExport(plan)
        vm.completeCalendarExport(context, null).join()
        assertFalse(vm.uiState.value.calendarExportBusy)
        assertEquals(CalendarExportMessage.CANCELED, vm.uiState.value.calendarExportMessage)
        vm.prepareCalendarExport(plan)
        portal.snapshot.value = PortalSnapshot()
        runCurrent()
        val file = File(context.cacheDir, "synthetic-stale-export.ics")
        vm.completeCalendarExport(context, Uri.fromFile(file)).join()
        assertFalse(file.exists())
        assertEquals(CalendarExportMessage.SAVE_FAILED, vm.uiState.value.calendarExportMessage)
        assertNull(vm.prepareCalendarExport(plan))
    }

    @Test fun refreshedOversizedDataCannotCrashTheSaveAction() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        portal.snapshot.value = snapshot.copy(events = (1..2001).map { snapshot.events.single().copy(key = "synthetic:$it") })
        runCurrent()
        assertNull(vm.prepareCalendarExport(plan))
        assertFalse(vm.uiState.value.calendarExportBusy)
        assertNotNull(vm.uiState.value.calendarExportMessage)
    }

    @Test fun recreationWithoutAPendingExportAndUnavailableDestinationsShowAnExplicitError() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val file = File(context.cacheDir, "synthetic-interrupted-export.ics")
        vm.completeCalendarExport(context, Uri.fromFile(file)).join()
        assertFalse(file.exists())
        assertFalse(vm.uiState.value.calendarExportBusy)
        vm.prepareCalendarExport(CalendarFileExport().plan(snapshot, options))
        vm.completeCalendarExport(context, Uri.parse("content://unavailable.synthetic/calendar.ics")).join()
        assertFalse(vm.uiState.value.calendarExportBusy)
        assertEquals(CalendarExportMessage.SAVE_FAILED, vm.uiState.value.calendarExportMessage)
    }

    @Test fun calendarInsertIntentPopulatesCalendarContractExtrasWithoutProviderPermissions() {
        val plan = CalendarFileExport().plan(snapshot, options)
        val intent = calendarInsertIntent(plan.entries.single(), 1793386800L)
        assertEquals(android.content.Intent.ACTION_INSERT, intent.action)
        assertEquals(android.provider.CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Database project", intent.getStringExtra(android.provider.CalendarContract.Events.TITLE))
        assertEquals(plan.entries.single().start * 1000L, intent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L))
        assertEquals(android.provider.CalendarContract.Events.AVAILABILITY_FREE, intent.getIntExtra(android.provider.CalendarContract.Events.AVAILABILITY, -1))
    }

    @Test fun accountChangeInvalidatesPendingFileEvenWhenScheduleEntriesAreIdentical() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val accounts = Accounts()
        val vm = ScheduleViewModel(portal, accounts)
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        val previousSession = vm.uiState.value.calendarSession
        vm.prepareCalendarExport(plan)
        accounts.accounts.value = listOf(Account("different", "https://example.invalid", 2, "Fixture", PortalType.MOODLE))
        runCurrent()
        val file = File(context.cacheDir, "synthetic-changed-account.ics")
        try {
            vm.completeCalendarExport(context, Uri.fromFile(file)).join()
            assertFalse(file.exists())
            assertEquals(CalendarExportMessage.SAVE_FAILED, vm.uiState.value.calendarExportMessage)
            assertNull(vm.prepareCalendarExport(plan, previousSession))
            vm.shareCalendar(context, plan, previousSession) { fail("Stale account callback opened Sharesheet") }
            assertEquals(CalendarExportMessage.REVIEW, vm.uiState.value.calendarExportMessage)
        } finally {
            file.delete()
        }
    }

    @Test fun shareRejectsStalePreviewAndDeletesCopyWhenChooserCannotOpen() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        val folder = File(context.cacheDir, "calendar-shares")
        try {
            var launched = false
            portal.snapshot.value = PortalSnapshot()
            runCurrent()
            vm.shareCalendar(context, plan) { launched = true }
            assertFalse(launched)
            assertTrue(folder.listFiles().isNullOrEmpty())
            portal.snapshot.value = snapshot
            runCurrent()
            vm.shareCalendar(context, plan) {
                launched = true
                throw android.content.ActivityNotFoundException()
            }
            assertTrue(launched)
            assertTrue(folder.listFiles().isNullOrEmpty())
            assertFalse(vm.uiState.value.calendarExportBusy)
            assertEquals(CalendarExportMessage.SHARE_FAILED, vm.uiState.value.calendarExportMessage)
            assertEquals(0, portal.submits)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun selectedSubsetIsAcceptedAndExcludedEventsNeverReachSavedFile() = runTest(dispatcher) {
        val second = snapshot.events.single().copy(key = "synthetic:2", name = "Excluded event")
        val source = snapshot.copy(events = snapshot.events + second)
        val portal = FakePortalRepository().apply { this.snapshot.value = source }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val all = CalendarFileExport().plan(source, options)
        val selected = CalendarFileExport().plan(source, options.copy(excludedKeys = setOf(all.entries.last().key)))
        val file = File(context.cacheDir, "synthetic-subset.ics")
        try {
            assertNotNull(vm.prepareCalendarExport(selected))
            vm.completeCalendarExport(context, Uri.fromFile(file)).join()
            assertTrue(file.readText().contains("SUMMARY:Database project"))
            assertFalse(file.readText().contains("Excluded event"))
        } finally {
            file.delete()
        }
    }

    @Test fun shareLaunchesOnceWithoutClaimingDeliveryAndCancellationCleansUp() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal, Accounts())
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        val folder = File(context.cacheDir, "calendar-shares")
        try {
            var send: android.content.Intent? = null
            vm.shareCalendar(context, plan) { send = it }
            assertNotNull(send)
            assertEquals(CalendarExportMessage.SHARE_OPENED, vm.uiState.value.calendarExportMessage)
            assertFalse(vm.uiState.value.calendarExportBusy)
            assertEquals(1, folder.listFiles()!!.size)
            folder.deleteRecursively()
            try {
                vm.shareCalendar(context, plan) { throw CancellationException("Synthetic lifecycle cancellation") }
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            assertTrue(folder.listFiles().isNullOrEmpty())
            assertFalse(vm.uiState.value.calendarExportBusy)
            assertEquals(0, portal.submits)
        } finally {
            folder.deleteRecursively()
        }
    }
}
