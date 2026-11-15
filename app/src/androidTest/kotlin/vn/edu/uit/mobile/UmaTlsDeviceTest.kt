package vn.edu.uit.mobile

import android.net.http.X509TrustManagerExtensions
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.auth.UmaSsoLoginController

/** Explicit public TLS research only: no credentials, sign-in submission or question request. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(29)
class UmaTlsDeviceTest {
    @Test fun legacyTrustAndProductionUmaWebViewAreVerifiedSeparately() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("umaTlsProbe") == "true")
        val expectScopedRoot = args.getString("umaTlsExpected") == "compatible"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "uma-tls").apply { mkdirs() }
        val factory = CertificateFactory.getInstance("X.509")
        fun certificate(name: String) = File(folder, name).inputStream().use { factory.generateCertificate(it) as X509Certificate }
        val chain = arrayOf(certificate("leaf.crt"), certificate("intermediate.crt"))
        val fingerprint = "c90f26f0fb1b4018b22227519b5ca2b53e2ca5b3be5cf18efe1bef47380c5383"
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val hasRoot = system.aliases().toList().any { alias ->
            MessageDigest.getInstance("SHA-256").digest(system.getCertificate(alias).encoded).joinToString("") { "%02x".format(it) } == fingerprint
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trust = X509TrustManagerExtensions(manager)
        fun accepted(host: String) = runCatching { trust.checkServerTrusted(chain, "ECDHE_ECDSA", host) }.isSuccess
        // Run before networking: a fetched cross-sign must not influence the missing-chain replay.
        val truncatedTrusted = accepted("uma.uit.edu.vn")
        val unrelatedTrusted = accepted("unrelated.example")
        fun https(host: String): String {
            val connection = URL("https://$host/uma/nhung/").openConnection() as HttpsURLConnection
            return try {
                connection.connectTimeout = 25000
                connection.readTimeout = 25000
                connection.instanceFollowRedirects = false
                connection.responseCode.toString()
            } catch (e: Exception) {
                generateSequence(e as Throwable) { it.cause }.take(6).joinToString(">") { it.javaClass.simpleName }
            } finally {
                connection.disconnect()
            }
        }
        val nativeUma = https("uma.uit.edu.vn")
        var formReached = false
        var browserPackage = ""
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            scenario.onActivity { activity ->
                UmaSsoLoginController(activity).launch(onResult = { fail("No credential is entered; a session must not be captured") }, onError = { errors += it })
                browserPackage = WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
                fun find(view: View): WebView? {
                    if (view is WebView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                web = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            }
            val browser = requireNotNull(web)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
            while (System.nanoTime() < deadline && errors.isEmpty() && !formReached) {
                val latch = CountDownLatch(1)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    browser.evaluateJavascript("location.hostname==='sso.uit.edu.vn' && !!document.querySelector('input[name=username]') && !!document.querySelector('input[type=password]')") { result ->
                        formReached = result == "true"
                        latch.countDown()
                    }
                }
                assertTrue(latch.await(15, TimeUnit.SECONDS))
                if (!formReached) Thread.sleep(500)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(folder, if (expectScopedRoot) "uma-sso-compatible.png" else "uma-sso-baseline.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val report = buildJsonObject {
            put("runtime", "Android emulator; actual platform TLS and production WebView controller")
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("webViewVersion", browserPackage)
            put("systemE46Present", hasRoot)
            put("expectedMode", if (expectScopedRoot) "compatible" else "baseline")
            put("missingCrossSignTrustedForUma", truncatedTrusted)
            put("missingCrossSignTrustedForUnrelatedHost", unrelatedTrusted)
            put("nativeUmaHttps", nativeUma)
            put("ssoFormReached", formReached)
            put("browserErrorCount", errors.size)
            put("browserCertificateError", errors.any { it.contains("certificate", true) })
            put("credentialsUsed", false)
            put("universityWrites", false)
        }
        File(folder, if (expectScopedRoot) "compatible-report.json" else "baseline-report.json").writeText(report.toString())
        println("UIT_UMA_TLS_REPORT $report")
        assertFalse("Unexpected E46 in this legacy-store test device", hasRoot)
        assertEquals("Domain policy does not match the requested missing-chain expectation", expectScopedRoot, truncatedTrusted)
        assertFalse("Compatibility root leaked to an unrelated host", unrelatedTrusted)
        if (expectScopedRoot) {
            assertEquals("200", nativeUma)
            assertTrue("Production WebView failed before the UIT password form", formReached)
            assertTrue(errors.isEmpty())
        }
    }
}
