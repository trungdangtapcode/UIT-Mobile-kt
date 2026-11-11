package vn.edu.uit.mobile.auth

import android.app.Activity
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.Button
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import vn.edu.uit.mobile.core.auth.R
import vn.edu.uit.mobile.model.UmaSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UmaSsoLoginTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val sessions = mutableListOf<UmaSession>()
    private val errors = mutableListOf<String>()

    @After fun close() {
        ShadowDialog.getLatestDialog()?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        activity.finish()
    }
    private fun launch(): android.app.Dialog {
        UmaSsoLoginController(activity).launch({ sessions += it }, { errors += it })
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog()
    }
    private fun android.app.Dialog.web(): WebView {
        fun find(v: View): WebView? {
            if (v is WebView) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return requireNotNull(find(window!!.decorView))
    }
    private fun request(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun loginEntryUsesTheUmaOidcClientAndTimeoutKeepsNativeRetryAvailable() {
        val d = launch()
        assertTrue(shadowOf(d.web()).lastLoadedUrl.startsWith("https://uma.uit.edu.vn/oidc/authenticate/?next="))
        shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, d.findViewById<Button>(R.id.sso_retry).visibility)
        assertTrue(sessions.isEmpty())
    }

    @Test fun onlyUmaAndSsoOriginsAreAllowedAndUmaMutationPostsAreBlocked() {
        val web = launch().web()
        for (u in listOf("http://uma.uit.edu.vn/", "https://uma.uit.edu.vn.evil.test/", "https://portal.uit.edu.vn/", "https://user@uma.uit.edu.vn/", "https://uma.uit.edu.vn/oidc/dang-xuat/")) assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(u)))
        assertNotNull(web.webViewClient.shouldInterceptRequest(web, request("https://uma.uit.edu.vn/uma/api/phan-hoi/", "POST")))
        assertNull(web.webViewClient.shouldInterceptRequest(web, request("https://sso.uit.edu.vn/realms/UIT/login-actions/authenticate", "POST")))
    }

    @Test fun successfulStudentPageCapturesOnlyUmaCookiesAndMaskedCsrf() {
        val d = launch()
        val web = d.web()
        val manager = CookieManager.getInstance()
        manager.setCookie("https://uma.uit.edu.vn", "ums2_sessionid=${"a".repeat(32)}")
        manager.setCookie("https://uma.uit.edu.vn", "ums2_csrftoken=${"b".repeat(32)}")
        manager.setCookie("https://uma.uit.edu.vn", "_ga=synthetic-analytics")
        manager.setCookie("https://uma.uit.edu.vn", "KEYCLOAK_IDENTITY=synthetic-identity; Domain=.uit.edu.vn")
        val url = "https://uma.uit.edu.vn/uma/nhung/?site=portal"
        web.loadUrl(url)
        web.webViewClient.onPageFinished(web, url)
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive("""{"ready":true,"csrf":"${"c".repeat(64)}"}""").toString())
        assertEquals("c".repeat(64), sessions.single().csrf)
        assertFalse(sessions.single().cookie.contains("_ga"))
        assertFalse(sessions.single().cookie.contains("KEYCLOAK"))
        assertFalse(d.isShowing)
    }

    @Test fun sslFailureNeverProceedsAndCloseDoesNotReturnACredential() {
        val d = launch()
        val web = d.web()
        val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
        web.webViewClient.onReceivedSslError(web, handler, SslError(SslError.SSL_UNTRUSTED, SslCertificate("UIT", "Synthetic CA", "", ""), "https://uma.uit.edu.vn/"))
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
        d.findViewById<Button>(R.id.sso_close).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        assertTrue(sessions.isEmpty())
        assertEquals(1, errors.size)
    }
}
