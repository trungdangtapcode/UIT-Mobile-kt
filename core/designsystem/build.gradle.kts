plugins { id("uit.compose") }
dependencies {
    api(project(":core:common"))
    api(libs.compose.material3)
    api(libs.compose.icons)
}
