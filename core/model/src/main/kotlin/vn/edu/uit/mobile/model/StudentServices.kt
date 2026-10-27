package vn.edu.uit.mobile.model

/** Decimal strings preserve financial precision; null means unavailable, never zero. */
data class TuitionCharge(val courseCode: String, val courseName: String, val credits: String?, val amount: String?)
data class TuitionPayment(val amount: String?, val paidAt: String, val bank: String, val status: String)
data class TuitionTerm(
    val id: String,
    val label: String,
    val academicYear: String,
    val payable: String?,
    val paid: String?,
    val remaining: String?,
    val priorDebt: String?,
    val dueDate: String,
    val secondDueDate: String,
    val extensionDate: String,
    val status: String,
    val note: String,
    val charges: List<TuitionCharge>,
    val payments: List<TuitionPayment>
)
data class TuitionSummary(val terms: List<TuitionTerm>, val fetchedAt: Long = 0)

enum class ProcedureKind(val title: String) {
    TUITION_EXTENSION("Tuition extensions"),
    CERTIFICATES("Student certificates"),
    TRANSCRIPTS("Official transcripts"),
    APPEALS("Grade appeals"),
    EXAMS("Exam deferral & re-examination"),
    CREDENTIALS("Certificate verification"),
    LEAVE("Leave & withdrawal")
}
data class ProcedureRecord(val id: String, val title: String, val status: String, val date: String, val note: String, val reference: String = "")
data class ProcedureOption(val label: String, val fee: String?)
data class ProcedureSection(
    val kind: ProcedureKind,
    val records: List<ProcedureRecord>,
    val windowStart: String = "",
    val windowEnd: String = "",
    val windowOpen: Boolean? = null,
    val explanation: String = "",
    val options: List<ProcedureOption> = emptyList(),
    val fetchedAt: Long = 0
)

data class GraduationCondition(val id: String, val name: String, val status: String, val note: String)
data class GraduationDebt(val name: String, val satisfied: Boolean?, val status: String, val hint: String)
data class GraduationSummary(
    val period: String,
    val windowStart: String,
    val windowEnd: String,
    val windowStatus: String,
    val canRegister: Boolean?,
    val alreadyRegistered: Boolean?,
    val registrationStatus: String,
    val conditions: List<GraduationCondition>,
    val debts: List<GraduationDebt>,
    val documentGate: Boolean?,
    val feeGate: Boolean?,
    val creditGate: Boolean?,
    val phoneGate: Boolean?,
    val fetchedAt: Long = 0
)
enum class ServiceFailureKind { SIGN_IN, NETWORK, INVALID_RESPONSE, UNAVAILABLE }
data class ServiceIssue(val key: String, val failure: ServiceFailureKind)
data class StudentServicesSnapshot(
    val tuition: TuitionSummary? = null,
    val procedures: List<ProcedureSection> = emptyList(),
    val graduation: GraduationSummary? = null,
    val issues: List<ServiceIssue> = emptyList()
)
