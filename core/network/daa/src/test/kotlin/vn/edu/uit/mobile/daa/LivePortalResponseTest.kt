package vn.edu.uit.mobile.daa

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Opt-in validation of private local captures; never commit those responses. */
class LivePortalResponseTest {
    @Test fun `authenticated portal captures match production parsers`() {
        val directory = System.getenv("UIT_LIVE_FIXTURES")
        assumeTrue(!directory.isNullOrBlank(), "Private portal captures were not provided")
        val root = Path.of(requireNotNull(directory))
        fun read(name: String) = Files.readString(root.resolve(name))
        assertTrue(UitPortalParser.timetable(Json.parseToJsonElement(read("_api_sinh-vien_tkb_dang-ky.json"))).isNotEmpty())
        assertTrue(UitPortalParser.transcript(Json.parseToJsonElement(read("_api_sinh-vien_bang-diem.json"))).semesters.isNotEmpty())
        assertTrue(UitPortalParser.profile(read("_sinh-vien_ho-so.html")).studentId.isNotBlank())
    }
}
