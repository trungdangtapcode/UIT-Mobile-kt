package vn.edu.uit.mobile.daa

import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

/** Only the researched reads are exposed. This client has no application or payment actions. */
class StudentServicesClient(private val transport: PortalTransport) {
    init {
        require(transport.session.account.portal == PortalType.UIT_PORTAL && transport.session.account.baseUrl == "https://portal.uit.edu.vn")
    }

    suspend fun tuition(): TuitionSummary = StudentServicesParser.tuition(
        parseJson(
            transport.request("/api/sv/tuition", HttpMethod.Post) {
                contentType(ContentType.Application.Json)
                setBody(obj("tuition_field_list" to TUITION_FIELDS, "detail_field_list" to DETAIL_FIELDS).toString())
            }
        )
    )

    suspend fun procedure(kind: ProcedureKind): ProcedureSection = StudentServicesParser.procedure(kind, read(PROCEDURE_PATHS.getValue(kind)))
    suspend fun graduation(): GraduationSummary = StudentServicesParser.graduation(read("/api/sinh-vien/tot-nghiep"))
    private suspend fun read(path: String): JsonElement = parseJson(transport.request(path))

    companion object {
        private val TUITION_FIELDS = listOf("id", "semester", "year_id", "tuition_amount", "tuition_semester", "tuition_renewal", "reduction_amount", "tuition_credit_number", "must_be_paid", "paid", "remaining", "debt_in_advance", "payment_status", "late_payment_date", "period_1_due_date", "period_2_due_date", "renewal_date", "tuition_status", "paid_time", "note")
        private val DETAIL_FIELDS = listOf("id", "subject_id", "subject_code", "subject_name", "tuition_credit_number", "unit_price", "additional_tuition", "amount", "repayment", "improve", "note")
        private val PROCEDURE_PATHS = mapOf(
            ProcedureKind.TUITION_EXTENSION to "/api/sinh-vien/gia-han-hoc-phi",
            ProcedureKind.CERTIFICATES to "/api/sinh-vien/giay-xac-nhan",
            ProcedureKind.TRANSCRIPTS to "/api/sinh-vien/xin-bang-diem",
            ProcedureKind.APPEALS to "/api/sinh-vien/phuc-khao",
            ProcedureKind.EXAMS to "/api/sinh-vien/hoan-thi",
            ProcedureKind.CREDENTIALS to "/api/sinh-vien/xac-nhan-chung-chi",
            ProcedureKind.LEAVE to "/api/sinh-vien/thoi-hoc-bao-luu"
        )
    }
}

object StudentServicesParser {
    private fun checked(data: JsonElement): JsonObject {
        val objectData = data as? JsonObject ?: throw InvalidResponseException("Student service returned an unexpected response")
        if (data.field("error") !in listOf(JsonNull, JsonPrimitive(false), JsonPrimitive(""))) throw InvalidResponseException("Student service reported an error")
        return objectData
    }
    private fun JsonElement.array(key: String): JsonArray = field(key) as? JsonArray ?: throw InvalidResponseException("Student service collection is missing")
    private fun JsonElement.text(vararg keys: String): String = keys.firstNotNullOfOrNull { str(it).takeIf(String::isNotBlank) }.orEmpty()
    private fun JsonElement.boolean(key: String): Boolean? = (field(key) as? JsonPrimitive)?.booleanOrNull
    private fun decimal(value: JsonElement): String? {
        if (value == JsonNull) return null
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: throw InvalidResponseException("Student service amount is invalid")
        if (raw.isBlank()) return null
        return raw.toBigDecimalOrNull()?.takeIf { it.scale() <= 6 && it.precision() <= 30 }?.toPlainString() ?: throw InvalidResponseException("Student service amount is invalid")
    }

    fun tuition(data: JsonElement): TuitionSummary = TuitionSummary(
        checked(data).array("records").map { row ->
            val id = row.str("id").takeIf(String::isNotBlank) ?: throw InvalidResponseException("Tuition record identity is missing")
            TuitionTerm(
                id, row.text("period", "semester_label"), row.str("year_id_name"), decimal(row.field("must_be_paid")), decimal(row.field("paid")), decimal(row.field("remaining")), decimal(row.field("debt_in_advance")),
                row.str("period_1_due_date"), row.str("period_2_due_date"), row.str("renewal_date"), row.text("payment_status_label", "payment_status"), row.str("note"),
                row.array("detail_ids").map { TuitionCharge(it.str("subject_code"), it.text("subject_name", "subject_id_name"), decimal(it.field("tuition_credit_number")), decimal(it.field("amount"))) },
                row.array("payment_ids").map { TuitionPayment(decimal(it.field("amount")), it.str("payment_time"), it.str("bank_name"), it.str("status")) }
            )
        }
    )

    fun procedure(kind: ProcedureKind, data: JsonElement): ProcedureSection {
        checked(data)
        val records = when (kind) {
            ProcedureKind.CERTIFICATES -> {
                val history = data.field("history") as? JsonObject ?: throw InvalidResponseException("Certificate history is missing")
                val categories = mapOf("xnsv" to "Student status certificate", "vvnh" to "Bank loan certificate", "ggt" to "Introduction letter", "xntt" to "Achievement certificate", "drl" to "Conduct score certificate", "udgd" to "Educational benefit certificate", "gkhk" to "Other certificate")
                categories.flatMap { (key, title) -> history.array(key).map { record(it, title, key) } }
            }
            ProcedureKind.CREDENTIALS -> data.array("certs").map { row ->
                ProcedureRecord(row.str("id"), row.str("cert_type").ifBlank { kind.title }, row.str("status"), row.text("create_date", "ngaythi"), "")
            }
            ProcedureKind.EXAMS -> data.array("history").map { record(it, "Exam deferral", "deferral") } + data.array("reexamHistory").map { record(it, "Re-examination", "reexam") }
            else -> data.array("history").map { record(it, kind.title, kind.name) }
        }
        val period = if (kind == ProcedureKind.TUITION_EXTENSION) {
            data.field("periodStatus")
        } else if (kind == ProcedureKind.EXAMS) {
            data.field("eligible")
        } else {
            JsonNull
        }
        val options = if (kind == ProcedureKind.TRANSCRIPTS) data.array("parameters").map { ProcedureOption(it.str("display_name"), decimal(it.field("cost"))) } else emptyList()
        return ProcedureSection(kind, records, period.str("start_date"), period.str("end_date"), period.boolean("open") ?: period.boolean("is_open"), period.text("registerBlockReason", "title_register"), options)
    }

    private fun record(row: JsonElement, title: String, prefix: String): ProcedureRecord {
        if (row !is JsonObject || row.str("id").isBlank()) throw InvalidResponseException("Procedure record identity is missing")
        return ProcedureRecord("$prefix:${row.str("id")}", title, row.text("stage_name", "status", "stage_code", "state"), row.text("create_date", "date", "registration_date"), row.text("wrong_reason", "note", "reason"), row.text("regis_number", "name"))
    }

    fun graduation(data: JsonElement): GraduationSummary {
        checked(data)
        val details = data.field("data") as? JsonObject ?: throw InvalidResponseException("Graduation assessment is unavailable")
        val config = details.field("config")
        val gates = details.field("registerGates")
        return GraduationSummary(
            config.str("periodLabel"), config.str("startDate"), config.str("endDate"), details.str("registrationPeriodStatus"), details.boolean("canRegister"), details.boolean("alreadyRegistered"),
            details.str("registrationStatus"), details.array("conditions").map { GraduationCondition(it.str("id"), it.str("name"), it.str("status"), it.str("note")) },
            details.array("debts").map { GraduationDebt(it.str("label"), it.boolean("ok"), it.str("statusText"), it.str("hint")) },
            gates.boolean("docs"), gates.boolean("fee"), gates.boolean("credit"), gates.boolean("phone")
        )
    }
}
