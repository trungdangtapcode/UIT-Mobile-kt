plugins { id("uit.compose") }
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(libs.activity.compose)
}
