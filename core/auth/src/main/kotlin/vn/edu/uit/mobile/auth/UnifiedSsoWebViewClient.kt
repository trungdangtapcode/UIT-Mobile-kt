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
import vn.edu.uit.mobile.model.*

/** A single short-lived WebView owns the UIT SSO session; credentials never enter app code. */
class UnifiedSsoLoginController(private val activity: Activity) {
    private val allowed = setOf("courses.uit.edu.vn", "coursesold.uit.edu.vn", "daa.uit.edu.vn", "sso.uit.edu.vn", "portal.uit.edu.vn")

    @SuppressLint("SetJavaScriptEnabled")
    fun launch(baseUrl: String = "https://courses.uit.edu.vn", onResult: (List<PortalSession>) -> Unit, onError: (String) -> Unit) {
        require(baseUrl in setOf("https://courses.uit.edu.vn", "https://coursesold.uit.edu.vn", "https://coursesold.uit.edu.vn/sdh"))
        WebView.setWebContentsDebuggingEnabled(activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val browser = SsoBrowserDialog(activity)
        val web = browser.web
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var failed = false
        var rendererDestroyed = false
        var jarReady = false
        val captured = mutableListOf<PortalSession>()
        var visitingPortal = false
        var timeout: Runnable? = null
        fun cancelTimeout() {
            timeout?.let(handler::removeCallbacks)
            timeout = null
        }
        fun fail(message: String) {
            if (closed || failed) return
            failed = true
            cancelTimeout()
            browser.error(message)
            onError(message)
        }
        fun armTimeout() {
            cancelTimeout()
            timeout = Runnable {
                fail(activity.getString(R.string.sso_timeout))
                if (!closed && !rendererDestroyed) web.stopLoading()
            }.also { handler.postDelayed(it, 30_000) }
        }
        fun finish(success: Boolean) {
            if (closed) return
            closed = true
            cancelTimeout()
            if (!rendererDestroyed) {
                web.stopLoading()
                web.clearHistory()
                web.clearCache(true)
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            CookieManager.getInstance().removeAllCookies(null)
            browser.dialog.dismiss()
            if (success) onResult(captured.toList())
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val blocked = !trusted(uri)
                if (blocked && request.isForMainFrame) fail(activity.getString(R.string.sso_blocked))
                return blocked
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (!trusted(uri)) return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (closed) return
                if (!trusted(Uri.parse(url))) {
                    fail(activity.getString(R.string.sso_blocked))
                    view.stopLoading()
                    return
                }
                failed = false
                browser.loading(activity.getString(R.string.sso_loading, Uri.parse(url).host))
                armTimeout()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || failed || view.url != url) return
                val uri = Uri.parse(url)
                if (!trusted(uri)) return
                cancelTimeout()
                browser.ready(activity.getString(if (visitingPortal) R.string.sso_portal_connecting else R.string.sso_ready, uri.host))
                if (!visitingPortal && uri.host == Uri.parse(baseUrl).host && (baseUrl.endsWith("/sdh") == uri.path.orEmpty().startsWith("/sdh"))) {
                    view.evaluateJavascript(IDENTITY_SCRIPT) { result ->
                        if (closed || failed || visitingPortal || view.url != url) return@evaluateJavascript
                        val identity = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject }.getOrNull() ?: return@evaluateJavascript
                        val sesskey = identity["sesskey"]?.jsonPrimitive?.content.orEmpty()
                        val origin = identity["origin"]?.jsonPrimitive?.content.orEmpty()
                        val profile = identity["profileHref"]?.jsonPrimitive?.content.orEmpty()
                        val profileUri = Uri.parse(profile)
                        val userId = if (trusted(profileUri) && profileUri.host == uri.host && profileUri.path == "${Uri.parse(baseUrl).path.orEmpty()}/user/profile.php") profileUri.getQueryParameter("id")?.toLongOrNull() ?: 0 else 0
                        if (sesskey.isBlank() || userId <= 0 || origin != "https://${uri.host}") return@evaluateJavascript
                        val cookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty().split(';').map(String::trim).filter { it.startsWith("MoodleSession=") }.joinToString("; ")
                        if (!cookie.contains("MoodleSession=")) return@evaluateJavascript
                        val name = identity["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "UIT $userId" }
                        val account = Account("$baseUrl:$userId", baseUrl, userId, name, PortalType.MOODLE)
                        captured += PortalSession(account, cookie, sesskey)
                        if (baseUrl == "https://courses.uit.edu.vn") {
                            visitingPortal = true
                            browser.allowMoodleOnly()
                            view.loadUrl("https://portal.uit.edu.vn/api/auth/login")
                        } else {
                            finish(true)
                        }
                    }
                } else if (visitingPortal && uri.host == "portal.uit.edu.vn") {
                    view.evaluateJavascript("""Boolean(document.querySelector('a[href="/sinh-vien/ho-so"]') && !document.querySelector('main[data-screen="auth"]'))""") { value ->
                        if (closed || failed || view.url != url || value != "true") return@evaluateJavascript
                        val cookie = CookieManager.getInstance().getCookie("https://portal.uit.edu.vn").orEmpty()
                            .split(';').map(String::trim).filter { it.startsWith("ums_portal_session=") }.joinToString("; ")
                        if (cookie.substringAfter('=', "").isBlank()) return@evaluateJavascript
                        val moodle = captured.first().account
                        captured += PortalSession(Account("portal:${moodle.userId}", "https://portal.uit.edu.vn", moodle.userId, moodle.displayName, PortalType.UIT_PORTAL), cookie)
                        finish(true)
                    }
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
                cancelTimeout()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                rendererDestroyed = true
                if (!closed) {
                    failed = true
                    val message = activity.getString(R.string.sso_renderer_error)
                    browser.error(message, canRetry = false)
                    onError(message)
                }
                return true
            }
        }
        fun start() {
            if (closed || rendererDestroyed) return
            failed = false
            browser.loading(activity.getString(R.string.sso_preparing))
            armTimeout()
            val target = if (visitingPortal) "https://portal.uit.edu.vn/api/auth/login" else "$baseUrl/login/index.php"
            if (jarReady) {
                web.loadUrl(target)
            } else {
                // Do not show a blank window while the asynchronous cookie reset completes.
                CookieManager.getInstance().removeAllCookies {
                    if (!closed && !failed) {
                        jarReady = true
                        web.loadUrl(target)
                    }
                }
            }
        }
        browser.show(onClose = { finish(false) }, onRetry = { start() }, onContinue = { if (captured.isNotEmpty()) finish(true) })
        start()
    }
    private fun trusted(uri: Uri): Boolean = uri.scheme == "https" && uri.host in allowed && uri.userInfo == null && uri.port in setOf(-1, 443)
    companion object {
        private val IDENTITY_SCRIPT = """JSON.stringify((() => { const cfg=window.M?.cfg||{};const links=Array.from(document.querySelectorAll('.logininfo a[href*="/user/profile.php"], .usermenu a[href*="/user/profile.php"]'));const profile=links.find(a=>{try{const u=new URL(a.href);return u.origin===location.origin&&Number(u.searchParams.get('id'))>0;}catch{return false;}});return {sesskey:String(cfg.sesskey||''),origin:location.origin,profileHref:profile?.href||'',name:profile?.textContent||''};})())"""
    }
}
