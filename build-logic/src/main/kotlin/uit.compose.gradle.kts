plugins { id("uit.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android { buildFeatures { compose = true } }
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    implementation(platform(libs.findLibrary("compose-bom").get()))
    implementation(libs.findLibrary("compose-material3").get())
    implementation(libs.findLibrary("compose-ui").get())
    implementation(libs.findLibrary("lifecycle-compose").get())
    implementation(libs.findLibrary("lifecycle-viewmodel").get())
}
