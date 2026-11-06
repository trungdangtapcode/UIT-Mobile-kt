package vn.edu.uit.mobile.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*

class ChatGptSessionTest {
    @Test fun largeCatalogMetadataDoesNotPreventExplicitModelActivation() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.modelBody = """{"models":[{"slug":"fixture-model","display_name":"Fixture model","visibility":"list","capability_description":"${"x".repeat(400000)}"}]}"""
            val session = f.session()
            f.connect(session)
            assertNull(session.state.value.problem)
            assertEquals("fixture-model", session.state.value.models.single().id)
            assertEquals(session.state.value.selected, session.selected("fixture-model").first)
            assertFalse(f.storage.read("chatgpt_accounts")!!.contains("capability_description"))
            assertEquals(0, f.requests.count { it.endsWith("/responses") })
        }
    }

    @Test fun oversizedCatalogPreservesSignInAndRecoversWithoutAnotherBrowserConsent() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.modelBody = """{"models":[],"extra":"${"x".repeat(ChatGptModelCatalog.MAX_BYTES)}"}"""
            f.storage.write("connection", "existing-connection")
            val session = f.session()
            f.connect(session)
            val diagnostic = session.state.value.diagnostic!!
            assertEquals(ChatGptProblem.RESPONSE, session.state.value.problem)
            assertEquals("response_too_large", diagnostic.code)
            assertEquals("models", diagnostic.operation)
            assertEquals(200, diagnostic.status)
            assertEquals("fixture-model-request", diagnostic.requestId)
            assertTrue(diagnostic.responseBytes!! > ChatGptModelCatalog.MAX_BYTES)
            assertTrue(session.state.value.accounts.single().signedIn)
            assertEquals("existing-connection", f.storage.read("connection"))
            assertFalse(session.state.value.loadingModels)
            session.acknowledgeWelcome()
            session.initialize()
            assertEquals(diagnostic, session.state.value.diagnostic)
            f.modelBody = """{"models":[{"slug":"recovered-model","visibility":"list"}]}"""
            session.reloadModels()
            assertNull(session.state.value.problem)
            assertNull(session.state.value.diagnostic)
            assertEquals("recovered-model", session.state.value.models.single().id)
            assertEquals(1, f.authorizationQueries.size)
        }
    }

    @Test fun malformedCatalogReportsResponseShapeInsteadOfBlamingTheNetwork() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.modelBody = "<html>fixture response, not a model catalog</html>"
            val session = f.session()
            f.connect(session)
            assertEquals(ChatGptProblem.RESPONSE, session.state.value.problem)
            assertEquals("invalid_json", session.state.value.diagnostic?.code)
            assertEquals("fixture-model-request", session.state.value.diagnostic?.requestId)
            assertEquals("models", session.state.value.diagnostic?.operation)
            f.modelBody = """{"data":[{"id":"fixture-api-model"}]}"""
            session.reloadModels()
            assertEquals(ChatGptProblem.RESPONSE, session.state.value.problem)
            assertEquals("unexpected_model_catalog", session.state.value.diagnostic?.code)
            assertEquals("api-model-list", session.state.value.diagnostic?.bodyShape)
            assertTrue(session.state.value.accounts.single().signedIn)
            assertTrue(session.state.value.models.isEmpty())
        }
    }

    @Test fun unavailableAndDeniedCatalogsRetainSafeHttpDiagnostics() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.modelStatus = 403
            f.modelBody = """{"error":{"code":"access_denied","message":"fixture-private-message"}}"""
            val session = f.session()
            f.connect(session)
            assertEquals(ChatGptProblem.ACCESS, session.state.value.problem)
            assertEquals("access_denied", session.state.value.diagnostic?.code)
            assertEquals(403, session.state.value.diagnostic?.status)
            assertEquals("models", session.state.value.diagnostic?.operation)
            assertFalse(session.state.value.diagnostic.toString().contains("fixture-private-message"))
            assertTrue(session.state.value.accounts.single().signedIn)
            f.modelStatus = 503
            session.reloadModels()
            assertEquals(ChatGptProblem.UNAVAILABLE, session.state.value.problem)
            assertEquals(503, session.state.value.diagnostic?.status)
        }
    }

    @Test fun catalogIoFailureCanBeDiagnosedWithoutLeakingTheExceptionMessage() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.beforeModels = { throw IOException("fixture-sensitive-message") }
            val session = f.session()
            f.connect(session)
            assertEquals(ChatGptProblem.NETWORK, session.state.value.problem)
            assertEquals("network_io", session.state.value.diagnostic?.code)
            assertEquals(0, session.state.value.diagnostic?.status)
            assertEquals("models", session.state.value.diagnostic?.operation)
            assertFalse(session.state.value.diagnostic.toString().contains("fixture-sensitive-message"))
            assertTrue(session.state.value.accounts.single().signedIn)
            for ((error, code) in listOf(
                java.net.UnknownHostException("fixture-sensitive-host") to "dns_failure",
                javax.net.ssl.SSLException("fixture-sensitive-certificate") to "tls_failure",
                java.net.SocketTimeoutException("fixture-sensitive-timeout") to "network_timeout"
            )) {
                f.beforeModels = { throw error }
                session.reloadModels()
                assertEquals(code, session.state.value.diagnostic?.code)
                assertFalse(session.state.value.diagnostic.toString().contains("fixture-sensitive"))
            }
        }
    }

    @Test fun malformedEntriesAreSkippedAndAnEmptyVisibleCatalogHasItsOwnRecovery() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.modelBody = """{"models":[null,{"visibility":"list","slug":{}},{"visibility":"list","slug":"usable-model","display_name":null},{"visibility":"hide","slug":"hidden"}]}"""
            val session = f.session()
            f.connect(session)
            assertEquals(listOf(ChatGptModel("usable-model", "usable-model")), session.state.value.models)
            assertNull(session.state.value.problem)
            f.modelBody = """{"models":[]}"""
            session.reloadModels()
            assertEquals(ChatGptProblem.NO_MODELS, session.state.value.problem)
            assertEquals("no_visible_models", session.state.value.diagnostic?.code)
            assertTrue(session.state.value.models.isEmpty())
        }
    }

    @Test fun cancelledRevocationStillClearsLocalAccessAndRetainsTheUnconfirmedWarning() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            val entered = CompletableDeferred<Unit>()
            f.beforeRevoke = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val work = launch { session.signOut() }
            entered.await()
            work.cancelAndJoin()
            session.initialize()
            assertFalse(session.state.value.accounts.single().signedIn)
            assertEquals(ChatGptProblem.REVOCATION, session.state.value.problem)
            assertFalse(f.storage.read("chatgpt_accounts")!!.contains("refresh_token"))
        }
    }

    @Test fun closingTheSignInUiDoesNotDiscardARotatedRefreshToken() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            f.now += 3550
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeRefresh = {
                entered.complete(Unit)
                release.await()
            }
            val work = async { session.lease(session.state.value.selected!!) }
            entered.await()
            session.cancelSignIn()
            release.complete(Unit)
            assertEquals("fixture-refreshed-access-token", work.await().token)
            assertTrue(f.storage.read("chatgpt_accounts")!!.contains("fixture-rotated-refresh-token"))
        }
    }

    @Test fun validIdentityRequiresSignatureIssuerAudienceNonceAndLifetime() {
        ChatGptTestSupport().use { f ->
            assertEquals(f.subject, ChatGptOAuth.identity(f.jwt(), f.jwks(), "oaiapp_fixture", f.nonce, f.now).getValue("sub").jsonPrimitive.content)
            for (token in listOf(f.jwt(aud = "other-client"), f.jwt(expectedNonce = "other"), f.jwt(expires = f.now), f.jwt(issuer = "https://example.test"))) {
                assertTrue(runCatching { ChatGptOAuth.identity(token, f.jwks(), "oaiapp_fixture", f.nonce, f.now) }.isFailure)
            }
            val token = f.jwt()
            assertTrue(runCatching { ChatGptOAuth.identity(token.substringBeforeLast('.') + ".invalid", f.jwks(), "oaiapp_fixture", f.nonce, f.now) }.isFailure)
        }
    }

    @Test fun callbackRejectsDuplicateParameters() {
        assertTrue(runCatching { ChatGptOAuth.parameters("state=a&state=b&code=c") }.isFailure)
    }

    @Test fun reauthorizingAnotherSubjectCannotReplaceTheExistingRegistration() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            val original = f.storage.read("chatgpt_accounts")
            f.subject = "a-different-subscriber"
            f.connect(session, session.state.value.selected)
            assertEquals(ChatGptProblem.VALIDATION, session.state.value.problem)
            assertEquals(original, f.storage.read("chatgpt_accounts"))
            assertEquals(1, f.modelCalls)
        }
    }

    @Test fun cancelledBrowserAttemptNeverCreatesAConnection() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            session.signIn({ session.cancelSignIn() })
            assertEquals(ChatGptProblem.CANCELLED, session.state.value.problem)
            assertTrue(session.state.value.accounts.isEmpty())
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun browserFlowRejectsBadStateThenConnectsWithFreshPkceAndIssuedClient() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session, badStateFirst = true)
            assertNull(session.state.value.problem)
            assertTrue(session.state.value.accounts.single().planEnabled)
            assertEquals("fixture-model", session.state.value.models.single().id)
            val first = f.authorizationQueries.single()
            assertEquals("dynamic_agent_client", first["client_id"])
            assertEquals("S256", first["code_challenge_method"])
            assertEquals("UIT Mobile", first["agent_name_hint"])
            assertFalse(first.containsKey("code_verifier"))
            f.connect(session, session.state.value.selected)
            val second = f.authorizationQueries.last()
            assertEquals("oaiapp_fixture", second["client_id"])
            assertNull(second["agent_name_hint"])
            assertEquals(first["ext_agent_host_id"], second["ext_agent_host_id"])
            assertNotEquals(first["state"], second["state"])
            assertNotEquals(first["code_challenge"], second["code_challenge"])
            assertEquals(1, session.state.value.accounts.size)
        }
    }

    @Test fun declineAndInvalidIdentityRetainExistingConnectionAndNeverFetchModels() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.storage.write("connection", "original-connection")
            val session = f.session()
            f.connect(session, denied = true)
            assertEquals(ChatGptProblem.CANCELLED, session.state.value.problem)
            f.corruptNonce = true
            f.connect(session)
            assertEquals(ChatGptProblem.VALIDATION, session.state.value.problem)
            assertTrue(session.state.value.accounts.isEmpty())
            assertEquals(0, f.modelCalls)
            assertEquals("original-connection", f.storage.read("connection"))
        }
    }

    @Test fun identityOnlyConsentIsStoredWithoutInferencePermission() = runBlocking {
        ChatGptTestSupport().use { f ->
            f.scopes = "openid profile email"
            val session = f.session()
            f.connect(session)
            assertTrue(session.state.value.accounts.single().signedIn)
            assertFalse(session.state.value.accounts.single().planEnabled)
            assertEquals(0, f.modelCalls)
            assertTrue(runCatching { session.lease(session.state.value.selected!!) }.exceptionOrNull() is ChatGptException)
        }
    }

    @Test fun concurrentExpiryRefreshesOnceAndRotatesCredentialsTogether() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            val id = session.state.value.selected!!
            f.now += 3550
            val leases = (1..8).map { async(Dispatchers.Default) { session.lease(id) } }.awaitAll()
            assertEquals(1, f.refreshCalls)
            assertTrue(leases.all { it.token == "fixture-refreshed-access-token" })
            assertTrue(f.storage.read("chatgpt_accounts")!!.contains("fixture-rotated-refresh-token"))
        }
    }

    @Test fun terminalRefreshClearsTokensButTemporaryFailureKeepsThem() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            f.now += 3600
            f.refreshCode = "temporary_unavailable"
            f.refreshStatus = 503
            assertTrue(runCatching { session.lease(session.state.value.selected!!) }.isFailure)
            assertTrue(f.storage.read("chatgpt_accounts")!!.contains("fixture-initial-refresh-token"))
            f.refreshCode = "invalid_grant"
            f.refreshStatus = 400
            assertTrue(runCatching { session.lease(session.state.value.selected!!) }.isFailure)
            assertFalse(f.storage.read("chatgpt_accounts")!!.contains("refresh_token"))
            assertFalse(session.state.value.accounts.single().signedIn)
        }
    }

    @Test fun signOutClearsLocalTokensAndReportsUnconfirmedRevocation() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            val lease = session.lease(session.state.value.selected!!)
            f.revokeStatus = 503
            session.signOut()
            assertFalse(session.current(lease))
            assertFalse(session.state.value.accounts.single().signedIn)
            assertEquals(ChatGptProblem.REVOCATION, session.state.value.problem)
            assertFalse(f.storage.read("chatgpt_accounts")!!.contains("access_token"))
            assertTrue(f.storage.read("chatgpt_accounts")!!.contains("oaiapp_fixture"))
        }
    }

    @Test fun welcomeIsAcknowledgedOnlyOnceAndHiddenModelsAreExcluded() = runBlocking {
        ChatGptTestSupport().use { f ->
            val session = f.session()
            f.connect(session)
            assertTrue(session.state.value.welcome)
            session.acknowledgeWelcome()
            f.connect(session, session.state.value.selected)
            assertFalse(session.state.value.welcome)
            assertEquals(listOf("fixture-model"), session.state.value.models.map { it.id })
        }
    }
}
