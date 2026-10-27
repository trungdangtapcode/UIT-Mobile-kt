package vn.edu.uit.mobile.daa

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.PortalJson
import vn.edu.uit.mobile.network.PortalTransport

class LiveProfileResponseTest {
    @Test fun `private profile resolves referenced sections and opens an embedded scan`() = runTest {
        val directory = System.getenv("UIT_LIVE_SCAN_FIXTURES")
        assumeTrue(!directory.isNullOrBlank(), "Private scan profile was not supplied")
        val html = Files.readString(Path.of(requireNotNull(directory)).resolve("profile.html"))
        val details = UitProfileParser.parse(html)
        assertTrue(details.faculty.isNotBlank())
        val document = details.sections.single { it.id == "images" }.groups.mapNotNull { it.document }.first { it.available }
        val transport = object : PortalTransport {
            override val session = PortalSession(Account("live-test", "https://portal.uit.edu.vn", 1, "Test", PortalType.UIT_PORTAL), "")
            override suspend fun request(path: String, method: HttpMethod, configure: HttpRequestBuilder.() -> Unit): ByteArray {
                assertEquals("/sinh-vien/ho-so", path)
                assertEquals(HttpMethod.Get, method)
                return html.toByteArray()
            }
            override suspend fun download(url: String, destination: File): DownloadedFile = error("Scans must not use disk downloads")
        }
        val scan = UitPortalClient(transport).profileScan(document.id)
        assertEquals(document.id, scan.documentId)
        assertTrue(scan.bytes.size > 8)
        assertFalse(details.toString().contains("data:image"))
    }

    @Test fun `private authorized profile and insurance captures pass production parsers`() {
        val directory = System.getenv("UIT_LIVE_PROFILE_FIXTURES")
        assumeTrue(!directory.isNullOrBlank(), "Private profile captures were not supplied")
        val root = Path.of(requireNotNull(directory))
        val profile = UitProfileParser.parse(Files.readString(root.resolve("profile.html")))
        val insurance = UitProfileParser.insurance(PortalJson.parseToJsonElement(Files.readString(root.resolve("insurance.json"))))
        assertTrue(profile.studentCode.isNotBlank())
        assertTrue(profile.fullName.isNotBlank())
        assertTrue(profile.sections.any { it.id == "academic" })
        assertEquals("insurance", insurance.id)
    }
}
