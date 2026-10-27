package vn.edu.uit.mobile.daa

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import vn.edu.uit.mobile.network.PortalJson

class LiveProfileResponseTest {
    @Test fun `private authorized profile and insurance captures pass production parsers`() {
        val directory = System.getenv("UIT_LIVE_PROFILE_FIXTURES")
        assumeTrue(!directory.isNullOrBlank(), "Private profile captures were not supplied")
        val root = Path.of(requireNotNull(directory))
        val profile = UitProfileParser.parse(Files.readString(root.resolve("profile.html")))
        val insurance = UitProfileParser.insurance(PortalJson.parseToJsonElement(Files.readString(root.resolve("insurance.json"))))
        assertTrue(profile.studentCode.isNotBlank())
        assertTrue(profile.fullName.isNotBlank())
        assertTrue(profile.sections.any { it.id == "academic" })
        assertEquals("insurance", insurance.id)
    }
}
