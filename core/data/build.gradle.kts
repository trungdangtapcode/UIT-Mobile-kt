plugins { id("uit.android.library") }
dependencies {
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
