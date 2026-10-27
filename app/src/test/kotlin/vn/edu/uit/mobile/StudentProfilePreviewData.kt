package vn.edu.uit.mobile

import vn.edu.uit.mobile.authui.StudentProfileState
import vn.edu.uit.mobile.model.*

internal object StudentProfilePreviewData {
    val account = Account("profile-test", "https://portal.uit.edu.vn", 1, "Alex Morgan", PortalType.UIT_PORTAL)
    val details = StudentProfileDetails(
        account.id, "TEST-2026001", "Alex Morgan", "Faculty of Computer Science", "Software Engineering", "SE2026", "Currently enrolled",
        listOf(
            ProfileSection(
                "personal",
                "Personal",
                listOf(
                    ProfileGroup("Personal information", listOf(ProfileField("Full name", "Alex Morgan"), ProfileField("Gender", "Not provided"), ProfileField("Date of birth", "2005-01-01"), ProfileField("Nationality", "Vietnamese"))),
                    ProfileGroup("Contact", listOf(ProfileField("University email", "student@example.invalid"), ProfileField("Phone", "TEST-PHONE"))),
                    ProfileGroup("Addresses", listOf(ProfileField("Permanent address", "Example campus address used only for UI testing")))
                )
            ),
            ProfileSection("family", "Family", listOf(ProfileGroup("Emergency contact", listOf(ProfileField("Full name", "Test Contact"), ProfileField("Relationship", "Parent"), ProfileField("Phone", "TEST-CONTACT-PHONE"))))),
            ProfileSection("academic", "Academic", listOf(ProfileGroup("Enrollment and study", listOf(ProfileField("Student ID", "TEST-2026001"), ProfileField("Class", "SE2026"), ProfileField("Training program", "Standard program"), ProfileField("Credits earned", "96"), ProfileField("Cumulative GPA", "8.1"))))),
            ProfileSection("health", "Health", listOf(ProfileGroup("Health information", listOf(ProfileField("Blood type", "Not provided"), ProfileField("Registered healthcare facility", "Example clinic"))))),
            ProfileSection("images", "Documents", listOf(ProfileGroup("Transcript", listOf(ProfileField("On file", "Yes"), ProfileField("Required", "Yes"))))),
            ProfileSection("insurance", "Insurance", listOf(ProfileGroup("Health insurance", listOf(ProfileField("Insurance number", "TEST-INSURANCE"), ProfileField("Valid until", "2026-12-31"), ProfileField("Healthcare facility", "Example clinic"))))),
            ProfileSection("rewards", "Awards", emptyList())
        ),
        fetchedAt = WorkspacePreviewData.now
    )
    val state = StudentProfileState(account, details)
}
