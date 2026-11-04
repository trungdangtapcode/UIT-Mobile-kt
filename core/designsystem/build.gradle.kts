plugins { id("uit.compose") }
dependencies {
    api(project(":core:common"))
    implementation(project(":core:domain"))
    api(libs.compose.material3)
    api(libs.compose.icons)
}
