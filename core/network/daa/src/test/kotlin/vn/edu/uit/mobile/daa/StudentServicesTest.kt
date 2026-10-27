package vn.edu.uit.mobile.daa

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.InvalidResponseException
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class StudentServicesTest {
    private fun json(value: String) = PortalJson.parseToJsonElement(value)
    private val tuition = """{"records":[{"id":1,"period":"Semester 1 / 2026","year_id":[47],"year_id_name":"2026","must_be_paid":"123456789012345.25","paid":"0","remaining":null,"debt_in_advance":"","payment_status_label":"Unpaid","period_1_due_date":"2026-11-01","detail_ids":[{"subject_code":"IT001","subject_name":"Programming","tuition_credit_number":"3","amount":"1000000.50"}],"payment_ids":[{"amount":100.25,"payment_time":"2026-10-01","bank_name":"Test bank","status":"Recorded","trans_id":"PRIVATE-TEST","invoice_code":"PRIVATE-INVOICE"}]}]}"""

    @Test fun `tuition retains exact decimal precision and does not turn unavailable balances into zero`() {
        val row = StudentServicesParser.tuition(json(tuition)).terms.single()
        assertEquals("123456789012345.25", row.payable)
        assertEquals("0", row.paid)
        assertNull(row.remaining)
        assertNull(row.priorDebt)
        assertEquals("1000000.50", row.charges.single().amount)
        assertEquals("100.25", row.payments.single().amount)
        assertFalse(row.toString().contains("PRIVATE"))
    }

    @Test fun `tuition empty response is distinct from a failed schema or reported error`() {
        assertTrue(StudentServicesParser.tuition(json("""{"records":[]}""")).terms.isEmpty())
        for (bad in listOf("{}", """{"records":[],"error":"unavailable"}""", tuition.replace("123456789012345.25", "not-an-amount"), tuition.replace("\"detail_ids\":[", "\"other\":["))) {
            assertThrows(InvalidResponseException::class.java) { StudentServicesParser.tuition(json(bad)) }
        }
    }

    @Test fun `certificate histories keep their categories and reject malformed records`() {
        val history = """{"history":{"xnsv":[{"id":"A","stage_name":"Ready","regis_number":"TEST-CERT","create_date":"2026-10-01","wrong_reason":"Bring student card"}],"vvnh":[],"ggt":[],"xntt":[],"drl":[],"udgd":[],"gkhk":[]}}"""
        val result = StudentServicesParser.procedure(ProcedureKind.CERTIFICATES, json(history))
        assertEquals("xnsv:A", result.records.single().id)
        assertEquals("Ready", result.records.single().status)
        assertEquals("Bring student card", result.records.single().note)
        assertThrows(InvalidResponseException::class.java) { StudentServicesParser.procedure(ProcedureKind.CERTIFICATES, json(history.replace("\"id\":\"A\",", ""))) }
    }

    @Test fun `graduation preserves unknown gates and server eligibility without computing a replacement`() {
        val data = """{"data":{"config":{"periodLabel":"Assessment 4","startDate":"2026-10-01","endDate":"2026-10-31"},"canRegister":false,"alreadyRegistered":false,"registrationStatus":null,"conditions":[{"id":1,"name":"Credits","status":"pending","note":null}],"debts":[{"label":"Library","ok":null,"statusText":"Not checked"}],"registerGates":{"docs":true,"fee":false,"credit":true}}}"""
        val result = StudentServicesParser.graduation(json(data))
        assertEquals(false, result.canRegister)
        assertEquals(false, result.feeGate)
        assertNull(result.phoneGate)
        assertNull(result.debts.single().satisfied)
        assertEquals("pending", result.conditions.single().status)
        assertEquals("", result.registrationStatus)
        assertThrows(InvalidResponseException::class.java) { StudentServicesParser.graduation(json("""{"data":null,"error":"No period"}""")) }
    }

    @Test fun `student service client only performs the nine approved reads`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val http = HttpClient(
            MockEngine { request ->
                requests += request.method to request.url.encodedPath
                val path = request.url.encodedPath
                if (request.method == HttpMethod.Post) {
                    assertEquals("/api/sv/tuition", path)
                    val body = request.body as io.ktor.http.content.TextContent
                    val projection = json(body.text).jsonObject
                    assertEquals(setOf("tuition_field_list", "detail_field_list"), projection.keys)
                    assertTrue(projection.getValue("tuition_field_list").jsonArray.contains(JsonPrimitive("remaining")))
                    assertFalse(body.text.contains("action"))
                }
                val response = when {
                    path == "/api/sv/tuition" -> """{"records":[]}"""
                    path.endsWith("giay-xac-nhan") -> """{"history":{"xnsv":[],"vvnh":[],"ggt":[],"xntt":[],"drl":[],"udgd":[],"gkhk":[]}}"""
                    path.endsWith("xin-bang-diem") -> """{"history":[],"parameters":[]}"""
                    path.endsWith("hoan-thi") -> """{"history":[],"reexamHistory":[]}"""
                    path.endsWith("xac-nhan-chung-chi") -> """{"certs":[]}"""
                    path.endsWith("tot-nghiep") -> """{"data":{"conditions":[],"debts":[]}}"""
                    else -> """{"history":[]}"""
                }
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        SafePortalTransport(PortalSession(Account("test-portal", "https://portal.uit.edu.vn", 1, "Test Student", PortalType.UIT_PORTAL), "ums_portal_session=synthetic"), http).use { transport ->
            val client = StudentServicesClient(transport)
            client.tuition()
            ProcedureKind.entries.forEach { client.procedure(it) }
            client.graduation()
        }
        assertEquals(9, requests.size)
        assertEquals(1, requests.count { it.first == HttpMethod.Post })
        assertEquals(8, requests.count { it.first == HttpMethod.Get })
    }
}
