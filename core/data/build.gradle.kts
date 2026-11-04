plugins { id("uit.android.library") }
dependencies {
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
