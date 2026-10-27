package vn.edu.uit.mobile

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.uit.mobile.auth.UnifiedSsoLoginController

/** Public pages only: CI never receives a student's credentials or session. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(29)
class LiveSignInSmokeTest {
    @Test fun uitHttpsAndNativeWebViewReachSsoPasswordForm() {
        for (host in listOf("courses.uit.edu.vn", "sso.uit.edu.vn", "portal.uit.edu.vn")) {
            val connection = URL("https://$host/").openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                assertTrue("HTTPS failed for $host", connection.responseCode in 200..399)
            } finally {
                connection.disconnect()
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            scenario.onActivity { activity ->
                UnifiedSsoLoginController(activity).launch(onResult = { fail("A public page must not yield a session") }, onError = { errors += it })
                // The Dialog decor is the focused root after launch.
                val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
                fun find(view: View): WebView? {
                    if (view is WebView) return view
                    if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                    return null
                }
                web = windows.firstNotNullOfOrNull(::find)
            }
            val browser = requireNotNull(web)
            fun evaluate(script: String): String {
                val latch = CountDownLatch(1)
                var result = ""
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    browser.evaluateJavascript(script) {
                        result = it
                        latch.countDown()
                    }
                }
                assertTrue("WebView did not respond", latch.await(10, TimeUnit.SECONDS))
                return result
            }
            fun awaitScript(script: String) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (System.nanoTime() < deadline) {
                    assertTrue("Sign-in failed: ${errors.joinToString()}", errors.isEmpty())
                    if (evaluate(script) == "true") return
                    Thread.sleep(300)
                }
                fail("The expected public sign-in page did not render")
            }
            fun screenshot(name: String) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "auth-smoke").apply { mkdirs() }
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            awaitScript("Boolean(document.querySelector('a[href*=\"/auth/oauth2/login.php\"]'))")
            screenshot("moodle-login")
            evaluate("document.querySelector('a[href*=\"/auth/oauth2/login.php\"]').click(); true")
            awaitScript("location.hostname === 'sso.uit.edu.vn' && Boolean(document.querySelector('#username') && document.querySelector('#password'))")
            screenshot("sso-login")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val root = browser.rootView
                val close = root.findViewById<Button>(vn.edu.uit.mobile.core.auth.R.id.sso_close)
                assertNotNull(close)
                close.performClick()
            }
        }
    }
}
