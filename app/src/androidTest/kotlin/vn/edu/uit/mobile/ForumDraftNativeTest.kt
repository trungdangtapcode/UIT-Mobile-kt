package vn.edu.uit.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.data.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.forum.network.*

/** Real Android Keystore and recreated production repositories; isolated HTTP fixture, no UIT. */
@RunWith(AndroidJUnit4::class)
class ForumDraftNativeTest {
    @Test fun encryptedDraftSurvivesRepositoryRecreationAndRemainsAccountBound() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "forum-draft-$suffix"
        val alias = "uit.forum.draft.$suffix"
        var accountId = 7
        val http = HttpClient(
            MockEngine { request ->
                val headers = headersOf(HttpHeaders.ContentType, "application/json")
                when (request.url.encodedPath) {
                    "/session/csrf.json" -> respond("""{"csrf":"fixture"}""", headers = headers)
                    "/session" -> respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "_t=fixture; Path=/; Secure"))
                    "/session/current.json" -> respond("""{"current_user":{"id":$accountId,"username":"fixture","can_create_topic":true}}""", headers = headers)
                    else -> error("Unexpected request in local draft acceptance")
                }
            }
        ) { followRedirects = false }
        val guest = object : ForumRepository {
            override suspend fun load(request: ForumRequest): ForumLoaded = error("Unexpected guest request")
            override suspend fun local() = ForumLocalState()
            override suspend fun save(topic: ForumTopic, saved: Boolean) = ForumLocalState()
            override suspend fun position(position: ForumPosition) = Unit
            override suspend fun image(url: String) = byteArrayOf()
            override suspend fun clear() = Unit
        }
        fun repository() = ConnectedForumRepository(guest, ForumSessionClient(http), StudyPrivateStorage(context, name, alias))
        try {
            val original = repository()
            original.login("fixture", "not-a-university-password")
            val draft = ForumDraft(raw = "Private draft **body**", title = "Private title", categoryId = 1)
            original.saveDraft(draft)
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.map { it.toString() }
            assertFalse(values.any { draft.raw in it || draft.title in it || "not-a-university-password" in it })
            val restored = repository()
            assertEquals(7L, restored.resume()?.id)
            assertEquals(draft, restored.draft())
            restored.disconnect()
            accountId = 8
            restored.login("other", "not-a-university-password")
            assertNull(restored.draft())
            restored.disconnect()
            accountId = 7
            restored.login("fixture", "not-a-university-password")
            assertEquals(draft, restored.draft())
            restored.saveDraft(null)
            restored.disconnect()
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        } finally {
            http.close()
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
