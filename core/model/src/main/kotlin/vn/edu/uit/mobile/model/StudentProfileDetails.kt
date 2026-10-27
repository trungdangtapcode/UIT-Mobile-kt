package vn.edu.uit.mobile.model

/** Detailed personal information is displayed in memory, never added to Room snapshots. */
data class ProfileField(val label: String, val value: String)
data class ProfileGroup(val title: String, val fields: List<ProfileField>)
data class ProfileSection(val id: String, val title: String, val groups: List<ProfileGroup>, val failure: ServiceFailureKind? = null)
data class StudentProfileDetails(
    val accountId: String = "",
    val studentCode: String,
    val fullName: String,
    val faculty: String,
    val major: String,
    val className: String,
    val enrollmentStatus: String,
    val sections: List<ProfileSection>,
    val portrait: ByteArray? = null,
    val fetchedAt: Long = 0
)
