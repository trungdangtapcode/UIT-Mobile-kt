package vn.edu.uit.mobile

import vn.edu.uit.mobile.model.*

/** Synthetic records only; never replace these with production student data. */
internal object StudentServicesPreviewData {
    private val now = WorkspacePreviewData.now
    private val term = TuitionTerm(
        "term-1", "Semester 1 · 2026–2027", "2026–2027", "12500000", "10000000", "2500000", "0",
        "2026-11-15", "", "", "Partially paid", "", listOf(TuitionCharge("IT001", "Programming fundamentals", "4", "3200000")),
        listOf(TuitionPayment("10000000", "2026-10-15", "Example bank", "Recorded"))
    )
    val services = StudentServicesSnapshot(
        tuition = TuitionSummary(listOf(term, term.copy(id = "term-2", label = "Semester 2 · 2025–2026", academicYear = "2025–2026", remaining = "0", status = "Paid")), now),
        procedures = listOf(
            ProcedureSection(ProcedureKind.TUITION_EXTENSION, emptyList(), "2026-10-20", "2026-10-31", false, "The current application window has closed.", fetchedAt = now),
            ProcedureSection(ProcedureKind.CERTIFICATES, listOf(ProcedureRecord("cert-1", "Student status certificate", "Ready for collection", "2026-10-18", "Bring your student card.", "TEST-CERT-001")), fetchedAt = now),
            ProcedureSection(ProcedureKind.TRANSCRIPTS, listOf(ProcedureRecord("transcript-1", "Official transcript", "Processing", "2026-10-20", "", "TEST-TRANSCRIPT-001")), options = listOf(ProcedureOption("Official semester transcript · English", "30000")), fetchedAt = now)
        ) + listOf(ProcedureKind.APPEALS, ProcedureKind.EXAMS, ProcedureKind.CREDENTIALS, ProcedureKind.LEAVE).map { ProcedureSection(it, emptyList(), fetchedAt = now) },
        graduation = GraduationSummary(
            "Assessment 4 · 2026", "2026-10-12", "2026-10-23", "Closed", false, false, "",
            listOf(GraduationCondition("credits", "Complete the degree credit requirement", "In progress", "Some required courses are still in progress."), GraduationCondition("language", "Foreign language requirement", "Confirmed", "")),
            listOf(GraduationDebt("Library clearance", true, "Cleared", ""), GraduationDebt("Tuition clearance", false, "Needs attention", "Check the tuition record for the current term.")),
            true, false, false, true, now
        )
    )
    val snapshot = WorkspacePreviewData.snapshot.copy(studentServices = services, portalLinked = true)
}
