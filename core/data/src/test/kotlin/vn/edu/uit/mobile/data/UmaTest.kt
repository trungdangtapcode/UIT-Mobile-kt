package vn.edu.uit.mobile.data

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.model.UmaSession

/** Synthetic protocol and fault fixtures; these are not live UMA evidence. */
class UmaTest {
    private val session = UmaSession("ums2_sessionid=${"a".repeat(32)}; ums2_csrftoken=${"b".repeat(32)}", "c".repeat(64))
    private val html = """<div class="ums-tk-head"><b>Synthetic student</b><span>synthetic-owner</span></div><input name="csrfmiddlewaretoken" value="${"d".repeat(64)}"><input id="ho_so" value="toi"><input name="nguon" value="chung"><input name="nguon" value="ca_nhan"><form id="ask"><textarea id="q"></textarea></form>"""
    private val answer = """{"ket_qua":"answered","tra_loi":"A **verified** reply [1].","cau_hieu":"Expanded question","trich_dan":[{"n":1,"ten":"Rule","xem":"/uma/nguon/1/","goc":"https://portal.uit.edu.vn/rules","vi_tri":"Section 2","ap_dung":"Students","khop_thap":true,"ban_quet":true}]}"""
    private class Memory : StudyStorage {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
        override fun removeAccount(accountId: String) = error("UMA must not access other account stores")
    }
    private fun remote(ask: suspend (UmaSession, String) -> UmaReceived = { s, _ -> UmaReceived(s, UmaProtocol.answer(answer)) }) = object : UmaRemote {
        override suspend fun connect(session: UmaSession) = UmaConnected(session, UmaProtocol.bootstrap(html))
        override suspend fun ask(session: UmaSession, body: String) = ask.invoke(session, body)
    }

    @Test fun bootstrapRequiresAnActualStudentChatAndDiscoversOnlySupportedSources() {
        val b = UmaProtocol.bootstrap(html.replace("</form>", "</form><input name=nguon value=kiem_dao_van>"))
        assertEquals("synthetic-owner", b.identity.id)
        assertEquals(setOf(UmaSource.GENERAL, UmaSource.PERSONAL), b.available)
        for (value in listOf("<button id=dang_nhap>Login</button>", html.replace("value=\"toi\"", "value=\"admin\""))) {
            try {
                UmaProtocol.bootstrap(value)
                fail()
            } catch (e: UmaException) {
                assertEquals(UmaFailure.EXPIRED, e.reason)
            }
        }
    }

    @Test fun boundedHistoryIsAnEncodedStringAndExcludesFailedTurns() {
        val history = (1..7).map { UmaTurn("$it", "q$it", if (it == 7) UmaTurnStatus.FAILED else UmaTurnStatus.COMPLETE, UmaProtocol.answer(answer).copy(text = "x ".repeat(300), understood = "full$it")) }
        val body = Json.parseToJsonElement(UmaProtocol.request("Next?", setOf(UmaSource.PERSONAL), history, null)).jsonObject
        assertEquals("full6", body["truoc"]!!.jsonPrimitive.content)
        assertEquals("ca_nhan", body["nguon"]!!.jsonPrimitive.content)
        val turns = Json.parseToJsonElement(body["lich_su"]!!.jsonPrimitive.content).jsonArray
        assertEquals(listOf("full4", "full5", "full6"), turns.map { it.jsonObject["q"]!!.jsonPrimitive.content })
        assertTrue(turns.all { it.jsonObject["tl"]!!.jsonPrimitive.content.length == 200 })
        assertEquals("toi", body["ho_so"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["goi_y_id"])
    }

    @Test fun noSourceIsAValidAnswerAndCitationsRetainWarningsAndSafeLinks() {
        assertEquals("no_source", UmaProtocol.answer("""{"ket_qua":"no_source","tra_loi":"No accessible source.","future_field":true}""").result)
        val a = UmaProtocol.answer(answer)
        assertTrue(a.citations.single().lowConfidence)
        assertTrue(a.citations.single().scanned)
        assertEquals("https://uma.uit.edu.vn/uma/nguon/1/", a.citations.single().url)
        assertEquals("Students", a.citations.single().appliesTo)
        assertTrue(a.blocks.flatMap { it.inline }.any { it.bold })
    }

    @Test fun markdownTablesAndStructuredDataRetainTheirValuesWithoutExecutingHtml() {
        val text = """{"ket_qua":"answered","tra_loi":"| Course | Grade |\n| --- | --- |\n| TEST101 | **9.0** |\n\n<script>unsafe()</script>","hien_thi":{"tieu_de":"Records","khoi":[{"loai":"bang","trong":"No rows","cot":[{"ten":"Course"},{"ten":"Grade"}],"dong":[[{"t":"TEST101","ma":true},{"t":"9.0","dam":true}]]}]}}"""
        val a = UmaProtocol.answer(text)
        assertTrue(a.blocks.any { it.kind == ForumBlockKind.TABLE })
        assertEquals("9.0", a.display.single().children.single().rows.single()[1].text)
        assertTrue(a.display.single().children.single().rows.single()[1].bold)
        assertEquals("", a.display.single().children.single().note)
    }

    @Test fun weekDetailsAndUnknownDisplayKindsRemainVisible() {
        val a = UmaProtocol.answer("""{"ket_qua":"answered","tra_loi":"Week text","lich_tuan":{"tieu_de":"Week","buoi":[{"ngay":"2026-11-12","ma_lop":"TEST101","ten_mon":"Synthetic course","tiet_bat_dau":1,"tiet_ket_thuc":3,"phong":"A101"}],"hoi_sau":"Next week?"},"hien_thi":{"khoi":[{"loai":"future_kind"}]}}""")
        assertEquals("A101", a.week!!.lessons.single().room)
        assertEquals("1–3", a.week!!.lessons.single().periods)
        assertEquals("Next week?", a.week!!.navigation.single().question)
        assertTrue(a.unsupported)
    }

    @Test fun linksRejectCredentialsUnsafeSchemesAndAccountMutations() {
        for (url in listOf("javascript:alert(1)", "//evil.test/", "https://user:secret@uit.edu.vn/", "http://uit.edu.vn/", "https://uma.uit.edu.vn/oidc/dang-xuat/", "/doc?token=private", "https://uit.edu.vn:8443/")) assertNull(url, UmaUrls.link(url))
        assertEquals("https://portal.uit.edu.vn/sinh-vien/tkb", UmaUrls.link("https://portal.uit.edu.vn/sinh-vien/tkb"))
        assertFalse(session.toString().contains("ums2_sessionid"))
    }

    @Test fun nativeRequestIsOriginScopedAndSendsTheBootstrapMaskedCsrf() = runTest {
        val calls = mutableListOf<String>()
        val client = UmaClient(
            HttpClient(
                MockEngine { request ->
                    assertEquals("uma.uit.edu.vn", request.url.host)
                    assertEquals(session.cookie, request.headers[HttpHeaders.Cookie])
                    assertFalse(request.headers.contains(HttpHeaders.Authorization))
                    calls += request.url.encodedPath
                    if (request.method == HttpMethod.Get) {
                        respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html"))
                    } else {
                        assertEquals("d".repeat(64), request.headers["X-CSRFToken"])
                        assertEquals(UmaUrls.ORIGIN, request.headers[HttpHeaders.Origin])
                        assertEquals(UmaUrls.EMBED, request.headers[HttpHeaders.Referrer])
                        assertTrue((request.body as TextContent).text.contains("\"lich_su\":\"[]\""))
                        respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            ) { followRedirects = false }
        )
        val connected = client.connect(session)
        assertEquals("answered", client.ask(connected.session, UmaProtocol.request("Question", setOf(UmaSource.GENERAL), emptyList(), null)).answer.result)
        assertEquals(listOf("/uma/nhung/", "/uma/api/hoi/"), calls)
    }

    @Test fun httpFailuresDoNotReplayQuestionsOrFollowRedirects() = runTest {
        for ((status, reason) in listOf(302 to UmaFailure.EXPIRED, 401 to UmaFailure.EXPIRED, 403 to UmaFailure.CSRF, 429 to UmaFailure.RATE_LIMIT, 503 to UmaFailure.SERVER)) {
            var calls = 0
            val client = UmaClient(
                HttpClient(
                    MockEngine {
                        calls++
                        respond("private server body", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.Location, "https://evil.test/"))
                    }
                ) { followRedirects = false }
            )
            try {
                client.ask(session, "{}")
                fail()
            } catch (e: UmaException) {
                assertEquals(reason, e.reason)
                assertFalse(e.message!!.contains("private"))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun expiredHtmlAndOversizedBodiesAreRejected() = runTest {
        for ((body, type, reason) in listOf(Triple("<form>Login</form>", "text/html", UmaFailure.EXPIRED), Triple("x".repeat(1024 * 1024 + 1), "application/json", UmaFailure.RESPONSE))) {
            val client = UmaClient(HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, type)) }))
            try {
                client.ask(session, "{}")
                fail()
            } catch (e: UmaException) {
                assertEquals(reason, e.reason)
            }
        }
    }

    @Test fun disconnectClearsSessionAndLateAnswersCannotRepopulateHistory() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val memory = Memory()
        val repo = ConnectedUmaRepository(
            memory,
            remote { s, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                UmaReceived(s, UmaProtocol.answer(answer))
            }
        )
        repo.connect(session)
        val work = launch { repo.ask("Private question") }
        entered.await()
        repo.disconnect()
        gate.complete(Unit)
        work.join()
        assertNull(repo.state.value.identity)
        assertTrue(repo.state.value.turns.isEmpty())
        assertTrue(memory.data.isEmpty())
    }

    @Test fun sourceChangesClearPersonalContextAndPersistenceContainsOnlyCredentials() = runTest {
        val memory = Memory()
        val bodies = mutableListOf<String>()
        val repo = ConnectedUmaRepository(
            memory,
            remote { s, body ->
                bodies += body
                UmaReceived(s, UmaProtocol.answer(answer))
            }
        )
        repo.connect(session)
        repo.sources(setOf(UmaSource.PERSONAL))
        repo.ask("Private question")
        repo.sources(setOf(UmaSource.GENERAL))
        repo.ask("General question")
        assertEquals("[]", Json.parseToJsonElement(bodies.last()).jsonObject["lich_su"]!!.jsonPrimitive.content)
        assertFalse(memory.data.values.any { it.contains("question") || it.contains("verified") })
        assertEquals(setOf("session"), memory.data.keys)
    }

    @Test fun cancellationRetainsTheQuestionAndDoesNotBecomeASuccessfulHistoryTurn() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repo = ConnectedUmaRepository(
            Memory(),
            remote { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
        )
        repo.connect(session)
        val job = launch { repo.ask("Keep this question") }
        entered.await()
        job.cancelAndJoin()
        assertFalse(repo.state.value.busy)
        assertEquals(UmaTurnStatus.CANCELLED, repo.state.value.turns.single().status)
        assertEquals("Keep this question", repo.state.value.turns.single().question)
    }

    @Test fun failedAccountReplacementCannotRestoreThePreviousSavedAccount() = runTest {
        val memory = Memory()
        val working = ConnectedUmaRepository(memory, remote())
        working.connect(session)
        assertFalse(memory.data.isEmpty())
        val failing = ConnectedUmaRepository(
            memory,
            object : UmaRemote {
                override suspend fun connect(session: UmaSession): UmaConnected = throw UmaException(UmaFailure.NETWORK)
                override suspend fun ask(session: UmaSession, body: String): UmaReceived = error("Not connected")
            }
        )
        failing.connect(session)
        assertTrue(memory.data.isEmpty())
        assertNull(failing.state.value.identity)
    }

    @Test fun expiredSessionDisablesFurtherSendingAndRemovesTheSavedGrant() = runTest {
        val memory = Memory()
        val repo = ConnectedUmaRepository(memory, remote { _, _ -> throw UmaException(UmaFailure.EXPIRED) })
        repo.connect(session)
        repo.ask("Question")
        assertNull(repo.state.value.identity)
        assertTrue(memory.data.isEmpty())
        assertEquals(UmaFailure.EXPIRED, repo.state.value.error)
        assertEquals(UmaTurnStatus.FAILED, repo.state.value.turns.single().status)
    }
}
