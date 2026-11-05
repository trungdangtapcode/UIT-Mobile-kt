package vn.edu.uit.mobile.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import vn.edu.uit.mobile.domain.*

class ChatGptSessionTest {
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
