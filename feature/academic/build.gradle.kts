plugins { id("uit.compose") }
dependencies {
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
}
