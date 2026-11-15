import java.io.File
import java.util.UUID

plugins { id("uit.android.library") }

tasks.register("writeChatGptProbeClasspath") {
    dependsOn("compileDebugUnitTestKotlin")
    doLast {
        val test = tasks.named<Test>("testDebugUnitTest").get()
        val output = layout.buildDirectory.file("outputs/chatgpt-native/classpath.txt").get().asFile
        output.parentFile.mkdirs()
        val snapshot = output.parentFile.resolve("runtime-${UUID.randomUUID()}").apply { mkdirs() }
        val root = rootProject.projectDir.toPath()
        val paths = (test.testClassesDirs + test.classpath).files.mapIndexed { index, source ->
            if (source.exists() && source.toPath().startsWith(root)) {
                val target = snapshot.resolve("$index-${source.name}")
                if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
                target.absolutePath
            } else source.absolutePath
        }
        output.writeText(paths.joinToString(File.pathSeparator))
    }
}
dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.ksoup)
    implementation(libs.ktor.okhttp)
    testImplementation(libs.ktor.mock)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:auth"))
    implementation(project(":core:network:moodle"))
    implementation(project(":core:network:daa"))
    implementation(project(":core:network:forum"))
    implementation(libs.work)
    implementation(libs.core.ktx)
}

tasks.withType<Test>().configureEach {
    val live = providers.environmentVariable("UIT_CHATGPT_NETWORK_LIVE").orElse("0")
    inputs.property("uit.chatgpt.network.live", live)
    outputs.upToDateWhen { live.get() != "1" }
    outputs.doNotCacheIf("Explicit keyless transport check must reach the current public endpoint") { live.get() == "1" }
}

tasks.withType<Test>().configureEach {
    val live = providers.environmentVariable("UIT_CHATGPT_RESPONSE_LIVE").orElse("0")
    inputs.property("uit.chatgpt.response.live", live)
    outputs.upToDateWhen { live.get() != "1" }
    outputs.doNotCacheIf("Explicit real Responses check must not reuse a cached result") { live.get() == "1" }
}

tasks.withType<Test>().configureEach {
    val live = providers.gradleProperty("assistantLive").getOrElse("false").toBoolean()
    systemProperty("uit.assistant.live", live.toString())
    systemProperty("uit.assistant.live.output", layout.buildDirectory.dir("outputs/assistant-live").get().asFile.absolutePath)
    outputs.upToDateWhen { !live }
    outputs.doNotCacheIf("Live assistant evaluation must use current sources and model") { live }
}

tasks.withType<Test>().configureEach {
    val live = providers.gradleProperty("retrievalLive").getOrElse("false").toBoolean()
    systemProperty("uit.retrieval.live", live.toString())
    systemProperty("uit.retrieval.output", layout.buildDirectory.dir("outputs/retrieval-live").get().asFile.absolutePath)
    outputs.upToDateWhen { !live }
    outputs.doNotCacheIf("Explicit retrieval evaluation must call the real embedding provider") { live }
}

tasks.withType<Test>().configureEach {
    val replay = providers.gradleProperty("retrievalReplay").getOrElse("false").toBoolean()
    systemProperty("uit.retrieval.replay", replay.toString())
    outputs.upToDateWhen { !replay }
    outputs.doNotCacheIf("Explicit local retrieval ablation must use current code and captured source") { replay }
}
