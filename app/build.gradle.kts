plugins { alias(libs.plugins.android.application); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "vn.edu.uit.mobile"
    compileSdk = 35
    defaultConfig { applicationId = "vn.edu.uit.mobile"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "0.1.6"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST") }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    androidTestImplementation(testFixtures(project(":core:domain")))
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    testImplementation(project(":core:network:daa"))
    testImplementation(project(":core:network:forum"))
    testImplementation(libs.ktor.core)
    testImplementation(libs.ktor.okhttp)
    androidTestImplementation(project(":core:network:forum"))
    androidTestImplementation(libs.ktor.core)
    androidTestImplementation(libs.ktor.mock)
    testImplementation(libs.junit4)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(project(":core:data")); implementation(project(":core:auth")); implementation(project(":core:database"))
    implementation(project(":core:designsystem")); implementation(project(":core:domain"))
    implementation(project(":feature:auth")); implementation(project(":feature:dashboard")); implementation(project(":feature:schedule"))
    implementation(project(":feature:courses")); implementation(project(":feature:academic")); implementation(project(":feature:forum"))
    implementation(platform(libs.compose.bom)); implementation(libs.compose.material3); implementation(libs.compose.ui)
    implementation(libs.activity.compose); implementation(libs.lifecycle.compose); implementation(libs.lifecycle.viewmodel)
    implementation(libs.koin.android); implementation(libs.work); implementation(libs.core.ktx)
}

tasks.withType<Test>().configureEach {
    // Compose rendering uses the debug-only host Activity supplied by ui-test-manifest.
    if (name.contains("Release")) exclude("**/AssistantDesignTest.class", "**/AssistantLiveDesignTest.class", "**/ForumDigestLiveDesignTest.class", "**/AnnouncementsDesignTest.class", "**/AnnouncementsLiveDesignTest.class", "**/WorkspaceDesignTest.class", "**/WidgetNavigationTest.class", "**/StudyDesignTest.class", "**/CalendarExportDesignTest.class", "**/FreeTimeDesignTest.class", "**/WorkspaceSearchDesignTest.class", "**/OfflineDownloadsDesignTest.class", "**/BookmarksDesignTest.class", "**/TimetableImageDesignTest.class", "**/WorkloadDesignTest.class", "**/RevisionDesignTest.class", "**/PracticeDesignTest.class", "**/CodeTutorDesignTest.class", "**/ReadingDesignTest.class", "**/KnowledgeDesignTest.class", "**/ForumDesignTest.class", "**/ForumLiveDesignTest.class", "**/ForumAccountDesignTest.class", "**/ForumAccountLiveTest.class")
    val accountLive = providers.gradleProperty("forumAccountLive").getOrElse("false").toBoolean()
    systemProperty("uit.forum.account.live", accountLive.toString())
    systemProperty("uit.forum.account.dir", layout.buildDirectory.dir("outputs/forum-account-private").get().asFile.absolutePath)
    outputs.upToDateWhen { !accountLive }
    outputs.doNotCacheIf("Authenticated Forum test must not be reused") { accountLive }
    val forumLive = providers.gradleProperty("forumLive").getOrElse("false").toBoolean()
    systemProperty("uit.forum.live", forumLive.toString())
    systemProperty("uit.forum.live.dir", layout.buildDirectory.dir("outputs/forum-live").get().asFile.absolutePath)
    outputs.upToDateWhen { !forumLive }
    outputs.doNotCacheIf("Live Forum verification must contact the current server") { forumLive }
    val recordUi = providers.gradleProperty("recordUi").getOrElse("false").toBoolean()
    systemProperty("roborazzi.test.record", recordUi)
    if (recordUi) systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    if (recordUi) outputs.dir(rootProject.layout.projectDirectory.dir("docs/design/previews"))
    systemProperty("uit.preview.dir", rootProject.layout.projectDirectory.dir("docs/design/previews").asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    val live = providers.gradleProperty("announcementsLive").getOrElse("false").toBoolean()
    systemProperty("uit.announcements.live", live.toString())
    systemProperty("uit.announcements.output", layout.buildDirectory.dir("outputs/announcements-live").get().asFile.absolutePath)
    outputs.upToDateWhen { !live }
    outputs.doNotCacheIf("Public announcement live evidence must contact UIT") { live }
}

tasks.withType<Test>().configureEach {
    val preview = providers.gradleProperty("assistantPreview").getOrElse("false").toBoolean()
    systemProperty("uit.assistant.preview", preview.toString())
    systemProperty("uit.assistant.capture.input", rootProject.layout.projectDirectory.dir("core/data/build/outputs/assistant-live").asFile.absolutePath)
    systemProperty("uit.assistant.capture.output", layout.buildDirectory.dir("outputs/assistant-live").get().asFile.absolutePath)
    outputs.upToDateWhen { !preview }
    outputs.doNotCacheIf("Assistant public-source render evidence is explicitly requested") { preview }
}
