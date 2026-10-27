package vn.edu.uit.mobile.auth

import android.app.Activity
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import vn.edu.uit.mobile.core.auth.R
import vn.edu.uit.mobile.model.PortalSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SsoLoginTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val errors = mutableListOf<String>()
    private val sessions = mutableListOf<List<PortalSession>>()

    @After fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        activity.finish()
    }

    private fun launch(): android.app.Dialog {
        UnifiedSsoLoginController(activity).launch(onResult = { sessions += it }, onError = { errors += it })
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog()
    }

    private fun android.app.Dialog.web(): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return requireNotNull(find(window!!.decorView))
    }

    private fun request(url: String = "https://courses.uit.edu.vn/login/index.php", main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun loginAlwaysOffersNativeCloseAndInitialStatus() {
        val dialog = launch()
        assertTrue(dialog.isShowing)
        assertEquals("Close", dialog.findViewById<Button>(R.id.sso_close).text.toString())
        assertTrue(dialog.findViewById<TextView>(R.id.sso_status).text.isNotBlank())
        assertEquals("https://courses.uit.edu.vn/login/index.php", shadowOf(dialog.web()).lastLoadedUrl)
        dialog.findViewById<Button>(R.id.sso_close).performClick()
        assertFalse(dialog.isShowing)
        assertTrue(sessions.isEmpty())
    }

    @Test fun timeoutRemainsVisibleAndRetryStartsSafeLoginEntry() {
        val dialog = launch()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertTrue(dialog.findViewById<TextView>(R.id.sso_status).text.contains("too long"))
        assertEquals(View.VISIBLE, dialog.findViewById<Button>(R.id.sso_retry).visibility)
        dialog.findViewById<Button>(R.id.sso_retry).performClick()
        assertEquals("https://courses.uit.edu.vn/login/index.php", shadowOf(dialog.web()).lastLoadedUrl)
        assertEquals(View.GONE, dialog.findViewById<Button>(R.id.sso_retry).visibility)
        assertEquals(1, errors.size)
    }

    @Test fun sslFailureIsCancelledAndCannotBeReplacedByPageFinished() {
        val dialog = launch()
        val web = dialog.web()
        val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
        val error = SslError(SslError.SSL_UNTRUSTED, SslCertificate("UIT", "Test CA", "", ""), "https://courses.uit.edu.vn/login/index.php?ticket=private")
        web.webViewClient.onReceivedSslError(web, handler, error)
        web.webViewClient.onPageFinished(web, "https://courses.uit.edu.vn/login/index.php")
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
        val message = dialog.findViewById<TextView>(R.id.sso_status).text.toString()
        assertTrue(message.contains("HTTPS certificate"))
        assertFalse(message.contains("ticket"))
        assertEquals(View.VISIBLE, dialog.findViewById<Button>(R.id.sso_retry).visibility)
        assertEquals(View.GONE, web.visibility)
        assertTrue(sessions.isEmpty())
    }

    @Test fun mainFrameHttpErrorsAreVisibleButMissingSubresourcesAreNotFatal() {
        val dialog = launch()
        val web = dialog.web()
        val response = WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable", emptyMap(), null)
        web.webViewClient.onReceivedHttpError(web, request(main = false), response)
        assertTrue(errors.isEmpty())
        web.webViewClient.onReceivedHttpError(web, request(), response)
        assertTrue(dialog.findViewById<TextView>(R.id.sso_status).text.contains("HTTP 503"))
        assertEquals(1, errors.size)
    }

    @Test fun navigationOnlyAllowsExactUitHttpsOrigins() {
        val dialog = launch()
        val web = dialog.web()
        for (url in listOf("https://sso.uit.edu.vn/realms/UIT", "https://courses.uit.edu.vn:443/login/index.php")) {
            assertFalse(web.webViewClient.shouldOverrideUrlLoading(web, request(url)))
        }
        for (url in listOf("http://courses.uit.edu.vn/", "https://courses.uit.edu.vn.evil.test/", "https://courses.uit.edu.vn:8443/", "https://user@courses.uit.edu.vn/")) {
            assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(url)))
        }
        assertTrue(dialog.findViewById<TextView>(R.id.sso_status).text.contains("blocked"))
    }

    @Test fun closingWindowCancelsPendingTimeout() {
        val dialog = launch()
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertTrue(errors.isEmpty())
        assertTrue(sessions.isEmpty())
    }

    @Test fun portalFailureDoesNotTrapAnAlreadyConnectedMoodleAccount() {
        val dialog = launch()
        val web = dialog.web()
        CookieManager.getInstance().setCookie("https://courses.uit.edu.vn", "MoodleSession=synthetic-session")
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        val identity = """{"sesskey":"synthetic-key","origin":"https://courses.uit.edu.vn","profileHref":"https://courses.uit.edu.vn/user/profile.php?id=123","name":"Test Student"}"""
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive(identity).toString())
        assertEquals("https://portal.uit.edu.vn/api/auth/login", shadowOf(web).lastLoadedUrl)
        assertTrue(sessions.isEmpty())
        web.webViewClient.onReceivedHttpError(web, request("https://portal.uit.edu.vn/api/auth/login"), WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable", emptyMap(), null))
        val continueButton = dialog.findViewById<Button>(R.id.sso_continue_moodle)
        assertEquals(View.VISIBLE, continueButton.visibility)
        continueButton.performClick()
        assertFalse(dialog.isShowing)
        assertEquals(1, sessions.single().size)
        assertEquals(123L, sessions.single().single().account.userId)
    }

    @Test fun scriptResultFromAnExternalProfileCannotCreateASession() {
        val dialog = launch()
        val web = dialog.web()
        CookieManager.getInstance().setCookie("https://courses.uit.edu.vn", "MoodleSession=synthetic-session")
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        val identity = """{"sesskey":"synthetic-key","origin":"https://courses.uit.edu.vn","profileHref":"https://evil.test/user/profile.php?id=123","name":"Test Student"}"""
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive(identity).toString())
        assertEquals(View.GONE, dialog.findViewById<Button>(R.id.sso_continue_moodle).visibility)
        assertTrue(sessions.isEmpty())
    }

    @Test fun portalSsoCompletesWithTwoOriginScopedServiceSessions() {
        val dialog = launch()
        val web = dialog.web()
        CookieManager.getInstance().setCookie("https://courses.uit.edu.vn", "MoodleSession=synthetic-session")
        CookieManager.getInstance().setCookie("https://courses.uit.edu.vn", "KEYCLOAK_IDENTITY=synthetic-identity; Domain=.uit.edu.vn")
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        val identity = """{"sesskey":"synthetic-key","origin":"https://courses.uit.edu.vn","profileHref":"https://courses.uit.edu.vn/user/profile.php?id=123","name":"Test Student"}"""
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive(identity).toString())
        CookieManager.getInstance().setCookie("https://portal.uit.edu.vn", "ums_portal_session=synthetic-portal")
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue("true")
        val result = sessions.single()
        assertEquals(listOf(vn.edu.uit.mobile.model.PortalType.MOODLE, vn.edu.uit.mobile.model.PortalType.UIT_PORTAL), result.map { it.account.portal })
        assertEquals("MoodleSession=synthetic-session", result[0].cookie)
        assertEquals("ums_portal_session=synthetic-portal", result[1].cookie)
        assertFalse(dialog.isShowing)
    }
}
