plugins { id("uit.jvm") }
dependencies {
    api(project(":core:network:common"))
    api(project(":core:domain"))
    implementation(project(":core:network:forum"))
    implementation(libs.ktor.okhttp)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.mock)
}

tasks.register<JavaExec>("researchAnnouncements") {
    group = "verification"
    description = "Opt-in bounded public UIT announcement reads using the production adapters."
    dependsOn(tasks.named("testClasses"))
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("vn.edu.uit.mobile.daa.AnnouncementLiveResearch")
    args(rootProject.layout.projectDirectory.file("docs/research/evidence/uit-announcements-public.json").asFile.absolutePath)
}
