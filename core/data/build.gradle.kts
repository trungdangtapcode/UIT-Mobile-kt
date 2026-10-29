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
    implementation(libs.work)
    implementation(libs.core.ktx)
}
