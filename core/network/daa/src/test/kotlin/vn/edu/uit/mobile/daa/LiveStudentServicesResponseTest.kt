package vn.edu.uit.mobile.daa

import io.ktor.client.request.*
import io.ktor.http.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.model.*
import vn.edu.uit.mobile.network.*

/** Private captures are opt-in local input, never repository fixtures or CI artifacts. */
class LiveStudentServicesResponseTest {
    @Test fun `all nine authenticated service responses pass the production reader`() = runTest {
        val directory = System.getenv("UIT_LIVE_SERVICE_FIXTURES")
        assumeTrue(!directory.isNullOrBlank(), "Private service captures were not supplied")
        val root = File(requireNotNull(directory))
        val requests = mutableListOf<Pair<String, HttpMethod>>()
        val transport = object : PortalTransport {
            override val session = PortalSession(Account("test", "https://portal.uit.edu.vn", 1, "Test Student", PortalType.UIT_PORTAL), "synthetic")
            override suspend fun request(path: String, method: HttpMethod, configure: HttpRequestBuilder.() -> Unit): ByteArray {
                requests += path to method
                return File(root, "${path.substringAfterLast('/')}.json").readBytes()
            }
            override suspend fun download(url: String, destination: File): DownloadedFile = error("Downloads are outside this test")
        }
        val client = StudentServicesClient(transport)
        client.tuition()
        ProcedureKind.entries.forEach { client.procedure(it) }
        client.graduation()
        assertEquals(9, requests.size)
        assertEquals(1, requests.count { it.second == HttpMethod.Post })
    }
}
