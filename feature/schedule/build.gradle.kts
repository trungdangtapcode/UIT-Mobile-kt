plugins { id("uit.compose") }
dependencies {
    implementation(libs.activity.compose)
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
}
