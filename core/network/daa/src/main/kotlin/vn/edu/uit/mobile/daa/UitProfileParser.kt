package vn.edu.uit.mobile.daa

import kotlinx.serialization.json.*
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

/** Only explicit display fields are retained; update actions and attachment URLs are excluded. */
object UitProfileParser {
    fun parse(body: String): StudentProfileDetails {
        val data = profile(body)
        if (data.str("studentCode").isBlank() || data.str("fullName").isBlank()) throw InvalidResponseException("Student profile identity is missing")
        val academic = data.field("academic")
        val personal = data.field("personal")
        val visible = (data["visibleTabs"] as? JsonArray)?.map { it.str() }?.takeIf { it.isNotEmpty() }?.toSet()
        val sections = buildList {
            fun section(id: String, title: String, groups: List<ProfileGroup>) {
                if (visible == null || id in visible) add(ProfileSection(id, title, groups))
            }
            section(
                "personal",
                "Personal",
                listOf(
                    group("Personal information", personal, "full_name" to "Full name", "gender" to "Gender", "date_of_birth" to "Date of birth", "place_of_birth" to "Place of birth", "nationality" to "Nationality", "ethnicity" to "Ethnicity", "religion" to "Religion", "marital_status" to "Marital status"),
                    group("Contact", personal, "school_email" to "University email", "personal_email" to "Personal email", "phone" to "Phone", "fb_address" to "Social profile"),
                    group("Addresses", personal, "permanent_address" to "Permanent address", "current_address" to "Current address", "foreign_permanent_address" to "Overseas address"),
                    address("Permanent address · current administration", personal.field("permanent_address_new")),
                    address("Current address · current administration", personal.field("present_address_new")),
                    group("Identity documents", personal, "id_card_number" to "National ID number", "id_card_issue_date" to "ID issue date", "id_card_issue_place" to "ID issuing authority", "passport_number" to "Passport number", "passport_date" to "Passport issue date", "passport_place" to "Passport issuing authority"),
                    group("Bank information", data.field("bank"), "bank_name" to "Bank", "account_number" to "Account number", "branch" to "Branch"),
                    group("Membership", data.field("membership"), "member_status" to "Youth Union member", "member_date" to "Youth Union admission date", "party_member_status" to "Party member", "party_member_date" to "Party admission date", "highest_position" to "Highest position", "education_and_work_history" to "Education and work history", "achievements_and_awards" to "Achievements and awards"),
                    group("Background", data.field("background"), "priority_household_type" to "Priority household category", "detailed_object" to "Category details", "background" to "Background", "region" to "Region", "student_area" to "Admission area", "policy" to "Policy category"),
                    group("Interests", data.field("hobby"), "football" to "Football", "volleyball" to "Volleyball", "basketball" to "Basketball", "badminton" to "Badminton", "table_tennis" to "Table tennis", "chess" to "Chess", "chinese_chess" to "Chinese chess", "gym_yoga" to "Gym / yoga", "other_sport" to "Other sports", "sports_achievements" to "Sport achievements", "likes_music" to "Music", "plays_instrument" to "Plays an instrument", "instrument_name" to "Instrument", "music_achievements" to "Music achievements", "likes_painting" to "Painting", "likes_literature" to "Literature")
                )
            )
            val family = data.field("family")
            section(
                "family",
                "Family",
                buildList {
                    listOf("father" to "Father", "mother" to "Mother", "partner" to "Partner").forEach { (key, title) ->
                        val member = family.field(key)
                        if (member is JsonObject) add(group(title, member, "full_name" to "Full name", "date_of_birth" to "Date of birth", "occupation" to "Occupation", "phone" to "Phone", "email" to "Email", "nationality" to "Nationality", "ethnicity" to "Ethnicity", "religion" to "Religion", "permanent_address" to "Address", "is_deceased_or_unknown" to "Deceased or unknown"))
                    }
                    family.list("notice_contacts").forEachIndexed { index, contact -> add(group("Contact ${index + 1}", contact, "full_name" to "Full name", "relationship" to "Relationship", "phone" to "Phone", "address" to "Address", "is_primary" to "Primary contact")) }
                    add(group("Other family information", family, "siblings_information" to "Siblings"))
                }
            )
            section(
                "academic",
                "Academic",
                listOf(
                    group(
                        "Enrollment and study", academic,
                        "student_code" to "Student ID", "cohort_label" to "Cohort", "year_of_admission" to "Admission year", "faculty_name" to "Faculty", "major_name" to "Major", "major_code" to "Major code", "specialization" to "Specialization", "training_program" to "Training program", "type_of_training" to "Training type", "class_name" to "Class", "enrollment_status" to "Enrollment status", "current_gpa" to "Cumulative GPA", "passed_credits" to "Credits earned", "total_program_credits" to "Program credits", "learned_year" to "Study year", "expected_graduation_year" to "Expected graduation year", "advisor_name" to "Academic adviser", "advisor_email" to "Adviser email", "tuition_debt" to "Reported tuition debt", "dual_major" to "Dual major", "dual_major_student" to "Dual major student record", "high_school_name" to "High school", "high_school_location" to "High school location"
                    )
                )
            )
            section("health", "Health", listOf(group("Health information", data.field("health"), "height_cm" to "Height (cm)", "weight_kg" to "Weight (kg)", "blood_type" to "Blood type", "blood_pressure" to "Blood pressure", "vision" to "Vision", "family_medical_history" to "Family medical history", "personal_medical_history" to "Personal medical history", "neurological_disease" to "Neurological condition", "heart_disease" to "Heart condition", "digestive_disease" to "Digestive condition", "surgical_intervention" to "Surgical history", "vaccinated" to "Vaccinated", "health_note" to "Health notes", "registered_healthcare_facility" to "Registered healthcare facility", "physical_health" to "Physical health", "mental_health" to "Mental health", "general_health_status" to "General health status", "mental_health_status" to "Mental health status")))
            section(
                "images",
                "Documents",
                data.list("imageProfile").mapIndexed { index, image ->
                    val document = document(image, index)
                    ProfileGroup(document.title, listOf(ProfileField("On file", if (document.available) "Yes" else "No"), ProfileField("Required", display(image.field("required"))), ProfileField("Uploaded", display(image.field("uploadedAt")))), document)
                }
            )
            section("insurance", "Insurance", emptyList())
            section("rewards", "Awards", data.list("rewards").map { reward -> group("Award", reward, "name" to "Name", "title" to "Title", "date" to "Date", "note" to "Notes") })
        }
        return StudentProfileDetails(studentCode = data.str("studentCode"), fullName = data.str("fullName"), faculty = academic.str("faculty_name"), major = academic.str("major_name"), className = academic.str("class_name"), enrollmentStatus = academic.str("enrollment_status"), sections = sections)
    }

    internal fun scanSource(body: String, documentId: String): Pair<ProfileDocument, String> {
        val data = profile(body)
        val visible = data["visibleTabs"] as? JsonArray
        if (!visible.isNullOrEmpty() && visible.none { it.str() == "images" }) throw InvalidResponseException("Profile scans are not available")
        val image = data.list("imageProfile").mapIndexed { index, item -> document(item, index) to item }.singleOrNull { it.first.id == documentId }
            ?: throw InvalidResponseException("This profile document is no longer available")
        if (!image.first.available) throw InvalidResponseException("No scan has been provided for this document")
        return image.first to image.second.str("imageUrl")
    }

    private fun profile(body: String): JsonObject = UitPortalParser.flightObjects(body).firstOrNull { "studentCode" in it && "academic" in it && it["academic"] is JsonObject }
        ?: throw InvalidResponseException("UIT Portal profile data is missing")
    private fun document(image: JsonElement, index: Int): ProfileDocument = ProfileDocument(image.str("type").ifBlank { "document-$index" }, image.str("label").ifBlank { "Profile document" }, image.str("imageUrl").isNotBlank())

    fun insurance(data: JsonElement): ProfileSection {
        if (data !is JsonObject || data["cards"] !is JsonObject || data["profile"] !is JsonObject || data["history"] !is JsonArray) throw InvalidResponseException("Student insurance data is missing")
        if (data.field("error") != JsonNull) throw InvalidResponseException("Student insurance is unavailable")
        val groups = buildList {
            listOf("bhyt" to "Health insurance", "bhtn" to "Accident insurance").forEach { (key, label) ->
                val current = data.field("cards").field(key).field("current")
                if (current is JsonObject) {
                    add(group(label, current, "insurance_code" to "Insurance number", "place_medical_name" to "Healthcare facility", "start_date" to "Valid from", "end_date" to "Valid until", "is_valid" to "Currently valid", "year_name" to "Insurance year"))
                } else if (key == "bhyt") {
                    add(group(label, data.field("profile"), "insurance_code" to "Reported insurance number", "insurance_period" to "Reported coverage period", "insurance_type" to "Reported insurance type"))
                }
            }
            data.list("history").forEachIndexed { index, row -> add(group("Insurance record ${index + 1}", row, "kind_label" to "Insurance type", "period_label" to "Period", "registered_at" to "Registered", "insurance_code" to "Insurance number", "place_medical_name" to "Healthcare facility", "start_date" to "Valid from", "end_date" to "Valid until", "amount" to "Amount (VND)", "status_label" to "Status")) }
        }
        return ProfileSection("insurance", "Insurance", groups)
    }

    private fun group(title: String, source: JsonElement, vararg fields: Pair<String, String>): ProfileGroup = ProfileGroup(title, fields.map { (key, label) -> ProfileField(label, display(source.field(key))) })
    private fun address(title: String, source: JsonElement): ProfileGroup = group(title, source, "street" to "Street", "wardName" to "Ward", "districtName" to "District", "stateName" to "Province / city", "countryName" to "Country")
    private fun display(value: JsonElement): String {
        val primitive = value as? JsonPrimitive ?: return "Not provided"
        if (primitive == JsonNull) return "Not provided"
        return when (primitive.booleanOrNull) {
            true -> "Yes"
            false -> "No"
            null -> primitive.content.trim().ifBlank { "Not provided" }
        }
    }
}
