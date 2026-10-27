package vn.edu.uit.mobile.daa

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.InvalidResponseException

class UitProfileTest {
    private fun html(profile: String): String {
        val frame = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("1:[\"$\",\"div\",null,{\"profile\":$profile}]\n")))
        return "<html><script>self.__next_f.push($frame)</script></html>"
    }
    private val profile = """{"studentCode":"TEST001","fullName":"Alex Morgan","academic":{"student_code":"TEST001","faculty_name":"Computer Science","major_name":"Software Engineering","class_name":"SE2026","passed_credits":0},"personal":{"full_name":"Alex Morgan","phone":"TEST-PHONE","date_of_birth":"2005-01-01","id_card_number":null},"family":{"mother":{"full_name":"Test Parent","is_deceased_or_unknown":false},"notice_contacts":[{"full_name":"Emergency Test","is_primary":true}]},"imageProfile":[{"label":"Transcript","imageUrl":"https://portal.uit.edu.vn/private-document?token=SECRET-TEST","required":true}],"visibleTabs":["personal","family","academic","images"]}"""

    @Test fun `student details follow visible tabs and contain human labels without attachment URLs`() {
        val details = UitProfileParser.parse(html(profile))
        assertEquals("TEST001", details.studentCode)
        assertEquals(listOf("personal", "family", "academic", "images"), details.sections.map { it.id })
        val personal = details.sections.first().groups.flatMap { it.fields }
        assertEquals("TEST-PHONE", personal.single { it.label == "Phone" }.value)
        assertEquals("Not provided", personal.single { it.label == "National ID number" }.value)
        assertEquals("0", details.sections.single { it.id == "academic" }.groups.single().fields.single { it.label == "Credits earned" }.value)
        assertEquals("No", details.sections.single { it.id == "family" }.groups.first().fields.single { it.label == "Deceased or unknown" }.value)
        assertFalse(details.toString().contains("SECRET-TEST"))
        assertFalse(details.toString().contains("private-document"))
        assertNull(details.portrait)
    }

    @Test fun `empty portal visibility list uses the portal default groups`() {
        val result = UitProfileParser.parse(html(profile.replace("[\"personal\",\"family\",\"academic\",\"images\"]", "[]")))
        assertEquals(7, result.sections.size)
    }

    @Test fun `missing profile identity cannot produce another student placeholder`() {
        assertThrows(InvalidResponseException::class.java) { UitProfileParser.parse(html(profile.replace("\"studentCode\":\"TEST001\"", "\"studentCode\":\"\""))) }
        assertThrows(InvalidResponseException::class.java) { UitProfileParser.parse("<html>Sign in required</html>") }
    }

    @Test fun `insurance keeps current cards separate from fallback profile information`() {
        val data = Json.parseToJsonElement("""{"profile":{"insurance_code":"TEST-FALLBACK","insurance_period":"2026"},"cards":{"bhyt":{"current":null},"bhtn":{"current":{"insurance_code":"TEST-CURRENT","start_date":"2026-01-01","end_date":"2026-12-31","is_valid":false}}},"history":[]}""")
        val section = UitProfileParser.insurance(data)
        assertEquals("TEST-FALLBACK", section.groups[0].fields.first().value)
        assertEquals("TEST-CURRENT", section.groups[1].fields.first().value)
        assertEquals("No", section.groups[1].fields.single { it.label == "Currently valid" }.value)
        assertThrows(InvalidResponseException::class.java) { UitProfileParser.insurance(Json.parseToJsonElement("{}")) }
    }
}
