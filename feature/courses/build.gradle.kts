plugins { id("uit.compose") }
dependencies {
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
}
