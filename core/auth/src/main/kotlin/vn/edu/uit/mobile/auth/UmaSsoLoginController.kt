package vn.edu.uit.mobile.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.core.auth.R
import vn.edu.uit.mobile.model.UmaSession

/** UMA's own OIDC relying-party session. Portal/Moodle cookies are never imported. */
class UmaSsoLoginController(private val activity: Activity) {
    @SuppressLint("SetJavaScriptEnabled")
    fun launch(onResult: (UmaSession) -> Unit, onError: (String) -> Unit) {
        val browser = SsoBrowserDialog(activity, R.string.uma_sign_in)
        val web = browser.web
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var failed = false
        var destroyed = false
        var timeout: Runnable? = null
        fun cancelTimeout() {
            timeout?.let(handler::removeCallbacks)
            timeout = null
        }
        fun fail(message: String, retry: Boolean = true) {
            if (closed || failed) return
            failed = true
            cancelTimeout()
            browser.error(message, retry)
            onError(message)
        }
        fun finish(result: UmaSession? = null) {
            if (closed) return
            closed = true
            cancelTimeout()
            if (!destroyed) {
                web.stopLoading()
                web.clearHistory()
                web.clearCache(true)
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            CookieManager.getInstance().removeAllCookies(null)
            browser.dialog.dismiss()
            result?.let(onResult)
        }
        fun trusted(uri: Uri) = uri.scheme == "https" && uri.host in setOf("uma.uit.edu.vn", "sso.uit.edu.vn") && uri.userInfo == null && uri.port in setOf(-1, 443)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = !trusted(request.url) || forbidden(request.url)
                if (blocked && request.isForMainFrame) fail(activity.getString(R.string.sso_blocked))
                return blocked
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!trusted(request.url) || forbidden(request.url) || request.method !in setOf("GET", "HEAD") && !(request.url.host == "sso.uit.edu.vn" && request.method == "POST")) return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (closed) return
                if (!trusted(Uri.parse(url)) || forbidden(Uri.parse(url))) {
                    view.stopLoading()
                    fail(activity.getString(R.string.sso_blocked))
                    return
                }
                failed = false
                browser.loading(activity.getString(R.string.sso_loading, Uri.parse(url).host))
                cancelTimeout()
                timeout = Runnable {
                    fail(activity.getString(R.string.sso_timeout))
                    if (!closed && !destroyed) web.stopLoading()
                }.also { handler.postDelayed(it, 45000) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || failed || view.url != url || !trusted(Uri.parse(url))) return
                cancelTimeout()
                browser.ready(activity.getString(R.string.sso_ready, Uri.parse(url).host))
                if (Uri.parse(url).host != "uma.uit.edu.vn" || Uri.parse(url).path != "/uma/nhung/") return
                view.evaluateJavascript("""JSON.stringify({ready:!!document.querySelector('#ask #q')&&document.querySelector('#ho_so')?.value==='toi',csrf:document.querySelector('[name=csrfmiddlewaretoken]')?.value||''})""") { result ->
                    if (closed || failed || view.url != url) return@evaluateJavascript
                    val obj = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject }.getOrNull() ?: return@evaluateJavascript
                    if (obj["ready"]?.jsonPrimitive?.booleanOrNull != true) return@evaluateJavascript
                    val csrf = obj["csrf"]?.jsonPrimitive?.content.orEmpty()
                    val cookie = CookieManager.getInstance().getCookie("https://uma.uit.edu.vn").orEmpty().split(';').map(String::trim).filter { it.substringBefore('=') in setOf("ums2_sessionid", "ums2_csrftoken") }.joinToString("; ")
                    if (csrf.matches(Regex("[A-Za-z0-9]{32,64}")) && cookie.contains("ums2_sessionid=") && cookie.contains("ums2_csrftoken=")) finish(UmaSession(cookie, csrf))
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(activity.getString(R.string.sso_network_error, error.errorCode))
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) fail(activity.getString(R.string.sso_http_error, errorResponse.statusCode))
            }
            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                fail(activity.getString(R.string.sso_ssl_error, Uri.parse(error.url).host.orEmpty()))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                destroyed = true
                fail(activity.getString(R.string.sso_renderer_error), false)
                return true
            }
        }
        fun start() {
            if (closed || destroyed) return
            failed = false
            browser.loading(activity.getString(R.string.sso_preparing))
            cancelTimeout()
            timeout = Runnable { fail(activity.getString(R.string.sso_timeout)) }.also { handler.postDelayed(it, 45000) }
            CookieManager.getInstance().removeAllCookies { if (!closed && !failed) web.loadUrl(LOGIN) }
        }
        browser.show(onClose = { finish() }, onRetry = ::start, onContinue = {})
        start()
    }
    private fun forbidden(uri: Uri) = Regex("(?i)(logout|dang-xuat|revoke|delete)").containsMatchIn(uri.path.orEmpty())
    companion object {
        private const val LOGIN = "https://uma.uit.edu.vn/oidc/authenticate/?next=%2Fuma%2Fnhung%2F%3Fsite%3Dportal%26trang%3D%252Fsinh-vien%252Ftkb"
    }
}
