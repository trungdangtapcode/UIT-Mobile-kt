package vn.edu.uit.mobile.network

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.model.*

class TransportTest {
    private val session = PortalSession(Account("test", "https://coursesold.uit.edu.vn/sdh", 1, "Student", PortalType.MOODLE), "MoodleSession=test", "key")

    @TempDir lateinit var directory: File

    @Test fun `external redirects never receive portal cookies`() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("MoodleSession=test", request.headers[HttpHeaders.Cookie])
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/steal"))
            }
        )
        SafePortalTransport(session, http).use { transport ->
            assertThrows(OriginRejectedException::class.java) { kotlinx.coroutines.runBlocking { transport.request("/file") } }
        }
        http.close()
        assertEquals(1, calls)
    }

    @Test fun `binary labeled login page is rejected before writing`() = runTest {
        val http = HttpClient(MockEngine { respond("<form id='login'><input name='password'></form>", headers = headersOf(HttpHeaders.ContentType, "application/pdf")) })
        val destination = File(directory, "slides.pdf").apply { writeText("original") }
        SafePortalTransport(session, http).use { transport ->
            assertThrows(SessionExpiredException::class.java) { kotlinx.coroutines.runBlocking { transport.download("/pluginfile.php/1/slides.pdf", destination) } }
        }
        assertEquals("original", destination.readText())
        assertEquals(1, directory.listFiles()!!.size)
        http.close()
    }

    @Test fun `download preserves all prefix bytes and atomically replaces file`() = runTest {
        val bytes = ByteArray(50000) { (it % 128).toByte() }
        val http = HttpClient(MockEngine { respond(bytes) })
        val destination = File(directory, "slides.pdf")
        SafePortalTransport(session, http).use {
            val result = it.download("/pluginfile.php/1/slides.pdf", destination)
            assertArrayEquals(bytes, destination.readBytes())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }, result.sha256)
        }
        assertEquals(1, directory.listFiles()!!.size)
        http.close()
    }

    @Test fun `same origin redirects preserve installation prefix`() = runTest {
        val seen = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                seen += request.url.encodedPath
                if (seen.size == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/pluginfile.php/2/file.pdf")) else respond("pdf data")
            }
        )
        SafePortalTransport(session, http).use { assertEquals("pdf data", it.request("/start").toString(Charsets.UTF_8)) }
        assertEquals(listOf("/sdh/start", "/sdh/pluginfile.php/2/file.pdf"), seen)
        http.close()
    }

    @Test fun `writes are never replayed across redirects`() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine {
                calls++
                respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/saved"))
            }
        )
        SafePortalTransport(session, http).use { transport ->
            assertThrows(InvalidResponseException::class.java) { kotlinx.coroutines.runBlocking { transport.request("/save", HttpMethod.Post) } }
        }
        assertEquals(1, calls)
        http.close()
    }

    @Test fun `post redirect confirms saved page using GET without replaying body`() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val http = HttpClient(
            MockEngine { request ->
                methods += request.method
                if (methods.size == 1) respond("", HttpStatusCode.SeeOther, headersOf(HttpHeaders.Location, "/saved")) else respond("saved")
            }
        )
        SafePortalTransport(session, http).use { assertEquals("saved", it.request("/save", HttpMethod.Post).toString(Charsets.UTF_8)) }
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), methods)
        http.close()
    }

    @Test fun `origin guard rejects downgrade embedded credentials and login redirects`() {
        val guard = OriginGuardPlugin(session.account.baseUrl)
        for (url in listOf("http://coursesold.uit.edu.vn/sdh/file", "https://user:pw@coursesold.uit.edu.vn/sdh/file", "https://coursesold.uit.edu.vn:444/sdh/file", "https://coursesold.uit.edu.vn/other")) assertThrows(OriginRejectedException::class.java) { guard.validate(url) }
        assertThrows(SessionExpiredException::class.java) { guard.validate("https://coursesold.uit.edu.vn/sdh/login/index.php") }
    }
}
