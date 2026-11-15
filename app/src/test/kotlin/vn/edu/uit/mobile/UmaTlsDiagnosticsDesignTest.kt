package vn.edu.uit.mobile

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.Button
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import vn.edu.uit.mobile.auth.UmaSsoLoginController

/** Explicit host error-state render, using captured public certificate metadata; not a live WebView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi-rVN-w390dp-h844dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UmaTlsDiagnosticsDesignTest {
    @Test fun errorActionCopiesTheActualCertificateFingerprintWithoutCallbackParameters() {
        val path = System.getProperty("uit.uma.capture")
        assumeTrue(path != null && File(path, "chain-0.crt").isFile)
        val root = File(requireNotNull(path))
        val certificate = File(root, "chain-0.crt").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            UmaSsoLoginController(activity).launch(onResult = { fail("No sign-in is performed") }, onError = {})
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            fun find(view: android.view.View): WebView? {
                if (view is WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val web = requireNotNull(find(dialog.window!!.decorView))
            val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
            web.webViewClient.onReceivedSslError(web, handler, SslError(SslError.SSL_UNTRUSTED, SslCertificate(certificate), "https://uma.uit.edu.vn/oidc/callback/?code=private-code"))
            shadowOf(Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            val copy = dialog.findViewById<Button>(vn.edu.uit.mobile.core.auth.R.id.sso_copy_diagnostics)
            assertEquals("Sao chép chẩn đoán kết nối", copy.text.toString())
            assertTrue(copy.isShown && copy.width > 0 && copy.height > 0)
            // Draw the actual native dialog synchronously; no asynchronous window pixel copy.
            val decor = dialog.window!!.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            File(root, "uma-tls-error-390.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            copy.performClick()
            val report = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(report.contains("certificateSha256=$expected"))
            assertFalse(report.contains("private-code"))
            assertTrue(shadowOf(handler).wasCancelCalled())
            assertFalse(shadowOf(handler).wasProceedCalled())
            dialog.dismiss()
        } finally {
            activity.finish()
        }
    }
}
