package vn.edu.uit.mobile.auth

import android.content.Context
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.WebView
import java.security.MessageDigest
import java.time.Instant

/** Explicit-copy support data: no URL path/query, credentials, cookies or account identity. */
internal object SsoTlsDiagnostics {
    fun report(context: Context, error: SslError): String {
        val host = Uri.parse(error.url).host?.takeIf { it in setOf("uma.uit.edu.vn", "sso.uit.edu.vn") }.orEmpty()
        val conditions = listOf(
            SslError.SSL_NOTYETVALID to "NOT_YET_VALID",
            SslError.SSL_EXPIRED to "EXPIRED",
            SslError.SSL_IDMISMATCH to "HOSTNAME_MISMATCH",
            SslError.SSL_UNTRUSTED to "UNTRUSTED",
            SslError.SSL_DATE_INVALID to "DATE_INVALID",
            SslError.SSL_INVALID to "INVALID"
        ).filter { error.hasError(it.first) }.joinToString(",") { it.second }
        val certificate = error.certificate
        val bytes = runCatching { SslCertificate.saveState(certificate)?.getByteArray("x509-certificate") }.getOrNull()
        val fingerprint = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }.orEmpty()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val webview = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull().orEmpty()
        return listOf(
            "UIT HTTPS diagnostics", "host=$host", "sslErrors=$conditions", "androidApi=${Build.VERSION.SDK_INT}", "webView=$webview", "appVersion=$version", "deviceUtc=${Instant.now()}",
            "certificateNotBefore=${certificate?.validNotBeforeDate?.toInstant() ?: ""}", "certificateNotAfter=${certificate?.validNotAfterDate?.toInstant() ?: ""}", "certificateSha256=$fingerprint"
        ).joinToString("\n")
    }
}
