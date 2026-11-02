package vn.edu.uit.mobile.domain

import java.io.File

/** Exports authored synthetic PDF bytes for reproducible host and native renderer evidence. */
object ReadingFixtureData {
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args.single()).apply { mkdirs() }
        File(output, "reading-synthetic.pdf").writeBytes(ReadingFixtures.pdf)
        File(output, "reading-synthetic.sha256").writeText(ReadingFixtures.identity.version + "\n")
    }
}
