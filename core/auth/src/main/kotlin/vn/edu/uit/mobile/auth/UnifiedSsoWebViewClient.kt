package vn.edu.uit.mobile.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import kotlinx.serialization.json.*
import vn.edu.uit.mobile.model.*

/** A single short-lived WebView owns the CAS session; credentials never enter app code. */
class UnifiedSsoLoginController(private val activity: Activity) {
    private val allowed = setOf("courses.uit.edu.vn", "coursesold.uit.edu.vn", "daa.uit.edu.vn", "sso.uit.edu.vn")

    @SuppressLint("SetJavaScriptEnabled")
    fun launch(baseUrl: String = "https://courses.uit.edu.vn", onResult: (List<PortalSession>) -> Unit, onError: (String) -> Unit) {
        require(baseUrl in setOf("https://courses.uit.edu.vn", "https://coursesold.uit.edu.vn", "https://coursesold.uit.edu.vn/sdh"))
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        val web = WebView(activity)
        var closed = false
        val captured = mutableListOf<PortalSession>()
        var visitingDaa = false
        fun finish(success: Boolean) {
            if (closed) return
            closed = true
            web.stopLoading()
            web.clearHistory()
            web.clearCache(true)
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            dialog.dismiss()
            if (success) onResult(captured.toList())
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || uri.host !in allowed || uri.userInfo != null
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme != "https" || uri.host !in allowed || uri.userInfo != null) return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed) return
                val uri = Uri.parse(url)
                if (!visitingDaa && uri.host == Uri.parse(baseUrl).host && (baseUrl.endsWith("/sdh") == uri.path.orEmpty().startsWith("/sdh"))) {
                    view.evaluateJavascript(IDENTITY_SCRIPT) { result ->
                        if (closed || visitingDaa) return@evaluateJavascript
                        val identity = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject }.getOrNull() ?: return@evaluateJavascript
                        val sesskey = identity["sesskey"]?.jsonPrimitive?.content.orEmpty()
                        val origin = identity["origin"]?.jsonPrimitive?.content.orEmpty()
                        val profile = identity["profileHref"]?.jsonPrimitive?.content.orEmpty()
                        val userId = Uri.parse(profile).getQueryParameter("id")?.toLongOrNull() ?: 0
                        if (sesskey.isBlank() || userId <= 0 || origin != "https://${uri.host}") return@evaluateJavascript
                        val cookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
                        if (!cookie.contains("MoodleSession=")) return@evaluateJavascript
                        val name = identity["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "UIT $userId" }
                        val account = Account("$baseUrl:$userId", baseUrl, userId, name, PortalType.MOODLE)
                        captured += PortalSession(account, cookie, sesskey)
                        if (baseUrl == "https://courses.uit.edu.vn") {
                            visitingDaa = true
                            view.loadUrl("https://daa.uit.edu.vn/user/login")
                        } else {
                            finish(true)
                        }
                    }
                } else if (visitingDaa && uri.host == "daa.uit.edu.vn") {
                    val cookie = CookieManager.getInstance().getCookie("https://daa.uit.edu.vn").orEmpty()
                    view.evaluateJavascript("""JSON.stringify({logged: !!document.querySelector('body.logged-in, a[href*=logout]'), cas: document.querySelector('a[href*="/cas"], a[href*="sso.uit.edu.vn"]')?.href || ''})""") { value ->
                        if (closed) return@evaluateJavascript
                        val info = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(value).jsonPrimitive.content).jsonObject }.getOrNull() ?: return@evaluateJavascript
                        if (info["logged"]?.jsonPrimitive?.booleanOrNull == true && Regex("(?:^|;\\s*)S?SESS[^=]*=").containsMatchIn(cookie)) {
                            val moodle = captured.first().account
                            captured += PortalSession(Account("daa:${moodle.userId}", "https://daa.uit.edu.vn", moodle.userId, moodle.displayName, PortalType.DAA), cookie)
                            finish(true)
                        } else {
                            val cas = info["cas"]?.jsonPrimitive?.content.orEmpty()
                            val target = Uri.parse(cas)
                            if (target.scheme == "https" && target.host in allowed && cas != url) view.loadUrl(cas)
                        }
                    }
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !closed) onError("The UIT sign-in page could not be loaded")
            }
        }
        dialog.setContentView(web)
        dialog.setOnCancelListener { finish(false) }
        // Start each login with an empty browser jar; captured portal sessions live in the encrypted vault.
        CookieManager.getInstance().removeAllCookies {
            if (!closed) {
                dialog.show()
                viewSize(dialog)
                web.loadUrl("$baseUrl/login/index.php")
            }
        }
    }
    private fun viewSize(dialog: Dialog) {
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    companion object {
        private val IDENTITY_SCRIPT = """JSON.stringify((() => { const cfg=window.M?.cfg||{};const info=document.querySelector('.logininfo');return {sesskey:String(cfg.sesskey||''),origin:location.origin,profileHref:info?.querySelector('a[href*="/user/profile.php"]')?.href||'',name:info?.querySelector('a')?.textContent||''};})())"""
    }
}
