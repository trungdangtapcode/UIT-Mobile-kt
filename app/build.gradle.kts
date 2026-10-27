plugins { alias(libs.plugins.android.application); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "vn.edu.uit.mobile"
    compileSdk = 35
    defaultConfig { applicationId = "vn.edu.uit.mobile"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.1.2"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST") }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(project(":core:data")); implementation(project(":core:auth")); implementation(project(":core:database"))
    implementation(project(":core:designsystem")); implementation(project(":core:domain"))
    implementation(project(":feature:auth")); implementation(project(":feature:dashboard")); implementation(project(":feature:schedule"))
    implementation(project(":feature:courses")); implementation(project(":feature:academic"))
    implementation(platform(libs.compose.bom)); implementation(libs.compose.material3); implementation(libs.compose.ui)
    implementation(libs.activity.compose); implementation(libs.lifecycle.compose); implementation(libs.lifecycle.viewmodel)
    implementation(libs.koin.android); implementation(libs.work); implementation(libs.core.ktx)
}

tasks.withType<Test>().configureEach {
    // Compose rendering uses the debug-only host Activity supplied by ui-test-manifest.
    if (name.contains("Release")) exclude("**/WorkspaceDesignTest.class")
    val recordUi = providers.gradleProperty("recordUi").getOrElse("false").toBoolean()
    systemProperty("roborazzi.test.record", recordUi)
    if (recordUi) outputs.dir(rootProject.layout.projectDirectory.dir("docs/design/previews"))
    systemProperty("uit.preview.dir", rootProject.layout.projectDirectory.dir("docs/design/previews").asFile.absolutePath)
}
