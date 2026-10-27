package vn.edu.uit.mobile.daa

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

class ProfileScanTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
    private val session = PortalSession(Account("test", "https://portal.uit.edu.vn", 1, "Test Student", PortalType.UIT_PORTAL), "ums_portal_session=synthetic")
    private fun frame(stream: String): String = "<script>self.__next_f.push(${JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(stream)))})</script>"
    private fun page(source: String, visible: String = "images"): String = frame("1:{\"studentCode\":\"TEST001\",\"fullName\":\"Alex Morgan\",\"academic\":{},\"visibleTabs\":[\"$visible\"],\"imageProfile\":[{\"type\":\"transcript\",\"label\":\"Transcript scan\",\"imageUrl\":${JsonPrimitive(source)}}]}\n")

    @Test fun `Flight text rows use UTF8 byte lengths and resolve shared profile sections`() {
        val label = "Bản scan · Test"
        val text = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        val stream = "1:{\"studentCode\":\"TEST001\",\"fullName\":\"Alex Morgan\",\"academic\":\"\$2:faculty\",\"imageProfile\":\"\$3\"}\n" +
            "2:{\"faculty\":{\"faculty_name\":\"Test Faculty\"}}\n3:[{\"type\":\"transcript\",\"label\":\"\$4\",\"imageUrl\":\"\$5\"}]\n" +
            "4:T${label.toByteArray().size.toString(16)},$label" + "5:T${text.toByteArray().size.toString(16)},$text"
        val details = UitProfileParser.parse(frame(stream))
        assertEquals("Test Faculty", details.faculty)
        val document = details.sections.single { it.id == "images" }.groups.single().document!!
        assertEquals(label, document.title)
        assertTrue(document.available)
        assertEquals(text, UitProfileParser.scanSource(frame(stream), document.id).second)
        assertFalse(details.toString().contains("base64"))
    }

    @Test fun `escaped dollars remain literal while cyclic and missing references are unavailable`() {
        val objects = PortalFlightReader.objects(frame("1:{\"literal\":\"\$\$2\",\"missing\":\"\$ff\",\"cycle\":\"\$2\"}\n2:{\"again\":\"\$2\"}\n"))
        val root = objects.first { "literal" in it }
        assertEquals("\$2", root["literal"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, root["missing"])
        assertEquals(JsonNull, root["cycle"]?.jsonObject?.get("again"))
        assertThrows(InvalidResponseException::class.java) { PortalFlightReader.objects(frame("1:Tffff,short")) }
    }

    @Test fun `embedded scan needs only the profile GET and no separate download`() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                paths += request.url.encodedPath
                respond(page("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)))
            }
        )
        SafePortalTransport(session, http).use {
            val result = UitPortalClient(it).profileScan("transcript")
            assertArrayEquals(jpeg, result.bytes)
            assertEquals(listOf("/sinh-vien/ho-so"), paths)
        }
    }

    @Test fun `same origin PNG images use the portal session only after document lookup`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(session.cookie, request.headers[HttpHeaders.Cookie])
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/sinh-vien/ho-so" -> respond(page("/api/test-scan?document=synthetic"))
                    "/api/test-scan" -> respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                    else -> error("Unexpected request")
                }
            }
        )
        SafePortalTransport(session, http).use { assertArrayEquals(png, UitPortalClient(it).profileScan("transcript").bytes) }
        assertEquals(listOf("/sinh-vien/ho-so", "/api/test-scan"), paths)
    }

    @Test fun `external origins missing scans and hidden sections are rejected without a second request`() = runTest {
        for (source in listOf("https://external.invalid/private.jpg", "javascript:alert(1)", "data:text/html;base64,PGh0bWw+", "data:image/jpeg;base64,bm90LWFuLWltYWdl", "")) {
            var requests = 0
            val http = HttpClient(
                MockEngine {
                    requests++
                    respond(page(source))
                }
            )
            SafePortalTransport(session, http).use {
                assertTrue(appResult(PortalType.UIT_PORTAL) { UitPortalClient(it).profileScan("transcript") } is AppResult.Failure)
                assertEquals(1, requests)
            }
        }
        assertThrows(InvalidResponseException::class.java) { UitProfileParser.scanSource(page("/scan", "personal"), "transcript") }
        assertThrows(InvalidResponseException::class.java) { UitProfileParser.scanSource(page("/scan"), "another-document") }
    }
}
