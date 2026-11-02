plugins { id("uit.jvm") }
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:network:common"))
    implementation(libs.ktor.okhttp)
    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.ktor.mock)
    testImplementation(libs.coroutines.test)
}

tasks.register<JavaExec>("researchPublicForum") {
    group = "verification"
    description = "Manually verify bounded anonymous Forum GETs and export aggregate evidence."
    dependsOn(tasks.named("testClasses"))
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("vn.edu.uit.mobile.forum.network.ForumLiveResearch")
    args(rootProject.layout.projectDirectory.file("docs/research/evidence/uit-forum-public.json").asFile.absolutePath)
}
