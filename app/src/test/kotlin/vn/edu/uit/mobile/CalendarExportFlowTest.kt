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
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate(2026, 10, 26)
    private val snapshot = PortalSnapshot(events = listOf(CalendarEvent("synthetic:1", 1, "synthetic", null, "Database project", dateAt(day, LocalTime(23, 0)), deadline = true)))
    private val options = CalendarExportOptions(day, day)
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun selectedDocumentReceivesTheReviewedCalendarAndNoProviderWrite() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal)
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        val file = File(context.cacheDir, "synthetic-export.ics")
        try {
            assertEquals(plan.fileName, vm.prepareCalendarExport(plan))
            vm.completeCalendarExport(context, Uri.fromFile(file)).join()
            assertTrue(file.readText().contains("SUMMARY:Database project"))
            assertFalse(vm.uiState.value.calendarExportBusy)
            assertEquals(0, portal.submits)
            assertTrue(vm.uiState.value.calendarExportMessage!!.startsWith("Calendar file saved."))
        } finally {
            file.delete()
        }
    }

    @Test fun canceledPickerWritesNothingAndChangedSnapshotCannotExportTheOldPreview() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal)
        runCurrent()
        val plan = CalendarFileExport().plan(snapshot, options)
        vm.prepareCalendarExport(plan)
        vm.completeCalendarExport(context, null).join()
        assertFalse(vm.uiState.value.calendarExportBusy)
        assertTrue(vm.uiState.value.calendarExportMessage!!.startsWith("Export canceled."))
        vm.prepareCalendarExport(plan)
        portal.snapshot.value = PortalSnapshot()
        runCurrent()
        val file = File(context.cacheDir, "synthetic-stale-export.ics")
        vm.completeCalendarExport(context, Uri.fromFile(file)).join()
        assertFalse(file.exists())
        assertTrue(vm.uiState.value.calendarExportMessage!!.startsWith("Calendar file was not saved."))
        assertNull(vm.prepareCalendarExport(plan))
    }

    @Test fun refreshedOversizedDataCannotCrashTheSaveAction() = runTest(dispatcher) {
        val portal = FakePortalRepository().apply { this.snapshot.value = this@CalendarExportFlowTest.snapshot }
        val vm = ScheduleViewModel(portal)
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
        val vm = ScheduleViewModel(portal)
        runCurrent()
        val file = File(context.cacheDir, "synthetic-interrupted-export.ics")
        vm.completeCalendarExport(context, Uri.fromFile(file)).join()
        assertFalse(file.exists())
        assertFalse(vm.uiState.value.calendarExportBusy)
        vm.prepareCalendarExport(CalendarFileExport().plan(snapshot, options))
        vm.completeCalendarExport(context, Uri.parse("content://unavailable.synthetic/calendar.ics")).join()
        assertFalse(vm.uiState.value.calendarExportBusy)
        assertTrue(vm.uiState.value.calendarExportMessage!!.startsWith("Calendar file was not saved."))
    }
}
