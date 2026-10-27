package vn.edu.uit.mobile

import android.app.Application
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UitTlsConfigurationTest {
    @Test fun bundledPublicRootMatchesPublishedSectigoCertificate() {
        val resources = RuntimeEnvironment.getApplication().resources
        val certificate = resources.openRawResource(R.raw.sectigo_public_server_authentication_root_e46).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        assertEquals("c90f26f0fb1b4018b22227519b5ca2b53e2ca5b3be5cf18efe1bef47380c5383", fingerprint)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.basicConstraints >= 0)
        certificate.verify(certificate.publicKey)
        certificate.checkValidity()
    }

    @Test fun additionalRootOnlyAppliesToExactUitHosts() {
        val resources = RuntimeEnvironment.getApplication().resources
        val domains = mutableSetOf<String>()
        val anchors = mutableMapOf<String, MutableList<String>>()
        var config = ""
        resources.getXml(R.xml.network_security_config).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "base-config", "domain-config" -> {
                            config = xml.name
                            assertEquals("false", xml.getAttributeValue(null, "cleartextTrafficPermitted"))
                        }
                        "domain" -> {
                            assertEquals("domain-config", config)
                            assertNotEquals("true", xml.getAttributeValue(null, "includeSubdomains"))
                            domains += xml.nextText()
                        }
                        "certificates" -> anchors.getOrPut(config) { mutableListOf() } += xml.getAttributeValue(null, "src")
                    }
                }
            }
        }
        assertEquals(setOf("courses.uit.edu.vn", "coursesold.uit.edu.vn", "sso.uit.edu.vn", "daa.uit.edu.vn", "portal.uit.edu.vn"), domains)
        assertEquals(listOf("system"), anchors["base-config"])
        val scoped = requireNotNull(anchors["domain-config"])
        assertEquals(2, scoped.size)
        assertTrue("system" in scoped)
        assertFalse(anchors.values.flatten().contains("user"))
    }
}
